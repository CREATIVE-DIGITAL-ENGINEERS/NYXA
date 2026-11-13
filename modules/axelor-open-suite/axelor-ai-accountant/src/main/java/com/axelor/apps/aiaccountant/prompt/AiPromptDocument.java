package com.axelor.apps.aiaccountant.prompt;

/**
 * One instruction document: what it is, where it came from, which version of it this is, and its
 * text.
 *
 * <h2>Why a version at all</h2>
 *
 * These files are the assistant's behaviour. A rule changes, the deployed WAR changes with it, and
 * nothing anywhere says which wording produced a given day's answers — so a report that "it started
 * collapsing invoice lines last week" cannot be tied to the edit that caused it, and a rollback
 * cannot be checked from the outside. The version is stamped in the file, logged at startup and
 * recorded per turn, so a run of behaviour has a document revision attached to it.
 *
 * <p>It is a plain integer, bumped by hand when the content changes meaningfully. Deliberately not
 * a hash: a hash changes when a typo is fixed, and would say "different" on every commit that
 * touched whitespace. The number is a claim by whoever edited the file that the instructions now
 * differ, which is the thing worth recording.
 *
 * <p>The header is stripped before the text is sent. The model is given the rules; the bookkeeping
 * about which revision they are is for the log.
 */
public final class AiPromptDocument {

  private final String id;
  private final String path;
  private final int version;
  private final String text;

  AiPromptDocument(String id, String path, int version, String text) {
    this.id = id;
    this.path = path;
    this.version = version;
    this.text = text;
  }

  /** Stable short name, e.g. {@code supplier-invoice-post} — what the metric line carries. */
  public String id() {
    return id;
  }

  public String path() {
    return path;
  }

  /** The declared revision, or 0 when the file carries no version header. */
  public int version() {
    return version;
  }

  /** The text as sent, with the version header removed. Never null, never empty. */
  public String text() {
    return text;
  }

  public int bytes() {
    return AiPromptResource.bytes(text);
  }

  /** {@code supplier-invoice-post@3} — one token for a log line. */
  public String describe() {
    return id + '@' + version;
  }

  @Override
  public String toString() {
    return "AiPromptDocument[" + describe() + ']';
  }
}
