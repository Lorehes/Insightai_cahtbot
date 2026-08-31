import { DocumentManager } from "@/components/document-manager/DocumentManager";

/**
 * [Purpose] Route entry for `/admin/documents` (document-ingestion Spec Tech Review card T11)
 *   -- renders under the shared `app/admin/layout.tsx` shell.
 * [Rationale] Thin composition root with no test/state of its own, mirroring `app/page.tsx`'s
 *   own pattern one Spec over -- all real logic/state lives in {@link DocumentManager}, tested
 *   independently.
 * [AC] document-ingestion:AC-14
 */
export default function AdminDocumentsPage() {
  return <DocumentManager />;
}
