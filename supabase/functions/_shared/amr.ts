export type AmrEntry = { method?: string; timestamp?: number };

const INTERACTIVE = new Set([
  "password",
  "otp",
  "oauth",
  "magiclink",
  "email",
  "sso",
  "totp",
  "google",
  "sign_in",
]);

const NON_INTERACTIVE = new Set(["anonymous", "token_refresh", "refresh"]);

export function jwtPayload(
  authHeader: string,
): { sub?: string; iat?: number; amr?: AmrEntry[] } | null {
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  const parts = token.split(".");
  if (parts.length < 2) return null;
  try {
    return JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return null;
  }
}

export function authEventTimestamp(payload: { amr?: AmrEntry[] }): number | null {
  const amr = payload.amr;
  if (!Array.isArray(amr) || amr.length === 0) return null;
  let max = 0;
  for (const entry of amr) {
    const ts = entry?.timestamp;
    if (typeof ts !== "number" || ts < 1) continue;
    const method = (entry?.method ?? "").toLowerCase();
    if (NON_INTERACTIVE.has(method)) continue;
    if (!INTERACTIVE.has(method)) continue;
    if (ts > max) max = ts;
  }
  return max > 0 ? max : null;
}
