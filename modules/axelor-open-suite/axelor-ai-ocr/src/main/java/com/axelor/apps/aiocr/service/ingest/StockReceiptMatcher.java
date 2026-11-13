package com.axelor.apps.aiocr.service.ingest;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.Type;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matches each detected stock receipt ("Стокова разписка") to the Supplier Invoice it documents,
 * from CONTENT — never document position. Pure + deterministic + unit-testable (no Azure, no DB):
 * it works only on the {@link DocSegment}s a {@link BgOcrPdfSegmenter} already produced (each
 * invoice / receipt segment carries its {@link OcrExtractResult} profile — total + line items).
 *
 * <p>Rules honoured (see the Phase A stock-receipt spec):
 *
 * <ul>
 *   <li><b>Primary signal = the total</b>; item overlap is corroborating / disambiguating evidence;
 *       quantities, unit prices and line totals add weight when present.
 *   <li>One invoice may receive <b>several</b> receipts; each unique receipt attaches to <b>one</b>
 *       invoice; a receipt may sit before OR after its invoice.
 *   <li>Document position is only a <b>weak tie-breaker</b> for an otherwise-even content tie,
 *       never the primary rule; two equally-plausible invoices are left <b>ambiguous</b> (never
 *       guessed).
 *   <li><b>Duplicate physical copies</b> of the same receipt are detected (same receipt number, or
 *       an all-but-identical total + items) and ignored — only the first is matched.
 *   <li>A receipt with no reliable invoice is <b>NO_MATCH</b> — preserved, flagged for review,
 *       never force-attached to an arbitrary invoice.
 * </ul>
 */
public final class StockReceiptMatcher {

  /** Absolute money tolerance (2 cents) for "equal" totals. */
  private static final BigDecimal MONEY_EPS = new BigDecimal("0.02");

  /** Relative tolerance for a strong total match (0.5%) and a weak one (3%). */
  private static final double REL_STRONG = 0.005;

  private static final double REL_WEAK = 0.03;

  /** A receipt needs at least this combined score against an invoice to be a candidate at all. */
  private static final double CANDIDATE_MIN = 0.50;

  /** Two candidates within this score margin are a near-tie → position tie-break / ambiguous. */
  private static final double TIE_MARGIN = 0.12;

  /** Item overlap at/above this alone makes a receipt a candidate even without a matching total. */
  private static final double ITEM_STRONG = 0.75;

  /** Two receipts whose totals are equal and whose items overlap at/above this are copies. */
  private static final double DUP_ITEM_OVERLAP = 0.85;

  /** Per-item fuzzy equality: normalized Levenshtein similarity, or word-set Jaccard. */
  private static final double ITEM_SIM = 0.80;

  private static final double ITEM_JACCARD = 0.60;

  public enum Status {
    MATCHED,
    AMBIGUOUS,
    NO_MATCH,
    DUPLICATE
  }

  /** Outcome for one receipt segment. */
  public static final class Decision {
    public final Status status;
    public final Integer invoiceSegIndex; // set for MATCHED
    public final Integer duplicateOfSegIndex; // set for DUPLICATE
    public final String reason;

    Decision(Status status, Integer invoiceSegIndex, Integer duplicateOfSegIndex, String reason) {
      this.status = status;
      this.invoiceSegIndex = invoiceSegIndex;
      this.duplicateOfSegIndex = duplicateOfSegIndex;
      this.reason = reason;
    }
  }

  private static final class Profile {
    final int segIndex;
    final int page;
    final BigDecimal total;
    final String number; // receipt number (receipts) / invoice number (invoices), may be null
    final List<Item> items;

    Profile(int segIndex, int page, BigDecimal total, String number, List<Item> items) {
      this.segIndex = segIndex;
      this.page = page;
      this.total = total;
      this.number = number;
      this.items = items;
    }
  }

  private static final class Item {
    final String text;
    final Set<String> tokens;
    final BigDecimal qty;
    final BigDecimal unitPrice;
    final BigDecimal lineAmount;

    Item(String text, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineAmount) {
      this.text = text;
      this.tokens = tokenSet(text);
      this.qty = qty;
      this.unitPrice = unitPrice;
      this.lineAmount = lineAmount;
    }
  }

  /**
   * Decide, for every STOCK_RECEIPT segment (by its index in {@code segments}), whether it matches
   * an invoice, is a duplicate copy, is ambiguous, or has no reliable invoice.
   */
  public Map<Integer, Decision> match(List<DocSegment> segments) {
    Map<Integer, Decision> out = new HashMap<>();
    if (segments == null || segments.isEmpty()) {
      return out;
    }
    List<Profile> invoices = new ArrayList<>();
    List<Profile> receipts = new ArrayList<>();
    for (int i = 0; i < segments.size(); i++) {
      DocSegment seg = segments.get(i);
      if (seg == null) {
        continue;
      }
      if (seg.type == Type.INVOICE || seg.type == Type.REVIEW) {
        invoices.add(profile(i, seg, seg.normalizedInvoiceNo));
      } else if (seg.type == Type.STOCK_RECEIPT) {
        receipts.add(profile(i, seg, seg.receiptNumber));
      }
    }

    // 1) Drop duplicate physical copies of a receipt (same number, or near-identical total+items).
    List<Profile> representatives = new ArrayList<>();
    for (Profile r : receipts) {
      Profile dupOf = findDuplicate(r, representatives);
      if (dupOf != null) {
        out.put(
            r.segIndex,
            new Decision(
                Status.DUPLICATE,
                null,
                dupOf.segIndex,
                "Duplicate copy of the receipt on pages of segment #" + dupOf.segIndex + "."));
      } else {
        representatives.add(r);
      }
    }

    // 2) Match each surviving receipt to an invoice by content.
    for (Profile r : representatives) {
      out.put(r.segIndex, decide(r, invoices));
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  private Decision decide(Profile receipt, List<Profile> invoices) {
    if (invoices.isEmpty()) {
      return new Decision(Status.NO_MATCH, null, null, "No invoice in this document to match.");
    }
    // Score every invoice; keep those above the candidate floor.
    List<double[]> scored = new ArrayList<>(); // [invoiceListIndex, score]
    for (int i = 0; i < invoices.size(); i++) {
      double s = score(receipt, invoices.get(i));
      if (s >= CANDIDATE_MIN) {
        scored.add(new double[] {i, s});
      }
    }
    if (scored.isEmpty()) {
      return new Decision(
          Status.NO_MATCH, null, null, "No invoice matches this receipt (total/items).");
    }
    scored.sort((a, b) -> Double.compare(b[1], a[1]));
    int bestIdx = (int) scored.get(0)[0];
    double best = scored.get(0)[1];
    if (scored.size() == 1) {
      return matched(invoices.get(bestIdx), best);
    }
    double second = scored.get(1)[1];
    if (best - second >= TIE_MARGIN) {
      return matched(invoices.get(bestIdx), best);
    }
    // Near-tie on content → position is a WEAK tie-breaker: pick the nearest invoice by page, but
    // only when a single nearest exists among the tied top candidates; otherwise stay ambiguous.
    List<Profile> tied = new ArrayList<>();
    for (double[] sc : scored) {
      if (best - sc[1] < TIE_MARGIN) {
        tied.add(invoices.get((int) sc[0]));
      }
    }
    Profile nearest = uniqueNearest(receipt.page, tied);
    if (nearest != null) {
      return new Decision(
          Status.MATCHED,
          nearest.segIndex,
          null,
          "Matched by content (near-tie resolved by document proximity).");
    }
    return new Decision(
        Status.AMBIGUOUS,
        null,
        null,
        tied.size() + " invoices are equally plausible for this receipt — not attached.");
  }

  private static Decision matched(Profile invoice, double score) {
    return new Decision(
        Status.MATCHED,
        invoice.segIndex,
        null,
        "Matched to invoice segment #" + invoice.segIndex + " (score " + round2(score) + ").");
  }

  /** The single invoice with the smallest page distance to {@code page}; null when it is tied. */
  private static Profile uniqueNearest(int page, List<Profile> invoices) {
    Profile best = null;
    int bestDist = Integer.MAX_VALUE;
    boolean tie = false;
    for (Profile inv : invoices) {
      int d = Math.abs(inv.page - page);
      if (d < bestDist) {
        bestDist = d;
        best = inv;
        tie = false;
      } else if (d == bestDist) {
        tie = true;
      }
    }
    return tie ? null : best;
  }

  /**
   * Combined score in [0,1]: total is primary (0.65), item overlap corroborates (0.35); a very
   * strong item overlap alone still makes the receipt a candidate (goods received vs invoiced).
   */
  private double score(Profile receipt, Profile invoice) {
    double totalComponent = totalComponent(receipt.total, invoice.total);
    double itemComponent = itemOverlap(receipt.items, invoice.items);
    double s = 0.65 * totalComponent + 0.35 * itemComponent;
    if (itemComponent >= ITEM_STRONG) {
      s = Math.max(s, 0.60);
    }
    return s;
  }

  private static double totalComponent(BigDecimal a, BigDecimal b) {
    if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) {
      return 0.0;
    }
    double diff = a.subtract(b).abs().doubleValue();
    double scale = Math.max(a.abs().doubleValue(), b.abs().doubleValue());
    if (diff <= MONEY_EPS.doubleValue() || diff <= REL_STRONG * scale) {
      return 1.0;
    }
    if (diff <= REL_WEAK * scale) {
      return 0.4;
    }
    return 0.0;
  }

  /** Fraction of receipt items that fuzzy-match some invoice item (receipt-centric). */
  private static double itemOverlap(List<Item> receiptItems, List<Item> invoiceItems) {
    if (receiptItems.isEmpty() || invoiceItems.isEmpty()) {
      return 0.0;
    }
    int matched = 0;
    for (Item r : receiptItems) {
      for (Item inv : invoiceItems) {
        if (itemsMatch(r, inv)) {
          matched++;
          break;
        }
      }
    }
    return (double) matched / receiptItems.size();
  }

  private static boolean itemsMatch(Item a, Item b) {
    // Amount evidence: same qty + unit price (or same line total) is a strong match on its own.
    if (moneyClose(a.lineAmount, b.lineAmount)
        && a.lineAmount != null
        && a.lineAmount.signum() > 0) {
      if (a.tokens.isEmpty() || b.tokens.isEmpty() || jaccard(a.tokens, b.tokens) >= 0.3) {
        return true;
      }
    }
    if (numberClose(a.qty, b.qty)
        && moneyClose(a.unitPrice, b.unitPrice)
        && a.qty != null
        && a.unitPrice != null) {
      return true;
    }
    // Text evidence: normalized equality / containment / high similarity / word overlap.
    if (a.text.isEmpty() || b.text.isEmpty()) {
      return false;
    }
    if (a.text.equals(b.text) || a.text.contains(b.text) || b.text.contains(a.text)) {
      return true;
    }
    if (similarity(a.text, b.text) >= ITEM_SIM) {
      return true;
    }
    return jaccard(a.tokens, b.tokens) >= ITEM_JACCARD;
  }

  // ---------------------------------------------------------------------------
  // Duplicate receipts
  // ---------------------------------------------------------------------------

  private static Profile findDuplicate(Profile r, List<Profile> kept) {
    for (Profile k : kept) {
      if (r.number != null && r.number.equals(k.number)) {
        return k; // same receipt number → same physical document
      }
      boolean totalsEqual = totalComponent(r.total, k.total) >= 1.0;
      if (totalsEqual
          && !r.items.isEmpty()
          && !k.items.isEmpty()
          && itemOverlap(r.items, k.items) >= DUP_ITEM_OVERLAP
          && itemOverlap(k.items, r.items) >= DUP_ITEM_OVERLAP) {
        return k; // near-identical content → a copy
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Profiles
  // ---------------------------------------------------------------------------

  private static Profile profile(int segIndex, DocSegment seg, String number) {
    OcrExtractResult r = seg.mappedResult;
    BigDecimal total = null;
    List<Item> items = new ArrayList<>();
    if (r != null) {
      total = r.getTotalGross() != null ? r.getTotalGross() : r.getTotalBase();
      if (r.getLines() != null) {
        for (OcrExtractLine l : r.getLines()) {
          String text = normalize(firstNonBlank(l.getDescription(), l.getProductCode()));
          BigDecimal lineAmount =
              l.getGrossAmount() != null ? l.getGrossAmount() : l.getNetAmount();
          items.add(new Item(text, l.getQty(), l.getUnitPrice(), lineAmount));
        }
      }
    }
    return new Profile(segIndex, seg.fromPage, total, normalizeNumber(number), items);
  }

  // ---------------------------------------------------------------------------
  // Text / number helpers
  // ---------------------------------------------------------------------------

  static String normalize(String s) {
    if (s == null) {
      return "";
    }
    String lower = s.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(lower.length());
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      sb.append(Character.isLetterOrDigit(c) ? c : ' ');
    }
    return sb.toString().replaceAll("\\s+", " ").trim();
  }

  private static String normalizeNumber(String s) {
    if (s == null) {
      return null;
    }
    String digits = s.replaceAll("\\D", "");
    return digits.isEmpty() ? null : digits;
  }

  static Set<String> tokenSet(String normalized) {
    Set<String> out = new LinkedHashSet<>();
    if (normalized == null || normalized.isEmpty()) {
      return out;
    }
    for (String w : normalized.split(" ")) {
      if (w.length() >= 2) { // drop 1-char noise tokens
        out.add(w);
      }
    }
    return out;
  }

  static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int inter = 0;
    for (String x : a) {
      if (b.contains(x)) {
        inter++;
      }
    }
    int union = a.size() + b.size() - inter;
    return union == 0 ? 0.0 : (double) inter / union;
  }

  /** Normalized Levenshtein similarity in [0,1] (1 == equal). Bounded for short OCR item names. */
  static double similarity(String a, String b) {
    if (a.equals(b)) {
      return 1.0;
    }
    int max = Math.max(a.length(), b.length());
    if (max == 0) {
      return 1.0;
    }
    return 1.0 - (double) levenshtein(a, b) / max;
  }

  private static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[b.length()];
  }

  private static boolean moneyClose(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return false;
    }
    double diff = a.subtract(b).abs().doubleValue();
    double scale = Math.max(a.abs().doubleValue(), b.abs().doubleValue());
    return diff <= MONEY_EPS.doubleValue() || diff <= REL_STRONG * scale;
  }

  private static boolean numberClose(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return false;
    }
    return a.subtract(b).abs().doubleValue() <= 0.001;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.trim().isEmpty()) {
      return a;
    }
    return b;
  }

  private static double round2(double d) {
    return Math.round(d * 100.0) / 100.0;
  }
}
