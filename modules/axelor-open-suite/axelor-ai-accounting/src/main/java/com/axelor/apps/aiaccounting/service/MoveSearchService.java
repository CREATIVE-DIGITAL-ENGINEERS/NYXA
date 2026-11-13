package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding accounting moves, inside the caller's company and nowhere else.
 *
 * <h2>Company isolation is in the query, not after it</h2>
 *
 * The filter is built with {@link AiCompanyScope#companyFilter(String)} and bound before the page
 * is fetched, so a move belonging to another company is never read, never counted in the total and
 * never paged over. Filtering after the fetch would leak the count even when it withheld the rows —
 * "your search matched 40 records" is itself information about another company's ledger.
 *
 * <h2>Paging</h2>
 *
 * Same contract as {@code search_ocr_records}: {@code totalMatches} is the size of the whole set
 * and {@code offset} walks it. A bulk instruction over more moves than one page holds has to be
 * able to reach the rest, and a search that silently returned the first page as though it were the
 * whole set is how a job finishes early and calls itself done.
 */
@Singleton
public class MoveSearchService {

  /** One page. Sixty summaries is already a large answer to put in front of a person. */
  public static final int MAX_LIMIT = 50;

  public static final int DEFAULT_LIMIT = 20;

  /** Beyond this an offset is a mistake rather than a page. */
  public static final int MAX_OFFSET = 10_000;

  /** What the model may filter on. Every field is optional; company never is. */
  public static class MoveQuery {
    public Long moveId;
    public String reference;
    public String journalCode;
    public Integer statusSelect;
    public String partnerName;
    public String origin;
    public LocalDate dateFrom;
    public LocalDate dateTo;
    public int limit = DEFAULT_LIMIT;
    public int offset;
  }

  /** One page of matches, plus the size of the set it came from. */
  public static class Page {
    private final List<Move> moves;
    private final long totalMatches;

    Page(List<Move> moves, long totalMatches) {
      this.moves = moves;
      this.totalMatches = totalMatches;
    }

    public List<Move> getMoves() {
      return moves;
    }

    public long getTotalMatches() {
      return totalMatches;
    }
  }

  public Page search(MoveQuery q) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();

    // Always first, and never taken from the arguments: the dispatcher strips any company the model
    // tried to name, and the active scope is the only one that decides what is visible.
    where.add(AiCompanyScope.companyFilter("self.company"));
    params.put(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId());

    if (q.moveId != null) {
      where.add("self.id = :moveId");
      params.put("moveId", q.moveId);
    }
    if (notBlank(q.reference)) {
      where.add("LOWER(self.reference) LIKE :reference");
      params.put("reference", like(q.reference));
    }
    if (notBlank(q.journalCode)) {
      where.add("LOWER(self.journal.code) = :journalCode");
      params.put("journalCode", q.journalCode.trim().toLowerCase(java.util.Locale.ROOT));
    }
    if (q.statusSelect != null) {
      where.add("self.statusSelect = :statusSelect");
      params.put("statusSelect", q.statusSelect);
    }
    if (notBlank(q.partnerName)) {
      where.add("LOWER(self.partner.fullName) LIKE :partnerName");
      params.put("partnerName", like(q.partnerName));
    }
    if (notBlank(q.origin)) {
      where.add("LOWER(self.origin) LIKE :origin");
      params.put("origin", like(q.origin));
    }
    if (q.dateFrom != null) {
      where.add("self.date >= :dateFrom");
      params.put("dateFrom", q.dateFrom);
    }
    if (q.dateTo != null) {
      where.add("self.date <= :dateTo");
      params.put("dateTo", q.dateTo);
    }

    Query<Move> query =
        Beans.get(MoveRepository.class).all().filter(String.join(" AND ", where)).bind(params);
    // Newest first: an accountant looking for a move almost always means a recent one, and the id
    // is a tiebreak so paging over same-date moves is stable rather than arbitrary.
    query.order("-date").order("-id");

    long total = query.count();
    int limit = Math.max(1, Math.min(q.limit, MAX_LIMIT));
    int offset = Math.max(0, Math.min(q.offset, MAX_OFFSET));
    return new Page(query.fetch(limit, offset), total);
  }

  /** The one move an id names, or null when it is absent or another company's. */
  public Move findScoped(long moveId) {
    Move move = Beans.get(MoveRepository.class).find(moveId);
    return move == null || !AiCompanyScope.owns(move.getCompany()) ? null : move;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String like(String value) {
    return "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%";
  }
}
