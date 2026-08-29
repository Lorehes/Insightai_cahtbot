"use client";

import { useEffect, useState } from "react";
import { listModels, type ModelResponse } from "@/lib/api/models";
import styles from "./ModelSelector.module.css";

type CatalogState =
  | { status: "loading" }
  | { status: "ready"; models: ModelResponse[] }
  | { status: "error"; message: string };

export interface ModelSelectorProps {
  /** Currently selected model id, or `null` before the catalog has loaded/chosen a default. */
  value: string | null;
  /** Fired once the catalog loads (with its default model id) and again on every user pick. */
  onChange: (modelId: string) => void;
}

/**
 * [Purpose] Card F3's model picker, Spec §5 "화면 (D3)" element ④: lists `GET /api/models`'s
 *   catalog (chat:AC-39 "개수·id가 일치") and reports the user's pick up to a parent, which is
 *   responsible for threading it into the next `POST .../messages` call (chat:AC-39 "선택한
 *   모델 id가 실제 전송 요청에 실린다" -- {@link ../chat-panel/ChatPanel}'s `model` prop, wired by
 *   {@link ../chat-workspace/ChatWorkspace}).
 * [Rationale] Fully controlled (`value`/`onChange`) rather than owning "which model is
 *   selected" as internal state -- {@link ../chat-workspace/ChatWorkspace} needs that same id
 *   to pass to {@link ../chat-panel/ChatPanel}, and keeping a single copy of it (in the
 *   parent) avoids the two ever disagreeing. This component still owns fetching the catalog
 *   itself (self-contained reference data, unrelated to the conversation list) and, once
 *   loaded, calls `onChange` once with the catalog's own default id (`ModelResponse.default`)
 *   so a parent does not have to duplicate "which one is the default" logic -- the backend
 *   already decided that (chat:AC-21).
 * [Caution] Reads `model.default`, never `model.isDefault` -- the wire field is literally
 *   named `"default"` (`@JsonProperty("default")` on the backend, `default` being a reserved
 *   Java word); see `lib/api/models.ts`'s `ModelResponse` [Caution].
 * [AC] chat:AC-39
 */
export function ModelSelector({ value, onChange }: ModelSelectorProps) {
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    listModels()
      .then((models) => {
        if (cancelled) return;
        setCatalog({ status: "ready", models });
        const defaultModel = models.find((model) => model.default) ?? models[0];
        if (defaultModel) onChange(defaultModel.id);
      })
      .catch((err: unknown) => {
        if (!cancelled) setCatalog({ status: "error", message: describeError(err) });
      });
    return () => {
      cancelled = true;
    };
    // Runs once on mount only -- `onChange` here exists solely to report the catalog's own
    // default pick, not to re-run on every parent render (same "read once" reasoning as
    // ChatPanel's own mount effect).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (catalog.status === "error") {
    return (
      <p role="alert" className={styles.error}>
        모델 목록을 불러오지 못했습니다: {catalog.message}
      </p>
    );
  }

  const models = catalog.status === "ready" ? catalog.models : [];

  return (
    <div className={styles.field}>
      <label htmlFor="model-selector" className={styles.label}>
        모델
      </label>
      <select
        id="model-selector"
        className={styles.select}
        value={value ?? ""}
        onChange={(e) => onChange(e.target.value)}
        disabled={catalog.status !== "ready"}
      >
        {catalog.status === "loading" && <option value="">모델 불러오는 중...</option>}
        {models.map((model) => (
          <option key={model.id} value={model.id}>
            {model.displayName}
            {model.default ? " (기본)" : ""}
          </option>
        ))}
      </select>
    </div>
  );
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
