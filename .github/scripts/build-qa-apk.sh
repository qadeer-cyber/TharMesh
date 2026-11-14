#!/usr/bin/env bash
set -euo pipefail

for name in THARMESH_QA_KEYSTORE_BASE64 THARMESH_QA_STORE_PASSWORD THARMESH_QA_KEY_ALIAS THARMESH_QA_KEY_PASSWORD THARMESH_QA_CERT_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    printf 'QA signing requires %s\n' "$name" >&2
    exit 1
  fi
done
expected=$(printf '%s' "$THARMESH_QA_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'THARMESH_QA_CERT_SHA256 must contain a SHA-256 certificate fingerprint.' >&2
  exit 1
fi

umask 077
key_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/tharmesh-qa.XXXXXXXX")
trap 'rm -rf -- "$key_dir"' EXIT
export THARMESH_QA_KEYSTORE_PATH="$key_dir/signing.p12"
if ! printf '%s' "$THARMESH_QA_KEYSTORE_BASE64" | base64 --decode > "$THARMESH_QA_KEYSTORE_PATH" 2>/dev/null; then
  echo 'The QA keystore could not be decoded.' >&2
  exit 1
fi
unset THARMESH_QA_KEYSTORE_BASE64
if ! keytool -list -storetype PKCS12 -keystore "$THARMESH_QA_KEYSTORE_PATH" \
    -storepass:env THARMESH_QA_STORE_PASSWORD -alias "$THARMESH_QA_KEY_ALIAS" >/dev/null 2>&1; then
  echo 'The QA keystore or alias could not be opened.' >&2
  exit 1
fi

if ! ./gradlew --no-daemon assembleQa >"$key_dir/gradle.log" 2>&1; then
  echo 'QA assembly failed. Check the QA signing credentials and the ordinary Android build.' >&2
  exit 1
fi
unset THARMESH_QA_STORE_PASSWORD THARMESH_QA_KEY_PASSWORD THARMESH_QA_KEY_ALIAS
output=app/build/outputs/apk/qa
shopt -s nullglob
apks=("$output"/*.apk)
if [[ ${#apks[@]} -ne 1 ]]; then
  echo 'Expected exactly one QA APK.' >&2
  exit 1
fi
apksigner="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
"$apksigner" verify --print-certs "${apks[0]}" > "$key_dir/apk-certs"
sha256=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$key_dir/apk-certs")
sha1=$(sed -n 's/^Signer #1 certificate SHA-1 digest: //p' "$key_dir/apk-certs")
if [[ $(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' "$key_dir/apk-certs") -ne 1 ||
      "$sha256" != "$expected" || ! "$sha1" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'QA APK signer does not match the pinned certificate.' >&2
  exit 1
fi
printf 'SHA-1: %s\nSHA-256: %s\n' "$sha1" "$sha256" | tee "$output/certificate-fingerprints.txt"
