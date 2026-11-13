package com.axelor.apps.aiaccountant.listener;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.metrics.AiMetricsFileLog;
import com.axelor.apps.aiaccountant.metrics.AiPromptBudget;
import com.axelor.apps.aiaccountant.metrics.AiUsageStatRecorder;
import com.axelor.apps.aiaccountant.prompt.AiOperatingProcedure;
import com.axelor.apps.aiaccountant.prompt.AiPlaybookLibrary;
import com.axelor.apps.aiaccountant.prompt.AiPromptResource;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.resilience.AiContextBudget;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolPublisher;
import com.axelor.apps.aiaccountant.tool.AiToolRegistry;
import com.axelor.event.Observes;
import com.axelor.events.ShutdownEvent;
import com.axelor.events.StartupEvent;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot-time sanity check. Auto-fired by Axelor only for explicitly bound observers (see {@code
 * AiAccountantModule}).
 *
 * <p>Validates that the tools declared on the Foundry agent match the Java registry and logs a
 * warning when they diverge. It deliberately does <b>not</b> write: the agent is a CTO-owned
 * resource, and a silent auto-patch on every boot would be an unannounced outward-facing change. A
 * mismatch means the model can request tools we cannot run, or cannot request tools we do have —
 * both are better shouted about in the log than quietly fixed.
 *
 * <p>Never blocks boot: any failure here is logged and swallowed.
 */
public class AiAccountantServerStartListener {

  private static final Logger LOG = LoggerFactory.getLogger(AiAccountantServerStartListener.class);

  public void onStartup(@Observes StartupEvent event) {
    // Owns one background thread for the whole deployment, stopped again in onShutdown. Started
    // unconditionally — not behind config.isEnabled() — because the App can be installed or
    // reactivated in Application Configuration at any time after this listener has already run,
    // with no server restart and no second StartupEvent to react to it. An idle recorder polling
    // an empty queue costs nothing; {@link AiUsageStatRecorder#record} itself drops everything
    // while the app is inactive, since nothing upstream ever builds a snapshot to hand it.
    Beans.get(AiUsageStatRecorder.class).start();

    // Also before the enabled check, for the same reason: the App can be switched on after boot,
    // and a tool-name clash found then would surface as a refused chat turn instead of here.
    AiToolRegistry registry = validatedToolRegistry();

    AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
    if (!config.isEnabled()) {
      LOG.info("AI Accountant: disabled (application not active, or aiaccountant.enabled=false).");
      return;
    }

    // Before anything below logs a metric line: ai.budget is emitted from this same method, and a
    // file installed after it would miss the one line that says what every request costs.
    AiMetricsFileLog.install();
    if (!config.isChatConfigured()) {
      LOG.warn(
          "AI Accountant: enabled but no agent endpoint configured ({}); the assistant will be"
              + " unavailable.",
          AiAccountantConfig.ENV_AGENT_ENDPOINT);
      return;
    }

    if (registry == null) {
      // Already logged by validatedToolRegistry, with every clash named.
      return;
    }
    LOG.info("AI Accountant: enabled with {} tool(s).", registry.all().size());
    warnAboutPromisedToolsThatDoNotExist(registry);

    // Read before the budget line so the budget can measure the live instructions instead of
    // reporting them as unknown. The diff already fetches the whole definition; no second request.
    String agentInstructions = null;
    try {
      AiToolPublisher.ToolDiff diff = Beans.get(AiToolPublisher.class).diff();
      agentInstructions = diff.getAgentInstructions();
      if (diff.isInSync()) {
        LOG.info("AI Accountant: agent '{}' tool declarations are in sync.", config.getAgentName());
      } else {
        LOG.warn(
            "AI Accountant: agent '{}' tool declarations are OUT OF SYNC ({}). Tools must be"
                + " declared on the agent definition — the Responses API refuses request-local"
                + " tools — and an out-of-date declaration means the model cannot see arguments"
                + " this code already offers. Publish them to fix this.",
            config.getAgentName(),
            diff);
      }
    } catch (Exception e) {
      // Project endpoint may be unset, or RBAC may not permit reading the agent. Not fatal.
      LOG.warn("AI Accountant: could not verify agent tool declarations: {}", e.getMessage());
    }

    // What every request pays before the user has typed anything. Logged at boot so a jump in the
    // fixed overhead is visible the moment a tool is added, instead of being discovered on a bill.
    try {
      // The procedure is the once-per-conversation cost; the playbooks are the per-turn one, and
      // only ever one of them at a time, so the largest is what a turn actually pays.
      AiContextBudget contextBudget = Beans.get(AiContextBudget.class);
      Beans.get(AiPromptBudget.class)
          .logAtStartup(
              AiOperatingProcedure.text(),
              agentInstructions,
              contextBudget.contextWindowTokens(),
              contextBudget.reservedTokens());
      LOG.info(
          "AI Accountant: procedure {} bytes; {} domain guide(s), {} bytes; {} playbook(s), {} bytes"
              + " in total. A task turn is sent one guide and one playbook, plus the bulk"
              + " supplement when the instruction covers a set.",
          AiOperatingProcedure.bytes(),
          AiPlaybookLibrary.domainCount(),
          AiPlaybookLibrary.domainBytes(),
          AiPlaybookLibrary.count(),
          AiPlaybookLibrary.bytes());
      // Which revision of the instructions this deployment is running. The per-turn line records
      // the same for the documents each turn was given; this is the whole shelf, once, at boot.
      LOG.info(
          "AI Accountant: instruction documents — procedure@{}, {}",
          AiOperatingProcedure.version(),
          AiPlaybookLibrary.versions());
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not measure the prompt budget: {}", e.getMessage());
    }
    logCapabilityBreakdown(registry);
  }

  /**
   * Discovers and validates the complete tool registry, or returns null after logging why not.
   *
   * <p>Every registry access fails the same way until the clash is fixed — discovery is retried and
   * never caches a partial result — so a conflicting tool is never silently chosen or dropped. Boot
   * itself still continues, as for every other check in this listener: the rest of the ERP does not
   * depend on the assistant.
   */
  protected AiToolRegistry validatedToolRegistry() {
    try {
      AiToolRegistry registry = Beans.get(AiToolRegistry.class);
      registry.all();
      return registry;
    } catch (RuntimeException e) {
      LOG.error(
          "AI Accountant: tool discovery failed; the assistant reports itself unavailable and"
              + " refuses chat turns and tool calls (before any model request) until this is fixed."
              + " {}",
          e.getMessage(),
          e);
      return null;
    }
  }

  /**
   * Stops the usage recorder so a redeploy does not leave the previous deployment's thread running.
   *
   * <p>Unconditional, unlike startup: whether the assistant is currently enabled says nothing about
   * whether it was enabled when this instance booted, and {@code shutdown()} on a recorder that was
   * never started does nothing.
   */
  public void onShutdown(@Observes ShutdownEvent event) {
    try {
      Beans.get(AiUsageStatRecorder.class).shutdown();
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not stop the usage recorder: {}", e.getMessage());
    }
  }

  /**
   * What each capability costs, and what the widest task would cost if tools could be scoped to it.
   *
   * <p>The saving is the whole question behind per-task tool exposure, and it is worth one log line
   * rather than an argument: the persisted agent declares one fixed tool list, so if the widest
   * task needs nearly all of them anyway, there is nothing to win and the idea can be dropped on
   * evidence. Recomputed at every boot, so a module that changes the answer says so itself.
   */
  protected void logCapabilityBreakdown(AiToolRegistry registry) {
    try {
      StringBuilder groups = new StringBuilder();
      for (Map.Entry<AiCapability, List<AiTool>> e : registry.byCapability().entrySet()) {
        int published = 0;
        int bytes = 0;
        for (AiTool t : e.getValue()) {
          if (t.published()) {
            published++;
            bytes += AiPromptResource.bytes(t.description()) + specBytes(t);
          }
        }
        groups
            .append(groups.length() == 0 ? "" : ", ")
            .append(e.getKey())
            .append('=')
            .append(published)
            .append('/')
            .append(e.getValue().size())
            .append(" (")
            .append(bytes)
            .append("B)");
      }
      int allPublished = registry.published().size();
      AiTask widest = AiTask.GENERAL;
      int widestCount = 0;
      for (AiTask task : AiTask.values()) {
        int count = registry.publishedFor(task.capabilities()).size();
        if (count > widestCount) {
          widestCount = count;
          widest = task;
        }
      }
      LOG.info(
          "AI Accountant: capabilities {}; {} published in total, and the widest task ({}) would"
              + " still reach {} of them — per-task tool exposure would save {}.",
          groups,
          allPublished,
          widest,
          widestCount,
          allPublished - widestCount == 0 ? "nothing" : (allPublished - widestCount) + " tool(s)");
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not measure the capability breakdown: {}", e.getMessage());
    }
  }

  private static int specBytes(AiTool tool) {
    try {
      return AiPromptResource.bytes(String.valueOf(tool.parametersSchema()));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /**
   * Does every tool the prompts name actually reach the model?
   *
   * <p>The Foundry diff above answers the opposite question — do the declared tools match the
   * registry — and cannot catch this one. A prompt may name a tool that is registered but
   * {@linkplain AiTool#published() unpublished}, or one that has never existed at all; in both
   * cases the agent's declarations are perfectly in sync and the instruction is still a promise the
   * model cannot keep. It calls the name, gets {@code unknown_tool}, and burns an iteration, every
   * time that route is taken.
   *
   * <p>Both known instances were found by reading: {@code lookup_bg_city} in the intake prompt, and
   * {@code apply_exact_product_code} in the repair playbook — registered, deliberately unpublished,
   * and offered to the model anyway. Reading is not a control; this is.
   *
   * <p>Warns rather than fails: an unreachable tool name costs an iteration, and refusing to boot
   * an ERP over a sentence in a prompt would be the more expensive error.
   */
  protected void warnAboutPromisedToolsThatDoNotExist(AiToolRegistry registry) {
    try {
      Set<String> published = new TreeSet<>();
      for (AiTool tool : registry.published()) {
        published.add(tool.name());
      }
      List<String> unreachable = new ArrayList<>();
      for (String name : AiPlaybookLibrary.toolNamesMentioned()) {
        if (!published.contains(name)) {
          unreachable.add(name);
        }
      }
      if (!unreachable.isEmpty()) {
        LOG.warn(
            "AI Accountant: the prompts name {} tool(s) the model cannot call: {}. Each one costs a"
                + " wasted iteration on every turn that reads it — publish the tool or remove the"
                + " sentence.",
            unreachable.size(),
            String.join(", ", unreachable));
      }
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not cross-check prompt tool names: {}", e.getMessage());
    }
  }
}
