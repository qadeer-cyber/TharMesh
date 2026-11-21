#!/usr/bin/env bash
# Verify an APK's file SHA-256 AND its signing certificate SHA-256 against
# committed expectations. Both checks are mandatory: there is no mode that
# accepts an APK without a hash match.
#
# usage: verify-pinned-apk.sh <apk> [mode]
#   pinned             (default) file hash must equal the pinned trusted v6 digest
#                      in scripts/pinned/v6-room6-debug.sha256
#   expected:<sha256>  file hash must equal <sha256> — for per-commit (head) APKs,
#                      whose digest is established once at build time and carried
#                      to every downstream job via apk-artifacts/SHA256SUMS.txt
#
# scripts/pinned/*.sha256 are the single source of truth. The workflows restate
# the same digests in-band via THARMESH_EXPECT_V6_FILE_SHA256 /
# THARMESH_EXPECT_DEBUG_CERT_SHA256; a value that DISAGREES with the committed pin
# is rejected, so nobody can turn a red gate green by editing a workflow env
# without touching the reviewed pin file. An empty or malformed expectation is
# also rejected, so a typo'd or unset variable fails closed instead of silently
# disabling the check.
set -euo pipefail

APK="${1:?apk path}"
MODE="${2:-pinned}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN_DIR="$ROOT/scripts/pinned"

die() { echo "FAIL $*" >&2; exit 1; }

# Refuse the retired bypass rather than honouring it. Cert-only verification of a
# pinned artifact was a 0.9 regression (commits 08875ba / b8275ea); it is gone.
for legacy in THARMESH_VERIFY_CERT_ONLY THERMESH_VERIFY_CERT_ONLY; do
  if [ -n "${!legacy:-}" ] && [ "${!legacy}" != "0" ]; then
    die "$legacy is no longer supported — APK hash verification cannot be disabled"
  fi
done

read_pin() {
  # First non-comment, non-blank line of a pin file.
  grep -vE '^[[:space:]]*(#|$)' "$1" | head -1 | tr -d '[:space:]'
}

# resolve_pin <pin-file> <override-var-name> <label>
# Returns the committed pin, and fails if an override is present but different.
resolve_pin() {
  local file="$1" var="$2" label="$3"
  local committed override
  committed="$(read_pin "$file")"
  override="${!var:-}"
  if [ -n "$override" ] && [ "$override" != "$committed" ]; then
    die "$var=$override disagrees with the committed $label pin $committed in $file"
  fi
  printf '%s' "$committed"
}

case "$MODE" in
  pinned)
    EXPECT_FILE_SHA="$(resolve_pin "$PIN_DIR/v6-room6-debug.sha256" THARMESH_EXPECT_V6_FILE_SHA256 v6)"
    PIN_SOURCE="pinned-v6"
    ;;
  expected:*)
    EXPECT_FILE_SHA="${MODE#expected:}"
    PIN_SOURCE="expected-argv"
    ;;
  cert-only)
    die "mode 'cert-only' is no longer supported — pass 'pinned' or 'expected:<sha256>'"
    ;;
  *)
    die "unknown mode '$MODE' — pass 'pinned' or 'expected:<sha256>'"
    ;;
esac

EXPECT_CERT_SHA="$(resolve_pin "$PIN_DIR/debug-cert.sha256" THARMESH_EXPECT_DEBUG_CERT_SHA256 debug-cert)"

echo "$EXPECT_FILE_SHA" | grep -qE '^[0-9a-f]{64}$' ||
  die "expected file sha256 is not a 64-hex digest (source=$PIN_SOURCE value='$EXPECT_FILE_SHA')"
echo "$EXPECT_CERT_SHA" | grep -qE '^[0-9a-f]{64}$' ||
  die "expected cert sha256 is not a 64-hex digest (value='$EXPECT_CERT_SHA')"

test -f "$APK" || die "apk not found: $APK"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
APKSIGNER="$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -n "$APKSIGNER" ] || die "apksigner not found under $SDK/build-tools"

FILE_SHA="$(sha256sum "$APK" | awk '{print $1}')"
if [ "$FILE_SHA" != "$EXPECT_FILE_SHA" ]; then
  echo "FAIL file sha256=$FILE_SHA expected=$EXPECT_FILE_SHA source=$PIN_SOURCE apk=$APK" >&2
  echo "----- reproducibility diagnostics -----" >&2
  echo "apk_bytes=$(stat -c %s "$APK")" >&2
  echo "legacy_source_commit=${LEGACY_SOURCE_COMMIT:-unset}" >&2
  echo "historical_worktree=${HISTORICAL_WORKTREE:-unset}" >&2
  echo "java=$(java -version 2>&1 | head -1)" >&2
  echo "build_tools=$(basename "$(dirname "$APKSIGNER")")" >&2
  echo "runner_image=${ImageOS:-unknown}/${ImageVersion:-unknown}" >&2
  exit 1
fi

CERT_SHA="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr -d '[:space:]')"
if [ -z "$CERT_SHA" ]; then
  CERT_SHA="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*SHA-256: //p' | head -1 | tr -d ': \r')"
fi
[ -n "$CERT_SHA" ] || die "could not read signing certificate from $APK"
if [ "$CERT_SHA" != "$EXPECT_CERT_SHA" ]; then
  die "cert sha256=$CERT_SHA expected=$EXPECT_CERT_SHA apk=$APK"
fi

echo "verify ok mode=$MODE file=$FILE_SHA cert=$CERT_SHA"
