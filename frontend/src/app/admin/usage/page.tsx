import { UsageDashboard } from "@/components/usage-dashboard/UsageDashboard";

/**
 * [Purpose] Route entry for `/admin/usage` (eval-usage-dashboard Spec Tech Review card U3) --
 *   renders under the shared `app/admin/layout.tsx` shell.
 * [Rationale] Thin composition root with no test/state of its own, mirroring
 *   `app/admin/documents/page.tsx`'s own pattern one card over -- all real logic/state lives in
 *   {@link UsageDashboard}, tested independently.
 * [AC] eval-usage-dashboard:AC-18
 */
export default function AdminUsagePage() {
  return <UsageDashboard />;
}
