package com.axelor.apps.aiaccountant.prompt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a prompt text file off the classpath, once, in UTF-8.
 *
 * <p>Shared by the operating procedure and every playbook so there is one answer to "what happens
 * when the file is missing": an exception naming it, at class-initialisation time. A prompt that
 * silently loads as an empty string produces an assistant that looks entirely normal and quietly
 * abandons every rule it was supposed to follow, which is far harder to notice than a failed start.
 *
 * <p>From the class loader rather than the filesystem, so the same code works in the WAR, in an
 * exploded build and in a unit test with no runtime around it.
 *
 * <p>Public because the same argument holds for any module that sends text to a model: the OCR
 * intake prompts were Java string concatenation with escaped JSON inside it, which is the format a
 * prompt is hardest to review in and easiest to break silently. A prompt belongs in a file an
 * accountant can read.
 */
public final class AiPromptResource {

  private AiPromptResource() {}

  /**
   * @param path classpath-relative, e.g. {@code com/axelor/apps/aiaccountant/prompt/x.txt}
   * @return the file's contents, trimmed; never null, never empty
   * @throws IllegalStateException when the resource is missing, unreadable or empty
   */
  public static String load(String path) {
    ClassLoader loader = AiPromptResource.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException(
            "AI Accountant: prompt resource missing from the classpath ("
                + path
                + "). The assistant must not run without it.");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
      if (text.isEmpty()) {
        throw new IllegalStateException("AI Accountant: prompt resource is empty (" + path + ").");
      }
      return text;
    } catch (IOException e) {
      throw new IllegalStateException(
          "AI Accountant: could not read prompt resource (" + path + ").", e);
    }
  }

  /**
   * The same file as a versioned document.
   *
   * <p>A resource may open with a header line of the form {@code #version 3}. It is read into
   * {@link AiPromptDocument#version()} and removed from the text, so the model sees the rules and
   * only the log sees the revision. A file without one loads at version 0 — legal, and visible as
   * "unversioned" wherever versions are reported, rather than silently reading as version 1.
   *
   * @param id the short stable name used in logs and metrics, e.g. {@code supplier-invoice-post}
   */
  public static AiPromptDocument loadDocument(String id, String path) {
    return document(id, path, load(path));
  }

  /**
   * As above, but {@code null} when the resource is absent instead of throwing.
   *
   * <p>For documents the assembly can legitimately do without. The strict loader is right for the
   * procedure and the playbooks — an assistant running without its rules looks entirely normal and
   * quietly abandons every one of them, so that must fail at startup. It is wrong for an optional
   * document, where the correct answer is to fall back to the composition that existed before it
   * did, and say so once.
   *
   * <p>Only absence is tolerated. A file that exists and is empty or unreadable still throws: that
   * is a broken document, not a missing one, and silently treating the two alike is how an empty
   * file becomes a set of rules nobody notices was lost.
   */
  public static AiPromptDocument loadOptionalDocument(String id, String path) {
    ClassLoader loader = AiPromptResource.class.getClassLoader();
    try (InputStream probe = loader.getResourceAsStream(path)) {
      if (probe == null) {
        return null;
      }
    } catch (IOException e) {
      return null;
    }
    return document(id, path, load(path));
  }

  private static AiPromptDocument document(String id, String path, String raw) {
    int version = 0;
    String text = raw;
    Matcher header = VERSION_HEADER.matcher(raw);
    if (header.find()) {
      try {
        version = Integer.parseInt(header.group(1));
      } catch (NumberFormatException ignore) {
        // A malformed number is worth no more than an absent one, and is not worth a failed boot.
        version = 0;
      }
      text = raw.substring(header.end()).trim();
    }
    if (text.isEmpty()) {
      throw new IllegalStateException(
          "AI Accountant: prompt resource holds a version header and nothing else (" + path + ").");
    }
    return new AiPromptDocument(id, path, version, text);
  }

  /** {@code #version 3} on the first line, and only on the first line. */
  private static final Pattern VERSION_HEADER = Pattern.compile("\\A#version\\s+(\\d+)\\s*\\R?");

  /** Size as it will be sent, for the startup budget line. */
  public static int bytes(String text) {
    return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Every {@code snake_case} token in a prompt that is meant to be a tool name.
   *
   * <p>Exists because a prompt naming a tool the model was never given is a bug that hides. The
   * assistant does not fail: it calls the name, gets {@code unknown_tool}, and spends one of its
   * iterations on it, every time that route is taken. Both shapes are possible — a name that does
   * not exist at all, and one that exists in Java but is deliberately not published (such as {@code
   * apply_exact_product_code}) — and reading the prompts is not a control.
   *
   * <p>Tool names are the only snake_case in these documents apart from the tool <em>result</em>
   * values below, which are quoted as answers rather than called. That list is short and stable
   * enough to keep by hand; a new one showing up here as a false warning is a cheap failure.
   */
  public static Set<String> toolNames(String text) {
    if (text == null) {
      return Collections.emptySet();
    }
    Set<String> names = new LinkedHashSet<>();
    Matcher m = SNAKE_CASE.matcher(text);
    while (m.find()) {
      String token = m.group();
      if (!RESULT_VALUES.contains(token)) {
        names.add(token);
      }
    }
    return names;
  }

  private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z]+(?:_[a-z]+)+\\b");

  /** Tool outcomes the prompts quote by name; they are values, not callable tools. */
  private static final Set<String> RESULT_VALUES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.asList("not_found", "not_executed", "already_failed")));
}
