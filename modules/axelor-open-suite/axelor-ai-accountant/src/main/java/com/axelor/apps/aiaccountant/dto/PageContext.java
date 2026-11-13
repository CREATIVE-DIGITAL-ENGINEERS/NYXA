package com.axelor.apps.aiaccountant.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the user is currently looking at, supplied by the web client.
 *
 * <p>Populated from the active Axelor tab: {@code model} comes from {@code Tab.action.model},
 * {@code viewType} from the view the tab is showing <em>now</em>, and {@code recordId} from the
 * form route — but only while that form view is the one on screen.
 *
 * <p>That last condition is the whole point of {@code viewType}. The client's route map remembers
 * the last form route per tab and never clears it, so before C6 a record the user had closed was
 * still reported as open, and the assistant argued about it instead of searching. A page with no
 * record now arrives as a model and a view type with no id, which says "a list is open and nothing
 * is selected" — different from the client not having said anything at all.
 */
public class PageContext {

  private String model;
  private Long recordId;
  private String viewName;
  private String viewType;

  /**
   * The rows the user has selected in the grid, as the client reported them.
   *
   * <h2>Why this is the only thing that may stand for "these ones"</h2>
   *
   * A delegated selection — «направи го за тези» — has to name records, and there are only two
   * places the names could come from: the user's own selection, or a list the server computed. The
   * second is not the same instruction. Substituting the worklist for a selection turns "these
   * three, which I have highlighted" into "everything currently eligible", which is a different and
   * much larger authorisation than the one given.
   *
   * <p>So this arrives from the request, is used exactly as sent, and is never filled in from
   * anywhere else. Empty means the user selected nothing, which is a reason to refuse rather than a
   * gap to close. The ids are still validated against the active company before they authorise
   * anything: the client is trusted to report what the user selected, not to decide what they may
   * see.
   */
  private List<Long> selectedIds;

  /**
   * How many rows the client says were selected, counted before it built {@link #selectedIds}.
   *
   * <p>It exists so the server can tell a complete selection from a shortened one. Without it, a
   * list of twenty ids is indistinguishable from twenty of the ninety the user actually
   * highlighted, and "post these" would quietly become "post twenty of these" — a smaller job than
   * the one authorised, reported back as if it were the whole of it. The count is the client's
   * declaration of what it was asked to send; the ids are what it sent, and the two must agree.
   */
  private Integer selectedCount;

  /**
   * Set by the client when it could not send the whole selection.
   *
   * <p>An honest admission of incompleteness, and the only correct thing to send when the selection
   * is larger than the transport bound. It is never a reason to work on the part that arrived: a
   * truncated selection is refused outright, because the records missing from it are exactly the
   * ones nobody would notice were missing.
   */
  private Boolean selectionTruncated;

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Long getRecordId() {
    return recordId;
  }

  public void setRecordId(Long recordId) {
    this.recordId = recordId;
  }

  public String getViewName() {
    return viewName;
  }

  public void setViewName(String viewName) {
    this.viewName = viewName;
  }

  public String getViewType() {
    return viewType;
  }

  public void setViewType(String viewType) {
    this.viewType = viewType;
  }

  public List<Long> getSelectedIds() {
    return selectedIds;
  }

  public void setSelectedIds(List<Long> selectedIds) {
    this.selectedIds = selectedIds;
  }

  public Integer getSelectedCount() {
    return selectedCount;
  }

  public void setSelectedCount(Integer selectedCount) {
    this.selectedCount = selectedCount;
  }

  public Boolean getSelectionTruncated() {
    return selectionTruncated;
  }

  public void setSelectionTruncated(Boolean selectionTruncated) {
    this.selectionTruncated = selectionTruncated;
  }

  /**
   * The largest selection this endpoint will carry. Beyond it the client sends {@code
   * selectionTruncated} instead of a shortened list, and the turn is refused.
   *
   * <p>This is a transport bound, not an authorisation one. What may actually be worked on is
   * decided much lower down by {@code aiaccountant.workflow.max.authorised.targets}, which also
   * refuses rather than truncates.
   */
  public static final int MAX_SELECTED_IDS = 500;

  /**
   * Why this selection may not be acted on, or {@code null} when it is exact and usable.
   *
   * <h2>Exact, or nothing</h2>
   *
   * A delegated selection is the user saying «тези», and the only correct handling of a set that
   * cannot be reproduced exactly is to refuse it. The failure this guards against is not a crash:
   * it is a job that runs happily over the part of the selection that survived, reports the
   * instruction as carried out, and leaves the rest unmentioned. Nobody has any reason to look for
   * what was dropped, because nothing said anything was.
   *
   * <p>So every way the set could be less than what the user chose is a refusal:
   *
   * <ul>
   *   <li><b>Truncated</b> — the client says so itself.
   *   <li><b>Over the transport bound</b> — the same condition, undeclared.
   *   <li><b>An invalid id</b> — null, zero or negative is not a saved record, and dropping it
   *       would silently shrink the set. Refusing says the selection did not arrive intact.
   *   <li><b>No declared count</b> — without it nothing can be checked, so the set is unverifiable
   *       rather than merely unusual. A client too old to send one gets a refusal, not a guess.
   *   <li><b>A count that disagrees with the unique ids</b> — duplicates, unsaved rows without ids,
   *       a serialiser that dropped entries. Whatever the cause, what arrived is not what was
   *       selected.
   *   <li><b>No model</b> — an id without an entity cannot become a {@code Model#id} ref at all.
   * </ul>
   *
   * <p>None of this establishes that the records exist, belong to the active company, or may be
   * written to. Those are settled afterwards, from the database, by the target registry and the
   * authorisation check; this only establishes that the set is the user's own and complete.
   */
  public String selectionDefect() {
    if (!hasDeclaredSelection()) {
      return null;
    }
    if (!hasModel()) {
      return "selection_without_model";
    }
    if (Boolean.TRUE.equals(selectionTruncated)) {
      return "selection_truncated";
    }
    if (selectedIds == null || selectedIds.isEmpty()) {
      // A count with no ids: the rows were selected but never made it into the request.
      return "selection_ids_missing";
    }
    if (selectedIds.size() > MAX_SELECTED_IDS
        || (selectedCount != null && selectedCount > MAX_SELECTED_IDS)) {
      return "selection_too_large";
    }
    Set<Long> unique = new LinkedHashSet<>();
    for (Long id : selectedIds) {
      if (id == null || id <= 0) {
        return "selection_invalid_id";
      }
      unique.add(id);
    }
    if (selectedCount == null) {
      return "selection_count_missing";
    }
    if (selectedCount != unique.size()) {
      return "selection_count_mismatch";
    }
    return null;
  }

  /**
   * The selection, exactly as chosen, or empty when it is not exactly reproducible.
   *
   * <p>The one view of the field the rest of the server reads. It never returns part of a
   * selection: either every row the client declared is here, or nothing is and {@link
   * #selectionDefect()} says why.
   */
  public List<Long> exactSelection() {
    List<Long> out = new ArrayList<>();
    if (selectionDefect() != null || selectedIds == null) {
      return out;
    }
    Set<Long> seen = new LinkedHashSet<>();
    for (Long id : selectedIds) {
      if (seen.add(id)) {
        out.add(id);
      }
    }
    return out;
  }

  /**
   * The client reported a selection, whether or not it survived validation.
   *
   * <p>Routing reads this rather than {@link #hasSelection()}: a turn made over a bad selection is
   * still a turn about that selection, and it has to reach the delegated-selection path to be
   * refused there. Reading it as "no selection" would quietly re-route it to some other scope.
   */
  public boolean hasDeclaredSelection() {
    return (selectedIds != null && !selectedIds.isEmpty())
        || (selectedCount != null && selectedCount > 0)
        || Boolean.TRUE.equals(selectionTruncated);
  }

  /** A model and a complete, usable selection — all a delegated selection may rest on. */
  public boolean hasSelection() {
    return !exactSelection().isEmpty();
  }

  public boolean hasRecord() {
    return model != null && !model.trim().isEmpty() && recordId != null;
  }

  /** A module is open, whether or not a record within it is. */
  public boolean hasModel() {
    return model != null && !model.trim().isEmpty();
  }

  /** {@code record}, {@code list} or {@code none} — for the metric, and for the context wording. */
  public String kind() {
    if (hasRecord()) {
      return "record";
    }
    return hasModel() ? "list" : "none";
  }
}
