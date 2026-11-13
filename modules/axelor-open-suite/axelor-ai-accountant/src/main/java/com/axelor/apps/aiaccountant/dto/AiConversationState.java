package com.axelor.apps.aiaccountant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The small amount of state a conversation carries between turns.
 *
 * <h2>Why the client holds it</h2>
 *
 * The service is stateless by design — no per-user map, no session — and {@code responseId} already
 * works this way: the browser stores it and sends it back. This follows the same rule rather than
 * introducing the first piece of server-side conversation memory, which would need a store, an
 * eviction policy and a multi-instance story none of the rest of the design has.
 *
 * <p>It is opaque to the client: it stores what the server returned and returns it unread. Nothing
 * here is authoritative — every accounting fact still comes from a tool reading the database. The
 * worst a tampered blob can do is claim an authorisation the user could have granted by typing the
 * same sentence, or misreport a token count and cause an unnecessary chain reset.
 *
 * <h2>What it is for</h2>
 *
 * <ul>
 *   <li>{@code lastInputTokens} — how big the Azure chain had grown, so the next turn can decide to
 *       start a fresh one instead of re-reading tens of thousands of tokens again;
 *   <li>the unfinished-workflow fields — so «продължи» resumes work the user already authorised
 *       instead of asking for permission a second time;
 *   <li>the recent-workflow descriptor — so «същото и с тази» knows which OPERATION to repeat
 *       without the conversation having to carry the record it was performed on.
 * </ul>
 *
 * <p>Deliberately tiny and deliberately not a transcript: no tool results, no invoice figures, no
 * supplier details, no conversation prose beyond the single instruction that is still outstanding.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiConversationState {

  /**
   * Bumped if the shape changes; an older blob is then ignored rather than misread.
   *
   * <p>2 — {@code pending} became stricter (it is no longer set when the authorised outcome was
   * reached) and the recent-workflow fields were added. A version 1 blob is dropped rather than
   * reinterpreted: its {@code pending} could mean "finished but cut short", which is precisely the
   * state that caused «продължи» to wander onto an unrelated invoice.
   *
   * <p>3 — the {@link AiWorkflow} block. A version 2 blob has {@code touched} but no goal and no
   * per-record outcome, so resuming from it would have to guess which of its records were done —
   * exactly the guessing this version exists to stop. Dropped rather than half-read.
   */
  public static final int VERSION = 3;

  /** The instruction is kept only as evidence of what was authorised, so it stays short. */
  public static final int MAX_REQUEST_CHARS = 300;

  /** Enough to name the records of a "next two/three" workflow without becoming a list. */
  public static final int MAX_REFS = 6;

  private int v = VERSION;

  /** Input tokens of the last model request of the previous turn. 0 when unknown. */
  private int lastInputTokens;

  /**
   * How much the chain grew over the previous turn, in input tokens. 0 when unknown.
   *
   * <h2>Why one number was not enough</h2>
   *
   * {@link #lastInputTokens} says how big the chain is. It cannot say how fast it is getting
   * bigger, and the difference decides whether a chain that is comfortably inside its bounds today
   * will still be inside them by the end of the turn about to run. Without it every bound could
   * only be checked against a measurement that was already one turn old — which is how a
   * conversation crosses a limit it was being checked against on every turn.
   *
   * <p>A difference, not a running average: the last turn's growth is the best available predictor
   * of the next one's, and keeping a series here would grow the blob to describe something a single
   * number already answers well enough for a decision whose wrong answer costs one re-sent
   * procedure.
   *
   * <p>Client-supplied like everything else in this blob, so it is clamped on the way in and every
   * unreadable value resolves toward resetting rather than continuing.
   */
  private int chainGrowthTokens;

  /**
   * True while a workflow the user authorised is known to be unfinished.
   *
   * <p>Known, not assumed. Set only when a turn was cut short AND the records it wrote have not
   * reached the outcome the user asked for. A turn that ran out of iterations with the invoice
   * already posted leaves this false: there is nothing to continue, and saying otherwise let a
   * later «продължи» carry a spent authorisation onto whatever record happened to be next.
   */
  private boolean pending;

  /**
   * The user's own words that authorised the unfinished workflow. Bounded, never re-interpreted.
   */
  private String request;

  /**
   * The records the unfinished authorisation is scoped to, as {@code Model#id}.
   *
   * <p>A scope, not a history. «Продължи» may resume the workflow on these and on nothing else: an
   * authorisation given for one invoice never becomes an authorisation for the next one, however
   * plainly the worklist lists it.
   */
  private List<String> touched = new ArrayList<>();

  /**
   * The last operation sequence that succeeded, e.g. {@code EDIT_VALIDATE_VENTILATE}.
   *
   * <p>This is what «същото и с тази» refers to, and it is deliberately only a shape. It carries no
   * amount, no date, no supplier, no line and no edit payload — repeating an operation means
   * repeating the operation, and every value for the new record must come from that record's own
   * evidence. Kept across turns because the alternative is retaining conversation history, and it
   * survives a chain reset because that is exactly when the model can no longer see what it did.
   */
  private String shape;

  /** The record the shape was performed on, as {@code Model#id}. Context, never a target. */
  private String shapeTarget;

  /** Whether that workflow reached the outcome the user asked for. */
  private boolean shapeOk;

  /*
   * No "workflow" field. The job used to travel in this blob, which let a client hand itself an
   * authorisation the server held no record of; it now lives only in the AiWorkflowState row. A
   * blob still carrying "workflow" parses unchanged — @JsonIgnoreProperties(ignoreUnknown) drops
   * it — and is never read as anything, exactly as the deprecated field was never read.
   */

  /**
   * The conversation this blob was written for.
   *
   * <p>Stamped by the server on the way out and compared on the way back in. The blob is held by
   * the browser and returned by it, so it is a hint rather than an authority — but a hint that says
   * which conversation it came from can at least be recognised as somebody else's. A blob from
   * another tab names another conversation, and the chain it describes is not continued.
   */
  private String conversationId;

  public String getConversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  public int getV() {
    return v;
  }

  public void setV(int v) {
    this.v = v;
  }

  public int getLastInputTokens() {
    return lastInputTokens;
  }

  public void setLastInputTokens(int lastInputTokens) {
    this.lastInputTokens = Math.max(0, lastInputTokens);
  }

  /**
   * Clamped to the chain's own size, because growth larger than the chain is not a measurement.
   *
   * <p>Clamped on the way out rather than on the way in: Jackson sets fields in whatever order the
   * JSON happens to carry them, so a setter that compared against {@code lastInputTokens} would
   * give a different answer depending on which field the browser sent first.
   *
   * <p>The clamp is not a security control — these numbers decide only whether to start a fresh
   * chain, and the worst an edited value can buy is a reset nobody needed. What it prevents is the
   * opposite: an implausible figure quietly disabling the projection that exists to catch a chain
   * before it crosses a limit.
   */
  public int getChainGrowthTokens() {
    return Math.max(0, Math.min(chainGrowthTokens, Math.max(0, lastInputTokens)));
  }

  public void setChainGrowthTokens(int chainGrowthTokens) {
    this.chainGrowthTokens = Math.max(0, chainGrowthTokens);
  }

  public boolean isPending() {
    return pending;
  }

  public void setPending(boolean pending) {
    this.pending = pending;
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
        trimmed.length() > MAX_REQUEST_CHARS ? trimmed.substring(0, MAX_REQUEST_CHARS) : trimmed;
  }

  public List<String> getTouched() {
    return touched;
  }

  public void setTouched(List<String> touched) {
    this.touched = touched == null ? new ArrayList<>() : cap(touched);
  }

  private static List<String> cap(List<String> in) {
    return in.size() <= MAX_REFS ? new ArrayList<>(in) : new ArrayList<>(in.subList(0, MAX_REFS));
  }

  public String getShape() {
    return shape;
  }

  public void setShape(String shape) {
    this.shape = shape == null || shape.trim().isEmpty() ? null : shape.trim();
  }

  public String getShapeTarget() {
    return shapeTarget;
  }

  public void setShapeTarget(String shapeTarget) {
    this.shapeTarget = shapeTarget == null || shapeTarget.trim().isEmpty() ? null : shapeTarget;
  }

  public boolean isShapeOk() {
    return shapeOk;
  }

  public void setShapeOk(boolean shapeOk) {
    this.shapeOk = shapeOk;
  }

  /**
   * An unfinished instruction is recorded here.
   *
   * <p>What this is <em>not</em>, since the name suggests otherwise: it is not a job, it is not an
   * authorisation, and nothing decides what may be written from it. Since 6C it is read only as an
   * audit trail — the user's own words, kept beside the turn that could not finish them — and every
   * decision about resuming, inheriting a goal or authorising a target is made from the {@code
   * AiWorkflowState} row.
   */
  public boolean hasWorkflow() {
    return pending && request != null && !request.isEmpty();
  }

  /** True when a previous operation sequence is known, so «същото» has a referent. */
  public boolean hasShape() {
    return shape != null && !shape.isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Transport. A JSON string, because the client only stores and returns it.
  // ---------------------------------------------------------------------------

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Never throws: an unreadable or foreign blob is simply no state at all. */
  public static AiConversationState parse(String json) {
    if (json == null || json.trim().isEmpty()) {
      return null;
    }
    try {
      AiConversationState state = MAPPER.readValue(json, AiConversationState.class);
      return state == null || state.getV() != VERSION ? null : state;
    } catch (Exception e) {
      return null;
    }
  }

  /** Null rather than "{}" when there is nothing to carry, so the client stores nothing. */
  public String toJson() {
    if (lastInputTokens == 0 && !hasWorkflow() && !hasShape()) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(this);
    } catch (Exception e) {
      return null;
    }
  }
}
