"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "./layout.module.css";

/**
 * [Purpose] Left-rail nav items for every `/admin/**` screen -- document-ingestion Spec Tech
 *   Review card T11's "공유 관리자 셸", corrected 2026-08-30 to match the actual prototype
 *   (blob `09e01276`): a 2-item side rail (문서 관리/사용량), no "채팅" tab. The earlier 3-item
 *   top-bar version (with 채팅) came from a coordinator instruction that turned out to
 *   contradict the prototype and has been reverted; this is a coordinator correction, not a
 *   review-driven rework.
 * [Caution] `href` is matched exactly against `usePathname()` below (no prefix matching) --
 *   correct today because neither route has a nested sub-route of its own. A future card
 *   adding one (e.g. `/admin/documents/[id]`) needs to switch this to a prefix check, or that
 *   sub-route will render with no active tab highlighted.
 */
const NAV_ITEMS: { href: string; label: string }[] = [
  { href: "/admin/documents", label: "문서 관리" },
  { href: "/admin/usage", label: "사용량" },
];

/**
 * [Purpose] Shared shell for every `/admin/**` route (document-ingestion Spec Tech Review T11)
 *   -- a left side rail (branding + nav + exit link) plus a content slot.
 *   `app/admin/documents/page.tsx` (this card) is its first consumer; `eval-usage-dashboard`
 *   card U3 (`/admin/usage`) is expected to reuse this same layout by adding a page under it.
 * [Rationale] Kept deliberately app-agnostic -- no document- or usage-specific markup lives
 *   here, only the rail every admin screen shares. `usePathname` (not a prop) drives the
 *   active tab so this stays self-contained: a new admin page can sit under it without wiring
 *   anything back up to this file. Layout matches the prototype (blob `09e01276`): side rail
 *   sized by `--app-admin-nav-width` (previously unused -- it is this rail's width, not a dead
 *   token), branding block, 2-item nav, bottom exit link back to `/`.
 * [Caution] The active tab is marked two ways at once (`aria-current="page"` plus a distinct
 *   CSS class, `navItemActive`) -- the same "color is not the only channel" rule
 *   `ConversationSidebar`'s own `aria-current` usage follows.
 * [AC] document-ingestion:AC-14
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <div className={styles.shell}>
      <aside className={styles.rail} aria-label="관리자 메뉴">
        <div className={styles.brand}>
          <div className={styles.brandRow}>
            <span className={styles.brandDot} aria-hidden="true" />
            <span className={styles.brandName}>insight</span>
          </div>
          <div className={styles.brandSub}>관리자</div>
        </div>
        <nav className={styles.nav}>
          <ul className={styles.navList}>
            {NAV_ITEMS.map((item) => {
              const isActive = pathname === item.href;
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    className={isActive ? styles.navItemActive : styles.navItem}
                    aria-current={isActive ? "page" : undefined}
                  >
                    <span className={styles.navMarker} aria-hidden="true" />
                    <span className={styles.navLabel}>{item.label}</span>
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
        <div className={styles.exit}>
          <Link href="/" className={styles.exitLink}>
            <span aria-hidden="true">←</span> 채팅으로 나가기
          </Link>
        </div>
      </aside>
      <main className={styles.content}>{children}</main>
    </div>
  );
}
