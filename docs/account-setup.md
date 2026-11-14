# Cloud account configuration

A debug APK built on a machine without `SUPABASE_URL` and `SUPABASE_ANON_KEY`
returns `cloud_auth_not_configured`. That is the build, not the hosted project.
CI passes the repository variables into BuildConfig. A blank `local.properties`
entry does not hide those variables. The publishable key is not parsed as a JWT.
Trailing whitespace and a trailing slash on the URL are trimmed. `http://` is rejected.

## Values the app reads

Set either `local.properties` (gitignored) or the environment / Gradle property
of the same name:

```
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=sb_publishable_...
GOOGLE_WEB_CLIENT_ID=YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID
```

`SUPABASE_URL` must be `https`. `SUPABASE_ANON_KEY` is the publishable client
key (`sb_publishable_...`), not a legacy JWT and not the service-role key.
The app sends it only in the `apikey` header. `Authorization: Bearer` is
reserved for a user access token after sign-in.

GitHub Actions reads the same names from repository secrets, then repository variables:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `GOOGLE_WEB_CLIENT_ID` (optional; the Google button stays disabled when it is empty)

Add them under Settings → Secrets and variables → Actions. Do not commit them.
This repository has no GitHub Environment, so the Android job does not set
`environment:`. A value stored only as an environment secret would not reach
the build until that environment exists and the job names it.

`google-services.json` is gitignored and is not required for this HTTP client.

## Custom SMTP (required before ordinary addresses receive mail)

The built-in Supabase mailer only delivers to addresses on the Supabase org or team, and only about two messages an hour. A normal inbox gets nothing, and GoTrue returns `email_address_not_authorized`, which the app shows as an email delivery failure. Do not put the SMTP password in this repo, and do not send test mail from CI.

Recommended provider: Resend. Brevo is the same shape if you already have an account.

1. Verify a domain you control.
2. Publish the provider’s DNS records before enabling SMTP:
   - SPF TXT on the sending hostname
   - the DKIM CNAME records the provider shows
   - DMARC TXT at `_dmarc`, starting at `p=none` while you watch delivery, then `p=quarantine`
3. In Supabase: Authentication → Emails → SMTP Settings:
   - Enable custom SMTP
   - Host: `smtp.resend.com` (Resend) or `smtp-relay.brevo.com` (Brevo)
   - Port: `465` when the form uses implicit TLS. Use `587` only if the form says STARTTLS
   - Username: `resend` (Resend) or the Brevo SMTP login
   - Password: the provider API key or SMTP key, stored in the Supabase dashboard only
   - Sender email: an address on the verified domain, such as `noreply@your-domain`
   - Sender name: `TharMesh`
4. Authentication → Emails → Templates. Both **Magic link** and **Confirm signup** must contain:

```
Your TharMesh code is {{ .Token }}
```

Confirm signup must also keep `{{ .ConfirmationURL }}`. Do not use `{{ .SiteURL }}` by itself. The six-digit code is `{{ .Token }}`. A template without it can still send a link and the in-app code screen has nothing to type.

5. Send one message to a non-team address only after SMTP is saved. Check Supabase Auth logs and the provider bounce log if it does not arrive. The app does not retry a 429 by itself.

## Google sign-in for the debug APK

Package name: `tharmesh.app`

The CI debug APK is signed with the committed `app/debug.keystore` (alias `androiddebugkey`, store password `android`):

- SHA-1: `55:A7:A3:2D:CA:87:F5:39:3E:1B:F8:3B:0B:6F:3B:A5:DB:23:5A:DD`
- SHA-256: `AC:1A:51:18:84:DF:9E:C2:CD:8B:0B:8D:69:6C:31:92:B6:71:CD:DC:6C:93:F8:E8:3A:E9:EF:D0:82:21:C2:EC`

Create an Android OAuth client in Google Cloud with that package and SHA-1, and a Web client. Put the Web client id in the Actions variable `GOOGLE_WEB_CLIENT_ID`. In Supabase → Authentication → Providers → Google, enable the provider and paste the same Web client id and its secret. Until the variable is set and the provider is enabled, the Google button does not sign in. The separate QA certificate in `docs/qa-signing.md` is not this debug SHA-1. Register that SHA-1 only after QA signing is enabled.

## URL configuration (owner action in the Supabase dashboard)

A confirmation link that opens `http://localhost:3000` was built from the project Site URL. GoTrue uses `redirect_to` only when that exact value is on the allow-list. If it is missing, GoTrue falls back to the Site URL and does not return an error to the app. An email sent before this APK, or before the allow-list was saved, keeps the old link. Resend from the new APK after the dashboard is saved. A user who is already confirmed will not get another mail.

Authentication → URL Configuration. Set these exactly:

- **Site URL:** `tharmesh://auth/callback`
- **Additional Redirect URLs:** `tharmesh://auth/callback`

If the dashboard offers a wildcard, also add `tharmesh://**`. The string must match, including the scheme. `http://localhost:3000` must not remain the Site URL.

Authentication → Emails → Confirm signup. The template must keep `{{ .ConfirmationURL }}`. Do not replace it with `{{ .SiteURL }}`. The app also accepts a direct link:

```
{{ .RedirectTo }}?token_hash={{ .TokenHash }}&type=signup
```

`type=email` is accepted as well. Signup and resend both send `redirect_to=tharmesh://auth/callback` as a query parameter. Signup also repeats it in the JSON body. The password grant and a verified callback are the only paths that mark the account confirmed on the phone.

If the link opens on a phone that no longer has the signup challenge (reinstall, or the mail was opened after the challenge was cleared), the screen says to sign in with the password. Code: `pkce_verifier_missing` or `pkce_expired`.

Built-in email rate limits return HTTP 429. The app shows that, honors `Retry-After` when Supabase sends it, and disables Resend for the cooldown. It does not retry the send by itself. For real use, configure custom SMTP under Authentication → Emails. The built-in mailer is only enough for a few messages an hour.

Every auth failure ends with `Code: <slug>`. Send that slug. It is not an email, token, or key.

## Dashboard steps that are not in this repo

1. Create a Supabase project.
2. Run `supabase/schema.sql` in the SQL editor.
3. Authentication → Providers: enable Email and Google.
4. Google provider client IDs must include the same web client id as
   `GOOGLE_WEB_CLIENT_ID`. Add the Android OAuth client in Google Cloud with
   package `tharmesh.app` and this build's SHA-1.
5. Authentication → Email: require email confirmation. Turn on rate limits and,
   if the project offers it, captcha / leaked-password protection.
6. Confirm RLS is enabled on `profiles`, `handles`, `devices`, and
   `recovery_blobs`. The policies are in the SQL file. There is no policy that
   lets the anon key read a recovery row it does not own, and the table has no
   column for a private key or a recovery phrase.

## What was verified here

- `./gradlew testDebugUnitTest` with the values unset (see the PR report for
  the exact count).
- The client refuses a blank config and a non-HTTPS URL without calling HTTP.
- The recovery upload builder emits ciphertext fields only.

The Firebase emulator and the Supabase CLI were not run from this repository.
The owner has a hosted project; CI calls `GET /auth/v1/health` with the
`apikey` header and does not sign up.
