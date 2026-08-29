import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SessionStatus } from "./SessionStatus";
import { getSession } from "@/lib/api/session";

/**
 * Only the network boundary (`lib/api/session`) is mocked. These tests verify UI behavior
 * driven by whatever `userId` the mock resolves with -- they do not, and cannot, exercise
 * the real browser cookie round-trip AC-40 also requires (no real `fetch`/cookie jar in
 * jsdom); that part of AC-40 is covered separately by manual verification (see Spec
 * `chat:AC-40` evidence).
 */
jest.mock("@/lib/api/session");

const mockedGetSession = getSession as jest.MockedFunction<typeof getSession>;

describe("SessionStatus", () => {
  beforeEach(() => {
    mockedGetSession.mockReset();
  });

  it("renders the session id returned by the initial bootstrap call", async () => {
    mockedGetSession.mockResolvedValueOnce({ userId: "11111111-1111-1111-1111-111111111111" });

    render(<SessionStatus />);

    expect(
      await screen.findByText(/11111111-1111-1111-1111-111111111111/),
    ).toBeInTheDocument();
    expect(mockedGetSession).toHaveBeenCalledTimes(1);
  });

  it("confirms the cookie rode along when a recheck returns the same session id", async () => {
    const user = userEvent.setup();
    mockedGetSession.mockResolvedValueOnce({ userId: "same-id" }).mockResolvedValueOnce({ userId: "same-id" });

    render(<SessionStatus />);
    await screen.findByText(/same-id/);

    await user.click(screen.getByRole("button", { name: "다시 확인" }));

    expect(await screen.findByText(/동일한 세션/)).toBeInTheDocument();
    expect(mockedGetSession).toHaveBeenCalledTimes(2);
  });

  it("warns when a recheck returns a different session id (cookie not carried)", async () => {
    const user = userEvent.setup();
    mockedGetSession.mockResolvedValueOnce({ userId: "first-id" }).mockResolvedValueOnce({ userId: "second-id" });

    render(<SessionStatus />);
    await screen.findByText(/first-id/);

    await user.click(screen.getByRole("button", { name: "다시 확인" }));

    expect(await screen.findByText(/다른 세션/)).toBeInTheDocument();
  });

  it("shows an accessible error message when the bootstrap call fails", async () => {
    mockedGetSession.mockRejectedValueOnce(new Error("network down"));

    render(<SessionStatus />);

    expect(await screen.findByRole("alert")).toHaveTextContent("network down");
  });
});
