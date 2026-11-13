package com.axelor.apps.aiocr.devfixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.SegmentPlan;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DEV/TEST-ONLY offline debugging harness for Phase A document splitting. Reads the stitched Azure
 * fixtures produced by {@link AzureFixtureGenerator} from {@code test-data/fixtures/} and runs
 * {@link BgOcrPdfSegmenter} against them WITHOUT any Azure call, so the split logic can be
 * inspected and regression-checked against real documents. Skips gracefully when a fixture is
 * absent.
 */
class StitchedFixtureSegmenterTest {

  @Test
  void debugTwoSingleInvoices() throws IOException {
    Path fixture = fixture("twosinglepageinvoices.azure-full.json");
    assumeTrue(
        fixture != null && Files.exists(fixture),
        "Run AzureFixtureGenerator first to produce " + "twosinglepageinvoices.azure-full.json");

    SegmentPlan plan = new BgOcrPdfSegmenter().segment(read(fixture));
    printPlan("twosinglepageinvoices", plan);

    List<DocSegment> invoices =
        plan.segments.stream()
            .filter(
                s ->
                    s.type == BgOcrPdfSegmenter.Type.INVOICE
                        || s.type == BgOcrPdfSegmenter.Type.REVIEW)
            .collect(Collectors.toList());

    assertTrue(plan.needsSplit, "Two separate invoices must trigger a split");
    assertEquals(2, invoices.size(), "Expected two distinct invoice segments");

    Set<String> numbers = new LinkedHashSet<>();
    for (DocSegment s : invoices) {
      numbers.add(s.normalizedInvoiceNo);
    }
    assertEquals(2, numbers.size(), "The two invoices must have distinct invoice numbers");

    // No field leakage: each segment's own result carries its own (different) invoice number.
    OcrExtractResult a = invoices.get(0).mappedResult;
    OcrExtractResult b = invoices.get(1).mappedResult;
    assertTrue(a != null && b != null, "Each invoice segment must carry its own OCR result");
    assertTrue(
        !equalsIgnoreNull(a.getInvoiceNumber(), b.getInvoiceNumber()),
        "Invoice numbers must not leak between segments");
  }

  /** Per-page ground-truth diagnostic for one fixture: markers + 10-digit numbers + first line. */
  @Test
  void dumpPagesInvoicesAndOther() throws IOException {
    String base = System.getenv("AIOCR_DUMP_FIXTURE");
    if (base == null || base.isEmpty()) {
      base = "invoicesandother_plusreceipts";
    }
    Path fixture = fixture(base + ".azure-full.json");
    assumeTrue(fixture != null && Files.exists(fixture), "Generate the fixture first.");
    com.fasterxml.jackson.databind.JsonNode root =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(read(fixture));
    System.out.println("===== invoicesandother per-page =====");
    for (com.fasterxml.jackson.databind.JsonNode p : root.path("pages")) {
      int pn = p.path("pageNumber").asInt();
      StringBuilder head = new StringBuilder();
      StringBuilder all = new StringBuilder();
      int i = 0;
      for (com.fasterxml.jackson.databind.JsonNode ln : p.path("lines")) {
        String c = ln.path("content").asText("");
        all.append(c).append('\n');
        if (i++ < 2) {
          head.append(c).append(" | ");
        }
      }
      String low = all.toString().toLowerCase(java.util.Locale.ROOT);
      boolean fakt = low.contains("фактура") || low.contains("ф-ра") || low.contains("фак.");
      boolean stock = low.contains("стокова разписка");
      boolean known =
          low.contains("товарителн")
              || low.contains("протокол")
              || low.contains("приемо-предавател")
              || low.contains("складова разписка")
              || low.contains("акт за приемане");
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("(?<!\\d)\\d{10}(?!\\d)").matcher(all);
      java.util.LinkedHashSet<String> nums = new java.util.LinkedHashSet<>();
      while (m.find()) {
        nums.add(m.group());
      }
      String t = all.toString();
      boolean fiscal =
          t.toLowerCase(java.util.Locale.ROOT)
              .matches("(?s).*(фискален бон|касова бележка|касов бон).*");
      boolean sale =
          java.util.regex.Pattern.compile("(?iuU)(?<![\\p{L}])(sale|продажба)(?![\\p{L}])")
              .matcher(t)
              .find();
      String[] sig = {
        "master\\s?card|maestro",
        "(?<![\\p{L}])visa(?![\\p{L}])",
        "(?<![\\p{L}])mid(?![\\p{L}])",
        "(?<![\\p{L}])tid(?![\\p{L}])",
        "(?<![\\p{L}])aid(?![\\p{L}])",
        "\\d{4,6}\\s?\\*{2,}\\s?\\d{2,4}|\\*{4,}"
      };
      int cardsigs = 0;
      for (String s : sig) {
        if (java.util.regex.Pattern.compile("(?iuU)" + s).matcher(t).find()) {
          cardsigs++;
        }
      }
      boolean pos = fiscal || (sale && cardsigs >= 2);
      System.out.printf(
          "  p%-2d len=%-5d faktura=%-5b stock=%-5b known=%-5b POS=%-5b(sale=%b cards=%d fiscal=%b) nums=%s%n         head[%s]%n",
          pn, all.length(), fakt, stock, known, pos, sale, cardsigs, fiscal, nums, head);
    }
    System.out.println("  documents=" + root.path("documents").size());

    // Line-level positions for the pages where boundary/anchor decisions look wrong.
    java.util.Set<Integer> focus = new java.util.HashSet<>(java.util.Arrays.asList(6, 8, 20, 21));
    for (com.fasterxml.jackson.databind.JsonNode p : root.path("pages")) {
      int pn = p.path("pageNumber").asInt();
      if (!focus.contains(pn)) {
        continue;
      }
      System.out.println("  --- page " + pn + " lines ---");
      int li = 0;
      for (com.fasterxml.jackson.databind.JsonNode ln : p.path("lines")) {
        String c = ln.path("content").asText("");
        String lc = c.toLowerCase(java.util.Locale.ROOT);
        boolean kw = lc.contains("фактура") || lc.contains("ф-ра") || lc.contains("фак.");
        boolean n10 = java.util.regex.Pattern.compile("(?<!\\d)\\d{10}(?!\\d)").matcher(c).find();
        boolean st = lc.contains("стокова разписка");
        boolean kn =
            lc.contains("товарителн")
                || lc.contains("протокол")
                || lc.contains("приемо-предавател")
                || lc.contains("складова разписка")
                || lc.contains("акт за приемане");
        System.out.printf(
            "    L%-2d kw=%-5b n10=%-5b stock=%-5b known=%-5b | %s%n", li++, kw, n10, st, kn, c);
      }
    }
  }

  /**
   * Prints the segment plan for every stitched fixture present (no assertions) — pure inspection.
   */
  @Test
  void dumpAllFixtures() throws IOException {
    Path dir = fixturesDir();
    assumeTrue(dir != null && Files.isDirectory(dir), "No test-data/fixtures directory yet.");
    try (Stream<Path> files = Files.list(dir)) {
      List<Path> full =
          files
              .filter(p -> p.getFileName().toString().endsWith(".azure-full.json"))
              .sorted()
              .collect(Collectors.toList());
      assumeTrue(!full.isEmpty(), "No *.azure-full.json fixtures yet.");
      for (Path f : full) {
        SegmentPlan plan = new BgOcrPdfSegmenter().segment(read(f));
        printPlan(f.getFileName().toString(), plan);
      }
    }
  }

  // ---------------------------------------------------------------------------

  private static void printPlan(String name, SegmentPlan plan) {
    System.out.println("===== " + name + " =====");
    System.out.println(
        "pages="
            + plan.pageCount
            + " needsSplit="
            + plan.needsSplit
            + " segments="
            + plan.segments.size());
    int i = 0;
    for (DocSegment s : plan.segments) {
      String no = s.normalizedInvoiceNo == null ? "-" : s.normalizedInvoiceNo;
      String crop = s.isCrop() ? " CROP" : "";
      String supplier =
          s.mappedResult == null ? "" : (" supplier=" + safe(s.mappedResult.getSupplierName()));
      String gross = s.mappedResult == null ? "" : (" gross=" + s.mappedResult.getTotalGross());
      System.out.printf(
          "  [%d] %-13s pages %d-%d no=%s%s%s%s%n",
          i++, s.type, s.fromPage, s.toPage, no, crop, supplier, gross);
    }
  }

  private static boolean equalsIgnoreNull(String a, String b) {
    if (a == null || b == null) {
      return false; // two nulls are NOT considered "the same invoice" for leakage purposes
    }
    return a.equals(b);
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String read(Path p) throws IOException {
    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
  }

  private static Path fixture(String name) {
    Path dir = fixturesDir();
    return dir == null ? null : dir.resolve(name);
  }

  private static Path fixturesDir() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("test-data").resolve("fixtures");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
