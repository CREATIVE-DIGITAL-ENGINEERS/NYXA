package com.axelor.apps.bglocalization.service.vat;

import static java.util.stream.Collectors.joining;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatCounterpartyId;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting.Kind;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting.Route;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseCreditTypeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTarget;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTaxCodeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSubject;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.db.Query;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads POSTED accounting data and converts it into document-grouped DTOs. This is the ONLY layer
 * that touches Axelor accounting entities; everything downstream works on DTOs.
 *
 * <p>WS-2 rules:
 *
 * <ul>
 *   <li>Collect posted ({@link MoveRepository#STATUS_ACCOUNTED}) moves for the selected company +
 *       period. One diary row = one accounting document = one {@link Move}.
 *   <li>If {@link Move#getInvoice()} exists, enrich the DTO from the invoice; otherwise use the
 *       move itself as the source document.
 *   <li>NAP document type is read from Invoice-then-Move — but that field does not exist yet (Block
 *       C), so it stays {@code null} and the validator blocks production.
 * </ul>
 */
@Singleton
public class BgVatDocumentCollector {

  private static final Logger log = LoggerFactory.getLogger(BgVatDocumentCollector.class);

  // Invoice.operationTypeSelect values that identify a PURCHASE document.
  private static final int OPERATION_SUPPLIER_PURCHASE = 1;
  private static final int OPERATION_SUPPLIER_REFUND = 2;

  /**
   * VAT account codes in the vendored BG_COA: 4531 = ДДС покупки (input / данъчен кредит), 4532 =
   * ДДС продажби (output / начислен данък). Matched by code PREFIX, so analytic sub-accounts
   * (4531/xxx, 45311, …) are recognised too. BG_COA has no dedicated purchase/sales VAT flag
   * ({@code vatSystemSelect} is "1" for both), so the code prefix is the reliable signal; change
   * these two constants if a client charts VAT differently.
   */
  private static final String ACCOUNT_VAT_PURCHASE = "4531";

  private static final String ACCOUNT_VAT_SALES = "4532";

  /** Вид на документа „07 – Митническа декларация“ ({@code bg.nap.document.type.select}). */
  private static final String NAP_DOCUMENT_CUSTOMS = "07";

  /** Tolerance for the reverse-calc base/rate cross-check on protocol moves. */
  private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

  /**
   * Законовите ставки като дроб: 20 % — ЗДДС чл. 66 ал. 1 („Стандартната ставка на данъка е 20 на
   * сто…“), 9 % — чл. 66а („Ставката на данъка е 9 на сто за: …“), и нулевата — чл. 66б.
   */
  private static final List<BigDecimal> STATUTORY_RATES =
      Collections.unmodifiableList(
          Arrays.asList(BigDecimal.ZERO, new BigDecimal("0.09"), new BigDecimal("0.20")));

  protected final MoveRepository moveRepo;

  @Inject
  public BgVatDocumentCollector(MoveRepository moveRepo) {
    this.moveRepo = moveRepo;
  }

  public List<BgVatDocumentDto> collect(BgVatExportRequestDto request) {
    List<BgVatDocumentDto> documents = new ArrayList<>();
    for (Move move : findPostedMoves(request)) {
      // Relevance filter (NOT a legal NAP classification): keep only moves that touch VAT — see
      // isVatRelevant. Excludes pure bank/payment moves. Column mapping stays out (WS-3).
      if (!isVatRelevant(move)) {
        continue;
      }
      BgVatDocumentDto document = toDocument(move);
      documents.add(document);
      BgVatDocumentDto customs = customsDocument(move, document);
      if (customs != null) {
        documents.add(customs);
      }
    }
    return documents;
  }

  /**
   * Митническият документ при внос с отложено начисляване по чл. 167а — ВТОРИ ред и в двата
   * дневника, само с колони 1–8. ППЗДДС чл. 49а ал. 4 т. 2: „посочи митническия документ за внос,
   * по който е отложено начисляването на данъка, и попълни колони от 1 до 8 вкл. на приложение №
   * 10“; ал. 5 т. 2 — същото за Прил. № 11.
   *
   * <p>Номерът и датата са на ЕАД (MRN): {@code Move.origin} и {@code Move.originDate}. Без тях
   * редът не може да се състави, затова е ГРЕШКА на протокола. Контрагентът е {@code Move.partner},
   * доставчикът, както в Прил. 3 т. IV.7 за SAF-T. ⚠️ Да се потвърди: чл. 49а казва само „колони
   * 1–8“, не и чий е ИН в колона 6. к.8а остава празна — тя е след колона 8.
   *
   * @return the row, or {@code null} when the move is not such an import or the row cannot be built
   */
  protected BgVatDocumentDto customsDocument(Move move, BgVatDocumentDto protocol) {
    String operation = move.getBgVatMoveVatOperation();
    Optional<Route> route =
        BgVatMoveOperationRouting.forOperation(isBlank(operation) ? null : operation.trim());
    if (!route.isPresent() || !route.get().hasCustomsDocument()) {
      return null;
    }
    if (isBlank(move.getOrigin()) || move.getOriginDate() == null) {
      protocol.addError(
          BgVatValidator.CUSTOMS_DOCUMENT_MISSING_CODE,
          "Внос по чл. 167а без номер (MRN в „Произход“) или дата на митническия документ — редът"
              + " по ППЗДДС чл. 49а ал. 4 т. 2 и ал. 5 т. 2 не може да се състави");
      return null;
    }
    BgVatDocumentDto customs = new BgVatDocumentDto();
    customs.setIdentificationOnly(true);
    customs.setNapDocumentType(NAP_DOCUMENT_CUSTOMS);
    customs.setDocumentNumber(move.getOrigin().trim());
    customs.setDocumentDate(move.getOriginDate());
    customs.setJournalCode(protocol.getJournalCode());
    customs.setFiscalPositionCode(protocol.getFiscalPositionCode());
    customs.setCounterpartyName(protocol.getCounterpartyName());
    customs.setSubject(protocol.getSubject());
    customs.setPartnerTaxNbr(protocol.getPartnerTaxNbr());
    customs.setCounterpartyVatNumber(protocol.getCounterpartyVatNumber());
    customs.setCounterpartyDiaryId(protocol.getCounterpartyDiaryId());
    return customs;
  }

  // ---------------------------------------------------------------------------
  // Period source — ISOLATED so the accountant/CTO decision changes only this method.
  // ---------------------------------------------------------------------------

  /**
   * Осчетоводените статии на дружеството в периода — {@link #findPostedMoves}. Статиите, мигрирани
   * от Microinvest, не влизат: периодите им вече са декларирани от Microinvest, тоест дневник от
   * тях би бил второ подаване на подаден период. Признакът се слага само от {@code
   * MicroinvestAccountingImportService}; ръчно въведените статии го оставят {@code null} и {@code
   * COALESCE} ги пази.
   */
  static final String POSTED_MOVES =
      "self.statusSelect IN (:statuses) "
          + "AND self.company.id = :companyId "
          + "AND "
          + BgVatPeriodScope.MOVES
          + " AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE";

  /**
   * Fetch posted moves for the company within the selected reporting period.
   *
   * <p>Периодът — {@link BgVatPeriodScope#MOVES}: {@code Move.date} в [periodFrom, periodTo], освен
   * ако фактурата на статията е отразена в друг период („Период на отразяване по ЗДДС“), плюс
   * статиите на фактури, отразени в този. Същото правило четат и съседните заявки по-долу.
   */
  protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
    if (request.getCompanyId() == null
        || request.getPeriodFrom() == null
        || request.getPeriodTo() == null) {
      // Not enough scope to query safely; the service surfaces this as a validation warning.
      return new ArrayList<>();
    }

    Query<Move> query =
        moveRepo
            .all()
            .filter(POSTED_MOVES)
            .bind("statuses", acceptedMoveStatuses())
            .bind("companyId", request.getCompanyId())
            .order("date");
    return BgVatPeriodScope.bind(query, request.getPeriodFrom(), request.getPeriodTo()).fetch();
  }

  /**
   * Which move statuses feed the VAT diaries — ISOLATED decision point.
   *
   * <p>PROVISIONAL: only {@link MoveRepository#STATUS_ACCOUNTED} (posted). Accountant/CTO decision:
   * should provisional {@link MoveRepository#STATUS_DAYBOOK} moves also be included? This matters
   * in practice — a deployment that keeps moves in daybook (not finally accounted) will otherwise
   * yield zero diary rows. Change the returned set here only.
   */
  protected List<Integer> acceptedMoveStatuses() {
    return Collections.singletonList(MoveRepository.STATUS_ACCOUNTED);
  }

  /**
   * Статиите с ДДС, които {@link #findPostedMoves} НЕ взема, и защо — за съобщението след
   * „Генерирай“. Решенията са същите като там: статус, дружество и дата на статията.
   *
   * <p>„С ДДС“ тук значи със фактура или с ДДС операция на статията. Ръчна статия, която носи ДДС
   * само по редовете си ({@link #isVatRelevant}), не се брои — за броенето щеше да се чете всеки
   * ред на всяка статия от периода.
   *
   * <p>Чужди дружества не се четат (едно активно дружество, решение В8 от 21.10.2026): „друго
   * дружество“ е само статия на НАША фактура, осчетоводена в друго дружество.
   */
  public BgVatExclusionsDto excludedMoves(BgVatExportRequestDto request) {
    BgVatExclusionsDto exclusions = new BgVatExclusionsDto();
    if (request.getCompanyId() == null
        || request.getPeriodFrom() == null
        || request.getPeriodTo() == null) {
      return exclusions;
    }
    classifyUnpostedMoves(findUnpostedMoves(request), exclusions);
    for (Move move : findMovesOutsidePeriod(request)) {
      exclusions.getOutsidePeriodMoves().add(moveLabel(move));
    }
    for (Move move : findMovesReportedElsewhere(request)) {
      exclusions
          .getReportedElsewhereMoves()
          .add(
              moveLabel(move)
                  + " → "
                  + BgVatReportingPeriod.label(BgVatReportingPeriod.explicit(move.getInvoice())));
    }
    for (Move move : findMovesInOtherCompany(request)) {
      exclusions.getOtherCompanyMoves().add(moveLabel(move));
    }
    return exclusions;
  }

  /** Неосчетоводените статии с ДДС в периода — {@link #findUnpostedMoves}. */
  static final String UNPOSTED_MOVES =
      "self.company.id = :companyId "
          + "AND "
          + BgVatPeriodScope.MOVES
          + " AND self.statusSelect NOT IN (:statuses) "
          + "AND self.statusSelect <> :canceled "
          + "AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE "
          + "AND (self.invoice IS NOT NULL OR COALESCE(self.bgVatMoveVatOperation, '') <> '')";

  /**
   * Статиите на фактури с данъчно събитие в периода, а статия извън него — {@link
   * #findMovesOutsidePeriod}. Фактура с изричен период на отразяване не е тук: тя е там, където е
   * отразена ({@link #REPORTED_ELSEWHERE_MOVES}).
   */
  static final String OUTSIDE_PERIOD_MOVES =
      "self.statusSelect IN (:statuses) "
          + "AND self.company.id = :companyId "
          + "AND (self.date < :from OR self.date > :to) "
          + "AND self.invoice IS NOT NULL "
          + "AND COALESCE(self.invoice.bgVatReportingPeriod, '') = '' "
          + "AND COALESCE(self.invoice.bgVatTaxEventDate, self.invoice.invoiceDate) >= :from "
          + "AND COALESCE(self.invoice.bgVatTaxEventDate, self.invoice.invoiceDate) <= :to "
          + "AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE";

  /**
   * Статиите на фактури, отразени в ДРУГ период, които иначе биха били тук: с дата на статията или
   * на данъчното събитие в периода — {@link #findMovesReportedElsewhere}.
   */
  static final String REPORTED_ELSEWHERE_MOVES =
      "self.statusSelect IN (:statuses) "
          + "AND self.company.id = :companyId "
          + "AND self.invoice IS NOT NULL "
          + "AND self.invoice.bgVatReportingPeriod <> '' "
          + "AND (self.invoice.bgVatReportingPeriod < :periodFrom"
          + " OR self.invoice.bgVatReportingPeriod > :periodTo) "
          + "AND ((self.date >= :from AND self.date <= :to)"
          + " OR (COALESCE(self.invoice.bgVatTaxEventDate, self.invoice.invoiceDate) >= :from"
          + " AND COALESCE(self.invoice.bgVatTaxEventDate, self.invoice.invoiceDate) <= :to)) "
          + "AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE";

  /**
   * Статиите в периода на фактури на дружеството в ДРУГО дружество — {@link
   * #findMovesInOtherCompany}.
   */
  static final String OTHER_COMPANY_MOVES =
      "self.statusSelect IN (:statuses) "
          + "AND self.company.id <> :companyId "
          + "AND self.invoice.company.id = :companyId "
          + "AND "
          + BgVatPeriodScope.MOVES
          + " AND COALESCE(self.bgMigratedHistorical, FALSE) = FALSE";

  /**
   * Неосчетоводените статии с ДДС на дружеството в периода (без анулираните). Мигрираните не се
   * броят — те са изключени нарочно.
   */
  protected List<Move> findUnpostedMoves(BgVatExportRequestDto request) {
    Query<Move> query =
        moveRepo
            .all()
            .filter(UNPOSTED_MOVES)
            .bind("companyId", request.getCompanyId())
            .bind("statuses", acceptedMoveStatuses())
            .bind("canceled", MoveRepository.STATUS_CANCELED)
            .order("date");
    return BgVatPeriodScope.bind(query, request.getPeriodFrom(), request.getPeriodTo()).fetch();
  }

  /**
   * Осчетоводени статии на фактури на дружеството, чиято дата на данъчното събитие (без нея — на
   * фактурата) е в периода, а датата на статията — не. Събирачът ги търси по датата на статията.
   */
  protected List<Move> findMovesOutsidePeriod(BgVatExportRequestDto request) {
    return moveRepo
        .all()
        .filter(OUTSIDE_PERIOD_MOVES)
        .bind("statuses", acceptedMoveStatuses())
        .bind("companyId", request.getCompanyId())
        .bind("from", request.getPeriodFrom())
        .bind("to", request.getPeriodTo())
        .order("date")
        .fetch();
  }

  /** Осчетоводени статии на фактури, отразени в друг период („Период на отразяване по ЗДДС“). */
  protected List<Move> findMovesReportedElsewhere(BgVatExportRequestDto request) {
    Query<Move> query =
        moveRepo
            .all()
            .filter(REPORTED_ELSEWHERE_MOVES)
            .bind("statuses", acceptedMoveStatuses())
            .bind("companyId", request.getCompanyId())
            .order("date");
    return BgVatPeriodScope.bind(query, request.getPeriodFrom(), request.getPeriodTo()).fetch();
  }

  /** Осчетоводени статии в периода на фактури на дружеството, записани в ДРУГО дружество. */
  protected List<Move> findMovesInOtherCompany(BgVatExportRequestDto request) {
    Query<Move> query =
        moveRepo
            .all()
            .filter(OTHER_COMPANY_MOVES)
            .bind("statuses", acceptedMoveStatuses())
            .bind("companyId", request.getCompanyId())
            .order("date");
    return BgVatPeriodScope.bind(query, request.getPeriodFrom(), request.getPeriodTo()).fetch();
  }

  /** Разпределя статиите от {@link #findUnpostedMoves} по статус. */
  protected static void classifyUnpostedMoves(List<Move> moves, BgVatExclusionsDto exclusions) {
    for (Move move : moves) {
      int status = move.getStatusSelect();
      if (status == MoveRepository.STATUS_DAYBOOK) {
        exclusions.getDaybookMoves().add(moveLabel(move));
      } else if (status == MoveRepository.STATUS_NEW || status == MoveRepository.STATUS_SIMULATED) {
        exclusions.getDraftMoves().add(moveLabel(move));
      }
    }
  }

  /** Номерът на статията; черновата още няма номер — тогава „статия id N“. */
  protected static String moveLabel(Move move) {
    return isBlank(move.getReference()) ? "статия id " + move.getId() : move.getReference().trim();
  }

  /**
   * Влиза ли статията в дневниците: фактура, ДДС операция на статията, кредитен тип на ред или
   * данък на ред — същият вход, който ползва и SAF-T.
   *
   * <p>⚠️ <b>Протоколът по ръчна статия влиза САМО по операцията си.</b> Редовете му са по
   * 4531/4532, а двете сметки са с {@code isTaxAuthorizedOnMoveLine = false} ({@code
   * account_account.csv}), тоест данък на реда не може да има; {@code
   * MoveLineCreateServiceImpl.createMoveLine} пише и празен {@code taxLineSet}. Докато се питаше
   * само за данък на реда, изпадаха мълчаливо протоколите по чл. 79/79а ({@code
   * BgArt79ProtocolService}, {@code BgArt79aProtocolService}) и всеки ВОП/чл. 82/чл. 163а само с
   * двете данъчни сметки. Тестовете им викаха маршрута направо и не го виждаха.
   */
  protected boolean isVatRelevant(Move move) {
    if (move.getInvoice() != null) {
      return true;
    }
    if (!isBlank(move.getBgVatMoveVatOperation())) {
      return true;
    }
    if (move.getMoveLineList() == null) {
      return false;
    }
    for (MoveLine line : move.getMoveLineList()) {
      if (!isBlank(line.getBgVatCreditType())) {
        return true;
      }
      if (carriesTax(line)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Носи ли редът ДДС: данък на реда ({@code taxLineSet}) или сума на данъка по сметка от вид
   * „данък“ (4531/4532 в BG_COA).
   *
   * <p>⚠️ <b>{@code MoveLine.taxAmount} извън сметка за данък НЕ е ДДС.</b> Полето е само за четене
   * и Axelor го пише като ДЯЛ НА ПЛАТЕНИЯ ДАНЪК по реда на контрагента при плащане ({@code
   * MoveLineTaxServiceImpl.computeTaxAmount} — сборът на {@code TaxPaymentMoveLine}) и по реда на
   * служителя при плащане на разход ({@code ExpensePaymentServiceImpl}). Докато стигаше {@code
   * taxAmount ≠ 0} на който и да е ред, всяко плащане по фактура с ДДС (ред 411/401 с дял) влизаше
   * в дневниците като „ръчен запис с ДДС без основа“, а в SAF-T — като SAFT_PROT_NO_BASE, който
   * спира файла.
   */
  public static boolean carriesTax(MoveLine line) {
    if (line.getTaxLineSet() != null && !line.getTaxLineSet().isEmpty()) {
      return true;
    }
    return line.getTaxAmount().signum() != 0 && isTaxAccount(line.getAccount());
  }

  /** Сметка от вид „данък“ — {@code MoveLineToolServiceImpl.isMoveLineTaxAccount} дословно. */
  static boolean isTaxAccount(Account account) {
    return account != null
        && account.getAccountType() != null
        && AccountTypeRepository.TYPE_TAX.equals(account.getAccountType().getTechnicalTypeSelect());
  }

  // ---------------------------------------------------------------------------
  // Document mapping (group by document, not by raw move line)
  // ---------------------------------------------------------------------------

  protected BgVatDocumentDto toDocument(Move move) {
    BgVatDocumentDto document = new BgVatDocumentDto();

    if (move.getJournal() != null) {
      document.setJournalCode(move.getJournal().getCode());
    }

    Invoice invoice = move.getInvoice();
    if (invoice != null) {
      enrichFromInvoice(document, invoice);
    } else {
      enrichFromMove(document, move);
    }

    // Fiscal position: prefer the invoice's (set in enrichFromInvoice); fall back to the move's.
    if (document.getFiscalPositionCode() == null && move.getFiscalPosition() != null) {
      document.setFiscalPositionCode(move.getFiscalPosition().getCode());
    }

    document.setNapDocumentType(resolveNapDocumentType(invoice, move));
    return document;
  }

  protected void enrichFromInvoice(BgVatDocumentDto document, Invoice invoice) {
    // Purchase docs carry the supplier's own number; sales use the internal invoice id.
    String number = invoice.getSupplierInvoiceNb();
    if (isBlank(number)) {
      number = invoice.getInvoiceId();
    }
    document.setDocumentNumber(number);
    document.setDocumentDate(
        invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : invoice.getOriginDate());
    // VIES (ППЗДДС чл. 117 ал. 2 т. 11): периодът на изискуемостта, когато е различен от периода на
    // декларацията — от датата на данъчното събитие (BgVatViesPeriod).
    document.setTaxEventDate(invoice.getBgVatTaxEventDate());
    // „Период на отразяване по ЗДДС“ — за предупреждението „отразен по-късно“ (BgVatValidator).
    document.setReportingPeriod(BgVatReportingPeriod.explicit(invoice));
    // Авансовата фактура с ДДС (на клиент или от доставчик) стига дотук през ДДС статията си
    // (BgVatAdvanceService, Move.invoice = тя); без ДДС (ВОД, услуга към ЕС) статия още няма.
    // Покупката — по редовете, както всяка фактура: редът за приспадане в окончателната носи
    // кредитния тип на аванса. Признакът пази VIES.
    document.setAdvancePayment(
        invoice.getOperationSubTypeSelect() == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    document.setOperationType(invoice.getOperationTypeSelect());

    // Вид на доставката (поле 02-27/03-45) — feeds both diaries via DTO.supplyTypeArt163a.
    document.setSupplyTypeArt163a(invoice.getBgVatSupplyType());

    // POKUPKI: for a PURCHASE invoice, route each line's base/VAT into к.09–к.15 — by its tax when
    // the tax fixes the column (BgVatPurchaseTaxCodeMapping: освободени и 0 % → к.9, тристранна →
    // к.15), otherwise by its bgVatCreditType. Sales invoices keep their tax-code routing (below).
    // No null guard: the generated Integer getter returns 0, and 0 is not a valid operation type.
    Integer op = invoice.getOperationTypeSelect();
    boolean purchase = op == OPERATION_SUPPLIER_PURCHASE || op == OPERATION_SUPPLIER_REFUND;
    // Редовете с обратно начисляване: основата и данъкът им са на протокола по чл. 117, не на
    // фактурата (applyInvoiceProtocols). ⚠️ Без това редът влиза и тук — с данък по СОБСТВЕНИЯ си
    // данък (Axelor: inTaxTotal = основа × (1 + ставка)), тоест двоен данъчен кредит до протокола.
    List<InvoiceLine> reverseChargeLines = new ArrayList<>();
    Set<String> reverseChargeTaxCodes = new LinkedHashSet<>();
    if (purchase && invoice.getInvoiceLineList() != null) {
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        List<String> codes = BgVatInvoiceProtocolCase.reverseChargeTaxCodes(line);
        if (!codes.isEmpty()) {
          reverseChargeLines.add(line);
          reverseChargeTaxCodes.addAll(codes);
          reverseChargeTaxCodes.addAll(BgVatInvoiceProtocolCase.taxCodes(line));
        }
      }
    }
    document.setReverseChargeInvoice(!reverseChargeLines.isEmpty());
    // Ред с нулеви суми не пише колона (addPurchaseAmount пропуска нулата). Ако такива са ВСИЧКИ
    // редове, фактурата изпада от POKUPKI мълчаливо — флагът казва защо.
    int amountLines = 0;
    int zeroAmountLines = 0;
    if (purchase && invoice.getInvoiceLineList() != null) {
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        if (reverseChargeLines.contains(line)) {
          continue;
        }
        String note =
            BgVatInvoiceProtocolCase.withoutReverseCharge(BgVatInvoiceProtocolCase.taxCodes(line));
        if (note != null) {
          document.addWarning(note + " (invoice line " + line.getId() + ")");
        }
        BigDecimal base = firstNonNull(line.getCompanyExTaxTotal(), line.getExTaxTotal());
        BigDecimal inTax = firstNonNull(line.getCompanyInTaxTotal(), line.getInTaxTotal());
        BigDecimal vat = nz(inTax).subtract(nz(base));
        amountLines++;
        if (nz(base).signum() == 0 && vat.signum() == 0) {
          zeroAmountLines++;
        }
        String ref = "invoice line " + line.getId();
        Optional<BgVatPurchaseTarget> fixed =
            BgVatPurchaseTaxCodeMapping.forTaxCodes(taxCodes(line.getTaxLineSet()));
        if (fixed.isPresent()) {
          if (!BgVatPurchaseTaxCodeMapping.agreesWith(fixed.get(), line.getBgVatCreditType())) {
            document.addWarning(
                "Данъкът на реда определя колоната — кредитният тип „"
                    + line.getBgVatCreditType().trim()
                    + "“ е пренебрегнат ("
                    + ref
                    + ")");
          }
          addPurchaseTarget(document, fixed.get(), nz(base), nz(vat));
          continue;
        }
        accumulatePurchaseColumns(document, line.getBgVatCreditType(), base, vat, ref);
      }
    }
    document.setZeroAmountPurchase(amountLines > 0 && zeroAmountLines == amountLines);

    if (invoice.getFiscalPosition() != null) {
      document.setFiscalPositionCode(invoice.getFiscalPosition().getCode());
    }

    Partner partner = invoice.getPartner();
    if (partner != null) {
      // Само името — fullName е „код - име“ и portal.nra.bg отказва файла (BgVatPartyName).
      document.setCounterpartyName(BgVatPartyName.of(partner));
    }
    document.setSubject(BgVatSubject.fromLines(subjectLines(invoice)));
    // Keep both raw candidates for dry-run verification, then resolve the identification:
    // Invoice.partnerTaxNbr first, else Partner.taxNbr.
    String invoicePartnerTaxNbr = invoice.getPartnerTaxNbr();
    String partnerTaxNbr = partner != null ? partner.getTaxNbr() : null;
    document.setInvoicePartnerTaxNbr(invoicePartnerTaxNbr);
    document.setPartnerTaxNbr(partnerTaxNbr);
    identifyCounterparty(
        document, partner, !isBlank(invoicePartnerTaxNbr) ? invoicePartnerTaxNbr : partnerTaxNbr);

    // Per-rate base/VAT come cleanly from the invoice tax lines (company currency for BGN).
    BigDecimal totalBase = BigDecimal.ZERO;
    BigDecimal totalVat = BigDecimal.ZERO;
    if (invoice.getInvoiceLineTaxList() != null) {
      for (InvoiceLineTax lineTax : invoice.getInvoiceLineTaxList()) {
        if (isReverseChargeRow(lineTax, reverseChargeTaxCodes)) {
          continue;
        }
        BgVatAmountDto amount = new BgVatAmountDto();
        if (lineTax.getTaxLine() != null) {
          amount.setVatRatePercent(lineTax.getTaxLine().getValue());
          if (lineTax.getTaxLine().getTax() != null) {
            amount.setTaxCode(lineTax.getTaxLine().getTax().getCode());
          }
        }
        BigDecimal base = firstNonNull(lineTax.getCompanyExTaxBase(), lineTax.getExTaxBase());
        BigDecimal vat = firstNonNull(lineTax.getCompanyTaxTotal(), lineTax.getTaxTotal());
        amount.setBase(base);
        amount.setVatAmount(vat);
        document.getAmounts().add(amount);
        totalBase = totalBase.add(nz(base));
        totalVat = totalVat.add(nz(vat));
      }
    }
    document.setTotalBase(firstNonNull(invoice.getCompanyExTaxTotal(), totalBase));
    document.setTotalVat(firstNonNull(invoice.getCompanyTaxTotal(), totalVat));
    document.setTotalGross(firstNonNull(invoice.getCompanyInTaxTotal(), totalBase.add(totalVat)));

    if (!reverseChargeLines.isEmpty()) {
      applyInvoiceProtocols(document, invoice, reverseChargeLines);
    }
  }

  /**
   * Покупна фактура с обратно начисляване: основата и данъкът ѝ са на протокола по чл. 117 ЗДДС, а
   * фактурата е в дневниците най-много с идентификацията си.
   *
   * <ul>
   *   <li><b>ВОП и чл. 82</b> — фактурата НЕ влиза в дневниците. Номерът и датата ѝ са реквизит на
   *       протокола: ЗДДС чл. 117 ал. 2 т. 9 („…номер и дата на фактурата - когато такава е
   *       издадена до датата на издаване на протокола“) и ППЗДДС чл. 81 ал. 3 т. 2.
   *   <li><b>Чл. 163а</b> — само колони 1–8а в дневника за покупки: „…отразява получената фактура и
   *       известия към нея в дневника за покупките, като попълва само данните в колони от 1 до 8а
   *       на дневника за покупките.“ (ППЗДДС чл. 97а ал. 5). к.8а е кодът на частта от Прил. № 2
   *       (чл. 97а ал. 1).
   *   <li><b>Без осчетоводен протокол</b> за някой от случаите, или случай, за който Nyxa протокол
   *       не съставя — ГРЕШКА {@link BgVatValidator#INVOICE_PROTOCOL_MISSING_CODE} вместо {@code
   *       UNMAPPED_SALES_TAX_CODE}.
   *   <li><b>Доставчик с „-EX“</b> при услуга — ПРЕДУПРЕЖДЕНИЕ, без протокол и без суми (чл. 82 ал.
   *       2 т. 3, изключението).
   * </ul>
   *
   * <p>Протоколът е налице, когато е осчетоводен и покрива групата си — операция, ставка и
   * основание ({@link BgVatInvoiceProtocolPlan#sameProtocol}). Заварен ръчен протокол се брои, щом
   * е свързан с фактурата.
   */
  protected void applyInvoiceProtocols(
      BgVatDocumentDto document, Invoice invoice, List<InvoiceLine> reverseChargeLines) {
    BgVatInvoiceProtocolCase.Supplier supplier = BgVatInvoiceProtocolCase.supplierOf(invoice);
    LocalDate event =
        invoice.getBgVatTaxEventDate() != null
            ? invoice.getBgVatTaxEventDate()
            : invoice.getOriginDate();
    List<BgVatInvoiceProtocolPlan.Line> planned = new ArrayList<>();
    Set<String> caseErrors = new LinkedHashSet<>();
    for (InvoiceLine line : reverseChargeLines) {
      BgVatInvoiceProtocolCase kase = BgVatInvoiceProtocolCase.of(line, supplier, event);
      switch (kase.getOutcome()) {
        case PROTOCOL:
          planned.add(
              new BgVatInvoiceProtocolPlan.Line(
                  kase,
                  BgVatInvoiceProtocolCase.reverseChargeRate(line),
                  line.getCompanyExTaxTotal(),
                  null,
                  null));
          break;
        case ERROR:
          caseErrors.add(kase.getMessage());
          break;
        case WARNING:
          document.addWarning(kase.getMessage());
          break;
        default:
          break;
      }
    }
    String refusal = BgVatInvoiceProtocolCase.invoiceRefusal(invoice);
    if (refusal != null) {
      caseErrors.add(
          refusal
              + " — съставете протокола като ръчна статия; до тогава, когато Nyxa свързва такъв"
              + " протокол, документът остава ГРЕШКА в дневника");
    }

    List<Move> posted = new ArrayList<>();
    List<String> unposted = new ArrayList<>();
    for (Move protocol : invoiceProtocols(invoice)) {
      if (acceptedMoveStatuses().contains(protocol.getStatusSelect())) {
        posted.add(protocol);
      } else if (protocol.getStatusSelect() != MoveRepository.STATUS_CANCELED) {
        unposted.add(protocolLabel(protocol));
      }
    }
    List<String> missing = new ArrayList<>();
    String supplyType = null;
    for (BgVatInvoiceProtocolPlan.Group group : BgVatInvoiceProtocolPlan.groups(planned)) {
      boolean covered = false;
      for (Move protocol : posted) {
        covered |=
            BgVatInvoiceProtocolPlan.sameProtocol(
                group,
                protocol.getBgVatMoveVatOperation(),
                protocol.getBgVatMoveVatRate(),
                protocol.getBgVatProtocolBasis());
      }
      if (!covered) {
        missing.add(group.getCase().getBasis() + ", " + plain(group.getRatePercent()) + " %");
      }
      if (group.getCase().isArt163a()) {
        if (supplyType != null && !supplyType.equals(group.getCase().getSupplyType())) {
          caseErrors.add(
              "доставки и по част първа, и по част втора на Прил. № 2 в една фактура — редът в"
                  + " дневника носи една к.8а (ППЗДДС чл. 97а ал. 1); да се потвърди");
        }
        supplyType = group.getCase().getSupplyType();
      }
    }

    if (!caseErrors.isEmpty() || !missing.isEmpty()) {
      StringBuilder message =
          new StringBuilder("Фактура с обратно начисляване без протокол по чл. 117 ЗДДС");
      if (!missing.isEmpty()) {
        message.append(" (").append(String.join("; ", missing)).append(")");
      }
      if (caseErrors.isEmpty()) {
        message.append(
            " — съставете го с „Състави протокол по чл. 117“ на фактурата или пакетно от ДДС"
                + " декларацията за периода");
      }
      caseErrors.forEach(error -> message.append("; ").append(error));
      if (!unposted.isEmpty()) {
        message
            .append("; свързан, но неосчетоводен: ")
            .append(String.join(", ", unposted))
            .append(" — дневникът чете само осчетоводени протоколи");
      }
      document.addError(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE, message.toString());
      return;
    }
    if (supplyType != null) {
      String entered = document.getSupplyTypeArt163a();
      if (!isBlank(entered) && !supplyType.equals(entered.trim())) {
        document.addWarning(
            "Вид на доставката „"
                + entered.trim()
                + "“ на фактурата не е частта от Прил. № 2 на данъка ѝ — приет „"
                + supplyType
                + "“ (ППЗДДС чл. 97а ал. 1)");
      }
      document.setSupplyTypeArt163a(supplyType);
      if (document.getPurchaseAmounts().isEmpty()) {
        document.setIdentificationOnly(true);
        document.setPurchasesOnly(true);
      } else {
        document.addWarning(
            "Фактура по чл. 163а с други облагаеми редове — те са в дневника с к.8а „"
                + supplyType
                + "“, а ППЗДДС чл. 97а ал. 5 иска за фактурата само колони 1–8а; да се"
                + " потвърди");
      }
    }
  }

  /** Редовете на фактурата за „вид на стоката/услугата“ — основа, семейство и вид на продукта. */
  protected static List<BgVatSubject.Line> subjectLines(Invoice invoice) {
    List<BgVatSubject.Line> lines = new ArrayList<>();
    if (invoice.getInvoiceLineList() == null) {
      return lines;
    }
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      Product product = line.getProduct();
      lines.add(
          new BgVatSubject.Line(
              firstNonNull(line.getCompanyExTaxTotal(), line.getExTaxTotal()),
              product != null,
              product != null && product.getProductFamily() != null
                  ? product.getProductFamily().getName()
                  : null,
              product != null ? product.getProductTypeSelect() : null));
    }
    return lines;
  }

  /**
   * Протоколите по чл. 117, свързани с фактурата ({@code Invoice.bgVatProtocolMoveList}) —
   * съставени от нея или заварени ръчни. Изолирано, защото HSQLDB не чете {@code Move}.
   */
  protected List<Move> invoiceProtocols(Invoice invoice) {
    return invoice.getBgVatProtocolMoveList() == null
        ? Collections.emptyList()
        : invoice.getBgVatProtocolMoveList();
  }

  /** Ред на данъците с обратно начисляване — маркиран или с данък на ред с обратно начисляване. */
  private static boolean isReverseChargeRow(InvoiceLineTax lineTax, Set<String> codes) {
    if (Boolean.TRUE.equals(lineTax.getReverseCharged())) {
      return true;
    }
    return lineTax.getTaxLine() != null
        && lineTax.getTaxLine().getTax() != null
        && lineTax.getTaxLine().getTax().getCode() != null
        && codes.contains(lineTax.getTaxLine().getTax().getCode().trim());
  }

  private static String protocolLabel(Move protocol) {
    String number = protocol.getBgVatProtocolNumber();
    return isBlank(number)
        ? (isBlank(protocol.getReference()) ? "#" + protocol.getId() : protocol.getReference())
        : number.trim();
  }

  protected void enrichFromMove(BgVatDocumentDto document, Move move) {
    document.setDocumentNumber(isBlank(move.getOrigin()) ? move.getReference() : move.getOrigin());
    document.setDocumentDate(move.getDate() != null ? move.getDate() : move.getOriginDate());
    // Закъснелият протокол: статията е в периода на изискуемостта, а дневникът носи датата на
    // издаване — ППЗДДС чл. 81 ал. 5 („…следва да се отрази в дневниците за продажби и покупки за
    // данъчния период, през който данъкът е станал изискуем…“).
    if (move.getBgVatProtocolDate() != null) {
      document.setDocumentDate(move.getBgVatProtocolDate());
      document.setIssuedLate(
          move.getDate() != null && move.getBgVatProtocolDate().isAfter(move.getDate()));
    }

    // Вид на доставката (поле 02-27/03-45) — feeds both diaries via DTO.supplyTypeArt163a.
    document.setSupplyTypeArt163a(move.getBgVatSupplyType());

    // Вид на стоката/услугата — по операцията на протокола (BgVatSubject).
    document.setSubject(BgVatSubject.forOperation(move.getBgVatMoveVatOperation()));

    Partner partner = move.getPartner();
    if (partner != null) {
      document.setCounterpartyName(BgVatPartyName.of(partner));
      // No invoice here, so the only candidate is Partner.taxNbr.
      document.setPartnerTaxNbr(partner.getTaxNbr());
      identifyCounterparty(document, partner, partner.getTaxNbr());
    }

    List<MoveLine> lines =
        move.getMoveLineList() != null ? move.getMoveLineList() : Collections.emptyList();

    // A protocol (ВОП, чл. 82, чл. 163а, чл. 167а) or a чл. 79/79а correction is decided by its
    // OPERATION, before MODE 1. A credit type on one of its lines only says where the protocol's
    // input side goes (addProtocolPurchaseSide); it must not turn the protocol into a plain
    // purchase and drop its self-charged output.
    String operation = move.getBgVatMoveVatOperation();
    Optional<Route> operationRoute =
        BgVatMoveOperationRouting.forOperation(isBlank(operation) ? null : operation.trim());
    if (operationRoute.isPresent() && operationRoute.get().getKind() != Kind.PURCHASE) {
      routeMoveOperation(document, move, lines, operation.trim());
      return;
    }

    // MODE 1 — line-level mixed entry (our advantage over single-operation tools): if any line
    // carries bgVatCreditType, route EACH such line by it. base = line debit, VAT = line taxAmount.
    boolean anyLineCredit = false;
    for (MoveLine line : lines) {
      if (!isBlank(line.getBgVatCreditType())) {
        anyLineCredit = true;
        break;
      }
    }
    if (anyLineCredit) {
      for (MoveLine line : lines) {
        if (isBlank(line.getBgVatCreditType())) {
          continue;
        }
        accumulatePurchaseColumns(
            document,
            line.getBgVatCreditType(),
            nz(line.getDebit()),
            nz(line.getTaxAmount()),
            "move line " + line.getId());
      }
      document.setTotalVat(sumVatAccountAmount(lines));
      return;
    }

    // MODE 2 — document-level VAT operation.
    String op = move.getBgVatMoveVatOperation();
    if (!isBlank(op)) {
      routeMoveOperation(document, move, lines, op.trim());
      return;
    }

    // MODE 3 — residual: no credit-type line and no operation. VAT from tax lines, base undefined.
    BigDecimal totalVat = BigDecimal.ZERO;
    for (MoveLine line : lines) {
      if (!carriesTax(line)) {
        continue;
      }
      BgVatAmountDto amount = new BgVatAmountDto();
      amount.setTaxCode(line.getTaxCode());
      amount.setVatRatePercent(line.getTaxRate());
      amount.setVatAmount(line.getTaxAmount());
      document.getAmounts().add(amount);
      totalVat = totalVat.add(nz(line.getTaxAmount()));
    }
    document.setTotalVat(totalVat);
    if (totalVat.signum() != 0) {
      document.addWarning(
          "Ръчен запис с ДДС, но без ДДС операция или кредитен тип — данъчната основа не е"
              + " определена");
    }
  }

  // ---------------------------------------------------------------------------
  // Manual-move VAT routing (two-mode model)
  // ---------------------------------------------------------------------------

  /**
   * Route a manual move by its document-level {@code bgVatMoveVatOperation}. Which columns an
   * operation fills is decided by {@link BgVatMoveOperationRouting}; this method computes the
   * amounts. PURCHASE_* operations take the base from the base lines and the VAT from account 4531;
   * protocol operations reverse-compute the base from VAT / rate and populate both the purchase
   * (input) and sales (output) sides.
   */
  protected void routeMoveOperation(
      BgVatDocumentDto document, Move move, List<MoveLine> lines, String op) {
    BigDecimal vat = sumVatAccountAmount(lines);
    BigDecimal baseLines = sumBaseLineAmount(lines);
    document.setTotalVat(vat);

    Optional<Route> found = BgVatMoveOperationRouting.forOperation(op);
    if (!found.isPresent()) {
      document.addWarning("Неразпозната ДДС операция за ръчен запис: " + op);
      return;
    }
    Route route = found.get();
    switch (route.getKind()) {
      case PURCHASE:
        addPurchaseTarget(document, route.getPurchaseTarget(), baseLines, vat);
        return;
      case ART79_CORRECTION:
        routeArt79Correction(document, route, lines);
        break;
      case ART79_DEDUCTION:
        routeArt79Deduction(document, route, lines);
        break;
      case ART79A_ANNUAL:
        routeArt79aAnnualCorrection(document, route, lines);
        break;
      case PROTOCOL:
        routeProtocol(document, move, lines, route, vat, baseLines);
        break;
    }
    applyOperationIdentity(document, move, route);
  }

  /**
   * Протокол по <b>чл. 79 ал. 4 ЗДДС</b> — еднократната корекция на ползван данъчен кредит.
   *
   * <p>Дословно ал. 4: „Начисляването на данъка по ал. 1 - 3 се извършва в данъчния период, през
   * който е възникнало съответното обстоятелство, чрез съставяне на протокол за определяне размера
   * на дължимия данък и отразяването му в <b>дневника за продажбите</b> и справка-декларацията за
   * този данъчен период.“
   *
   * <p><b>Защо НЕ минава през {@link #routeProtocol}.</b> Онзи метод обслужва самоначисляването
   * (ВОП, чл. 163а, услуги от ЕО): там има получена доставка, входящ данъчен кредит по 4531 и
   * данъчна основа, която се обръща от ставката. Тук няма нито едно от трите — корекцията е само
   * начислен данък, без насрещна доставка и без ДО. Прекаран през routeProtocol, протоколът щеше да
   * поражда и ФИКТИВЕН данъчен кредит по к.10/к.11, тоест да си върне сам сумата, която току-що е
   * начислил.
   *
   * <p>Колоната е к.12 — дословно по ППЗДДС Приложение № 10: „Начислен ДДС за доставки по к. 11 и
   * начислен данък (20 %), <b>предвиден в закона в други случаи</b>“. Данъчна основа не се пише:
   * к.11 остава празна, защото доставка няма. Оттам сумата влиза в кл.21, събира се в кл.20 и стига
   * до кл.50.
   */
  protected void routeArt79Correction(
      BgVatDocumentDto document, Route route, List<MoveLine> lines) {
    BigDecimal chargedVat = sumVatSalesAccountAmount(lines);
    if (chargedVat.signum() == 0) {
      document.addWarning(
          "Протокол по чл. 79 ал. 4 без начислен данък по сметка 4532 — нищо не влиза в к.12");
      return;
    }
    document.setTotalVat(chargedVat);
    document.addSalesAmount(route.getSalesVatColumn(), chargedVat);
  }

  /**
   * Протокол по <b>чл. 79 ал. 7 ЗДДС</b> — приспадане на неползван при придобиването данъчен
   * кредит, когато активът излиза с облагаема доставка.
   *
   * <p>Дословно ал. 7: „… чрез съставяне на протокол за определяне размера на неползвания при
   * придобиването данъчен кредит, който лицето може да приспадне, и отразяването му в <b>дневника
   * за покупки</b> и справка-декларацията за този данъчен период.“
   *
   * <p><b>Защо к.11, а не к.14 или к.13.</b> к.14 е заета дословно от друга разпоредба — Приложение
   * № 11 към ППЗДДС я озаглавява „Годишна корекция по чл. 73, ал. 8 ЗДДС (+/-)“, а това тук не е
   * годишна корекция. к.13 („ДДС с право на частичен данъчен кредит“) също не върши работа:
   * формулата на ал. 5 ВЕЧЕ е приложила (1 − К₀), тъй че минаването през к.13 би пуснало сумата и
   * през кл.42 × кл.33 и би я намалило втори път. Остава к.11 „ДДС с право на пълен данъчен кредит“
   * — изчисленото е точно приспадаемата част, тоест влиза цяло в кл.41.
   *
   * <p>Данъчна основа не се пише: к.10 остава празна, защото получена доставка няма.
   */
  protected void routeArt79Deduction(BgVatDocumentDto document, Route route, List<MoveLine> lines) {
    BigDecimal deductibleVat = sumVatAccountAmount(lines);
    if (deductibleVat.signum() == 0) {
      document.addWarning(
          "Протокол по чл. 79 ал. 7 без данъчен кредит по сметка 4531 — нищо не влиза в к.11");
      return;
    }
    document.setTotalVat(deductibleVat);
    document.addPurchaseAmount(route.getPurchaseTarget().getVatColumn(), deductibleVat);
  }

  /**
   * Протокол по <b>чл. 79а ал. 4</b> / <b>чл. 79б ал. 4 ЗДДС</b> — ГОДИШНАТА корекция при изменение
   * в използването на дълготраен актив.
   *
   * <p>Дословно (и в двата члена с едни и същи думи): „Корекцията по ал. 3 се извършва в последния
   * данъчен период на календарната година … и отразяване на изменението в размера на ползвания
   * данъчен кредит по този протокол в <b>дневника за покупки</b> и справка-декларацията за този
   * данъчен период, както следва: 1. със знак <b>(+)</b>, когато е в посока увеличение …; 2. със
   * знак <b>(−)</b>, когато е в посока намаление.“
   *
   * <p><b>Колоната е к.11 и за двете посоки</b> — не по извод, а дословно по <b>ППЗДДС чл. 66 ал.
   * 9</b>: „…протоколите по ал. 4, <b>5 и 6</b>, с които се определя размерът на
   * увеличението/намалението на размера на данъчния кредит, се посочват в колона 11 „ДДС с право на
   * пълен данъчен кредит“ на дневника за покупките.“ (ал. 5 е чл. 79а, ал. 6 е чл. 79б.)
   *
   * <p>⚠️ <b>к.14 НЕ е тази колона, въпреки думата „годишна“.</b> Приложение № 11 я озаглавява
   * „Годишна корекция по <b>чл. 73, ал. 8</b> ЗДДС (+/−)“ — изчерпателно заглавие за
   * преизчисляването на частичния данъчен кредит, което е друга материя и се отнася за НЕ-ДМА.
   * Насочена там, годишната корекция на актив щеше да се събере с чуждо число в кл.43.
   *
   * <p>⚠️ <b>Знакът идва от самата статия, не от отделен маршрут.</b> {@link
   * #sumVatAccountAmount(List)} брои Σ(дебит − кредит) по 4531, тъй че намалението — кредитен ред —
   * излиза отрицателно и стига до к.11 със знак (−), както ал. 4 т. 2 го иска. Разделено на две
   * колони по посока, намалението щеше да излезе като начислен данък в дневника за ПРОДАЖБИТЕ,
   * където нито чл. 79а, нито правилникът го пращат.
   *
   * <p>Данъчна основа не се пише: к.10 остава празна, защото получена доставка няма.
   */
  protected void routeArt79aAnnualCorrection(
      BgVatDocumentDto document, Route route, List<MoveLine> lines) {
    BigDecimal correction = sumVatAccountAmount(lines);
    if (correction.signum() == 0) {
      document.addWarning(
          "Протокол по чл. 79а/79б ал. 4 без изменение по сметка 4531 — нищо не влиза в к.11");
      return;
    }
    document.setTotalVat(correction);
    document.addPurchaseAmount(route.getPurchaseTarget().getVatColumn(), correction);
  }

  /**
   * Protocol / self-assessment operations. The protocol states ONE base and ONE tax (ЗДДС чл. 117
   * ал. 2 т. 5 и т. 8): the tax is the charged amount on 4532 (начислен данък); when only 4531 is
   * posted it falls back to that amount (self-charge, equal by construction). The base is
   * reverse-computed as tax / rate (as Microinvest does); rate is the manual {@code
   * bgVatMoveVatRate} when set ({@link #manualRate}), otherwise the one rate on the lines ({@link
   * #lineRates}). Both are FRACTIONS (0.20) by the time they meet here.
   *
   * <p>Several rates on the lines and no manual rate: the base is per rate, never tax / the first
   * rate ({@link #mixedRateBase}). A manual rate NEXT TO several rates on the lines is a blocking
   * error ({@link BgVatValidator#PROTOCOL_MANUAL_RATE_MIXED_CODE}) — the protocol has ONE rate
   * (ЗДДС чл. 117 ал. 2 т. 6) and which one is right is unknown; SAF-T refuses it for the same
   * reason.
   *
   * <p>An explicit base ({@code Move.bgVatProtocolBase} — the protocol generated from an invoice)
   * wins over every computation: tax / rate loses cents (1234.57 × 20 % = 246.91, but 246.91 / 0.20
   * = 1234.55). The rate only checks it.
   *
   * <p>The same base and tax feed both sides: the output side goes to the operation's sales
   * columns, the input side to the purchase columns of each line's credit type ({@link
   * #addProtocolPurchaseSide}).
   */
  protected void routeProtocol(
      BgVatDocumentDto document,
      Move move,
      List<MoveLine> lines,
      Route route,
      BigDecimal vat,
      BigDecimal baseLines) {
    BigDecimal tax = sumVatSalesAccountAmount(lines);
    if (tax.signum() == 0) {
      tax = vat;
    }

    BigDecimal manualRate = manualRate(document, move);
    Set<BigDecimal> lineRates = lineRates(lines);
    BigDecimal derivedRate = lineRates.size() == 1 ? lineRates.iterator().next() : null;
    BigDecimal rate = manualRate.signum() != 0 ? manualRate : derivedRate;

    if (manualRate.signum() != 0 && lineRates.size() > 1) {
      document.addError(
          BgVatValidator.PROTOCOL_MANUAL_RATE_MIXED_CODE,
          "ДДС протокол с ръчна ставка "
              + plain(manualRate)
              + " до смесени ставки по редовете "
              + lineRates.stream().map(BgVatDocumentCollector::plain).collect(joining(", "))
              + ": ставката на протокола е една (ЗДДС чл. 117 ал. 2 т. 6), а коя е вярната — не се"
              + " знае. Махнете ръчната ставка (основата тогава е по ставката на всеки ред) или"
              + " поправете ставките на редовете.");
    }

    BigDecimal base;
    BigDecimal explicitBase = move.getBgVatProtocolBase();
    if (explicitBase.signum() != 0) {
      base = explicitBase;
      if (rate != null && rate.signum() != 0 && lineRates.size() <= 1) {
        BigDecimal expected = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (expected.subtract(tax).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
          document.addWarning(
              "Разминаване на протокол: основа "
                  + plain(base)
                  + " × ставка "
                  + plain(rate)
                  + " = "
                  + expected
                  + " ≠ данък "
                  + plain(tax));
        }
      }
      if (baseLines.signum() != 0
          && base.subtract(baseLines).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
        document.addWarning(
            "Разминаване база на протокол: изрична " + base + " ≠ базови редове " + baseLines);
      }
    } else if (manualRate.signum() == 0 && lineRates.size() > 1) {
      base = mixedRateBase(document, lines, tax, baseLines);
      if (base == null) {
        base = BigDecimal.ZERO;
      } else if (baseLines.signum() != 0
          && base.subtract(baseLines).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
        document.addWarning(
            "Разминаване база на протокол: изчислена " + base + " ≠ базови редове " + baseLines);
      }
    } else if (rate != null && rate.signum() != 0) {
      base = tax.divide(rate, 2, RoundingMode.HALF_UP);
      // Нищо не сверява изведената основа — не тихо: обратното смятане губи стотинки (185.19 /
      // 0.20 = 925.95, а основата е 925.93). Указанието е изричната основа (bgVatProtocolBase).
      if (baseLines.signum() == 0) {
        document.setDerivedBase(base);
      }
      // Cross-check: reverse-computed base vs any actual base lines. Warn, never block.
      if (baseLines.signum() != 0
          && base.subtract(baseLines).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
        document.addWarning(
            "Разминаване база на протокол: изчислена " + base + " ≠ базови редове " + baseLines);
      }
      // Manual rate vs the ONE rate on the lines — a blocking error, like SAF-T
      // (BgSaftDiaryCells.rates): the protocol has one rate (ЗДДС чл. 117 ал. 2 т. 6) and which one
      // is right is unknown. ⚠️ manualRate comes from a generated decimal getter and cannot be null
      // (ZERO instead); derivedRate comes from our own method, which really does return null — that
      // guard stays. Both are fractions here, so 0.20 against a line's 20 % is NOT a discrepancy. A
      // manual rate next to SEVERAL line rates is the blocking error above.
      if (manualRate.signum() != 0
          && derivedRate != null
          && derivedRate.signum() != 0
          && manualRate.subtract(derivedRate).abs().compareTo(new BigDecimal("0.0001")) > 0) {
        document.addError(
            BgVatValidator.PROTOCOL_MANUAL_RATE_CONFLICT_CODE,
            "ДДС протокол с ръчна ставка "
                + plain(manualRate)
                + " ≠ ставката на редовете "
                + plain(derivedRate)
                + ": ставката на протокола е една (ЗДДС чл. 117 ал. 2 т. 6), а коя е вярната — не"
                + " се знае. Поправете ръчната ставка или ставката на редовете.");
      }
    } else {
      base = baseLines;
      document.addWarning("Протокол без ставка — базата е взета от базовите редове");
    }

    // Purchase (input) side — by the credit type of each line.
    addProtocolPurchaseSide(document, route, lines, base, tax);

    // Sales (output, self-charged) side. The route leaves a column null when the operation has no
    // such output.
    if (route.getSalesBaseColumn() != null) {
      document.addSalesAmount(route.getSalesBaseColumn(), base);
    }
    if (route.getSalesVatColumn() != null) {
      document.addSalesAmount(route.getSalesVatColumn(), tax);
    }
  }

  /**
   * Покупната страна на протокола — по КРЕДИТНИЯ ТИП на редовете, не по операцията. ППЗДДС чл. 97а
   * ал. 4 б. „б“ и чл. 49а ал. 5 т. 1: „данъчната основа в колони 9, 10 или 12 и начисления данък в
   * колони 11 или 13“.
   *
   * <p>Различни типове в един протокол не са грешка. Протоколът е ЕДИН ред в дневника, а основата и
   * данъкът му се разпределят между колоните по редовете:
   *
   * <ul>
   *   <li><b>Носители</b> са базовите редове, ако протоколът има такива. Иначе са редовете по 4531
   *       и редовете с кредитен тип; 4532 не е носител, тя е продажбената страна.
   *   <li><b>Делът</b> на носителя е сумата му (дебит − кредит). Последният взема остатъка, за да
   *       са сборовете точно основата и данъкът на протокола.
   *   <li>к.13 получава ПЪЛНИЯ данък: кл.40 = кл.41 + кл.42 × кл.33 + кл.43 прилага коефициента
   *       сам. к.9 сгъва основа + данък.
   *   <li>Носител без кредитен тип е пълен кредит с предупреждение. Същото важи за протокол без
   *       нито един носител.
   * </ul>
   */
  protected void addProtocolPurchaseSide(
      BgVatDocumentDto document,
      Route route,
      List<MoveLine> lines,
      BigDecimal base,
      BigDecimal tax) {
    List<MoveLine> carriers = new ArrayList<>();
    for (MoveLine line : lines) {
      if (!isVatAccount(line) && hasTaxRate(line)) {
        carriers.add(line);
      }
    }
    if (carriers.isEmpty()) {
      for (MoveLine line : lines) {
        if (!isVatSalesAccount(line)
            && (isVatPurchaseAccount(line) || !isBlank(line.getBgVatCreditType()))) {
          carriers.add(line);
        }
      }
    }
    BigDecimal total = BigDecimal.ZERO;
    for (MoveLine carrier : carriers) {
      total = total.add(signedAmount(carrier));
    }
    if (total.signum() == 0) {
      document.addWarning("Протокол без вид данъчен кредит — приет пълен ДК");
      addPurchaseTarget(document, route.purchaseTargetFor(null), base, tax);
      return;
    }

    BigDecimal baseLeft = base;
    BigDecimal taxLeft = tax;
    for (int i = 0; i < carriers.size(); i++) {
      MoveLine carrier = carriers.get(i);
      BigDecimal lineBase = baseLeft;
      BigDecimal lineTax = taxLeft;
      if (i < carriers.size() - 1) {
        BigDecimal share = signedAmount(carrier);
        lineBase = base.multiply(share).divide(total, 2, RoundingMode.HALF_UP);
        lineTax = tax.multiply(share).divide(total, 2, RoundingMode.HALF_UP);
        baseLeft = baseLeft.subtract(lineBase);
        taxLeft = taxLeft.subtract(lineTax);
      }
      String creditType = carrier.getBgVatCreditType();
      if (BgVatPurchaseCreditTypeMapping.isDefaulted(creditType)) {
        warnDefaultedCredit(document, "move line " + carrier.getId());
      }
      addPurchaseTarget(document, route.purchaseTargetFor(creditType), lineBase, lineTax);
    }
  }

  private static BigDecimal signedAmount(MoveLine line) {
    return nz(line.getDebit()).subtract(nz(line.getCredit()));
  }

  /**
   * Какво протоколът сам предписва в идентификацията на реда си — номер и к.8а.
   *
   * <p><b>Номерът</b> е собственият на протокола: {@code Move.bgVatProtocolNumber}, даден при
   * осчетоводяване от поредицата на дружеството ({@link BgVatProtocolNumbering}) или въведен ръчно
   * за реално издаден протокол ({@code BgVatProtocolNumberEntryService}). ЗДДС чл. 117 ал. 2 т. 1
   * иска „номер и дата“ на протокола, не номера на фактурата на доставчика. Без него — заварен
   * протокол или дружество без поредица — е ГРЕШКА: Прил. № 12 казва, че „Номер на документ“ „се
   * попълва задължително“. Редът пак носи досегашния номер, за да се познае кой е. При вноса това е
   * номерът на статията: {@code Move.origin} там е MRN-ът на митническия документ (вторият ред —
   * {@link #customsDocument}).
   *
   * <p><b>к.8а</b>: при внос по чл. 167а кодът е „03“ — ППЗДДС чл. 49а ал. 4 т. 1 и ал. 5 т. 1 („в
   * колона 8а … с код "03"“). Друга стойност в {@code bgVatSupplyType} се заменя с предупреждение.
   */
  protected void applyOperationIdentity(BgVatDocumentDto document, Move move, Route route) {
    String number = move.getBgVatProtocolNumber();
    if (!isBlank(number)) {
      document.setDocumentNumber(number.trim());
      if (!BgVatProtocolNumbering.isWellFormed(number.trim())) {
        document.addWarning(
            "Номерът на ДДС протокола „"
                + number.trim()
                + "“ не е 10 арабски цифри — проверете поредицата „"
                + BgVatProtocolNumbering.SEQUENCE_CODE
                + "“ на дружеството");
      }
    } else {
      if (route.hasCustomsDocument()) {
        document.setDocumentNumber(move.getReference());
      }
      String fallback = document.getDocumentNumber();
      document.addError(
          BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE,
          "ДДС протоколът"
              + (isBlank(fallback) ? "" : " „" + fallback.trim() + "“")
              + " няма номер (ЗДДС чл. 117 ал. 2 т. 1; Прил. № 12 — „Номер на документ“ се"
              + " попълва задължително). Въведете номера на издадения протокол от статията или"
              + " настройте поредицата „"
              + BgVatProtocolNumbering.SEQUENCE_CODE
              + "“ на дружеството.");
    }

    String supplyType = route.getSupplyType();
    if (supplyType != null) {
      String entered = document.getSupplyTypeArt163a();
      if (!isBlank(entered) && !supplyType.equals(entered.trim())) {
        document.addWarning(
            "Вид на доставката „"
                + entered.trim()
                + "“ не е за внос по чл. 167а — приет „"
                + supplyType
                + "“ (ППЗДДС чл. 49а ал. 4 т. 1)");
      }
      document.setSupplyTypeArt163a(supplyType);
    }
  }

  /**
   * ИН на контрагента за дневника (02-07 / 03-08) и ДДС номерът му за VIES — от една и съща чиста
   * функция ({@link BgVatCounterpartyId}) по правилото на Прил. № 12: ДДС номер в България или ДЧ;
   * иначе ИН по § 1 ДР ППЗДДС; иначе „999999999999999“ — без ЕГН.
   */
  protected void identifyCounterparty(BgVatDocumentDto document, Partner partner, String taxNbr) {
    String country =
        partner != null
                && partner.getMainAddress() != null
                && partner.getMainAddress().getCountry() != null
            ? partner.getMainAddress().getCountry().getAlpha2Code()
            : null;
    BgVatCounterpartyId id =
        BgVatCounterpartyId.of(
            partner != null ? partner.getPartnerTypeSelect() : null,
            partner != null ? partner.getBgIdentifierType() : null,
            partner != null ? partner.getRegistrationCode() : null,
            taxNbr,
            country);
    document.setCounterpartyVatNumber(id.getVatNumber());
    document.setCounterpartyDiaryId(id.getDiaryId());
  }

  /** Input VAT = Σ (debit − credit) over lines posted to the VAT-purchases account (4531). */
  protected BigDecimal sumVatAccountAmount(List<MoveLine> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (MoveLine line : lines) {
      if (isVatPurchaseAccount(line)) {
        sum = sum.add(nz(line.getDebit())).subtract(nz(line.getCredit()));
      }
    }
    return sum;
  }

  /**
   * Charged (output) VAT = Σ (credit − debit) over lines posted to the VAT-sales account (4532).
   */
  protected BigDecimal sumVatSalesAccountAmount(List<MoveLine> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (MoveLine line : lines) {
      if (isVatSalesAccount(line)) {
        sum = sum.add(nz(line.getCredit())).subtract(nz(line.getDebit()));
      }
    }
    return sum;
  }

  /**
   * Taxable base = Σ (debit − credit) over base lines: those with a tax rate that are NEITHER VAT
   * account. Excluding 4532 ensures the charged-tax (output) line is never mistaken for base.
   */
  protected BigDecimal sumBaseLineAmount(List<MoveLine> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (MoveLine line : lines) {
      if (!isVatAccount(line) && hasTaxRate(line)) {
        sum = sum.add(nz(line.getDebit())).subtract(nz(line.getCredit()));
      }
    }
    return sum;
  }

  protected boolean isVatPurchaseAccount(MoveLine line) {
    return hasAccountCodePrefix(line, ACCOUNT_VAT_PURCHASE);
  }

  protected boolean isVatSalesAccount(MoveLine line) {
    return hasAccountCodePrefix(line, ACCOUNT_VAT_SALES);
  }

  protected boolean isVatAccount(MoveLine line) {
    return isVatPurchaseAccount(line) || isVatSalesAccount(line);
  }

  private static boolean hasAccountCodePrefix(MoveLine line, String prefix) {
    return line.getAccount() != null
        && line.getAccount().getCode() != null
        && line.getAccount().getCode().startsWith(prefix);
  }

  private static boolean hasTaxRate(MoveLine line) {
    return line.getTaxRate().signum() != 0
        || (line.getTaxLineSet() != null && !line.getTaxLineSet().isEmpty());
  }

  /**
   * Ръчната ставка на протокола като ДРОБ — полето е „напр. 0.20“ ({@code Move.bgVatMoveVatRate}).
   *
   * <p>Въведена в проценти (над 1, например 20), тя се дели на 100 с предупреждение. След това
   * трябва да е законова — 0, 0.09 или 0.20 ({@link #STATUTORY_RATES}) — иначе е ГРЕШКА: от нея се
   * обръща основата, тоест грешна ставка значи грешна основа и в двата дневника. Нула значи „не е
   * въведена“ и тогава важи изведената от редовете.
   */
  protected BigDecimal manualRate(BgVatDocumentDto document, Move move) {
    // ⚠️ Генерираният getter не връща null, а ZERO.
    BigDecimal rate = move.getBgVatMoveVatRate();
    if (rate.compareTo(BigDecimal.ONE) > 0) {
      BigDecimal fraction = rate.movePointLeft(2);
      document.addWarning(
          "Ставката на протокола е въведена в проценти ("
              + plain(rate)
              + ") — приета като "
              + plain(fraction));
      rate = fraction;
    }
    BigDecimal checked = rate;
    if (STATUTORY_RATES.stream().noneMatch(statutory -> statutory.compareTo(checked) == 0)) {
      document.addError(
          BgVatValidator.PROTOCOL_RATE_INVALID_CODE,
          "Ставката на протокола "
              + plain(rate)
              + " не е законова: 0, 0.09 или 0.20 (ЗДДС чл. 66 ал. 1, чл. 66а, чл. 66б)");
    }
    return rate;
  }

  /**
   * Ставката на протокола за печата ({@link BgVatMoveDiaryRows#protocolRates}) — по правилото на
   * {@link #routeProtocol}: ръчната ({@link #manualRate}, проценти → дроб), иначе тези по редовете.
   * Предупрежденията на {@link #manualRate} остават в документа на дневника, не тук.
   */
  protected Set<BigDecimal> protocolRates(Move move) {
    BigDecimal manual = manualRate(new BgVatDocumentDto(), move);
    if (manual.signum() != 0) {
      return Collections.singleton(manual.stripTrailingZeros());
    }
    return move.getMoveLineList() == null
        ? Collections.emptySet()
        : lineRates(move.getMoveLineList());
  }

  /**
   * Различните ненулеви ставки по редовете, като ДРОБИ, по реда на срещане (when no manual protocol
   * rate is given). Една — тя е ставката на протокола; повече — основата е по ставка ({@link
   * #mixedRateBase}).
   *
   * <p>⚠️ {@code MoveLine.taxRate} е в ПРОЦЕНТИ. Axelor го пише при осчетоводяване от {@code
   * getTotalTaxRateInPercentage(taxLineSet)} — сбора на {@code TaxLine.value}, който за BG_SRP_20 е
   * 20 ({@code account_taxLine.csv}). Неразделен на 100, той правеше основата 100 пъти по-малка.
   */
  private static Set<BigDecimal> lineRates(List<MoveLine> lines) {
    Set<BigDecimal> rates = new LinkedHashSet<>();
    for (MoveLine line : lines) {
      if (line.getTaxRate().signum() != 0) {
        rates.add(fraction(line.getTaxRate()));
      }
    }
    return rates;
  }

  private static BigDecimal fraction(BigDecimal percent) {
    return percent.movePointLeft(2).stripTrailingZeros();
  }

  /**
   * Основата на протокол със СМЕСЕНИ ставки по редовете — по ставката на всеки ред (ЗДДС чл. 117
   * ал. 2 т. 5 и т. 8: основата и данъкът на протокола). Данъкът през една ставка я изкривява: 1000
   * × 20 % + 500 × 9 % е данък 245, а 245 / 0.20 = 1225 вместо 1500 в к.13 и в кл.12.
   *
   * <ol>
   *   <li><b>Редовете по 4532 или 4531 носят ставката си</b> — всеки ред с ненулева сума. Основата
   *       е Σ (данък на ставката / ставката), като обратното изчисление при една ставка. Първо се
   *       гледа страната, от която е данъкът; другата — само ако сборът ѝ е същият данък.
   *   <li><b>Иначе базовите редове</b> — те носят ставката и основата си, затова основата е сборът
   *       им. Данъкът по ставките им се сверява с начисления — с предупреждение, не с грешка.
   *   <li><b>Иначе ГРЕШКА</b> ({@link BgVatValidator#PROTOCOL_MIXED_RATE_BASE_CODE}): основата не
   *       се определя по ставка, а през една ставка би била грешна. Връща {@code null}.
   * </ol>
   */
  protected BigDecimal mixedRateBase(
      BgVatDocumentDto document, List<MoveLine> lines, BigDecimal tax, BigDecimal baseLines) {
    boolean fromSales = sumVatSalesAccountAmount(lines).signum() != 0;
    Map<BigDecimal, BigDecimal> taxByRate = taxByRate(lines, fromSales, tax);
    if (taxByRate == null) {
      taxByRate = taxByRate(lines, !fromSales, tax);
    }
    if (taxByRate != null) {
      BigDecimal base = BigDecimal.ZERO;
      for (Map.Entry<BigDecimal, BigDecimal> entry : taxByRate.entrySet()) {
        base = base.add(entry.getValue().divide(entry.getKey(), 2, RoundingMode.HALF_UP));
      }
      return base;
    }

    if (baseLines.signum() != 0) {
      Map<BigDecimal, BigDecimal> baseByRate = new LinkedHashMap<>();
      for (MoveLine line : lines) {
        if (!isVatAccount(line) && hasTaxRate(line)) {
          baseByRate.merge(fraction(line.getTaxRate()), signedAmount(line), BigDecimal::add);
        }
      }
      BigDecimal expected = BigDecimal.ZERO;
      for (Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
        expected = expected.add(entry.getValue().multiply(entry.getKey()));
      }
      expected = expected.setScale(2, RoundingMode.HALF_UP);
      if (expected.subtract(tax).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
        document.addWarning(
            "Разминаване данък на протокол със смесени ставки: по ставките на базовите редове "
                + expected
                + " ≠ начислен "
                + plain(tax));
      }
      return baseLines;
    }

    document.addError(
        BgVatValidator.PROTOCOL_MIXED_RATE_BASE_CODE,
        "ДДС протокол със смесени ставки по редовете (ЗДДС чл. 117 ал. 2 т. 5 и т. 8): основата не"
            + " се определя по ставка — нито всеки ред по 4532/4531 носи ставката си, нито има"
            + " базови редове. Сложете данъка на всеки ред по 4532 или добавете базовите редове.");
    return null;
  }

  /**
   * Данъкът по ставка от редовете на едната ДДС страна ({@code sales}: 4532, кредит − дебит; иначе
   * 4531, дебит − кредит), или {@code null}, ако ред с ненулева сума е без ставка, страната е
   * празна или сборът ѝ не е данъкът на протокола.
   */
  private Map<BigDecimal, BigDecimal> taxByRate(
      List<MoveLine> lines, boolean sales, BigDecimal tax) {
    Map<BigDecimal, BigDecimal> byRate = new LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (MoveLine line : lines) {
      if (sales ? !isVatSalesAccount(line) : !isVatPurchaseAccount(line)) {
        continue;
      }
      BigDecimal amount = sales ? signedAmount(line).negate() : signedAmount(line);
      if (amount.signum() == 0) {
        continue;
      }
      if (line.getTaxRate().signum() == 0) {
        return null;
      }
      byRate.merge(fraction(line.getTaxRate()), amount, BigDecimal::add);
      total = total.add(amount);
    }
    return byRate.isEmpty() || total.compareTo(tax) != 0 ? null : byRate;
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  /**
   * Route a purchase line into к.09–к.13 by its {@code bgVatCreditType}; the columns come from
   * {@link BgVatPurchaseCreditTypeMapping}. Blank defaults to full credit and logs.
   */
  protected void accumulatePurchaseColumns(
      BgVatDocumentDto document, String creditType, BigDecimal base, BigDecimal vat, String ref) {
    if (BgVatPurchaseCreditTypeMapping.isDefaulted(creditType)) {
      warnDefaultedCredit(document, ref);
    }
    addPurchaseTarget(
        document, BgVatPurchaseCreditTypeMapping.forCreditType(creditType), nz(base), nz(vat));
  }

  private static void warnDefaultedCredit(BgVatDocumentDto document, String ref) {
    log.warn("BG VAT purchase {}: no bgVatCreditType — defaulting to full credit (1)", ref);
    document.addWarning("Ред без вид данъчен кредит — приет пълен ДК (" + ref + ")");
  }

  /**
   * Write one base/VAT pair into the target's columns. A null column means that amount is not
   * reported; a folded target puts base + VAT into the base column (к.09).
   */
  private static void addPurchaseTarget(
      BgVatDocumentDto document, BgVatPurchaseTarget target, BigDecimal base, BigDecimal vat) {
    if (target.getBaseColumn() != null) {
      document.addPurchaseAmount(
          target.getBaseColumn(), target.isVatFoldedIntoBase() ? base.add(vat) : base);
    }
    if (target.getVatColumn() != null) {
      document.addPurchaseAmount(target.getVatColumn(), vat);
    }
  }

  /**
   * NAP document type (вид на документа). Block C foundation is now in place: the field exists on
   * both Invoice and Move. Rule — read from the Invoice for invoice-based documents, otherwise from
   * the Move. The value may still be {@code null} until a user fills it in; the validator turns a
   * missing value into a blocking error for production.
   */
  protected String resolveNapDocumentType(Invoice invoice, Move move) {
    if (invoice != null) {
      return invoice.getNapDocumentTypeSelect();
    }
    return move.getNapDocumentTypeSelect();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** {@code Tax.code} of every tax line; a line without tax gives an empty list. */
  private static List<String> taxCodes(Set<TaxLine> taxLines) {
    List<String> codes = new ArrayList<>();
    if (taxLines != null) {
      for (TaxLine taxLine : taxLines) {
        if (taxLine != null && taxLine.getTax() != null) {
          codes.add(taxLine.getTax().getCode());
        }
      }
    }
    return codes;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
    return first != null ? first : second;
  }
}
