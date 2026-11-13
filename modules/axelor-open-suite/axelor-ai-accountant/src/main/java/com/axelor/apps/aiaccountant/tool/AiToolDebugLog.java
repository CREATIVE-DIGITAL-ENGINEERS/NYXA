package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.context.AiRequestContext;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Testing aid: appends a redacted record of every call of the configured tools (by default the
 * supplier tools) to a plain-text file, enough to see WHY a supplier turn was refused or what it
 * changed — the route, the job's authorised targets, the tool's own target, which arguments were
 * given, and the outcome.
 *
 * <p>What it never writes: the chat text (only its length), argument values other than identifiers
 * (operation, ids, ЕИК), bank details, addresses, phones or e-mails, and results beyond a short
 * redacted outcome. The file is capped: it rolls once to {@code <file>.1} at {@code
 * aiaccountant.debug.max.bytes}, so two files at most exist.
 *
 * <p>Off unless {@code aiaccountant.debug.file} is set. Never throws.
 */
public final class AiToolDebugLog {

  private static final Logger LOG = LoggerFactory.getLogger(AiToolDebugLog.class);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_OUTCOME = 300;
  private static final int MAX_REPLY = 120;

  /** Argument values that are identifiers, not data about the supplier. */
  private static final Set<String> SAFE_ARGS =
      new HashSet<>(
          Arrays.asList("operation", "supplierId", "eik", "uploadId", "invoiceId", "id", "mode"));

  /** Result fields that are flags or identifiers. */
  private static final Set<String> SAFE_RESULT =
      new HashSet<>(
          Arrays.asList(
              "created", "supplierId", "verified", "existingSupplierId", "success", "count"));

  private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
  private static final Pattern PHONE =
      Pattern.compile("(?:\\+|\\b00)\\d[\\d\\s\\-()]{7,}\\d|\\b0\\d{9}\\b");

  private AiToolDebugLog() {}

  /** Whether calls of this tool are recorded. Never throws. */
  static boolean enabled(String name) {
    try {
      AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
      return !config.toolDebugFile().isEmpty() && config.toolDebugTools().contains(name);
    } catch (RuntimeException | LinkageError e) {
      return false;
    }
  }

  static void record(
      String name, String argumentsJson, String targetKey, String result, AiRequestContext ctx) {
    try {
      AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
      boolean error = result != null && result.startsWith("{\"error\":");
      StringBuilder b = new StringBuilder();
      b.append("==== ")
          .append(LocalDateTime.now().format(TS))
          .append("  ")
          .append(name)
          .append("  ")
          .append(error ? "!!! ERROR" : "OK")
          .append(" ====\n");
      if (ctx != null) {
        b.append("message      : ").append(describeMessage(ctx.getMessage())).append('\n');
        b.append("user/company : ")
            .append(ctx.getUserId())
            .append(" / ")
            .append(ctx.getCompanyId())
            .append("  conversation ")
            .append(ctx.getConversationId())
            .append('\n');
        PageContext page = ctx.getPage();
        b.append("page         : ")
            .append(page == null ? "-" : page.getModel() + "#" + page.getRecordId())
            .append('\n');
        b.append("route        : ").append(ctx.getRoute()).append('\n');
        appendJob(b, ctx.getWorkflow());
      } else {
        b.append("context      : none (not a chat turn)\n");
      }
      b.append("tool target  : ").append(targetKey).append('\n');
      b.append("arguments    : ").append(summariseArguments(argumentsJson)).append('\n');
      b.append("outcome      : ").append(summariseResult(result)).append("\n\n");
      append(config.toolDebugFile(), config.toolDebugMaxBytes(), b.toString());
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("AI Accountant: could not write the tool debug file: {}", e.getMessage());
    }
  }

  /**
   * The end of a turn that was about suppliers or used a recorded tool — the only place a turn that
   * asked for permission instead of calling the tool shows up.
   */
  public static void recordTurn(
      String message, AiRoute route, AiWorkflow workflow, List<String> toolsUsed, String reply) {
    try {
      AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
      String path = config.toolDebugFile();
      if (path.isEmpty()) {
        return;
      }
      boolean partner = route != null && route.domain() == AiDomain.PARTNER;
      boolean usedRecorded =
          toolsUsed != null && toolsUsed.stream().anyMatch(config.toolDebugTools()::contains);
      if (!partner && !usedRecorded) {
        return;
      }
      StringBuilder b = new StringBuilder();
      b.append("---- ")
          .append(LocalDateTime.now().format(TS))
          .append("  TURN END ----\n")
          .append("message      : ")
          .append(describeMessage(message))
          .append('\n')
          .append("route        : ")
          .append(route)
          .append('\n');
      appendJob(b, workflow);
      b.append("tools used   : ")
          .append(toolsUsed)
          .append('\n')
          .append("reply        : ")
          .append(describeReply(reply))
          .append("\n\n");
      append(path, config.toolDebugMaxBytes(), b.toString());
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("AI Accountant: could not write the tool debug file: {}", e.getMessage());
    }
  }

  private static void appendJob(StringBuilder b, AiWorkflow wf) {
    if (wf == null) {
      b.append("job          : NONE (no job was opened for this message)\n");
      return;
    }
    b.append("job          : task=")
        .append(wf.getTask())
        .append(" scope=")
        .append(wf.getScope())
        .append(" explicitness=")
        .append(wf.getExplicitness())
        .append(" active=")
        .append(wf.isActive())
        .append('\n');
    b.append("authorised   : ").append(wf.getAuthorisedTargets()).append('\n');
    if (wf.getTargetRefusalCode() != null) {
      b.append("set refused  : ")
          .append(wf.getTargetRefusalCode())
          .append(" — ")
          .append(truncate(redact(oneLine(wf.getTargetRefusalMessage())), MAX_OUTCOME))
          .append('\n');
    }
  }

  /** The size of the message, never its words. */
  static String describeMessage(String message) {
    return message == null ? "null" : message.length() + " chars (text not logged)";
  }

  static String describeReply(String reply) {
    if (reply == null) {
      return "null";
    }
    return reply.length() + " chars: " + truncate(redact(oneLine(reply)), MAX_REPLY);
  }

  /**
   * The identifier arguments with their values, every other argument by name only: what was given
   * is visible, what it was is not.
   */
  static String summariseArguments(String argumentsJson) {
    try {
      JsonNode args =
          argumentsJson == null || argumentsJson.trim().isEmpty()
              ? null
              : MAPPER.readTree(argumentsJson);
      if (args == null || !args.isObject()) {
        return "(none)";
      }
      List<String> shown = new ArrayList<>();
      List<String> named = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> it = args.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode v = e.getValue();
        if (v == null || v.isNull()) {
          continue;
        }
        if (SAFE_ARGS.contains(e.getKey()) && v.isValueNode()) {
          shown.add(e.getKey() + "=" + truncate(redact(v.asText()), 40));
        } else {
          named.add(e.getKey());
        }
      }
      return String.join(" ", shown) + (named.isEmpty() ? "" : "  | also given: " + named);
    } catch (Exception e) {
      return "(unreadable)";
    }
  }

  /** A refusal's own words (redacted, short), or the flags and ids of a success. */
  static String summariseResult(String result) {
    if (result == null) {
      return "null";
    }
    try {
      JsonNode node = MAPPER.readTree(result);
      if (node == null || !node.isObject()) {
        return "not an object (" + result.length() + " chars)";
      }
      List<String> parts = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      List<String> other = new ArrayList<>();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode v = e.getValue();
        String key = e.getKey();
        if (v == null || v.isNull()) {
          continue;
        }
        if ("error".equals(key) || "blocker".equals(key) || "code".equals(key)) {
          parts.add(
              key
                  + "="
                  + truncate(
                      redact(oneLine(v.isValueNode() ? v.asText() : v.toString())), MAX_OUTCOME));
        } else if (SAFE_RESULT.contains(key) && v.isValueNode()) {
          parts.add(key + "=" + v.asText());
        } else if (("verificationProblems".equals(key) || "missingInfo".equals(key))
            && v.isArray()) {
          parts.add(key + "=" + truncate(redact(oneLine(v.toString())), MAX_OUTCOME));
        } else {
          other.add(key);
        }
      }
      return String.join(" ", parts) + (other.isEmpty() ? "" : "  | other fields: " + other);
    } catch (Exception e) {
      return "unreadable (" + result.length() + " chars)";
    }
  }

  /** Masks IBANs, e-mail addresses and phone numbers. */
  static String redact(String s) {
    if (s == null) {
      return "";
    }
    String out = IBAN.matcher(s).replaceAll("[IBAN]");
    out = EMAIL.matcher(out).replaceAll("[email]");
    return PHONE.matcher(out).replaceAll("[phone]");
  }

  private static synchronized void append(String path, int maxBytes, String text) {
    try {
      File file = new File(path);
      File dir = file.getAbsoluteFile().getParentFile();
      if (dir != null && !dir.isDirectory()) {
        dir.mkdirs();
      }
      Path target = file.toPath();
      if (Files.exists(target) && Files.size(target) > maxBytes) {
        Files.move(
            target,
            target.resolveSibling(file.getName() + ".1"),
            StandardCopyOption.REPLACE_EXISTING);
      }
      Files.write(
          target,
          text.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      LOG.warn("AI Accountant: could not append to {}: {}", path, e.getMessage());
    }
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + " …";
  }

  private static String oneLine(String s) {
    return s == null ? "null" : s.replaceAll("\\s+", " ").trim();
  }
}
