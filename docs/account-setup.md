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

## URL configuration (owner action in the Supabase dashboard)

Authentication → URL Configuration. The default Site URL `http://localhost:3000` is why the confirmation email never opens the app.

Set both of these exactly:

- **Site URL:** `tharmesh://auth/callback`
- **Additional Redirect URLs:** `tharmesh://auth/callback`

Save. The app also sends `redirect_to=tharmesh://auth/callback` on signup and resend. Supabase still refuses a redirect that is not on that allow-list, and the email link is built from the Site URL, so the dashboard values are required.

Built-in email rate limits return HTTP 429. The app shows that, honors `Retry-After` when Supabase sends it, and disables Resend for the cooldown. It does not retry the send by itself. For real use, configure custom SMTP under Authentication → Emails. The built-in mailer is only enough for a few messages an hour.

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
