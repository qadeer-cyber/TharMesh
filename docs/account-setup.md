# Cloud account configuration

Live Supabase auth is **not** configured in this repository. The debug build
succeeds with the values empty. Sign-up and sign-in then return
`cloud_auth_not_configured` and do not invent a user.

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

GitHub Actions reads the same names from repository secrets, then variables:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `GOOGLE_WEB_CLIENT_ID` (optional; the Google button stays disabled when it is empty)

Add them under Settings → Secrets and variables → Actions. Do not commit them.

`google-services.json` is gitignored and is not required for this HTTP client.

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

The Firebase emulator and the Supabase CLI were not run. There is no hosted
project to run them against.
