package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.service.AiBlockerClass;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of one tool call, as the server understands it.
 *
 * <h2>Two audiences, one object</h2>
 *
 * A tool call produces two different things. The model gets JSON — the data on success, the {@code
 * {"error": …}} envelope on refusal — and that wire format is part of a published contract the
 * agent has been running against for twenty-six versions. The <em>server</em> needs something else
 * entirely: whether this succeeded, what kind of obstacle it hit, which record it was aimed at,
 * whether trying again could possibly help.
 *
 * <p>Until now only the first existed, and everything server-side was reconstructed from it by
 * re-reading the JSON — {@code isErrorEnvelope} tests a string prefix, {@code blockerOf} re-parses
 * the envelope to recover a code the dispatcher had in its hand a moment earlier, and the workflow
 * classifies from whichever of those survived. This is that second audience, made explicit.
 *
 * <h2>The wire format is unchanged, and that is the point</h2>
 *
 * {@link #toJson} produces byte-for-byte what the dispatcher produced before: {@code error} then
 * {@code message} then the tool's own details, in that order, or the serialised data on success.
 * Nothing published moves. The structured fields are for the server, and they are not sent.
 */
public final class AiToolResult {

  /** The envelope key for a business rule naming itself. Set by tools through their details map. */
  public static final String BLOCKER = "blocker";

  private final boolean success;
  private final String code;
  private final AiBlockerClass blockerClass;
  private final String humanMessage;
  private final Object data;
  private final Map<String, String> details;
  private final String target;
  private final String lifecycle;
  private final String notice;

  /** The wire key {@link #withNotice} adds; never a key any tool result uses for its own data. */
  public static final String ARGUMENT_NOTICE = "argumentNotice";

  private AiToolResult(
      boolean success,
      String code,
      AiBlockerClass blockerClass,
      String humanMessage,
      Object data,
      Map<String, String> details,
      String target,
      String lifecycle) {
    this(success, code, blockerClass, humanMessage, data, details, target, lifecycle, null);
  }

  private AiToolResult(
      boolean success,
      String code,
      AiBlockerClass blockerClass,
      String humanMessage,
      Object data,
      Map<String, String> details,
      String target,
      String lifecycle,
      String notice) {
    this.success = success;
    this.code = code;
    this.blockerClass = blockerClass;
    this.humanMessage = humanMessage;
    this.data = data;
    this.details =
        details == null || details.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    this.target = target;
    this.lifecycle = lifecycle;
    this.notice = notice;
  }

  /** A tool ran and produced a result. {@code data} is what goes to the model, unwrapped. */
  public static AiToolResult ok(Object data, String target) {
    return new AiToolResult(true, null, null, null, data, null, target, null);
  }

  /**
   * A refusal, classified by its code.
   *
   * <p>The classification comes from {@link AiBlockerCode} — the structured value — and falls back
   * to reading the message only when no code matches, which is {@link AiBlockerClass}'s own
   * documented ladder rather than a second one invented here.
   *
   * @param code the envelope code: {@code unknown_tool}, {@code tool_error}, and so on
   * @param details the tool's own machine-readable fields, {@code blocker} among them
   */
  public static AiToolResult blocked(String code, String message, Map<String, String> details) {
    String blocker = details == null ? null : details.get(BLOCKER);
    return new AiToolResult(
        false, code, AiBlockerClass.of(blocker, code, message), message, null, details, null, null);
  }

  public static AiToolResult blocked(String code, String message) {
    return blocked(code, message, null);
  }

  /**
   * Reads back a result that has already been serialised.
   *
   * <p>For the one caller that legitimately sees only the wire form: the batch runner receives the
   * dispatcher's answer as a string through a seam that exists so ordering and abort rules can be
   * tested without a database. It used to ask that string three separate questions — is it an
   * error, what is its message, what is its code — with a prefix test and two independent parses.
   * This answers all three from one parse, and classifies from the code the tool set rather than
   * from the sentence it wrote.
   *
   * <p>A result that is not an error envelope comes back as a success carrying no data: the caller
   * has the JSON already, and re-inflating the payload to hand it back would double the memory of
   * every batch for nothing.
   */
  public static AiToolResult fromEnvelope(String json, ObjectMapper mapper) {
    if (json == null || !json.startsWith("{\"error\":")) {
      return ok(null, null);
    }
    try {
      JsonNode envelope = mapper.readTree(json);
      String code = text(envelope.path("error"));
      String blocker = text(envelope.path(BLOCKER));
      Map<String, String> details = new LinkedHashMap<>();
      if (blocker != null) {
        details.put(BLOCKER, blocker);
      }
      return blocked(code, text(envelope.path("message")), details);
    } catch (Exception e) {
      // It began with the envelope prefix, so it is a refusal whatever else is wrong with it.
      // Classifying it as a success because it failed to parse would be the one unrecoverable
      // reading.
      return blocked(AiBlockerCode.TOOL_FAILED.wire(), null, null);
    }
  }

  private static String text(JsonNode node) {
    String value = node.isMissingNode() || node.isNull() ? null : node.asText(null);
    return value == null || value.trim().isEmpty() ? null : value;
  }

  /** The same outcome, told which record it concerned. */
  public AiToolResult withTarget(String newTarget) {
    return new AiToolResult(
        success, code, blockerClass, humanMessage, data, details, newTarget, lifecycle, notice);
  }

  /**
   * The same outcome, carrying a note the dispatcher owes the model about the call itself — today
   * only that a model-supplied company argument was ignored. Null leaves the result untouched, so
   * the ordinary wire format is byte-for-byte what it was.
   */
  public AiToolResult withNotice(String newNotice) {
    if (newNotice == null || newNotice.trim().isEmpty()) {
      return this;
    }
    return new AiToolResult(
        success, code, blockerClass, humanMessage, data, details, target, lifecycle, newNotice);
  }

  /** The dispatcher's note about the call, or null. */
  public String getNotice() {
    return notice;
  }

  /**
   * The same outcome, told where the record now stands.
   *
   * <p>Deliberately not filled in by the dispatcher. Reading a lifecycle costs a query, and doing
   * it per call would add one to every tool in the turn to populate a field nothing reads yet. The
   * workflow already reads lifecycles once, at the end of the turn, from the same registry; this is
   * where a caller that has the answer in hand can put it.
   */
  public AiToolResult withLifecycle(String newLifecycle) {
    return new AiToolResult(
        success, code, blockerClass, humanMessage, data, details, target, newLifecycle, notice);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // What the server asks it
  // ───────────────────────────────────────────────────────────────────────────

  public boolean isSuccess() {
    return success;
  }

  /** The structured code, or null on success. */
  public String getCode() {
    return code;
  }

  /** The business blocker a tool named, or null when it named none. */
  public String getBlocker() {
    return details.get(BLOCKER);
  }

  /**
   * The code the ledger records: the business blocker where there is one, the envelope code
   * otherwise.
   *
   * <p>Exactly the preference {@code AiToolBatchRunner.blockerOf} applied by re-parsing the JSON,
   * decided here instead from the values that produced it.
   */
  public String getLedgerCode() {
    String blocker = getBlocker();
    return blocker == null || blocker.trim().isEmpty() ? code : blocker;
  }

  /** What kind of obstacle this was, or null on success. */
  public AiBlockerClass getBlockerClass() {
    return blockerClass;
  }

  /** The record this call was aimed at, or null when it could not be named. */
  public String getTarget() {
    return target;
  }

  /** Where the record stands now, when the caller knew; null otherwise. */
  public String getLifecycle() {
    return lifecycle;
  }

  /** The sentence a person would be shown. Never the thing anything branches on. */
  public String getHumanMessage() {
    return humanMessage;
  }

  public Object getData() {
    return data;
  }

  /**
   * Whether repeating this call could conceivably produce a different answer.
   *
   * <p>Only a {@link AiBlockerClass#TECHNICAL_FAILURE} qualifies: something broke rather than
   * refused, so the record is unchanged and the same call may simply work. Every other class is a
   * decision — the state has to change first, which is what the repeat guard already enforces
   * independently. Not a licence to retry in the same turn; a statement that retrying is not
   * definitionally pointless.
   */
  public boolean isRetryable() {
    return !success && blockerClass == AiBlockerClass.TECHNICAL_FAILURE;
  }

  /** True when the right next move is a question to the user rather than more work. */
  public boolean needsUser() {
    return blockerClass != null && blockerClass.needsUser();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // What the model gets — unchanged
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * The JSON to return as {@code function_call_output}.
   *
   * <p>On success, the data serialised exactly as before — no wrapper, no added fields, because the
   * shape of every tool result is part of what the published agent was written against. On refusal,
   * the same three-part envelope in the same order, with the same rule that a tool's details may
   * not overwrite {@code error} or {@code message}.
   *
   * @throws Exception when the data cannot be serialised; the caller answers that itself, exactly
   *     as it did before
   */
  public String toJson(ObjectMapper mapper) throws Exception {
    if (success) {
      if (notice != null) {
        // Only an object result has somewhere to put it; an array or scalar result is sent as it
        // always was rather than re-shaped. A tool's own key of the same name is never overwritten.
        JsonNode tree = mapper.valueToTree(data);
        if (tree != null && tree.isObject() && !tree.has(ARGUMENT_NOTICE)) {
          ((ObjectNode) tree).put(ARGUMENT_NOTICE, notice);
          return mapper.writeValueAsString(tree);
        }
      }
      return mapper.writeValueAsString(data);
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("error", code);
    node.put("message", humanMessage == null ? "" : humanMessage);
    details.forEach(
        (k, v) -> {
          if (v != null && !"error".equals(k) && !"message".equals(k)) {
            node.put(k, v);
          }
        });
    if (notice != null && !node.has(ARGUMENT_NOTICE)) {
      node.put(ARGUMENT_NOTICE, notice);
    }
    return node.toString();
  }
}
