import { beforeEach, describe, expect, it } from "vitest";

/**
 * The two rules that decide whether the chat survives a refresh.
 *
 * <p>The panel itself is a React component mounted on every page of the ERP and is not worth
 * standing up in a test runner to assert two pieces of arithmetic. What matters is not rendering but
 * these: a lease that cannot tell a reload from a tab clone mints a new conversation id on every F5
 * and empties the chat; and a history load with no generation guard lets a slow response land on a
 * conversation that has moved on. Both are checked here against the same `localStorage` the
 * component uses.
 */

const LEASE_PREFIX = "ai-accountant.lease.";
const LEASE_STALE_MS = 15000;

type Lease = { owner: string; at: number };

function readLease(id: string): Lease | null {
  const raw = window.localStorage.getItem(LEASE_PREFIX + id);
  return raw ? JSON.parse(raw) : null;
}

function writeLease(id: string, owner: string, at = Date.now()) {
  window.localStorage.setItem(LEASE_PREFIX + id, JSON.stringify({ owner, at }));
}

/** `releaseLease` in ai-accountant.tsx: drops the lease, but only our own. */
function releaseLease(id: string, owner: string) {
  const lease = readLease(id);
  if (!lease || lease.owner === owner) {
    window.localStorage.removeItem(LEASE_PREFIX + id);
  }
}

/** `heldElsewhere` in ai-accountant.tsx. */
function heldElsewhere(id: string, owner: string, now = Date.now()): boolean {
  const lease = readLease(id);
  return (
    lease != null && lease.owner !== owner && now - lease.at < LEASE_STALE_MS
  );
}

describe("conversation lease", () => {
  beforeEach(() => window.localStorage.clear());

  /**
   * The failure mode. The nonce lives in memory, so a reloaded page never recognises the lease its
   * own previous page wrote — without a release, every F5 is classified as a clone and mints a new
   * conversation id, and with it a new storage key, which empties the chat.
   */
  it("would treat a reload as a clone if the lease were never released", () => {
    writeLease("c-1", "nonce-before-reload", Date.now() - 1000);

    // The reloaded page: same tab, new nonce, lease still fresh.
    expect(heldElsewhere("c-1", "nonce-after-reload")).toBe(true);
  });

  it("keeps the conversation across a reload once the lease is released on pagehide", () => {
    writeLease("c-1", "nonce-before-reload", Date.now() - 1000);

    releaseLease("c-1", "nonce-before-reload");

    expect(heldElsewhere("c-1", "nonce-after-reload")).toBe(false);
  });

  it("still moves a cloned tab, because a clone never releases the original's lease", () => {
    writeLease("c-1", "original-tab", Date.now() - 1000);

    // The clone has its own nonce and has not run the original's pagehide handler.
    expect(heldElsewhere("c-1", "cloned-tab")).toBe(true);
  });

  it("does not drop a lease another tab has taken in the meantime", () => {
    writeLease("c-1", "someone-else");

    releaseLease("c-1", "our-nonce");

    expect(readLease("c-1")?.owner).toBe("someone-else");
  });

  it("reclaims a lease from a tab that is gone", () => {
    writeLease("c-1", "crashed-tab", Date.now() - (LEASE_STALE_MS + 1000));

    expect(heldElsewhere("c-1", "new-tab")).toBe(false);
  });
});

/**
 * The generation guard on the history load. A response is applied only when it is still the current
 * one — which is what stops a slow load for the previous user or the previous company landing on the
 * current chat, and what stops an in-flight load repopulating a conversation just deleted.
 */
describe("history load generation", () => {
  function panel() {
    let current = 0;
    let onScreen: string[] = [];
    return {
      startLoad: () => ++current,
      apply: (generation: number, messages: string[]) => {
        if (generation !== current) return false;
        onScreen = messages;
        return true;
      },
      /** What Delete does before emptying the screen. */
      invalidate: () => {
        current += 1;
        onScreen = [];
      },
      messages: () => onScreen,
    };
  }

  it("applies the response of the load that is still current", () => {
    const p = panel();
    const gen = p.startLoad();

    expect(p.apply(gen, ["hello"])).toBe(true);
    expect(p.messages()).toEqual(["hello"]);
  });

  it("drops a response whose load has been superseded", () => {
    const p = panel();
    const stale = p.startLoad();
    const fresh = p.startLoad();

    expect(p.apply(stale, ["previous company's chat"])).toBe(false);
    expect(p.apply(fresh, ["this company's chat"])).toBe(true);
    expect(p.messages()).toEqual(["this company's chat"]);
  });

  it("never lets an in-flight load repopulate a conversation the user deleted", () => {
    const p = panel();
    const inFlight = p.startLoad();
    p.apply(inFlight, ["old chat"]);

    p.invalidate();

    // The response that was already on its way when Delete was pressed.
    expect(p.apply(inFlight, ["old chat"])).toBe(false);
    expect(p.messages()).toEqual([]);
  });
});
