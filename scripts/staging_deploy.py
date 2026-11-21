#!/usr/bin/env python3
"""Apply TharMesh migrations to the STAGING Supabase project over the Management API.

The Supabase CLI needs a direct PostgreSQL connection, which is impossible from a
network that permits HTTPS only, so each migration is submitted through
POST /v1/projects/{ref}/database/query.

Verified properties this relies on:
  * one submission is one transaction, so the migration and its history INSERT commit
    or roll back together (proved against staging with SET LOCAL / carried
    transaction_timestamp probes);
  * an inner COMMIT ends that transaction early, so each file's own top-level
    begin;/commit; is removed — only ever one pair occupying the first and last
    statement; a plpgsql block opener is a bare `begin` with no semicolon and can
    never match;
  * `statements` is recorded the way the real CLI records it: the file split into
    individual statements with semicolons stripped. split_statements() was verified
    to reproduce the CLI's arrays for all 15 migrations, byte for byte.

Targeting is hardcoded. There is no flag that can redirect this at another project.
"""
from __future__ import annotations

import argparse, hashlib, json, os, subprocess, sys
from typing import Callable

# ---------------------------------------------------------------- constants

STAGING_REF = "uxntqgspxrepvhelhqfs"        # the ONLY permitted target
PRODUCTION_REF = "owhmuxueacjbljhpmpua"     # must never be contacted for writes
API = "https://api.supabase.com/v1/projects"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(REPO, "scripts", "staging-migration-manifest.json")
MIGRATIONS_DIR = os.path.join(REPO, "supabase", "migrations")

HISTORY_DDL = (
    "create schema if not exists supabase_migrations;\n"
    "create table if not exists supabase_migrations.schema_migrations"
    " (version text not null primary key);\n"
    "alter table supabase_migrations.schema_migrations add column if not exists name text;\n"
    "alter table supabase_migrations.schema_migrations"
    " add column if not exists statements text[];"
)


class Outcome:
    """Result of one API submission. `ok` is only ever True for a proven success."""

    def __init__(self, ok: bool, rows=None, detail: str = "", uncertain: bool = False):
        self.ok, self.rows, self.detail, self.uncertain = ok, rows, detail, uncertain


class DeploymentError(RuntimeError):
    pass


class UncertainError(RuntimeError):
    """The request may or may not have committed. Never retry on this."""


# ---------------------------------------------------------------- SQL helpers

def split_statements(sql: str) -> list[str]:
    """Split SQL the way the Supabase CLI records it in `statements`.

    Verified to match the CLI's output exactly for all 15 migrations.
    """
    out: list[str] = []
    buf: list[str] = []
    i, n = 0, len(sql)
    while i < n:
        c = sql[i]
        if sql.startswith("--", i):
            j = sql.find("\n", i)
            j = n if j == -1 else j
            buf.append(sql[i:j]); i = j; continue
        if sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            j = n if j == -1 else j + 2
            buf.append(sql[i:j]); i = j; continue
        if c == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'":
                    if j + 1 < n and sql[j + 1] == "'":
                        j += 2; continue
                    j += 1; break
                j += 1
            buf.append(sql[i:j]); i = j; continue
        if c == "$":
            k = sql.find("$", i + 1)
            if k != -1 and (k == i + 1 or sql[i + 1:k].replace("_", "").isalnum()):
                tag = sql[i:k + 1]
                e = sql.find(tag, k + 1)
                if e != -1:
                    buf.append(sql[i:e + len(tag)]); i = e + len(tag); continue
        if c == ";":
            stmt = "".join(buf).strip()
            if stmt:
                out.append(stmt)
            buf = []; i += 1; continue
        buf.append(c); i += 1
    tail = "".join(buf).strip()
    if tail:
        out.append(tail)
    return out


def strip_outer_transaction(sql: str, label: str) -> str:
    """Remove the file's own top-level BEGIN/COMMIT, refusing anything unexpected."""
    lines = sql.split("\n")
    def is_begin(l): return l.strip().lower() in ("begin;",)
    def is_commit(l): return l.strip().lower() in ("commit;",)
    begins = [i for i, l in enumerate(lines) if is_begin(l)]
    commits = [i for i, l in enumerate(lines) if is_commit(l)]
    if not begins and not commits:
        return sql
    stmts = [i for i, l in enumerate(lines) if l.strip() and not l.strip().startswith("--")]
    if not (len(begins) == len(commits) == 1
            and begins[0] == stmts[0] and commits[0] == stmts[-1]):
        raise DeploymentError(
            f"{label}: transaction wrapper is not a single first/last begin;/commit; pair")
    drop = {begins[0], commits[0]}
    return "\n".join(l for i, l in enumerate(lines) if i not in drop)


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_submission(version: str, name: str, raw_sql: str) -> str:
    body = strip_outer_transaction(raw_sql, f"{version}_{name}")
    arr = ", ".join(sql_literal(s) for s in split_statements(raw_sql))
    return (
        "begin;\n" + body +
        "\ninsert into supabase_migrations.schema_migrations(version, name, statements)"
        f" values ({sql_literal(version)}, {sql_literal(name)}, array[{arr}]::text[]);\n"
        "commit;"
    )


# ---------------------------------------------------------------- transport

def curl_transport(ref: str, payload: dict, token: str, timeout: int = 180) -> Outcome:
    """POST to the query endpoint. Classifies strictly; ambiguity is failure."""
    if ref != STAGING_REF:
        raise DeploymentError(f"transport refused: ref {ref!r} is not the staging project")
    proc = subprocess.run(
        ["curl", "-sS", "--max-time", str(timeout), "-X", "POST",
         "-o", "-", "-w", "\n%{http_code}",
         "-H", f"Authorization: Bearer {token}",
         "-H", "Content-Type: application/json",
         "--data-binary", "@-", f"{API}/{ref}/database/query"],
        input=json.dumps(payload), capture_output=True, text=True)

    # Transport-level failure: the request may or may not have reached the server.
    if proc.returncode != 0:
        return Outcome(False, detail=f"curl exit {proc.returncode}: {proc.stderr.strip()[:300]}",
                       uncertain=proc.returncode in (7, 28, 52, 55, 56))

    text = proc.stdout or ""
    body, _, status = text.rpartition("\n")
    status = status.strip()
    if not status.isdigit():
        return Outcome(False, detail=f"no HTTP status in response: {text[:200]!r}", uncertain=True)
    code = int(status)
    if code != 200:
        return Outcome(False, detail=f"HTTP {code}: {body.strip()[:300]}")
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        return Outcome(False, detail=f"HTTP 200 with non-JSON body: {body[:200]!r}")
    # Documented success schema is a JSON array of result rows. Anything else -
    # notably {"message": ...} - is a failure.
    if not isinstance(parsed, list):
        if isinstance(parsed, dict) and "message" in parsed:
            return Outcome(False, detail=f"SQL error: {str(parsed['message'])[:300]}")
        return Outcome(False, detail=f"unexpected response shape: {str(parsed)[:200]}")
    return Outcome(True, rows=parsed)


def history_transport(ref: str, token: str, timeout: int = 60) -> Outcome:
    """GET the official migration history. Used for preflight and after uncertainty."""
    proc = subprocess.run(
        ["curl", "-sS", "--max-time", str(timeout), "-o", "-", "-w", "\n%{http_code}",
         "-H", f"Authorization: Bearer {token}", f"{API}/{ref}/database/migrations"],
        capture_output=True, text=True)
    if proc.returncode != 0:
        return Outcome(False, detail=f"curl exit {proc.returncode}")
    body, _, status = (proc.stdout or "").rpartition("\n")
    if status.strip() != "200":
        return Outcome(False, detail=f"HTTP {status.strip()}: {body[:200]}")
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        return Outcome(False, detail="non-JSON history response")
    if not isinstance(parsed, list):
        return Outcome(False, detail="unexpected history shape")
    return Outcome(True, rows=parsed)


# ---------------------------------------------------------------- preflight

def read_text(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load_manifest() -> dict:
    with open(MANIFEST) as fh:
        return json.load(fh)


def verify_files(manifest: dict) -> list[dict]:
    """Item E: filenames and SHA-256 must match the pinned manifest exactly."""
    entries = manifest["migrations"]
    if len(entries) != 15:
        raise DeploymentError(f"manifest lists {len(entries)} migrations, expected 15")
    on_disk = sorted(f for f in os.listdir(MIGRATIONS_DIR) if f.endswith(".sql"))
    expected = sorted(e["file"] for e in entries)
    if on_disk != expected:
        raise DeploymentError(
            "migration set differs from the manifest\n"
            f"  missing: {sorted(set(expected) - set(on_disk))}\n"
            f"  extra  : {sorted(set(on_disk) - set(expected))}")
    out = []
    for e in entries:
        path = os.path.join(MIGRATIONS_DIR, e["file"])
        with open(path, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        if digest != e["sha256"]:
            raise DeploymentError(
                f"{e['file']}: sha256 mismatch\n  manifest {e['sha256']}\n  on disk  {digest}")
        out.append(dict(e, path=path))
    versions = [e["version"] for e in out]
    if versions != sorted(versions) or len(set(versions)) != len(versions):
        raise DeploymentError("migration versions are not unique and ascending")
    return out


def verify_remote_clean(ref: str, token: str, query, history) -> None:
    """Item E: empty history and the expected pristine schema, or abort."""
    h = history(ref, token)
    if not h.ok:
        raise DeploymentError(f"cannot read migration history: {h.detail}")
    if h.rows:
        raise DeploymentError(
            "remote migration history is NOT empty; refusing to run a second time. "
            f"versions present: {[r.get('version') for r in h.rows][:20]}")
    probe = query(ref, {"read_only": True, "query":
        "select (select count(*) from information_schema.tables where table_schema='public') as public_tables,"
        " (select count(*) from information_schema.schemata where schema_name='supabase_migrations') as sm,"
        " (select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace"
        "   where n.nspname in ('public','tharmesh_private')) as app_functions"}, token)
    if not probe.ok:
        raise DeploymentError(f"cannot read remote schema state: {probe.detail}")
    row = (probe.rows or [{}])[0]
    drift = {k: v for k, v in row.items() if v not in (0, "0")}
    if drift:
        raise DeploymentError(f"staging schema is not in the expected pristine state: {drift}")


# ---------------------------------------------------------------- deployment

def deploy(token: str, query=curl_transport, history=history_transport,
           dry_run: bool = False, log=print) -> int:
    ref = STAGING_REF                      # item B: not configurable
    manifest = load_manifest()
    entries = verify_files(manifest)
    log(f"target        : {ref} (hardcoded)")
    log(f"pinned commit : {manifest['pinned_commit']}")
    log(f"migrations    : {len(entries)} verified against the manifest")
    if dry_run:
        for e in entries:
            raw = read_text(e["path"])
            sub = build_submission(e["version"], e["name"], raw)
            log(f"  would apply {e['version']} {e['name']} "
                f"({len(split_statements(raw))} statements, {len(sub)} bytes)")
        log("\nDRY RUN — no request was sent")
        return 0

    verify_remote_clean(ref, token, query, history)
    log("preflight     : history empty, schema pristine\n")

    res = query(ref, {"query": HISTORY_DDL}, token)
    if not res.ok:
        raise DeploymentError(f"could not create the history table: {res.detail}")

    applied = 0
    for e in entries:
        sub = build_submission(e["version"], e["name"], read_text(e["path"]))
        res = query(ref, {"query": sub}, token)
        if res.ok:
            applied += 1
            log(f"  applied {e['version']} {e['name']}")
            continue
        if res.uncertain:
            # Item C: the response was lost; the transaction may have committed.
            log(f"  UNCERTAIN {e['version']} {e['name']}: {res.detail}")
            h = history(ref, token)
            state = ("unreadable" if not h.ok
                     else ("COMMITTED" if any(r.get("version") == e["version"] for r in h.rows)
                           else "not committed"))
            raise UncertainError(
                f"lost the response for {e['version']}. Remote history says: {state}.\n"
                f"{applied} migration(s) applied before this point.\n"
                "NOT retrying: a blind retry could double-apply. Inspect the project, then "
                "decide manually.")
        raise DeploymentError(
            f"{e['version']} {e['name']} FAILED: {res.detail}\n"
            f"{applied} migration(s) applied and recorded before this point.\n"
            "The failed migration rolled back entirely (no objects, no history row). "
            "No repair or reset attempted.")
    log(f"\n{applied}/{len(entries)} migrations applied")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--i-understand-staging-has-no-backup", action="store_true",
                    help="required for a real run; staging is disposable")
    a = ap.parse_args(argv)
    if not a.dry_run and not a.i_understand_staging_has_no_backup:
        print("REFUSED: a real run requires --i-understand-staging-has-no-backup",
              file=sys.stderr)
        return 2
    token_path = os.path.expanduser("~/.supabase/access-token")
    token = read_text(token_path).strip() if os.path.exists(token_path) else ""
    if not token and not a.dry_run:
        print("REFUSED: no access token; run `supabase login` first", file=sys.stderr)
        return 2
    try:
        return deploy(token, dry_run=a.dry_run)
    except UncertainError as exc:
        print(f"\nSTOPPED (UNCERTAIN)\n{exc}", file=sys.stderr); return 3
    except DeploymentError as exc:
        print(f"\nSTOPPED\n{exc}", file=sys.stderr); return 1


if __name__ == "__main__":
    sys.exit(main())
