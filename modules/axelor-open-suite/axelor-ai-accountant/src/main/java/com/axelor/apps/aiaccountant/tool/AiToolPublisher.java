package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.client.AiAccountantException;
import com.axelor.apps.aiaccountant.client.AiTokenProvider;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.AiToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the Foundry agent's declared function tools in step with the Java registry.
 *
 * <p>Why this exists: the Responses service refuses request-local {@code tools} whenever an agent
 * is specified ("Not allowed when agent is specified"). Tools must therefore live on the agent
 * definition, which makes part of our tool contract Azure state.
 *
 * <p>Writing the agent is an outward-facing change to a CTO-owned resource, so it is never
 * automatic: {@link #diff()} is safe and read-only and runs at startup for a warning, while {@link
 * #publish()} requires {@code aiaccountant.azure.allow.agent.publish=true}.
 *
 * <p>Publication <em>merges</em>: non-function tools (e.g. {@code web_search}), {@code
 * instructions} and {@code model} are preserved exactly as configured in the portal.
 */
@Singleton
public class AiToolPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(AiToolPublisher.class);

  private final AiAccountantConfig config;
  private final AiTokenProvider tokenProvider;
  private final AiToolRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public AiToolPublisher(
      AiAccountantConfig config, AiTokenProvider tokenProvider, AiToolRegistry registry) {
    this.config = config;
    this.tokenProvider = tokenProvider;
    this.registry = registry;
  }

  /** Result of comparing the Java registry against the live agent definition. */
  public static final class ToolDiff {
    private final List<String> missingOnAgent = new ArrayList<>();
    private final List<String> staleOnAgent = new ArrayList<>();
    private final List<String> outOfDateOnAgent = new ArrayList<>();
    private String agentInstructions;

    /**
     * The live agent's {@code instructions}, read in the same request as the tools, or null when
     * the definition carries none. Measured by {@code AiPromptBudget}; never logged as text.
     */
    public String getAgentInstructions() {
      return agentInstructions;
    }

    public List<String> getMissingOnAgent() {
      return missingOnAgent;
    }

    public List<String> getStaleOnAgent() {
      return staleOnAgent;
    }

    /** Declared under the right name, but with a description or schema that is no longer ours. */
    public List<String> getOutOfDateOnAgent() {
      return outOfDateOnAgent;
    }

    public boolean isInSync() {
      return missingOnAgent.isEmpty() && staleOnAgent.isEmpty() && outOfDateOnAgent.isEmpty();
    }

    @Override
    public String toString() {
      return "missing on agent="
          + missingOnAgent
          + ", stale on agent="
          + staleOnAgent
          + ", out of date on agent="
          + outOfDateOnAgent;
    }
  }

  /**
   * Read-only. Never writes.
   *
   * <h2>Compared against what is published, not against what is registered</h2>
   *
   * {@link #publish()} writes {@link AiToolRegistry#specs()}, which is the published subset, so the
   * agent is compared with that and not with {@link AiToolRegistry#all()}. The two differ by
   * exactly the tools that deliberately opt out of publication; comparing with the full registry
   * would log OUT OF SYNC on every boot — a warning that publishing cannot clear, because
   * publishing is what puts it there. A check that cannot go green is not a check; it teaches the
   * reader to skip the line.
   *
   * <h2>And on content, not only on names</h2>
   *
   * Names alone miss the whole class of drift this exists to catch. When a tool gains a parameter
   * (say {@code offset} on {@code search_ocr_records}), the name is still declared and a name-only
   * check reports IN SYNC while the model cannot see the argument the operating procedure tells it
   * to use. Descriptions and schemas are compared too: they are the contract, and a schema that has
   * quietly diverged is worse than a missing tool, which at least fails loudly.
   */
  public ToolDiff diff() {
    JsonNode definition = readDefinition();
    Map<String, JsonNode> onAgent = new LinkedHashMap<>();
    for (JsonNode t : definition.path("tools")) {
      if ("function".equals(t.path("type").asText())) {
        onAgent.put(t.path("name").asText(""), t);
      }
    }

    ToolDiff d = new ToolDiff();
    JsonNode instructions = definition.path("instructions");
    d.agentInstructions = instructions.isTextual() ? instructions.asText() : null;
    Set<String> published = new LinkedHashSet<>();
    for (AiToolSpec spec : registry.specs()) {
      published.add(spec.getName());
      JsonNode declared = onAgent.get(spec.getName());
      if (declared == null) {
        d.getMissingOnAgent().add(spec.getName());
      } else if (!matches(spec, declared)) {
        d.getOutOfDateOnAgent().add(spec.getName());
      }
    }
    for (String n : onAgent.keySet()) {
      if (!published.contains(n)) {
        d.getStaleOnAgent().add(n);
      }
    }
    return d;
  }

  /**
   * Whether the agent's declaration still says what the Java tool says.
   *
   * <p>Schemas are compared as parsed JSON rather than as text, so a re-serialisation that reorders
   * fields is not mistaken for a change — Jackson compares object nodes by content.
   */
  private boolean matches(AiToolSpec spec, JsonNode declared) {
    if (!spec.getDescription().equals(declared.path("description").asText(null))) {
      return false;
    }
    JsonNode ours = spec.getParameters();
    JsonNode theirs = declared.path("parameters");
    return ours == null ? theirs.isMissingNode() || theirs.isNull() : ours.equals(theirs);
  }

  /**
   * Publishes a new agent version whose function tools match the registry exactly, preserving every
   * other part of the definition.
   *
   * @return the created version id
   */
  public String publish() {
    if (!config.isAgentPublishAllowed()) {
      throw new AiAccountantException(
          "Publishing the agent definition is disabled. Set"
              + " aiaccountant.azure.allow.agent.publish=true to allow it.");
    }
    JsonNode current = readDefinition();

    // Copy the whole definition and replace only the function tools. Naming the fields to keep
    // instead would silently drop anything the portal added that this code has not heard of — the
    // live agent already carries a "reasoning" block that an explicit kind/model/instructions copy
    // would have thrown away, turning a tool refresh into a behaviour change.
    ObjectNode definition =
        current.isObject() ? ((ObjectNode) current).deepCopy() : mapper.createObjectNode();
    if (!definition.hasNonNull("kind")) {
      definition.put("kind", "prompt");
    }
    definition.remove("tools");

    ArrayNode tools = definition.putArray("tools");
    // Preserve platform/hosted tools verbatim — dropping web_search would silently degrade the
    // agent.
    for (JsonNode t : current.path("tools")) {
      if (!"function".equals(t.path("type").asText())) {
        tools.add(t.deepCopy());
      }
    }
    for (AiToolSpec spec : registry.specs()) {
      tools.add(declaration(spec));
    }

    // The versions route takes the definition alone — the agent is named by the URL.
    ObjectNode body = mapper.createObjectNode();
    body.set("definition", definition);

    HttpResult res = http("POST", config.agentCreateUrl(), body.toString());
    if (res.status < 200 || res.status >= 300) {
      throw new AiAccountantException(
          "Could not publish the agent definition (HTTP " + res.status + "): " + res.body);
    }
    try {
      // The versions route answers with the created agent.version object itself.
      String version = mapper.readTree(res.body).path("id").asText("?");
      LOG.info("AI Accountant: published agent version {}.", version);
      return version;
    } catch (IOException e) {
      throw new AiAccountantException("Malformed publish response.", e);
    }
  }

  /**
   * One function-tool declaration, exactly as {@link #publish()} writes it to the agent.
   *
   * <p>Shared with {@code AiPromptBudget} so the size it reports is the size of what the model is
   * actually given — envelope included — rather than a second, hand-kept idea of it.
   */
  public static ObjectNode declaration(AiToolSpec spec) {
    ObjectNode fn = JsonNodeFactory.instance.objectNode();
    fn.put("type", "function");
    fn.put("name", spec.getName());
    fn.put("description", spec.getDescription());
    fn.set("parameters", spec.getParameters());
    return fn;
  }

  /** {@code versions.latest.definition} of the configured agent. */
  protected JsonNode readDefinition() {
    String url = config.agentDefinitionUrl();
    if (url.isEmpty()) {
      throw new AiAccountantException(
          "AI Accountant: the Foundry project endpoint is not configured ("
              + AiAccountantConfig.ENV_PROJECT_ENDPOINT
              + "), so the agent definition cannot be read.");
    }
    HttpResult res = http("GET", url, null);
    if (res.status != 200) {
      throw new AiAccountantException(
          "Could not read the agent definition (HTTP " + res.status + "): " + res.body);
    }
    try {
      return mapper.readTree(res.body).path("versions").path("latest").path("definition");
    } catch (IOException e) {
      throw new AiAccountantException("Malformed agent definition response.", e);
    }
  }

  /** Minimal transport result, so HTTP can be stubbed in tests. */
  protected static final class HttpResult {
    final int status;
    final String body;

    HttpResult(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  protected HttpResult http(String method, String url, String body) {
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(config.connectTimeout()))
              .build();
      HttpRequest.Builder b =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(config.requestTimeout()))
              .header("Authorization", "Bearer " + tokenProvider.bearerToken());
      if (body == null) {
        b.GET();
      } else {
        b.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      HttpResponse<String> resp =
          client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new HttpResult(resp.statusCode(), resp.body());
    } catch (IOException e) {
      throw new AiAccountantException("Cannot reach Foundry: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAccountantException("Interrupted while calling Foundry.", e);
    }
  }
}
