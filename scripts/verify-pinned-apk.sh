#!/usr/bin/env bash
# Verify debug APK file hash and signing cert against pinned expectations.
set -euo pipefail
APK="${1:?apk path}"
EXPECT_FILE_SHA="${THARMESH_EXPECT_V6_FILE_SHA256:-e2f7145b975ff099566f3a8a2d02cbd236ba75ae67af7a29f4ffd8d43f900c89}"
EXPECT_CERT_SHA="${THARMESH_EXPECT_DEBUG_CERT_SHA256:-ac1a511884df9ec2cd8b0b8d696c3192b671cddc6c93f8e83ae9efd08221c2ec}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
APKSIGNER="$(ls -d "$SDK/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)"
FILE_SHA="$(sha256sum "$APK" | awk '{print $1}')"
if [ -n "$EXPECT_FILE_SHA" ] && [ "$FILE_SHA" != "$EXPECT_FILE_SHA" ]; then
  echo "FAIL file sha256=$FILE_SHA expected=$EXPECT_FILE_SHA"
  exit 1
fi
CERT_SHA="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr -d '[:space:]')"
if [ -z "$CERT_SHA" ]; then
  CERT_SHA="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*SHA-256: //p' | head -1 | tr -d ': \r')"
fi
if [ "$CERT_SHA" != "$EXPECT_CERT_SHA" ]; then
  echo "FAIL cert sha256=$CERT_SHA expected=$EXPECT_CERT_SHA"
  exit 1
fi
echo "verify ok file=$FILE_SHA cert=$CERT_SHA"
