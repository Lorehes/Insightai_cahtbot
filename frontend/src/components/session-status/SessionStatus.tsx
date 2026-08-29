"use client";

import { useCallback, useEffect, useState } from "react";
import { getSession, type SessionResponse } from "@/lib/api/session";
import styles from "./SessionStatus.module.css";

type Status = "loading" | "success" | "error";

/**
 * [Purpose] Card F1's minimal confirmation screen -- proves the Next.js rewrites +
 *   anonymous session bootstrap wiring actually works end to end in the browser
 *   (chat Spec AC-40). It is not the chat screen; F2/F3 own that (Spec §5 "화면 (D3)").
 * [Rationale] AC-40 is about the *browser's* cookie jar, not the Next.js server's, so
 *   bootstrap must run as a client-side fetch -- a Server Component's fetch runs in Node
 *   and never touches the browser's cookies. `chatbot_sid` is HttpOnly
 *   (AnonymousSessionFilter), so this component can never read the cookie value itself;
 *   it only observes the round trip through the `userId` the backend echoes in the
 *   response body (Tech Review A-2). Calling `GET /api/session` a second time and
 *   comparing the returned `userId` to the first is the indirect signal that the cookie
 *   rode along on the second call -- if it hadn't, AnonymousSessionFilter would have
 *   minted a brand-new user instead of resolving the existing one (chat Spec AC-12).
 * [Caution] Do not grow this into the real chat screen -- its only job is to prove the
 *   wiring works. The match/mismatch outcome is announced in text, not only via the
 *   `styles.match`/`styles.mismatch` color, so the result does not depend on color alone.
 * [AC] chat:AC-40
 */
export function SessionStatus() {
  const [status, setStatus] = useState<Status>("loading");
  const [first, setFirst] = useState<SessionResponse | null>(null);
  const [second, setSecond] = useState<SessionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getSession()
      .then((session) => {
        setFirst(session);
        setStatus("success");
      })
      .catch((err: unknown) => {
        setError(describeError(err));
        setStatus("error");
      });
  }, []);

  const recheck = useCallback(() => {
    getSession()
      .then((session) => setSecond(session))
      .catch((err: unknown) => {
        setError(describeError(err));
        setStatus("error");
      });
  }, []);

  if (status === "loading") {
    return <p role="status">세션 확인 중...</p>;
  }

  if (status === "error") {
    return (
      <p role="alert" className={styles.error}>
        세션 확인 실패: {error}
      </p>
    );
  }

  const matches = second !== null && first !== null && second.userId === first.userId;

  return (
    <div className={styles.card}>
      <p>
        세션 발급 완료 -- <code>{first?.userId}</code>
      </p>
      <button type="button" onClick={recheck} className={styles.button}>
        다시 확인
      </button>
      {second !== null && (
        <p className={matches ? styles.match : styles.mismatch}>
          {matches
            ? `재확인 결과 동일한 세션(${second.userId})입니다 -- 쿠키가 요청에 실리고 있습니다.`
            : `재확인 결과 다른 세션(${second.userId})이 발급됐습니다 -- 쿠키가 요청에 실리지 않고 있습니다.`}
        </p>
      )}
    </div>
  );
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
