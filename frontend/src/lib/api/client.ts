/**
 * [Purpose] Single choke point every data-access module uses to call the backend,
 *   instead of components calling `fetch()` directly (project rule: server data must go
 *   through the data-access layer, never straight from a component).
 * [Rationale] Two things every same-origin call to `/api/*` needs are centralized here so
 *   they are never repeated (or forgotten) per call site: an explicit `credentials` mode
 *   (see [Caution]) and one shared error type so callers branch on {@link ApiError.status}
 *   instead of re-parsing failure responses individually.
 * [Caution] `path` must be root-relative (e.g. "/api/session"), never an absolute URL to
 *   the backend origin (e.g. "http://127.0.0.1:8080/..."). Calling the backend origin
 *   directly from the browser bypasses next.config.ts's `rewrites` and turns the request
 *   cross-origin, which drops the SameSite=Lax session cookie (chat Spec AC-40, OQ-11) --
 *   the whole point of the rewrites proxy is to keep every request on one origin.
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    // A browser's fetch() already defaults `credentials` to "same-origin". This is set
    // explicitly, and after the `...init` spread so a caller cannot override it away, so
    // the session-cookie contract (chat Spec AC-40) never silently depends on an unstated
    // default or an accidental override.
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await describeFailure(response));
  }

  return (await response.json()) as T;
}

/**
 * [Purpose] Same as {@link apiFetch} (same-origin credentials enforced the same way, same
 *   shared {@link ApiError} on failure) for an endpoint whose success response is `204 No
 *   Content` -- e.g. `DELETE /api/conversations/{id}` (chat Spec Tech Review §5 A-11, card F3).
 * [Rationale] {@link apiFetch} always awaits `response.json()`, which throws (`SyntaxError:
 *   Unexpected end of JSON input`) on an empty 204 body -- there is no JSON to parse.
 *   `lib/api/messages.ts`'s `streamAssistantReply` [Rationale] documents the same kind of
 *   body-shape mismatch at the opposite extreme (an unbounded stream instead of no body at
 *   all) and, for the same reason, does not reuse {@link apiFetch} either. Kept in this shared
 *   choke-point file rather than duplicated per caller so every 204 endpoint gets the same
 *   credentials/error handling {@link apiFetch} already gives every JSON endpoint.
 * [Caution] Returns nothing -- a 204 response has no body to read. A caller that needs the
 *   changed resource back must call an endpoint that actually returns one (e.g. this
 *   project's `PATCH /api/conversations/{id}` does; `DELETE` does not, by design).
 */
export async function apiFetchVoid(path: string, init?: RequestInit): Promise<void> {
  const response = await fetch(path, {
    ...init,
    // Same guarantee as apiFetch, and for the same reason (chat Spec AC-40, OQ-11) -- see
    // apiFetch's own [Caution].
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await describeFailure(response));
  }
}

/**
 * [Purpose] Thrown by {@link apiFetch} for a non-2xx response so callers can branch on
 *   `status` (e.g. tell a 503 "server busy" apart from other failures) without re-parsing
 *   the response body themselves.
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Reads the response body for the error message, falling back to the status line alone
 *  when the body can't be read as text. */
async function describeFailure(response: Response): Promise<string> {
  try {
    const text = await response.text();
    return text.length > 0
      ? `${response.status} ${response.statusText}: ${text}`
      : `${response.status} ${response.statusText}`;
  } catch {
    return `${response.status} ${response.statusText}`;
  }
}
