import styles from "./TruncatedCell.module.css";

/**
 * [Purpose] Two list-cell primitives for untrusted vs. predictable text (Spec §2 "목록 셀의
 *   전제"): `TruncatedCell` for `documents.filename` (untrusted, up to 255 chars, no
 *   whitespace guaranteed), `ErrorMessageCell` for `documents.error_message` (fixed, short,
 *   predictable English strings).
 * [Rationale] Two functions, not one component with a `variant` prop -- Spec §2's "두 컬럼을
 *   같은 셀 패턴으로 다루면 안 된다" is enforced at the type level this way: a caller cannot
 *   accidentally pass `variant="truncated"` for an error message column, because there is no
 *   such prop to get wrong (Spec §Tech Review AD-7). `title` on `TruncatedCell` always carries
 *   the full, untruncated `text` -- AC-14 requires the original be recoverable without loss,
 *   and a caller that pre-truncated `text` before calling this component would defeat that
 *   guarantee, so this component never truncates the string itself, only the CSS does
 *   (visually).
 * [Caution] Column-width constraints (`table-layout: fixed`, a header cell `width`) are
 *   `document-ingestion` T11's job, not this component's -- the prototype has no filename
 *   column width token, so none is invented here (Spec §Tech Review AD-8(c), 창작 금지).
 * [AC] design-system:AC-14, design-system:AC-15
 */
export function TruncatedCell({ text }: { text: string }) {
  return (
    <span className={styles.truncated} title={text}>
      {text}
    </span>
  );
}

/**
 * [Purpose] Renders `documents.error_message` -- a short, predictable string, shown in full
 *   inside its cell rather than truncated (Spec §2 "목록 셀의 전제").
 * [AC] design-system:AC-15
 */
export function ErrorMessageCell({ text }: { text: string }) {
  return <span className={styles.errorMessage}>{text}</span>;
}
