import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ModelSelector } from "./ModelSelector";
import { listModels } from "@/lib/api/models";

/**
 * Only the network boundary (`lib/api/models`) is mocked; the `<select>` rendering and
 * default-model selection logic under test are real. `CATALOG` intentionally has more
 * than one non-default model so tests distinguishing "the default" from "a picked
 * non-default" (AC-39's discriminating case) are meaningful.
 */
jest.mock("@/lib/api/models");

const mockedListModels = listModels as jest.MockedFunction<typeof listModels>;

const CATALOG = [
  { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
  { id: "some/other-model", displayName: "Other Model", default: false },
  { id: "third/model", displayName: "Third Model", default: false },
];

describe("ModelSelector", () => {
  beforeEach(() => {
    mockedListModels.mockReset();
  });

  it("shows a disabled loading placeholder before the catalog resolves", () => {
    mockedListModels.mockReturnValue(new Promise(() => {})); // never resolves in this test

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    expect(screen.getByRole("combobox")).toBeDisabled();
  });

  it("[chat:AC-39] renders exactly the models GET /api/models returned, matching id and count", async () => {
    mockedListModels.mockResolvedValue(CATALOG);

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    const select = await screen.findByRole("combobox");
    await waitFor(() => expect(select).toBeEnabled());

    const options = within(select).getAllByRole("option");
    expect(options).toHaveLength(CATALOG.length);
    expect(options.map((o) => (o as HTMLOptionElement).value)).toEqual(CATALOG.map((m) => m.id));
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
    const select = await screen.findByRole("combobox");
    await waitFor(() => expect(select).toBeEnabled());
    onChange.mockClear(); // drop the initial "default picked" call from the mount effect

    await user.selectOptions(select, "some/other-model");

    expect(onChange).toHaveBeenCalledWith("some/other-model");
  });

  it("marks the default model's option label so it is distinguishable from the others", async () => {
    mockedListModels.mockResolvedValue(CATALOG);

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    expect(await screen.findByRole("option", { name: "Solar Pro 4 (기본)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Other Model" })).toBeInTheDocument();
  });

  it("shows an accessible error when the catalog fails to load", async () => {
    mockedListModels.mockRejectedValue(new Error("network down"));

    render(<ModelSelector value={null} onChange={jest.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("network down");
  });
});
