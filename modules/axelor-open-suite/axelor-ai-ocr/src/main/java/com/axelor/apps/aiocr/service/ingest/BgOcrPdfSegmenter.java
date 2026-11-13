package com.axelor.apps.aiocr.service.ingest;

import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits ONE Azure {@code analyzeResult} (a single OCR call over a multi-document PDF) into ordered
 * {@link DocSegment}s, without any further Azure call. Pure + deterministic + unit-testable.
 *
 * <p>Boundaries (A3c): an invoice starts at an invoice anchor and continues through following pages
 * until the next invoice anchor, a stock receipt, a recognized non-invoice document, or the end of
 * the PDF. Blank pages are hints only — they never end an invoice by themselves.
 *
 * <p>Invoice anchors come from BOTH Azure {@code documents[]} AND a text scan of page lines for
 * {@code Фактура / Ф-ра / Фак.} + a nearby 10-digit number (so several invoices are detected even
 * when Azure collapses them into one {@code documents[]} entry). Text anchors are de-duplicated
 * against {@code documents[]} anchors by normalized invoice number.
 *
 * <p>Each invoice/move/review segment carries its own {@link OcrExtractResult} built from ONLY the
 * OCR content inside its boundary (A3d) — never a copy of the first document's fields.
 */
public class BgOcrPdfSegmenter {

  /** Segment kinds. STOCK_RECEIPT/POS_RECEIPT/BLANK produce no record of their own. */
  public enum Type {
    INVOICE,
    MOVE,
    REVIEW,
    STOCK_RECEIPT,
    POS_RECEIPT, // POS / fiscal receipt — attached to the preceding invoice, never its own record
    BLANK
  }

  /** One detected document (or stock-receipt slice) within the source PDF. */
  public static final class DocSegment {
    public Type type;
    public int fromPage; // 1-based inclusive
    public int toPage; // 1-based inclusive
    public String normalizedInvoiceNo; // invoice/review only, for dedup/ordinal (may be null)
    public String receiptNumber; // STOCK_RECEIPT only: the receipt's own "No: ..." (dedup/matching)

    // Same-page crop (two invoices on one physical page); null for whole-page segments.
    public double[] cropBbox; // [left, top, right, bottom] in page units (origin top-left)
    public double cropPageW;
    public double cropPageH;

    // Per-segment OCR result (null for STOCK_RECEIPT/BLANK).
    public OcrExtractResult mappedResult;

    // The Azure documents[] entry mappedResult was built from. Kept so the item list can be
    // re-scoped to this segment's FINAL page range: a segment's toPage still grows while the pages
    // after it are classified (a continuation page extends it, a duplicate copy closes it), so the
    // range is not yet known when the result is first mapped. Internal to segmentation.
    Anchor sourceAnchor;

    public boolean isCrop() {
      return cropBbox != null;
    }
  }

  /** Result of segmentation. */
  public static final class SegmentPlan {
    public final List<DocSegment> segments;
    public final boolean needsSplit;
    public final int pageCount;

    /**
     * Whether the PDF contains at least one recognised invoice, independently of whether that is
     * enough to warrant a split. {@link #needsSplit} answers "does this PDF hold more than one
     * document?"; this answers "does it hold an invoice at all?" - the weaker fact a ZIP-extracted
     * PDF is promoted to a carrier on, so that a single-invoice PDF inside an archive still yields
     * ZIP carrier -&gt; PDF carrier -&gt; invoice child. Never affects needsSplit, so the
     * direct-upload path is unchanged.
     */
    public final boolean hasInvoiceAnchor;

    SegmentPlan(
        List<DocSegment> segments, boolean needsSplit, int pageCount, boolean hasInvoiceAnchor) {
      this.segments = segments;
      this.needsSplit = needsSplit;
      this.pageCount = pageCount;
      this.hasInvoiceAnchor = hasInvoiceAnchor;
    }

    /**
     * How many segments would become a child record if this plan were split - the INVOICE / MOVE /
     * REVIEW ones. STOCK_RECEIPT, POS_RECEIPT and BLANK produce none (a receipt is attached to an
     * invoice child, a blank page is dropped), which is exactly the set {@code
     * AiOcrPdfSplitService.doSplit} skips. Zero here means splitting would leave the carrier with
     * no children at all, and a childless finished carrier rolls up as failed - so the promotion
     * check refuses it and the upload stays a single document.
     */
    public int recordSegmentCount() {
      if (segments == null) {
        return 0;
      }
      return (int)
          segments.stream()
              .filter(s -> s.type == Type.INVOICE || s.type == Type.MOVE || s.type == Type.REVIEW)
              .count();
    }
  }

  // Invoice keyword as a standalone token: Фактура / ФАКТУРА / Ф-ра / Фак.
  private static final Pattern INVOICE_KEYWORD =
      Pattern.compile("(?iuU)(?<![\\p{L}])(?:фактура|ф-?ра|фак\\.?)(?![\\p{L}])");

  // A standalone 10-digit invoice number (not a digit-substring of a longer run such as an IBAN).
  private static final Pattern INVOICE_NUMBER_10 = Pattern.compile("(?<!\\d)(\\d{10})(?!\\d)");

  // An invoice-number label near the number strengthens it as THE invoice number (vs a client id).
  private static final Pattern NUMBER_LABEL =
      Pattern.compile("(?iuU)(№|\\bno\\b|номер|фактур|ф-?ра)");

  private static final String STOCK_RECEIPT = "стокова разписка";

  // A stock receipt's own document number, printed in its header as "No: 9000121609" (6+ digits).
  // Used to recognize a physical COPY of a receipt already placed (same number → duplicate) and to
  // deduplicate receipts across the PDF before content matching.
  private static final Pattern RECEIPT_NUMBER = Pattern.compile("(?<!\\d)(\\d{6,})(?!\\d)");

  // POS / fiscal receipt markers. Either an explicit fiscal-receipt title, or a POS/card slip
  // pattern: the word SALE together with at least two card-transaction signals. Conservative, so an
  // ordinary invoice that merely shows a payment line is not misread as a receipt.
  private static final Pattern FISCAL_TITLE =
      Pattern.compile("(?iuU)(фискален бон|касова бележка|касов бон)");
  private static final Pattern POS_SALE =
      Pattern.compile("(?iuU)(?<![\\p{L}])(sale|продажба)(?![\\p{L}])");
  private static final Pattern[] POS_CARD_SIGNALS = {
    Pattern.compile("(?iuU)master\\s?card|maestro"),
    Pattern.compile("(?iuU)(?<![\\p{L}])visa(?![\\p{L}])"),
    Pattern.compile("(?iuU)(?<![\\p{L}])mid(?![\\p{L}])"),
    Pattern.compile("(?iuU)(?<![\\p{L}])tid(?![\\p{L}])"),
    Pattern.compile("(?iuU)(?<![\\p{L}])aid(?![\\p{L}])"),
    Pattern.compile("\\d{4,6}\\s?\\*{2,}\\s?\\d{2,4}|\\*{4,}") // masked PAN
  };

  // Recognized non-invoice document types → draft Move (conservative; ambiguous stays "review").
  private static final String[] KNOWN_NON_INVOICE = {
    "товарителн", // товарителница (delivery note)
    "приемо-предавател", // приемо-предавателен протокол
    "приемо предавател",
    "протокол", // protocol
    "акт за приемане", // acceptance report
    "складова разписка"
  };

  // Personal / HR documents that are NOT accounting documents and must be SKIPPED entirely (no
  // Invoice, Move or Review). Matched anywhere on the page (a leave request states its title
  // mid-page,
  // not in the letterhead). These phrases never occur on a supplier invoice, so the whole-page scan
  // is safe.
  private static final String[] SKIP_DOCUMENT_MARKERS = {
    "болничен лист", // sick-leave certificate
    "временна неработоспособност", // "for temporary incapacity" (sick leave)
    "заявление за отпуск", // leave request
    "молба за отпуск", // leave request (older wording)
    "искане за отпуск",
    "разрешение за отпуск"
  };

  private final ObjectMapper mapper;

  public BgOcrPdfSegmenter() {
    this.mapper = new ObjectMapper();
  }

  public BgOcrPdfSegmenter(ObjectMapper mapper) {
    this.mapper = mapper != null ? mapper : new ObjectMapper();
  }

  /** Parse + segment. Returns an empty no-split plan on any parse problem (caller keeps 1 doc). */
  public SegmentPlan segment(String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty()) {
      return new SegmentPlan(new ArrayList<>(), false, 0, false);
    }
    JsonNode root;
    try {
      root = mapper.readTree(rawJson);
    } catch (Exception e) {
      return new SegmentPlan(new ArrayList<>(), false, 0, false);
    }
    if (root == null || root.isNull()) {
      return new SegmentPlan(new ArrayList<>(), false, 0, false);
    }

    List<PageInfo> pages = readPages(root);
    int pageCount = pages.isEmpty() ? 0 : pages.get(pages.size() - 1).pageNumber;

    // Placement comes from the page TEXT (keyword + nearby 10-digit number), because Azure often
    // MERGES several invoices into a single documents[] entry whose fields mix invoices and whose
    // boundingRegions span every page. documents[] is used only to ENRICH text anchors when Azure
    // produced exactly one clean document per detected invoice — never to place them.
    List<Anchor> textAnchors = scanTextAnchors(pages);
    List<Anchor> docs = readDocuments(root, pages);
    List<Anchor> anchors = resolveAnchors(textAnchors, docs);
    // Recover invoices whose "Фактура" title was not legible to the text scan (e.g. a
    // fiscal-receipt
    // layout) but that Azure surfaced with strong structured evidence (invoice number + total +
    // VAT).
    // Never anchors a stock/known/HR-skip/card-slip document.
    addStructuredInvoiceAnchors(anchors, docs, pages);

    List<DocSegment> segments = buildSegments(pages, anchors, docs);

    // Page ranges are final only now, so this is where each segment's line items are narrowed to
    // the pages it actually keeps — the duplicate copy Azure merged into the same document is
    // dropped here, before any total is interpreted or any child is created.
    scopeResultsToRetainedPages(segments);

    // Give each stock-receipt segment a comparable profile (total + items) from its covering Azure
    // document, so the split step can match receipts to invoices by CONTENT rather than position.
    enrichReceiptResults(segments, pages, docs);

    long recordSegs =
        segments.stream()
            .filter(s -> s.type == Type.INVOICE || s.type == Type.MOVE || s.type == Type.REVIEW)
            .count();
    boolean hasSupporting =
        segments.stream().anyMatch(s -> s.type == Type.STOCK_RECEIPT || s.type == Type.POS_RECEIPT);
    // A duplicate physical COPY of the invoice (a repeated title page) means the source PDF holds
    // more than the invoice's own page(s); it MUST be split so the invoice keeps only its own slice
    // and the copy is dropped — even when there is no stock/POS receipt and only one record.
    boolean hasDuplicateCopy = hasDuplicateCopyPage(pages, anchors);
    // Split only for a genuine multi-document PDF that contains at least one invoice. A lone
    // invoice
    // (or a PDF with no invoice at all) stays on today's single-document path.
    boolean hasInvoiceAnchor = !anchors.isEmpty();
    boolean needsSplit = hasInvoiceAnchor && (recordSegs >= 2 || hasSupporting || hasDuplicateCopy);

    return new SegmentPlan(segments, needsSplit, pageCount, hasInvoiceAnchor);
  }

  /**
   * True when a non-anchor page repeats, in its header, an invoice keyword + an already-anchored
   * invoice number — i.e. a physical COPY of an invoice already placed. Used to force a split so
   * the invoice never keeps the copy pages, independently of the boundary walk.
   */
  private boolean hasDuplicateCopyPage(List<PageInfo> pages, List<Anchor> anchors) {
    Set<String> anchorNumbers = new HashSet<>();
    Set<Integer> anchorPages = new HashSet<>();
    for (Anchor a : anchors) {
      if (a.normalizedNo != null) {
        anchorNumbers.add(a.normalizedNo);
      }
      anchorPages.add(a.page);
    }
    if (anchorNumbers.isEmpty()) {
      return false;
    }
    for (PageInfo p : pages) {
      if (anchorPages.contains(p.pageNumber)) {
        continue;
      }
      String headerNo = headerInvoiceNumber(p);
      if (headerNo != null && anchorNumbers.contains(headerNo)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Boundary walk (A3c)
  // ---------------------------------------------------------------------------

  private List<DocSegment> buildSegments(
      List<PageInfo> pages, List<Anchor> anchors, List<Anchor> docs) {
    List<DocSegment> out = new ArrayList<>();
    DocSegment openInvoice = null;
    DocSegment lastInvoice = null; // most recent invoice, for supporting-receipt attachment
    DocSegment openTail = null; // open supporting/MOVE/REVIEW run
    List<PageInfo> pending = new ArrayList<>(); // blank/ambiguous pages held while nothing is open

    // A page inherits its covering Azure document's type: the first page of a following document
    // (e.g. a delivery-note letterhead) is recognized as belonging to that document even when its
    // own header carries no marker — so it is NOT absorbed into the preceding invoice.
    Map<Integer, PageKind> docKind = classifyByDocument(pages, docs);

    // Invoice numbers already placed as anchors — used to recognize a repeated invoice TITLE page
    // as
    // a physical COPY of an earlier invoice (see the duplicate-copy handling below).
    Set<String> anchorNumbers = new HashSet<>();
    for (Anchor a : anchors) {
      if (a.normalizedNo != null) {
        anchorNumbers.add(a.normalizedNo);
      }
    }
    // A "drop run": pages of a duplicate invoice copy are swallowed here and never emitted, so the
    // original invoice's PDF keeps only its own pages.
    DocSegment openDrop = null;

    // Receipt numbers already placed as STOCK_RECEIPT segments. A stock-receipt title page whose
    // number repeats one already seen is a physical COPY → dropped (kept only once), so a receipt
    // and its duplicate copies collapse to a single attachment.
    Set<String> receiptNumbersSeen = new HashSet<>();

    for (PageInfo page : pages) {
      // A recognized personal/HR document (sick-leave / leave-request) is SKIPPED entirely: end any
      // open run and drop the page so it never becomes an Invoice, Move or Review.
      if (isSkipPage(page)) {
        if (openInvoice != null) {
          openInvoice.toPage = page.pageNumber - 1;
          openInvoice = null;
        }
        openTail = null;
        openDrop = null;
        pending.clear();
        continue;
      }

      List<Anchor> onPage = anchorsOnPage(anchors, page.pageNumber);

      if (!onPage.isEmpty()) {
        openTail = null;
        openDrop = null;
        if (openInvoice != null) {
          openInvoice.toPage = page.pageNumber - 1;
          openInvoice = null;
        }
        if (onPage.size() >= 2) {
          // Two (or more) invoices on one physical page → crop bands, anchor-to-next-anchor.
          onPage.sort((a, b) -> Double.compare(a.yTop, b.yTop));
          for (int i = 0; i < onPage.size(); i++) {
            Anchor a = onPage.get(i);
            double bandTop = Math.max(0, a.yTop - 0.1 * page.height);
            double bandBottom = (i + 1 < onPage.size()) ? onPage.get(i + 1).yTop : page.height;
            DocSegment seg = new DocSegment();
            seg.type = Type.INVOICE;
            seg.fromPage = page.pageNumber;
            seg.toPage = page.pageNumber;
            seg.normalizedInvoiceNo = a.normalizedNo;
            seg.cropBbox = new double[] {0, bandTop, page.width, bandBottom};
            seg.cropPageW = page.width;
            seg.cropPageH = page.height;
            seg.sourceAnchor = a;
            seg.mappedResult = buildInvoiceResult(a, bandText(page, bandTop, bandBottom));
            out.add(seg);
            lastInvoice = seg;
          }
        } else {
          Anchor a = onPage.get(0);
          DocSegment seg = new DocSegment();
          seg.type = Type.INVOICE;
          seg.fromPage = page.pageNumber;
          seg.toPage = page.pageNumber;
          seg.normalizedInvoiceNo = a.normalizedNo;
          seg.sourceAnchor = a;
          seg.mappedResult = buildInvoiceResult(a, page.text());
          out.add(seg);
          lastInvoice = seg;
          // A page that is BOTH a valid invoice anchor AND a POS/card slip is a single-page fiscal
          // invoice: keep it as the invoice (anchor wins) but do NOT let it swallow the following
          // supporting receipts. A normal invoice stays open to absorb its own continuation pages.
          openInvoice = isCardSlip(page) ? null : seg;
        }
        pending.clear();
        continue;
      }

      // A repeated invoice TITLE page (invoice keyword + an already-detected invoice number in its
      // header) is a physical COPY of an earlier invoice — NOT a continuation. End the open invoice
      // and DROP the copy (no record, not absorbed) so the invoice PDF keeps only its own pages;
      // following continuation pages of the copy are swallowed until the next real boundary. The
      // full multi-page source stays available on the carrier in Upload Invoices.
      String headerNo = headerInvoiceNumber(page);
      if (headerNo != null && anchorNumbers.contains(headerNo)) {
        if (openInvoice != null) {
          openInvoice.toPage = page.pageNumber - 1;
          openInvoice = null;
        }
        openTail = null;
        pending.clear();
        openDrop = new DocSegment();
        openDrop.fromPage = page.pageNumber;
        openDrop.toPage = page.pageNumber;
        continue;
      }

      // No invoice anchor on this page. Resolve its kind: a truly blank page is a hint; a
      // POS/fiscal
      // receipt or a stock-receipt/known-type marker (its own header OR inherited from its covering
      // Azure document) starts a non-invoice run; anything else is ambiguous CONTENT.
      PageKind pageKind = classify(page);
      PageKind inherited = docKind.get(page.pageNumber);
      PageKind kind;
      if (pageKind == PageKind.BLANK) {
        kind = PageKind.BLANK;
      } else if (isPosReceipt(page)) {
        kind = PageKind.POS;
      } else if (inherited != null) {
        kind = inherited;
      } else {
        kind = pageKind;
      }
      switch (kind) {
        case POS:
          openDrop = null;
          if (openInvoice != null) {
            openInvoice.toPage = page.pageNumber - 1;
            openInvoice = null;
          }
          // A page that LOOKS like a fiscal receipt (ФИСКАЛЕН БОН / card slip) but whose covering
          // Azure document is actually a DISTINCT invoice — a different supplier EIK/VAT, a
          // different
          // document number, or a clearly incompatible total — must NOT be attached to the
          // preceding
          // invoice as its receipt. Route it as its own invoice when it carries enough evidence,
          // else preserve it as a review record; never silently attach a real invoice to the wrong
          // one. Only when its identity is compatible with (or gives no evidence against) the
          // preceding invoice is it treated as that invoice's supporting receipt.
          {
            Anchor cover = singleCoveringDoc(page.pageNumber, docs);
            DistinctDoc distinct = classifyReceiptVsInvoice(cover, lastInvoice);
            if (distinct != DistinctDoc.NONE) {
              openTail = null;
              DocSegment seg = new DocSegment();
              seg.type = distinct == DistinctDoc.INVOICE ? Type.INVOICE : Type.REVIEW;
              seg.fromPage = page.pageNumber;
              seg.toPage = page.pageNumber;
              seg.normalizedInvoiceNo = cover.normalizedNo;
              seg.sourceAnchor = cover;
              seg.mappedResult = buildInvoiceResult(cover, page.text());
              out.add(seg);
              if (distinct == DistinctDoc.INVOICE) {
                lastInvoice = seg; // a confident invoice can anchor its own following receipts
              }
              pending.clear();
              break;
            }
          }
          // POS/fiscal receipt: attached to the preceding invoice by adjacency (a card slip belongs
          // to its fiscal invoice). With no preceding invoice it becomes a reviewable record.
          openTail =
              extendSupporting(
                  out,
                  openTail,
                  lastInvoice == null ? Type.REVIEW : Type.POS_RECEIPT,
                  page,
                  pending);
          pending.clear();
          break;
        case STOCK:
          {
            if (openInvoice != null) {
              openInvoice.toPage = page.pageNumber - 1;
              openInvoice = null;
            }
            String rno = headerReceiptNumber(page); // number when this is a receipt TITLE page
            // A repeated receipt title (a number already placed) is a physical COPY: end the open
            // receipt run and DROP the copy + its continuation, so a receipt and its copies
            // collapse
            // to one attachment. The full source stays on the carrier in Upload Invoices.
            if (rno != null && receiptNumbersSeen.contains(rno)) {
              openTail = null;
              pending.clear();
              openDrop = new DocSegment();
              openDrop.fromPage = page.pageNumber;
              openDrop.toPage = page.pageNumber;
              break;
            }
            openDrop = null;
            // A stock receipt is NEVER routed as its own record: it is always a STOCK_RECEIPT
            // segment, matched to an invoice later by CONTENT (total + item overlap) — a receipt
            // may
            // even precede its invoice. Unmatched receipts are handled at attach time (preserved +
            // flagged for review), never turned into an invoice/move here.
            openTail = extendSupporting(out, openTail, Type.STOCK_RECEIPT, page, pending);
            pending.clear();
            if (rno != null) {
              receiptNumbersSeen.add(rno);
              if (openTail.type == Type.STOCK_RECEIPT && openTail.receiptNumber == null) {
                openTail.receiptNumber = rno;
              }
            }
            break;
          }
        case KNOWN:
          openDrop = null;
          if (openInvoice != null) {
            openInvoice.toPage = page.pageNumber - 1;
            openInvoice = null;
          }
          flushPending(out, pending);
          openTail = extendOrNew(out, openTail, Type.MOVE, page);
          break;
        case BLANK:
          if (openInvoice != null) {
            openInvoice.toPage = page.pageNumber; // blank stays inside the open invoice (hint only)
          } else if (openTail != null) {
            openTail.toPage = page.pageNumber; // ...or the open non-invoice run
          } else if (openDrop != null) {
            openDrop.toPage = page.pageNumber; // ...or swallowed into the dropped duplicate copy
          } else {
            pending.add(page); // held: joins a following supporting run, else dropped
          }
          break;
        case CONTENT:
        default:
          if (openInvoice != null) {
            openInvoice.toPage = page.pageNumber; // continuation of the open invoice
          } else if (openTail != null) {
            openTail.toPage = page.pageNumber; // continuation of the open non-invoice document
            appendText(openTail.mappedResult, page.text());
          } else if (openDrop != null) {
            openDrop.toPage = page.pageNumber; // continuation of the dropped duplicate copy
          } else {
            pending.add(page); // ambiguous, nothing open: held pending a following supporting run
          }
          break;
      }
    }
    if (openInvoice != null && !pages.isEmpty()) {
      openInvoice.toPage = pages.get(pages.size() - 1).pageNumber;
    }
    flushPending(out, pending);
    return out;
  }

  /**
   * Start or extend a supporting-receipt run (POS/fiscal or stock) attached to the preceding
   * invoice. Any {@code pending} blank/ambiguous pages held since the invoice are folded into the
   * run's front, so e.g. a blank + a cover page that precede the receipts belong to the receipt
   * attachment rather than the invoice.
   */
  private DocSegment extendSupporting(
      List<DocSegment> out, DocSegment open, Type type, PageInfo page, List<PageInfo> pending) {
    if (open != null && open.type == type && open.toPage >= page.pageNumber - 1 - pending.size()) {
      open.toPage = page.pageNumber;
      return open;
    }
    DocSegment seg = new DocSegment();
    seg.type = type;
    seg.fromPage = pending.isEmpty() ? page.pageNumber : pending.get(0).pageNumber;
    seg.toPage = page.pageNumber;
    if (type == Type.REVIEW) {
      seg.mappedResult = new OcrExtractResult();
      seg.mappedResult.setRawText(page.text());
    }
    out.add(seg);
    return seg;
  }

  /** Flush held pending pages: drop blanks, and emit any content as a single To-review segment. */
  private void flushPending(List<DocSegment> out, List<PageInfo> pending) {
    if (pending.isEmpty()) {
      return;
    }
    int from = -1;
    int to = -1;
    StringBuilder text = new StringBuilder();
    for (PageInfo p : pending) {
      if (collapse(p.text()).length() >= 5) { // non-blank
        if (from < 0) {
          from = p.pageNumber;
        }
        to = p.pageNumber;
        text.append(p.text()).append('\n');
      }
    }
    pending.clear();
    if (from < 0) {
      return; // only blanks — dropped
    }
    DocSegment seg = new DocSegment();
    seg.type = Type.REVIEW;
    seg.fromPage = from;
    seg.toPage = to;
    seg.mappedResult = new OcrExtractResult();
    seg.mappedResult.setRawText(text.toString());
    out.add(seg);
  }

  /**
   * Start a new non-invoice run of {@code type} at {@code page}, or extend {@code open} when it is
   * the same type and immediately precedes this page. STOCK_RECEIPT runs carry no OCR result (they
   * attach to the preceding invoice); MOVE/REVIEW runs accumulate the segment text.
   */
  private DocSegment extendOrNew(List<DocSegment> out, DocSegment open, Type type, PageInfo page) {
    if (open != null && open.type == type && open.toPage == page.pageNumber - 1) {
      open.toPage = page.pageNumber;
      appendText(open.mappedResult, page.text());
      return open;
    }
    DocSegment seg = new DocSegment();
    seg.type = type;
    seg.fromPage = page.pageNumber;
    seg.toPage = page.pageNumber;
    if (type != Type.STOCK_RECEIPT) {
      seg.mappedResult = new OcrExtractResult();
      seg.mappedResult.setRawText(page.text());
    }
    out.add(seg);
    return seg;
  }

  // ---------------------------------------------------------------------------
  // Per-segment OcrExtractResult (A3d)
  // ---------------------------------------------------------------------------

  private OcrExtractResult buildInvoiceResult(Anchor anchor, String segmentText) {
    return buildInvoiceResult(anchor, segmentText, null, null);
  }

  /**
   * Re-map every segment's structured result now that its page range is final, keeping only the
   * Azure line items located on the pages the segment actually retains.
   *
   * <p>This cannot be done while the segments are being built: {@code toPage} still moves
   * afterwards — a continuation page extends it, a repeated title page closes it one page earlier —
   * so at mapping time the retained range is not yet known. It matters because a duplicate physical
   * copy makes Azure return ONE document covering both copies, whose {@code Items} therefore hold
   * every line twice against a single header total. Filtering by the item's own page is exact; the
   * dropped copy's pages belong to no emitted segment, so nothing real is ever excluded.
   *
   * <p>The accumulated segment text is preserved as-is — it is already page-scoped, and a
   * continuation page appended to it must stay.
   */
  private void scopeResultsToRetainedPages(List<DocSegment> segments) {
    for (DocSegment seg : segments) {
      if (seg.sourceAnchor == null || seg.mappedResult == null) {
        continue;
      }
      String text = seg.mappedResult.getRawText();
      seg.mappedResult = buildInvoiceResult(seg.sourceAnchor, text, seg.fromPage, seg.toPage);
    }
  }

  private OcrExtractResult buildInvoiceResult(
      Anchor anchor, String segmentText, Integer fromPage, Integer toPage) {
    OcrExtractResult r = new OcrExtractResult();
    if (anchor.fields != null && !anchor.fields.isNull()) {
      // Structured invoice fields from THIS document only — never document[0] copied to all, and
      // only the line items this segment's own pages carry.
      AzureDocumentIntelligenceExtractor.mapDocumentFields(r, anchor.fields, fromPage, toPage);
      // Confidence = the mean of critical per-field extraction confidences (the same metric the
      // full-document carrier reports), NOT Azure's documents[].confidence (anchor.confidence),
      // which is the document-TYPE classification score — routinely 1.0 and thus a misleading 100%.
      r.setConfidence(AzureDocumentIntelligenceExtractor.overallConfidencePercent(r));
    }
    r.setRawText(segmentText);
    // Ensure the invoice number is present even for a text-only anchor (multi-invoice single doc):
    // the segment-scoped text may defeat invoiceNoFromText's single-candidate rule, so seed it
    // here.
    if (isBlank(r.getInvoiceNumber()) && anchor.rawNumber != null) {
      r.setInvoiceNumber(anchor.rawNumber);
    }
    return r;
  }

  /**
   * Build a comparable {@link OcrExtractResult} (total + line items + segment text) for every
   * STOCK_RECEIPT segment, from the Azure {@code documents[]} entry that most covers its page
   * range. A receipt's own document number is captured on the segment; a matching total and
   * overlapping items to an invoice are then computed downstream. Never turns the receipt into a
   * record.
   */
  private void enrichReceiptResults(
      List<DocSegment> segments, List<PageInfo> pages, List<Anchor> docs) {
    Map<Integer, PageInfo> byPage = new HashMap<>();
    for (PageInfo p : pages) {
      byPage.put(p.pageNumber, p);
    }
    for (DocSegment seg : segments) {
      if (seg.type != Type.STOCK_RECEIPT) {
        continue;
      }
      OcrExtractResult r = new OcrExtractResult();
      Anchor cover = bestCoveringDoc(docs, seg.fromPage, seg.toPage);
      if (cover != null && cover.fields != null && !cover.fields.isNull()) {
        // Same page scoping as an invoice segment: a receipt printed twice would otherwise give
        // this profile a doubled item list, and the item overlap is what matches it to an invoice.
        AzureDocumentIntelligenceExtractor.mapDocumentFields(
            r, cover.fields, seg.fromPage, seg.toPage);
      }
      StringBuilder text = new StringBuilder();
      for (int pn = seg.fromPage; pn <= seg.toPage; pn++) {
        PageInfo pi = byPage.get(pn);
        if (pi != null) {
          text.append(pi.text()).append('\n');
        }
      }
      r.setRawText(text.toString());
      seg.mappedResult = r;
    }
  }

  /** The {@code documents[]} anchor whose page span overlaps [from,to] the most (null if none). */
  private static Anchor bestCoveringDoc(List<Anchor> docs, int from, int to) {
    Anchor best = null;
    int bestOverlap = 0;
    if (docs != null) {
      for (Anchor d : docs) {
        int overlap = Math.min(to, d.pageMax) - Math.max(from, d.pageMin) + 1;
        if (overlap > bestOverlap) {
          bestOverlap = overlap;
          best = d;
        }
      }
    }
    return best;
  }

  private static void appendText(OcrExtractResult r, String more) {
    if (r == null || more == null || more.isEmpty()) {
      return;
    }
    String prev = r.getRawText();
    r.setRawText((prev == null || prev.isEmpty()) ? more : prev + "\n" + more);
  }

  // ---------------------------------------------------------------------------
  // Page classification
  // ---------------------------------------------------------------------------

  private enum PageKind {
    STOCK,
    POS,
    KNOWN,
    SKIP, // a recognized personal/HR document (sick-leave / leave-request) → no record at all
    BLANK,
    CONTENT
  }

  /**
   * True when the page is a recognized personal/HR document (sick-leave certificate, leave request)
   * that must be skipped completely — never an Invoice, Move or Review. Scans the whole page (the
   * title is often mid-page, e.g. "ЗАЯВЛЕНИЕ ЗА ОТПУСК" under the company letterhead).
   */
  private boolean isSkipPage(PageInfo page) {
    String text = collapse(page.text());
    if (text.isEmpty()) {
      return false;
    }
    for (String marker : SKIP_DOCUMENT_MARKERS) {
      if (text.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Classify pages by their covering Azure {@code documents[]} entry: if ANY page in a document has
   * a stock-receipt / known-non-invoice marker in its header, EVERY page of that document inherits
   * that kind. This lets a document's marker-less first page (a letterhead) be recognized as part
   * of that document rather than absorbed into the preceding invoice. Invoice documents (no such
   * marker) are left unmarked, so multi-page invoices and blank pages are unaffected.
   */
  private Map<Integer, PageKind> classifyByDocument(List<PageInfo> pages, List<Anchor> docs) {
    Map<Integer, PageKind> byPage = new HashMap<>();
    if (docs == null || docs.isEmpty()) {
      return byPage;
    }
    Map<Integer, PageInfo> pageByNumber = new HashMap<>();
    for (PageInfo p : pages) {
      pageByNumber.put(p.pageNumber, p);
    }
    for (Anchor doc : docs) {
      PageKind kind = null; // stock wins over known
      for (int pn = doc.pageMin; pn <= doc.pageMax; pn++) {
        PageInfo p = pageByNumber.get(pn);
        if (p == null) {
          continue;
        }
        String header = collapse(headerText(p, 3));
        if (header.contains(STOCK_RECEIPT)) {
          kind = PageKind.STOCK;
          break;
        }
        if (kind == null && matchesKnownType(header)) {
          kind = PageKind.KNOWN;
        }
      }
      if (kind != null) {
        for (int pn = doc.pageMin; pn <= doc.pageMax; pn++) {
          byPage.put(pn, kind);
        }
      }
    }
    return byPage;
  }

  private static boolean matchesKnownType(String header) {
    for (String kw : KNOWN_NON_INVOICE) {
      if (header.contains(kw)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A POS / card slip: the word SALE together with at least two card-transaction signals (MASTER
   * CARD/VISA/MID/TID/AID/masked-PAN). This is the STRONG signal — specific enough that an ordinary
   * invoice with a single card-payment line does not match. Used to decide that an invoice-anchored
   * page is a one-page fiscal invoice (so it does not swallow the following supporting receipts).
   */
  private boolean isCardSlip(PageInfo page) {
    String text = page.text();
    if (text == null || text.isEmpty() || !POS_SALE.matcher(text).find()) {
      return false;
    }
    int signals = 0;
    for (Pattern p : POS_CARD_SIGNALS) {
      if (p.matcher(text).find()) {
        signals++;
      }
    }
    return signals >= 2;
  }

  /**
   * A POS / fiscal receipt page: a card slip (above) OR an explicit fiscal-receipt <b>title</b>
   * that appears as a short standalone line (not the phrase buried in invoice boilerplate such as
   * "…не замества касова бележка"), so ordinary invoices are not misread as receipts.
   */
  private boolean isPosReceipt(PageInfo page) {
    if (isCardSlip(page)) {
      return true;
    }
    for (LineInfo l : page.lines) {
      String lc = collapse(l.content);
      if (lc.length() <= 25 && FISCAL_TITLE.matcher(lc).find()) {
        return true;
      }
    }
    return false;
  }

  private PageKind classify(PageInfo page) {
    String norm = collapse(page.text());
    if (norm.isEmpty() || norm.length() < 5) {
      return PageKind.BLANK;
    }
    // Match the document-type marker only in the page HEADER (its title area), not anywhere in the
    // body — so a keyword buried inside an invoice body never spuriously splits a single invoice.
    String header = collapse(headerText(page, 3));
    if (header.contains(STOCK_RECEIPT)) {
      return PageKind.STOCK;
    }
    for (String kw : KNOWN_NON_INVOICE) {
      if (header.contains(kw)) {
        return PageKind.KNOWN;
      }
    }
    return PageKind.CONTENT;
  }

  /** Header line budget for detecting a repeated invoice TITLE (a duplicate-copy page). */
  private static final int TITLE_HEADER_LINES = 6;

  /**
   * The normalized 10-digit invoice number printed in the page's HEADER (title area) together with
   * an invoice keyword there — i.e., an invoice TITLE page. A continuation page of a multi-page
   * invoice (which does not repeat the title) yields no keyword in its header and returns {@code
   * null}, so it is never mistaken for a copy. Used only to recognize a physical COPY of an
   * already-anchored invoice.
   */
  private String headerInvoiceNumber(PageInfo page) {
    StringBuilder header = new StringBuilder();
    int used = 0;
    for (LineInfo l : page.lines) {
      if (l.content == null || l.content.trim().isEmpty()) {
        continue;
      }
      header.append(l.content).append('\n');
      if (++used >= TITLE_HEADER_LINES) {
        break;
      }
    }
    String h = header.toString();
    if (!INVOICE_KEYWORD.matcher(h).find()) {
      return null;
    }
    Matcher m = INVOICE_NUMBER_10.matcher(h);
    if (!m.find()) {
      return null;
    }
    return BgOcrIntakeService.normalizeInvoiceNo(m.group(1));
  }

  /**
   * The stock receipt's own document number when this page is a "Стокова разписка" TITLE page (its
   * header carries the stock-receipt marker AND a 6+ digit number, e.g. {@code No: 9000121609}).
   * Returns {@code null} for a non-title page (a receipt continuation/letterhead, or a non-receipt
   * page). Used to collapse a receipt's duplicate physical copies to one attachment.
   */
  private String headerReceiptNumber(PageInfo page) {
    String header = collapse(headerText(page, TITLE_HEADER_LINES));
    if (!header.contains(STOCK_RECEIPT)) {
      return null;
    }
    Matcher m = RECEIPT_NUMBER.matcher(header);
    return m.find() ? m.group(1) : null;
  }

  /** The first {@code maxLines} non-empty lines of the page (its title/header area). */
  private static String headerText(PageInfo page, int maxLines) {
    StringBuilder sb = new StringBuilder();
    int used = 0;
    for (LineInfo l : page.lines) {
      if (l.content == null || l.content.trim().isEmpty()) {
        continue;
      }
      sb.append(l.content).append('\n');
      if (++used >= maxLines) {
        break;
      }
    }
    return sb.toString();
  }

  private static String collapse(String s) {
    if (s == null) {
      return "";
    }
    return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Anchor discovery
  // ---------------------------------------------------------------------------

  private static List<Anchor> anchorsOnPage(List<Anchor> anchors, int page) {
    List<Anchor> out = new ArrayList<>();
    for (Anchor a : anchors) {
      if (a.page == page) {
        out.add(a);
      }
    }
    return out;
  }

  /**
   * Scan page TEXT for invoice anchors. A page is an invoice page when it carries the standalone
   * keyword ({@code Фактура}/{@code Ф-ра}/{@code Фак.}) AND a standalone 10-digit number. The
   * number label and the keyword are frequently far apart on the page (title top-left, number in a
   * top box), so the number is chosen page-wide as the standalone 10-digit NEAREST (by line
   * distance) to a keyword line, preferring a line that also carries an invoice-number label
   * ({@code № / No / Номер}). At most one anchor per page (the invoice's own number); de-duplicated
   * across the PDF by normalized number so an invoice's original + copy pages collapse to one
   * anchor.
   */
  private List<Anchor> scanTextAnchors(List<PageInfo> pages) {
    List<Anchor> anchors = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (PageInfo page : pages) {
      // A page whose HEADER is a stock-receipt / known-non-invoice title is NOT an invoice page,
      // even if its body references "по фактура № …". Suppress anchoring there so a delivery note /
      // stock receipt is never mistaken for an invoice.
      PageKind headerKind = classify(page);
      if (headerKind == PageKind.STOCK || headerKind == PageKind.KNOWN || isSkipPage(page)) {
        continue;
      }
      List<LineInfo> lines = page.lines;
      List<Integer> keywordLines = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        String c = lines.get(i).content;
        if (c != null && INVOICE_KEYWORD.matcher(c).find()) {
          keywordLines.add(i);
        }
      }
      if (keywordLines.isEmpty()) {
        continue; // not an invoice page
      }
      // Collect standalone 10-digit candidates with their line index + whether the line is
      // labelled.
      int bestLine = -1;
      String bestRaw = null;
      int bestDist = Integer.MAX_VALUE;
      boolean bestLabelled = false;
      for (int i = 0; i < lines.size(); i++) {
        String c = lines.get(i).content;
        if (c == null) {
          continue;
        }
        Matcher m = INVOICE_NUMBER_10.matcher(c);
        boolean labelled = NUMBER_LABEL.matcher(c).find();
        while (m.find()) {
          int dist = Integer.MAX_VALUE;
          for (int k : keywordLines) {
            dist = Math.min(dist, Math.abs(i - k));
          }
          // Prefer: a labelled line; then nearer to the keyword; then higher on the page.
          if (bestRaw == null
              || (labelled && !bestLabelled)
              || (labelled == bestLabelled && dist < bestDist)) {
            bestRaw = m.group(1);
            bestDist = dist;
            bestLabelled = labelled;
            bestLine = i;
          }
        }
      }
      if (bestRaw == null) {
        continue; // keyword but no invoice-number-like value → not a reliable anchor
      }
      String norm = BgOcrIntakeService.normalizeInvoiceNo(bestRaw);
      if (norm == null || seen.contains(norm)) {
        continue;
      }
      seen.add(norm);
      Anchor a = new Anchor();
      a.page = page.pageNumber;
      a.pageMin = page.pageNumber;
      a.pageMax = page.pageNumber;
      a.yTop = lines.get(bestLine).yTop;
      a.fields = null;
      a.rawNumber = bestRaw;
      a.normalizedNo = norm;
      anchors.add(a);
    }
    return anchors;
  }

  /** Read {@code documents[]} as candidate anchors (fields + number + first page). */
  private List<Anchor> readDocuments(JsonNode root, List<PageInfo> pages) {
    List<Anchor> out = new ArrayList<>();
    JsonNode docs = root.get("documents");
    if (docs == null || !docs.isArray()) {
      return out;
    }
    for (JsonNode doc : docs) {
      JsonNode fields = doc.get("fields");
      int firstPage = Integer.MAX_VALUE;
      int lastPage = 0;
      double yTop = 0;
      JsonNode regions = doc.get("boundingRegions");
      if (regions != null && regions.isArray()) {
        for (JsonNode reg : regions) {
          int pn = reg.path("pageNumber").asInt(0);
          if (pn > 0 && pn < firstPage) {
            firstPage = pn;
            yTop = polygonMinY(reg.get("polygon"));
          }
          if (pn > lastPage) {
            lastPage = pn;
          }
        }
      }
      if (firstPage == Integer.MAX_VALUE) {
        firstPage = pages.isEmpty() ? 1 : pages.get(0).pageNumber;
      }
      if (lastPage < firstPage) {
        lastPage = firstPage;
      }
      OcrExtractResult probe = new OcrExtractResult();
      if (fields != null) {
        AzureDocumentIntelligenceExtractor.mapDocumentFields(probe, fields);
      }
      Anchor a = new Anchor();
      a.page = firstPage;
      a.pageMin = firstPage;
      a.pageMax = lastPage;
      a.yTop = yTop;
      a.fields = fields;
      a.confidence = doc.has("confidence") ? doc.get("confidence").asDouble() : null;
      a.rawNumber = probe.getInvoiceNumber();
      a.normalizedNo = BgOcrIntakeService.normalizeInvoiceNo(probe.getInvoiceNumber());
      a.strongInvoice = isStrongInvoiceDoc(probe);
      out.add(a);
    }
    return out;
  }

  /**
   * Strong structured invoice evidence: a real invoice number AND a positive gross total. This is
   * what a full supplier invoice always carries (even one printed in a fiscal-receipt layout
   * without a legible "Фактура" title), and what a bare POS card-slip, a sick-leave note or a leave
   * request never carry — so it recognizes a partially-covered invoice header without turning
   * non-invoices into invoices.
   *
   * <p>The VAT/base breakdown is deliberately NOT required: on a real fiscal-receipt-format invoice
   * (e.g. an in-store electronics or fuel purchase) Azure sometimes returns only the invoice number
   * and the total, dropping SubTotal/TotalTax. Requiring all three previously let such an invoice
   * fall through to the "ФИСКАЛЕН БОН" POS path and be swallowed as a receipt attached to the
   * PREVIOUS (unrelated) invoice — a whole standalone invoice lost. A number + a positive gross is
   * enough to anchor it as its own invoice; the callsite still vetoes stock receipts, recognized
   * non-invoice types, HR/skip pages and genuine card-slips via {@link #docLooksNonInvoice}.
   */
  private static boolean isStrongInvoiceDoc(OcrExtractResult probe) {
    return probe != null
        && probe.getInvoiceNumber() != null
        && !probe.getInvoiceNumber().trim().isEmpty()
        && probe.getTotalGross() != null
        && probe.getTotalGross().signum() > 0;
  }

  /** How a would-be POS/receipt page relates to the invoice it would be attached to. */
  enum DistinctDoc {
    NONE, // compatible / no contradicting evidence → a genuine supporting receipt
    REVIEW, // a distinct document, but under-evidenced → preserve as a review record
    INVOICE // a distinct document with its own number + total → its own invoice
  }

  /** The single {@code documents[]} entry covering {@code page}, or null when none/several do. */
  private static Anchor singleCoveringDoc(int page, List<Anchor> docs) {
    Anchor found = null;
    for (Anchor d : docs) {
      if (page >= d.pageMin && page <= d.pageMax) {
        if (found != null) {
          return null; // ambiguous — more than one document covers this page
        }
        found = d;
      }
    }
    return found;
  }

  /**
   * Decide whether a page about to be attached to {@code lastInvoice} as its POS/fiscal receipt is
   * in fact a DISTINCT invoice — the guard that stops a real invoice (e.g. an in-store purchase
   * printed in fiscal-receipt layout, whose "Фактура" title Azure did not surface) from being
   * silently swallowed as the previous, unrelated invoice's receipt.
   *
   * <p>It compares the page's covering Azure document against the preceding invoice on identity
   * that a genuine supporting receipt would SHARE: supplier EIK, supplier VAT, document number and
   * gross total. A different supplier EIK/VAT, a different document number, or a clearly
   * incompatible total (neither equal nor an EUR↔BGN peg conversion) means it is NOT that invoice's
   * receipt. A real card-slip of the same purchase shares the supplier + total (or carries no
   * identifying fields at all), so it stays {@link DistinctDoc#NONE} and is attached exactly as
   * before. When the page IS distinct it becomes its own {@link DistinctDoc#INVOICE} if it carries
   * a number + positive gross, otherwise a {@link DistinctDoc#REVIEW} record (preserved + flagged,
   * never mis-attached).
   *
   * <p>Deliberately identity-based, not text-based: it never depends on the word "Фактура" being
   * legible, and hard-codes no supplier-specific text.
   */
  private static DistinctDoc classifyReceiptVsInvoice(Anchor cover, DocSegment lastInvoice) {
    if (cover == null || lastInvoice == null || lastInvoice.mappedResult == null) {
      return DistinctDoc.NONE;
    }
    OcrExtractResult prev = lastInvoice.mappedResult;
    OcrExtractResult probe = new OcrExtractResult();
    if (cover.fields != null) {
      AzureDocumentIntelligenceExtractor.mapDocumentFields(probe, cover.fields);
    }
    boolean distinct =
        differs(probe.getSupplierEik(), prev.getSupplierEik())
            || differs(probe.getSupplierVat(), prev.getSupplierVat())
            || differsNumber(cover.normalizedNo, lastInvoice.normalizedInvoiceNo)
            || incompatibleTotal(probe.getTotalGross(), prev.getTotalGross());
    if (!distinct) {
      return DistinctDoc.NONE;
    }
    return isStrongInvoiceDoc(probe) ? DistinctDoc.INVOICE : DistinctDoc.REVIEW;
  }

  /** Two identity strings that are BOTH present and unequal (case/space-insensitive). */
  private static boolean differs(String a, String b) {
    if (isBlank(a) || isBlank(b)) {
      return false;
    }
    return !a.trim().equalsIgnoreCase(b.trim());
  }

  /** Two normalized document numbers that are BOTH present and unequal. */
  private static boolean differsNumber(String a, String b) {
    if (isBlank(a) || isBlank(b)) {
      return false;
    }
    return !a.equals(b);
  }

  /**
   * Two gross totals that are BOTH positive and clearly incompatible — neither equal (within 1%)
   * nor a plausible EUR↔BGN peg conversion (≈1.95583). A receipt of the same purchase carries the
   * same total, so only a genuinely different amount counts as evidence of a different document.
   */
  private static boolean incompatibleTotal(java.math.BigDecimal a, java.math.BigDecimal b) {
    if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) {
      return false;
    }
    java.math.BigDecimal lo = a.min(b);
    java.math.BigDecimal hi = a.max(b);
    java.math.BigDecimal ratio = hi.divide(lo, 4, java.math.RoundingMode.HALF_UP);
    boolean equalish = ratio.compareTo(new java.math.BigDecimal("1.01")) <= 0;
    boolean peg =
        ratio.compareTo(new java.math.BigDecimal("1.90")) >= 0
            && ratio.compareTo(new java.math.BigDecimal("2.02")) <= 0;
    return !equalish && !peg;
  }

  /**
   * Decide the final anchor list. Text anchors (page-placed) are authoritative for WHERE invoices
   * are; a {@code documents[]} entry is used only to ENRICH a text anchor with structured fields,
   * and only when that document covers EXACTLY ONE text anchor. When a single document spans
   * several text anchors (Azure merged multiple invoices — its fields mix them), no enrichment
   * happens and those segments are built from their own segment text (no field leakage; they land
   * "To review").
   */
  private List<Anchor> resolveAnchors(List<Anchor> textAnchors, List<Anchor> docs) {
    if (textAnchors.isEmpty()) {
      // Text detection found nothing (e.g. an unusual layout) — fall back to Azure's own documents.
      return docs;
    }
    for (Anchor t : textAnchors) {
      Anchor covering = null;
      boolean ambiguous = false;
      for (Anchor d : docs) {
        if (t.page >= d.pageMin && t.page <= d.pageMax) {
          if (covering != null) {
            ambiguous = true; // more than one document covers this page — don't trust either
            break;
          }
          covering = d;
        }
      }
      if (ambiguous || covering == null) {
        continue;
      }
      // Only enrich when this document covers a SINGLE invoice (no merge → no field leakage).
      final Anchor doc = covering;
      long anchorsInDoc =
          textAnchors.stream().filter(a -> a.page >= doc.pageMin && a.page <= doc.pageMax).count();
      if (anchorsInDoc == 1) {
        t.fields = doc.fields;
        t.confidence = doc.confidence;
        if (t.rawNumber == null) {
          t.rawNumber = doc.rawNumber;
        }
      }
    }
    return textAnchors;
  }

  /**
   * Add invoice anchors for {@code documents[]} entries with STRONG structured invoice evidence
   * (see {@link #isStrongInvoiceDoc}) whose pages the text scan did not already anchor — so an
   * invoice whose "Фактура" header was not legible (a fiscal-receipt-format invoice) is still
   * placed as its own invoice. A stock-receipt, a recognized non-invoice document, a personal/HR
   * skip page, or a genuine POS card-slip is never anchored here, so receipts stay receipts.
   */
  private void addStructuredInvoiceAnchors(
      List<Anchor> anchors, List<Anchor> docs, List<PageInfo> pages) {
    if (docs == null || docs.isEmpty()) {
      return;
    }
    Map<Integer, PageInfo> byPage = new HashMap<>();
    for (PageInfo p : pages) {
      byPage.put(p.pageNumber, p);
    }
    Set<Integer> anchoredPages = new HashSet<>();
    Set<String> seenNumbers = new HashSet<>();
    for (Anchor a : anchors) {
      for (int pn = a.pageMin; pn <= a.pageMax; pn++) {
        anchoredPages.add(pn);
      }
      if (a.normalizedNo != null) {
        seenNumbers.add(a.normalizedNo);
      }
    }
    for (Anchor d : docs) {
      if (!d.strongInvoice) {
        continue;
      }
      boolean overlaps = false;
      for (int pn = d.pageMin; pn <= d.pageMax; pn++) {
        if (anchoredPages.contains(pn)) {
          overlaps = true;
          break;
        }
      }
      if (overlaps) {
        continue; // a text anchor already placed an invoice on this document's page(s)
      }
      if (d.normalizedNo != null && seenNumbers.contains(d.normalizedNo)) {
        continue; // same invoice number already placed
      }
      if (docLooksNonInvoice(d, byPage)) {
        continue; // stock receipt / known non-invoice / HR skip / POS card-slip → not an invoice
      }
      Anchor a = new Anchor();
      a.page = d.pageMin;
      a.pageMin = d.pageMin;
      a.pageMax = d.pageMax;
      a.yTop = d.yTop;
      a.fields = d.fields;
      a.confidence = d.confidence;
      a.rawNumber = d.rawNumber;
      a.normalizedNo = d.normalizedNo;
      a.strongInvoice = true;
      anchors.add(a);
      for (int pn = d.pageMin; pn <= d.pageMax; pn++) {
        anchoredPages.add(pn);
      }
      if (d.normalizedNo != null) {
        seenNumbers.add(d.normalizedNo);
      }
    }
  }

  /**
   * True when any page of the document is a stock receipt, a recognized non-invoice type, a
   * personal/HR skip page, or a genuine POS card-slip — none of which may be anchored as an invoice
   * on structured evidence alone.
   */
  private boolean docLooksNonInvoice(Anchor doc, Map<Integer, PageInfo> byPage) {
    for (int pn = doc.pageMin; pn <= doc.pageMax; pn++) {
      PageInfo p = byPage.get(pn);
      if (p == null) {
        continue;
      }
      String header = collapse(headerText(p, 3));
      if (header.contains(STOCK_RECEIPT) || matchesKnownType(header)) {
        return true;
      }
      if (isSkipPage(p) || isCardSlip(p)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Azure JSON reading
  // ---------------------------------------------------------------------------

  private List<PageInfo> readPages(JsonNode root) {
    List<PageInfo> out = new ArrayList<>();
    JsonNode pages = root.get("pages");
    if (pages == null || !pages.isArray()) {
      return out;
    }
    for (JsonNode p : pages) {
      PageInfo pi = new PageInfo();
      pi.pageNumber = p.path("pageNumber").asInt(out.size() + 1);
      pi.width = p.path("width").asDouble(0);
      pi.height = p.path("height").asDouble(0);
      JsonNode lines = p.get("lines");
      if (lines != null && lines.isArray()) {
        for (JsonNode ln : lines) {
          LineInfo li = new LineInfo();
          li.content = ln.path("content").asText("");
          li.yTop = polygonMinY(ln.get("polygon"));
          pi.lines.add(li);
        }
      }
      out.add(pi);
    }
    out.sort((a, b) -> Integer.compare(a.pageNumber, b.pageNumber));
    return out;
  }

  private static double polygonMinY(JsonNode polygon) {
    if (polygon == null || !polygon.isArray() || polygon.size() < 2) {
      return 0;
    }
    double min = Double.MAX_VALUE;
    for (int i = 1; i < polygon.size(); i += 2) {
      double y = polygon.get(i).asDouble(0);
      if (y < min) {
        min = y;
      }
    }
    return (min == Double.MAX_VALUE) ? 0 : min;
  }

  // ---------------------------------------------------------------------------
  // Small value holders
  // ---------------------------------------------------------------------------

  private static final class PageInfo {
    int pageNumber;
    double width;
    double height;
    final List<LineInfo> lines = new ArrayList<>();

    String text() {
      StringBuilder sb = new StringBuilder();
      for (LineInfo l : lines) {
        if (l.content != null && !l.content.isEmpty()) {
          sb.append(l.content).append('\n');
        }
      }
      return sb.toString();
    }
  }

  private static final class LineInfo {
    String content;
    double yTop;
  }

  /** Lines of {@code page} whose top falls within [top, bottom] (same-page crop band text). */
  private static String bandText(PageInfo page, double top, double bottom) {
    StringBuilder sb = new StringBuilder();
    for (LineInfo l : page.lines) {
      if (l.yTop >= top && l.yTop < bottom && l.content != null && !l.content.isEmpty()) {
        sb.append(l.content).append('\n');
      }
    }
    return sb.toString();
  }

  private static final class Anchor {
    int page;
    int pageMin; // document page span (documents[] anchors); == page for text anchors
    int pageMax;
    double yTop;
    JsonNode fields; // Azure documents[i].fields, or null for a text-only anchor
    Double confidence;
    String rawNumber;
    String normalizedNo;
    boolean strongInvoice; // documents[] anchors: strong structured invoice evidence (id+total+VAT)
  }
}
