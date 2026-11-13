package com.axelor.apps.aiaccountant.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small helpers for reading tool arguments in plain Java.
 *
 * <p>Deliberately not a JSON Schema validation engine: no such library is on the classpath and
 * adding one would buy little. The declared schema guides the model; these helpers plus the
 * handler's own checks are what actually protect the ERP.
 */
public final class AiToolArgs {

  private AiToolArgs() {}

  public static String requireText(JsonNode args, String field) {
    String v = optText(args, field);
    if (v == null) {
      throw new AiToolException("Missing required argument '" + field + "'.");
    }
    return v;
  }

  public static String optText(JsonNode args, String field) {
    if (args == null) {
      return null;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText("").trim();
    return v.isEmpty() ? null : v;
  }

  /** A positive database id. Rejects zero, negatives and non-numeric text. */
  public static long requireId(JsonNode args, String field) {
    Long v = optId(args, field);
    if (v == null) {
      throw new AiToolException("Missing required argument '" + field + "'.");
    }
    return v;
  }

  public static Long optId(JsonNode args, String field) {
    if (args == null) {
      return null;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    long v;
    if (n.isNumber()) {
      v = n.asLong();
    } else {
      try {
        v = Long.parseLong(n.asText("").trim());
      } catch (NumberFormatException e) {
        throw new AiToolException("Argument '" + field + "' must be a numeric id.");
      }
    }
    if (v <= 0) {
      throw new AiToolException("Argument '" + field + "' must be a positive id.");
    }
    return v;
  }

  /**
   * Whether the caller mentioned this field at all, whatever it set it to.
   *
   * <h2>Why presence is a question of its own</h2>
   *
   * {@link #optText} answers null both for "not mentioned" and for "explicitly set to null", and
   * for an edit those mean opposite things: the first leaves a field alone, the second clears it.
   * Anything that edits an existing record has to be able to tell them apart, or clearing a field
   * becomes impossible and every omitted field becomes a silent clear.
   */
  public static boolean has(JsonNode args, String field) {
    return args != null && args.has(field);
  }

  /** Present, and explicitly null — the caller is asking for the field to be cleared. */
  public static boolean isExplicitNull(JsonNode args, String field) {
    if (args == null) {
      return false;
    }
    JsonNode n = args.get(field);
    return n != null && n.isNull();
  }

  /**
   * A boolean, or null when the field is absent or null.
   *
   * <p>Accepts the JSON boolean and the strings "true"/"false", because models do send the quoted
   * form. Anything else is a refusal rather than a silent false: a misspelled flag that reads as
   * "no" looks exactly like a deliberate "no" in the reply.
   */
  public static Boolean optBool(JsonNode args, String field) {
    if (args == null) {
      return null;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isBoolean()) {
      return n.asBoolean();
    }
    String text = n.asText("").trim().toLowerCase(java.util.Locale.ROOT);
    if ("true".equals(text)) {
      return Boolean.TRUE;
    }
    if ("false".equals(text)) {
      return Boolean.FALSE;
    }
    throw new AiToolException("Argument '" + field + "' must be true or false.");
  }

  /** A decimal, or null when the field is absent or null. */
  public static java.math.BigDecimal optDecimal(JsonNode args, String field) {
    if (args == null) {
      return null;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String text = n.isNumber() ? n.asText() : n.asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new java.math.BigDecimal(text);
    } catch (NumberFormatException e) {
      throw new AiToolException("Argument '" + field + "' must be a number.");
    }
  }

  /** An ISO date ({@code yyyy-MM-dd}), or null when the field is absent or null. */
  public static java.time.LocalDate optDate(JsonNode args, String field) {
    String text = optText(args, field);
    if (text == null) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw new AiToolException(
          "Argument '" + field + "' must be an ISO date (yyyy-MM-dd), not '" + text + "'.");
    }
  }

  /** Clamps a caller-supplied page size into a sane range, so results stay bounded. */
  public static int limit(JsonNode args, String field, int defaultValue, int max) {
    if (args == null) {
      return defaultValue;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return defaultValue;
    }
    int v;
    if (n.isNumber()) {
      v = n.asInt(defaultValue);
    } else {
      try {
        v = Integer.parseInt(n.asText("").trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    if (v < 1) {
      return 1;
    }
    return Math.min(v, max);
  }

  /**
   * Clamps a caller-supplied page offset into {@code [0, max]}; a missing, null, non-numeric or
   * negative value means the first page (offset 0).
   *
   * <p>An offset is a count of rows to skip, not a database id, so — unlike {@link #optId} — zero
   * and negatives are not an error: zero is the first page and a negative is clamped to it. Reading
   * an offset through {@code optId} rejected both with "must be a positive id", which is the wrong
   * contract for paging.
   */
  public static int offset(JsonNode args, String field, int max) {
    if (args == null) {
      return 0;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return 0;
    }
    long v;
    if (n.isNumber()) {
      v = n.asLong();
    } else {
      try {
        v = Long.parseLong(n.asText("").trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return (int) Math.max(0L, Math.min(v, max));
  }
}
