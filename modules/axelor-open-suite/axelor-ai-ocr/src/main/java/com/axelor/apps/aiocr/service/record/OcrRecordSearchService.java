package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JpaSecurity;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

/**
 * Locates OCR upload records from a parameterised query.
 *
 * <p>Reusable on purpose: the AI tool is a thin adapter over this, and a controller or future UI
 * can call the same method with the same {@link OcrRecordQuery}.
 *
 * <p>Safety properties, all enforced here rather than in the caller:
 *
 * <ul>
 *   <li>an empty query is refused — no unfiltered table scan;
 *   <li>results are always bounded by {@link OcrRecordQuery#MAX_LIMIT};
 *   <li>rows the current user may not read are filtered out by {@link JpaSecurity};
 *   <li>every query is restricted, in SQL, to the caller's single active company ({@link
 *       AiCompanyScope}) — including a search by {@code uploadId}, so knowing another company's id
 *       returns the same empty outcome as an id that does not exist;
 *   <li>an ambiguous result is returned as a candidate list — never silently narrowed to one.
 * </ul>
 */
@Singleton
public class OcrRecordSearchService {

  private final AiOcrUploadRepository uploadRepo;

  @Inject
  public OcrRecordSearchService(AiOcrUploadRepository uploadRepo) {
    this.uploadRepo = uploadRepo;
  }

  /** Outcome of a search: none, exactly one, or several candidates. */
  public static final class SearchOutcome {
    private final List<AiOcrUpload> records;
    private final long totalMatches;
    private final boolean truncated;

    SearchOutcome(List<AiOcrUpload> records, long totalMatches, boolean truncated) {
      this.records = records;
      this.totalMatches = totalMatches;
      this.truncated = truncated;
    }

    public List<AiOcrUpload> getRecords() {
      return Collections.unmodifiableList(records);
    }

    public long getTotalMatches() {
      return totalMatches;
    }

    public boolean isTruncated() {
      return truncated;
    }

    public boolean isNotFound() {
      return records.isEmpty();
    }

    public boolean isUnique() {
      return records.size() == 1;
    }

    public AiOcrUpload single() {
      return isUnique() ? records.get(0) : null;
    }
  }

  /**
   * @throws IllegalArgumentException when no criterion was supplied
   */
  public SearchOutcome search(OcrRecordQuery q) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; OCR records cannot be searched without a caller identity.");
    }
    if (q == null || q.isEmpty()) {
      throw new IllegalArgumentException(
          "At least one search criterion is required; an unfiltered search is not allowed.");
    }

    StringBuilder jpql = new StringBuilder("1 = 1");
    Map<String, Object> params = new LinkedHashMap<>();

    if (q.getUploadId() != null) {
      jpql.append(" AND self.id = :uploadId");
      params.put("uploadId", q.getUploadId());
    }
    if (q.getInvoiceId() != null) {
      jpql.append(" AND self.invoice.id = :invoiceId");
      params.put("invoiceId", q.getInvoiceId());
    }
    if (notBlank(q.getInvoiceNumber())) {
      // The supplier's own number lives on the invoice, not the upload.
      jpql.append(" AND LOWER(self.invoice.supplierInvoiceNb) LIKE :invoiceNumber");
      params.put("invoiceNumber", like(q.getInvoiceNumber()));
    }
    if (notBlank(q.getFileName())) {
      jpql.append(" AND LOWER(self.fileName) LIKE :fileName");
      params.put("fileName", like(q.getFileName()));
    }
    if (notBlank(q.getPartnerName())) {
      jpql.append(
          " AND (LOWER(self.invoice.partner.name) LIKE :partnerName"
              + " OR LOWER(self.invoice.partner.fullName) LIKE :partnerName)");
      params.put("partnerName", like(q.getPartnerName()));
    }
    if (notBlank(q.getEik())) {
      jpql.append(" AND ").append(eikCriterion(q.getEik(), params));
    }
    if (notBlank(q.getIban())) {
      jpql.append(" AND REPLACE(UPPER(self.invoice.bankDetails.iban), ' ', '') = :iban");
      params.put("iban", q.getIban().replaceAll("\\s", "").toUpperCase());
    }
    if (notBlank(q.getStatusSelect())) {
      jpql.append(" AND self.statusSelect = :statusSelect");
      params.put("statusSelect", q.getStatusSelect().trim());
    }
    if (notBlank(q.getOcrReviewStatus())) {
      jpql.append(" AND self.invoice.ocrReviewStatus = :ocrReviewStatus");
      params.put("ocrReviewStatus", q.getOcrReviewStatus().trim());
    }
    if (notBlank(q.getSegmentType())) {
      jpql.append(" AND self.segmentType = :segmentType");
      params.put("segmentType", q.getSegmentType().trim());
    }
    if (notBlank(q.getCarrierKind())) {
      jpql.append(" AND self.carrierKind = :carrierKind");
      params.put("carrierKind", q.getCarrierKind().trim());
    }
    if (notBlank(q.getDocKey())) {
      jpql.append(" AND self.docKey = :docKey");
      params.put("docKey", q.getDocKey().trim());
    }
    if (notBlank(q.getFileHash())) {
      jpql.append(" AND self.fileHash = :fileHash");
      params.put("fileHash", q.getFileHash().trim());
    }
    if (q.getImportArchiveId() != null) {
      jpql.append(" AND self.importArchive.id = :importArchiveId");
      params.put("importArchiveId", q.getImportArchiveId());
    }
    if (q.getCompanyId() != null) {
      jpql.append(" AND self.company.id = :companyId");
      params.put("companyId", q.getCompanyId());
    }
    if (q.getCreatedFrom() != null) {
      jpql.append(" AND self.createdOn >= :createdFrom");
      params.put("createdFrom", q.getCreatedFrom().atStartOfDay());
    }
    if (q.getCreatedTo() != null) {
      jpql.append(" AND self.createdOn <= :createdTo");
      params.put("createdTo", q.getCreatedTo().atTime(LocalTime.MAX));
    }

    // Company scope, applied last and unconditionally. It is an AND on top of whatever the caller
    // asked for, so no combination of criteria — including an exact uploadId — can reach another
    // company's document, and a company-less row is not this company's data either.
    jpql.append(" AND ").append(AiCompanyScope.companyFilter("self.company"));
    params.put(AiCompanyScope.COMPANY_PARAM, scopeCompanyId());

    Query<AiOcrUpload> query = uploadRepo.all().filter(jpql.toString());
    params.forEach(query::bind);
    query.order("-id");

    long total = query.count();
    List<AiOcrUpload> page = query.fetch(q.getLimit(), q.getOffset());

    // Row-level read permission is the platform's job; honour it rather than reimplementing it.
    // Only meaningful with a user in context — authentication is enforced a layer up.
    if (AuthUtils.getUser() == null) {
      return new SearchOutcome(page, total, total > q.getOffset() + page.size());
    }
    List<AiOcrUpload> permitted = new ArrayList<>();
    JpaSecurity security = Beans.get(JpaSecurity.class);
    for (AiOcrUpload u : page) {
      if (security.isPermitted(JpaSecurity.CAN_READ, AiOcrUpload.class, u.getId())) {
        permitted.add(u);
      }
    }
    return new SearchOutcome(permitted, total, total > q.getOffset() + permitted.size());
  }

  /**
   * The one company every query here is confined to.
   *
   * <p>Overridable so the scope rule can be exercised without a live Shiro subject; there is no
   * value it can return that means "all companies".
   *
   * @throws AiCompanyScope.ScopeUnavailableException when the caller has no resolvable company
   */
  protected Long scopeCompanyId() {
    return AiCompanyScope.requireCompanyId();
  }

  /** How many rows the fuzzy pass may look at, so a near-miss never turns into a table scan. */
  static final int SUGGEST_SCAN_LIMIT = 200;

  /** Below this similarity a name is a different document, not a typo. */
  static final double SUGGEST_MIN_SIMILARITY = 0.55;

  /**
   * Near-miss file names, for when an exact search found nothing.
   *
   * <p>Why this exists: users mistype file names, and Bulgarian ones especially ("Получетаел.pdf"
   * for "Получател.pdf"). A bare {@code not_found} gives an agent nothing to act on, so it re-runs
   * near-identical searches until its iteration budget is gone. Handing back ranked candidates ends
   * the turn in one call.
   *
   * <p>Bounded on both sides: candidates are pre-filtered in SQL on a leading or trailing fragment
   * and capped at {@link #SUGGEST_SCAN_LIMIT}, then ranked in Java by edit distance.
   */
  public List<AiOcrUpload> suggestByFileName(String fileName, int max) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; OCR records cannot be searched without a caller identity.");
    }
    String needle = baseName(fileName);
    if (needle.length() < 4) {
      return new ArrayList<>();
    }
    String head = needle.substring(0, 3);
    String tail = needle.substring(needle.length() - 3);

    StringBuilder jpql =
        new StringBuilder("(LOWER(self.fileName) LIKE :head OR LOWER(self.fileName) LIKE :tail)");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("head", "%" + head + "%");
    params.put("tail", "%" + tail + "%");

    jpql.append(" AND ").append(AiCompanyScope.companyFilter("self.company"));
    params.put(AiCompanyScope.COMPANY_PARAM, scopeCompanyId());

    Query<AiOcrUpload> query = uploadRepo.all().filter(jpql.toString());
    params.forEach(query::bind);
    List<AiOcrUpload> candidates = query.order("-id").fetch(SUGGEST_SCAN_LIMIT);

    JpaSecurity security = AuthUtils.getUser() == null ? null : Beans.get(JpaSecurity.class);
    List<AiOcrUpload> ranked = new ArrayList<>();
    Map<Long, Double> scores = new LinkedHashMap<>();
    for (AiOcrUpload u : candidates) {
      if (security != null
          && !security.isPermitted(JpaSecurity.CAN_READ, AiOcrUpload.class, u.getId())) {
        continue;
      }
      double s = similarity(needle, baseName(u.getFileName()));
      if (s >= SUGGEST_MIN_SIMILARITY) {
        scores.put(u.getId(), s);
        ranked.add(u);
      }
    }
    ranked.sort((a, b) -> Double.compare(scores.get(b.getId()), scores.get(a.getId())));
    return ranked.size() > max ? new ArrayList<>(ranked.subList(0, max)) : ranked;
  }

  /** Similarity of a file name to the searched one, 0..1. Same metric the ranking above uses. */
  public double fileNameSimilarity(String searched, String candidate) {
    return similarity(baseName(searched), baseName(candidate));
  }

  /** Lower-cased name without its extension or separators, so "Fact_1 (2).pdf" ≈ "fact12". */
  static String baseName(String fileName) {
    if (fileName == null) {
      return "";
    }
    String v = fileName.trim().toLowerCase();
    int dot = v.lastIndexOf('.');
    if (dot > 0) {
      v = v.substring(0, dot);
    }
    return v.replaceAll("[\\s_\\-()\\[\\].]", "");
  }

  static double similarity(String a, String b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0d;
    }
    if (a.equals(b)) {
      return 1d;
    }
    // A short search fragment fully contained in a longer name is a strong hit; plain edit distance
    // would punish it for the length difference alone.
    if (b.contains(a) || a.contains(b)) {
      return 0.95d;
    }
    int distance = levenshtein(a, b);
    return 1d - ((double) distance / Math.max(a.length(), b.length()));
  }

  static int levenshtein(String a, String b) {
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
      int[] swap = prev;
      prev = cur;
      cur = swap;
    }
    return prev[b.length()];
  }

  /**
   * The supplier ЕИК/VAT condition, by the one rule every AI-facing lookup uses ({@link
   * SupplierEik}): the searched value canonicalised, and BOTH stored fields compared with spaces
   * and a {@code BG} prefix stripped. The old condition stripped only spaces from the stored
   * values, so a partner whose VAT number was stored as {@code BG831919536} never matched.
   *
   * @throws IllegalArgumentException when the value is not a plausible ЕИК — a bad argument, not an
   *     empty result
   */
  static String eikCriterion(String raw, Map<String, Object> params) {
    String eik = SupplierEik.canonical(raw);
    if (eik == null) {
      throw new IllegalArgumentException(
          "'"
              + raw.trim()
              + "' is not a plausible ЕИК or BG VAT number (expected 9-13 digits, optionally"
              + " BG-prefixed).");
    }
    params.put("eik", eik);
    return SupplierEik.identityCondition("self.invoice.partner", "eik");
  }

  private static String like(String v) {
    return "%" + v.trim().toLowerCase() + "%";
  }

  private static boolean notBlank(String v) {
    return v != null && !v.trim().isEmpty();
  }
}
