package com.axelor.apps.aiaccountant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.Set;

/**
 * One capability the AI may invoke. Implementations are discovered automatically by {@code
 * AiToolRegistry} (MetaScanner), so any module — e.g. axelor-ai-ocr in Phase B — can contribute
 * tools without this module knowing about it.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link #name()} is a stable snake_case identifier, unique across all modules.
 *   <li>{@link #parametersSchema()} documents the arguments <em>to the model</em>. It is not a
 *       runtime validator — {@link #execute(JsonNode)} validates its own typed arguments (see
 *       {@link AiToolArgs}), because real constraints (record exists, company scope, permissions)
 *       cannot be expressed in a schema anyway.
 *   <li>{@link #execute(JsonNode)} returns a value Jackson can serialise. It must be <em>bounded by
 *       construction</em> — page/limit large result sets and report {@code truncated} rather than
 *       relying on the dispatcher's size guard.
 *   <li>Implementations must be safe to call as the current user and must reuse existing Axelor
 *       services/repositories and permissions.
 * </ul>
 */
public interface AiTool {

  String name();

  String description();

  JsonNode parametersSchema();

  AiToolKind kind();

  /**
   * Which area of the ERP this tool belongs to.
   *
   * <p>Deliberately has <b>no default</b>, unlike every other optional member of this interface. A
   * default would put a forgotten override into whichever group it named, silently and plausibly,
   * and the whole value of this field is that it can be trusted without reading the class. A
   * missing one is a compile error, which is the right failure for metadata nothing else can
   * verify.
   */
  AiCapability capability();

  /**
   * @param args the model-supplied arguments, already parsed from the JSON string
   * @return any Jackson-serialisable value
   * @throws AiToolException for bad input or a refused operation
   */
  Object execute(JsonNode args) throws AiToolException;

  /**
   * Whether this tool is declared to the model.
   *
   * <p>Every published tool costs its name, description and JSON schema on <em>every single</em>
   * model request of every conversation, whether or not it is ever called — so a capability that no
   * flow actually reaches is a permanent tax on latency and context. Returning false keeps the tool
   * registered, dispatchable and tested, but leaves it out of the agent declaration, which is what
   * the model sees.
   *
   * <p>This is not a way to hide a dangerous tool. An unpublished tool is still reachable by any
   * Java caller that dispatches it by name, and every guard it declares still applies. It means
   * only: "nothing asks the model to choose this, so do not pay for describing it."
   */
  default boolean published() {
    return true;
  }

  /**
   * Which channels this tool may be invoked from. Ignored for {@link AiToolKind#READ} tools.
   *
   * <p>Defaults to {@link AiWriteMode#CONFIRMED} only, so a new write tool is never unattended by
   * accident — opting into {@link AiWriteMode#UNATTENDED} has to be a deliberate act.
   */
  default Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /**
   * Last check before an unattended (pipeline-driven) write, given the actual arguments.
   *
   * <p>Tool-level modes are too coarse on their own: one tool can accept both a trivially
   * reversible argument and a payment-affecting one. This hook is where that distinction lives —
   * {@code update_ocr_record}, for instance, allows an unattended {@code externalReference} but not
   * an unattended {@code bankDetailsId}.
   *
   * @return {@code null} to allow, or a human-readable reason to refuse
   */
  default String unattendedGuard(JsonNode args) {
    return "this tool does not allow unattended writes";
  }

  /**
   * Which single record this call is aimed at, as a short ref, or null when it has no single one.
   *
   * <p>Used by {@link AiToolBatchRunner} to decide how far one failure reaches. A batch abort used
   * to be global: any refusal stopped every write left in the response, so an instruction covering
   * four invoices that hit a duplicate number on the second left the third and fourth untouched and
   * unattempted, and the user had to say «продължи» to get work that had nothing to do with the
   * blocked one. With a target, the abort is confined to the record that actually failed.
   *
   * <p>Returning null is the safe answer and is what the default does for anything without an
   * {@code uploadId}: a call whose blast radius cannot be identified — company configuration,
   * supplier master data shared by several documents — falls back to stopping the whole batch,
   * because independence is a claim, and an unverified claim about a write is not worth making.
   *
   * @param args the same arguments {@link #execute(JsonNode)} will receive
   */
  default String targetKey(JsonNode args) {
    if (args == null) {
      return null;
    }
    JsonNode uploadId = args.get("uploadId");
    if (uploadId == null || uploadId.isNull()) {
      return null;
    }
    String text = uploadId.asText("").trim();
    return text.isEmpty() ? null : "Upload#" + text;
  }

  /**
   * A second, more specific reference for the workflow layer to track completion against — never
   * for authorisation, the repeat guard, or batch abort scope, which stay on {@link #targetKey}.
   *
   * <h2>Why a second reference, and not a richer {@link #targetKey}</h2>
   *
   * {@link #targetKey} answers "which record's blast radius does a failure here cover?", and a
   * general answer — {@code Partner#84}, {@code Invoice#606} — is exactly right for that: it is
   * what the repeat guard fingerprints, what a batch abort scopes itself to, and what a client
   * refreshes. It is the wrong answer for "did the specific change this call asked for land?",
   * because a record's own identity says nothing about which of its many possible edits this
   * particular call was for — {@code Partner#84} exists identically whether an IBAN update on it
   * just succeeded or was just refused. Overloading {@link #targetKey} to sometimes answer one
   * question and sometimes the other would put a single value nobody can adjust for two different
   * meanings.
   *
   * <p>Null by default, which reproduces {@link #targetKey}'s own answer everywhere this is asked —
   * see {@link AiToolBatchRunner}, the only caller. A tool overrides this only where it can name an
   * exact, checkable postcondition from arguments alone (never from a result, and never from a
   * human-readable message): the same identity for a call that fails and for the retry that lands
   * it, so a completion resolver — see {@code AiCompletionResolver} — can prove the one against the
   * other. Company scope and any id used here must come from the request's own trusted context (the
   * active company, an id the caller supplied to name an existing record), never from prose a tool
   * wrote for a person to read.
   *
   * @param args the same arguments {@link #execute(JsonNode)} will receive
   */
  default String completionTarget(JsonNode args) {
    return null;
  }

  /**
   * Dependency rank, for a caller that has to order several writes. Lower runs first.
   *
   * <p>Ordering used to be enforced by the proposal queue, which sorted a compound change by this
   * rank. That queue is gone: one user intent is now expressed as one high-level tool call which
   * orders its own steps internally ({@code edit_supplier_invoice} creates the draft, then sets the
   * supplier, then the header, then the lines). The rank is kept because the dependency it
   * describes is real — a supplier has to exist before an invoice can point at it — and a caller
   * that does make several calls in one reply should make them in this order.
   *
   * <p>The scale used by the OCR tools, for reference: supplier master data 5, invoice corrections
   * 20, per-line product/tax 50-60.
   *
   * @return the rank; {@code 50} by default, which is neither first nor last
   */
  default int writeOrder() {
    return 50;
  }
}
