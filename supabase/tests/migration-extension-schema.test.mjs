// Extension functions must not be referenced as public.<fn> in migrations.
//
// On Supabase, pgcrypto is installed into the `extensions` schema, not `public`.
// A local cluster where `create extension pgcrypto` defaulted to `public` resolves
// `public.digest(...)` happily, so the whole local suite passes while the hosted
// project would raise `function public.digest(bytea, unknown) does not exist`.
//
// It fails at CALL time, not apply time: these are `security definer` functions with
// `set search_path = ''` (so there is no fallback resolution), and plpgsql bodies are
// not resolved when the function is created. The migration applies cleanly and the
// break only surfaces when a user exercises that code path.
//
// Prefer a pg_catalog equivalent where one exists (sha256/md5 for digest), otherwise
// qualify with `extensions.`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const migrationsDir = join(dirname(fileURLToPath(import.meta.url)), "..", "migrations");

// pgcrypto surface that is NOT in pg_catalog and therefore never lives in `public`
// on a hosted Supabase project.
const PGCRYPTO_FUNCTIONS = [
  "digest", "hmac", "crypt", "gen_salt", "gen_random_bytes",
  "encrypt", "decrypt", "encrypt_iv", "decrypt_iv",
  "pgp_sym_encrypt", "pgp_sym_decrypt", "pgp_pub_encrypt", "pgp_pub_decrypt",
  "armor", "dearmor",
];

function migrationFiles() {
  return readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort();
}

/** Strip `--` line comments so documentation about the hazard is not flagged. */
function stripComments(sql) {
  return sql.split("\n").map((l) => l.replace(/--.*$/, "")).join("\n");
}

test("no migration calls a pgcrypto function through the public schema", () => {
  const offenders = [];
  for (const file of migrationFiles()) {
    const sql = stripComments(readFileSync(join(migrationsDir, file), "utf8"));
    sql.split("\n").forEach((line, i) => {
      for (const fn of PGCRYPTO_FUNCTIONS) {
        if (new RegExp(`\\bpublic\\.${fn}\\s*\\(`).test(line)) {
          offenders.push(`${file}:${i + 1}: public.${fn}(...)`);
        }
      }
    });
  }
  assert.deepEqual(
    offenders,
    [],
    "pgcrypto lives in `extensions` on Supabase, so these resolve locally and fail " +
      "on the hosted project at call time:\n" + offenders.join("\n")
  );
});

test("the recovery ciphertext digest uses a pg_catalog function", () => {
  const sql = readFileSync(
    join(migrationsDir, "202611120010_grant_session_atomic_recovery.sql"), "utf8"
  );
  assert.match(
    sql,
    /digest_hex\s*:=\s*pg_catalog\.encode\(pg_catalog\.sha256\(/,
    "the recovery upload hash must use pg_catalog.sha256, which needs no extension " +
      "and resolves under `set search_path = ''`"
  );
});

test("security-definer functions that empty search_path qualify their calls", () => {
  // Spot-check the invariant that makes the rule above necessary.
  const sql = readFileSync(
    join(migrationsDir, "202611120010_grant_session_atomic_recovery.sql"), "utf8"
  );
  assert.match(sql, /set search_path = ''/, "expected the hardened search_path pattern");
});
