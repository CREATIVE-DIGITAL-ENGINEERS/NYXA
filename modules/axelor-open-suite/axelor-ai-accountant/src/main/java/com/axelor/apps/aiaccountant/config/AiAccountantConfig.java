package com.axelor.apps.aiaccountant.config;

import com.axelor.app.AppSettings;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration for the AI Accountant.
 *
 * <p>Reads env var first, then {@link AppSettings} — the same precedence as {@code
 * AzureDocumentIntelligenceExtractor}, so both Azure integrations are configured the same way.
 *
 * <p>Every accessor is {@code protected} so tests can override without touching AppSettings.
 *
 * <p>NB: AXELOR_CONFIG_* env mapping replaces '_' with '.', so property keys here are dotted only
 * (never hyphenated) to stay settable from the container environment.
 *
 * <h2>Five settings an administrator can change without a redeploy</h2>
 *
 * {@link #isEnabled()}, {@link #isWriteToolsAllowed()}, {@link #isRouterEnabled()}, {@link
 * #routerMode()} and {@link #maxToolIterations()} consult {@link
 * AiAccountantSettingOverrideService} first, and fall through to the env/AppSettings chain below
 * whenever it has nothing to say. Those five are the switches somebody needs during an incident,
 * when waiting for a container restart is the whole problem. Everything else here — endpoints,
 * credentials, timeouts, token budgets — is deployment configuration and is deliberately left
 * exactly as it was.
 *
 * <p>Nothing is cached: each call resolves again, so a change on the Settings screen takes effect
 * on the next call rather than on the next restart.
 */
@Singleton
public class AiAccountantConfig {

  public static final String ENV_AGENT_ENDPOINT = "AZURE_FOUNDRY_AGENT_ENDPOINT";
  public static final String ENV_PROJECT_ENDPOINT = "AZURE_FOUNDRY_PROJECT_ENDPOINT";
  public static final String ENV_AGENT_NAME = "AZURE_FOUNDRY_AGENT_NAME";
  public static final String ENV_API_VERSION = "AZURE_FOUNDRY_API_VERSION";
  public static final String ENV_AUTH_MODE = "AZURE_FOUNDRY_AUTH";
  public static final String ENV_TENANT_ID = "AZURE_FOUNDRY_TENANT_ID";
  public static final String ENV_CLIENT_ID = "AZURE_FOUNDRY_CLIENT_ID";
  public static final String ENV_CLIENT_SECRET = "AZURE_FOUNDRY_CLIENT_SECRET";
  public static final String ENV_ENABLED = "AIACCOUNTANT_ENABLED";
  public static final String ENV_MODE = "AIACCOUNTANT_MODE";
  public static final String ENV_ROUTER_AGENT_ENDPOINT = "AZURE_FOUNDRY_ROUTER_AGENT_ENDPOINT";
  public static final String ENV_ROUTER_ENABLED = "AIACCOUNTANT_ROUTER_ENABLED";
  public static final String ENV_ROUTER_MODE = "AIACCOUNTANT_ROUTER_MODE";

  /** Observed only: the router runs beside the existing one and decides nothing. */
  public static final String ROUTER_MODE_SHADOW = "shadow";

  /**
   * Primary: a valid router ROUTE or CONTINUE controls the turn; CLARIFICATION, INVALID, FAILED, a
   * malformed reply or a timeout all fall back to {@code AiRouteAdapter} and the turn still reaches
   * the main agent — the router never ends a turn with a user-facing question.
   *
   * <p>"Controls the route" is the whole of it. The route is five enum values; it selects a
   * playbook, a domain guide and a job, and it is one of the inputs {@code AiWriteAuthorisation}
   * weighs. It is not a permission, it does not name a record, and it cannot reach data — every
   * company, scope, target and authorisation check runs afterwards, unchanged, on whichever route
   * was chosen.
   */
  public static final String ROUTER_MODE_ACTIVE = "active";

  /** Required on the agent endpoint, forbidden on /openai/v1 paths. */
  public static final String DEFAULT_API_VERSION = "v1";

  public static final String DEFAULT_AGENT_NAME = "ai-accountant";

  /**
   * Code of the Studio {@code App} record this module registers under Application Configuration.
   */
  public static final String APP_CODE = "ai-accountant";

  /** Entra scope for the Foundry data plane (agent endpoint is Azure-RBAC protected). */
  public static final String SCOPE = "https://ai.azure.com/.default";

  /**
   * Entra scope for Azure Resource Manager, which is where metrics are read from.
   *
   * <p>A different audience from {@link #SCOPE}, and that is the whole point of it being its own
   * constant: a token minted for the Foundry data plane is not accepted by ARM and vice versa. The
   * two are acquired by separate providers so that adding metrics could not change, or break, the
   * token the chat depends on.
   */
  public static final String ARM_SCOPE = "https://management.azure.com/.default";

  /** What {@code az account get-access-token --resource} wants for ARM: no {@code /.default}. */
  public static final String ARM_CLI_RESOURCE = "https://management.azure.com/";

  public static final String AUTH_CLIENT_SECRET = "client-secret";
  public static final String AUTH_AZURE_CLI = "azure-cli";

  public static final String MODE_AZURE = "azure";
  public static final String MODE_MOCK = "mock";

  /**
   * Transport selection, mirroring {@code aiocr.extractor.mode}: {@code azure} (default) or {@code
   * mock} for offline development and deterministic tests.
   */
  protected String mode() {
    return firstNonBlank(env(ENV_MODE), cfg("aiaccountant.azure.mode", MODE_AZURE));
  }

  public boolean isMockMode() {
    return MODE_MOCK.equalsIgnoreCase(mode().trim());
  }

  /** Dedicated agent Responses endpoint — the only route used for chat. */
  protected String agentEndpoint() {
    return firstNonBlank(env(ENV_AGENT_ENDPOINT), cfg("aiaccountant.azure.agent.endpoint", ""));
  }

  /**
   * Foundry project endpoint. Used only for project-level operations (reading/publishing the agent
   * definition), never for chat.
   */
  protected String projectEndpoint() {
    return firstNonBlank(env(ENV_PROJECT_ENDPOINT), cfg("aiaccountant.azure.project.endpoint", ""));
  }

  protected String agentName() {
    return firstNonBlank(env(ENV_AGENT_NAME), cfg("aiaccountant.azure.agent", DEFAULT_AGENT_NAME));
  }

  protected String apiVersion() {
    return firstNonBlank(
        env(ENV_API_VERSION), cfg("aiaccountant.azure.apiversion", DEFAULT_API_VERSION));
  }

  protected String authMode() {
    return firstNonBlank(env(ENV_AUTH_MODE), cfg("aiaccountant.azure.auth", AUTH_CLIENT_SECRET));
  }

  protected String tenantId() {
    return firstNonBlank(env(ENV_TENANT_ID), cfg("aiaccountant.azure.tenant", ""));
  }

  protected String clientId() {
    return firstNonBlank(env(ENV_CLIENT_ID), cfg("aiaccountant.azure.clientid", ""));
  }

  /** Never log this value. */
  protected String clientSecret() {
    return firstNonBlank(env(ENV_CLIENT_SECRET), cfg("aiaccountant.azure.secret", ""));
  }

  public boolean isEnabled() {
    if (!isAppActive()) {
      return false;
    }
    AiAccountantSettingOverrideService overrides = overridesOrNull();
    if (overrides != null) {
      AiAccountantOverride decision = overrides.enabled();
      if (decision.isDecided()) {
        return decision.isOn();
      }
    }
    String v = firstNonBlank(env(ENV_ENABLED), cfg("aiaccountant.enabled", "false"));
    return "true".equalsIgnoreCase(v.trim());
  }

  /**
   * Whether the AI Accountant Axelor application is installed and active in Application
   * Configuration.
   *
   * <p>The outermost switch: deactivating the app must stop every agent operation and background
   * job outright, before any of the finer {@link AiAccountantSetting} toggles are even consulted.
   *
   * <p>Fails closed, unlike {@link #overridesOrNull()}: no injector yet (Guice module
   * configuration), no database, or the studio lookup itself throwing all resolve to NOT ACTIVE. An
   * app whose state cannot be proven is treated as absent, never as present — the opposite choice
   * would let a transient failure silently re-enable a deactivated assistant.
   */
  public boolean isAppActive() {
    try {
      return Beans.get(AppBaseService.class).isApp(APP_CODE);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * The settings reader, or null when there is no injector to ask.
   *
   * <p>Null is the normal answer in a unit test — this class is deliberately constructible with
   * {@code new}, and {@code AiAccountantModule} does exactly that while configuring Guice, long
   * before a database exists. Every caller below therefore treats null as "no override" and reads
   * the environment, which is the behaviour this class had before the Settings screen existed.
   */
  protected AiAccountantSettingOverrideService overridesOrNull() {
    try {
      return Beans.get(AiAccountantSettingOverrideService.class);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Writing the agent definition is an outward-facing change — opt-in only. */
  public boolean isAgentPublishAllowed() {
    return "true".equalsIgnoreCase(cfg("aiaccountant.azure.allow.agent.publish", "false").trim());
  }

  /**
   * Tools that may write with no human present (the OCR intake pipeline).
   *
   * <p>Empty by default, and there is deliberately no wildcard: "allow everything" should not be
   * something anyone can type by accident.
   */
  public Set<String> unattendedWriteTools() {
    return toolSet(cfg("aiaccountant.write.unattended.tools", ""));
  }

  /**
   * Tools that may be used from the chat, where a person is present. Empty by default.
   *
   * <p>Reads {@code aiaccountant.write.chat.tools}, falling back to the older {@code
   * aiaccountant.write.confirmed.tools} so an existing deployment keeps working across the upgrade
   * that removed the confirmation queue. The name changed because "confirmed" described a mechanism
   * that no longer exists: a chat write is executed when the conversation authorises it, not parked
   * for a lexical yes.
   */
  public Set<String> chatWriteTools() {
    Set<String> tools = toolSet(cfg("aiaccountant.write.chat.tools", ""));
    return tools.isEmpty() ? toolSet(cfg("aiaccountant.write.confirmed.tools", "")) : tools;
  }

  protected static Set<String> toolSet(String csv) {
    Set<String> out = new LinkedHashSet<>();
    if (csv == null) {
      return out;
    }
    for (String part : csv.split(",")) {
      String name = part.trim();
      if (!name.isEmpty()) {
        out.add(name);
      }
    }
    return out;
  }

  public int maxToolIterations() {
    AiAccountantSettingOverrideService overrides = overridesOrNull();
    if (overrides != null) {
      Integer override = overrides.maxToolIterations();
      if (override != null) {
        return override;
      }
    }
    return cfgInt("aiaccountant.max.tool.iterations", 5);
  }

  /**
   * The deployed model's context window, in tokens.
   *
   * <p>This is the boundary past which the provider refuses the request outright and the user loses
   * the turn — the only bound production v1 resets the chain for automatically. There is no
   * cost-only threshold beside it: resetting a chain that is merely getting expensive to re-read
   * drops the model's retained context — and the continuity handoff handed back in its place — for
   * a reason the user never sees and did not ask for. Only the hard limit below justifies that; see
   * {@code AiChainResetReason#CONTEXT_LIMIT} and {@code #GROWTH_PROJECTION}, which is the
   * projection of this same bound rather than a second, independent one.
   *
   * <p>Configured rather than inferred: the Responses API reports usage but never the window, and
   * deriving it from a model name would break silently the day the agent is pointed at another
   * deployment. The default (400,000) is this deployment's confirmed real ceiling for its
   * gpt-5-mini agent — under-stating it costs an early reset, over-stating it costs a refused
   * request.
   */
  public int contextWindowTokens() {
    return cfgInt("aiaccountant.context.window.tokens", 400_000);
  }

  /**
   * How much of the window is held back for everything that is not retained history.
   *
   * <p>The window has to hold the chain <em>and</em> this turn's instructions, its tool results and
   * the model's own answer including reasoning tokens. Only the chain is what the bound is measured
   * against, so the rest is reserved in advance rather than discovered when a request is refused.
   */
  public int contextReserveTokens() {
    return cfgInt("aiaccountant.context.reserve.tokens", 32_000);
  }

  /**
   * The self-imposed conversation-memory ceiling chosen on the Settings screen, in tokens.
   *
   * <p>Read from {@link AiAccountantSettingOverrideService#conversationMemoryPreset()}, which
   * already resolves a missing row, an unset field or an unreadable row to {@link
   * AiConversationMemoryPreset#MEDIUM} — this needs no fallback of its own. {@code
   * AiContextBudget#usableChainTokens()} clamps the result to the model's real headroom, so this
   * preset can raise or lower how much the assistant remembers but can never itself cause a refused
   * request.
   */
  public int conversationMemoryTokens() {
    AiAccountantSettingOverrideService overrides = overridesOrNull();
    AiConversationMemoryPreset preset =
        overrides == null
            ? AiConversationMemoryPreset.MEDIUM
            : overrides.conversationMemoryPreset();
    return preset.tokens();
  }

  /**
   * Where the metric lines are also written, or empty to keep them on stdout only.
   *
   * <p>Container stdout lives and dies with the container, so every rebuild discards the whole
   * measured history. A host-mounted path survives that.
   */
  public String metricsFile() {
    return cfg("aiaccountant.metrics.file", "").trim();
  }

  /**
   * Testing aid: a plain-text file every call to {@link #toolDebugTools()} is appended to, with the
   * turn's route, authorised targets and a redacted summary of the call and its outcome. Empty (the
   * default) disables it.
   */
  public String toolDebugFile() {
    return cfg("aiaccountant.debug.file", "").trim();
  }

  /** Size at which the debug file rolls to {@code <file>.1}; at most two files ever exist. */
  public int toolDebugMaxBytes() {
    return Math.max(64 * 1024, cfgInt("aiaccountant.debug.max.bytes", 2 * 1024 * 1024));
  }

  /** The tools whose calls {@link #toolDebugFile()} records. */
  public java.util.Set<String> toolDebugTools() {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (String s :
        cfg("aiaccountant.debug.tools", "manage_supplier,find_supplier,edit_supplier_invoice")
            .split(",")) {
      if (!s.trim().isEmpty()) {
        out.add(s.trim());
      }
    }
    return out;
  }

  /** Size at which the metrics file rolls. Small: one line is ~400 bytes. */
  public String metricsMaxFileSize() {
    return cfg("aiaccountant.metrics.max.file.size", "5MB").trim();
  }

  /** How many rolled files to keep, so the mount cannot grow without bound. */
  public int metricsMaxFiles() {
    return cfgInt("aiaccountant.metrics.max.files", 5);
  }

  public int maxToolResultBytes() {
    return cfgInt("aiaccountant.tool.max.result.bytes", 32_768);
  }

  public int maxMessageChars() {
    return cfgInt("aiaccountant.max.message.chars", 8_000);
  }

  /**
   * How many records one instruction may authorise when the user named a set rather than a record.
   *
   * <h2>Why a bound at all</h2>
   *
   * «Осчетоводи всичко от инбокса» is a real instruction and its set is whatever the inbox holds —
   * which could be four documents or four hundred. The person saying it has a number in mind, and
   * it is not four hundred. Resolving such a set into an authorisation without a ceiling turns one
   * sentence into unbounded write access for as long as the job stays open.
   *
   * <p>Over the limit the set is not truncated, it is refused: taking the first twenty of ninety
   * would authorise a job the user never described and leave seventy silently outside it, which is
   * the failure mode a bulk instruction is most likely to hide. They are told the number and asked
   * to narrow it.
   */
  public int maxAuthorisedTargets() {
    return cfgInt("aiaccountant.workflow.max.authorised.targets", 25);
  }

  /**
   * Whether the Router Agent is called at all. Off by default.
   *
   * <p>Separate from {@code aiaccountant.enabled} because it is a separate agent with its own
   * endpoint and its own failure modes. Turning the assistant on must not silently start sending
   * every message to a second service.
   */
  public boolean isRouterEnabled() {
    AiAccountantSettingOverrideService overrides = overridesOrNull();
    if (overrides != null) {
      AiAccountantOverride decision = overrides.routerEnabled();
      if (decision.isDecided()) {
        return decision.isOn();
      }
    }
    String v = firstNonBlank(env(ENV_ROUTER_ENABLED), cfg("aiaccountant.router.enabled", "false"));
    return "true".equalsIgnoreCase(v.trim());
  }

  /**
   * What the router's answer is allowed to do: {@code shadow} or {@code active}.
   *
   * <p>Written as a mode rather than a boolean because the difference between the two — the router
   * actually routing — is a change in what the answer <em>means</em>, and that deserves a
   * deliberate word in a config file rather than the flipping of a flag named "enabled".
   */
  protected String routerMode() {
    AiAccountantSettingOverrideService overrides = overridesOrNull();
    if (overrides != null) {
      String mode = overrides.routerMode();
      if (mode != null) {
        return mode;
      }
    }
    return firstNonBlank(env(ENV_ROUTER_MODE), cfg("aiaccountant.router.mode", ROUTER_MODE_SHADOW));
  }

  /**
   * True while the router is observed only. Any unrecognised mode reads as shadow.
   *
   * <p>Fail safe, not fail open: only the exact word {@code active} promotes the router, so a typo,
   * a blank and a value from a future version all leave the adapter in charge.
   */
  public boolean isRouterShadowMode() {
    return !isRouterActiveMode();
  }

  /** True when a valid router answer decides the route for the turn. */
  public boolean isRouterActiveMode() {
    String mode = routerMode() == null ? "" : routerMode().trim();
    return ROUTER_MODE_ACTIVE.equalsIgnoreCase(mode);
  }

  protected String routerAgentEndpoint() {
    return firstNonBlank(
        env(ENV_ROUTER_AGENT_ENDPOINT), cfg("aiaccountant.router.azure.agent.endpoint", ""));
  }

  /** Full router URL: its own agent endpoint with the mandatory api-version parameter. */
  public String routerChatUrl() {
    String base = routerAgentEndpoint();
    if (base == null || base.trim().isEmpty()) {
      return "";
    }
    base = base.trim();
    String sep = base.indexOf('?') >= 0 ? "&" : "?";
    return base + sep + "api-version=" + apiVersion();
  }

  public int routerConnectTimeout() {
    return cfgInt("aiaccountant.router.azure.connect.timeout.seconds", 10);
  }

  /**
   * How long the router gets. Far shorter than the chat's 120s.
   *
   * <p>A routing answer is only useful before the turn is routed. In shadow mode a late answer
   * costs a sample; in any later mode it would cost the user's patience for a decision the fallback
   * can make instantly. The deadline is set by what the answer is worth, not by what the service
   * can manage.
   */
  public int routerRequestTimeout() {
    return cfgInt("aiaccountant.router.azure.request.timeout.seconds", 15);
  }

  public static final int DEFAULT_ROUTER_REQUEST_TOKEN_BUDGET = 100_000;

  public static final int DEFAULT_ROUTER_OUTPUT_RESERVE_TOKENS = 8_000;

  /**
   * The whole token envelope one Router request may use: its input — contract, context, the user's
   * message and the stored history — plus {@link #routerOutputReserveTokens()}. See {@code
   * AiRouterContract#firstKept}.
   *
   * <h2>Why an estimate</h2>
   *
   * {@code AiContextBudget} measures the Azure chain from the provider's own reported {@code usage}
   * figures, because that chain always has a previous request to project from. The router is
   * stateless: every call is a fresh, one-shot request with no prior usage to read, so its size has
   * to be estimated before it is sent — by {@code AiTokenEstimate}, which rounds against itself.
   *
   * <p>The default is deliberately generous and well under any current model window: the router
   * reads the complete stored conversation in the ordinary case, and this exists for the genuinely
   * long-lived one. When it is reached, the oldest whole exchanges are left out and the payload
   * says so explicitly — never a silent truncation. Replaces the former character budget ({@code
   * aiaccountant.router.history.char.budget}), which counted only the history text and read the
   * same length of Bulgarian and English as the same cost.
   */
  public int routerRequestTokenBudget() {
    return cfgInt("aiaccountant.router.request.token.budget", DEFAULT_ROUTER_REQUEST_TOKEN_BUDGET);
  }

  /**
   * Held back from {@link #routerRequestTokenBudget()} for the router's own answer, including any
   * reasoning tokens. The JSON answer is tiny; a reasoning model's thinking is not.
   */
  public int routerOutputReserveTokens() {
    return cfgInt(
        "aiaccountant.router.output.reserve.tokens", DEFAULT_ROUTER_OUTPUT_RESERVE_TOKENS);
  }

  /**
   * Whether the Stats page queries Azure Monitor at all. Off by default.
   *
   * <p>Off rather than on because it needs a role nobody has granted yet: the app registration must
   * hold {@code Monitoring Reader} on the Foundry resource. Enabled without it, every poll would
   * answer 403 — so this stays a deliberate switch flipped once the role is in place, not something
   * that starts failing quietly on upgrade.
   */
  public boolean isAzureMonitorEnabled() {
    return "true".equalsIgnoreCase(cfg("aiaccountant.azure.monitor.enabled", "false").trim());
  }

  /** Full ARM resource id of the Foundry (Cognitive Services) resource to read metrics from. */
  public String azureMonitorResourceId() {
    return cfg("aiaccountant.azure.monitor.resource.id", "").trim();
  }

  /**
   * Azure Monitor's own API version, which is not the Foundry data plane's.
   *
   * <p>Its own key for that reason: {@link #apiVersion()} is the agent endpoint's and the two move
   * independently. Pinning it means a change to Azure's default cannot silently alter what the
   * poller reads.
   */
  public String azureMonitorApiVersion() {
    return cfg("aiaccountant.azure.monitor.apiversion", "2018-01-01").trim();
  }

  /**
   * Which metrics to collect.
   *
   * <p>The default list is the set confirmed to exist on this resource. It is still validated
   * against the resource's own {@code metricDefinitions} before use — a name that is merely
   * plausible produces an empty chart nobody can explain, so the poller drops what the resource
   * does not actually offer and says which.
   */
  public Set<String> azureMonitorMetrics() {
    return toolSet(
        cfg(
            "aiaccountant.azure.monitor.metrics",
            "ProcessedPromptTokens,GeneratedTokens,TokenTransaction,AzureOpenAIRequests"));
  }

  /**
   * How far back each hourly poll re-reads. Longer than one hour so late revisions are picked up.
   */
  public int azureMonitorHourlyLookbackHours() {
    return cfgInt("aiaccountant.azure.monitor.hourly.lookback.hours", 48);
  }

  /** How far back the daily pass re-reads; also what the 7d and 30d views are drawn from. */
  public int azureMonitorDailyLookbackDays() {
    return cfgInt("aiaccountant.azure.monitor.daily.lookback.days", 90);
  }

  /** How long a live Azure answer is reused before the resource is asked again. */
  public int azureMonitorCacheSeconds() {
    return cfgInt("aiaccountant.azure.monitor.cache.seconds", 120);
  }

  /**
   * How long the two snapshot series are kept.
   *
   * <p>Separate numbers because they answer different questions: the hourly series exists for the
   * recent view and stops being interesting almost immediately, while the daily one is the only
   * record of a month ago. Keeping hourly rows for a year would be hundreds of thousands of rows
   * nothing ever selects.
   */
  public int azureMonitorHourlyRetentionDays() {
    return cfgInt("aiaccountant.azure.monitor.retention.hourly.days", 7);
  }

  public int azureMonitorDailyRetentionDays() {
    return cfgInt("aiaccountant.azure.monitor.retention.daily.days", 400);
  }

  /** How long local per-request telemetry is kept before the retention job removes it. */
  public int usageStatRetentionDays() {
    return cfgInt("aiaccountant.usagestat.retention.days", 180);
  }

  public int connectTimeout() {
    return cfgInt("aiaccountant.azure.connect.timeout.seconds", 15);
  }

  public int requestTimeout() {
    return cfgInt("aiaccountant.azure.request.timeout.seconds", 120);
  }

  public int maxRetries() {
    return cfgInt("aiaccountant.azure.max.retries", 3);
  }

  /**
   * Deadline for an Entra token request, in seconds.
   *
   * <p>Its own number rather than {@link #requestTimeout()}. A token endpoint answers in well under
   * a second or it is unavailable, and lending it the chat's two minutes would let a broken
   * identity provider spend the whole turn budget before the first model request is attempted.
   */
  public int tokenRequestTimeout() {
    return cfgInt("aiaccountant.azure.token.timeout.seconds", 20);
  }

  /**
   * How long one whole chat turn may take, in seconds; 0 removes the bound.
   *
   * <p>Per-request timeouts bound one call and say nothing about a turn that makes five model
   * requests with retries between them — every number reasonable, the person waiting four minutes.
   * This is the only bound that describes what the user experiences, and it is what {@code
   * AiTurnDeadline} enforces.
   *
   * <p>180 is deliberately well above the observed turn (a few seconds to tens of seconds) and well
   * below the point where anyone is still watching. It is a backstop, not a scheduler.
   */
  public int turnDeadlineSeconds() {
    return cfgInt("aiaccountant.turn.deadline.seconds", 180);
  }

  /**
   * Extra attempts a READ tool gets after a transient failure. Clamped to 0..2 by the policy.
   *
   * <p>Reads only. There is deliberately no equivalent key that makes writes retry on an unknown
   * outcome — see {@link #writeRetryAttempts()}.
   */
  public int readRetryAttempts() {
    return cfgInt("aiaccountant.tool.read.retry.attempts", 1);
  }

  /**
   * Extra attempts a WRITE tool gets <em>after Java has proven the write did not happen</em>.
   * Clamped to 0..1 by the policy.
   *
   * <p>The proof is a precondition in code, not something this setting can waive: raising the
   * number cannot make an uncertain write retryable, and setting it to 0 turns a proven-unwritten
   * timeout into an ordinary reported failure. Those are the only two behaviours available.
   */
  public int writeRetryAttempts() {
    return cfgInt("aiaccountant.tool.write.retry.attempts", 1);
  }

  /** Full chat URL: the agent endpoint with the mandatory api-version query parameter. */
  public String chatUrl() {
    String base = agentEndpoint();
    if (base == null || base.trim().isEmpty()) {
      return "";
    }
    base = base.trim();
    String sep = base.indexOf('?') >= 0 ? "&" : "?";
    return base + sep + "api-version=" + apiVersion();
  }

  /** {@code {project}/agents/{name}?api-version=...} — read/publish the agent definition. */
  public String agentDefinitionUrl() {
    String base = projectEndpoint();
    if (base == null || base.trim().isEmpty()) {
      return "";
    }
    return stripTrailingSlash(base.trim())
        + "/agents/"
        + agentName()
        + "?api-version="
        + apiVersion();
  }

  /**
   * {@code {project}/agents/{name}/versions?api-version=...} — adds a new version to the existing
   * agent.
   *
   * <p>NB: {@code POST {project}/agents} only <em>creates</em>; against an agent that already
   * exists it answers HTTP 409 {@code conflict}. Verified against the live service.
   */
  public String agentCreateUrl() {
    String base = projectEndpoint();
    if (base == null || base.trim().isEmpty()) {
      return "";
    }
    return stripTrailingSlash(base.trim())
        + "/agents/"
        + agentName()
        + "/versions?api-version="
        + apiVersion();
  }

  /** True when the minimum needed to talk to the agent is present. Mock needs no endpoint. */
  public boolean isChatConfigured() {
    return isMockMode() || !chatUrl().isEmpty();
  }

  public String getAgentName() {
    return agentName();
  }

  public String getAuthMode() {
    return authMode();
  }

  public String getTenantId() {
    return tenantId();
  }

  public String getClientId() {
    return clientId();
  }

  public String getClientSecret() {
    return clientSecret();
  }

  protected static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  protected static String env(String name) {
    String v = System.getenv(name);
    return v == null ? "" : v.trim();
  }

  protected static String firstNonBlank(String a, String b) {
    return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
  }

  protected String cfg(String key, String def) {
    String v = AppSettings.get().get(key, def);
    return v == null ? def : v;
  }

  protected int cfgInt(String key, int def) {
    try {
      return Integer.parseInt(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
