import { apiFetch } from "./client";

/**
 * [Purpose] Response body for `GET /api/session` (chat Spec Tech Review A-11).
 * [Caution] `userId` duplicates the value already carried by the `chatbot_sid` cookie --
 *   the backend deliberately echoes it in the body too (Tech Review A-2) because that
 *   cookie is HttpOnly and this app has no other way to read it. Do not treat this id as
 *   more sensitive than the cookie itself: it is exactly as exposed, by the same accepted
 *   Spec R-6 trade-off (no auth in stage 1 -- knowing the id already grants full access).
 */
export interface SessionResponse {
  userId: string;
}

/**
 * [Purpose] Calls `GET /api/session` to bootstrap (or confirm) the anonymous session.
 * [Rationale] Data-access layer entry point for the session endpoint (project rule:
 *   components never call `fetch()` directly). Every screen that needs to prove or use
 *   the session should call this instead of re-implementing the request.
 * [AC] chat:AC-40
 */
export function getSession(): Promise<SessionResponse> {
  return apiFetch<SessionResponse>("/api/session");
}
