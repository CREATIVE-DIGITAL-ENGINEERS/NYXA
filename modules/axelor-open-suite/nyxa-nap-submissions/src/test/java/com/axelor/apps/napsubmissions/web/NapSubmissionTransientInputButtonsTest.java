package com.axelor.apps.napsubmissions.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Buttons whose controller reads a TRANSIENT field (a reason, a date, a certificate, a file) must
 * not run {@code save} first: saving reloads the record and blanks every transient field, so the
 * controller always saw an empty value ("A resolution reason is required." with a reason typed in).
 * The controllers load the stored record themselves ({@code find(ctx)}), so no save is needed.
 */
class NapSubmissionTransientInputButtonsTest {

  private static final Path VIEW = Paths.get("src/main/resources/views/NapSubmission.xml");

  private static final List<String> TRANSIENT_INPUT_ACTIONS =
      List.of(
          "action-nap-submission-method-fetch-nap-result",
          "action-nap-submission-method-resolve-unknown",
          "action-nap-submission-method-record-manual",
          "action-nap-submission-method-record-accepted",
          "action-nap-submission-method-record-rejected",
          "action-nap-submission-method-record-correction",
          "action-nap-submission-method-record-result",
          "action-nap-submission-method-attach-response");

  @Test
  void transientInputButtonsDoNotSaveFirst() throws Exception {
    String view = new String(Files.readAllBytes(VIEW), StandardCharsets.UTF_8);
    List<String> offending = new ArrayList<>();
    Matcher onClick = Pattern.compile("onClick=\"([^\"]*)\"").matcher(view);
    while (onClick.find()) {
      List<String> steps = List.of(onClick.group(1).split("\\s*,\\s*"));
      for (String action : TRANSIENT_INPUT_ACTIONS) {
        int at = steps.indexOf(action);
        if (at > 0 && steps.subList(0, at).contains("save")) {
          offending.add(onClick.group(1));
        }
      }
    }
    assertTrue(offending.isEmpty(), "save before a transient-input action: " + offending);
    for (String action : TRANSIENT_INPUT_ACTIONS) {
      assertTrue(view.contains("onClick=\"" + action + "\""), "button not found for " + action);
    }
  }
}
