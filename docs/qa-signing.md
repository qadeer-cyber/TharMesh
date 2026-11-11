# Stable TharMesh QA signing

The QA variant keeps application ID `tharmesh.app`. Debug and production
signing are separate. Only use the QA private key for controlled test builds.

## Owner setup

1. On a trusted machine outside the source checkout, run
   `keytool -genkeypair -keystore tharmesh-qa.p12 -storetype PKCS12 -alias tharmesh-qa -keyalg RSA -keysize 3072 -validity 7300`.
   Enter a strong password at the prompt. PKCS12 uses the store password for
   the key as well. Retain an encrypted, access-controlled backup and keep
   the password separately.
2. In repository Settings → Secrets and variables → Actions, create
   `THARMESH_QA_KEYSTORE_BASE64` with the single-line base64 of the PKCS12
   file, plus `THARMESH_QA_STORE_PASSWORD`, `THARMESH_QA_KEY_ALIAS`, and
   `THARMESH_QA_KEY_PASSWORD`. Base64 is reversible. Handle it like the
   original key and clear the clipboard after entry.
3. Run `keytool -list -v -storetype PKCS12 -keystore tharmesh-qa.p12 -alias tharmesh-qa`.
   Set the Actions variable `THARMESH_QA_CERT_SHA256` to the public SHA-256
   fingerprint. Set `THARMESH_QA_SIGNING_ENABLED` to `true` to enable the
   QA steps on trusted same-repository changes. Keep signing secrets available
   only to workflows and contributors you trust.
4. Run Android CI twice on reviewed changes. Both runs must publish
   `tharmesh-app-qa` and show the same certificate SHA-1 and SHA-256. An
   enabled QA job fails when credentials are missing or the digest differs.
   The workflow leaves ordinary debug artifacts separately identified.

## Google

Configure the OAuth consent screen and add QA test users if the app is in
Testing. Create an Android OAuth client for `tharmesh.app` with the QA
APK's verified SHA-1. Use the verified SHA-256 where the consuming service
requests it. Create a Web client, register the callback shown on the
Supabase Google provider page, and enable the provider with the Web client
ID and secret. Set Actions variable `GOOGLE_WEB_CLIENT_ID` to the Web
client ID. Keep the Web client secret exclusively in Supabase.

## Upgrade validation

Install the first QA APK on a disposable device or emulator, activate an
account, and record its account ID, mesh fingerprint, contacts, and chats.
Install the second APK using `adb install -r path/to/second-qa.apk`. Check
all four values after restart and repeat with networking disabled. Matching
certificates are a prerequisite. They do not replace the device test.

Older debug APKs may use different keys. To migrate an existing device,
retain its installation and verify an authenticated transfer or encrypted
backup on a separate device before considering removal. Identity recovery
does not by itself prove that conversations can be migrated. Keep the
original installation if any data cannot be restored. An in-place update
requires the original signer or an Android-supported signing lineage.
