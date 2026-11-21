#!/usr/bin/env bash
# Self-test for scripts/verify-pinned-apk.sh: proves the verifier fails closed,
# and fails for the *right reason*. Runs in seconds, needs no APK and no
# emulator. Invoked from the Cat 6 prepare-apks job so a regression that
# re-opens the cert-only bypass, or that accepts an empty/typo'd expectation,
# cannot reach the gates.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/scripts/verify-pinned-apk.sh"
PINNED_V6="$(grep -vE '^[[:space:]]*(#|$)' "$ROOT/scripts/pinned/v6-room6-debug.sha256" | head -1 | tr -d '[:space:]')"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAKE="$TMP/not-really.apk"
printf 'not an apk' > "$FAKE"
fails=0

# expect_fail <expected-substring-in-output> <description> -- <command...>
expect_fail() {
  local want="$1" desc="$2"; shift 3
  if "$@" >"$TMP/out" 2>&1; then
    echo "SELFTEST FAIL (accepted when it must reject): $desc"
    sed -n 1,5p "$TMP/out"
    fails=$((fails + 1))
    return
  fi
  if grep -qF -- "$want" "$TMP/out"; then
    echo "selftest ok (rejected: $want): $desc"
  else
    echo "SELFTEST FAIL (rejected for the wrong reason, wanted '$want'): $desc"
    sed -n 1,5p "$TMP/out"
    fails=$((fails + 1))
  fi
}

# 1. The retired cert-only bypass must be refused, not honoured.
expect_fail "no longer supported" "THARMESH_VERIFY_CERT_ONLY=1" -- \
  env THARMESH_VERIFY_CERT_ONLY=1 bash "$V" "$FAKE" pinned
expect_fail "no longer supported" "THERMESH_VERIFY_CERT_ONLY=1 (historical typo spelling)" -- \
  env THERMESH_VERIFY_CERT_ONLY=1 bash "$V" "$FAKE" pinned
expect_fail "no longer supported" "mode cert-only" -- \
  bash "$V" "$FAKE" cert-only
expect_fail "unknown mode" "unrecognised mode" -- \
  bash "$V" "$FAKE" whatever-mode

# 2. An env override that disagrees with the committed pin must be refused, so a
#    red gate cannot be turned green by editing a workflow env block.
expect_fail "disagrees with the committed" "divergent v6 override" -- \
  env THARMESH_EXPECT_V6_FILE_SHA256=$(printf 'a%.0s' $(seq 64)) bash "$V" "$FAKE" pinned
expect_fail "disagrees with the committed" "non-hex v6 override" -- \
  env THARMESH_EXPECT_V6_FILE_SHA256=deadbeef bash "$V" "$FAKE" pinned
expect_fail "disagrees with the committed" "divergent cert override" -- \
  env THARMESH_EXPECT_DEBUG_CERT_SHA256=nope bash "$V" "$FAKE" pinned
expect_fail "not a 64-hex digest" "empty expected: argv" -- \
  bash "$V" "$FAKE" "expected:"

# 3. An unset/empty override must fall back to the committed pin, not to "no check".
expect_fail "expected=$PINNED_V6" "empty pinned override still compares against the committed pin" -- \
  env THARMESH_EXPECT_V6_FILE_SHA256= bash "$V" "$FAKE" pinned

# 3b. The workflow's in-band restatement must agree with the committed pins.
WF=".github/workflows/emulator-evidence.yml"
if [ -f "$ROOT/$WF" ]; then
  wf_v6="$(sed -n 's/^[[:space:]]*THARMESH_EXPECT_V6_FILE_SHA256:[[:space:]]*//p' "$ROOT/$WF" | tr -d '[:space:]')"
  if [ -n "$wf_v6" ] && [ "$wf_v6" != "$PINNED_V6" ]; then
    echo "SELFTEST FAIL $WF restates v6 pin $wf_v6 but committed pin is $PINNED_V6"
    fails=$((fails + 1))
  else
    echo "selftest ok (workflow restates the committed v6 pin)"
  fi
fi

# 4. Content mismatch and missing input must fail.
expect_fail "file sha256=" "file hash mismatch" -- bash "$V" "$FAKE" pinned
expect_fail "apk not found" "missing apk" -- bash "$V" "$TMP/absent.apk" pinned

# 5. The committed pins must still be well-formed 64-hex digests.
for pin in "$ROOT/scripts/pinned/v6-room6-debug.sha256" "$ROOT/scripts/pinned/debug-cert.sha256"; do
  value="$(grep -vE '^[[:space:]]*(#|$)' "$pin" | head -1 | tr -d '[:space:]')"
  if echo "$value" | grep -qE '^[0-9a-f]{64}$'; then
    echo "selftest ok (pin well-formed): $(basename "$pin")"
  else
    echo "SELFTEST FAIL malformed pin in $pin: '$value'"
    fails=$((fails + 1))
  fi
done

if [ "$fails" -ne 0 ]; then
  echo "RESULT=FAIL verify-pinned-apk selftest failures=$fails"
  exit 1
fi
echo "RESULT=PASS verify-pinned-apk selftest (fail-closed behaviour confirmed)"
