package com.axelor.apps.aiaccountant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The job in progress: what was authorised, on which records, and how far each one got.
 *
 * <h2>Why the server had to start writing this down</h2>
 *
 * The conversation used to record an unfinished workflow only when a turn hit the iteration cap.
 * Every other partial outcome left nothing behind — a bulk instruction that posted two invoices,
 * was blocked on a third and never reached a fourth ended the turn with no record of any of it. So
 * «какво остана?» and «продължи» were answered from the model's own prose, which is the one source
 * that cannot be checked: it is a summary of intent, written by the thing whose intent is in
 * question.
 *
 * <p>Now it is written on every turn that has a goal, whether the turn finished, failed, was cut
 * short or was merely a question. «Продължи», «какво остана?» and «какво оправи?» read this instead
 * of guessing.
 *
 * <h2>What it is not</h2>
 *
 * Not an accounting state machine, and not a plan. It records four things the server directly
 * observed — the goal in the user's own words, the target set, which targets reached the goal, and
 * which are blocked and why — and it decides nothing about how an invoice should be repaired. Every
 * accounting judgement stays with the model and the tools.
 *
 * <p>{@code remaining} is deliberately derived rather than stored: three lists that can disagree is
 * three chances to be wrong about the same fact.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiWorkflow {

  /**
   * Targets one authorisation may cover.
   *
   * <h2>Not the search page size</h2>
   *
   * It was 25, borrowed from {@code search_ocr_records}, and that stopped being defensible the
   * moment the search learned to page: «всички» can now genuinely resolve to forty records, and a
   * cap of 25 would have silently dropped fifteen of them from the authorised set — so «продължи»
   * would have finished early and called it done, which is the failure the whole workflow state
   * exists to prevent. A page size bounds one answer; it has nothing to say about how many invoices
   * a person may authorise in one sentence.
   *
   * <p>What the limit is really protecting is the blob the browser stores and returns, so it is a
   * size limit ({@link #MAX_STATE_BYTES}) with this as a coarse backstop. Refs cost about fourteen
   * bytes each, so two hundred of them is under three kilobytes — far below the budget, which means
   * this cap is reached only by something pathological.
   */
  public static final int MAX_TARGETS = 200;

  /**
   * Rough ceiling on the serialised job, in bytes.
   *
   * <p>The variable part is the blocker reasons, not the refs: a reason is up to 160 characters and
   * arrives one per blocked record. So the budget sheds reasons first — a blocked record keeps its
   * classification, which is what decides whether to ask the user, and loses only the sentence
   * explaining it, which the model can re-read from the record.
   *
   * <p>Must stay above the worst case with reasons already shed: {@link #MAX_TARGETS} records each
   * blocked with the longest {@code AiBlockerClass} name ({@code HUMAN_DECISION_REQUIRED}, 24
   * chars) costs about 26 KB — {@link #length} doubles every character, refs included, as the safe
   * UTF-8 assumption, so it is 200 × (2×ref + 2×24 + 64) plus the job's own fields, not the ~3 KB
   * the plain-ref estimate on {@link #MAX_TARGETS} above would suggest. That is over the previous
   * 16 KB (and a first-pass 24 KB) bound on its own, so a fully-blocked job of the maximum size
   * lost targets even after every reason was cleared, which defeats the "classification survives,
   * only the sentence is shed" design above. 32 KB clears that worst case with headroom.
   */
  public static final int MAX_STATE_BYTES = 32 * 1024;

  /**
   * How many refs of a list the model is shown at once.
   *
   * <p>The full set is authoritative and travels in the state; the prompt gets a preview and a
   * total. Forty ids in a context block is a hundred and sixty wasted tokens on every turn of the
   * job, and the model does not need to see them — it needs to know how many there are and that the
   * list it cannot see is the real one.
   */
  public static final int PREVIEW_REFS = 8;

  /**
   * Identity of this job, minted when it opens and never reused.
   *
   * <h2>What it is for</h2>
   *
   * Two tabs writing the same row is the ordinary case, and the interesting question when their
   * writes collide is not who was first — it is whether they were doing <em>the same job</em>. Two
   * turns of one job can be merged: both are progress on one authorised set. Two different jobs
   * cannot, and merging them would build an authorisation nobody gave, out of two that were each
   * given for something narrower.
   *
   * <p>Nothing else identifies a job. The goal, the task and the request all repeat across jobs —
   * «осчетоводи 946» said twice is two authorisations, not one — and the target set grows as work
   * proceeds, so comparing any of them answers a different question.
   */
  private String workflowId;

  /** {@link com.axelor.apps.aiaccountant.prompt.AiTask} name, for continuity of routing. */
  private String task;

  /** {@link com.axelor.apps.aiaccountant.service.AiWorkflowIntent.Stage} name: the destination. */
  private String goal;

  /** The user's own authorising words. Never re-interpreted, only carried. */
  private String request;

  /**
   * The company this job was authorised in.
   *
   * <p>An authorisation is given inside one set of books. The blob that carries this state goes to
   * the browser and comes back, and a browser can switch company between turns, so the workflow has
   * to say which company it belongs to rather than assume it is still the active one. A workflow
   * whose company is not the active company is not resumed — see {@code AiWorkflowController}.
   */
  private Long companyId;

  /**
   * The conversation this job was authorised in.
   *
   * <p>Same argument as the company, one level finer. An authorisation is given by a person typing
   * a sentence into a particular chat panel; another panel is another conversation, even for the
   * same user in the same company, and «продължи» typed there continues nothing. Carried on the
   * workflow rather than looked up, so the check works on the blob the browser returns as well as
   * on the row the server stored.
   */
  private String conversationId;

  /**
   * {@link com.axelor.apps.aiaccountant.route.AiScope} name: how the target set was established.
   *
   * <p>What makes the difference between a set that is closed and one that is not. «Осчетоводи 946»
   * with a record open authorises that record; «осчетоводи всичко от инбокса» authorises whatever
   * the assistant finds there. The first must refuse a write aimed elsewhere, the second cannot.
   */
  private String scope;

  /**
   * {@link com.axelor.apps.aiaccountant.route.AiExplicitness} name: how firmly it was authorised.
   *
   * <p>Carried so a resumed workflow still knows it began with an explicit instruction. «Продължи»
   * inherits an authorisation; it cannot manufacture one.
   */
  private String explicitness;

  /**
   * True when the instruction that opened this job asked for something to be changed.
   *
   * <p>Carried so a job with no lifecycle destination can still be a job. «Оправи 946» authorises
   * writes and names no stage; before this, {@link #isActive()} answered false for it, so it was
   * never persisted, never resumed, and «продължи» after it inherited nothing.
   */
  private boolean writeJob;

  /**
   * The records this job was authorised over, decided when it opened and never changed afterwards.
   *
   * <h2>Why it is separate from {@link #targets}, and why it is frozen</h2>
   *
   * {@code targets} is progress: it grows as work touches records. If the authorised set were the
   * same list, then every write would enlarge the set of records the job is allowed to write to —
   * an authorisation that ratifies whatever was done under it, which is no authorisation at all.
   *
   * <p>So this is the snapshot: taken from what the request itself established (the record on
   * screen, or the set a previous turn of this same job already had), before any tool runs. Empty
   * means the set was deliberately left open — a worklist, or a selection the user delegated — not
   * that it is unknown.
   */
  private List<String> authorisedTargets = new ArrayList<>();

  /** Every record the instruction covers. */
  private List<AiWorkflowRecord> targets = new ArrayList<>();

  /** Refs that provably reached {@link #goal}. A subset of {@link #targets} by construction. */
  private List<String> completed = new ArrayList<>();

  /**
   * Why the authorised set was refused when this job opened, when it was refused for a reason more
   * precise than "nothing resolved": an {@code AiBlockerCode} wire value and the sentence to answer
   * a write with. Set only by {@code AiWorkflowController} while the set is being frozen, read only
   * by {@code AiWriteAuthorisation}.
   *
   * <p>Turn-local and never serialised: a job whose set was refused authorises nothing and is never
   * active, so there is nothing for a later turn to inherit from it.
   */
  private transient String targetRefusalCode;

  private transient String targetRefusalMessage;

  @JsonIgnore
  public String getTargetRefusalCode() {
    return targetRefusalCode;
  }

  @JsonIgnore
  public String getTargetRefusalMessage() {
    return targetRefusalMessage;
  }

  /** Records why the set was refused. Ignored once a set exists — a refusal never shrinks one. */
  @JsonIgnore
  public void refuseTargets(String code, String message) {
    if (!authorisedTargets.isEmpty()) {
      return;
    }
    this.targetRefusalCode = code;
    this.targetRefusalMessage = message;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  public boolean isWriteJob() {
    return writeJob;
  }

  public void setWriteJob(boolean writeJob) {
    this.writeJob = writeJob;
  }

  public List<String> getAuthorisedTargets() {
    return authorisedTargets;
  }

  /**
   * Sets the authorised set. Refused once it has one.
   *
   * <p>The guard is the point: this is the value everything else is checked against, and a setter
   * that could be called twice would let a later turn widen what an earlier one authorised. It
   * remains a setter only because the job round-trips through JSON, where it is written once onto a
   * fresh object.
   */
  public void setAuthorisedTargets(List<String> authorisedTargets) {
    if (!this.authorisedTargets.isEmpty()) {
      return;
    }
    this.authorisedTargets =
        authorisedTargets == null ? new ArrayList<>() : new ArrayList<>(authorisedTargets);
  }

  /**
   * True when the job was authorised over particular records rather than over an open set.
   *
   * <p>Read from whether a snapshot exists, not from the scope's name: the snapshot is what a check
   * can actually be made against, and a scope that claimed to be closed with nothing in it would
   * describe a boundary drawn round nothing.
   */
  @JsonIgnore
  public boolean hasClosedTargets() {
    return !authorisedTargets.isEmpty();
  }

  public String getTask() {
    return task;
  }

  public void setTask(String task) {
    this.task = task;
  }

  public String getGoal() {
    return goal;
  }

  public void setGoal(String goal) {
    this.goal = goal;
  }

  public String getRequest() {
    return request;
  }

  public void setRequest(String request) {
    if (request == null) {
      this.request = null;
      return;
    }
    String trimmed = request.trim();
    this.request =
        trimmed.length() > AiConversationState.MAX_REQUEST_CHARS
            ? trimmed.substring(0, AiConversationState.MAX_REQUEST_CHARS)
            : trimmed;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  public String getConversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getExplicitness() {
    return explicitness;
  }

  public void setExplicitness(String explicitness) {
    this.explicitness = explicitness;
  }

  public List<AiWorkflowRecord> getTargets() {
    return targets;
  }

  public void setTargets(List<AiWorkflowRecord> targets) {
    this.targets = targets == null ? new ArrayList<>() : cap(targets);
  }

  public List<String> getCompleted() {
    return completed;
  }

  public void setCompleted(List<String> completed) {
    this.completed = completed == null ? new ArrayList<>() : new ArrayList<>(completed);
  }

  private static List<AiWorkflowRecord> cap(List<AiWorkflowRecord> in) {
    return in.size() <= MAX_TARGETS
        ? new ArrayList<>(in)
        : new ArrayList<>(in.subList(0, MAX_TARGETS));
  }

  // ---------------------------------------------------------------------------
  // Derived views. Nothing below is stored, so nothing below can disagree with the lists above.
  // ---------------------------------------------------------------------------

  /** Adds a target if it is not already one. */
  public void addTarget(String ref) {
    if (ref == null || ref.trim().isEmpty() || targets.size() >= MAX_TARGETS) {
      return;
    }
    if (find(ref) == null) {
      targets.add(new AiWorkflowRecord(ref.trim()));
    }
  }

  /** Marks a target as having reached the goal, and clears any blocker it used to carry. */
  public void markCompleted(String ref) {
    if (ref == null) {
      return;
    }
    addTarget(ref);
    AiWorkflowRecord record = find(ref);
    if (record != null) {
      record.setBlocker(null);
      record.setBlockerCode(null);
      record.setReason(null);
    }
    if (!completed.contains(ref)) {
      completed.add(ref);
    }
  }

  /**
   * Marks a target blocked. A completed target is never re-blocked: reaching the goal is a fact
   * about the database, and a later refusal on an already-posted invoice says nothing about it.
   */
  public void markBlocked(String ref, String blocker, String reason) {
    markBlocked(ref, blocker, null, reason);
  }

  /** As above, keeping the blocker's own code beside its classification. */
  public void markBlocked(String ref, String blocker, String code, String reason) {
    if (ref == null || completed.contains(ref)) {
      return;
    }
    addTarget(ref);
    AiWorkflowRecord record = find(ref);
    if (record != null) {
      record.setBlocker(blocker);
      record.setBlockerCode(code);
      record.setReason(reason);
    }
  }

  @JsonIgnore
  public AiWorkflowRecord find(String ref) {
    for (AiWorkflowRecord r : targets) {
      if (r.getRef() != null && r.getRef().equals(ref)) {
        return r;
      }
    }
    return null;
  }

  /** Blocked targets, in target order. */
  @JsonIgnore
  public List<AiWorkflowRecord> blocked() {
    List<AiWorkflowRecord> out = new ArrayList<>();
    for (AiWorkflowRecord r : targets) {
      if (r.isBlocked() && !completed.contains(r.getRef())) {
        out.add(r);
      }
    }
    return out;
  }

  /** Targets that are neither done nor blocked — the actual work left. */
  @JsonIgnore
  public List<String> remaining() {
    Set<String> done = new LinkedHashSet<>(completed);
    List<String> out = new ArrayList<>();
    for (AiWorkflowRecord r : targets) {
      if (!done.contains(r.getRef()) && !r.isBlocked()) {
        out.add(r.getRef());
      }
    }
    return out;
  }

  /** True while there is work left that no human decision is waiting on. */
  @JsonIgnore
  public boolean hasRemaining() {
    return !remaining().isEmpty();
  }

  /** True when every target is either done or blocked, so nothing more can be attempted. */
  @JsonIgnore
  public boolean isSettled() {
    return !targets.isEmpty() && remaining().isEmpty();
  }

  /**
   * True when this describes a real job.
   *
   * <h2>Two ways to be one</h2>
   *
   * A destination — bring these invoices to POSTED — is one. An instruction to change something is
   * the other: «оправи 946» authorises work and names no stage, and requiring a goal here is what
   * used to make such a job invisible. It was never persisted, so «продължи» after it inherited
   * nothing and the continuation authorised no write.
   *
   * <p>Either way it needs at least one record. A job over nothing is a sentence, not work.
   */
  @JsonIgnore
  public boolean isActive() {
    return (goal != null || writeJob) && !targets.isEmpty();
  }

  /**
   * Sheds the variable part until the job fits the byte budget.
   *
   * <p>Reasons first, in target order, because a blocker's <em>class</em> is what decides whether
   * to ask the user and its sentence is only an explanation the model can re-read from the record.
   * Dropping targets is the last resort and effectively unreachable — two hundred refs with no
   * reasons is a few kilobytes — but it is here rather than absent, because "cannot happen" is not
   * a bound.
   */
  public void capToBudget() {
    if (estimatedBytes() <= MAX_STATE_BYTES) {
      return;
    }
    for (AiWorkflowRecord record : targets) {
      if (estimatedBytes() <= MAX_STATE_BYTES) {
        return;
      }
      record.setReason(null);
    }
    while (targets.size() > 1 && estimatedBytes() > MAX_STATE_BYTES) {
      targets.remove(targets.size() - 1);
    }
  }

  /** Cheap upper bound on the serialised size — refs, classes, reasons and JSON punctuation. */
  @JsonIgnore
  public int estimatedBytes() {
    int bytes = length(goal) + length(request) + length(task) + length(scope) + 96;
    bytes += length(workflowId);
    for (String ref : authorisedTargets) {
      bytes += length(ref) + 4;
    }
    for (AiWorkflowRecord record : targets) {
      bytes += length(record.getRef()) + length(record.getBlocker()) + length(record.getReason());
      bytes += length(record.getBlockerCode());
      bytes += 64; // field names and punctuation for one record
    }
    for (String ref : completed) {
      bytes += length(ref) + 4;
    }
    return bytes;
  }

  private static int length(String value) {
    // Refs and class names are ASCII; a reason may be Cyrillic, which is two bytes a character in
    // UTF-8. Counting every character as two is the safe direction for a budget check.
    return value == null ? 0 : value.length() * 2;
  }

  /**
   * The first few refs of a list, with a count of what is not shown.
   *
   * <p>The prompt's view of a set the state holds in full. Saying "и още 32" matters more than the
   * ids themselves: it is what stops the model treating the visible handful as the whole job, which
   * is exactly the mistake rule 11 was written for.
   */
  public static String preview(List<String> refs, boolean bulgarian) {
    if (refs == null || refs.isEmpty()) {
      return "";
    }
    if (refs.size() <= PREVIEW_REFS) {
      return String.join(", ", refs);
    }
    return String.join(", ", refs.subList(0, PREVIEW_REFS))
        + (bulgarian ? " и още " : " and ")
        + (refs.size() - PREVIEW_REFS)
        + (bulgarian ? "" : " more");
  }
}
