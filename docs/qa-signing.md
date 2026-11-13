# Stable TharMesh QA signing

The QA variant keeps application ID `tharmesh.app`. Debug and production
signing are separate. Only use the QA private key for controlled test builds.

## Owner setup

1. On a trusted machine outside the source checkout, run
   `keytool -genkeypair -keystore tharmesh-qa.p12 -storetype PKCS12 -alias tharmesh-qa -keyalg RSA -keysize 3072 -validity 7300`.
   Enter a strong password at the prompt. PKCS12 uses the store password for
   the key as well. Retain an encrypted, access-controlled backup and keep
   the password separately.
2. In GitHub repository **Settings → Environments**, create
   `tharmesh-qa-signing`. Select **Selected branches and tags** and add only
   the branch `cursor/universal-account-0939`. Add a trusted required reviewer,
   enable **Prevent self-review** when another reviewer is available, and
   disable administrator bypass. Keep the repository opt-in variable off
   until these protections are verified. If approval rules are unavailable,
   leave signing disabled until an equivalent trusted approval gate is in place.
3. In PowerShell, with GitHub CLI authenticated as a repository administrator,
   run the following from the directory holding the keystore. The password
   commands prompt for input. Enter the PKCS#12 password at both prompts.

   ```powershell
   $repo = "qadeer-cyber/TharMesh"
   $environment = "tharmesh-qa-signing"
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\tharmesh-qa.p12).Path)) |
     gh secret set THARMESH_QA_KEYSTORE_BASE64 --repo $repo --env $environment
   "tharmesh-qa" | gh secret set THARMESH_QA_KEY_ALIAS --repo $repo --env $environment
   gh secret set THARMESH_QA_STORE_PASSWORD --repo $repo --env $environment
   gh secret set THARMESH_QA_KEY_PASSWORD --repo $repo --env $environment
   keytool -list -v -storetype PKCS12 -keystore .\tharmesh-qa.p12 -alias tharmesh-qa
   ```

   Keep all four secrets scoped to the environment. If any were previously
   provisioned as repository secrets, remove the repository-scoped copies
   before enabling QA.
4. Set the environment variable `THARMESH_QA_CERT_SHA256` to the public
   SHA-256 fingerprint printed by keytool. Then set the repository Actions
   variable `THARMESH_QA_SIGNING_ENABLED` to `true`.
5. Review the full source SHA, including build scripts, before approving the
   signed QA job on a push to the configured branch. The PR event never
   starts the environment job. Repeat on two reviewed pushes and compare the
   public fingerprints included in both `tharmesh-app-qa` artifacts.

## Google

The debug APK built by the main Android job is signed with the committed
`app/debug.keystore`, not with the QA key. Its SHA-1 is
`55:A7:A3:2D:CA:87:F5:39:3E:1B:F8:3B:0B:6F:3B:A5:DB:23:5A:DD`
and its package is `tharmesh.app`. Register that Android OAuth client for
debug installs. Register the QA certificate's SHA-1 as a second Android
client only after the steps above are enabled.

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
