package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a manual accounting move in NEW status, through Axelor's own services.
 *
 * <h2>What this does not do</h2>
 *
 * It does not post. A move created here is a NEW draft exactly as though someone had filled in the
 * form and pressed Save — reviewable, editable in the UI, and deletable. Posting is a separate tool
 * with its own authorisation, because creating a journal entry and putting it in the books are
 * different decisions and a person makes the second one.
 *
 * <p>It also invents nothing. The journal and every account are looked up by the code the caller
 * gave, in the caller's own company, and a code that matches nothing is a refusal rather than a
 * near-miss — an entry posted to almost the right account is worse than one that was never created.
 *
 * <h2>Balance is checked before anything is written</h2>
 *
 * Axelor refuses an unbalanced move at accounting time ({@code MOVE_7}), which is correct but late:
 * by then a draft exists that somebody has to find and delete. The check here runs on the numbers
 * as given, before the transaction does any work, and the refusal reports both totals and the
 * difference so the caller can correct it in one round trip instead of discovering it a line at a
 * time.
 *
 * <p>The line's side is an explicit {@code debit}/{@code credit} choice over a single positive
 * amount, rather than two nullable columns. Axelor's {@code MOVE_6} exists because a line carrying
 * both is ambiguous; taking one amount and one side makes that state unrepresentable rather than
 * merely refused.
 */
@Singleton
public class MoveCreationService {

  private static final Logger LOG = LoggerFactory.getLogger(MoveCreationService.class);

  /** Enough for a payroll or year-end entry; past this it is an import, not a chat instruction. */
  public static final int MAX_LINES = 100;

  /** One line of the requested entry, as the caller described it. */
  public static class LineRequest {
    public String accountCode;
    public BigDecimal amount;
    public boolean debit;
    public Long partnerId;
    public String description;
    public LocalDate dueDate;
  }

  /** The whole requested entry. */
  public static class MoveRequest {
    public String journalCode;
    public LocalDate date;
    public Long partnerId;
    public String origin;
    public LocalDate originDate;
    public String description;
    public Integer functionalOriginSelect;
    public List<LineRequest> lines = new ArrayList<>();
  }

  /**
   * Builds, balances and saves the move.
   *
   * <p>Transactional and rolling back on anything: a half-written entry with three of its four
   * lines is a worse outcome than no entry at all, and it is one somebody would have to notice
   * before they could fix it.
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> create(MoveRequest request) {
    Company company = resolveCompany();
    Journal journal = resolveJournal(request.journalCode, company);
    LocalDate date = request.date == null ? LocalDate.now() : request.date;

    if (request.lines == null || request.lines.isEmpty()) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_NOT_BALANCED,
          "A move needs at least two lines — one debit and one credit. Nothing was created.");
    }
    if (request.lines.size() > MAX_LINES) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_NOT_BALANCED,
          "This move has "
              + request.lines.size()
              + " lines and the limit for a move created from the assistant is "
              + MAX_LINES
              + ". Nothing was created.");
    }

    // Every account resolved, and the totals proved equal, before the transaction writes anything.
    List<Account> accounts = new ArrayList<>();
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (int i = 0; i < request.lines.size(); i++) {
      LineRequest line = request.lines.get(i);
      accounts.add(resolveAccount(line.accountCode, company, i + 1));
      BigDecimal amount = requirePositive(line.amount, i + 1);
      if (line.debit) {
        debit = debit.add(amount);
      } else {
        credit = credit.add(amount);
      }
    }
    requireBalanced(debit, credit);

    Partner partner = resolvePartner(request.partnerId);
    Move move;
    try {
      move =
          Beans.get(MoveCreateService.class)
              .createMove(
                  journal,
                  company,
                  null, // company currency; the service resolves and sets it
                  partner,
                  date,
                  request.originDate,
                  null, // payment mode
                  partner == null ? null : partner.getFiscalPosition(),
                  MoveRepository.TECHNICAL_ORIGIN_ENTRY,
                  request.functionalOriginSelect == null ? 0 : request.functionalOriginSelect,
                  request.origin,
                  request.description,
                  null); // company bank details
    } catch (Exception e) {
      // The commonest cause by far is no open period for this date, which the service reports as a
      // configuration error. Relayed rather than paraphrased: it names the company and the date.
      throw new AiBlocked(
          AiBlockerCode.PERIOD_NOT_AVAILABLE,
          "The move could not be created for "
              + date
              + ": "
              + message(e)
              + " Nothing was created.");
    }

    MoveLineCreateService lineService = Beans.get(MoveLineCreateService.class);
    for (int i = 0; i < request.lines.size(); i++) {
      LineRequest line = request.lines.get(i);
      try {
        move.addMoveLineListItem(
            lineService.createMoveLine(
                move,
                line.partnerId == null ? partner : resolvePartner(line.partnerId),
                accounts.get(i),
                line.amount,
                line.debit,
                date,
                line.dueDate,
                i + 1, // counter, 1-based as the form numbers them
                request.origin,
                line.description == null ? request.description : line.description));
      } catch (Exception e) {
        throw new AiBlocked(
            AiBlockerCode.AXELOR_REFUSED_MOVE,
            "Line " + (i + 1) + " could not be built: " + message(e) + " Nothing was created.");
      }
    }

    // Through the repository, deliberately — MoveManagementRepository.save is where the journal's
    // authorised-role check and the partner-consistency check live, and bypassing it with JPA.save
    // would create an entry that the same user could not have created through the form.
    Move saved;
    try {
      saved = Beans.get(MoveRepository.class).save(move);
      JPA.flush();
    } catch (Exception e) {
      throw new AiBlocked(
          AiBlockerCode.JOURNAL_NOT_AUTHORISED,
          "The move was refused when saving: " + message(e) + " Nothing was created.");
    }

    AiWriteEffects.touched(Move.class.getName(), saved.getId());
    LOG.info(
        "AI Accountant: created move {} ({} lines, journal {}) by {}.",
        saved.getId(),
        request.lines.size(),
        journal.getCode(),
        AiExecutionContext.callerDescription());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("created", true);
    out.putAll(Beans.get(MoveViewService.class).detail(saved));
    out.put(
        "nextStep",
        "The move is a NEW draft and is NOT in the books. Show it to the user and post it with"
            + " post_move only if they ask.");
    return out;
  }

  // ---------------------------------------------------------------------------
  // Resolution. Every one of these refuses rather than guesses.
  // ---------------------------------------------------------------------------

  protected Company resolveCompany() {
    Long companyId = AiCompanyScope.requireCompanyId();
    Company company = Beans.get(CompanyRepository.class).find(companyId);
    if (company == null) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "The active company could not be read. Nothing was created.");
    }
    return company;
  }

  protected Journal resolveJournal(String code, Company company) {
    if (code == null || code.trim().isEmpty()) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "A journal code is required to create a move. Nothing was created.");
    }
    Journal journal =
        Beans.get(JournalRepository.class)
            .all()
            .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
            .bind("code", code.trim().toLowerCase(java.util.Locale.ROOT))
            .bind("companyId", company.getId())
            .fetchOne();
    if (journal == null) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "No journal with code '"
              + code
              + "' in "
              + company.getName()
              + ". Nothing was created — do not substitute a different journal.");
    }
    return journal;
  }

  protected Account resolveAccount(String code, Company company, int lineNumber) {
    if (code == null || code.trim().isEmpty()) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "Line " + lineNumber + " has no account code. Nothing was created.");
    }
    Account account =
        Beans.get(AccountRepository.class)
            .all()
            .filter("LOWER(self.code) = :code AND self.company.id = :companyId")
            .bind("code", code.trim().toLowerCase(java.util.Locale.ROOT))
            .bind("companyId", company.getId())
            .fetchOne();
    if (account == null) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "Line "
              + lineNumber
              + " names account '"
              + code
              + "', which does not exist in "
              + company.getName()
              + ". Nothing was created — do not substitute a similar code.");
    }
    // Axelor refuses an inactive account at accounting time (INACTIVE_ACCOUNT_FOUND). Caught here
    // so the refusal names the line while nothing has been written yet.
    if (account.getStatusSelect() != null
        && account.getStatusSelect() != AccountRepository.STATUS_ACTIVE) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "Line "
              + lineNumber
              + " names account "
              + code
              + ", which is not active, so nothing may be posted to it. Nothing was created.");
    }
    return account;
  }

  /**
   * An optional partner for the move or one of its lines, or a refusal.
   *
   * <p>Shared by create and edit. The id comes from the model, so it proves nothing by itself: the
   * partner has to be one this company may use ({@link AiCompanyScope#partnerInScope}), one the
   * caller may read, a partner rather than a contact person, not archived, and not the company's
   * own identity ({@code Company.partner}) — a company is not its own counterparty.
   *
   * <p>Every one of those reasons answers exactly as an id that does not exist, so an id from
   * another company cannot be used to learn that it does.
   */
  protected Partner resolvePartner(Long partnerId) {
    if (partnerId == null) {
      return null;
    }
    Partner partner = loadPartner(partnerId);
    if (partner == null || !usableOnEntry(partner)) {
      throw new AiBlocked(
          AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND,
          "No partner with id "
              + partnerId
              + " in this company. Nothing was written — do not substitute a different partner.");
    }
    return partner;
  }

  /** Whether a loaded partner may stand on an entry of the active company. */
  protected boolean usableOnEntry(Partner partner) {
    if (!AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      return false;
    }
    // A contact is a person attached to a partner; an archived partner is one the form no longer
    // offers.
    if (Boolean.TRUE.equals(partner.getIsContact())) {
      return false;
    }
    if (Boolean.TRUE.equals(partner.getArchived())) {
      return false;
    }
    Long ownIdentity = ownIdentityPartnerId();
    if (ownIdentity != null && ownIdentity.equals(partner.getId())) {
      return false;
    }
    return readable(partner);
  }

  /** The partner row by id, unscoped. A seam; {@link #usableOnEntry} decides what may be used. */
  protected Partner loadPartner(long partnerId) {
    return Beans.get(PartnerRepository.class).find(partnerId);
  }

  /** The active company's own {@code Company.partner} id, or null when it has none. */
  protected Long ownIdentityPartnerId() {
    Partner own = AiCompanyScope.requireCompany().getPartner();
    return own == null ? null : own.getId();
  }

  /** The caller's read permission on this partner. System-context work has no user to check. */
  protected boolean readable(Partner partner) {
    if (AiExecutionContext.isSystem()) {
      return true;
    }
    return partner.getId() != null
        && Beans.get(JpaSecurity.class)
            .isPermitted(JpaSecurity.CAN_READ, Partner.class, partner.getId());
  }

  protected BigDecimal requirePositive(BigDecimal amount, int lineNumber) {
    if (amount == null || amount.signum() <= 0) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_NOT_BALANCED,
          "Line "
              + lineNumber
              + " must carry a positive amount and a side (debit or credit). A negative amount is"
              + " expressed by putting it on the other side. Nothing was created.");
    }
    return amount;
  }

  /** The rule Axelor enforces at posting, applied here so no draft is left behind by it. */
  protected void requireBalanced(BigDecimal debit, BigDecimal credit) {
    if (debit.compareTo(credit) == 0) {
      return;
    }
    throw new AiBlocked(
        AiBlockerCode.MOVE_NOT_BALANCED,
        "The entry does not balance: total debit "
            + debit.toPlainString()
            + " against total credit "
            + credit.toPlainString()
            + ", a difference of "
            + debit.subtract(credit).toPlainString()
            + ". Nothing was created. Correct the lines and call create_move again.");
  }

  /**
   * Axelor's own wording, kept verbatim.
   *
   * <p>Its messages name the company, the date, the account — the actual accounting reason. A
   * paraphrase would send someone looking for the wrong problem, so the only thing done to it is
   * trimming, and the class name is the fallback when there is no message at all.
   */
  private static String message(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName()
        : message.trim();
  }
}
