import styles from "./StatusBadge.module.css";

/**
 * [Purpose] The 5 lifecycle states a `documents` row can be in, mirrored from the backend's
 *   `DocumentStatus` enum (`backend/.../rag/domain/DocumentStatus.java`) so this component's
 *   prop type and the wire values `document-ingestion` T11 will consume line up exactly.
 * [Rationale] Defined here, not in `lib/api/documents.ts`, because that file is T11's (Spec
 *   §2 In scope 1, `insight-chat-4c` decision 2026-08-28) -- T11 imports this type or stays
 *   structurally compatible with it.
 */
export type DocumentStatus = "PENDING" | "PARSING" | "EMBEDDING" | "INDEXED" | "FAILED";

const LABELS: Record<DocumentStatus, string> = {
  PENDING: "대기 중",
  PARSING: "파싱 중",
  EMBEDDING: "임베딩 중",
  INDEXED: "색인 완료",
  FAILED: "실패",
};

const CLASS_NAMES: Record<DocumentStatus, string> = {
  PENDING: styles.pending,
  PARSING: styles.parsing,
  EMBEDDING: styles.embedding,
  INDEXED: styles.indexed,
  FAILED: styles.failed,
};

/**
 * [Purpose] Renders a document's ingestion status as a small labelled badge (Spec §2 "상태
 *   배지 프리미티브"). Pure presentation -- no fetch, no polling; a caller (`document-ingestion`
 *   T11) re-renders it as the status changes.
 * [Rationale] `--app-status-active` (PARSING/EMBEDDING) and `--app-status-ok` (INDEXED) are the
 *   same color (`#222222`, Spec §2 table) -- color alone cannot distinguish "in progress" from
 *   "done". The Korean text label is therefore load-bearing, not decorative (Spec §8 D3):
 *   `document-ingestion` T11's polling UI relies on the label changing from "임베딩 중" to
 *   "색인 완료" as the only signal that polling has stopped. No runtime fallback branch exists
 *   for an unrecognized status -- `DocumentStatus` is a closed union, so an invalid value is a
 *   compile-time error, not a 6th rendered state (Spec §Tech Review AD-7: a fallback would blur
 *   AC-13's "5 states render 5 distinct labels" requirement).
 * [Caution] `data-status` is a stable, wording-independent anchor for `document-ingestion` T11's
 *   own tests to observe status without asserting on the Korean label text -- AD-9's label
 *   strings are this Spec's own choice (AC-13 only requires "5 distinct labels", not these
 *   specific words), so a text-based assertion in T11 would misattribute a future label wording
 *   change here as T11's own regression (Spec §Tech Review AD-7a).
 * [AC] design-system:AC-12, design-system:AC-13
 */
export function StatusBadge({ status }: { status: DocumentStatus }) {
  return (
    <span className={CLASS_NAMES[status]} data-status={status}>
      <StatusIcon status={status} />
      {LABELS[status]}
    </span>
  );
}

/** Decorative glyph per state (prototype blob `353949a8`: clock / spinner / check / warning).
 *  `aria-hidden` and text-free, so the badge's text content is still exactly the label. */
function StatusIcon({ status }: { status: DocumentStatus }) {
  const common = { width: 12, height: 12, viewBox: "0 0 12 12", "aria-hidden": true as const, className: styles.icon };
  switch (status) {
    case "PENDING":
      return (
        <svg {...common}>
          <circle cx="6" cy="6" r="4.6" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <path d="M6 3.4 V6 L8 7.2" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      );
    case "PARSING":
    case "EMBEDDING":
      return (
        <svg {...common}>
          <circle
            cx="6"
            cy="6"
            r="4.6"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeDasharray="10 20"
          />
        </svg>
      );
    case "INDEXED":
      return (
        <svg {...common}>
          <path
            d="M1.6 6.3 L4.4 9.1 L10.4 3.1"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      );
    case "FAILED":
      return (
        <svg {...common}>
          <path d="M6 1.4 L11.2 10.4 H0.8 Z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
          <path d="M6 4.9 V7.1" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          <circle cx="6" cy="8.8" r="0.75" fill="currentColor" />
        </svg>
      );
  }
}
