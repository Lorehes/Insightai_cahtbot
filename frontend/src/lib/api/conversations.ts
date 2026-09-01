import { apiFetch, apiFetchVoid } from "./client";

/**
 * [Purpose] Response body for `POST /api/conversations` (chat Spec Tech Review A-11,
 *   confirmed against `chat/api/ConversationController.ConversationResponse`).
 * [Caution] `title` is `null` until the conversation's first message has been sent
 *   (`ConversationController` [Caution]) -- always `null` for a value returned by
 *   {@link createConversation}. This card never reads `title`/`model`/`ragEnabled`; they
 *   are typed here only so the shape matches the wire contract exactly for whoever wires
 *   the conversation list next (chat:AC-38, card F3).
 */
export interface ConversationResponse {
  id: string;
  title: string | null;
  model: string;
  ragEnabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * [Purpose] Calls `POST /api/conversations` to create a new conversation for the current
 *   anonymous session, so {@link ../../components/chat-panel/ChatPanel} has an id to send
 *   messages against.
 * [Rationale] Card F2 does not own conversation *management* -- creating/selecting/
 *   deleting from a list is chat:AC-38, card F3's scope. This call exists only because
 *   `POST /api/conversations/{id}/messages` (chat:AC-36) requires an id to already exist.
 *   No model or RAG-toggle override is sent, so the backend's own default model applies
 *   (chat Spec Tech Review A-11) -- picking a model is chat:AC-39, also card F3's scope.
 * [AC] chat:AC-36
 */
export function createConversation(): Promise<ConversationResponse> {
  return apiFetch<ConversationResponse>("/api/conversations", { method: "POST" });
}

/**
 * [Purpose] One entry of `GET /api/conversations` (chat Spec Tech Review A-11, confirmed
 *   against `ConversationController.ConversationSummaryResponse`) -- what card F3's sidebar
 *   lists (chat:AC-38).
 * [Caution] Deliberately narrower than {@link ConversationResponse} -- no `createdAt`. Do not
 *   widen this type to match {@link ConversationResponse}; the backend's list endpoint
 *   actually returns fewer fields, and a type that claimed more than the wire sends would let
 *   a caller read `undefined` silently instead of failing loudly.
 */
export interface ConversationSummaryResponse {
  id: string;
  title: string | null;
  model: string;
  ragEnabled: boolean;
  updatedAt: string;
}

/**
 * [Purpose] Calls `GET /api/conversations` to list the current session's conversations, for
 *   card F3's sidebar (chat:AC-38).
 * [Rationale] The backend already returns them non-deleted and ordered by `updatedAt` DESC
 *   (`ConversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc`, confirmed
 *   against `ConversationController#list` for this card) -- this function does not re-filter
 *   or re-sort the result, to avoid a second copy of that logic drifting from the backend's.
 * [AC] chat:AC-38
 */
export function listConversations(): Promise<ConversationSummaryResponse[]> {
  return apiFetch<ConversationSummaryResponse[]>("/api/conversations");
}

/**
 * [Purpose] Calls `DELETE /api/conversations/{id}` to soft-delete a conversation, for card
 *   F3's sidebar delete action (chat:AC-38).
 * [Rationale] Routed through {@link apiFetchVoid}, not {@link apiFetch} -- this endpoint
 *   returns `204 No Content` (`ConversationController#delete` [Rationale]), and {@link
 *   apiFetch} always awaits `response.json()`, which throws on an empty body. `id` is
 *   URL-encoded the same way `lib/api/messages.ts`'s `streamAssistantReply` already does for
 *   the same path shape, for consistency.
 * [Caution] A 404 here (surfaced as {@link ApiError}) covers three indistinguishable causes
 *   on the backend -- unknown id, another session's conversation, or an already-deleted one
 *   (`ConversationNotFoundException` [Rationale]) -- a caller must not guess which one
 *   occurred from this call alone (same guidance applies to `lib/api/messages.ts`'s
 *   `getMessages`).
 * [AC] chat:AC-38
 */
export function deleteConversation(id: string): Promise<void> {
  return apiFetchVoid(`/api/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/**
 * [Purpose] Response body for `PATCH /api/conversations/{id}` (chat Spec §5 A-11, confirmed
 *   against `ConversationController.ConversationRagEnabledResponse`).
 * [Caution] Deliberately narrower than {@link ConversationResponse} -- this endpoint updates
 *   only `ragEnabled` and echoes back just `{id, ragEnabled}`, not the full conversation
 *   resource (`ConversationController#updateRagEnabled` [Rationale]: returning the whole
 *   resource would imply a broader "update conversation" contract this endpoint does not
 *   implement).
 */
export interface ConversationRagEnabledResponse {
  id: string;
  ragEnabled: boolean;
}

/**
 * [Purpose] Calls `PATCH /api/conversations/{id}` with body `{ragEnabled}` to flip the
 *   conversation-scoped RAG toggle, for the toggle UI (chat-prototype-port Spec T5).
 * [Rationale] This function (card T1) owns only the path/method/body/credentials contract --
 *   whichever `ragEnabled` value the caller passes in is sent through unchanged. Deciding
 *   *that* value is the negation of the toggle's current state, and reflecting the response
 *   in `aria-checked`, is card T5's scope (chat-prototype-port Spec T1 card notes), not this
 *   function's. Routed through {@link apiFetch}, not {@link apiFetchVoid}, because this
 *   endpoint returns `200` with a body (unlike {@link deleteConversation}'s `204`).
 * [AC] chat-prototype-port:AC-10 (path/method/body/credentials only -- see card T1 notes)
 */
export function updateRagEnabled(id: string, ragEnabled: boolean): Promise<ConversationRagEnabledResponse> {
  return apiFetch<ConversationRagEnabledResponse>(`/api/conversations/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ ragEnabled }),
  });
}
