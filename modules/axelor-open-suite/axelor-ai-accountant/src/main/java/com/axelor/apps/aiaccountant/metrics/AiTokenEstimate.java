package com.axelor.apps.aiaccountant.metrics;

/**
 * A conservative estimate of how many tokens a piece of text costs. Never an exact count.
 *
 * <h2>Why an estimate, and why this one</h2>
 *
 * There is no tokeniser in this runtime, and the deployed models (o200k-family byte-level BPE) are
 * not something a Java build can reproduce without shipping the vocabulary. The authoritative
 * figures are the ones the service returns in {@code usage}, which {@link AiTurnMetrics} and {@link
 * AiRequestMetrics} record; this class exists only for decisions that must be made <em>before</em>
 * a request is sent, where no such figure exists yet.
 *
 * <p>The old rule — UTF-8 bytes ÷ 4 — was an English-prose heuristic. It undercounts JSON, whose
 * punctuation tokenises poorly, and it was only accidentally pessimistic for Cyrillic. This one
 * counts by code-point class, each rate chosen on the expensive side of what BPE tokenisers
 * measure:
 *
 * <ul>
 *   <li><b>ASCII</b> (Latin text, digits, JSON punctuation, whitespace): 3 characters per token.
 *       English prose is nearer 4, tool-schema JSON nearer 3.5, so 3 covers both.
 *   <li><b>Two-byte UTF-8</b> (U+0080–U+07FF — Cyrillic, so Bulgarian): 2 characters per token.
 *       Cyrillic measures roughly 2.5–4 characters per token in current vocabularies.
 *   <li><b>Anything wider</b> (CJK, symbols, emoji): 1 token per code point.
 * </ul>
 *
 * <p>The sum is then raised by {@link #SAFETY_MARGIN}. A bound checked against this estimate is
 * therefore reached somewhat early rather than late: under-estimating would send a request the
 * provider refuses, over-estimating only leaves out an old exchange that would have fitted.
 */
public final class AiTokenEstimate {

  static final double ASCII_CHARS_PER_TOKEN = 3.0d;

  static final double TWO_BYTE_CHARS_PER_TOKEN = 2.0d;

  /** Applied on top of the per-class rates. */
  static final double SAFETY_MARGIN = 1.15d;

  private AiTokenEstimate() {}

  /** Estimated tokens for this text; 0 for null or empty. Rounded up. */
  public static int of(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    long ascii = 0;
    long twoByte = 0;
    long wider = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp < 0x80) {
        ascii++;
      } else if (cp < 0x800) {
        twoByte++;
      } else {
        wider++;
      }
    }
    double raw = ascii / ASCII_CHARS_PER_TOKEN + twoByte / TWO_BYTE_CHARS_PER_TOKEN + wider;
    return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(raw * SAFETY_MARGIN));
  }
}
