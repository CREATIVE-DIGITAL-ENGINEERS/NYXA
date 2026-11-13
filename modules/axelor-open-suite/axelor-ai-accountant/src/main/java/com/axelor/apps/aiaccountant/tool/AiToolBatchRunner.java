package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.metrics.AiTurnMetrics;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the function calls one model response asked for, safely, whatever order they arrive in.
 *
 * <h2>Why this is not a plain loop</h2>
 *
 * A single response may carry several function calls — ordinary Responses API behaviour, on by
 * default, and now the behaviour the operating procedure actively asks for. Executed naively, two
 * defects followed. Neither had bitten; both were waiting.
 *
 * <ol>
 *   <li><b>Order came from the model.</b> {@link AiTool#writeOrder()} has always declared the
 *       domain order — edit (20), validate (30), refresh terms (35), ventilate (40) — and nothing
 *       read it. A response listing ventilate before refresh would have posted the invoice before
 *       repairing it, which is the exact sequence the repair exists to prevent.
 *   <li><b>A failure did not stop the rest.</b> The dispatcher answers a refusal with an error
 *       envelope rather than an exception, so the loop carried on into the next call. Ventilating
 *       after a failed prerequisite is precisely what must never happen.
 * </ol>
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li>One call: executed exactly as before. No ordering question exists, so none is imposed.
 *   <li>Several calls: all READs first in the model's own order, then WRITEs by {@code
 *       writeOrder()}, ties broken by the model's order. Reads move ahead of writes because a read
 *       batched <em>with</em> a write cannot have been intended to observe that write's result —
 *       the model would have needed the result to compose the call at all. Running them first gives
 *       a consistent "before" snapshot instead of a mixture.
 *   <li>Any error stops every WRITE that has not run. Remaining READs still run: they cannot change
 *       anything, and their answers help the model's next response diagnose the failure.
 *   <li>An exact duplicate (same tool, same arguments) within one batch runs once.
 *   <li>Skipped calls still get a {@code function_call_output}. This is not optional — leaving a
 *       call unanswered strands the response id, and the next turn's chain would be answered with
 *       HTTP 400 for a call that can never be satisfied.
 * </ul>
 *
 * <p>Arguments are never reordered or rewritten, only whole calls. A genuine data dependency —
 * where a later call's arguments come from an earlier call's result — cannot be satisfied by any
 * ordering, because the model fixed those arguments before it had the result; such a batch is
 * already broken on arrival, and abort-on-failure is what contains it.
 *
 * <p>This is the guarantee the procedure's batching rule rests on. The model is told it may emit a
 * whole authorised workflow at once precisely because the server, not the model, decides the order
 * and stops at the first failure.
 */
public class AiToolBatchRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AiToolBatchRunner.class);

  private final AiToolDispatcher dispatcher;
  private final ObjectMapper mapper = new ObjectMapper();

  public AiToolBatchRunner(AiToolDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /** Runs every call the model asked for; one {@code function_call_output} per {@code call_id}. */
  public ArrayNode executeAll(List<FoundryResponse.FunctionCall> calls, List<String> toolsUsed) {
    ArrayNode outputs = mapper.createArrayNode();
    List<FoundryResponse.FunctionCall> ordered = orderCalls(calls);
    boolean reordered = !sameOrder(calls, ordered);
    if (reordered) {
      LOG.info(
          "AI Accountant: reordered {} batched call(s) to the declared write order: {} -> {}.",
          calls.size(),
          names(calls),
          names(ordered));
    }

    // Two scopes of abort, because a failure has two possible blast radii. A refusal on a record
    // stops that record; a refusal whose target cannot be identified stops everything, since
    // independence would then be an assumption rather than an observation.
    boolean abortAll = false;
    Set<String> failedTargets = new LinkedHashSet<>();
    // Kept apart from failedTargets even though both stop the same writes, because the two say
    // opposite things to whoever reads the skip. "An earlier call failed" asserts that nothing was
    // written; after a lost answer that assertion is not available, and putting it in front of the
    // model would invite it to redo work that may already be in the books.
    Set<String> uncertainTargets = new LinkedHashSet<>();
    boolean skippedAny = false;
    Set<String> done = new LinkedHashSet<>();
    for (FoundryResponse.FunctionCall call : ordered) {
      String signature = call.getName() + ' ' + String.valueOf(call.getArguments());
      String target = targetOf(call);
      // What the WORKFLOW's own required-work tracking keys this call's ledger entry on, kept
      // deliberately apart from `target` above: `target` is authorisation's own identity — batch
      // abort scope, the repeat guard, the client refresh list — and never changes here. A tool
      // that can name an exact postcondition (see AiTool#completionTarget) is asked for one, and
      // its answer replaces only the value AiTurnToolLog records; every use of `target` itself,
      // below, is untouched.
      String workflowTarget = isWrite(call.getName()) ? completionTargetOf(call, target) : target;
      if (!done.add(signature)) {
        skippedAny = true;
        AiTurnToolLog.record(
            call.getName(),
            isWrite(call.getName()),
            AiTurnToolLog.Status.SKIPPED,
            workflowTarget,
            null,
            AiBlockerCode.DUPLICATE_CALL.wire());
        outputs.add(
            skipped(
                call,
                AiBlockerCode.DUPLICATE_CALL.wire(),
                "That exact call appears twice in one response; it was executed once."));
        continue;
      }
      boolean blockedByOwnRecord = target != null && failedTargets.contains(target);
      if ((abortAll || blockedByOwnRecord) && isWrite(call.getName())) {
        // The safety rule, narrowed. A later write ON THE SAME RECORD was composed assuming the
        // earlier one succeeded, and that assumption is now false. A write on a different record
        // assumed nothing of the kind: it is its own document, its own transaction, and stopping it
        // punishes a record that never failed.
        LOG.warn(
            "AI Accountant: skipping batched write '{}'{} after an earlier failure.",
            call.getName(),
            target == null ? "" : " on " + target);
        skippedAny = true;
        AiTurnToolLog.record(
            call.getName(),
            true,
            AiTurnToolLog.Status.SKIPPED,
            workflowTarget,
            null,
            AiBlockerCode.NOT_EXECUTED.wire());
        outputs.add(
            skipped(
                call,
                AiBlockerCode.NOT_EXECUTED.wire(),
                skipReason(
                    blockedByOwnRecord, target != null && uncertainTargets.contains(target))));
        continue;
      }
      // The arguments carry supplier names, IBANs, invoice numbers and line descriptions, so they
      // are a debugging aid rather than something to write to an INFO log on every turn.
      LOG.info(
          "AI Accountant: executing tool '{}' ({} bytes of arguments).",
          call.getName(),
          call.getArguments() == null ? 0 : call.getArguments().length());
      LOG.debug("AI Accountant: '{}' arguments: {}", call.getName(), call.getArguments());
      toolsUsed.add(call.getName());
      String result = dispatch(call.getName(), call.getArguments());
      // One reading of the answer, not three. The prefix test, the message and the code used to be
      // recovered separately from the same string; they are all facts about one outcome, and taking
      // them from one parse means they cannot disagree about whether the call failed.
      AiToolResult outcome = AiToolResult.fromEnvelope(result, mapper);
      boolean failed = !outcome.isSuccess();
      // A lost answer stops exactly what a failure stops — every dependent write on that record —
      // and is recorded differently, because "we do not know" and "it did not happen" lead the same
      // work to be skipped for opposite reasons.
      boolean uncertain =
          failed && outcome.getBlockerClass() != null && outcome.getBlockerClass().isUncertain();
      if (failed) {
        if (target == null) {
          abortAll = true;
        } else {
          failedTargets.add(target);
          if (uncertain) {
            uncertainTargets.add(target);
          }
        }
      }
      // The ledger the reply is built from. Recorded per call as it happens, because after the loop
      // there is no way to tell a call that ran from one that was never reached. Keyed on
      // `workflowTarget`, not `target` — see its own comment above — so a FAILED and a later
      // SUCCESSFUL call for the exact same requested postcondition (the same IBAN, the same
      // supplier) land under the identical reference and a completion resolver can prove one
      // against the other; a different requested postcondition (a different IBAN) never can.
      AiTurnToolLog.record(
          call.getName(),
          isWrite(call.getName()),
          uncertain
              ? AiTurnToolLog.Status.UNCERTAIN
              : failed ? AiTurnToolLog.Status.FAILED : AiTurnToolLog.Status.OK,
          workflowTarget,
          failed ? outcome.getHumanMessage() : null,
          failed ? outcome.getLedgerCode() : null);
      ObjectNode item = outputs.addObject();
      item.put("type", "function_call_output");
      item.put("call_id", call.getCallId());
      // 'output' is a JSON *string*, not an object — confirmed against the live service.
      item.put("output", result);
    }
    // 'aborted' means calls were actually dropped, not merely that one failed: a single-call
    // response that was refused is an ordinary refusal, and reporting it as an abort would make the
    // batching statistics claim a safety rule fired when nothing was skipped.
    AiTurnMetrics.toolBatch(calls.size(), reordered, skippedAny);
    return outputs;
  }

  /**
   * What to tell the model about a write that was deliberately not run.
   *
   * <p>Three cases, and the third is the one that had to be added. A skip after an ordinary failure
   * can honestly say nothing was changed. A skip after a <em>lost</em> answer cannot: the earlier
   * call may have landed, so the model must be stopped from concluding the record is untouched and
   * quietly redoing the work.
   */
  protected String skipReason(boolean sameRecord, boolean afterUncertainty) {
    if (afterUncertainty) {
      return "An earlier call on this same record ended without a confirmed result — it is NOT"
          + " known whether it was saved — so this write was not run. Do not repeat that call and"
          + " do not assume the record is unchanged. Tell the user to check this record before"
          + " anything else is done to it. Other records in this response were not affected.";
    }
    return sameRecord
        ? "An earlier tool failed on this same record, so this write was not run and nothing was"
            + " changed by it. Other records in this response were not affected. Read the failure,"
            + " then decide what to do next."
        : "An earlier tool in the same response failed in a way that could affect this one, so this"
            + " write was not run and nothing was changed by it. Read the failure, then decide what"
            + " to do next.";
  }

  /**
   * READs in the model's order, then WRITEs by {@link AiTool#writeOrder()}, ties in the model's
   * order.
   *
   * <p>A single call is returned untouched — there is nothing to order, and leaving the common case
   * literally unchanged keeps this rule out of the path it does not apply to.
   *
   * <p>An unknown tool keeps its emitted position: the dispatcher will answer it with {@code
   * unknown_tool}, and guessing where an unregistered name belongs would be inventing an order for
   * something with no declared one.
   */
  public List<FoundryResponse.FunctionCall> orderCalls(List<FoundryResponse.FunctionCall> calls) {
    if (calls == null || calls.size() < 2) {
      return calls;
    }
    List<FoundryResponse.FunctionCall> sorted = new ArrayList<>(calls);
    // A stable sort, so equal keys keep the order the model emitted them in.
    sorted.sort(Comparator.comparingInt(c -> sortKey(c.getName())));
    return sorted;
  }

  /** Reads sort before writes; writes among themselves by their declared domain order. */
  public int sortKey(String toolName) {
    AiTool tool = findTool(toolName);
    if (tool == null || !tool.kind().isWrite()) {
      return Integer.MIN_VALUE;
    }
    return tool.writeOrder();
  }

  /** Whether this call changes business data. */
  public boolean isWrite(String toolName) {
    AiTool tool = findTool(toolName);
    return tool != null && tool.kind().isWrite();
  }

  /**
   * The registry, or null when it cannot be reached.
   *
   * <p>Looked up rather than injected, and defensively: ordering is an optimisation, and it must
   * never be the reason a turn fails. Without a registry the batch simply runs in the model's own
   * order, which is what happened before this existed.
   */
  protected AiTool findTool(String toolName) {
    try {
      return Beans.get(AiToolRegistry.class).find(toolName);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: tool registry unavailable while ordering: {}", e.getMessage());
      return null;
    }
  }

  protected boolean sameOrder(
      List<FoundryResponse.FunctionCall> a, List<FoundryResponse.FunctionCall> b) {
    if (a == b) {
      return true;
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i) != b.get(i)) {
        return false;
      }
    }
    return true;
  }

  protected String names(List<FoundryResponse.FunctionCall> calls) {
    StringBuilder sb = new StringBuilder();
    for (FoundryResponse.FunctionCall c : calls) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(c.getName());
    }
    return sb.toString();
  }

  /**
   * Runs one call. A seam, so the ordering and abort rules can be tested without a Guice injector,
   * a database or a Foundry agent — the behaviour under test is entirely about which calls run and
   * in what order, and none of that needs a real tool to decide.
   */
  protected String dispatch(String name, String arguments) {
    return dispatcher.dispatch(name, arguments);
  }

  /** Cheap check for the dispatcher's {@code {"error":…}} envelope, without re-parsing it. */
  public boolean isErrorEnvelope(String result) {
    return result != null && result.startsWith("{\"error\":");
  }

  /**
   * The record a call is aimed at, asked of the tool itself.
   *
   * <p>Defensive on both sides: an unknown tool and a tool that throws while inspecting its own
   * arguments both yield null, which routes the call into the conservative whole-batch abort rather
   * than into an optimistic "this one is independent".
   */
  protected String targetOf(FoundryResponse.FunctionCall call) {
    AiTool tool = findTool(call.getName());
    if (tool == null) {
      return null;
    }
    try {
      return tool.targetKey(
          mapper.readTree(call.getArguments() == null ? "{}" : call.getArguments()));
    } catch (Exception e) {
      LOG.debug(
          "AI Accountant: could not read the target of '{}': {}", call.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * What the workflow's ledger entry for this call should be keyed on: the tool's own {@link
   * AiTool#completionTarget}, when it names one, otherwise the authorisation target unchanged.
   *
   * <p>Defensive the same way {@link #targetOf} is — an unknown tool or one that throws inspecting
   * its own arguments falls back to {@code fallback} rather than losing the entry's identity.
   *
   * @param fallback the authorisation target already computed for this call
   */
  protected String completionTargetOf(FoundryResponse.FunctionCall call, String fallback) {
    AiTool tool = findTool(call.getName());
    if (tool == null) {
      return fallback;
    }
    try {
      String declared =
          tool.completionTarget(
              mapper.readTree(call.getArguments() == null ? "{}" : call.getArguments()));
      return declared == null || declared.trim().isEmpty() ? fallback : declared.trim();
    } catch (Exception e) {
      LOG.debug(
          "AI Accountant: could not read the completion target of '{}': {}",
          call.getName(),
          e.getMessage());
      return fallback;
    }
  }

  /** The {@code message} out of a refusal envelope, for the turn ledger. Never throws. */
  protected String reasonOf(String result) {
    return AiToolResult.fromEnvelope(result, mapper).getHumanMessage();
  }

  /**
   * The refusal's code, preferring the business {@code blocker} over the envelope's {@code error}.
   *
   * <p>Both already travel to the model; only the ledger was throwing them away, which left the
   * workflow classifying a refusal by re-reading its prose even when the tool had said plainly what
   * kind of refusal it was. Null when neither is present — an honest "no code", which is what the
   * fallback in {@code AiBlockerClass} is for.
   */
  protected String blockerOf(String result) {
    return AiToolResult.fromEnvelope(result, mapper).getLedgerCode();
  }

  /**
   * An answer for a call that was deliberately not run.
   *
   * <p>Mandatory rather than tidy: an unanswered {@code call_id} strands the response id, and the
   * next turn chaining onto it is refused with HTTP 400 for a call that can never be satisfied.
   */
  protected ObjectNode skipped(FoundryResponse.FunctionCall call, String code, String message) {
    ObjectNode item = mapper.createObjectNode();
    item.put("type", "function_call_output");
    item.put("call_id", call.getCallId());
    // The same envelope the dispatcher produces for a refusal, from the same builder, so a skip and
    // a refusal cannot drift into two shapes of the same thing.
    item.put("output", envelope(code, message));
    return item;
  }

  /** A refusal envelope as a string. Never throws — it is built from two scalars. */
  protected String envelope(String code, String message) {
    try {
      return AiToolResult.blocked(code, message).toJson(mapper);
    } catch (Exception e) {
      ObjectNode fallback = mapper.createObjectNode();
      fallback.put("error", code);
      fallback.put("message", message);
      return fallback.toString();
    }
  }
}
