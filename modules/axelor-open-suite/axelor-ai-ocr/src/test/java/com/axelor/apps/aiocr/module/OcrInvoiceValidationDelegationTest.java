package com.axelor.apps.aiocr.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The stock validation lifecycle must still run, whole and once.
 *
 * <p>Our hook wraps {@code InvoiceService.validate}; it does not reimplement any part of it. Every
 * stock side effect — {@code compute}, the tax/account checks, the invoice terms, the payment-mode
 * and blocking checks, the partner account, the journal, the status transition and its metadata,
 * the workflow callback, advance payments, the automatic mail — reaches the invoice because {@code
 * super.validate(invoice)} is called exactly once and nothing else is called in its place.
 *
 * <p>Read from the SOURCE because the alternative is instantiating a 21-argument Guice service. A
 * structural check is enough here: the risk being guarded is somebody "optimising" the hook by
 * inlining part of validation or by adding another rebuild next to it, and both are visible in the
 * text.
 */
class OcrInvoiceValidationDelegationTest {

  private static String source() throws IOException {
    Path root = Paths.get("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("modules/axelor-open-suite"))) {
      root = root.getParent();
    }
    if (root == null) {
      return null;
    }
    Path file =
        root.resolve(
            "modules/axelor-open-suite/axelor-ai-ocr/src/main/java/com/axelor/apps/aiocr/"
                + "service/invoice/OcrInvoiceServiceImpl.java");
    return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }

  @Test
  void stockValidationIsDelegatedToExactlyOnceAndNothingIsRebuiltBesideIt() throws IOException {
    String source = source();
    Assumptions.assumeTrue(source != null, "source not reachable from the test working dir");

    assertEquals(1, occurrences(source, "super.validate(invoice)"), "one stock validate call");
    assertEquals(
        1, occurrences(source, "super.checkPreconditions(invoice)"), "one stock precondition call");
    assertEquals(
        1,
        occurrences(source, "reconcileBeforeValidation(invoice)"),
        "the hook runs once, around the stock call — never twice");

    // The duplicate Tax rows came from rebuilding the summary inside the validation transaction.
    // Nothing of the sort may live beside the stock call.
    for (String forbidden :
        new String[] {"syncTaxSummary", "TaxInvoiceLine", "InvoiceService.class).compute"}) {
      assertTrue(
          !source.contains(forbidden),
          "the validation hook must not " + forbidden + " — stock compute already does");
    }
  }
}
