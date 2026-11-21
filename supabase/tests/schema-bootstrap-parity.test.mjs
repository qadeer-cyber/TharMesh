// The tracked base-schema migration must stay in step with supabase/schema.sql.
//
// supabase/schema.sql is applied by hand in the Supabase SQL editor and is NOT a
// migration, so `supabase db push` never installs it. 202611120000_base_schema.sql
// exists so a fresh project can be brought up by migrations alone. If someone edits
// schema.sql and forgets the migration, a newly bootstrapped project silently differs
// from a hand-built one. This test regenerates the migration from schema.sql with the
// documented transformation and asserts the committed file matches.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const schemaPath = join(root, "schema.sql");
const migrationPath = join(root, "migrations", "202611120000_base_schema.sql");

/** Same transformation the migration was generated with: make policies idempotent. */
function generate(schemaSql) {
  return schemaSql.replace(
    /create policy (\w+) on ([\w.]+)/g,
    (match, name, table) => `drop policy if exists ${name} on ${table};\n${match}`
  );
}

test("base-schema migration matches supabase/schema.sql", () => {
  const schema = readFileSync(schemaPath, "utf8");
  const migration = readFileSync(migrationPath, "utf8");
  const marker = "-- regenerate.\n\n";
  const idx = migration.indexOf(marker);
  assert.ok(idx > 0, "migration is missing its generated-file header");
  const body = migration.slice(idx + marker.length);
  assert.equal(
    body,
    generate(schema),
    "202611120000_base_schema.sql is out of step with supabase/schema.sql — " +
      "edit schema.sql and regenerate the migration, do not edit it by hand"
  );
});

test("every create policy in the migration is preceded by a drop", () => {
  const migration = readFileSync(migrationPath, "utf8");
  const creates = [...migration.matchAll(/create policy (\w+) on ([\w.]+)/g)];
  assert.ok(creates.length > 0, "no policies found");
  for (const [, name, table] of creates) {
    assert.match(
      migration,
      new RegExp(`drop policy if exists ${name} on ${table.replace(".", "\\.")};`),
      `policy ${name} on ${table} is not guarded by a drop, so the migration is ` +
        "not idempotent on a project where schema.sql was already applied by hand"
    );
  }
});

test("the base schema creates the tables later migrations depend on", () => {
  const migration = readFileSync(migrationPath, "utf8");
  // 202611120003 fails with `relation "public.devices" does not exist` without these.
  for (const table of ["profiles", "handles", "devices", "recovery_blobs"]) {
    assert.match(
      migration,
      new RegExp(`create table if not exists public\\.${table}`),
      `public.${table} must be created by the tracked bootstrap`
    );
  }
});
