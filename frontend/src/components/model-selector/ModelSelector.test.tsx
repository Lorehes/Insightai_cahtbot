import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ModelSelector } from "./ModelSelector";
import { listModels } from "@/lib/api/models";

/**
 * Only the network boundary (`lib/api/models`) is mocked; the popover rendering and
 * default-model selection logic under test are real. `CATALOG` intentionally has more
 * than one non-default model so tests distinguishing "the default" from "a picked
 * non-default" (AC-39's discriminating case) are meaningful.
 *
 * Card T11 (chat-prototype-port) rewrote every test body below onto the trigger-button +
 * `role="listbox"` popover shape (native `<select>` removed) -- the 6 pre-existing test
 * *names* are left byte-for-byte unchanged (`design-tokens.test.ts`'s `D1_BASELINE_NAMES`
 * baseline, chat-prototype-port:AC-29) even though every body underneath them changed.
 * Options are looked up by `data-model-id` (never by accessible name alone) because an
 * option's accessible name can also carry its "기본" note text (AC-33), which would make a
 * name-based query for the bare `displayName` ambiguous for the default model.
 *
 * Card T12 adds the keyboard/focus/outside-click tests at the bottom (AC-34~38) -- all new
 * names, no baseline collision. `document.activeElement` is asserted directly rather than
 * `toHaveFocus()` in a couple of spots where the assertion also needs to identify *which*
 * option element is focused (`toHaveFocus()` only answers yes/no for a node already in hand).
 */
jest.mock("@/lib/api/models");

const mockedListModels = listModels as jest.MockedFunction<typeof listModels>;

const CATALOG = [
  { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
  { id: "some/other-model", displayName: "Other Model", default: false },
  { id: "third/model", displayName: "Third Model", default: false },
];

function findOptionById(options: HTMLElement[], modelId: string): HTMLElement {
  const found = options.find((option) => option.getAttribute("data-model-id") === modelId);
  if (!found) throw new Error(`no option with data-model-id="${modelId}"`);
  return found;
}

describe("ModelSelector", () => {
  beforeEach(() => {
    mockedListModels.mockReset();
  });

  it("shows a disabled loading placeholder before the catalog resolves", () => {
    mockedListModels.mockReturnValue(new Promise(() => {})); // never resolves in this test

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    expect(screen.getByRole("button")).toBeDisabled();
  });

  it("[chat:AC-39] renders exactly the models GET /api/models returned, matching id and count", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);

    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(CATALOG.length);
    expect(options.map((o) => o.getAttribute("data-model-id"))).toEqual(CATALOG.map((m) => m.id));
  });

  it("calls onChange once with the catalog's default model id once loaded", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const onChange = jest.fn();

    render(<ModelSelector value={null} onChange={onChange} />);

    await waitFor(() => expect(onChange).toHaveBeenCalledWith("upstage/solar-pro4"));
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("[chat:AC-39] picking a different model calls onChange with the newly picked id", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const onChange = jest.fn();
    const user = userEvent.setup();

    render(<ModelSelector value="upstage/solar-pro4" onChange={onChange} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    onChange.mockClear(); // drop the initial "default picked" call from the mount effect
    await user.click(trigger);

    const target = findOptionById(screen.getAllByRole("option"), "some/other-model");
    await user.click(target);

    expect(onChange).toHaveBeenCalledWith("some/other-model");
  });

  it("marks the default model's option label so it is distinguishable from the others", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value={null} onChange={jest.fn()} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);

    const options = screen.getAllByRole("option");
    const defaultOption = findOptionById(options, "upstage/solar-pro4");
    const otherOption = findOptionById(options, "some/other-model");

    expect(within(defaultOption).getByText("기본")).toBeInTheDocument();
    expect(within(otherOption).queryByText("기본")).not.toBeInTheDocument();
  });

  it("shows an accessible error when the catalog fails to load", async () => {
    mockedListModels.mockRejectedValue(new Error("network down"));

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("network down");
  });

  it("[chat-prototype-port:AC-30] the trigger is a #model-selector button with aria-haspopup=listbox, closed by default, whose accessible name includes the selected model's displayName", async () => {
    mockedListModels.mockResolvedValue(CATALOG);

    render(<ModelSelector value="some/other-model" onChange={jest.fn()} />);

    const trigger = await screen.findByRole("button", { name: "Other Model" });
    expect(trigger).toHaveAttribute("id", "model-selector");
    expect(trigger).toHaveAttribute("aria-haspopup", "listbox");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("[chat-prototype-port:AC-31] opening the popover renders exactly one listbox whose option data-model-id set matches the catalog's id set", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value={null} onChange={jest.fn()} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());

    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    await user.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const listboxes = screen.getAllByRole("listbox");
    expect(listboxes).toHaveLength(1);
    const optionIds = within(listboxes[0]).getAllByRole("option").map((o) => o.getAttribute("data-model-id"));
    expect(optionIds).toEqual(CATALOG.map((m) => m.id));
  });

  it("[chat-prototype-port:AC-32] exactly one option is aria-selected=true with a single check icon, and the rest have neither", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value="some/other-model" onChange={jest.fn()} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);

    const options = screen.getAllByRole("option");
    const selected = options.filter((o) => o.getAttribute("aria-selected") === "true");
    expect(selected).toHaveLength(1);
    expect(selected[0]).toHaveAttribute("data-model-id", "some/other-model");
    expect(within(selected[0]).getByTestId("model-option-check")).toBeInTheDocument();

    const unselected = options.filter((o) => o !== selected[0]);
    expect(unselected).toHaveLength(CATALOG.length - 1);
    unselected.forEach((option) => {
      expect(option).toHaveAttribute("aria-selected", "false");
      expect(within(option).queryByTestId("model-option-check")).not.toBeInTheDocument();
    });
  });

  it("[chat-prototype-port:AC-33] only the default model's option renders the 기본 note, and no other note text is rendered", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value={null} onChange={jest.fn()} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);

    const options = screen.getAllByRole("option");
    const withNote = options.filter((o) => within(o).queryByText("기본") !== null);
    expect(withNote).toHaveLength(1);
    expect(withNote[0]).toHaveAttribute("data-model-id", "upstage/solar-pro4");
  });

  it("[chat-prototype-port:AC-34] Enter, Space, and ArrowDown on the closed trigger all open the popover with the current selection focused", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    // " " (a literal space) is how user-event v14 represents a Space keypress -- there is no
    // `{Space}` special-key token distinct from typing the character itself.
    for (const key of ["{Enter}", " ", "{ArrowDown}"]) {
      const { unmount } = render(<ModelSelector value="some/other-model" onChange={jest.fn()} />);
      const trigger = await screen.findByRole("button");
      await waitFor(() => expect(trigger).toBeEnabled());
      trigger.focus();

      await user.keyboard(key);

      expect(trigger).toHaveAttribute("aria-expanded", "true");
      const focused = findOptionById(screen.getAllByRole("option"), "some/other-model");
      expect(document.activeElement).toBe(focused);

      unmount();
    }
  });

  it("[chat-prototype-port:AC-35] ArrowDown/ArrowUp move focus by one without wrapping past either edge, and Home/End jump to the first/last option", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const user = userEvent.setup();

    render(<ModelSelector value="upstage/solar-pro4" onChange={jest.fn()} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger); // opens focused on the current selection: index 0

    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(3); // CATALOG has 3 entries -- the edge below is index 2, not an arbitrary number
    expect(document.activeElement).toBe(options[0]);

    await user.keyboard("{ArrowDown}");
    expect(document.activeElement).toBe(options[1]);

    await user.keyboard("{ArrowDown}");
    expect(document.activeElement).toBe(options[2]); // last option

    await user.keyboard("{ArrowDown}"); // already at the last option -- must not wrap to the first
    expect(document.activeElement).toBe(options[2]);

    await user.keyboard("{ArrowUp}");
    expect(document.activeElement).toBe(options[1]);

    await user.keyboard("{Home}");
    expect(document.activeElement).toBe(options[0]);

    await user.keyboard("{ArrowUp}"); // already at the first option -- must not wrap to the last
    expect(document.activeElement).toBe(options[0]);

    await user.keyboard("{End}");
    expect(document.activeElement).toBe(options[2]);
  });

  it("[chat-prototype-port:AC-36] Enter on the focused option selects it, closes the popover, and returns focus to the trigger", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const onChange = jest.fn();
    const user = userEvent.setup();

    render(<ModelSelector value="upstage/solar-pro4" onChange={onChange} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    onChange.mockClear(); // drop the initial "default picked" call from the mount effect
    await user.click(trigger);

    await user.keyboard("{ArrowDown}"); // move focus onto "some/other-model" -- not the default
    await user.keyboard("{Enter}");

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("some/other-model");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  it("[chat-prototype-port:AC-37] Escape closes the popover without calling onChange, and returns focus to the trigger", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const onChange = jest.fn();
    const user = userEvent.setup();

    render(<ModelSelector value="upstage/solar-pro4" onChange={onChange} />);
    const trigger = await screen.findByRole("button");
    await waitFor(() => expect(trigger).toBeEnabled());
    onChange.mockClear();
    await user.click(trigger);
    // Moves focus off the initially-focused option first -- proves Escape's "no selection"
    // guarantee holds even when a different (non-default) option was under focus at the time,
    // not just the trivial "nothing was ever focused" case.
    await user.keyboard("{ArrowDown}");

    await user.keyboard("{Escape}");

    expect(onChange).not.toHaveBeenCalled();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  it("[chat-prototype-port:AC-38] clicking outside the popover closes it without calling onChange", async () => {
    mockedListModels.mockResolvedValue(CATALOG);
    const onChange = jest.fn();
    const user = userEvent.setup();

    render(
      <div>
        <button type="button">outside</button>
        <ModelSelector value="upstage/solar-pro4" onChange={onChange} />
      </div>,
    );
    // Selected by id rather than accessible name/role alone -- an ambient sibling <button> makes
    // a bare screen.findByRole("button") ambiguous here.
    const trigger = document.getElementById("model-selector") as HTMLButtonElement;
    await waitFor(() => expect(trigger).toBeEnabled());
    onChange.mockClear();
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");

    await user.click(screen.getByRole("button", { name: "outside" }));

    expect(onChange).not.toHaveBeenCalled();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
});
