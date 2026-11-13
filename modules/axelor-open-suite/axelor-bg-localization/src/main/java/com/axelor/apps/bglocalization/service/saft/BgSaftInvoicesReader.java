package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.bglocalization.db.BgSafVatCellCode;
import com.axelor.apps.bglocalization.db.repo.BgSafVatCellCodeRepository;
import com.axelor.apps.bglocalization.service.saft.BgSaftProtocolInvoices.DiaryDocument;
import com.axelor.apps.bglocalization.service.saft.BgSaftProtocolInvoices.MoveLineRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftProtocolInvoices.VatSide;
import com.axelor.apps.bglocalization.service.vat.BgVatMoveDiaryRows;
import com.axelor.apps.bglocalization.service.vat.BgVatReportingPeriod;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * ЕДИНСТВЕНОТО МЯСТО, КОЕТО ЧЕТЕ БАЗАТА ЗА SalesInvoices/PurchaseInvoices (G5a) — всичко надолу
 * работи върху стойности.
 *
 * <p><b>⚠️ ФАКТУРИТЕ СА ТЕЗИ НА СТАТИИТЕ НА ДНЕВНИКА.</b> Списъкът е фактурите, чиято статия е в
 * {@link BgSaftMoveScope#PERIOD_MOVES} — същият предикат, по който G2c чете продуктите и данъците
 * на фактурите. Така документът и транзакцията му в GeneralLedgerEntries влизат в един и същи месец
 * и S.I.18 сочи GL.9.
 *
 * <p><b>⚠️ ПАРЧЕТАТА СА НА ДНЕВНИКА</b> (моделът на FEC): първо само id-тата, после стойностите по
 * {@link BgSaftGlEntriesReader#CHUNK} фактури и след всяко парче {@code JPA.clear()}. Нито една
 * заявка не зарежда обект.
 *
 * <p><b>⚠️ СТАТИИТЕ С ДДС БЕЗ ФАКТУРА МИНАВАТ ПРЕЗ ДНЕВНИКА (G5b).</b> Кандидатите са статиите на
 * периода с ДДС операция, кредитен тип или данък на ред ({@link #VAT_MOVES}); всяка се зарежда и
 * минава през {@link BgVatMoveDiaryRows} — същия колектор, филтри и колони като PRODAGBI/POKUPKI.
 * Тук, и само тук, четецът зарежда обекти: колекторът работи върху {@code Move}. Парчетата и {@code
 * JPA.clear()} са същите. Статия, която дневникът носи, а GL не ({@link #DIARY_ONLY_MOVES} —
 * изключена от счетоводството или откриваща), е предупреждение, не тишина.
 */
@Singleton
public class BgSaftInvoicesReader {

  /** Фактурите на периода — по статията им, {@link BgSaftMoveScope#PERIOD_MOVES} дословно. */
  public static final String INVOICE_IDS =
      "SELECT invoice.id FROM Invoice invoice JOIN invoice.move self WHERE "
          + BgSaftMoveScope.PERIOD_MOVES
          + " ORDER BY invoice.id";

  /** Шапките на едно парче. ⚠️ LEFT JOIN: фактура без партньор или сметка остава и се вижда. */
  static final String INVOICES =
      "SELECT invoice.id, invoice.invoiceId, invoice.supplierInvoiceNb,"
          + " invoice.operationTypeSelect, invoice.napDocumentTypeSelect, invoice.invoiceDate,"
          + " invoice.originDate, partner.id, partnerAccount.id, move.id, move.reference,"
          + " period.fromDate, invoice.bgVatSupplyType"
          + " FROM Invoice invoice JOIN invoice.move move LEFT JOIN invoice.partner partner"
          + " LEFT JOIN invoice.partnerAccount partnerAccount LEFT JOIN move.period period"
          + " WHERE invoice.id IN (:ids)";

  /** Редовете на едно парче — сумите във валутата на дружеството. */
  static final String LINES =
      "SELECT line.id, invoice.id, line.sequence, line.typeSelect, product.id, product.code,"
          + " line.productName, line.description, line.qty, line.companyExTaxTotal,"
          + " line.companyInTaxTotal, account.id, line.bgVatCreditType"
          + " FROM InvoiceLine line JOIN line.invoice invoice LEFT JOIN line.product product"
          + " LEFT JOIN line.account account"
          + " WHERE invoice.id IN (:ids)";

  /** Данъците на редовете — ставката е {@code TaxLine.value}, в проценти. */
  static final String LINE_TAXES =
      "SELECT line.id, taxLine.id, tax.code, taxLine.value"
          + " FROM InvoiceLine line JOIN line.taxLineSet taxLine JOIN taxLine.tax tax"
          + " WHERE line.invoice.id IN (:ids)";

  /** Данъкът на фактурата по ставка и дали е обратно начисляване. */
  static final String INVOICE_TAXES =
      "SELECT invoice.id, taxLine.id, tax.companyTaxTotal, tax.reverseCharged"
          + " FROM InvoiceLineTax tax JOIN tax.invoice invoice LEFT JOIN tax.taxLine taxLine"
          + " WHERE invoice.id IN (:ids)";

  /**
   * С ДДС: операция, кредитен тип или данък на ред — кандидатите; решава {@code isVatRelevant}. ⚠️
   * {@code NULL <> ''} не е истина — празната операция и празният кредитен тип не я вкарват.
   */
  static final String WITH_VAT =
      " AND self.invoice IS NULL"
          + " AND (self.bgVatMoveVatOperation <> ''"
          + " OR EXISTS (SELECT line.id FROM MoveLine line WHERE line.move = self"
          + " AND (line.bgVatCreditType <> '' OR line.taxLineSet IS NOT EMPTY"
          + " OR line.taxAmount <> 0)))";

  /** Статиите на периода с ДДС без фактура — само id-тата. */
  public static final String VAT_MOVES =
      "SELECT self.id FROM Move self WHERE "
          + BgSaftMoveScope.PERIOD_MOVES
          + WITH_VAT
          + " ORDER BY self.id";

  /**
   * Статиите, които дневникът носи, а GL — не: обхватът на дневника ({@code
   * BgVatDocumentCollector.findPostedMoves} — дружество, осчетоводени, {@code date} в периода)
   * минус {@link BgSaftMoveScope#PERIOD_MOVES} — изключените от счетоводството и откриващите.
   */
  public static final String DIARY_ONLY_MOVES =
      "SELECT self.id FROM Move self WHERE self.company.id = :companyId"
          + " AND self.statusSelect = :posted"
          + " AND self.date >= :fromDate AND self.date <= :toDate"
          + " AND (self.ignoreInAccountingOk = true OR self.functionalOriginSelect = :openingOrigin)"
          + WITH_VAT
          + " ORDER BY self.id";

  /**
   * Фактурите на месеца, отразени в дневниците на ДРУГ месец („Период на отразяване по ЗДДС“).
   * SAF-T остава по статията (решение Р12 от 02.11.2026, до отговора на НАП) — това е само
   * предупреждението.
   */
  public static final String REPORTED_ELSEWHERE =
      "SELECT invoice.id, invoice.invoiceId, invoice.supplierInvoiceNb,"
          + " invoice.bgVatReportingPeriod FROM Invoice invoice JOIN invoice.move self WHERE "
          + BgSaftMoveScope.PERIOD_MOVES
          + " AND invoice.bgVatReportingPeriod <> ''"
          + " AND (invoice.bgVatReportingPeriod < :periodFrom"
          + " OR invoice.bgVatReportingPeriod > :periodTo)"
          + " ORDER BY invoice.id";

  /** Фактурите, отразени в дневниците на този месец, чиято статия е в друг — не са в SAF-T му. */
  public static final String REPORTED_HERE =
      "SELECT invoice.id, invoice.invoiceId, invoice.supplierInvoiceNb, self.date"
          + " FROM Invoice invoice JOIN invoice.move self WHERE "
          + BgSaftMoveScope.POSTED_MOVES
          + " AND invoice.bgVatReportingPeriod >= :periodFrom"
          + " AND invoice.bgVatReportingPeriod <= :periodTo"
          + " AND (self.date < :fromDate OR self.date > :toDate)"
          + " ORDER BY invoice.id";

  /** Периодът на дружеството по касова отчетност по ДДС (глава 17а ЗДДС). */
  static final String CASH_ACCOUNTING =
      "SELECT self.bgVatCashAccountingFrom, self.bgVatCashAccountingTo FROM Company self"
          + " WHERE self.id = :companyId";

  /** Статиите на едно парче — обекти, защото колекторът работи върху тях. */
  static final String MOVES = "SELECT self FROM Move self WHERE self.id IN (:ids) ORDER BY self.id";

  protected final BgSafVatCellCodeRepository cellCodeRepo;
  protected final BgVatMoveDiaryRows diaryRows;

  @Inject
  public BgSaftInvoicesReader(
      BgSafVatCellCodeRepository cellCodeRepo, BgVatMoveDiaryRows diaryRows) {
    this.cellCodeRepo = cellCodeRepo;
    this.diaryRows = diaryRows;
  }

  /** Фактурите на периода, редовете и данъците им, и статиите с ДДС без фактура. */
  public BgSaftInvoicesAssembler.Source source(BgSaftMoveScope scope) {
    BgSaftInvoicesAssembler.Source source = new BgSaftInvoicesAssembler.Source();
    source.cashAccounting(cashAccounting(scope));
    List<Long> ids = invoiceIds(scope);
    for (int from = 0; from < ids.size(); from += BgSaftGlEntriesReader.CHUNK) {
      chunk(ids.subList(from, Math.min(ids.size(), from + BgSaftGlEntriesReader.CHUNK)), source);
      clear();
    }
    List<Long> moves = moveIds(VAT_MOVES, scope);
    for (int from = 0; from < moves.size(); from += BgSaftGlEntriesReader.CHUNK) {
      for (Move move :
          moves(moves.subList(from, Math.min(moves.size(), from + BgSaftGlEntriesReader.CHUNK)))) {
        diaryDocuments(move).forEach(source::add);
      }
      clear();
    }
    List<Long> outside = moveIds(DIARY_ONLY_MOVES, scope);
    for (int from = 0; from < outside.size(); from += BgSaftGlEntriesReader.CHUNK) {
      for (Move move :
          moves(
              outside.subList(
                  from, Math.min(outside.size(), from + BgSaftGlEntriesReader.CHUNK)))) {
        if (!diaryRows.rows(move).isEmpty()) {
          String reference = move.getReference();
          String operation = move.getBgVatMoveVatOperation();
          source.outOfScope(
              (reference == null || reference.trim().isEmpty() ? "#" + move.getId() : reference)
                  + (operation == null || operation.trim().isEmpty()
                      ? ""
                      : " (" + operation + ")"));
        }
      }
      clear();
    }
    reportingPeriods(scope, source);
    return source;
  }

  /** Фактурите, чийто дневник и SAF-T са в различни месеци — за предупреждението. */
  protected void reportingPeriods(BgSaftMoveScope scope, BgSaftInvoicesAssembler.Source source) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("periodFrom", BgVatReportingPeriod.of(scope.getFromDate()));
    extra.put("periodTo", BgVatReportingPeriod.of(scope.getToDate()));
    for (Object[] r : rows(REPORTED_ELSEWHERE, scope, extra)) {
      source.reportingPeriod(
          id(r[0]),
          number(r)
              + " — в дневниците за "
              + BgVatReportingPeriod.label((String) r[3])
              + ", в SAF-T — тук (по статията)");
    }
    for (Object[] r : rows(REPORTED_HERE, scope, extra)) {
      source.reportingPeriod(
          id(r[0]),
          number(r)
              + " — в дневниците за този месец, в SAF-T — за "
              + BgVatReportingPeriod.label(BgVatReportingPeriod.of((LocalDate) r[3]))
              + " (по статията)");
    }
  }

  private static List<Object[]> rows(
      String jpql, BgSaftMoveScope scope, Map<String, Object> extra) {
    TypedQuery<Object[]> query = JPA.em().createQuery(jpql, Object[].class);
    scope.parameters(jpql, extra).forEach(query::setParameter);
    return query.getResultList();
  }

  private static String number(Object[] r) {
    String supplier = (String) r[2];
    return "ф-ра № "
        + (supplier != null && !supplier.trim().isEmpty() ? supplier.trim() : (String) r[1]);
  }

  /** {@code BgSafVatCellCode}: ключ ({@link BgSaftVatCell#key}) → TAX-IMP код. */
  public Map<String, String> vatCellCodes() {
    Map<String, String> map = new HashMap<>();
    for (BgSafVatCellCode row : cellCodeRepo.all().fetch()) {
      String code = row.getSafTaxCode();
      if (code != null && !code.trim().isEmpty()) {
        map.put(BgSaftVatCell.key(row.getCellCode(), row.getCellValue()), code.trim());
      }
    }
    return map;
  }

  /** Периодът по касова отчетност на дружеството на обхвата. */
  protected BgSaftCashAccounting cashAccounting(BgSaftMoveScope scope) {
    TypedQuery<Object[]> query = JPA.em().createQuery(CASH_ACCOUNTING, Object[].class);
    scope.parameters(CASH_ACCOUNTING).forEach(query::setParameter);
    List<Object[]> rows = query.getResultList();
    return rows.isEmpty()
        ? BgSaftCashAccounting.NONE
        : BgSaftCashAccounting.of((LocalDate) rows.get(0)[0], (LocalDate) rows.get(0)[1]);
  }

  /** Само id-тата — едно число на фактура. */
  protected List<Long> invoiceIds(BgSaftMoveScope scope) {
    TypedQuery<Long> query = JPA.em().createQuery(INVOICE_IDS, Long.class);
    scope.parameters(INVOICE_IDS).forEach(query::setParameter);
    return query.getResultList();
  }

  /**
   * Едно парче: шапките, редовете с данъците им и данъкът на фактурите. За фактурите с обратно
   * начисляване — и присъдата на дневника ({@link #diaryVerdict}).
   */
  protected void chunk(List<Long> ids, BgSaftInvoicesAssembler.Source source) {
    Map<Long, BgSaftInvoicesAssembler.InvoiceRow> invoices = new HashMap<>();
    Map<Long, Long> moveOf = new HashMap<>();
    for (Object[] r : rows(INVOICES, ids)) {
      Long invoiceId = id(r[0]);
      BgSaftInvoicesAssembler.InvoiceRow invoice =
          new BgSaftInvoicesAssembler.InvoiceRow(invoiceId)
              .number((String) r[1], (String) r[2])
              .operationType(r[3] == null ? null : ((Number) r[3]).intValue())
              .documentType((String) r[4])
              .dates((LocalDate) r[5], (LocalDate) r[6])
              .partner(r[7] == null ? null : id(r[7]), r[8] == null ? null : id(r[8]))
              .move(id(r[9]), (String) r[10], (LocalDate) r[11])
              .supplyType((String) r[12]);
      invoices.put(invoiceId, invoice);
      moveOf.put(invoiceId, id(r[9]));
      source.add(invoice);
    }
    Map<Long, BgSaftInvoicesAssembler.LineRow> lines = new HashMap<>();
    for (Object[] r : rows(LINES, ids)) {
      Long lineId = id(r[0]);
      BgSaftInvoicesAssembler.LineRow line =
          new BgSaftInvoicesAssembler.LineRow(lineId, id(r[1]))
              .sequence(r[2] == null ? null : ((Number) r[2]).intValue())
              .type(r[3] == null ? null : ((Number) r[3]).intValue())
              .product(r[4] == null ? null : id(r[4]), (String) r[5], (String) r[6])
              .description((String) r[7])
              .qty(decimal(r[8]))
              .amounts(decimal(r[9]), decimal(r[10]))
              .accountId(r[11] == null ? null : id(r[11]))
              .creditType((String) r[12]);
      lines.put(lineId, line);
      source.add(line);
    }
    for (Object[] r : rows(LINE_TAXES, ids)) {
      BgSaftInvoicesAssembler.LineRow line = lines.get(id(r[0]));
      if (line != null) {
        line.tax(id(r[1]), (String) r[2], decimal(r[3]));
      }
    }
    List<Long> reverseCharged = new ArrayList<>();
    for (Object[] r : rows(INVOICE_TAXES, ids)) {
      source.add(
          new BgSaftInvoicesAssembler.InvoiceTaxRow(
              id(r[0]), r[1] == null ? null : id(r[1]), decimal(r[2]), (Boolean) r[3]));
      Long moveId = moveOf.get(id(r[0]));
      if (Boolean.TRUE.equals(r[3]) && moveId != null && !reverseCharged.contains(moveId)) {
        reverseCharged.add(moveId);
      }
    }
    if (!reverseCharged.isEmpty()) {
      for (Move move : moves(reverseCharged)) {
        if (move.getInvoice() != null && invoices.containsKey(move.getInvoice().getId())) {
          invoices.get(move.getInvoice().getId()).diaryVerdict(diaryVerdict(move));
        }
      }
    }
  }

  /**
   * Присъдата на дневника за статията на фактура с обратно начисляване — дали протоколът ѝ по чл.
   * 117 я покрива. Не тълкува фактурата втори път: това е редът на колектора ({@link
   * BgVatMoveDiaryRows}), както за протоколите.
   */
  protected BgSaftInvoicesAssembler.DiaryVerdict diaryVerdict(Move move) {
    boolean inDiaries = false;
    List<String> errors = new ArrayList<>();
    for (BgVatMoveDiaryRows.Row row : diaryRows.rows(move)) {
      inDiaries |= row.isSales() || row.isPurchases();
      for (BgVatValidationErrorDto error : row.getDocument().getErrors()) {
        errors.add(error.getCode() + ": " + error.getMessage());
      }
    }
    return new BgSaftInvoicesAssembler.DiaryVerdict(inDiaries, errors);
  }

  /** id-тата на статиите по заявката — едно число на статия. */
  protected List<Long> moveIds(String jpql, BgSaftMoveScope scope) {
    TypedQuery<Long> query = JPA.em().createQuery(jpql, Long.class);
    scope.parameters(jpql).forEach(query::setParameter);
    return query.getResultList();
  }

  /** Статиите на едно парче. */
  protected List<Move> moves(List<Long> ids) {
    return JPA.em().createQuery(MOVES, Move.class).setParameter("ids", ids).getResultList();
  }

  /**
   * Редовете на статията в дневниците като стойности: идентификацията и колоните — от {@link
   * BgVatMoveDiaryRows}, а редовете на статията — за ставката, сметките и контрагента.
   */
  protected List<DiaryDocument> diaryDocuments(Move move) {
    List<DiaryDocument> documents = new ArrayList<>();
    for (BgVatMoveDiaryRows.Row row : diaryRows.rows(move)) {
      BgVatDocumentDto doc = row.getDocument();
      DiaryDocument document =
          new DiaryDocument(move.getId())
              .move(
                  move.getReference(),
                  move.getPeriod() == null ? null : move.getPeriod().getFromDate())
              .operation(move.getBgVatMoveVatOperation(), move.getDescription())
              .partner(
                  move.getPartner() == null ? null : move.getPartner().getId(),
                  move.getBgVatMoveVatRate())
              .identity(
                  doc.getDocumentNumber(),
                  doc.getDocumentDate(),
                  doc.getNapDocumentType(),
                  doc.getSupplyTypeArt163a(),
                  doc.isIdentificationOnly())
              .derivedBase(doc.getDerivedBase());
      for (BgVatValidationErrorDto error : doc.getErrors()) {
        document.error(error.getCode() + ": " + error.getMessage());
      }
      for (String warning : doc.getWarnings()) {
        document.warning(warning);
      }
      if (row.isSales()) {
        document.sales(row.getSalesColumns());
      }
      if (row.isPurchases()) {
        document.purchases(row.getPurchaseColumns());
      }
      List<MoveLine> lines =
          move.getMoveLineList() == null
              ? new ArrayList<>()
              : new ArrayList<>(move.getMoveLineList());
      lines.sort(
          Comparator.comparing(
                  MoveLine::getCounter, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(MoveLine::getId, Comparator.nullsLast(Comparator.naturalOrder())));
      for (MoveLine line : lines) {
        VatSide side =
            diaryRows.isVatPurchaseAccount(line)
                ? VatSide.PURCHASE
                : diaryRows.isVatSalesAccount(line) ? VatSide.SALES : null;
        document.line(
            new MoveLineRow(
                line.getAccount() == null ? null : line.getAccount().getId(),
                side,
                line.getDebit(),
                line.getCredit(),
                line.getTaxRate(),
                line.getPartner() == null ? null : line.getPartner().getId(),
                line.getBgVatCreditType()));
      }
      documents.add(document);
    }
    return documents;
  }

  /** Изчиства сесията след всяко парче. */
  protected void clear() {
    JPA.clear();
  }

  private static List<Object[]> rows(String jpql, List<Long> ids) {
    TypedQuery<Object[]> query = JPA.em().createQuery(jpql, Object[].class);
    query.setParameter("ids", ids);
    return query.getResultList();
  }

  private static Long id(Object value) {
    return ((Number) value).longValue();
  }

  private static BigDecimal decimal(Object value) {
    if (value == null || value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(value.toString());
  }
}
