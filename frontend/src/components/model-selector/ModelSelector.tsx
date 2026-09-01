"use client";

import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { listModels, type ModelResponse } from "@/lib/api/models";
import styles from "./ModelSelector.module.css";

type CatalogState =
  | { status: "loading" }
  | { status: "ready"; models: ModelResponse[] }
  | { status: "error"; message: string };

export interface ModelSelectorProps {
  /** Currently selected model id, or `null` before the catalog has loaded/chosen a default. */
  value: string | null;
  /** Fired once the catalog loads (with its default model id) and again on every user pick. */
  onChange: (modelId: string) => void;
}

/**
 * [Purpose] Card F3's model picker, Spec §5 "화면 (D3)" element ④: lists `GET /api/models`'s
 *   catalog (chat:AC-39 "개수·id가 일치") and reports the user's pick up to a parent, which is
 *   responsible for threading it into the next `POST .../messages` call (chat:AC-39 "선택한
 *   모델 id가 실제 전송 요청에 실린다" -- {@link ../chat-panel/ChatPanel}'s `model` prop, wired by
 *   {@link ../chat-workspace/ChatWorkspace}).
 *   Card T11 (chat-prototype-port) replaces the former native `<select id="model-selector">`
 *   with a trigger `button` (still `id="model-selector"`) plus a `role="listbox"` popover
 *   (`insight-chat 프로토타입.html` P:90-107) -- `design-system:AC-17`'s 2026-09-01 revision
 *   accepts any trigger/option pair whose `role="option"` `data-model-id` set matches the
 *   catalog, not only a `<select>` (chat-prototype-port:AC-30, AC-31). T11 wired mouse
 *   interaction only; card T12 adds everything a native `<select>` used to give for free --
 *   opening via `Enter`/`Space`/`ArrowDown` with the current selection focused (AC-34),
 *   `ArrowUp`/`ArrowDown`/`Home`/`End` movement within the open list, clamped rather than
 *   wrapping at the edges since neither AC-35 nor the prototype requires wrap (AC-35), `Enter`
 *   selecting the focused option and returning focus to the trigger (AC-36), `Escape`
 *   cancelling without calling `onChange` and also returning focus to the trigger (AC-37), and
 *   an outside click closing without selecting (AC-38).
 * [Rationale] Fully controlled (`value`/`onChange`) rather than owning "which model is
 *   selected" as internal state -- {@link ../chat-workspace/ChatWorkspace} needs that same id
 *   to pass to {@link ../chat-panel/ChatPanel}, and keeping a single copy of it (in the
 *   parent) avoids the two ever disagreeing. This component still owns fetching the catalog
 *   itself (self-contained reference data, unrelated to the conversation list) and, once
 *   loaded, calls `onChange` once with the catalog's own default id (`ModelResponse.default`)
 *   so a parent does not have to duplicate "which one is the default" logic -- the backend
 *   already decided that (chat:AC-21).
 *   Whether the popover is open is local `useState`, not lifted -- no other component needs to
 *   know that, only the selected `value` crosses this component's boundary.
 *   Keyboard navigation moves real DOM focus onto each `<li role="option">` (`tabIndex={-1}`,
 *   focused imperatively via a ref array) rather than keeping focus on the trigger/listbox and
 *   tracking an `aria-activedescendant` -- both are valid ARIA APG listbox patterns, and real
 *   DOM focus is the simpler one to implement without a new dependency (AD-8, Spec §Tech
 *   Review). `tabIndex={-1}` also keeps options out of the normal Tab sequence, which combined
 *   with never calling `preventDefault()` on `Tab` is what leaves a `Tab` press free to move
 *   focus straight to whatever follows this component in the DOM -- Spec §8 R-4 ④ forbids a
 *   focus trap; `role="listbox"` popovers are not modal.
 *   Opening always focuses the currently-selected option (or index 0 if none is selected) via
 *   a `useEffect` keyed on `open`, regardless of whether the popover was opened by mouse click
 *   or by keyboard -- AC-34 only requires this for the keyboard-open path, but there is no
 *   `role="option"` requirement this contradicts for a mouse-open, and one focus-on-open rule
 *   is simpler than branching by how the popover was opened.
 * [Caution] Reads `model.default`, never `model.isDefault` -- the wire field is literally
 *   named `"default"` (`@JsonProperty("default")` on the backend, `default` being a reserved
 *   Java word); see `lib/api/models.ts`'s `ModelResponse` [Caution].
 *   The "모델" caption is a plain `<span>`, deliberately *not* a `<label htmlFor="model-selector">`
 *   -- `button` is a labelable element, so `<label for>` would prepend "모델" to the trigger's
 *   computed accessible name, but chat-prototype-port:AC-30 requires that name to *be* the
 *   selected model's `displayName`, not a compound of both.
 *   The trigger's `onKeyDown` calls `preventDefault()` on `Enter`/`Space` before calling
 *   `setOpen(true)` -- without it, the browser's own default action for those keys on a
 *   `<button>` synthesizes a `click` event right after, which would hit the existing `onClick`
 *   toggle handler and immediately close what this handler just opened.
 * [AC] chat:AC-39, chat-prototype-port:AC-30, chat-prototype-port:AC-31,
 *   chat-prototype-port:AC-32, chat-prototype-port:AC-33, chat-prototype-port:AC-34,
 *   chat-prototype-port:AC-35, chat-prototype-port:AC-36, chat-prototype-port:AC-37,
 *   chat-prototype-port:AC-38
 */
export function ModelSelector({ value, onChange }: ModelSelectorProps) {
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading" });
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const anchorRef = useRef<HTMLDivElement>(null);
  const optionRefs = useRef<Array<HTMLLIElement | null>>([]);
  // Tracks which option index keyboard focus is currently on while the popover is open --
  // plain state would also work, but this is read/written only from imperative key-handling
  // code (never rendered), so a ref avoids a re-render on every arrow-key press.
  const focusedIndexRef = useRef(0);

  useEffect(() => {
    let cancelled = false;
    listModels()
      .then((models) => {
        if (cancelled) return;
        setCatalog({ status: "ready", models });
        const defaultModel = models.find((model) => model.default) ?? models[0];
        if (defaultModel) onChange(defaultModel.id);
      })
      .catch((err: unknown) => {
        if (!cancelled) setCatalog({ status: "error", message: describeError(err) });
      });
    return () => {
      cancelled = true;
    };
    // Runs once on mount only -- `onChange` here exists solely to report the catalog's own
    // default pick, not to re-run on every parent render (same "read once" reasoning as
    // ChatPanel's own mount effect).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Moved ahead of the error-state early return below (Rules of Hooks: every hook after this
  // point must run on every render, so they cannot follow a conditional `return`).
  const isReady = catalog.status === "ready";
  const models = isReady ? catalog.models : [];
  // Falls back to the catalog's own default (then its first entry) so the trigger's label is
  // never blank during the render gap between "catalog just resolved" and "parent re-rendered
  // with the mount effect's onChange(defaultModel.id) applied" -- `value` can still be `null`
  // for that one render even though `catalog.status` is already "ready".
  const selectedModel =
    models.find((model) => model.id === value) ?? models.find((model) => model.default) ?? models[0] ?? null;
  const triggerLabel = isReady ? (selectedModel?.displayName ?? "모델 선택") : "모델 불러오는 중...";
  const selectedIndex = selectedModel ? models.findIndex((model) => model.id === selectedModel.id) : -1;

  /** Clamps `index` into `[0, models.length - 1]` and moves real DOM focus onto that option --
   *  AC-35's "목록 밖으로 나가지 않는다" (no wrap at the edges). No-op if the popover currently
   *  has no options (`models.length === 0`, e.g. never actually reachable since the trigger is
   *  `disabled` until `isReady`, but kept safe regardless). */
  function focusOptionAt(index: number) {
    if (models.length === 0) return;
    const clamped = Math.max(0, Math.min(models.length - 1, index));
    focusedIndexRef.current = clamped;
    optionRefs.current[clamped]?.focus();
  }

  // chat-prototype-port:AC-34 -- opening the popover (by any means: click, Enter, Space,
  // ArrowDown) always focuses the currently-selected option (see this component's own
  // [Rationale] for why this is not branched by "how" it opened). Falls back to index 0 if
  // nothing is selected yet (`selectedIndex === -1`, e.g. `value` briefly `null`).
  useEffect(() => {
    if (!open) return;
    focusOptionAt(selectedIndex >= 0 ? selectedIndex : 0);
    // Only re-run when `open` itself flips -- re-running on every `selectedIndex` change would
    // yank focus back onto the selected option mid-navigation, fighting the user's own arrow-key
    // presses.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // chat-prototype-port:AC-38 -- a `mousedown` outside `anchorRef` (the div wrapping both the
  // trigger and the listbox) closes the popover without calling `onChange`. `mousedown`, not
  // `click`, so this settles before the trigger's own `onClick` toggle handler runs on the same
  // interaction (irrelevant here since a click on the trigger is *inside* `anchorRef` and never
  // reaches this branch, but keeps the two handlers' relative ordering unsurprising either way).
  useEffect(() => {
    if (!open) return;
    function handleOutsideMouseDown(event: MouseEvent) {
      if (anchorRef.current && !anchorRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsideMouseDown);
    return () => document.removeEventListener("mousedown", handleOutsideMouseDown);
  }, [open]);

  const handlePick = (modelId: string) => {
    onChange(modelId);
    setOpen(false);
  };

  /** chat-prototype-port:AC-34 -- opens the popover on `Enter`/`Space`/`ArrowDown` while the
   *  trigger has focus and the popover is closed (the open-list keys below take over once it
   *  is open). `preventDefault()` on `Enter`/`Space` is load-bearing -- see this component's
   *  own [Caution]. */
  function handleTriggerKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (open) return;
    if (event.key === "Enter" || event.key === " " || event.key === "ArrowDown") {
      event.preventDefault();
      setOpen(true);
    }
  }

  /** chat-prototype-port:AC-35/36/37 -- keyboard handling for the open listbox. Attached to the
   *  `<ul>`, not each `<li>`, since a native `keydown` bubbles from whichever option currently
   *  has DOM focus up to its `<ul>` ancestor. */
  function handleListKeyDown(event: ReactKeyboardEvent<HTMLUListElement>) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        focusOptionAt(focusedIndexRef.current + 1);
        break;
      case "ArrowUp":
        event.preventDefault();
        focusOptionAt(focusedIndexRef.current - 1);
        break;
      case "Home":
        event.preventDefault();
        focusOptionAt(0);
        break;
      case "End":
        event.preventDefault();
        focusOptionAt(models.length - 1);
        break;
      case "Enter": {
        event.preventDefault();
        const focusedModel = models[focusedIndexRef.current];
        if (focusedModel) handlePick(focusedModel.id);
        triggerRef.current?.focus();
        break;
      }
      case "Escape":
        // chat-prototype-port:AC-37 -- closes without calling `onChange` (unlike "Enter" above,
        // this branch never calls `handlePick`).
        event.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
        break;
      default:
        break;
    }
  }

  if (catalog.status === "error") {
    return (
      <p role="alert" className={styles.error}>
        모델 목록을 불러오지 못했습니다: {catalog.message}
      </p>
    );
  }

  return (
    <div className={styles.field}>
      <span className={styles.label}>모델</span>
      <div className={styles.popoverAnchor} ref={anchorRef}>
        <button
          type="button"
          id="model-selector"
          ref={triggerRef}
          className={styles.trigger}
          aria-haspopup="listbox"
          aria-expanded={open}
          disabled={!isReady}
          onClick={() => setOpen((prev) => !prev)}
          onKeyDown={handleTriggerKeyDown}
        >
          {/* Prototype P:91 -- decorative ink dot ahead of the label. */}
          <span className={styles.triggerDot} aria-hidden="true" />
          <span className={styles.triggerLabel}>{triggerLabel}</span>
          <svg width="11" height="11" viewBox="0 0 11 11" aria-hidden="true" className={styles.chevron}>
            <path
              d="M2 4 L5.5 7.5 L9 4"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {open && (
          <ul role="listbox" aria-label="모델 선택" className={styles.listbox} onKeyDown={handleListKeyDown}>
            {models.map((model, index) => {
              const isSelected = model.id === value;
              return (
                <li
                  key={model.id}
                  role="option"
                  data-model-id={model.id}
                  aria-selected={isSelected}
                  tabIndex={-1}
                  ref={(el) => {
                    optionRefs.current[index] = el;
                  }}
                  className={isSelected ? styles.optionSelected : styles.option}
                  onClick={() => handlePick(model.id)}
                >
                  <span className={styles.optionLabel}>{model.displayName}</span>
                  {model.default && <span className={styles.optionNote}>기본</span>}
                  {isSelected && (
                    <svg
                      data-testid="model-option-check"
                      aria-hidden="true"
                      width="12"
                      height="10"
                      viewBox="0 0 12 10"
                      className={styles.check}
                    >
                      <path
                        d="M1 5 L4.5 8.5 L11 1"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.6"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
