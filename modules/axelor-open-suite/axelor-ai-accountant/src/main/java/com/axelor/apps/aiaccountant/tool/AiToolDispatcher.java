package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.context.AiRequestContext;
import com.axelor.apps.aiaccountant.metrics.AiTurnMetrics;
import com.axelor.apps.aiaccountant.resilience.AiFailureClassifier;
import com.axelor.apps.aiaccountant.resilience.AiFailureKind;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
import com.axelor.apps.aiaccountant.resilience.AiTurnDeadline;
import com.axelor.apps.aiaccountant.resilience.AiUncertainWrites;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteReconciliation;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiTurnReadMemory;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.service.AiWriteAuthorisation;
import com.axelor.auth.AuthUtils;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a requested tool, runs it, and serialises the outcome.
 *
 * <p>Never throws for a tool-level problem: the model receives a structured {@code {"error": …}}
 * result so it can recover or explain. Only programming errors escape.
 *
 * <h2>Writes are executed, not staged</h2>
 *
 * A chat write runs immediately. There is no proposal queue, no confirmation token and no apply
 * step, because the previous design put a lexical gate — "does the user's message look like
 * да/yes?" — in front of every change. That gate could not read intent: it rejected "hell yeah" and
 * "давай, но с адрес София", it could not tell a corrected instruction from an approval, and it
 * forced one intent to be described, parked, re-described and applied across two turns.
 *
 * <p>Authorisation is now the model's judgement of the conversation, stated in the operating
 * procedure: a question is not permission, an ambiguous instruction is asked about, and a change is
 * made only when the current message authorises that specific change. What stays in Java is
 * everything Java can actually decide:
 *
 * <ul>
 *   <li><b>the channel</b> — the unattended OCR pipeline may only reach tools that opted into
 *       {@link AiWriteMode#UNATTENDED} and pass their own {@link AiTool#unattendedGuard};
 *   <li><b>the allowlists</b> — both empty by default, so enabling writes globally still grants
 *       nothing;
 *   <li><b>the company scope</b>, row permissions, document state and typed arguments, all enforced
 *       inside the tools themselves;
 *   <li><b>the repeat guard</b> — a write that failed is not retried against unchanged state, which
 *       is a loop-breaker rather than a permission check.
 * </ul>
 *
 * <p>Size guarding returns a <em>valid JSON</em> replacement rather than cutting bytes — truncating
 * serialised JSON mid-string would hand the model something unparseable. Tools are expected to
 * bound their own output; this is the backstop.
 */
@Singleton
public class AiToolDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(AiToolDispatcher.class);

  /**
   * The policy used when no injector is available.
   *
   * <p>Reads the same configuration keys as the injected singleton, so a directly-constructed
   * dispatcher is bounded by the same numbers rather than by none.
   */
  private static final AiResiliencePolicy DEFAULT_POLICY = new AiResiliencePolicy();

  private final AiToolRegistry registry;
  private final AiAccountantConfig config;
  private final AiWriteRepeatGuard repeatGuard;
  private final AiWriteTargetStateRegistry targetState;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public AiToolDispatcher(
      AiToolRegistry registry,
      AiAccountantConfig config,
      AiWriteRepeatGuard repeatGuard,
      AiWriteTargetStateRegistry targetState) {
    this.registry = registry;
    this.config = config;
    this.repeatGuard = repeatGuard;
    this.targetState = targetState;
  }

  /**
   * A dispatcher with its own repeat guard.
   *
   * <p>For callers that construct one directly rather than through Guice. A fresh guard starts with
   * nothing remembered, so it never blocks — which is the right default for anything that is not
   * the long-lived singleton.
   */
  public AiToolDispatcher(AiToolRegistry registry, AiAccountantConfig config) {
    this(registry, config, new AiWriteRepeatGuard(), new AiWriteTargetStateRegistry());
  }

  /**
   * @param name tool name requested by the model
   * @param argumentsJson the raw {@code arguments} string from the function_call item
   * @return a JSON string to send back as {@code function_call_output}
   */
  public String dispatch(String name, String argumentsJson) {
    return dispatch(name, argumentsJson, AiRequestContext.current());
  }

  /**
   * The same call, told which request it belongs to.
   *
   * <p>The context is descriptive: it says who is asking, in which company, about what. Nothing is
   * decided from it that was not already decided the same way — {@link #currentUserId()} prefers
   * the identity resolved once at the top of the turn over resolving it again per call, which is
   * the same user either way and one less place for the two to differ. Every gate below still reads
   * {@code AiExecutionContext} and {@code AiCompanyScope} directly, because those are the
   * enforcement points and a description must not become a second one.
   *
   * <p>A null context is ordinary: the OCR intake pipeline calls tools with no chat turn around
   * them, and every path here behaves exactly as it did before this parameter existed.
   */
  public String dispatch(String name, String argumentsJson, AiRequestContext context) {
    long started = System.nanoTime();
    String result = serialise(name, dispatchInternal(name, argumentsJson, context));
    // Measured here rather than in the caller so it covers argument parsing, every gate and the
    // serialisation — the whole of what the model waited for — and so a refusal is counted too.
    // Only the name, the duration and the size are recorded; never the arguments or the payload.
    AiTurnMetrics.tool(
        name,
        (System.nanoTime() - started) / 1_000_000L,
        result == null ? 0 : result.getBytes(StandardCharsets.UTF_8).length,
        isErrorEnvelope(result));
    if (AiToolDebugLog.enabled(name)) {
      AiToolDebugLog.record(name, argumentsJson, debugTarget(name, argumentsJson), result, context);
    }
    return result;
  }

  /** The tool's own target for the debug file; null when it cannot be read. */
  private String debugTarget(String name, String argumentsJson) {
    try {
      AiTool tool = registry.find(name);
      return tool == null
          ? null
          : tool.targetKey(
              argumentsJson == null || argumentsJson.trim().isEmpty()
                  ? mapper.createObjectNode()
                  : mapper.readTree(argumentsJson));
    } catch (Exception e) {
      return null;
    }
  }

  /** Cheap check for the {@code {"error":…}} envelope, without re-parsing the whole result. */
  private boolean isErrorEnvelope(String result) {
    return result != null && result.startsWith("{\"error\":");
  }

  /**
   * Turns a result into the JSON the model receives.
   *
   * <p>Serialisation and the size ceiling live here rather than inside {@code dispatchInternal} so
   * that method can answer in {@link AiToolResult} throughout — including the two cases where a
   * successful call still ends as a refusal, because its payload could not be written or was too
   * large. Both produce the same envelopes, with the same codes and the same wording, as before.
   */
  protected String serialise(String name, AiToolResult result) {
    String json;
    try {
      json = result.toJson(mapper);
    } catch (Exception e) {
      LOG.error("AI Accountant: could not serialise result of tool '" + name + "'.", e);
      return json(
          AiToolResult.blocked(
              AiBlockerCode.UNSERIALISABLE_RESULT.wire(),
              "The tool result could not be serialised."));
    }
    if (!result.isSuccess()) {
      return json;
    }
    int max = config.maxToolResultBytes();
    if (json.getBytes(StandardCharsets.UTF_8).length > max) {
      LOG.warn("AI Accountant: result of tool '{}' exceeded {} bytes; replaced.", name, max);
      return json(
          AiToolResult.blocked(
              "result_too_large",
              "The result exceeded "
                  + max
                  + " bytes. Narrow the query (fewer fields or a lower limit)."));
    }
    return json;
  }

  /** A refusal envelope, which cannot itself fail to serialise — it is three strings. */
  private String json(AiToolResult refusal) {
    try {
      return refusal.toJson(mapper);
    } catch (Exception e) {
      // Unreachable: the envelope is built from scalars. Answered rather than thrown, because a
      // tool-level problem has never been allowed to fail the turn.
      return "{\"error\":\"tool_failed\",\"message\":\"The tool result could not be serialised.\"}";
    }
  }

  /**
   * Why no tool can be resolved at all, or null when the registry is usable — see {@link
   * AiToolRegistry#problem}. Null too for a dispatcher built without a registry (test stubs that
   * override dispatch).
   */
  public String registryProblem() {
    return registry == null ? null : registry.problem();
  }

  protected AiToolResult dispatchInternal(
      String name, String argumentsJson, AiRequestContext context) {
    AiTool tool;
    try {
      tool = registry.find(name);
    } catch (RuntimeException e) {
      // A registry that failed validation throws on every access (it never caches a partial
      // result). That is a deployment fault, not a programming error in this call, and it must not
      // escape: the batch runner and the unattended OCR intake both call this and expect a result.
      // Every caller gets the same clean refusal, and nothing runs.
      LOG.error(
          "AI Accountant: refused '{}' — the tool registry is invalid: {}", name, e.getMessage());
      return error(
          AiBlockerCode.TOOL_REGISTRY_INVALID.wire(),
          "The tool configuration is invalid, so no tool can run. Nothing was read or changed."
              + " Tell the user the assistant is unavailable until an administrator fixes it.");
    }
    if (tool == null) {
      LOG.warn("AI Accountant: model requested unknown tool '{}'.", name);
      return error(
          AiBlockerCode.UNKNOWN_TOOL.wire(), "No tool named '" + name + "' is registered.");
    }

    // Parse first: it is free, and a precise "bad_arguments" is more useful to the model than a
    // generic refusal. Nothing is executed before the permission check below.
    JsonNode args;
    try {
      args =
          (argumentsJson == null || argumentsJson.trim().isEmpty())
              ? mapper.createObjectNode()
              : mapper.readTree(argumentsJson);
    } catch (Exception e) {
      return error(AiBlockerCode.BAD_ARGUMENTS.wire(), "Arguments were not valid JSON.");
    }

    // Either a real authenticated user, or an explicit system context opened by trusted Java (the
    // OCR intake pipeline runs on background threads with no Shiro subject). Absence of both is a
    // refusal — "no user" is never treated as implicit trust.
    if (!AiExecutionContext.isAuthorisedCaller()) {
      return error(
          AiBlockerCode.NO_USER.wire(),
          "No authenticated user in context; refusing to run a tool.");
    }

    // Every tool below reads or writes company-specific ERP data, so the company it is confined to
    // has to be knowable before it runs. Resolving here as well as inside each tool is deliberate:
    // it turns "no company" into one clear message the model can relay, instead of the same refusal
    // arriving later dressed as a per-record failure.
    Long companyId;
    try {
      companyId = AiCompanyScope.requireCompanyId();
    } catch (AiCompanyScope.ScopeUnavailableException e) {
      LOG.warn("AI Accountant: refused '{}' — no company scope: {}", name, e.getMessage());
      return error(AiBlockerCode.NO_COMPANY_SCOPE.wire(), e.getMessage());
    }

    // The company is the server's to decide. If the model supplied one anyway, drop it before the
    // tool can see it — no tool reads a company argument, and this keeps that true by construction.
    // The model is told, on the result itself: dropping it silently let a model that asked for
    // another company's data read the active company's answer as if it were the one it asked for.
    List<String> stripped = AiCompanyScope.stripCompanyArguments(args);
    String notice = null;
    if (!stripped.isEmpty()) {
      LOG.warn(
          "AI Accountant: ignored model-supplied {} on '{}'; the active company always applies.",
          stripped,
          name);
      notice =
          "Ignored the "
              + String.join(", ", stripped)
              + " argument: tools always run in the active company, and this result is for the"
              + " active company.";
    }

    Long userId = currentUserId(context);
    if (tool.kind() == AiToolKind.WRITE) {
      AiToolResult refusal = checkWriteChannel(tool, args, context);
      if (refusal != null) {
        return refusal.withNotice(notice);
      }
    } else if (AiTurnReadMemory.alreadyAnswered(userId, companyId, name, args.toString())) {
      // Identical read, same turn, same user and company, nothing written in between — the answer
      // cannot have changed, so the database is not asked again. The earlier result is returned as
      // the success it was: an "already answered" refusal with no data read as a failure, and left
      // a model that asked again because it no longer had the data in view without it.
      LOG.info(
          "AI Accountant: '{}' repeated identically in one turn; answered from the earlier"
              + " result.",
          name);
      return AiToolResult.ok(
              AiTurnReadMemory.earlierAnswer(userId, companyId, name, args.toString()),
              targetKey(tool, args))
          .withNotice(notice);
    }

    // The turn's clock, checked at the one moment stopping is free: before anything runs. A call
    // that was never started changed nothing, which is the only kind of interruption this system
    // is willing to perform — a write already in flight is left alone to finish or fail on its own.
    if (AiTurnDeadline.isExpired()) {
      LOG.warn("AI Accountant: refused '{}' — the turn's time budget is spent.", name);
      AiTurnMetrics.deadlineExceeded();
      return error(
          AiBlockerCode.TURN_DEADLINE_EXCEEDED.wire(),
          "This turn ran out of time before "
              + name
              + " was started, so nothing was read or changed by it. Say what should happen and it"
              + " will run in a new turn.");
    }

    String target = targetKey(tool, args);
    if (tool.kind() == AiToolKind.WRITE && !AiRoles.mayWrite()) {
      // G8: reading through the assistant is accounting.ai.read; letting it change records is
      // accounting.ai.write. Refused before anything runs, so there is nothing to reconcile.
      return error(
          "tool_error",
          "You are not allowed to let the AI Accountant create or change records (role"
              + " accounting.ai.write).",
          null);
    }
    AiToolResult outcome =
        tool.kind() == AiToolKind.WRITE
            ? executeWrite(tool, args, target)
            : executeRead(tool, args, target);
    if (!outcome.isSuccess()) {
      // Every bookkeeping a failure needs — the repeat guard, the uncertainty register, the metric
      // — was done by the method that produced it, where the kind of failure was still known.
      return outcome.withNotice(notice);
    }

    if (tool.kind() == AiToolKind.WRITE) {
      // The record really is different now, so both memories that assumed otherwise are released:
      // reads taken earlier in this turn are stale, and a remembered failure on this target no
      // longer describes the current state.
      AiTurnReadMemory.invalidate();
      repeatGuard.clearTarget(currentUserId(context), target);
      LOG.info("AI Accountant: applied '{}' as {}.", name, AiExecutionContext.callerDescription());
    } else {
      // A successful read is remembered twice over: once so an identical repeat in this turn is
      // answered from it, and once as evidence that the caller has actually looked at this record —
      // which is what lets AiWriteRepeatGuard release a write that previously failed on it.
      AiTurnReadMemory.remember(userId, companyId, name, args.toString(), outcome.getData());
      if (target != null) {
        repeatGuard.recordRead(userId, target);
      }
    }

    // The target travels with the outcome rather than being re-derived by whoever asks next. It is
    // the same key the repeat guard and the batch runner already compute from these arguments.
    return outcome.withNotice(notice);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Execution, under the resilience policy
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Runs a READ, retrying only what is safe to retry.
   *
   * <p>A read changes nothing, so repeating it cannot duplicate anything and the only question is
   * whether it could plausibly answer differently. That is exactly {@link AiFailureKind#TRANSIENT}:
   * a refused connection, a database saying "not now". A timeout is <em>not</em> retried here even
   * though a read is harmless to repeat — a query that ran long enough to time out will run long
   * enough again, and spending the turn's remaining budget discovering that is worse than answering
   * now.
   *
   * <p>A refusal from the tool itself is never retried at all. The tool answered; asking again gets
   * the same answer, and the model needs to see it.
   */
  protected AiToolResult executeRead(AiTool tool, JsonNode args, String target) {
    int attempts = 1 + policy().readRetryAttempts();
    for (int attempt = 1; ; attempt++) {
      try {
        return AiToolResult.ok(tool.execute(args), target);
      } catch (AiToolException e) {
        LOG.info("AI Accountant: tool '{}' rejected the call: {}", tool.name(), e.getMessage());
        return error("tool_error", e.getMessage(), e.details());
      } catch (Exception e) {
        AiFailureKind kind = AiFailureClassifier.classify(e);
        long remaining = AiTurnDeadline.remainingMillis();
        boolean lastAttempt = attempt >= attempts;
        if (!kind.isRetryable()
            || lastAttempt
            || !policy().hasTimeForAnotherAttempt(remaining)
            || !AiTurnDeadline.sleep(policy().backoffMillis(attempt, null, remaining))) {
          return technicalFailure(tool, kind, e);
        }
        AiTurnMetrics.toolRetried(tool.name(), kind.wire());
        LOG.warn(
            "AI Accountant: '{}' failed transiently (attempt {} of {}); retrying.",
            tool.name(),
            attempt,
            attempts);
      }
    }
  }

  /**
   * Runs a WRITE, and never repeats one on a guess.
   *
   * <h2>The order the questions have to be asked in</h2>
   *
   * <ol>
   *   <li><b>Did the tool answer?</b> An {@link AiToolException} is an answer — a business rule
   *       refusing — so nothing is uncertain, nothing is retried, and this behaves exactly as it
   *       always has.
   *   <li><b>Was the failure decisive?</b> {@link AiFailureKind#PERMANENT} means the call was
   *       answered and the answer was no. Same as before: reported, remembered by the repeat guard.
   *   <li><b>Does the tool know what its own success looks like?</b> A tool implementing {@link
   *       AiWriteVerifier} is asked first, because it can settle precisely what a fingerprint can
   *       only describe. A creation has no fingerprint at all — there was no record before the call
   *       — so this is the only mechanism that can ever answer for one; a lifecycle transition has
   *       an exact postcondition, and "the invoice is VENTILATED" beats "the record moved". A
   *       proven completion is reported as an ordinary success, in the tool's own result shape.
   *   <li><b>Otherwise, what does the record say?</b> The fingerprint taken before the call is
   *       compared with the record's state now, and the write is retried <em>only</em> if that
   *       comparison proves it did not land. Everything else — the record moved, no fingerprint, an
   *       unreadable record — is an uncertain outcome and stops.
   * </ol>
   *
   * <p>The probe is taken before the first attempt and reused: a retry only happens after the
   * record has been proven unchanged, so the baseline it was proven against is still current.
   *
   * <p><b>A target with neither a verifier nor a fingerprint provider can never be retried.</b>
   * That is intended and it is the conservative direction — an unprovable write is declared
   * uncertain rather than repeated — but it means such a tool will report uncertainty where a
   * reconcilable one reports a clean failure. The fix is an {@link AiWriteTargetState} provider, or
   * a verifier, for that kind of record; never a weaker rule here.
   */
  protected AiToolResult executeWrite(AiTool tool, JsonNode args, String target) {
    // Both sides of every later comparison have to exist, and this is the last moment the outcome
    // is still certain: the canonical company-scoped identity, its state, and what this turn had
    // already written before the call.
    String canonical = AiWriteReconciliation.canonical(target);
    AiWriteProbe probe =
        AiWriteProbe.capture(
            target, canonical, AiWriteReconciliation.fingerprint(canonical), baseline(tool, args));
    int retries = policy().writeRetryAttemptsWhenProvenUnwritten();
    for (int attempt = 1; ; attempt++) {
      try {
        // Marked, so the posting guard holds the tool to drafts whatever service it goes through.
        return AiToolResult.ok(
            AiExecutionContext.callAsToolWrite(() -> tool.execute(args)), target);
      } catch (AiToolException e) {
        LOG.info("AI Accountant: tool '{}' rejected the call: {}", tool.name(), e.getMessage());
        rememberFailure(tool, args, e.getMessage());
        return error("tool_error", e.getMessage(), e.details());
      } catch (Exception e) {
        // Drafts only: a refusal, decided and complete — the transaction rolled back. Not retried
        // and not "uncertain", and said in the words the user needs.
        AiDraftOnlyRefusal draftOnly = AiDraftOnlyRefusal.find(e);
        if (draftOnly != null) {
          LOG.info("AI Accountant: tool '{}' tried to post; refused (drafts only).", tool.name());
          rememberFailure(tool, args, draftOnly.getMessage());
          return error(AiBlockerCode.AXELOR_REFUSED_POSTING.wire(), draftOnly.getMessage());
        }
        AiFailureKind kind = AiFailureClassifier.classify(e);
        if (kind == AiFailureKind.PERMANENT) {
          LOG.error("AI Accountant: tool '" + tool.name() + "' failed unexpectedly.", e);
          rememberFailure(tool, args, e.getMessage());
          return error(AiBlockerCode.TOOL_FAILED.wire(), "The tool failed: " + e.getMessage());
        }
        // Once, before either mechanism reads anything: a check answered from a session still
        // holding the rolled-back entity is not a check.
        AiWriteReconciliation.clearSession();

        AiWriteOutcome verified = verify(tool, args, probe);
        AiTurnMetrics.writeVerified(verified.wire());
        if (verified.isCompleted()) {
          // The postcondition proves the change is in the database. Not a consolation message: an
          // ordinary success, carrying the shape the tool would have returned.
          LOG.warn(
              "AI Accountant: '{}' lost its answer, but its postcondition proves the change landed"
                  + " on {}; reporting success.",
              tool.name(),
              target == null ? "the new record" : target);
          AiTurnMetrics.writeConfirmedAfterLoss();
          return AiToolResult.ok(verified.result(), target);
        }

        boolean provenUnwritten = verified.isNotWritten();
        if (!provenUnwritten) {
          AiWriteReconciliation.Verdict verdict = AiWriteReconciliation.reconcile(probe);
          AiTurnMetrics.writeReconciled(verdict.wire());
          if (verdict.isUncertain()) {
            return uncertainWrite(tool, probe, kind, verdict);
          }
          provenUnwritten = true;
        }

        // Proven untouched. Anything the attempt reported having written was rolled back with it,
        // so it must not stay on the list the client refreshes and the workflow counts.
        probe.forgetNewEffects();
        long remaining = AiTurnDeadline.remainingMillis();
        if (attempt > retries
            || !policy().hasTimeForAnotherAttempt(remaining)
            || !AiTurnDeadline.sleep(policy().backoffMillis(attempt, null, remaining))) {
          // Proven untouched, so this is an ordinary technical failure and may be reported as one:
          // the record is exactly where it was, which is what makes "nothing was written" sayable.
          rememberFailure(tool, args, e.getMessage());
          return technicalFailure(tool, kind, e);
        }
        AiTurnMetrics.toolRetried(tool.name(), kind.wire());
        LOG.warn(
            "AI Accountant: retrying write '{}' — {} is provably unchanged, so nothing was"
                + " duplicated.",
            tool.name(),
            target == null ? "the target" : target);
      }
    }
  }

  /**
   * Asks the tool whether its own postcondition holds, if it can answer at all.
   *
   * <p>Defensive on every side. A verifier is code that runs after something has already gone
   * wrong, on a thread whose transaction has just rolled back; if it throws, the honest answer is
   * that nothing was established, not that the turn should fail twice over.
   */
  /**
   * The tool's own record of how things stood before the call, when it keeps one.
   *
   * <p>Runs on every write, so it must be cheap and it must not fail: a baseline that threw would
   * turn a successful write into a broken turn to serve a check that only matters when something
   * else has already gone wrong. Null simply means the verifier will have no baseline.
   */
  protected Object baseline(AiTool tool, JsonNode args) {
    if (!(tool instanceof AiWriteVerifier)) {
      return null;
    }
    try {
      return ((AiWriteVerifier) tool).beforeExecute(args);
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not capture the baseline for '{}': {}",
          tool.name(),
          e.getMessage());
      return null;
    }
  }

  protected AiWriteOutcome verify(AiTool tool, JsonNode args, AiWriteProbe probe) {
    if (!(tool instanceof AiWriteVerifier)) {
      return AiWriteOutcome.unknown();
    }
    try {
      AiWriteOutcome outcome = ((AiWriteVerifier) tool).verifyAfterAmbiguousFailure(args, probe);
      return outcome == null ? AiWriteOutcome.unknown() : outcome;
    } catch (RuntimeException e) {
      LOG.warn(
          "AI Accountant: the postcondition check for '{}' failed: {}",
          tool.name(),
          e.getMessage());
      return AiWriteOutcome.unknown();
    }
  }

  /**
   * The refusal for a write nobody can account for.
   *
   * <p>Worded for the one reader who can settle it. The model is told plainly that it may not
   * retry, may not claim success and may not report a failure — because all three are assertions
   * about a fact nobody has — and that the next move is the user looking at the record.
   */
  protected AiToolResult uncertainWrite(
      AiTool tool, AiWriteProbe probe, AiFailureKind kind, AiWriteReconciliation.Verdict verdict) {
    String target = probe == null ? null : probe.target();
    // Registered under BOTH names the record answers to. One supplier invoice is {@code Upload#946}
    // to the tools and {@code Invoice#606} to the workflow, and an uncertainty filed under one of
    // them is invisible to everything that speaks the other — which would leave the record open to
    // exactly the follow-up write this is meant to stop.
    AiUncertainWrites.record(target);
    if (probe != null && probe.canonical() != null) {
      AiUncertainWrites.record(probe.canonical());
    }
    // Anything the attempt reported writing is unaccounted for too: the ids were recorded inside a
    // transaction whose fate is unknown, so they may name rows that exist or rows that never did.
    if (probe != null) {
      for (String ref : probe.newEffects()) {
        int hash = ref.lastIndexOf('#');
        if (hash > 0) {
          String model = ref.substring(0, hash);
          int dot = model.lastIndexOf('.');
          AiUncertainWrites.record(
              (dot < 0 ? model : model.substring(dot + 1)) + ref.substring(hash));
        }
      }
    }
    AiTurnMetrics.uncertainWrite();
    LOG.error(
        "AI Accountant: the outcome of write '{}' on {} is unknown ({}, reconciliation {}). It is"
            + " neither retried nor reported as failed.",
        tool.name(),
        target == null ? "an unnamed record" : target,
        kind.wire(),
        verdict.wire());
    return error(
        AiBlockerCode.UNCERTAIN_WRITE.wire(),
        "The connection to "
            + (target == null ? "the record" : target)
            + " was lost while "
            + tool.name()
            + " was running, so it is NOT known whether the change was saved. Re-reading the record"
            + " could not settle it. Do NOT run this call again and do NOT report it as done or as"
            + " failed — either statement could be wrong. Tell the user, in their language, that"
            + " this one operation ended without a confirmed result and that they should open the"
            + " record and check it before anything else is done to it.");
  }

  /** A failure the record's state proves harmless, coded by what kind of failure it was. */
  protected AiToolResult technicalFailure(AiTool tool, AiFailureKind kind, Exception e) {
    switch (kind) {
      case TIMEOUT:
        LOG.warn("AI Accountant: tool '{}' timed out: {}", tool.name(), e.getMessage());
        return error(
            AiBlockerCode.TIMEOUT.wire(),
            "The call to " + tool.name() + " timed out: " + e.getMessage());
      case TRANSIENT:
        LOG.warn("AI Accountant: tool '{}' failed transiently: {}", tool.name(), e.getMessage());
        return error(
            AiBlockerCode.TRANSIENT_FAILURE.wire(),
            "The call to " + tool.name() + " could not be completed: " + e.getMessage());
      default:
        LOG.error("AI Accountant: tool '" + tool.name() + "' failed unexpectedly.", e);
        return error(AiBlockerCode.TOOL_FAILED.wire(), "The tool failed: " + e.getMessage());
    }
  }

  /**
   * The deadlines and attempt bounds this dispatcher runs under.
   *
   * <p>Looked up rather than injected so the two-argument constructor — the one tests and direct
   * callers use — keeps working unchanged, and defensively, because a missing injector must not be
   * the reason a tool call fails. The default policy reads the same configuration.
   */
  protected AiResiliencePolicy policy() {
    try {
      return Beans.get(AiResiliencePolicy.class);
    } catch (RuntimeException e) {
      return DEFAULT_POLICY;
    }
  }

  /**
   * Decides whether this write may run on this channel.
   *
   * <p>The channel comes from {@link AiExecutionContext#isSystem()} rather than a parameter: that
   * thread-local is already the module's single source of truth for who is calling, and it cannot
   * be reached from tool arguments, so the model cannot claim to be the pipeline. Adding a second
   * signal would only create a way for the two to disagree.
   *
   * @return {@code null} to proceed, or the refusal to return to the model
   */
  protected AiToolResult checkWriteChannel(AiTool tool, JsonNode args) {
    return checkWriteChannel(tool, args, AiRequestContext.current());
  }

  /**
   * The same gate, plus the one check that needs to know what the conversation asked for.
   *
   * <p>The channel rules above answer "may this tool ever write here?". {@link
   * AiWriteAuthorisation} answers "did anybody ask for this change?" — a question the allowlists
   * cannot reach, because a tool being enabled for the chat says nothing about whether the current
   * message called for it. It applies to the chat path only: the unattended path has no
   * conversation to derive an authorisation from and is governed entirely by the mode, the
   * allowlist and the tool's own guard.
   */
  protected AiToolResult checkWriteChannel(AiTool tool, JsonNode args, AiRequestContext context) {
    String name = tool.name();
    boolean intake = AiExecutionContext.isSystem();

    // Before every channel rule, because it applies to every channel: a record whose write outcome
    // this turn could not establish is closed to further writes for the rest of it. The batch
    // runner already stops the dependent writes inside one response; this is what stops the model
    // from simply asking again on the next iteration, which is the same duplicate arriving one
    // round trip later. It clears when the turn ends — a new turn re-reads the record, and by then
    // somebody has had the chance to look at it.
    String uncertainTarget = targetKey(tool, args);
    if (uncertainTarget != null && AiUncertainWrites.contains(uncertainTarget)) {
      LOG.warn(
          "AI Accountant: refused '{}' — an earlier write on {} ended without a knowable outcome.",
          name,
          uncertainTarget);
      return error(
          AiBlockerCode.UNCERTAIN_WRITE.wire(),
          "An earlier change to "
              + uncertainTarget
              + " in this turn ended without a confirmed result, so it is not known whether it was"
              + " saved. Nothing further will be written to this record until somebody has looked"
              + " at it. Do not try another way round it — tell the user to open the record and"
              + " check it.");
    }

    if (intake) {
      // A chat request must never be running inside a system scope. Assert it rather than trust it:
      // if it ever happened, a chat-only tool would silently become unattended.
      if (AuthUtils.getUser() != null) {
        LOG.error(
            "AI Accountant: '{}' invoked with both a user and a system scope; refusing.", name);
        return error(
            AiBlockerCode.AMBIGUOUS_CONTEXT.wire(),
            "The calling context is ambiguous; refusing to write anything.");
      }
      if (!tool.writeModes().contains(AiWriteMode.UNATTENDED)
          || !config.unattendedWriteTools().contains(name)) {
        LOG.info("AI Accountant: '{}' is not permitted to write unattended.", name);
        return error(
            AiBlockerCode.WRITE_NOT_ALLOWED.wire(),
            "Tool '"
                + name
                + "' may only be used by a person in the chat, not during automatic processing.");
      }
      String guard = tool.unattendedGuard(args);
      if (guard != null) {
        LOG.info("AI Accountant: unattended '{}' refused by its own guard: {}", name, guard);
        return error(
            AiBlockerCode.WRITE_NOT_ALLOWED.wire(),
            "Tool '" + name + "' cannot make this particular change unattended: " + guard + ".");
      }
      return null;
    }

    if (!config.chatWriteTools().contains(name)) {
      LOG.info("AI Accountant: '{}' is not in the chat-write allowlist.", name);
      return error(
          AiBlockerCode.WRITE_NOT_ALLOWED.wire(),
          "Tool '" + name + "' is not enabled for use from the chat.");
    }

    // Did the conversation ask for this change, on this record, in this company? Checked after the
    // allowlist and before the repeat guard: an unauthorised call must not be remembered as a
    // failed attempt on the record, because nothing was attempted on it.
    AiWriteAuthorisation.Refusal unauthorised = AiWriteAuthorisation.check(context, tool, args);
    if (unauthorised != null) {
      LOG.info(
          "AI Accountant: refused '{}' — the turn carries no authorisation for it: {}",
          name,
          unauthorised.message());
      return error(unauthorised.code().wire(), unauthorised.message());
    }

    // Refuse to repeat a call that already failed on unchanged state. Not a permission check: a
    // deterministic blocker (a missing configuration, an account that does not exist) produces the
    // same failure every time, and retrying it burns the turn instead of telling the user what has
    // to change.
    String repeated =
        repeatGuard.blockedReason(
            currentUserId(),
            targetKey(tool, args),
            actionKey(name, args),
            args.toString(),
            fingerprint(tool, args));
    if (repeated != null) {
      LOG.info(
          "AI Accountant: refused to repeat '{}' — it already failed and the record has not"
              + " changed since.",
          name);
      // Worded so the model cannot mistake this for something the ERP did. It previously relayed
      // the refusal as "Axelor recorded the failed attempt and it cannot be retried until the
      // record is regenerated" — a requirement that exists nowhere, invented to fill the gap where
      // an explanation should have been.
      return error(
          AiBlockerCode.ALREADY_FAILED.wire(),
          "This exact change was already attempted in this conversation and it failed: "
              + repeated
              + " NOTHING WAS WRITTEN, then or now, and Axelor keeps no memory of the attempt —"
              + " this refusal comes from the assistant's own repeat guard. The record is unchanged"
              + " since it failed, so the same call would fail for the same reason. Tell the user"
              + " that blocker, exactly as stated above, and do not invent a recovery step: nothing"
              + " has to be reloaded, regenerated or re-validated. The block clears by itself the"
              + " moment the record actually changes — either fix the cause with another tool, or"
              + " say what has to be corrected outside the chat.");
    }
    return null;
  }

  /**
   * Remembers a failed write, together with the target's state, so the identical call is not made
   * again until that state changes.
   *
   * <p>Best-effort: a loop-breaker must never be the reason a correctly reported failure turns into
   * an exception.
   */
  protected void rememberFailure(AiTool tool, JsonNode args, String message) {
    if (tool.kind() != AiToolKind.WRITE) {
      return;
    }
    try {
      repeatGuard.recordFailure(
          currentUserId(),
          targetKey(tool, args),
          actionKey(tool.name(), args),
          args.toString(),
          message == null ? "the change failed." : message,
          fingerprint(tool, args));
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not record the failed write: {}", e.getMessage());
    }
  }

  /**
   * The target's current state, as an opaque value the guard only ever compares.
   *
   * <p>Taken here rather than inside the guard so the guard stays a pure in-memory map with no JPA
   * or MetaScanner in it, and so both sides of the comparison are read through the same code path.
   *
   * <p>Read against the record's canonical identity where the active company can establish one, so
   * the same record fingerprints identically however the call happened to name it. Falling back to
   * the short ref keeps a target whose kind no resolver owns fingerprintable, which is how the
   * guard behaved before canonical identities existed.
   */
  protected String fingerprint(AiTool tool, JsonNode args) {
    String target = targetKey(tool, args);
    if (target == null) {
      return null;
    }
    String canonical = AiWriteReconciliation.canonical(target);
    try {
      return targetState.fingerprint(canonical == null ? target : canonical);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not fingerprint {}: {}", target, e.getMessage());
      return null;
    }
  }

  /**
   * What a change targets, as the short ref every other layer already speaks.
   *
   * <h2>One form, not two</h2>
   *
   * This used to derive its own key from the argument names — {@code upload:946} — while {@link
   * AiTool#targetKey} produced {@code Upload#946} for the batch runner, the tool ledger and the
   * workflow. Two keys for one record, and everything that compared across the boundary compared
   * strings that could never match: an uncertain write registered under one name was invisible to
   * the layer that reads the other, and the fingerprint providers had to be written against the
   * private form rather than the public one.
   *
   * <p>So the tool is asked, exactly as the batch runner asks it, and the argument-name heuristic
   * survives only as a fallback for a tool that overrides nothing — now emitting the same {@code
   * Model#id} form as everything else.
   *
   * <p>Null is not a harmless default. A write whose target cannot be named has its repeat guard
   * silently disabled, is never reconciled against a fingerprint, and {@code AiToolBatchRunner}
   * widens a failure on it to abort the entire response rather than one record. A creation
   * legitimately has none — that is what {@link AiWriteVerifier} is for — but an <em>existing
   * record</em> write with no target is a gap, not a design.
   */
  protected String targetKey(AiTool tool, JsonNode args) {
    if (tool != null) {
      try {
        String declared = tool.targetKey(args);
        if (declared != null && !declared.trim().isEmpty()) {
          return declared.trim();
        }
        // A tool that overrides targetKey and answers null means it: a creation, or a change with
        // no single record. Only fall through for one that never overrode it at all, which the
        // default implementation cannot distinguish — hence the heuristic below staying.
      } catch (RuntimeException e) {
        LOG.debug(
            "AI Accountant: could not read the target of '{}': {}", tool.name(), e.getMessage());
      }
    }
    if (args == null) {
      return null;
    }
    if (args.hasNonNull("uploadId")) {
      return "Upload#" + args.get("uploadId").asText();
    }
    if (args.hasNonNull("moveId")) {
      return "Move#" + args.get("moveId").asText();
    }
    if (args.hasNonNull("bankStatementId")) {
      return "BankStatement#" + args.get("bankStatementId").asText();
    }
    return null;
  }

  /**
   * Identity of one change within a target, so two different line edits are not treated as the same
   * failed call. The argument shape says which is which, so no per-tool declaration is needed.
   */
  protected String actionKey(String toolName, JsonNode args) {
    if (args == null) {
      return toolName;
    }
    if (args.hasNonNull("lineId")) {
      return toolName + ":line:" + args.get("lineId").asText();
    }
    if (args.hasNonNull("moveLineId")) {
      return toolName + ":line:" + args.get("moveLineId").asText();
    }
    return toolName;
  }

  /**
   * The current user's id, or null when running as the system (the intake pipeline).
   *
   * <p>Null deliberately disables the repeat guard for unattended processing: there is no
   * conversation there to loop, and the pipeline's own idempotency handles repetition.
   */
  protected Long currentUserId() {
    return AuthUtils.getUser() == null ? null : AuthUtils.getUser().getId();
  }

  /**
   * The same id, taken from the request when there is one.
   *
   * <p>Identical in value — the context resolved it from {@code AuthUtils} at the top of the turn —
   * but resolved once instead of per call, so the guard cannot key one call to a different identity
   * than the next. Falls back to the thread when no request is bound, which is the intake path.
   */
  protected Long currentUserId(AiRequestContext context) {
    return context != null && context.getUserId() != null ? context.getUserId() : currentUserId();
  }

  private AiToolResult error(String code, String message) {
    return error(code, message, null);
  }

  /**
   * @param details machine-readable fields from the refusal itself — a blocker code, the tool that
   *     repairs it — added alongside the prose so recovery does not depend on reading the wording
   */
  private AiToolResult error(String code, String message, Map<String, String> details) {
    // The envelope is built by AiToolResult now, in the same order and with the same rule about a
    // tool's details not overwriting it. What is added is the classification, taken from the code
    // rather than from the message — server-side only, and never sent.
    return AiToolResult.blocked(code, message, details);
  }
}
