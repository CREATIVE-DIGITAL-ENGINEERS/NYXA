package com.axelor.apps.aiaccountant.metrics;

import com.axelor.apps.aiaccountant.dto.AiToolSpec;
import com.axelor.apps.aiaccountant.tool.AiToolPublisher;
import com.axelor.apps.aiaccountant.tool.AiToolRegistry;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the fixed part of every request costs, before the user has typed anything.
 *
 * <h2>Why measure this separately</h2>
 *
 * The per-turn numbers in {@link AiTurnMetrics} say what a conversation cost; they cannot say what
 * of that was unavoidable. Two things are paid for on <em>every single</em> model request
 * regardless of the question: the agent's {@code instructions} and the full declaration of every
 * published tool. A tool that is never called still costs its declaration on every request of every
 * conversation, so the tool payload is the one overhead that grows silently as capabilities are
 * added.
 *
 * <p>The runtime operating procedure is a third, smaller cost, and a different one: it is sent as
 * the first message of a conversation and then lives in the stored history, so it is paid once per
 * conversation rather than once per request.
 *
 * <h2>Measured from what is actually sent</h2>
 *
 * Each tool is measured as the declaration {@link AiToolPublisher#declaration} builds — the same
 * object {@code publish()} writes to the agent, {@code type}/{@code name} envelope included — and
 * the total as the serialised array of them. There are no hand-kept size constants: a baseline
 * typed in from one release drifts from the method it is compared with. The instructions are
 * measured from the live agent definition when it could be read; when it could not, they are
 * reported as unknown and left <em>out</em> of the total rather than substituted.
 *
 * <h2>Token figures are estimates, and are labelled as such</h2>
 *
 * Bytes are exact. Tokens come from {@link AiTokenEstimate}, a conservative estimate, and every key
 * and log field carrying one says {@code estTokens}. The authoritative numbers are the ones the
 * service returns in {@code usage}, which {@link AiTurnMetrics} records.
 */
@Singleton
public class AiPromptBudget {

  /**
   * Same logger as {@link AiTurnMetrics} — see its field javadoc for why the name is not "ai.*".
   */
  private static final Logger LOG = LoggerFactory.getLogger("com.axelor.ai.metrics");

  private final AiToolRegistry registry;

  @Inject
  public AiPromptBudget(AiToolRegistry registry) {
    this.registry = registry;
  }

  /** One tool's share of the fixed payload. */
  public static final class ToolCost {
    private final String name;
    private final int descriptionBytes;
    private final int schemaBytes;
    private final int declarationBytes;
    private final int estTokens;

    ToolCost(String name, int descriptionBytes, int schemaBytes, String declaration) {
      this.name = name;
      this.descriptionBytes = descriptionBytes;
      this.schemaBytes = schemaBytes;
      this.declarationBytes = bytes(declaration);
      this.estTokens = AiTokenEstimate.of(declaration);
    }

    public String getName() {
      return name;
    }

    public int getDescriptionBytes() {
      return descriptionBytes;
    }

    public int getSchemaBytes() {
      return schemaBytes;
    }

    /** The whole declaration as sent: envelope, description and schema. */
    public int getTotalBytes() {
      return declarationBytes;
    }

    /** {@link AiTokenEstimate} of the declaration. An estimate, not a count. */
    public int getEstTokens() {
      return estTokens;
    }
  }

  /**
   * Per-tool costs for the tools the model can see, largest first.
   *
   * <p>Unpublished tools are excluded because they cost nothing: they are not in the agent
   * declaration, so they never appear in a request.
   */
  public List<ToolCost> toolCosts() {
    List<ToolCost> costs = new ArrayList<>();
    for (AiToolSpec spec : registry.specs()) {
      ObjectNode declaration = AiToolPublisher.declaration(spec);
      costs.add(
          new ToolCost(
              spec.getName(),
              bytes(spec.getDescription()),
              spec.getParameters() == null ? 0 : bytes(spec.getParameters().toString()),
              declaration.toString()));
    }
    costs.sort((a, b) -> Integer.compare(b.getTotalBytes(), a.getTotalBytes()));
    return costs;
  }

  /** The published declarations serialised as one array — the tool payload as the model gets it. */
  String toolPayload() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    for (AiToolSpec spec : registry.specs()) {
      tools.add(AiToolPublisher.declaration(spec));
    }
    return tools.toString();
  }

  /**
   * The whole picture as a map, so a controller or a test can read it without parsing a log line.
   *
   * @param operatingProcedure the runtime procedure text, passed in rather than imported so this
   *     class stays free of the chat service
   * @param agentInstructions the live agent's instructions, or null when they could not be read
   */
  public Map<String, Object> snapshot(String operatingProcedure, String agentInstructions) {
    List<ToolCost> costs = toolCosts();
    String payload = toolPayload();
    int payloadBytes = bytes(payload);
    int payloadTokens = AiTokenEstimate.of(payload);
    boolean instructionsKnown = agentInstructions != null;

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("toolCount", costs.size());
    m.put("registeredToolCount", registry.all().size());
    m.put("unpublishedToolCount", registry.all().size() - costs.size());
    m.put("toolPayloadBytes", payloadBytes);
    m.put("toolPayloadEstTokens", payloadTokens);
    m.put("instructionsKnown", instructionsKnown);
    m.put("instructionsBytes", instructionsKnown ? bytes(agentInstructions) : null);
    m.put(
        "instructionsEstTokens", instructionsKnown ? AiTokenEstimate.of(agentInstructions) : null);
    // Only what was measured. With the instructions unknown this is the tool payload alone, and
    // instructionsKnown=false says so — never a remembered figure standing in for the agent's text.
    m.put(
        "fixedPerRequestEstTokens",
        payloadTokens + (instructionsKnown ? AiTokenEstimate.of(agentInstructions) : 0));
    m.put("operatingProcedureBytes", bytes(operatingProcedure));
    m.put("operatingProcedureEstTokens", AiTokenEstimate.of(operatingProcedure));
    List<Map<String, Object>> perTool = new ArrayList<>();
    for (ToolCost c : costs) {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("name", c.getName());
      t.put("descriptionBytes", c.getDescriptionBytes());
      t.put("schemaBytes", c.getSchemaBytes());
      t.put("totalBytes", c.getTotalBytes());
      t.put("estTokens", c.getEstTokens());
      t.put(
          "shareOfToolPayloadPct",
          payloadBytes == 0 ? 0 : (c.getTotalBytes() * 100) / payloadBytes);
      perTool.add(t);
    }
    m.put("tools", perTool);
    return m;
  }

  /**
   * Logs the fixed overhead once, at startup.
   *
   * <p>Deliberately at INFO and on the same logger as the per-turn lines: an aggregation over a
   * day's log then has both the constant and the variable part of the cost in one place, and a jump
   * in the constant is visible the moment a tool is added.
   *
   * @param agentInstructions the live Foundry {@code instructions}, or null when the agent could
   *     not be read — they live on the agent, not in this codebase
   * @param contextWindowTokens the configured model window, for the headroom figure
   * @param reservedTokens what the window holds back for this turn's documents, tool results and
   *     the model's own output — see {@code AiContextBudget#reservedTokens}
   */
  public void logAtStartup(
      String operatingProcedure,
      String agentInstructions,
      int contextWindowTokens,
      int reservedTokens) {
    try {
      Map<String, Object> snap = snapshot(operatingProcedure, agentInstructions);
      boolean instructionsKnown = (Boolean) snap.get("instructionsKnown");
      int fixed = (Integer) snap.get("fixedPerRequestEstTokens");
      LOG.info(
          "ai.budget modelVisibleTools={} ({} unpublished of {} registered)"
              + " toolPayloadBytes={} (~{} estTokens)"
              + " instructionsBytes={} (~{} estTokens)"
              + " fixedPerRequestEstTokens={}{}"
              + " operatingProcedureBytes={} (~{} estTokens, once per conversation)"
              + " contextWindowTokens={} reservedTokens={} headroomEstTokens={}",
          snap.get("toolCount"),
          snap.get("unpublishedToolCount"),
          snap.get("registeredToolCount"),
          snap.get("toolPayloadBytes"),
          snap.get("toolPayloadEstTokens"),
          instructionsKnown ? snap.get("instructionsBytes") : "UNKNOWN",
          instructionsKnown ? snap.get("instructionsEstTokens") : "?",
          fixed,
          instructionsKnown ? "" : " (tools only — agent instructions not read)",
          snap.get("operatingProcedureBytes"),
          snap.get("operatingProcedureEstTokens"),
          contextWindowTokens,
          reservedTokens,
          Math.max(0, contextWindowTokens - reservedTokens - fixed));
      for (ToolCost c : toolCosts()) {
        LOG.info(
            "ai.budget.tool name={} descriptionBytes={} schemaBytes={} totalBytes={} estTokens={}",
            c.getName(),
            c.getDescriptionBytes(),
            c.getSchemaBytes(),
            c.getTotalBytes(),
            c.getEstTokens());
      }
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not measure the prompt budget: {}", e.getMessage());
    }
  }

  /** Empty-safe UTF-8 byte count — the wire size, not the character count. */
  private static int bytes(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }
}
