package com.axelor.apps.aiaccountant.prompt;

import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which instruction documents this turn is sent, chosen from {@link AiRoute} and nothing else.
 *
 * <h2>Deterministic, and therefore explainable</h2>
 *
 * The route is resolved once at the top of the turn and states five things; three of them decide
 * the documents:
 *
 * <ul>
 *   <li><b>task</b> → the playbook, and through it the domain guide, exactly as before;
 *   <li><b>scope</b> → whether the set-scoped supplement is added: {@link AiScope#WORKLIST} and
 *       {@link AiScope#DELEGATED_SELECTION} are the two scopes that mean "several records", and
 *       they are the two that get it;
 *   <li><b>domain</b> → which document type's files those are.
 * </ul>
 *
 * <p>No text matching, no heuristics, no reading of the message: given the same route the same
 * documents are selected, every time. That is what makes a log line saying which documents were
 * sent a usable explanation of an answer, rather than a guess about one.
 *
 * <h2>Complete, or not used at all</h2>
 *
 * A turn is either given the whole set of documents its route calls for, or the assembly falls back
 * to the composition that existed before this class — procedure, domain guide, playbook. Half a set
 * of instructions is the one outcome worth avoiding: the model would be told it is working on a
 * batch by one document and given the single-record procedure by another, and the resulting answer
 * would look considered.
 *
 * <p>Concretely, {@link #isComplete()} is false when the route's task claims a playbook or a domain
 * guide that did not load, or when a set-scoped turn was to be given a bulk supplement that this
 * domain does not ship. In every one of those the caller uses the previous composition and says so
 * once.
 */
public final class AiInstructionSet {

  private final AiRoute route;
  private final AiPromptDocument domainGuide;
  private final AiPromptDocument playbook;
  private final AiPromptDocument bulk;
  private final boolean complete;

  private AiInstructionSet(
      AiRoute route,
      AiPromptDocument domainGuide,
      AiPromptDocument playbook,
      AiPromptDocument bulk,
      boolean complete) {
    this.route = route;
    this.domainGuide = domainGuide;
    this.playbook = playbook;
    this.bulk = bulk;
    this.complete = complete;
  }

  /**
   * The documents for this route.
   *
   * <p>Never throws and never returns null: a turn with no route, or a route whose task is {@link
   * AiTask#GENERAL}, gets an empty-but-complete set — the universal rules on their own, which is
   * the correct instruction load for a question that is not about a document.
   */
  public static AiInstructionSet forRoute(AiRoute route) {
    if (route == null || route.task() == null) {
      return new AiInstructionSet(route, null, null, null, true);
    }
    AiTask task = route.task();
    AiPromptDocument guide = AiPlaybookLibrary.domainDocument(task);
    AiPromptDocument playbook = AiPlaybookLibrary.playbookDocument(task);
    // A task that declares files must be given them. Missing here means packaged wrong, and the
    // honest answer is the old composition rather than a partial new one.
    boolean complete =
        (guide != null || !task.hasDomainGuide()) && (playbook != null || !task.hasPlaybook());

    AiPromptDocument bulk = null;
    if (complete && playbook != null && isSetScoped(route)) {
      bulk = AiPlaybookLibrary.bulkDocument(task);
      complete = bulk != null;
    }
    return new AiInstructionSet(route, guide, playbook, bulk, complete);
  }

  /**
   * Whether this route's instruction is about several records.
   *
   * <p>Read from the scope the server resolved, not from the words. «Тези» with a grid selection
   * behind it and «всички needsReview» over a list are both sets; the same words with no selection
   * and no list are not, and the scope is where that distinction has already been settled — by
   * {@code AiRouteAdapter} against the page context, and validated against the company and the
   * permissions before any of it authorises anything.
   */
  static boolean isSetScoped(AiRoute route) {
    return route.scope() == AiScope.WORKLIST || route.scope() == AiScope.DELEGATED_SELECTION;
  }

  /** True when every document this route calls for was found. */
  public boolean isComplete() {
    return complete;
  }

  /** The documents in the order they are sent: domain guide, playbook, then the bulk supplement. */
  public List<AiPromptDocument> documents() {
    List<AiPromptDocument> docs = new ArrayList<>(3);
    if (domainGuide != null) {
      docs.add(domainGuide);
    }
    if (playbook != null) {
      docs.add(playbook);
    }
    if (bulk != null) {
      docs.add(bulk);
    }
    return Collections.unmodifiableList(docs);
  }

  public AiPromptDocument domainGuide() {
    return domainGuide;
  }

  public AiPromptDocument playbook() {
    return playbook;
  }

  /** The set-scoped supplement, or null when this turn is about one record. */
  public AiPromptDocument bulk() {
    return bulk;
  }

  public AiRoute route() {
    return route;
  }

  public int bytes() {
    int total = 0;
    for (AiPromptDocument doc : documents()) {
      total += doc.bytes();
    }
    return total;
  }

  /** {@code supplier-invoice@2+supplier-invoice-post@1+supplier-invoice-bulk@1}, or {@code -}. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    for (AiPromptDocument doc : documents()) {
      if (sb.length() > 0) {
        sb.append('+');
      }
      sb.append(doc.describe());
    }
    return sb.length() == 0 ? "-" : sb.toString();
  }
}
