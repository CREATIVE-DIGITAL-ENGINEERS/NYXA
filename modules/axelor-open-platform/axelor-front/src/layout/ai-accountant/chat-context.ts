/**
 * The two pieces of chat logic that are worth testing on their own: what the user is looking at,
 * and how much of the transcript is kept.
 *
 * Extracted from the panel because both are pure functions over plain data, and because both got
 * their behaviour wrong in ways a rendered-component test would not have caught any faster.
 */

export type ChatMessage = {
  role: "user" | "assistant" | "error";
  text: string;
  tools?: string[];
};

/** What the client tells the server about the page. Absent fields mean "not known", never "none". */
export type PageContextPayload = {
  model?: string;
  recordId?: number;
  viewName?: string;
  /** `form`, `grid`, `cards`, … — the view the tab is showing right now. */
  viewType?: string;
  /**
   * The rows selected in the grid right now, if any.
   *
   * This is the only thing on the client that can authorise the assistant to change a *set* of
   * records — "post these three" means these three, and the server will not accept any other source
   * for that. So it is read live from the active tab at send time and never stored: there is no
   * cache to go stale, and a selection cannot outlive the page it was made on.
   *
   * Absent when nothing is selected, which the server reads as an instruction naming no records —
   * a refusal, not an invitation to work out which ones were meant.
   */
  selectedIds?: number[];
  /**
   * How many rows the grid says are selected, sent alongside the ids built from them.
   *
   * The server compares the two and refuses the turn when they disagree. That is the point of
   * sending it: without a declared count, a list of nineteen ids is indistinguishable from a
   * selection of nineteen, and "post these twenty" would run as a job over nineteen with nothing
   * anywhere saying one went missing.
   */
  selectedCount?: number;
  /**
   * Set when the selection was larger than this payload will carry.
   *
   * Sending a shortened list is never right — the records cut from it are precisely the ones no
   * one would notice were gone. So the client admits it instead, and the server refuses the turn;
   * the user narrows the selection and asks again.
   */
  selectionTruncated?: boolean;
};

/** More than a chat message should ever carry; the server bounds it again, far lower. */
export const MAX_SELECTED_IDS = 200;

/**
 * Whether a record is genuinely open, and which one.
 *
 * ## The bug this exists for
 *
 * The panel read the open record as `tabState.routes.form.id`. `routes` is a map of the last route
 * *per view type* (`use-tabs.ts`), kept so that switching back to the form restores where you were.
 * It is never cleared on navigation. So once a user had opened any Upload Invoice in a tab,
 * `routes.form.id` kept returning it — including after they went back to the Upload Invoices list,
 * and including in a completely different module reached through the same tab.
 *
 * The consequence was not cosmetic. Asked «дай needsReview записи» from the list, the assistant was
 * told a specific completed record was open and argued about that record instead of searching.
 *
 * ## The rule
 *
 * A record is open only when the tab is showing the form view *now* — `state.type === "form"` — and
 * that route carries an id. Anything else is a list, and a list is reported as a list rather than
 * omitted, so the model can tell "no record is open" from "the client did not say".
 */
export function resolvePageContext(
  model: string | undefined,
  viewType: string | undefined,
  formRouteId: string | number | undefined,
  viewName: string | undefined,
  selectedIds?: number[],
  selectedCount?: number,
): PageContextPayload | undefined {
  if (!model) return undefined;

  const onForm = viewType === "form";
  const id =
    onForm && formRouteId != null && formRouteId !== ""
      ? Number(formRouteId)
      : undefined;

  return {
    model,
    // A form route with no id is a NEW, unsaved record: there is nothing for a tool to address.
    recordId: id != null && Number.isFinite(id) && id > 0 ? id : undefined,
    viewName,
    viewType,
    ...cleanSelection(viewType, selectedIds, selectedCount),
  };
}

/** What the payload says about the selection: the ids, how many there were, and whether all fit. */
type SelectionPayload = Pick<
  PageContextPayload,
  "selectedIds" | "selectedCount" | "selectionTruncated"
>;

/**
 * The selection, if this page can have one — reported exactly, or reported as incomplete.
 *
 * Never on a form: that view has an open record, which the server already reads from `recordId`,
 * and a selection reported there could only be one a grid left behind.
 *
 * ## Whole, or admitted to be partial
 *
 * The one thing this must never do is quietly send fewer records than the user picked. A shortened
 * list is not a smaller instruction, it is the same instruction carried out on part of its subject
 * — and the part left out is invisible, because a list of ids carries no evidence of what used to
 * be in it. So the count is always sent as the grid reported it, and over `MAX_SELECTED_IDS` the
 * payload says so with `selectionTruncated` instead of pretending the surplus was never selected.
 * The server compares the count against the ids and refuses anything that does not add up.
 *
 * All fields absent when there is nothing selected: absent means "no selection", which the server
 * reads as an instruction naming no records rather than one covering none.
 */
function cleanSelection(
  viewType: string | undefined,
  selectedIds: number[] | undefined,
  selectedCount: number | undefined,
): SelectionPayload {
  if (viewType === "form") return {};
  const count = selectedCount ?? selectedIds?.length ?? 0;
  if (count <= 0) return {};

  // Deduplicated but never filtered down to fit: a row that yields no usable id leaves the ids
  // shorter than the count, which is exactly what the server needs to see to refuse the turn.
  const seen = new Set<number>();
  for (const raw of selectedIds ?? []) {
    const id = Number(raw);
    if (Number.isFinite(id) && id > 0) seen.add(id);
  }
  const ids = Array.from(seen);
  const truncated = count > MAX_SELECTED_IDS || ids.length > MAX_SELECTED_IDS;

  return {
    selectedIds: truncated ? ids.slice(0, MAX_SELECTED_IDS) : ids,
    selectedCount: count,
    // Only ever true. Absent means "the whole selection is here", so there is no third state where
    // an older or partial payload could read as complete by default.
    selectionTruncated: truncated || undefined,
  };
}

/** True when the payload names a real, saved record. Mirrors the server's `PageContext.hasRecord`. */
export function hasOpenRecord(context?: PageContextPayload): boolean {
  return Boolean(context?.model && context?.recordId);
}
