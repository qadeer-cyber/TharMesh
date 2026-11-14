#!/usr/bin/env bash
# Migration runner: inventory checks, ordered apply, schema_migrations recording, idempotent re-apply.
set -euo pipefail

migration_psql() {
  psql -h "${PGHOST:-127.0.0.1}" -U "${PGUSER:-tharmesh_runner}" -v ON_ERROR_STOP=1 "$@"
}

migration_ensure_tracking() {
  local db="$1"
  local root="$2"
  migration_psql -d "$db" -f "$root/tests/postgres-migration-tracking.sql"
}

migration_inventory_check() {
  local dir="$1"
  local prev="" seen="" dup=0
  local f version
  for f in $(find "$dir" -maxdepth 1 -name '*.sql' | sort); do
    version=$(basename "$f" | cut -c1-12)
  if [ -z "$version" ] || ! [[ "$version" =~ ^[0-9]{12}$ ]]; then
      echo "migration inventory: invalid filename $(basename "$f")" >&2
      return 1
    fi
    if echo "$seen" | grep -qF "|$version|"; then
      echo "migration inventory: duplicate version $version" >&2
      dup=1
    fi
    seen="$seen|$version|"
    if [ -n "$prev" ] && [ "$version" \< "$prev" ]; then
      echo "migration inventory: out-of-order $version before $prev in sort" >&2
      return 1
    fi
    prev="$version"
  done
  if [ "$dup" -eq 1 ]; then
    return 1
  fi
  local count
  count=$(find "$dir" -maxdepth 1 -name '*.sql' | wc -l)
  local last="$prev"
  local expected=14
  if [ "$count" -ne "$expected" ]; then
    echo "migration inventory: expected $expected files, found $count" >&2
    return 1
  fi
  if [ "$last" != "202611120014" ]; then
    echo "migration inventory: expected last version 202611120014, got $last" >&2
    return 1
  fi
  local n=1
  while [ "$n" -le 14 ]; do
    local want
    want=$(printf '202611120%03d' "$n")
    if ! echo "$seen" | grep -qF "|$want|"; then
      echo "migration inventory: missing version gap $want" >&2
      return 1
    fi
    n=$((n + 1))
  done
}

migration_apply_dir() {
  local db="$1"
  local dir="$2"
  migration_inventory_check "$dir"
  local applied=0 skipped=0
  local f version name
  for f in $(find "$dir" -maxdepth 1 -name '*.sql' | sort); do
    name=$(basename "$f")
    version="${name:0:12}"
    local exists
    exists=$(migration_psql -d "$db" -tAc \
      "select 1 from tharmesh_private.schema_migrations where version='$version'" 2>/dev/null || echo "")
    if [ "$exists" = "1" ]; then
      skipped=$((skipped + 1))
      continue
    fi
    migration_psql -d "$db" -f "$f"
    migration_psql -d "$db" -c \
      "insert into tharmesh_private.schema_migrations (version, filename) values ('$version', '$name')"
    applied=$((applied + 1))
  done
  echo "migration_apply_dir: db=$db applied=$applied skipped=$skipped"
}
