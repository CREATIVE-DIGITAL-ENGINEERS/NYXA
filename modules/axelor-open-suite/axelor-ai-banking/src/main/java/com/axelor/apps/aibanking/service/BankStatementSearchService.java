package com.axelor.apps.aibanking.service;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding bank statements, inside the caller's company.
 *
 * <h2>The company is on the statement now</h2>
 *
 * Stock {@code BankStatement} carries no company at all — its columns are a name, two dates, a
 * status, a file, a file format and a reconciliation flag — so a statement used to be attributable
 * only through its lines, and therefore only after it had been imported. A RECEIVED statement
 * belonged, as far as the database was concerned, to nobody, and this class answered "not found"
 * for a record plainly visible on the Bank statements screen.
 *
 * <p>This module adds {@code BankStatement.company} (see its domain extension), filled in when the
 * statement is created and independent of its lifecycle, and that field is what every read scopes
 * on. The scope is applied inside the query: another company's statement is never read, never
 * counted and never paged over.
 *
 * <h2>The legacy clause, and why it is not a loophole</h2>
 *
 * Statements that predate the field have no company stored. Rather than a migration that would have
 * to make a judgement with less context than this query has, such a row is matched only when its
 * own lines prove it: at least one line on this company's account and <em>no</em> line anywhere
 * else, including none without an account. That is the same certainty the old rule offered, so
 * nothing became visible that was not visible before — the field only adds the RECEIVED case, and
 * only for statements that were explicitly given a company. Ambiguous legacy rows stay invisible
 * until a person sets the company on the form, which is exactly the case where guessing would file
 * a bank file in the wrong ledger.
 *
 * <p>An earlier version had an {@code includeNotImported} option that listed unattributed
 * statements with a warning attached. It has been removed and is not coming back: a caveat in a
 * tool result is not an access control. Nothing here reads a statement this company cannot be shown
 * to own; the one lookup that touches an unattributed row is {@link #findImportCandidate}, which is
 * not a read path — see its javadoc.
 */
@Singleton
public class BankStatementSearchService {

  public static final int MAX_LIMIT = 50;
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_OFFSET = 10_000;

  /** The statement's own company — the normal case, and the only one for new records. */
  private static final String COMPANY_ON_STATEMENT =
      "self.company.id = :" + AiCompanyScope.COMPANY_PARAM;

  /**
   * A pre-field row whose lines leave no doubt: some on this company's accounts, none elsewhere.
   *
   * <p>A line with no bank account counts as elsewhere, because it could belong to anyone.
   */
  private static final String LEGACY_LINES_AGREE =
      "self.company IS NULL"
          + " AND exists (SELECT 1 FROM BankStatementLine l WHERE l.bankStatement = self"
          + " AND l.bankDetails.company.id = :"
          + AiCompanyScope.COMPANY_PARAM
          + ")"
          + " AND not exists (SELECT 1 FROM BankStatementLine l2 WHERE l2.bankStatement = self"
          + " AND (l2.bankDetails IS NULL OR l2.bankDetails.company IS NULL"
          + " OR l2.bankDetails.company.id != :"
          + AiCompanyScope.COMPANY_PARAM
          + "))";

  /** Everything this company may read. */
  private static final String OWNED_BY_COMPANY =
      COMPANY_ON_STATEMENT + " OR (" + LEGACY_LINES_AGREE + ")";

  /** No lines at all: not imported yet. */
  private static final String HAS_NO_LINES =
      "not exists (SELECT 1 FROM BankStatementLine l2 WHERE l2.bankStatement = self)";

  /** Unattributed, or attributed to this company. Never another company's. */
  private static final String NOT_ANOTHER_COMPANYS =
      "(self.company IS NULL OR self.company.id = :" + AiCompanyScope.COMPANY_PARAM + ")";

  public static class StatementQuery {
    public Long bankStatementId;
    public String name;
    public Integer statusSelect;
    public Long bankDetailsId;
    public LocalDate fromDate;
    public LocalDate toDate;
    public int limit = DEFAULT_LIMIT;
    public int offset;
  }

  public static class Page {
    private final List<BankStatement> statements;
    private final long totalMatches;

    Page(List<BankStatement> statements, long totalMatches) {
      this.statements = statements;
      this.totalMatches = totalMatches;
    }

    public List<BankStatement> getStatements() {
      return statements;
    }

    public long getTotalMatches() {
      return totalMatches;
    }
  }

  public Page search(StatementQuery q) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();

    where.add("(" + OWNED_BY_COMPANY + ")");
    params.put(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId());

    if (q.bankStatementId != null) {
      where.add("self.id = :bankStatementId");
      params.put("bankStatementId", q.bankStatementId);
    }
    if (notBlank(q.name)) {
      where.add("LOWER(self.name) LIKE :name");
      params.put("name", "%" + q.name.trim().toLowerCase(java.util.Locale.ROOT) + "%");
    }
    if (q.statusSelect != null) {
      where.add("self.statusSelect = :statusSelect");
      params.put("statusSelect", q.statusSelect);
    }
    if (q.bankDetailsId != null) {
      // Through the lines, because that is the only place a statement names an account.
      where.add(
          "exists (SELECT 1 FROM BankStatementLine bl WHERE bl.bankStatement = self"
              + " AND bl.bankDetails.id = :bankDetailsId)");
      params.put("bankDetailsId", q.bankDetailsId);
    }
    if (q.fromDate != null) {
      where.add("self.toDate >= :fromDate");
      params.put("fromDate", q.fromDate);
    }
    if (q.toDate != null) {
      where.add("self.fromDate <= :toDate");
      params.put("toDate", q.toDate);
    }

    Query<BankStatement> query =
        Beans.get(BankStatementRepository.class)
            .all()
            .filter(String.join(" AND ", where))
            .bind(params);
    // The grid's own order: most recent period first.
    query.order("-toDate").order("-id");

    long total = query.count();
    int limit = Math.max(1, Math.min(q.limit, MAX_LIMIT));
    int offset = Math.max(0, Math.min(q.offset, MAX_OFFSET));
    return new Page(query.fetch(limit, offset), total);
  }

  /**
   * The one statement an id names, or null when it is absent, another company's, or unattributed.
   *
   * <p>The only lookup any read path may use. There is no variant that relaxes the scope.
   */
  public BankStatement findScoped(long bankStatementId) {
    return Beans.get(BankStatementRepository.class)
        .all()
        .filter("self.id = :id AND (" + OWNED_BY_COMPANY + ")")
        .bind("id", bankStatementId)
        .bind(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId())
        .fetchOne();
  }

  /**
   * A statement with no lines that is not another company's, for the import path only.
   *
   * <h2>Why this is not a hole in the scoping</h2>
   *
   * It matches only statements with zero lines whose company is this one or not yet set. A
   * statement explicitly marked as another company's is excluded outright; an unattributed one is
   * reachable because there is no other company's data to protect on it — nothing has claimed it.
   * What it deliberately cannot reach is an <em>imported</em> statement belonging to somebody else:
   * those have lines, fail this filter, and are answered as not found exactly as on every read
   * path.
   *
   * <p>It is also not a read path. Nothing it returns is described to the model. The import service
   * uses it to obtain the row, gates the operation on a bank account that <em>is</em>
   * company-owned, and reports the statement's name and contents only after the imported lines have
   * proved it belongs to that account. Every refusal before that point names the id and nothing
   * else.
   */
  public BankStatement findImportCandidate(long bankStatementId) {
    return Beans.get(BankStatementRepository.class)
        .all()
        .filter("self.id = :id AND " + NOT_ANOTHER_COMPANYS + " AND (" + HAS_NO_LINES + ")")
        .bind("id", bankStatementId)
        .bind(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId())
        .fetchOne();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
