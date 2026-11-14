# Account backend

## Decision

**Supabase Auth (GoTrue over HTTPS) plus Postgres Row Level Security.**

The client speaks `/auth/v1/signup`, `/auth/v1/token?grant_type=password`,
`/auth/v1/token?grant_type=id_token` (Google), `/auth/v1/resend`, and
`/auth/v1/user`. Profile, handle, device, and recovery rows go through
PostgREST `/rest/v1/`. SQL is in `supabase/schema.sql`. No Supabase SDK is
compiled in, because this tree is Gradle 6.5 and Android Gradle Plugin 4.1.3.

Empty `SUPABASE_URL` or `SUPABASE_ANON_KEY`, or a URL that is not `https://`,
returns `cloud_auth_not_configured` and does not create a session. The value
in `SUPABASE_ANON_KEY` is the publishable client key (`sb_publishable_...`).
It is sent only as the `apikey` header. It is not decoded as a JWT and it is
not copied into `Authorization`. A bearer header is added only for a user
access token. Service-role keys are not used and must not be put in the app.

## Why Supabase, not Firebase

Both products can do email, Google, and email verification without a phone
number. The constraint that matters here is the toolchain and who operates it.

| | Supabase | Firebase |
| --- | --- | --- |
| Android fit on AGP 4.1.3 | HTTPS from the app. No Gradle plugin. | Current `google-services` / Firebase BoM does not fit AGP 4.1.3. A hand-written Identity Toolkit client would, but it would be a second HTTP stack next to GoTrue, which is already in this branch. |
| Authorization rules in git | Postgres RLS, plain SQL, reviewed in `supabase/schema.sql`. | Firestore rules. Also reviewable, but they need the Firebase CLI and a project file the Gradle plugin expects. |
| Uniqueness | `handles.handle` unique constraint. The database rejects the duplicate. The client cannot be the only check. | A transaction on `handles/{handle}` can do the same. |
| Abuse | GoTrue's built-in rate limits, plus the app's `AuthRateLimiter` (8 failures / 15 minutes). Hosted projects should also turn on captcha and leaked-password protection in the Supabase dashboard. There is no App Check equivalent until that dashboard toggle exists. | App Check is stronger on Play-distributed Android builds. We do not have a Firebase project, and App Check would not run without one. |
| Cost for a solo founder | Free tier covers auth and a few small rows per user (profile, devices, one ciphertext). No always-on server to run. | Auth for email and Google is free. Firestore's free quota would also cover these rows. Phone auth is the expensive Firebase product; we do not use it. |
| Maintenance | One hosted project, SQL the repo already contains. | A second console, `google-services.json`, and a plugin this AGP cannot load. |

Firebase was the closer match to the existing Google Sign-In button, and its
Auth REST API would also fail closed without a project. It is rejected because
the official Android integration fights the toolchain, and the GoTrue client,
tests, and draft PR already exist. Replacing them would discard a working
fail-closed path without a project to point either product at.

Google Sign-In stays. When a Supabase URL is present, the Google ID token is
sent to GoTrue as `grant_type=id_token`. When it is absent, the existing local
Google profile path still runs and the screen says cloud link is not configured.
That local profile is not a Supabase session.

## What is not claimed

No Supabase project is configured in this repository. Unit tests use
`HttpTransport` fakes and `SimulatedAccountDirectory` (`LABEL = "SIMULATED"`).
A simulated success is not live authentication. Live sign-in has not been executed.

## Native Google and email code configuration

- Google Cloud needs an Android OAuth client for package `tharmesh.app` and
  the SHA-1 of each signing certificate used for testing or distribution.
  Run `./gradlew :app:signingReport` in the APK build environment. Use the
  certificate for the installed APK, including Play App Signing when applicable.
- Create a Google Web OAuth client and enable Google under Supabase Auth
  providers. Supply the Web client ID and secret to Supabase and register the
  provider callback URL shown by Supabase in the Web client's redirect URIs.
  Set the GitHub Actions variable `GOOGLE_WEB_CLIENT_ID` to the Web client ID.
  Only the public client ID belongs in the Android build.
- In Supabase Auth email settings, set the OTP length to six. Both the
  Magic Link and Confirm Signup templates must include `{{ .Token }}`.
  Confirm Signup also keeps `{{ .ConfirmationURL }}`. Keep
  `tharmesh://auth/callback` in the redirect allow-list. The email-code
  request sends that same value as `redirect_to`.
- For public email delivery, verify a sending domain with an SMTP provider
  such as Resend. Publish the provider's SPF and DKIM records and configure
  DMARC for the sender domain. In Supabase Auth SMTP settings use
  `smtp.resend.com`, port `465` for implicit TLS (or `587` for STARTTLS),
  username `resend`, the provider API key as the SMTP password, and an
  address on the verified domain as the sender.
  Keep the SMTP credential in Supabase only. Review Auth rate limits and
  provider limits before increasing volume. For missing messages, check
  Supabase Auth logs and the provider delivery/bounce log first.
- On a clean device, verify both Google and a real email code, then repeat
  with an existing password account. Verify the Supabase user ID and local
  mesh key remain stable across restart, reauthentication, and offline use.
