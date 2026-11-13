package com.axelor.apps.aiaccountant.tool;

import com.axelor.apps.aiaccountant.dto.AiToolSpec;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single source of truth for which tools exist.
 *
 * <p>Implementations are discovered with {@link MetaScanner} — the same idiom {@code
 * AppServletModule} uses to find JAX-RS resources — and instantiated through Guice. That means a
 * feature module (Phase B's axelor-ai-ocr) contributes tools just by implementing {@link AiTool},
 * with no binding here and no dependency back on that module.
 *
 * <p>Discovery happens once and fails fast on blank or duplicate names, reporting all of them. It
 * is forced at startup by {@code AiAccountantServerStartListener}, so a clash is reported at boot
 * rather than on the first chat turn; a failed discovery is not cached and fails again on every
 * access.
 */
@Singleton
public class AiToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiToolRegistry.class);

  private volatile Map<String, AiTool> tools;

  public Map<String, AiTool> all() {
    Map<String, AiTool> local = tools;
    if (local == null) {
      synchronized (this) {
        if (tools == null) {
          tools = discover();
        }
        local = tools;
      }
    }
    return local;
  }

  public AiTool find(String name) {
    return name == null ? null : all().get(name);
  }

  /**
   * Why the registry cannot be used, or null when it can.
   *
   * <p>The non-throwing form of {@link #all()}, for callers that must answer "is the assistant
   * usable?" rather than run a tool — availability, turn entry. Discovery is attempted exactly as
   * {@link #all()} attempts it, so a failure is never cached and a fixed deployment recovers.
   */
  public String problem() {
    try {
      all();
      return null;
    } catch (RuntimeException e) {
      return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
  }

  /**
   * Declarations for publication to the agent definition, ordered by name for stable diffs.
   *
   * <p>Only the {@linkplain AiTool#published() published} ones. A tool left out is still registered
   * and still dispatchable by name from Java — it simply stops costing its description and schema
   * on every model request, which is the whole point of the flag.
   */
  public List<AiToolSpec> specs() {
    List<AiToolSpec> specs = new ArrayList<>();
    for (AiTool t : all().values()) {
      if (t.published()) {
        specs.add(new AiToolSpec(t.name(), t.description(), t.parametersSchema()));
      }
    }
    return specs;
  }

  /** The tools the model can actually see, in the same order as {@link #specs()}. */
  public List<AiTool> published() {
    List<AiTool> out = new ArrayList<>();
    for (AiTool t : all().values()) {
      if (t.published()) {
        out.add(t);
      }
    }
    return out;
  }

  /**
   * Registered tools grouped by {@linkplain AiTool#capability() capability}, name-ordered within
   * each group and containing an entry for every capability, empty ones included.
   *
   * <p>An empty group is information, not noise: it is how a module that declared a capability but
   * has not shipped its tools yet shows up, and how the startup line stays readable as groups
   * arrive.
   */
  public Map<AiCapability, List<AiTool>> byCapability() {
    Map<AiCapability, List<AiTool>> grouped = new EnumMap<>(AiCapability.class);
    for (AiCapability capability : AiCapability.values()) {
      grouped.put(capability, new ArrayList<>());
    }
    for (AiTool t : all().values()) {
      grouped.get(t.capability()).add(t);
    }
    return grouped;
  }

  /**
   * The published tools a task could reach, given the capabilities it declares.
   *
   * <p>Measurement, not enforcement. Nothing narrows what the model is offered today — the
   * persisted agent declares one fixed tool list — so this exists to answer, in bytes, what a
   * per-task exposure would actually save before anyone builds it.
   */
  public List<AiTool> publishedFor(Set<AiCapability> capabilities) {
    List<AiTool> out = new ArrayList<>();
    for (AiTool t : all().values()) {
      if (t.published() && capabilities.contains(t.capability())) {
        out.add(t);
      }
    }
    return out;
  }

  protected Map<String, AiTool> discover() {
    List<AiTool> tools = new ArrayList<>();
    for (Class<? extends AiTool> type : MetaScanner.findSubTypesOf(AiTool.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      tools.add(Beans.get(type));
    }
    return index(tools);
  }

  /**
   * Indexes discovered tools by name, refusing the whole set when any name is blank or claimed
   * twice.
   *
   * <p>Every problem is collected before failing, so one boot names all of them — each with the
   * contributing class and the module (jar or classes directory) it came from — instead of a
   * fix-one-rebuild-find-the-next loop. Nothing is chosen or dropped: a clash fails the registry.
   */
  static Map<String, AiTool> index(List<AiTool> tools) {
    Map<String, List<AiTool>> byName = new TreeMap<>();
    List<String> problems = new ArrayList<>();
    for (AiTool tool : tools) {
      String name = tool.name();
      if (name == null || name.trim().isEmpty()) {
        problems.add("blank name() in " + origin(tool));
        continue;
      }
      byName.computeIfAbsent(name, n -> new ArrayList<>()).add(tool);
    }
    for (Map.Entry<String, List<AiTool>> e : byName.entrySet()) {
      if (e.getValue().size() > 1) {
        List<String> origins = new ArrayList<>();
        for (AiTool t : e.getValue()) {
          origins.add(origin(t));
        }
        problems.add("duplicate name '" + e.getKey() + "' declared by " + origins);
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid AiTool registry (" + problems.size() + " problem(s)): " + problems);
    }
    // Stable, name-ordered map so published tool lists and diffs don't churn between boots.
    Map<String, AiTool> ordered = new LinkedHashMap<>();
    for (Map.Entry<String, List<AiTool>> e : byName.entrySet()) {
      ordered.put(e.getKey(), e.getValue().get(0));
    }
    LOG.info("AI Accountant: registered {} tool(s): {}", ordered.size(), ordered.keySet());
    return ordered;
  }

  /** {@code com.x.FooTool (axelor-ai-ocr-8.4.7.2.jar)} — the class and where it was loaded from. */
  static String origin(AiTool tool) {
    Class<?> type = tool.getClass();
    String where = "unknown location";
    try {
      CodeSource source = type.getProtectionDomain().getCodeSource();
      if (source != null && source.getLocation() != null) {
        String path = source.getLocation().getPath();
        // A jar names its module; a classes directory (IDE, Gradle test run) needs its full path,
        // because its leaf is just "main".
        where = path.endsWith(".jar") ? path.substring(path.lastIndexOf('/') + 1) : path;
      }
    } catch (RuntimeException e) {
      // SecurityException or an exotic class loader; the class name alone still identifies it.
    }
    return type.getName() + " (" + where + ")";
  }
}
