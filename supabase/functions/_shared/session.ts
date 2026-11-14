export function sessionId(payload: { session_id?: string; jti?: string } | null): string | null {
  if (!payload) return null;
  const sid = payload.session_id ?? payload.jti;
  return typeof sid === "string" && sid.length >= 8 ? sid : null;
}
