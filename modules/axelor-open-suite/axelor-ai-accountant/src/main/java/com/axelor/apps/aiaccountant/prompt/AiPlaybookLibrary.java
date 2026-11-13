package com.axelor.apps.aiaccountant.prompt;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The task-scoped prompt fragments — domain guides and playbooks — loaded once and handed out by
 * task.
 *
 * <h2>Two layers, not one</h2>
 *
 * A turn about a supplier invoice gets a <b>domain guide</b> and then <b>one playbook</b>. The
 * guide holds what is true of the document whichever task is running — how evidence is read, what
 * the reconciliation verdicts mean, that the document's own lines must survive a repair, what a
 * payable account needs before Axelor will post against it. The playbook holds the route to one
 * goal.
 *
 * <p>The split exists because tasks overlap in one direction: posting an invoice may require a
 * repair and a validation first, but routing resolves to the destination only. Rules that any task
 * might need on the way therefore cannot live in a sibling's playbook. Copying them into all five
 * would have been the other option, and five copies of an accounting rule drift.
 *
 * <h2>Shape of a playbook</h2>
 *
 * Five headings, always the same, because a model reads a familiar shape faster than prose and a
 * reviewer can see at a glance whether a section is missing:
 *
 * <ul>
 *   <li><b>Goal</b> — the state the record has to end in;
 *   <li><b>Allowed autonomous prerequisites</b> — what may be done without asking;
 *   <li><b>Procedure</b> — the route, and the tools it runs through;
 *   <li><b>Hard blockers</b> — the short list that genuinely stops and asks a human;
 *   <li><b>Done when</b> — the completion condition, so "finished" is not a judgement call.
 * </ul>
 *
 * <h2>Eagerly loaded</h2>
 *
 * Every playbook is read at class-initialisation, not on first use. A missing file then fails at
 * startup with its name, rather than on the one turn six weeks later that happens to resolve to
 * that task — by which time the failure looks like a model problem, not a packaging one.
 *
 * <h2>Adding a module</h2>
 *
 * A bank statement, a fixed asset or a payment run is a new {@link AiTask} constant and a new text
 * file beside these. Nothing in the global procedure changes, and nothing here needs a new branch:
 * the enum carries its own resource name.
 */
public final class AiPlaybookLibrary {

  static final String DIR = "com/axelor/apps/aiaccountant/prompt/playbooks/";

  static final String DOMAIN_DIR = "com/axelor/apps/aiaccountant/prompt/domain/";

  /** Suffix of the optional set-scoped playbook that sits beside a domain guide. */
  static final String BULK_SUFFIX = "-bulk.txt";

  private static final Map<AiTask, AiPromptDocument> PLAYBOOKS = load();

  private static final Map<AiTask, AiPromptDocument> DOMAIN_GUIDES = loadDomainGuides();

  private static final Map<AiTask, AiPromptDocument> BULK = loadBulk();

  private AiPlaybookLibrary() {}

  /** The playbook for this task, or null for {@link AiTask#GENERAL} and for a null task. */
  public static String playbook(AiTask task) {
    return text(playbookDocument(task));
  }

  /**
   * The shared rules for the kind of document this task is about, or null for {@link
   * AiTask#GENERAL} and for a null task.
   */
  public static String domainGuide(AiTask task) {
    return text(domainDocument(task));
  }

  public static AiPromptDocument playbookDocument(AiTask task) {
    return task == null ? null : PLAYBOOKS.get(task);
  }

  public static AiPromptDocument domainDocument(AiTask task) {
    return task == null ? null : DOMAIN_GUIDES.get(task);
  }

  /**
   * The set-scoped supplement for this task's document type, or null when the domain ships none.
   *
   * <h2>Why bulk is a supplement and not a task</h2>
   *
   * «Осчетоводи тези четири» is a posting job that happens to cover four records. It is not a
   * different job, so it does not get a different playbook — it gets the posting playbook plus the
   * rules for running one job across a set without losing a record in the middle. Making bulk a
   * task instead would mean six more files, each restating one playbook with a paragraph about
   * batching bolted on, and five of them would drift.
   *
   * <p>Optional by construction: a domain that ships no bulk file simply does not get one, and the
   * turn falls back to the composition that existed before this document did. That is why it is
   * loaded leniently while everything else here fails at startup.
   */
  public static AiPromptDocument bulkDocument(AiTask task) {
    return task == null ? null : BULK.get(task);
  }

  /** Total bytes of all playbooks, for the startup budget line. */
  public static int bytes() {
    int total = 0;
    for (AiPromptDocument doc : PLAYBOOKS.values()) {
      total += doc.bytes();
    }
    return total;
  }

  /**
   * Every distinct document and its version, for the startup line: {@code supplier-invoice@2,
   * supplier-invoice-post@1, …}.
   *
   * <p>So that a deployment states which revision of its instructions it is running. Without it the
   * only record of what the assistant was told on a given day is the WAR that happened to be
   * deployed, and "it started doing X last week" cannot be matched to the edit that caused it.
   */
  public static String versions() {
    Map<String, Integer> byId = new TreeMap<>();
    for (AiPromptDocument doc : distinctDomainGuideDocuments().values()) {
      byId.put(doc.id(), doc.version());
    }
    for (AiPromptDocument doc : PLAYBOOKS.values()) {
      byId.put(doc.id(), doc.version());
    }
    for (AiPromptDocument doc : BULK.values()) {
      byId.put(doc.id(), doc.version());
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> e : byId.entrySet()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(e.getKey()).append('@').append(e.getValue());
    }
    return sb.toString();
  }

  /**
   * Total bytes of the <em>distinct</em> domain guides — several tasks share one file, and counting
   * it once per task would report a per-turn cost nobody pays.
   */
  public static int domainBytes() {
    int total = 0;
    for (AiPromptDocument doc : distinctDomainGuideDocuments().values()) {
      total += doc.bytes();
    }
    return total;
  }

  public static int count() {
    return PLAYBOOKS.size();
  }

  public static int domainCount() {
    return distinctDomainGuideDocuments().size();
  }

  /**
   * Every tool name any prompt document mentions — procedure, domain guides and playbooks.
   *
   * <p>Checked at startup against the published registry. A name here that the agent does not
   * declare is a promise the model cannot keep: it calls it, gets {@code unknown_tool}, and loses
   * an iteration, silently, on every turn that routes to that document.
   */
  public static Set<String> toolNamesMentioned() {
    Set<String> names = new TreeSet<>(AiPromptResource.toolNames(AiOperatingProcedure.text()));
    for (AiPromptDocument doc : distinctDomainGuideDocuments().values()) {
      names.addAll(AiPromptResource.toolNames(doc.text()));
    }
    for (AiPromptDocument doc : PLAYBOOKS.values()) {
      names.addAll(AiPromptResource.toolNames(doc.text()));
    }
    for (AiPromptDocument doc : BULK.values()) {
      names.addAll(AiPromptResource.toolNames(doc.text()));
    }
    return names;
  }

  private static String text(AiPromptDocument doc) {
    return doc == null ? null : doc.text();
  }

  private static Map<AiTask, AiPromptDocument> load() {
    Map<AiTask, AiPromptDocument> loaded = new EnumMap<>(AiTask.class);
    for (AiTask task : AiTask.values()) {
      if (task.hasPlaybook()) {
        loaded.put(task, AiPromptResource.loadDocument(id(task.resource()), DIR + task.resource()));
      }
    }
    return Collections.unmodifiableMap(loaded);
  }

  /**
   * One read per file, shared by every task that names it, so the five supplier-invoice tasks hand
   * out the same instance rather than five equal copies.
   */
  private static Map<AiTask, AiPromptDocument> loadDomainGuides() {
    Map<String, AiPromptDocument> byFile = new HashMap<>();
    Map<AiTask, AiPromptDocument> loaded = new EnumMap<>(AiTask.class);
    for (AiTask task : AiTask.values()) {
      if (task.hasDomainGuide()) {
        loaded.put(
            task,
            byFile.computeIfAbsent(
                task.domainResource(),
                file -> AiPromptResource.loadDocument(id(file), DOMAIN_DIR + file)));
      }
    }
    return Collections.unmodifiableMap(loaded);
  }

  /**
   * The set-scoped supplement, named after the domain guide it belongs to: {@code
   * supplier-invoice.txt} → {@code playbooks/supplier-invoice-bulk.txt}.
   *
   * <p>Loaded leniently. A domain with no bulk file has its set-scoped turns assembled exactly as
   * they were before this document existed, which is a fallback rather than a fault — so it must
   * not be the reason an application refuses to start.
   */
  private static Map<AiTask, AiPromptDocument> loadBulk() {
    Map<String, AiPromptDocument> byFile = new HashMap<>();
    Map<AiTask, AiPromptDocument> loaded = new EnumMap<>(AiTask.class);
    for (AiTask task : AiTask.values()) {
      if (!task.hasDomainGuide()) {
        continue;
      }
      String base = task.domainResource();
      String file = base.substring(0, base.length() - ".txt".length()) + BULK_SUFFIX;
      AiPromptDocument doc =
          byFile.computeIfAbsent(
              file, name -> AiPromptResource.loadOptionalDocument(id(name), DIR + name));
      if (doc != null) {
        loaded.put(task, doc);
      }
    }
    return Collections.unmodifiableMap(loaded);
  }

  private static Map<String, AiPromptDocument> distinctDomainGuideDocuments() {
    Map<String, AiPromptDocument> byFile = new HashMap<>();
    for (AiTask task : AiTask.values()) {
      if (task.hasDomainGuide()) {
        byFile.put(task.domainResource(), DOMAIN_GUIDES.get(task));
      }
    }
    return byFile;
  }

  /** {@code supplier-invoice-post.txt} → {@code supplier-invoice-post}. */
  private static String id(String file) {
    return file.endsWith(".txt") ? file.substring(0, file.length() - ".txt".length()) : file;
  }
}
