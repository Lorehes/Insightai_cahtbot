import { render, screen } from "@testing-library/react";
import AdminLayout from "./layout";

/** `usePathname` is the only `next/navigation` export this layout uses -- mocked directly
 *  rather than pulling in a full router/provider, mirroring how `SessionStatus.test.tsx`/
 *  `conversations.test.ts` mock only the boundary a component actually calls. */
const mockUsePathname = jest.fn<string, []>();
jest.mock("next/navigation", () => ({
  usePathname: () => mockUsePathname(),
}));

describe("AdminLayout", () => {
  beforeEach(() => {
    mockUsePathname.mockReset();
  });

  it("renders the 2 nav items in prototype order (문서 관리/사용량), with no 채팅 tab", () => {
    mockUsePathname.mockReturnValue("/admin/documents");

    render(
      <AdminLayout>
        <p>content</p>
      </AdminLayout>,
    );

    const nav = screen.getByRole("navigation");
    const links = screen.getAllByRole("link", { name: /문서 관리|사용량|채팅/ }).filter((link) => nav.contains(link));
    expect(links.map((link) => link.textContent)).toEqual(["문서 관리", "사용량"]);
    expect(links.map((link) => link.getAttribute("href"))).toEqual(["/admin/documents", "/admin/usage"]);
    expect(screen.queryByRole("link", { name: "채팅" })).not.toBeInTheDocument();
  });

  it("marks only the current route's tab active, via aria-current", () => {
    mockUsePathname.mockReturnValue("/admin/documents");

    render(
      <AdminLayout>
        <p>content</p>
      </AdminLayout>,
    );

    expect(screen.getByRole("link", { name: "문서 관리" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "사용량" })).not.toHaveAttribute("aria-current");
  });

  it("moves the active tab when the route changes", () => {
    mockUsePathname.mockReturnValue("/admin/usage");

    render(
      <AdminLayout>
        <p>content</p>
      </AdminLayout>,
    );

    expect(screen.getByRole("link", { name: "사용량" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "문서 관리" })).not.toHaveAttribute("aria-current");
  });

  it("marks no tab active on an unrelated path", () => {
    mockUsePathname.mockReturnValue("/admin/somewhere-else");

    render(
      <AdminLayout>
        <p>content</p>
      </AdminLayout>,
    );

    for (const label of ["문서 관리", "사용량"]) {
      expect(screen.getByRole("link", { name: label })).not.toHaveAttribute("aria-current");
    }
  });

  it("renders a link back to the chat screen (채팅으로 나가기)", () => {
    mockUsePathname.mockReturnValue("/admin/documents");

    render(
      <AdminLayout>
        <p>content</p>
      </AdminLayout>,
    );

    const exitLink = screen.getByRole("link", { name: /채팅으로 나가기/ });
    expect(exitLink).toHaveAttribute("href", "/");
  });

  it("renders children inside the content slot", () => {
    mockUsePathname.mockReturnValue("/admin/documents");

    render(
      <AdminLayout>
        <p>document manager screen</p>
      </AdminLayout>,
    );

    expect(screen.getByText("document manager screen")).toBeInTheDocument();
  });
});
