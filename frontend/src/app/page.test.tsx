import { render, screen } from "@testing-library/react";
import Home from "./page";
import { createConversation, listConversations } from "@/lib/api/conversations";
import { listModels } from "@/lib/api/models";

/**
 * [Purpose] Proves the h1/h2-absence clauses of `chat-prototype-port:AC-1` -- the dev-only top
 *   section (`insight-chat` `h1`, `채팅`/`세션 부트스트랩 확인 (F1)` `h2`s) that used to live in
 *   `page.tsx` is gone. `ChatWorkspace.test.tsx` cannot prove this (it never renders `page.tsx`
 *   itself, only `ChatWorkspace`), so per Spec §8 AD-3 this file owns those two clauses and
 *   `ChatWorkspace.test.tsx` owns the third (`aside`/main sibling structure).
 * [Rationale] Only the network boundary (`lib/api/*`) is mocked, matching every other screen-
 *   level test in this codebase (`ChatWorkspace.test.tsx`, `dom-contract.test.tsx`) -- `Home`
 *   renders the real `ChatWorkspace` tree, so a regression that reintroduces the dev-only
 *   section anywhere under `Home` (not just literally in `page.tsx`) would still fail here.
 * [Caution] `chat-prototype-port:AC-1`'s "부재(absence)" clauses only mean something if a
 *   positive control exists -- `SessionStatus.tsx` renders no heading elements at all (checked
 *   2026-09-01: no `<h1>`/`<h2>` anywhere in that component), so it cannot serve as that proof.
 *   The test below instead renders the exact `h1`/`h2` markup as a local fixture first and
 *   asserts it *is* found (length 1 each) before rendering `Home` and asserting 0 -- this is the
 *   positive control, kept in this file rather than relying on an unrelated component
 *   (INC-013 부류 2: a query that could never match anything makes "0 found" a tautology).
 */
jest.mock("@/lib/api/conversations");
jest.mock("@/lib/api/models");
jest.mock("@/lib/api/messages", () => ({
  ...jest.requireActual("@/lib/api/messages"),
  streamAssistantReply: jest.fn(),
  getMessages: jest.fn(),
}));

const mockedListConversations = listConversations as jest.MockedFunction<typeof listConversations>;
const mockedCreateConversation = createConversation as jest.MockedFunction<typeof createConversation>;
const mockedListModels = listModels as jest.MockedFunction<typeof listModels>;

describe("Home (/)", () => {
  beforeEach(() => {
    mockedListConversations.mockReset().mockResolvedValue([]);
    // ChatWorkspace's mount effect leaves `selectedId` null on an empty conversation list, which
    // makes `ChatPanel` auto-create one via `POST /api/conversations` (its own unrelated,
    // unchanged auto-create behavior, see `ChatWorkspace.tsx`'s own [Caution]) -- unmocked, this
    // throws "Cannot read properties of undefined (reading 'then')" inside `ChatPanel.tsx`.
    mockedCreateConversation.mockReset().mockResolvedValue({
      id: "new-conv",
      title: null,
      model: "upstage/solar-pro4",
      ragEnabled: false,
      createdAt: "2026-08-28T00:00:00Z",
      updatedAt: "2026-08-28T00:00:00Z",
    });
    mockedListModels.mockReset().mockResolvedValue([
      { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
    ]);
  });

  it("[chat-prototype-port:AC-1] renders no insight-chat h1 and no 채팅/세션 부트스트랩 확인 (F1) h2", async () => {
    // Positive control (review 2026-09-01 High-1): prove these exact queries CAN match a real
    // h1/h2 pair before asserting Home has none -- otherwise "0 found" below would be
    // indistinguishable from a query that could never match anything in the first place.
    const control = render(
      <>
        <h1>insight-chat</h1>
        <h2>채팅</h2>
        <h2>세션 부트스트랩 확인 (F1)</h2>
      </>,
    );
    expect(screen.queryAllByRole("heading", { level: 1, name: "insight-chat" })).toHaveLength(1);
    expect(screen.queryAllByRole("heading", { level: 2, name: "채팅" })).toHaveLength(1);
    expect(screen.queryAllByRole("heading", { level: 2, name: "세션 부트스트랩 확인 (F1)" })).toHaveLength(1);
    control.unmount();

    render(<Home />);
    // Waits for ChatWorkspace's mount effects (listConversations/listModels) to settle before
    // asserting absence -- otherwise a "not yet rendered" false negative for a section that
    // actually does exist later would be indistinguishable from a real absence.
    await screen.findByRole("textbox");
    // review 2026-09-01 Low-2: ties this test to AC-1's third clause (aside/main sibling
    // structure) too, instead of relying solely on the composer textbox appearing.
    expect(screen.getByRole("complementary", { name: "대화 목록" })).toBeInTheDocument();

    const h1s = screen.queryAllByRole("heading", { level: 1, name: "insight-chat" });
    expect(h1s).toHaveLength(0);

    const chatH2s = screen.queryAllByRole("heading", { level: 2, name: "채팅" });
    expect(chatH2s).toHaveLength(0);

    const bootstrapH2s = screen.queryAllByRole("heading", { level: 2, name: "세션 부트스트랩 확인 (F1)" });
    expect(bootstrapH2s).toHaveLength(0);
  });
});
