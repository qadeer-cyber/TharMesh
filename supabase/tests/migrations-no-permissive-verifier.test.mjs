import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const migrations = join(dirname(fileURLToPath(import.meta.url)), '../migrations');

test('no migration defines a permissive ECDSA verifier', () => {
  const forbidden = [
    /length\s*\(\s*spki_b64\s*\)\s*>=\s*80/,
    /length\s*\(\s*der_sig_b64\s*\)\s*>=\s*40/,
    /octet_length\s*\(\s*message\s*\)\s*>\s*10/,
  ];
  for (const name of readdirSync(migrations).filter((f) => f.endsWith('.sql'))) {
    const sql = readFileSync(join(migrations, name), 'utf8');
    for (const pattern of forbidden) {
      assert.equal(
        pattern.test(sql),
        false,
        `forbidden permissive verifier pattern in ${name}`,
      );
    }
  }
});
