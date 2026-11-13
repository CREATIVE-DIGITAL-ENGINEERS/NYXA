/**
 * Where the AI Accountant floats.
 *
 * The button and the chat panel are one unit: both are pinned to the same two viewport edges with
 * the same two gaps. Opened, the panel grows away from those edges; closed, the button sits in the
 * panel's corner on them. That is what makes the panel follow the button and the button come back
 * where the panel was, with no second position to keep in step.
 *
 * The edges are the ones nearest the dragged element when it is dropped, so a unit parked top-left
 * stays top-left when the window is resized, and the default — bottom-right, 24px in — is simply
 * the anchor a user who never moved it has.
 *
 * The stored gaps are the user's choice and are never rewritten to fit; they are clamped when
 * placed, so a viewport that shrinks and grows back returns the unit to where it was.
 */

export type Anchor = {
  h: "left" | "right";
  x: number;
  v: "top" | "bottom";
  y: number;
};

export type Box = { width: number; height: number };
export type Rect = Box & { left: number; top: number };

/** Closest either element may come to a viewport edge. */
export const EDGE_MARGIN = 8;

export const DEFAULT_ANCHOR: Anchor = { h: "right", x: 24, v: "bottom", y: 24 };

export const FAB_BOX: Box = { width: 56, height: 56 };

export const POSITION_KEY = "ai-accountant.position";

/** Movement below this is a click that wobbled, not a drag. Fingers wobble more than mice. */
export function isDrag(dx: number, dy: number, pointerType?: string) {
  const threshold = pointerType === "touch" || pointerType === "pen" ? 10 : 5;
  return Math.hypot(dx, dy) >= threshold;
}

/** The layout viewport, without scrollbars — what `position: fixed` offsets are measured from. */
export function readViewport(): Box {
  const el = document.documentElement;
  return {
    width: el?.clientWidth || window.innerWidth,
    height: el?.clientHeight || window.innerHeight,
  };
}

function clampGap(gap: number, size: number, room: number) {
  const max = Math.max(EDGE_MARGIN, room - size - EDGE_MARGIN);
  return Math.min(Math.max(gap, EDGE_MARGIN), max);
}

/** The anchor as it can actually be placed for an element of this size in this viewport. */
export function clampAnchor(anchor: Anchor, box: Box, viewport: Box): Anchor {
  return {
    ...anchor,
    x: clampGap(anchor.x, box.width, viewport.width),
    y: clampGap(anchor.y, box.height, viewport.height),
  };
}

export function anchorToRect(anchor: Anchor, box: Box, viewport: Box): Rect {
  const { h, x, v, y } = clampAnchor(anchor, box, viewport);
  return {
    ...box,
    left: h === "left" ? x : viewport.width - x - box.width,
    top: v === "top" ? y : viewport.height - y - box.height,
  };
}

/** Pins a rect to the edges nearest its centre. Exact, so a drop never makes the element jump. */
export function rectToAnchor(rect: Rect, viewport: Box): Anchor {
  const right = viewport.width - rect.left - rect.width;
  const bottom = viewport.height - rect.top - rect.height;
  return clampAnchor(
    {
      h: rect.left <= right ? "left" : "right",
      x: rect.left <= right ? rect.left : right,
      v: rect.top <= bottom ? "top" : "bottom",
      y: rect.top <= bottom ? rect.top : bottom,
    },
    rect,
    viewport,
  );
}

/** `origin` moved by the pointer delta, kept inside the viewport. */
export function moveBy(
  origin: Anchor,
  box: Box,
  viewport: Box,
  dx: number,
  dy: number,
): Anchor {
  const rect = anchorToRect(origin, box, viewport);
  return rectToAnchor(
    { ...rect, left: rect.left + dx, top: rect.top + dy },
    viewport,
  );
}

export function anchorStyle({ h, x, v, y }: Anchor): Record<string, number> {
  return { [h]: x, [v]: y };
}

export function readStoredAnchor(): Anchor {
  try {
    const raw = window.localStorage.getItem(POSITION_KEY);
    if (!raw) return DEFAULT_ANCHOR;
    const p = JSON.parse(raw);
    if (
      (p?.h === "left" || p?.h === "right") &&
      (p?.v === "top" || p?.v === "bottom") &&
      Number.isFinite(p?.x) &&
      Number.isFinite(p?.y)
    ) {
      return { h: p.h, x: p.x, v: p.v, y: p.y };
    }
  } catch {
    // Unreadable or disabled storage: the default corner is a fine place to be.
  }
  return DEFAULT_ANCHOR;
}

export function writeStoredAnchor(anchor: Anchor) {
  try {
    window.localStorage.setItem(POSITION_KEY, JSON.stringify(anchor));
  } catch {
    // The position still applies until the page goes away.
  }
}
