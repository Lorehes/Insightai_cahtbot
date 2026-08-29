import { apiFetch } from "./client";

/**
 * [Purpose] Response body for one entry of `GET /api/models` (chat Spec Tech Review A-11,
 *   confirmed against `ModelsController.ModelResponse`) -- the catalog card F3's model picker
 *   renders (chat:AC-39).
 * [Caution] The wire field is `"default"`, not `"isDefault"` -- the backend's Java record
 *   component is named `isDefault` but serialized under `@JsonProperty("default")` because
 *   `default` is a reserved word and cannot be a Java record component name
 *   (`ModelsController.ModelResponse` [Caution]). This TS interface names the field `default`
 *   to match the actual JSON, not the Java source name. Reading `.isDefault` on a value of
 *   this type is a silent `undefined` at runtime, not a compile error (TypeScript does not
 *   flag reading a property absent from an object's declared shape as an error the way it
 *   flags an absent required one on write) -- always read `.default`.
 * [AC] chat:AC-39
 */
export interface ModelResponse {
  id: string;
  displayName: string;
  default: boolean;
}

/**
 * [Purpose] Calls `GET /api/models` to list the chat model catalog, for card F3's model
 *   picker (chat:AC-39).
 * [Rationale] The backend returns a fixed catalog sourced from `application.yaml`
 *   (`ModelsController` [Rationale]), not a live OpenRouter call -- the response is
 *   deterministic, which is why this card's tests can assert the picker's item count/ids
 *   against a mocked response without a real backend or network access.
 * [AC] chat:AC-39
 */
export function listModels(): Promise<ModelResponse[]> {
  return apiFetch<ModelResponse[]>("/api/models");
}
