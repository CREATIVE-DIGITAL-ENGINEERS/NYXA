package com.axelor.apps.aiaccountant.prompt;

/**
 * The universal operating procedure, sent once per conversation, held as a classpath resource.
 *
 * <h2>Why it is not a Java constant any more</h2>
 *
 * It had grown to roughly six thousand bytes of concatenated string literals inside the turn
 * orchestrator. Every review of it was a review of escaped quotes and {@code + " "} continuations,
 * every diff touched the class that runs the tool loop, and a rule could be edited without anyone
 * reading the class it lived in. Java 11 has no text blocks, so the only way to keep the text
 * reviewable as text is to store it as text.
 *
 * <h2>Three layers, and what belongs in each</h2>
 *
 * <ol>
 *   <li>The Foundry agent's {@code instructions} — persona and general safety, sent as the system
 *       prompt on <em>every</em> request.
 *   <li><b>This file</b> — the rules that hold whatever the user is doing: how to be brief, what
 *       authorisation means, how to resolve a target, how to batch, what a write result is worth,
 *       that there is no background execution. Sent once per conversation, because Azure retains
 *       the history.
 *   <li>{@link AiPlaybookLibrary} — the procedure for the job in front of the model, chosen per
 *       turn by {@link AiTaskResolver}. Everything supplier-invoice-specific lives there.
 * </ol>
 *
 * <p>Before that split this file was 28 KB and twenty-odd rules, most of them describing a single
 * workflow. Every turn carried the address rules while posting and the posting rules while listing,
 * and every future module would have added its own share to the same document sent to everybody.
 *
 * <p>Where these rules and the Foundry text genuinely differ, this one says so explicitly and wins
 * — it is written against the actual tool contracts, and the agent definition is portal state that
 * ships on a different cycle from the code. Where a playbook is more specific about its own
 * subject, the playbook wins.
 *
 * <h2>Loading</h2>
 *
 * Read once into a static final field, explicit UTF-8, from the class loader rather than the
 * filesystem, so it works identically in the WAR, in a test and in an exploded build. A missing or
 * empty resource throws at class-initialisation time: a silently empty procedure would produce an
 * assistant that looks fine and quietly abandons every rule in it, which is far worse than a
 * startup failure naming the file.
 */
public final class AiOperatingProcedure {

  /** Package-relative, so the file sits beside this class in both the source tree and the jar. */
  static final String RESOURCE = "com/axelor/apps/aiaccountant/prompt/operating-procedure.txt";

  private static final AiPromptDocument DOCUMENT =
      AiPromptResource.loadDocument("operating-procedure", RESOURCE);

  private AiOperatingProcedure() {}

  /** The procedure as sent, with its version header stripped. Never null, never empty. */
  public static String text() {
    return DOCUMENT.text();
  }

  /** The declared revision of the file, for the startup line. */
  public static int version() {
    return DOCUMENT.version();
  }

  public static AiPromptDocument document() {
    return DOCUMENT;
  }

  /** Its size in bytes as sent, for the startup prompt-budget line. */
  public static int bytes() {
    return DOCUMENT.bytes();
  }
}
