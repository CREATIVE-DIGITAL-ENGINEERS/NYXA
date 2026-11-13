import { atom } from "jotai";
import { useAtomValue } from "jotai";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { MaterialIcon } from "@axelor/ui/icons/material-icon";

import { useSession } from "@/hooks/use-session";
import { useTabs } from "@/hooks/use-tabs";
import { TabState } from "@/hooks/use-tabs/use-tabs";
import { request } from "@/services/client/client";
import { i18n } from "@/services/client/i18n";

import styles from "./ai-accountant.module.scss";
import { ChatMessage, hasOpenRecord, resolvePageContext } from "./chat-context";
import {
  Anchor,
  FAB_BOX,
  anchorStyle,
  clampAnchor,
  moveBy,
  readStoredAnchor,
  readViewport,
  writeStoredAnchor,
} from "./floating-position";
import { useDragToMove } from "./use-drag-to-move";

/** One record the server committed this turn. */
type WriteRecord = { model?: string; id?: number };

type ChatReply = {
  reply?: string;
  responseId?: string;
  /** The company this turn ran under; stored beside `responseId` and echoed back next turn. */
  companyId?: number;
  /**
   * Opaque conversation state: how large the Azure chain has grown, and any workflow the user
   * authorised that was cut short. Stored and returned unread — the server is the only party that
   * interprets it. Cleared by New conversation, which is what makes that a real reset.
   */
  state?: string;
  toolsUsed?: string[];
  truncated?: boolean;
  error?: string;
  /**
   * The turn was refused because the active company is no longer the one this panel's transcript
   * belongs to. Nothing ran — not the model, not a tool — and the message was not stored.
   * `companyId` carries the company now in force.
   */
  companyMismatch?: boolean;
  /**
   * Present only when the turn committed something. The server clears it when a write is rolled
   * back, so its presence means the database really changed.
   */
  writeResult?: { refresh?: boolean; records?: WriteRecord[] };
};

type PanelSize = { width: number; height: number };

/** The panel grows away from the viewport edges it is pinned to — see `floating-position.ts`. */
const MIN_SIZE: PanelSize = { width: 320, height: 320 };
const DEFAULT_SIZE: PanelSize = { width: 400, height: 560 };
const SIZE_KEY = "ai-accountant.panel-size";

/** Keep the panel on screen: the anchor sits 24px from each edge. */
function clampSize({ width, height }: PanelSize): PanelSize {
  return {
    width: Math.min(Math.max(width, MIN_SIZE.width), window.innerWidth - 48),
    height: Math.min(
      Math.max(height, MIN_SIZE.height),
      window.innerHeight - 48,
    ),
  };
}

/**
 * Persisted CHAIN state — not the transcript.
 *
 * ## What changed, and why
 *
 * This used to hold `messages` as well, and that was the bug. The transcript the user reads lived
 * only here, under a key containing the per-tab `conversationId`, and nothing on the server was ever
 * read back into the panel. So the chat disappeared whenever that key changed: after every F5 (the
 * lease below misread a reload as a tab clone and minted a new id), in a new tab, in another
 * browser, after a version bump, whenever storage was cleared. The words were in the database the
 * whole time — under an id nobody could reach any more.
 *
 * The transcript is now the server's, keyed by user and company, fetched on mount from
 * `ws/ai-accountant/conversation/history`, and this side keeps no copy of it. What is left here is
 * only what the SERVER cannot reconstruct: the Azure chain this tab is holding.
 *
 * ## The transcript and the model's memory are separate things
 *
 * What the user reads is the server's durable transcript. `responseId` + `state` are what Azure
 * reads: the server drops that chain when it grows past its token threshold and hands the new one a
 * short continuity note instead — deliberately, because the whole retained conversation is re-read
 * and re-charged on every request.
 *
 * A chain reset therefore leaves every visible message exactly where it was, now structurally
 * rather than by convention: the reset touches this object, and the transcript is not in it. The
 * user scrolls up and sees a conversation the model can no longer quote. That is the intended
 * behaviour, not a leak. Only "Delete conversation" clears both, and it does so on the server.
 *
 * Scoped per user id, because `responseId` addresses a conversation that belongs to whoever started
 * it. Signing in as somebody else therefore starts clean rather than inheriting.
 */
type StoredChat = {
  v: number;
  responseId?: string;
  /**
   * The company the server said `responseId` belongs to, stored verbatim and echoed back as
   * `chainCompanyId`. This side never interprets it: the active company is the server's to resolve,
   * and it drops the chain when the value it gets back is not the company now in force. That is
   * what keeps one company's invoices out of the model's context while the accountant works in the
   * next, without this component having to know anything about companies.
   */
  companyId?: number;
  /** See `ChatReply.state`. Held here so it survives a refresh, like the id it accompanies. */
  state?: string;
  savedAt: number;
};

/**
 * Bumped when the stored shape changes, so an old entry is discarded instead of misread.
 *
 * <p>3 dropped `messages`: the transcript is the server's now. A v2 entry is simply ignored, which
 * costs one chain reset on the first turn after the upgrade and nothing else — the messages a v2
 * entry still carries are not lost with it, because the server has been writing them all along.
 */
const CHAT_VERSION = 3;

/**
 * How long a stored conversation stays usable. Past this the transcript is still shown but the
 * `responseId` is dropped, so the next message starts a fresh Azure conversation instead of chaining
 * onto one that may no longer exist — a stale id fails the whole turn with a 400.
 */
const CHAT_MAX_AGE_MS = 12 * 60 * 60 * 1000;

/**
 * This panel's conversation id.
 *
 * ## Why per tab, and why it exists at all
 *
 * Everything the assistant carries between turns used to be keyed by user alone: the Azure chain
 * and — far more seriously — the authorised job on the server. Two tabs are one user, so they
 * shared both. The second tab inherited a model conversation about records it was not showing, and
 * could continue a job authorised by a sentence typed in the first, which nobody in the second tab
 * had asked for.
 *
 * `sessionStorage` is exactly the scope wanted: one value per tab, surviving a reload, gone when
 * the tab is. So a reload continues the conversation, a new tab starts its own, and the server
 * refuses to let either touch the other's chain or job.
 *
 * The id is opaque and carries nothing: `crypto.randomUUID` where available, and a random string
 * otherwise. It is not a secret and it is not a credential — the user and the company are resolved
 * server-side from the session, and knowing another tab's id gains nothing the user could not do by
 * switching to that tab.
 */
const CONVERSATION_KEY = "ai-accountant.conversation";

/**
 * Why `sessionStorage` alone is not enough.
 *
 * Duplicating a tab — Ctrl-clicking a link, "Duplicate tab", a session restore — **copies**
 * `sessionStorage` into the new tab. Both tabs then believe they own the same conversation, which
 * is precisely the state this design exists to prevent: two panels, one chain, one authorised job.
 * The id has to survive a reload and not survive a clone, and no storage API distinguishes those
 * two on its own.
 *
 * So ownership is a short lease rather than a stored value. Each tab writes `{owner, at}` under the
 * conversation's own key and refreshes it on a heartbeat; a tab claiming an id it finds under a
 * *live* lease held by somebody else mints a new one instead. A clone finds a fresh lease held by
 * the tab it was cloned from, and moves.
 *
 * `owner` is a nonce held in memory only, so a cloned tab cannot inherit it — that is the whole
 * mechanism. `BroadcastChannel` is added on top for the race a lease cannot settle: two tabs
 * mounting close enough together that both read before either writes. It is an optimisation, not
 * the mechanism, and everything still works where it is unavailable.
 *
 * ## The reload bug this used to have
 *
 * The comment here used to claim that "a reload finds the lease it wrote itself". It cannot, and
 * never could: `TAB_NONCE` lives in memory, so a reload gets a *new* nonce and reads the lease its
 * own previous page wrote as somebody else's. The heartbeat is 5s and the staleness bound 15s, so
 * that lease was always live — and every single F5 was therefore classified as a tab clone, minting
 * a new conversation id. That is what emptied the chat on refresh, reset the Azure chain every time,
 * and orphaned a set of server rows per reload.
 *
 * The fix is to release the lease on `pagehide`, which is the one event that separates the two
 * cases: a page being replaced by its own reload runs it, and a tab being *cloned* does not touch
 * the original's lease at all. So a reload now finds no lease and keeps its conversation, and a
 * clone still finds a live one and still moves. `visibilitychange` is not used — a backgrounded tab
 * must keep its lease.
 */
const LEASE_PREFIX = "ai-accountant.lease.";

/** Refreshed this often; a lease older than the staleness bound belongs to a tab that is gone. */
const LEASE_HEARTBEAT_MS = 5000;

/** Three missed heartbeats. Long enough for a busy tab, short enough to reclaim after a crash. */
const LEASE_STALE_MS = 15000;

const CONVERSATION_CHANNEL = "ai-accountant.conversation";

/** This tab, for as long as it lives. In memory on purpose: a cloned tab gets a different one. */
const TAB_NONCE = randomToken();

function randomToken(): string {
  try {
    if (
      typeof crypto !== "undefined" &&
      typeof crypto.randomUUID === "function"
    ) {
      return crypto.randomUUID();
    }
  } catch {
    // Some embedded browsers expose crypto without randomUUID; fall through.
  }
  return `${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function newConversationId(): string {
  return `c-${randomToken()}`;
}

type Lease = { owner: string; at: number };

function readLease(id: string): Lease | null {
  try {
    const raw = window.localStorage.getItem(LEASE_PREFIX + id);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (typeof parsed?.owner !== "string" || typeof parsed?.at !== "number")
      return null;
    return parsed;
  } catch {
    return null;
  }
}

/** Takes or refreshes the lease on this conversation. Silent when storage is unavailable. */
function writeLease(id: string) {
  try {
    window.localStorage.setItem(
      LEASE_PREFIX + id,
      JSON.stringify({ owner: TAB_NONCE, at: Date.now() } as Lease),
    );
  } catch {
    // Without storage there is no lease and no clone detection; the conversation still works.
  }
}

/**
 * Gives up the lease on this conversation.
 *
 * Called when the page goes away, so the next page — this tab's own reload — can claim the id back.
 * See the note on `LEASE_PREFIX`: this is what distinguishes a reload from a clone.
 */
function releaseLease(id: string) {
  try {
    const lease = readLease(id);
    // Only our own. A lease another tab has taken in the meantime is not ours to drop.
    if (!lease || lease.owner === TAB_NONCE) {
      window.localStorage.removeItem(LEASE_PREFIX + id);
    }
  } catch {
    // Nothing to release and nothing to report.
  }
}

/** True when another tab is alive and holding this id right now. */
function heldElsewhere(id: string): boolean {
  const lease = readLease(id);
  return (
    lease != null &&
    lease.owner !== TAB_NONCE &&
    Date.now() - lease.at < LEASE_STALE_MS
  );
}

/**
 * The conversation this tab owns: the stored one when it can be claimed, a new one otherwise.
 *
 * <p>Also drops leases whose conversation is long gone, so the key space does not grow with every
 * tab ever opened.
 */
function claimConversationId(): string {
  let id: string | null = null;
  try {
    const existing = window.sessionStorage.getItem(CONVERSATION_KEY);
    // Same validation the server applies, so a hand-edited value is replaced here rather than
    // silently rejected on every turn.
    if (existing && /^[A-Za-z0-9_-]{8,64}$/.test(existing)) id = existing;
  } catch {
    // Storage disabled: the panel still works, with a conversation that lasts as long as it is
    // mounted. A remount then starts a fresh chain, which is the safe direction.
  }
  if (!id || heldElsewhere(id)) {
    id = newConversationId();
    try {
      window.sessionStorage.setItem(CONVERSATION_KEY, id);
    } catch {
      // As above — in-memory only for this mount.
    }
  }
  writeLease(id);
  pruneLeases(id);
  return id;
}

/** Removes leases nobody has refreshed for far longer than they could still be in use. */
function pruneLeases(keep: string) {
  try {
    const now = Date.now();
    const doomed: string[] = [];
    for (let i = 0; i < window.localStorage.length; i++) {
      const key = window.localStorage.key(i);
      if (!key?.startsWith(LEASE_PREFIX) || key === LEASE_PREFIX + keep)
        continue;
      const lease = readLease(key.slice(LEASE_PREFIX.length));
      if (!lease || now - lease.at >= CHAT_MAX_AGE_MS) doomed.push(key);
    }
    doomed.forEach((key) => window.localStorage.removeItem(key));
  } catch {
    // Nothing to prune and nothing to report.
  }
}

/**
 * Where this conversation's CHAIN state is stored. The transcript is not here — see `StoredChat`.
 *
 * The user is part of the key because `responseId` addresses a conversation that belongs to whoever
 * started it. The conversation is part of it because a tab must not read another tab's chain — which
 * is the one thing the server will refuse to continue anyway.
 */
function chatKey(userId?: number, conversationId?: string) {
  return `ai-accountant.chat.${userId ?? "anonymous"}.${conversationId ?? "none"}`;
}

function readStoredChat(
  userId?: number,
  conversationId?: string,
): StoredChat | null {
  try {
    const raw = window.localStorage.getItem(chatKey(userId, conversationId));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    // Validate rather than trust: a half-written or hand-edited entry must start a clean chain, not
    // crash the panel that is mounted on every page of the ERP.
    if (parsed?.v !== CHAT_VERSION) return null;
    const savedAt = typeof parsed.savedAt === "number" ? parsed.savedAt : 0;
    const fresh = Date.now() - savedAt < CHAT_MAX_AGE_MS;
    return {
      v: CHAT_VERSION,
      responseId:
        fresh && typeof parsed.responseId === "string"
          ? parsed.responseId
          : undefined,
      companyId:
        typeof parsed.companyId === "number" ? parsed.companyId : undefined,
      // Tied to the chain: once the id is too old to use, its state describes a chain that no
      // longer exists, and carrying it would bound a conversation that has already restarted.
      state:
        fresh && typeof parsed.state === "string" ? parsed.state : undefined,
      savedAt,
    };
  } catch {
    return null;
  }
}

/**
 * Drops stored chain entries that are past their usable age.
 *
 * One entry per conversation means entries accumulate: every tab, every New conversation, every
 * day. Age is the right criterion — the same `CHAT_MAX_AGE_MS` after which a chain is too old to
 * continue anyway — and never the current conversation, which is being written as this runs.
 *
 * This no longer touches anything the user can see. An entry dropped here is a dead Azure chain,
 * not a transcript; the transcript is the server's and is deleted only when the user asks.
 */
function pruneStoredChats(keep: string) {
  try {
    const now = Date.now();
    const doomed: string[] = [];
    for (let i = 0; i < window.localStorage.length; i++) {
      const key = window.localStorage.key(i);
      if (!key?.startsWith("ai-accountant.chat.") || key.endsWith(`.${keep}`))
        continue;
      try {
        const parsed = JSON.parse(window.localStorage.getItem(key) ?? "{}");
        const savedAt =
          typeof parsed?.savedAt === "number" ? parsed.savedAt : 0;
        if (now - savedAt >= CHAT_MAX_AGE_MS) doomed.push(key);
      } catch {
        // Unparseable entry: it can never be restored from, so it is only taking up quota.
        doomed.push(key);
      }
    }
    doomed.forEach((key) => window.localStorage.removeItem(key));
  } catch {
    // Storage unavailable. Nothing to prune and nothing to report.
  }
}

/** Chosen size outlives the session — resizing every time you reopen the chat is a papercut. */
function readStoredSize(): PanelSize {
  try {
    const raw = window.localStorage.getItem(SIZE_KEY);
    if (!raw) return DEFAULT_SIZE;
    const parsed = JSON.parse(raw);
    if (
      typeof parsed?.width === "number" &&
      typeof parsed?.height === "number"
    ) {
      return clampSize(parsed);
    }
  } catch {
    // Unreadable or disabled storage is not worth failing the panel over.
  }
  return DEFAULT_SIZE;
}

/** A press on the header's own buttons is a click on them, never the start of a move. */
function isHeaderButton(target: Element) {
  return target.closest("button") != null;
}

/** The resize grip sits in the corner away from the pinned edges, the one that moves. */
function gripCorner({ h, v }: Anchor) {
  return `${v === "bottom" ? "t" : "b"}${h === "right" ? "l" : "r"}`;
}

/**
 * Global AI Accountant assistant: a floating button that expands into a chat panel.
 *
 * Mounted once from `Layout`, so it is present on every page without the user navigating anywhere.
 *
 * Conversation state lives here on purpose — the backend is stateless and chains turns through
 * Azure using `responseId`, so this component owns it and sends it back with each message. It is
 * mirrored to `localStorage` per user (see `StoredChat`) so a refresh, a menu click or opening
 * another record continues the same conversation instead of silently starting a new one.
 */
export function AiAccountant() {
  const [available, setAvailable] = useState(false);
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [responseId, setResponseId] = useState<string | undefined>();
  /** The company the current `responseId` belongs to; echoed back so the server can detect a
   * company switch and start a new model conversation. Opaque to this component. */
  const [chainCompanyId, setChainCompanyId] = useState<number | undefined>();
  /** Opaque server state carried between turns; see `StoredChat.state`. */
  const [convState, setConvState] = useState<string | undefined>();
  /**
   * This tab's conversation. Read once on mount and only replaced by New conversation, so every
   * turn of this panel carries the same id and no other panel carries it.
   */
  const [conversationId, setConversationId] =
    useState<string>(claimConversationId);
  /** When this tab took the conversation. The tie-break when two tabs claim it at once. */
  const claimedAt = useRef<number>(Date.now());
  const [size, setSize] = useState<PanelSize>(readStoredSize);
  /** Shared by the button and the panel, so moving either moves both. Per browser, not per user. */
  const [anchor, setAnchor] = useState<Anchor>(readStoredAnchor);
  const [viewport, setViewport] = useState(readViewport);
  /** Where the unit was, as placed, when the current drag began. */
  const dragOrigin = useRef<Anchor>(anchor);
  /** Restored before the first save, so an empty initial state cannot overwrite the stored chain. */
  const [restored, setRestored] = useState(false);
  /**
   * How the durable transcript load is going.
   *
   * Three states, not a boolean, because "empty" and "could not be read" are opposite facts and
   * showing the first when the second is true is how a user concludes their history is gone.
   */
  const [historyState, setHistoryState] = useState<
    "loading" | "ready" | "error"
  >("loading");
  /**
   * Which company the transcript on screen belongs to, as the server reported it.
   *
   * This client has no notion of an active company — every company decision in this feature is the
   * server's — so this is how it notices the books changed underneath it: a chat reply carries the
   * same field, and a reply from a different company means the transcript shown is the wrong one.
   */
  const [historyCompanyId, setHistoryCompanyId] = useState<
    number | undefined
  >();
  /**
   * Whether `historyCompanyId` is an answer rather than an absence.
   *
   * Sent with every message and required by the server. Without it, `historyCompanyId === undefined`
   * would be ambiguous on the wire: "the history endpoint told me there is no active company" and "I
   * never loaded any history" would look identical, and the second must never be allowed to execute
   * a turn. This says which one it is, and until a load has actually succeeded it says neither.
   *
   * Set only in the success handler below, alongside the id it qualifies, so the two cannot disagree.
   */
  const [companyContextLoaded, setCompanyContextLoaded] = useState(false);
  /**
   * The generation of the transcript currently on screen.
   *
   * Bumped whenever a load starts and whenever Delete clears the panel; a response carrying an older
   * generation is dropped. This is what stops a slow response for the previous user or the previous
   * company landing on the current one, and what stops an in-flight load repopulating a conversation
   * the user has just deleted.
   */
  const loadSeq = useRef(0);
  /** Forces a reload of the transcript: bumped by Retry, by focus revalidation, and by a conflict. */
  const [reloadToken, setReloadToken] = useState(0);
  /**
   * A one-line banner above the composer, for something the user must read that is not part of the
   * conversation. Currently only the company-change refusal, which has to survive the transcript
   * being replaced underneath it.
   */
  const [notice, setNotice] = useState<string | undefined>();
  /** True while "Delete conversation" is waiting on the server. Blocks a second click mid-flight. */
  const [deleting, setDeleting] = useState(false);

  const { data: sessionInfo } = useSession();
  const userId = sessionInfo?.user?.id;

  const listRef = useRef<HTMLDivElement | null>(null);

  // Current record, straight from the active tab — no URL parsing, no server round-trip.
  const { active } = useTabs();
  const fallbackState = useMemo(() => atom({} as TabState), []);
  const tabState = useAtomValue(active?.state ?? fallbackState);

  const model = active?.action?.model;
  const tabId = active?.id;

  /**
   * The rows selected in the view the tab is showing *now*.
   *
   * Read from `props[state.type]`, so it is the current view's own selection: a form has none, and
   * a different tab has its own. Nothing is cached here and nothing is written to storage — the
   * value is recomputed from tab state on every render and read again at send time, so a selection
   * cannot survive the page, the model or the view it was made on.
   *
   * The active company is deliberately not consulted, because this client has no notion of one:
   * every company decision is the server's, which drops any selected record that is not the active
   * company's before it can authorise anything.
   */
  const selectedIds = tabState?.type
    ? tabState?.props?.[tabState.type]?.selectedIds
    : undefined;
  // Sent with the ids so the server can check that it received all of them. The grid counts the
  // selected rows; the ids are what could be built from those rows, and only the two together say
  // whether the set is complete.
  const selectedCount = tabState?.type
    ? tabState?.props?.[tabState.type]?.selectedCount
    : undefined;

  // What the page actually shows, recomputed on every tab change. `routes.form` is a *remembered*
  // route, not the current view, so the current view type has to gate it — see `resolvePageContext`.
  const pageContext = useMemo(
    () =>
      resolvePageContext(
        model,
        tabState?.type,
        tabState?.routes?.form?.id,
        tabState?.name,
        selectedIds,
        selectedCount,
      ),
    [
      model,
      tabState?.type,
      tabState?.routes?.form?.id,
      tabState?.name,
      selectedIds,
      selectedCount,
    ],
  );
  const recordId = pageContext?.recordId;
  const hasContext = hasOpenRecord(pageContext);

  /**
   * Hold the lease on this conversation, and give it up to whoever claimed it first.
   *
   * The heartbeat is what makes a lease mean "a tab is alive and using this id" rather than "a tab
   * once used it". The `BroadcastChannel` half settles the one case the lease cannot: two tabs that
   * both read before either wrote — a duplicated tab is exactly that, since it mounts milliseconds
   * after the original refreshed. Whoever claimed the id earlier keeps it, with the nonce as a
   * deterministic tie-break so both tabs reach the same answer without another round trip, and the
   * loser mints a new conversation on the spot.
   *
   * Losing the id resets only what belongs to the id: the chain, the company stamp and the server
   * state. The transcript on screen is deliberately left alone — the user was in the middle of
   * reading it, and nothing about a tab being a clone makes what they already saw untrue.
   */
  useEffect(() => {
    writeLease(conversationId);
    const beat = window.setInterval(
      () => writeLease(conversationId),
      LEASE_HEARTBEAT_MS,
    );

    // The line that makes a reload a reload. Without it the page this tab is about to replace leaves
    // a live lease behind, the reloaded page reads it under a fresh nonce as somebody else's, and
    // mints a new conversation — which is exactly what used to empty the chat on every F5. A cloned
    // tab does not run its original's handler, so clone detection is untouched.
    const release = () => releaseLease(conversationId);
    window.addEventListener("pagehide", release);

    let channel: BroadcastChannel | undefined;
    const yieldConversation = () => {
      const minted = newConversationId();
      claimedAt.current = Date.now();
      try {
        window.sessionStorage.setItem(CONVERSATION_KEY, minted);
      } catch {
        // In-memory only for this mount; the id is still unique to this tab.
      }
      writeLease(minted);
      setConversationId(minted);
      setResponseId(undefined);
      setChainCompanyId(undefined);
      setConvState(undefined);
    };

    try {
      if (typeof BroadcastChannel === "function") {
        channel = new BroadcastChannel(CONVERSATION_CHANNEL);
        channel.onmessage = (event: MessageEvent) => {
          const claim = event.data;
          if (
            claim?.type !== "claim" ||
            claim.id !== conversationId ||
            claim.owner === TAB_NONCE
          ) {
            return;
          }
          const theirs = typeof claim.at === "number" ? claim.at : 0;
          const mine = claimedAt.current;
          // Earlier claim wins; identical timestamps are settled by the nonce, which both tabs
          // compare the same way, so exactly one of them moves.
          const iLose =
            theirs < mine ||
            (theirs === mine && String(claim.owner) < TAB_NONCE);
          if (iLose) yieldConversation();
        };
        channel.postMessage({
          type: "claim",
          id: conversationId,
          owner: TAB_NONCE,
          at: claimedAt.current,
        });
      }
    } catch {
      // No BroadcastChannel here: the lease alone still separates a clone from a reload, one
      // heartbeat later at the worst.
    }

    return () => {
      window.clearInterval(beat);
      window.removeEventListener("pagehide", release);
      try {
        channel?.close();
      } catch {
        // Already closed with the document.
      }
    };
  }, [conversationId]);

  /**
   * Reload the open record when the assistant just changed it.
   *
   * Without this the assistant reports "I set the origin date on invoice 576" and the form keeps
   * showing the old value until the user presses F5 — which reads as the write not having happened.
   *
   * Deliberately narrow:
   * - it fires only when the server said a record was committed (`writeResult`), which it clears
   *   when a write is rolled back, so a failed change never reloads anything;
   * - it fires only when one of those records is *the one this tab has open* — same model, same id.
   *   A different record, or a grid with no record open, is left alone;
   * - it reuses the platform's own `tab:refresh` event, the same one the tab context menu and
   *   `signal: "refresh-tab"` dispatch, so the form reloads through its normal path rather than a
   *   second mechanism that could drift from it;
   * - `forceReload` is *not* set, so `form.tsx` routes through `showConfirmDirty`: a clean form
   *   reloads silently, and a form with unsaved edits asks instead of discarding them;
   * - it runs once per reply, inside `send`, so there is no effect to re-run and no way to loop.
   *
   * The chat panel is a sibling of the view, so nothing here touches the conversation.
   */
  const refreshIfOpenRecordChanged = useCallback(
    (reply: ChatReply) => {
      const records = reply.writeResult?.refresh
        ? reply.writeResult.records
        : undefined;
      if (!records?.length || !tabId || !model || recordId == null) return;

      const open = Number(recordId);
      const affected = records.some(
        (r) => r.model === model && Number(r.id) === open,
      );
      if (!affected) return;

      document.dispatchEvent(
        new CustomEvent("tab:refresh", { detail: { id: tabId } }),
      );
    },
    [model, recordId, tabId],
  );

  useEffect(() => {
    let cancelled = false;
    request({
      url: "ws/ai-accountant/status",
      method: "GET",
      // silent: this is a background probe; it must never drive the global loading UI.
      options: { silent: true },
    })
      .then((resp) => (resp.ok ? resp.json() : null))
      .then((data) => {
        if (!cancelled) setAvailable(Boolean(data?.available));
      })
      .catch(() => {
        if (!cancelled) setAvailable(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    // Keep the newest message in view.
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages, busy]);

  // Restore this tab's Azure chain. Runs when the user becomes known, and again if it changes:
  // signing in as somebody else must not inherit the previous `responseId`.
  //
  // The transcript is deliberately NOT restored here any more — see the effect below.
  useEffect(() => {
    if (userId == null) return;
    pruneStoredChats(conversationId);
    const stored = readStoredChat(userId, conversationId);
    setResponseId(stored?.responseId);
    setChainCompanyId(stored?.companyId);
    setConvState(stored?.state);
    setRestored(true);
  }, [userId, conversationId]);

  /**
   * Load the durable transcript from the server. This is the conversation the user reads.
   *
   * It is keyed by user and company on the server and by nothing this side sends, so a reload, a new
   * tab, a new session, a container restart or a redeploy all land on the same messages in the same
   * order.
   *
   * Three things make it race-safe, and all three are needed:
   *
   * - `loadSeq`: a response that is no longer the current generation is DROPPED. That covers a
   *   response for the previous user or the previous company arriving late, and a response arriving
   *   after the user deleted the conversation.
   * - `historyState === "loading"` disables sending. A message cannot be composed into a transcript
   *   that has not arrived, so there is nothing for a slow response to overwrite — rather than
   *   merging two sources and hoping.
   * - a failure sets `"error"`, never an empty transcript. The panel then shows a load error with a
   *   Retry, because "your chat could not be read" and "you have no chat" must not look alike.
   */
  useEffect(() => {
    if (userId == null) return;
    const generation = ++loadSeq.current;
    setHistoryState("loading");
    request({
      url: "ws/ai-accountant/conversation/history",
      method: "GET",
      // silent: the panel shows its own state; this must never drive the global loading UI.
      options: { silent: true },
    })
      .then(async (resp) => {
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
      })
      .then((data) => {
        // Stale: the user, the company or the conversation moved on while this was in flight.
        if (generation !== loadSeq.current) return;
        const loaded: ChatMessage[] = Array.isArray(data?.messages)
          ? data.messages.filter(
              (m: unknown): m is ChatMessage =>
                typeof (m as ChatMessage)?.text === "string" &&
                ["user", "assistant", "error"].includes(
                  (m as ChatMessage)?.role,
                ),
            )
          : [];
        setMessages(loaded);
        // Set together: the id, and the fact that it is an answer. A response that carried no
        // companyId is the server saying there is no active company, which is a real answer and a
        // legitimate thing to claim — quite unlike never having asked.
        setHistoryCompanyId(
          typeof data?.companyId === "number" ? data.companyId : undefined,
        );
        setCompanyContextLoaded(true);
        setHistoryState("ready");
      })
      .catch(() => {
        if (generation !== loadSeq.current) return;
        // Deliberately leaves `messages` alone: whatever is on screen is at worst stale, and
        // blanking it would state something this side does not know.
        setHistoryState("error");
      });
  }, [userId, reloadToken]);

  /**
   * Revalidate when the panel regains focus.
   *
   * The active company is switched elsewhere in Nyxa — often in another tab — and this window may
   * have been showing the old company's chat the whole time. Coming back to it is the earliest
   * moment this side can find out, and reloading then means the mismatch is usually caught before
   * the user types rather than when they press send. The pre-send check is still what guarantees
   * correctness; this only makes hitting it rare.
   *
   * Skipped while a turn is in flight, so a refocus cannot pull the transcript out from under a
   * reply that is still coming.
   */
  useEffect(() => {
    if (!open || userId == null) return;
    const revalidate = () => {
      if (document.visibilityState !== "visible" || busy) return;
      setReloadToken((n) => n + 1);
    };
    window.addEventListener("focus", revalidate);
    document.addEventListener("visibilitychange", revalidate);
    return () => {
      window.removeEventListener("focus", revalidate);
      document.removeEventListener("visibilitychange", revalidate);
    };
  }, [open, userId, busy]);

  // Mirror the chain back, so a refresh or a remount continues the same Azure conversation.
  useEffect(() => {
    if (userId == null || !restored) return;
    try {
      if (!responseId && !convState) {
        window.localStorage.removeItem(chatKey(userId, conversationId));
        return;
      }
      const stored: StoredChat = {
        v: CHAT_VERSION,
        responseId,
        companyId: chainCompanyId,
        state: convState,
        savedAt: Date.now(),
      };
      window.localStorage.setItem(
        chatKey(userId, conversationId),
        JSON.stringify(stored),
      );
    } catch {
      // Storage full or disabled. A few hundred bytes of chain state is all this is now — the
      // transcript no longer competes for the origin's quota — so there is nothing left to trim and
      // nothing to retry. The conversation still works while the panel is mounted, and its
      // transcript is on the server either way.
    }
  }, [responseId, chainCompanyId, convState, restored, userId, conversationId]);

  const send = useCallback(async () => {
    const text = input.trim();
    // Not while the transcript is still arriving: a message composed onto a conversation that has
    // not loaded would be overwritten by it, and this is the simplest way to make that impossible
    // rather than merely unlikely.
    if (!text || busy || historyState !== "ready") return;

    setInput("");
    setNotice(undefined);
    setMessages((prev) => [...prev, { role: "user", text }]);
    setBusy(true);

    // This send's own id, so the server can store it exactly once. A retried request — by the
    // browser, a proxy, or a user who refreshed mid-flight — finds its rows already there.
    const turnKey = randomToken();

    try {
      const resp = await request({
        url: "ws/ai-accountant/chat",
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: text,
          responseId,
          chainCompanyId,
          state: convState,
          turnKey,
          // Which company's conversation the user was reading when they wrote this. A consistency
          // token, never scope: the server resolves the company it acts in from the session, and
          // only compares this with it. If the active company has changed since the transcript
          // loaded, the turn is refused before anything runs, rather than being carried out in
          // books the user was not looking at.
          expectedCompanyId: historyCompanyId,
          // Required by the server, and the reason a null `expectedCompanyId` above is readable at
          // all: it distinguishes "the history said there is no active company" from "I never loaded
          // any history". The second is refused, and defaults that way — a body without this
          // property deserialises to false server-side.
          companyContextLoaded,
          // Which panel this turn belongs to. The server keys the model chain and the authorised
          // job on it, so another tab can neither continue this conversation nor write under its
          // authorisation.
          conversationId,
          // Sent whenever a module is open, with or without a record. "This is the Upload Invoices
          // list and nothing is open" is information; silence is ambiguous, and the model filled the
          // silence with whatever record it last heard about.
          context: pageContext,
        }),
        // A GPT-5 turn with tool calls routinely runs 5-30s. Without `silent` the platform's
        // HttpWatch shows its "Loading..." indicator after 300ms and, at 5s, drops the modal
        // block overlay that swallows clicks and keystrokes across the whole app — so the
        // assistant would freeze the ERP for as long as it was thinking. The panel shows its
        // own "Thinking…" bubble instead, and the rest of Nyxa stays usable meanwhile.
        options: { silent: true },
      });

      if (!resp.ok) {
        // A refusal (403 without the AI role, 401 signed out) carries its own message: show it,
        // rather than calling every refusal a connection failure.
        const refused: ChatReply | null = await resp.json().catch(() => null);
        if (refused?.error) {
          setMessages((prev) => [
            ...prev,
            { role: "error", text: refused.error as string },
          ]);
          return;
        }
        throw new Error(`HTTP ${resp.status}`);
      }
      const data: ChatReply = await resp.json();
      if (data.companyMismatch) {
        // Refused before anything ran: the active company is no longer the one this transcript
        // belongs to. The message was not stored, so the optimistic bubble above has to go — and it
        // goes by reloading, which replaces the whole transcript with the new company's. The chain
        // goes with it: it addresses a conversation about the other company's records.
        setResponseId(undefined);
        setChainCompanyId(undefined);
        setConvState(undefined);
        // Put the text back in the composer rather than making the user retype it. They still have
        // to press send, which is the point — the instruction is re-issued, never replayed.
        setInput(text);
        setReloadToken((n) => n + 1);
        // A banner rather than a message bubble: the bubbles are about to be replaced by the new
        // company's transcript, and this has to outlive that.
        setNotice(
          data.error ??
            i18n.get(
              "The active company changed, so nothing was done. Please send your instruction again.",
            ),
        );
      } else if (data.error) {
        setMessages((prev) => [
          ...prev,
          { role: "error", text: data.error as string },
        ]);
      } else {
        if (data.responseId) setResponseId(data.responseId);
        // Stamped from the reply, never from anything this side works out: the server decides
        // which company a chain belongs to, and this only carries its answer to the next turn.
        if (typeof data.companyId === "number")
          setChainCompanyId(data.companyId);
        // The books changed under the panel — the user switched active company since the transcript
        // was loaded. The transcript on screen belongs to the other company's chat, so reload it.
        // This client cannot see the active company itself; the server's answer is how it finds out.
        if (
          typeof data.companyId === "number" &&
          data.companyId !== historyCompanyId
        ) {
          setReloadToken((n) => n + 1);
        }
        // Replaced wholesale, including with undefined: the server clearing it is how a finished
        // workflow stops being resumable, so a merge here would keep authorisations alive.
        setConvState(data.state);
        // `truncated` is deliberately not shown. It means the server ran out of tool iterations,
        // which is an internal budget the user can do nothing about; the reply the server sends in
        // that case already asks for whatever it still needs, in the user's own language.
        setMessages((prev) => [
          ...prev,
          {
            role: "assistant",
            text: data.reply ?? "",
            tools: data.toolsUsed?.length ? data.toolsUsed : undefined,
          },
        ]);
        // After the answer is on screen, so the reload never delays the reply appearing.
        refreshIfOpenRecordChanged(data);
      }
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        {
          role: "error",
          text: i18n.get("The assistant could not be reached."),
        },
      ]);
    } finally {
      setBusy(false);
    }
  }, [
    busy,
    chainCompanyId,
    companyContextLoaded,
    convState,
    historyCompanyId,
    historyState,
    input,
    pageContext,
    refreshIfOpenRecordChanged,
    responseId,
  ]);

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // Enter sends, Shift+Enter makes a new line.
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    },
    [send],
  );

  /**
   * Delete conversation: erases the server's copy first, then — only once that is confirmed —
   * resets everything on this side that could reconnect to it.
   *
   * Close (the × button) hides the panel and touches nothing; this is the only action that clears
   * anything, and it is now a real deletion rather than a local-only reset. The server drops the
   * stored conversation history, the authorised job and the chain-size row for this exact
   * conversation id — see `ws/ai-accountant/conversation/delete` — before this side clears the
   * transcript, the Azure `responseId`, the company stamp and the state blob carrying chain size
   * and any unfinished authorisation, and mints a fresh conversation id. The next message therefore
   * arrives with no `previous_response_id`, so the server starts a new chain, resends the operating
   * procedure and records `chained=false`.
   *
   * If the server deletion fails, NOTHING here is cleared: an error is shown instead, so the panel
   * never claims the conversation is gone when it is not.
   *
   * The abandoned Azure responses are simply left unreferenced. Nothing can reach them again: the
   * only handle was the id this just discarded.
   */
  const reset = useCallback(async () => {
    if (deleting) return;
    setDeleting(true);
    try {
      const resp = await request({
        url: "ws/ai-accountant/conversation/delete",
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ conversationId }),
        options: { silent: true },
      });
      const data = resp.ok ? await resp.json() : null;
      if (!resp.ok || !data?.deleted) {
        throw new Error(data?.error || `HTTP ${resp.status}`);
      }
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        {
          role: "error",
          text: i18n.get(
            "Could not delete the conversation. Nothing was cleared.",
          ),
        },
      ]);
      setDeleting(false);
      return;
    }
    setDeleting(false);

    // Before anything is cleared: invalidate any history request still in flight. Without this a
    // load that started a moment ago would arrive next and repopulate the very conversation the
    // user has just deleted — and the server would then be empty while the screen was not.
    loadSeq.current += 1;
    setHistoryState("ready");
    setNotice(undefined);

    setMessages([]);
    setResponseId(undefined);
    setChainCompanyId(undefined);
    setConvState(undefined);
    // A new conversation id too, so the reset reaches the server's side of it: the stored job is
    // keyed by conversation, and reusing the id would leave this panel able to continue the job the
    // user has just said they are done with.
    const minted = newConversationId();
    claimedAt.current = Date.now();
    setConversationId(minted);
    try {
      window.localStorage.removeItem(chatKey(userId, conversationId));
      window.localStorage.removeItem(LEASE_PREFIX + conversationId);
      window.sessionStorage.setItem(CONVERSATION_KEY, minted);
    } catch {
      // Nothing to do — the in-memory state is already cleared, which is what the user sees.
    }
    // Held immediately rather than at the next heartbeat, so a tab opened in the same second
    // cannot claim the id this panel has just started using.
    writeLease(minted);
  }, [deleting, userId, conversationId]);

  const box = open ? size : FAB_BOX;
  /** The anchor as it fits right now; the stored one is left alone so a regrown window restores it. */
  const placed = clampAnchor(anchor, box, viewport);

  /**
   * Moving the unit: the button when closed, the panel's header when open. Each drag starts from
   * where the unit is actually drawn, and the result is stored only on release.
   */
  const moveUnit = {
    onStart: () => {
      dragOrigin.current = placed;
    },
    onMove: (dx: number, dy: number) =>
      setAnchor(moveBy(dragOrigin.current, box, readViewport(), dx, dy)),
    onEnd: () =>
      setAnchor((current) => {
        writeStoredAnchor(current);
        return current;
      }),
  };
  const fabDrag = useDragToMove(moveUnit);
  const headerDrag = useDragToMove(moveUnit, isHeaderButton);

  /**
   * Resize from the grip in the corner away from the pinned edges: that is the corner that moves,
   * so dragging it outward grows the panel and dragging it inward shrinks it.
   *
   * Pointer capture keeps the drag alive over iframes and outside the window, and avoids the
   * document-level listener juggling a mousemove implementation would need.
   */
  const onResizeStart = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      e.preventDefault();
      const startX = e.clientX;
      const startY = e.clientY;
      const start = size;
      const growsLeft = placed.h === "right";
      const growsUp = placed.v === "bottom";
      const target = e.currentTarget;
      target.setPointerCapture(e.pointerId);

      const onMove = (ev: PointerEvent) => {
        const dx = ev.clientX - startX;
        const dy = ev.clientY - startY;
        setSize(
          clampSize({
            width: start.width + (growsLeft ? -dx : dx),
            height: start.height + (growsUp ? -dy : dy),
          }),
        );
      };
      const onUp = (ev: PointerEvent) => {
        target.releasePointerCapture(ev.pointerId);
        target.removeEventListener("pointermove", onMove);
        target.removeEventListener("pointerup", onUp);
        setSize((current) => {
          try {
            window.localStorage.setItem(SIZE_KEY, JSON.stringify(current));
          } catch {
            // Storage may be unavailable; the size still applies for this session.
          }
          return current;
        });
      };

      target.addEventListener("pointermove", onMove);
      target.addEventListener("pointerup", onUp);
    },
    [size, placed.h, placed.v],
  );

  // A window that shrank (or a zoom, which resizes the CSS viewport) would push the unit off
  // screen. The new viewport re-clamps the placement; the stored position is untouched.
  useEffect(() => {
    const onResize = () => {
      setViewport(readViewport());
      setSize((current) => clampSize(current));
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  if (!available) return null;

  const placement = anchorStyle(placed);

  if (!open) {
    return (
      <button
        type="button"
        className={`${styles.fab} ${fabDrag.dragging ? styles.dragging : ""}`}
        style={placement}
        title={i18n.get("AI Accountant — click to open, drag to move")}
        aria-label={i18n.get("AI Accountant")}
        onPointerDown={fabDrag.onPointerDown}
        onClick={() => {
          if (!fabDrag.consumeClick()) setOpen(true);
        }}
      >
        <MaterialIcon icon="smart_toy" />
        <span className={styles.dragCue} aria-hidden="true">
          <MaterialIcon icon="open_with" />
        </span>
      </button>
    );
  }

  return (
    <div
      className={`${styles.panel} ${headerDrag.dragging ? styles.dragging : ""}`}
      role="dialog"
      aria-label={i18n.get("AI Accountant")}
      data-grip={gripCorner(placed)}
      style={{ ...placement, width: size.width, height: size.height }}
    >
      <div
        className={styles.resizeGrip}
        onPointerDown={onResizeStart}
        role="separator"
        aria-label={i18n.get("Resize")}
        title={i18n.get("Drag to resize")}
      />
      <div
        className={styles.header}
        title={i18n.get("Drag to move")}
        onPointerDown={headerDrag.onPointerDown}
      >
        <MaterialIcon icon="drag_indicator" className={styles.dragHandle} />
        <MaterialIcon icon="smart_toy" />
        <span className={styles.title}>{i18n.get("AI Accountant")}</span>
        {/*
          Not `refresh`. That icon reads as "reload this panel" — a safe, repeatable thing — and
          this button irreversibly deletes the conversation, on the server as well as here.
          `delete_sweep` is the Material glyph for clearing a list, which is what it does.
        */}
        <button
          type="button"
          className={styles.headerButton}
          title={i18n.get("Delete this conversation and start a new one")}
          aria-label={i18n.get("Delete this conversation and start a new one")}
          disabled={deleting}
          onClick={reset}
        >
          <MaterialIcon icon="delete_sweep" />
        </button>
        <button
          type="button"
          className={styles.headerButton}
          title={i18n.get("Close")}
          onClick={() => setOpen(false)}
        >
          <MaterialIcon icon="close" />
        </button>
      </div>

      {pageContext?.model && (
        <div
          className={styles.context}
          title={
            hasContext
              ? `${model} #${recordId}`
              : `${model} (${pageContext.viewType})`
          }
        >
          {i18n.get("Context")}: {String(model).split(".").pop()}
          {hasContext
            ? ` #${recordId}`
            : ` — ${i18n.get("list, no record open")}`}
          {/* Shown because it is now load-bearing: a selection is what lets the assistant act on
              several records at once, so the user should be able to see that it has been noticed
              before they ask for something to be done to "these". */}
          {/* The count, not the number of ids: they differ exactly when the selection did not
              arrive whole, and that is a turn the server will refuse — better shown as the 90 the
              user chose than as the 200 that fit. */}
          {!!pageContext.selectedCount &&
            ` — ${pageContext.selectedCount} ${i18n.get("selected")}`}
        </div>
      )}

      <div className={styles.messages} ref={listRef}>
        {historyState === "loading" && (
          <div className={styles.empty}>
            {i18n.get("Loading the conversation…")}
          </div>
        )}
        {/* An explicit failure, never an empty chat. The two mean opposite things, and showing the
            second for the first is how a user concludes their history has been deleted. */}
        {historyState === "error" && (
          <div className={styles.empty}>
            {i18n.get("The conversation could not be loaded.")}{" "}
            <button
              type="button"
              className={styles.retry}
              onClick={() => setReloadToken((n) => n + 1)}
            >
              {i18n.get("Retry")}
            </button>
          </div>
        )}
        {historyState === "ready" && messages.length === 0 && (
          <div className={styles.empty}>
            {i18n.get(
              "Ask about the record you are viewing, or anything in Nyxa.",
            )}
          </div>
        )}
        {messages.map((m, i) => (
          <div
            key={i}
            className={`${styles.message} ${
              m.role === "user"
                ? styles.user
                : m.role === "error"
                  ? styles.failed
                  : styles.assistant
            }`}
          >
            {m.text}
            {m.tools && (
              <div className={styles.tools}>
                {i18n.get("Used")}: {m.tools.join(", ")}
              </div>
            )}
          </div>
        ))}
        {busy && (
          <div className={`${styles.message} ${styles.assistant}`}>
            {i18n.get("Thinking…")}
          </div>
        )}
      </div>

      {/* Outside the message list on purpose: it has to outlive the transcript being replaced by
          another company's, which is exactly what has just happened when it appears. */}
      {notice && (
        <div className={styles.notice} role="status">
          {notice}
          <button
            type="button"
            className={styles.retry}
            title={i18n.get("Dismiss")}
            onClick={() => setNotice(undefined)}
          >
            {i18n.get("Dismiss")}
          </button>
        </div>
      )}

      <div className={styles.composer}>
        {/* Composing is blocked until the transcript has arrived: a message typed onto a
            conversation that has not loaded yet would be overwritten when it does. */}
        <textarea
          className={styles.input}
          rows={2}
          value={input}
          disabled={historyState !== "ready"}
          placeholder={
            historyState === "loading"
              ? i18n.get("Loading the conversation…")
              : historyState === "error"
                ? i18n.get("The conversation could not be loaded.")
                : i18n.get("Message AI Accountant…")
          }
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
        />
        <button
          type="button"
          className={styles.send}
          disabled={busy || historyState !== "ready" || !input.trim()}
          title={i18n.get("Send")}
          onClick={send}
        >
          <MaterialIcon icon="send" />
        </button>
      </div>
    </div>
  );
}
