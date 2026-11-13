import { act, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  DEFAULT_ANCHOR,
  EDGE_MARGIN,
  FAB_BOX,
  POSITION_KEY,
  anchorToRect,
  clampAnchor,
  moveBy,
  readStoredAnchor,
  writeStoredAnchor,
} from "./floating-position";
import { useDragToMove } from "./use-drag-to-move";

const VIEWPORT = { width: 1280, height: 800 };
const PANEL = { width: 400, height: 560 };

// jsdom has no PointerEvent; without one, fireEvent drops clientX/pointerId on the floor.
if (typeof window.PointerEvent === "undefined") {
  class PointerEventShim extends MouseEvent {
    pointerId: number;
    pointerType: string;
    isPrimary: boolean;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
      this.pointerType = init.pointerType ?? "";
      this.isPrimary = init.isPrimary ?? false;
    }
  }
  window.PointerEvent = PointerEventShim as unknown as typeof PointerEvent;
}

beforeEach(() => window.localStorage.clear());

describe("position", () => {
  it("defaults to bottom-right for a user who never moved it", () => {
    expect(readStoredAnchor()).toEqual(DEFAULT_ANCHOR);
    expect(anchorToRect(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT)).toMatchObject({
      left: 1280 - 24 - 56,
      top: 800 - 24 - 56,
    });
  });

  it("persists a move and ignores a corrupt entry", () => {
    const moved = moveBy(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT, -1000, -600);
    writeStoredAnchor(moved);
    expect(readStoredAnchor()).toEqual(moved);

    window.localStorage.setItem(POSITION_KEY, '{"h":"middle","x":1}');
    expect(readStoredAnchor()).toEqual(DEFAULT_ANCHOR);
  });

  it("re-pins to the nearest edges without the element jumping", () => {
    const before = anchorToRect(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT);
    const moved = moveBy(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT, -1000, -600);
    expect(moved).toMatchObject({ h: "left", v: "top" });
    expect(anchorToRect(moved, FAB_BOX, VIEWPORT)).toMatchObject({
      left: before.left - 1000,
      top: before.top - 600,
    });
  });

  it("opens the panel from the button's corner and closes back into it", () => {
    // Moved while closed, then reopened: the panel shares the button's pinned corner.
    const moved = moveBy(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT, -1100, -650);
    const fab = anchorToRect(moved, FAB_BOX, VIEWPORT);
    const panel = anchorToRect(moved, PANEL, VIEWPORT);
    expect([panel.left, panel.top]).toEqual([fab.left, fab.top]);

    // Moved by the header while open, then closed: the button lands in the panel's pinned corner.
    const dropped = moveBy(DEFAULT_ANCHOR, PANEL, VIEWPORT, -300, 0);
    const p = anchorToRect(dropped, PANEL, VIEWPORT);
    const f = anchorToRect(dropped, FAB_BOX, VIEWPORT);
    expect(f.left + f.width).toBe(p.left + p.width);
    expect(f.top + f.height).toBe(p.top + p.height);
  });

  it("keeps both elements inside the viewport, and restores the choice when it grows back", () => {
    const flung = moveBy(DEFAULT_ANCHOR, FAB_BOX, VIEWPORT, 5000, 5000);
    expect(anchorToRect(flung, FAB_BOX, VIEWPORT)).toMatchObject({
      left: VIEWPORT.width - EDGE_MARGIN - 56,
      top: VIEWPORT.height - EDGE_MARGIN - 56,
    });

    const chosen = { h: "right", x: 900, v: "bottom", y: 24 } as const;
    const phone = { width: 360, height: 640 };
    for (const box of [FAB_BOX, { width: 312, height: 560 }]) {
      const r = anchorToRect(chosen, box, phone);
      expect(r.left).toBeGreaterThanOrEqual(EDGE_MARGIN);
      expect(r.left + r.width).toBeLessThanOrEqual(phone.width - EDGE_MARGIN);
      expect(r.top).toBeGreaterThanOrEqual(EDGE_MARGIN);
    }
    // Clamping is applied when placing, never written back.
    expect(clampAnchor(chosen, FAB_BOX, VIEWPORT).x).toBe(900);
  });
});

function Draggable({ onMove }: { onMove: (dx: number, dy: number) => void }) {
  const [clicks, setClicks] = useState(0);
  const drag = useDragToMove({ onStart: () => {}, onMove, onEnd: () => {} });
  return (
    <button
      onPointerDown={drag.onPointerDown}
      onClick={() => !drag.consumeClick() && setClicks((n) => n + 1)}
    >
      clicks {clicks}
    </button>
  );
}

function press(el: Element, dx: number, dy: number, pointerType = "mouse") {
  const base = { pointerId: 1, isPrimary: true, button: 0, pointerType };
  fireEvent.pointerDown(el, { ...base, clientX: 100, clientY: 100 });
  act(() => {
    fireEvent.pointerMove(window, {
      ...base,
      clientX: 100 + dx,
      clientY: 100 + dy,
    });
    fireEvent.pointerUp(window, {
      ...base,
      clientX: 100 + dx,
      clientY: 100 + dy,
    });
  });
  fireEvent.click(el);
}

describe("click versus drag", () => {
  it("a wobble is a click; a drag moves and does not click; the next click still works", () => {
    const onMove = vi.fn();
    render(<Draggable onMove={onMove} />);
    const button = screen.getByRole("button");

    press(button, 3, 2);
    expect(onMove).not.toHaveBeenCalled();
    expect(button.textContent).toBe("clicks 1");

    press(button, 6, 5, "touch"); // inside the larger touch threshold
    expect(onMove).not.toHaveBeenCalled();
    expect(button.textContent).toBe("clicks 2");

    press(button, 40, -30);
    expect(onMove).toHaveBeenLastCalledWith(40, -30);
    expect(button.textContent).toBe("clicks 2");

    press(button, 0, 0);
    expect(button.textContent).toBe("clicks 3");
  });
});
