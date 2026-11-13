import { useCallback, useEffect, useRef, useState } from "react";

import { isDrag } from "./floating-position";

type Handlers = {
  /** The pointer has passed the drag threshold. */
  onStart: () => void;
  /** Pointer offset from where it went down. */
  onMove: (dx: number, dy: number) => void;
  onEnd: () => void;
};

/**
 * Press-and-drag to move, with click still meaning click.
 *
 * Nothing moves until the pointer has travelled past `isDrag`'s threshold, so a click with a
 * shaky hand stays a click. Once it has, the click the browser fires on release is swallowed:
 * `consumeClick` answers true exactly once. The flag is cleared on the next press as well, so a
 * release that never produced a click cannot eat a later, genuine one.
 *
 * Elements using this need `touch-action: none`, or a finger drag scrolls the page instead.
 */
export function useDragToMove(
  handlers: Handlers,
  /** Presses that start here are left alone — the header's own buttons, for instance. */
  ignore?: (target: Element) => boolean,
) {
  const latest = useRef(handlers);
  latest.current = handlers;
  const suppressClick = useRef(false);
  const cleanup = useRef<() => void>();
  const [dragging, setDragging] = useState(false);

  useEffect(() => () => cleanup.current?.(), []);

  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLElement>) => {
      suppressClick.current = false;
      if (e.button !== 0 || !e.isPrimary) return;
      if (ignore && e.target instanceof Element && ignore(e.target)) return;
      cleanup.current?.();

      const el = e.currentTarget;
      const pointerId = e.pointerId;
      const startX = e.clientX;
      const startY = e.clientY;
      const pointerType = e.pointerType;
      let moving = false;

      // Header text must not get selected while it is being dragged.
      if (pointerType === "mouse" && el.tagName !== "BUTTON")
        e.preventDefault();

      try {
        // Keeps the drag alive over iframes and outside the window.
        el.setPointerCapture?.(pointerId);
      } catch {
        // Not every environment supports capture; window listeners still see the pointer.
      }

      function onMove(ev: PointerEvent) {
        if (ev.pointerId !== pointerId) return;
        const dx = ev.clientX - startX;
        const dy = ev.clientY - startY;
        if (!moving) {
          if (!isDrag(dx, dy, pointerType)) return;
          moving = true;
          setDragging(true);
          latest.current.onStart();
        }
        latest.current.onMove(dx, dy);
      }
      function finish(ev: PointerEvent) {
        if (ev.pointerId !== pointerId) return;
        stop();
        if (moving) {
          // A cancelled pointer fires no click, so there is nothing to swallow.
          suppressClick.current = ev.type === "pointerup";
          setDragging(false);
          latest.current.onEnd();
        }
      }
      function stop() {
        window.removeEventListener("pointermove", onMove);
        window.removeEventListener("pointerup", finish);
        window.removeEventListener("pointercancel", finish);
        try {
          if (el.hasPointerCapture?.(pointerId))
            el.releasePointerCapture(pointerId);
        } catch {
          // Already released with the element.
        }
        cleanup.current = undefined;
      }

      window.addEventListener("pointermove", onMove);
      window.addEventListener("pointerup", finish);
      window.addEventListener("pointercancel", finish);
      cleanup.current = stop;
    },
    [ignore],
  );

  /** True when the click being handled is the tail of a drag and must be ignored. */
  const consumeClick = useCallback(() => {
    const swallowed = suppressClick.current;
    suppressClick.current = false;
    return swallowed;
  }, []);

  return { onPointerDown, consumeClick, dragging };
}
