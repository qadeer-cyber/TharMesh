#!/usr/bin/env python3
"""Apply TharMesh migrations to a Supabase project over the Management API.

Why this exists: the Supabase CLI opens a direct PostgreSQL connection, which is
impossible from a network that only permits HTTPS. This submits each migration and
its history row through POST /v1/projects/{ref}/database/query.

Safety properties, each verified before this script was written:
  * a submission is ONE transaction, so the migration and its history INSERT commit
    or roll back together;
  * an inner COMMIT would end that transaction early, so each file's own top-level
    `begin;` / `commit;` is removed (only ever the first and last statement; plpgsql
    block openers are a bare `begin` with no semicolon and cannot match);
  * history rows use the repository version and name, written with the same
    statement the CLI itself uses, so `supabase db push` later sees them as applied;
  * execution stops at the first failure and never repairs or resets anything.

Usage:
  staging-apply-migrations.py --ref <project-ref> [--dry-run]

The target ref must be passed explicitly AND match --expect-ref, which defaults to
the staging project. Production is hard-blocked by ref.
"""
import argparse, glob, json, os, re, subprocess, sys

STAGING_REF = "uxntqgspxrepvhelhqfs"
PRODUCTION_REF = "owhmuxueacjbljhpmpua"      # never a valid target for this script

BEGIN = re.compile(r'^[ \t]*begin[ \t]*;[ \t]*$', re.I)
COMMIT = re.compile(r'^[ \t]*commit[ \t]*;[ \t]*$', re.I)


def strip_outer_transaction(sql: str, path: str) -> str:
    """Drop the file's own top-level BEGIN/COMMIT, refusing anything unexpected."""
    lines = sql.split("\n")
    marks = [i for i, l in enumerate(lines) if BEGIN.match(l) or COMMIT.match(l)]
    if not marks:
        return sql
    stmts = [i for i, l in enumerate(lines) if l.strip() and not l.strip().startswith("--")]
    begins = [i for i in marks if BEGIN.match(lines[i])]
    commits = [i for i in marks if COMMIT.match(lines[i])]
    if not (len(begins) == len(commits) == 1
            and begins[0] == stmts[0] and commits[0] == stmts[-1]):
        sys.exit(f"REFUSING {path}: transaction wrapper is not a single first/last pair")
    return "\n".join(l for i, l in enumerate(lines) if i not in (begins[0], commits[0]))


def submit(ref: str, sql: str, token: str, read_only: bool = False) -> tuple[bool, str]:
    body = json.dumps({"query": sql, "read_only": read_only})
    p = subprocess.run(
        ["curl", "-sS", "--max-time", "180", "-X", "POST",
         "-H", f"Authorization: Bearer {token}",
         "-H", "Content-Type: application/json",
         "--data-binary", "@-",
         f"https://api.supabase.com/v1/projects/{ref}/database/query"],
        input=body, capture_output=True, text=True)
    out = (p.stdout or "") + (p.stderr or "")
    return (p.returncode == 0 and '"message"' not in out), out.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", required=True, help="target project ref (must be staging)")
    ap.add_argument("--expect-ref", default=STAGING_REF)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    # Gate 4: explicit, fail-closed targeting.
    if a.ref == PRODUCTION_REF:
        sys.exit("REFUSED: that ref is PROTECTED PRODUCTION")
    if a.ref != a.expect_ref:
        sys.exit(f"REFUSED: --ref {a.ref} does not match --expect-ref {a.expect_ref}")

    token_path = os.path.expanduser("~/.supabase/access-token")
    token = open(token_path).read().strip() if os.path.exists(token_path) else ""
    if not token and not a.dry_run:
        sys.exit("REFUSED: no access token; run `supabase login` first")

    files = sorted(glob.glob("supabase/migrations/*.sql"))
    if len(files) != 15:
        sys.exit(f"REFUSED: expected 15 migrations, found {len(files)}")
    print(f"target ref : {a.ref}")
    print(f"migrations : {len(files)}  ({os.path.basename(files[0])} .. {os.path.basename(files[-1])})")
    print(f"mode       : {'DRY RUN (no request sent)' if a.dry_run else 'APPLY'}\n")

    if not a.dry_run:
        ok, out = submit(a.ref,
                         "create schema if not exists supabase_migrations;\n"
                         "create table if not exists supabase_migrations.schema_migrations"
                         " (version text not null primary key);\n"
                         "alter table supabase_migrations.schema_migrations"
                         " add column if not exists name text;\n"
                         "alter table supabase_migrations.schema_migrations"
                         " add column if not exists statements text[];", token)
        if not ok:
            sys.exit(f"STOPPED: could not create the history table\n{out[:400]}")
        print("history table ready (CLI-compatible schema)\n")

    applied = 0
    for path in files:
        base = os.path.basename(path)
        version = base.split("_")[0]
        name = base[len(version) + 1:-4]
        raw = open(path).read()
        body = strip_outer_transaction(raw, base)
        literal = raw.replace("'", "''")
        sql = ("begin;\n" + body +
               "\ninsert into supabase_migrations.schema_migrations(version, name, statements)"
               f" values ('{version}', '{name}', array['{literal}']);\ncommit;")
        if a.dry_run:
            print(f"  would apply {version} {name}  ({len(sql)} bytes, one transaction)")
            applied += 1
            continue
        ok, out = submit(a.ref, sql, token)
        if not ok:
            print(f"  FAILED  {version} {name}")
            print(f"\nSTOPPED at {base}. {applied} migration(s) applied and recorded.")
            print("The failed migration rolled back entirely: no objects, no history row.")
            print("No repair or reset attempted, by design.")
            print(f"\nserver said: {out[:600]}")
            return 1
        applied += 1
        print(f"  applied {version} {name}")

    print(f"\n{applied}/{len(files)} migrations {'would be ' if a.dry_run else ''}applied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
