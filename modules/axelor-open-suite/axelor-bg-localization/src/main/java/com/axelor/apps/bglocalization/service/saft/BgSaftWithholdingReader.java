package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.IncomeLine;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.InvoiceMove;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.Mapping;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.PayrollMove;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomePaymentFeedMapper;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.persistence.TypedQuery;

/**
 * ВХОДОВЕТЕ НА {@link BgSaftWithholdingRouter} — единственото място, което чете базата за G3b.
 *
 * <p><b>⚠️ ПИТА САМО СТАТИИТЕ НА ДНЕВНИКА.</b> Фишовете и фактурите се търсят по id на статията,
 * взето от редовете на {@link BgSaftGlEntriesReader} — тоест рутерът никога не вижда документ,
 * чиято статия не е във файла.
 *
 * <p><b>⚠️ ФИШЪТ СЕ ЧЕТЕ КАТО ENTITY, НЕ КАТО ПРОЕКЦИЯ.</b> {@link
 * com.axelor.apps.bglocalization.service.accounting.BgPayrollPostingPlan} иска перата такива,
 * каквито са (знак, флаг, фонд, коригиран период) — второ копие на правилата му върху проекция би
 * било втори план, който утре се разминава с първия. Затова перата и фондовете им се ИНИЦИАЛИЗИРАТ
 * тук: след {@code JPA.clear()} на следващия читател lazy връзка би гръмнала.
 *
 * <p><b>⚠️ ЧУЖДЕСТРАННОТО ЛИЦЕ Е ОТ РЕГИСТЪРА, НЕ ОТ АДРЕСА.</b> {@code isForeign} има само {@code
 * BgIndividualIncomePayment} и се пише от човек (виж {@code BgIncomePaymentFeedMapper}: местен
 * адрес не е местно лице). Затова фактура е „чуждестранна“, когато някое плащане по нея има ред в
 * регистъра с вдигнат признак.
 *
 * <p><b>⚠️ МОРСКОТО ЛИЦЕ — ПО КОДА НА ВИДА ОСИГУРЕН</b>, без дата: набор от кодовете с {@code
 * bgIsSeafarer}. Флагът е свойство на вида, не на периода — обявена граница.
 */
@Singleton
public class BgSaftWithholdingReader {

  static final String MAPPINGS =
      "SELECT self.incomeTypeCode, self.safTaxCode, self.ruleSelect"
          + " FROM BgSafWithholdingMapping self";

  /** Фишовете, чиято статия е в дневника. */
  static final String SLIPS =
      "SELECT self FROM BgPaySlip self WHERE self.bgPayrollMove.id IN (:moves)";

  /** Видовете осигурен на морско лице. ⚠️ „= TRUE“ изпуска NULL — и NULL не е морско лице. */
  static final String SEAFARER_TYPES =
      "SELECT DISTINCT self.code FROM BgInsuredType self WHERE self.bgIsSeafarer = TRUE";

  /**
   * Редовете с вид доход на фактурите за покупка, чиято статия е в дневника. ⚠️ JOIN на вида доход
   * — ред без него не носи код; LEFT JOIN на сметката и партньора — без тях редът пак се брои.
   */
  static final String INVOICE_LINES =
      "SELECT move.id, invoice.invoiceId, invoice.invoiceDate, partner.bgSelfInsuredDeclaredOn,"
          + " account.code, incomeType.code, incomeType.taxPattern, incomeType.expenseRate,"
          + " incomeType.finalTaxRate, incomeType.advanceTaxRate, incomeType.withheldByPayer,"
          + " self.exTaxTotal"
          + " FROM InvoiceLine self JOIN self.invoice invoice JOIN invoice.move move"
          + " JOIN self.bgIncomeType incomeType LEFT JOIN self.account account"
          + " LEFT JOIN invoice.partner partner"
          + " WHERE move.id IN (:moves) AND invoice.operationTypeSelect = :purchase"
          + " ORDER BY move.id, self.id";

  /** Референциите на плащанията по фактура, водени в регистъра като чуждестранно лице. */
  static final String FOREIGN_REFS =
      "SELECT self.sourceRef FROM BgIndividualIncomePayment self"
          + " WHERE self.source = :source AND self.isForeign = TRUE";

  /** Статиите на фактурите на тези плащания. */
  static final String PAYMENT_MOVES =
      "SELECT DISTINCT invoice.move.id FROM InvoicePayment self JOIN self.invoice invoice"
          + " WHERE self.id IN (:ids)";

  /** Колко id-та в един {@code IN} — същото като парчето на дневника. */
  static final int CHUNK = BgSaftGlEntriesReader.CHUNK;

  /** Картата „вид доход → код“ по код на вида доход. */
  public Map<String, Mapping> mappings() {
    Map<String, Mapping> out = new TreeMap<>();
    for (Object[] r : JPA.em().createQuery(MAPPINGS, Object[].class).getResultList()) {
      if (r[0] != null) {
        out.put(r[0].toString().trim(), new Mapping((String) r[1], (String) r[2]));
      }
    }
    return out;
  }

  /** Статиите от фишове — по id на статията. */
  public Map<Long, PayrollMove> payroll(Collection<Long> moveIds) {
    Set<String> seafarers =
        new HashSet<>(JPA.em().createQuery(SEAFARER_TYPES, String.class).getResultList());
    Map<Long, PayrollMove> out = new LinkedHashMap<>();
    for (List<Long> chunk : chunks(moveIds)) {
      TypedQuery<BgPaySlip> query = JPA.em().createQuery(SLIPS, BgPaySlip.class);
      query.setParameter("moves", chunk);
      for (BgPaySlip slip : query.getResultList()) {
        String insuredType = BgPaySlipResolver.insuredTypeCodeOf(slip);
        out.put(
            slip.getBgPayrollMove().getId(),
            new PayrollMove(
                    slip.getReference(),
                    initialized(slip.getBgPaySlipLineList()),
                    insuredType,
                    insuredType != null && seafarers.contains(insuredType.trim()))
                .socialExpenseTaxBase(slip.getBgSocialExpenseTaxBase()));
      }
    }
    return out;
  }

  /** Статиите от фактури за покупка с вид доход — по id на статията. */
  public Map<Long, InvoiceMove> invoices(Collection<Long> moveIds) {
    List<Object[]> rows = new ArrayList<>();
    for (List<Long> chunk : chunks(moveIds)) {
      TypedQuery<Object[]> query = JPA.em().createQuery(INVOICE_LINES, Object[].class);
      query.setParameter("moves", chunk);
      query.setParameter("purchase", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
      rows.addAll(query.getResultList());
    }
    if (rows.isEmpty()) {
      return new LinkedHashMap<>();
    }
    return invoiceMoves(rows, foreignMoves());
  }

  /** Статиите на фактурите, по чиито плащания регистърът води чуждестранно лице. */
  protected Set<Long> foreignMoves() {
    List<String> refs =
        JPA.em()
            .createQuery(FOREIGN_REFS, String.class)
            .setParameter("source", BgIncomePaymentFeedMapper.SOURCE_INVOICE)
            .getResultList();
    Set<Long> moves = new HashSet<>();
    for (List<Long> chunk : chunks(paymentIds(refs))) {
      moves.addAll(
          JPA.em()
              .createQuery(PAYMENT_MOVES, Long.class)
              .setParameter("ids", chunk)
              .getResultList());
    }
    return moves;
  }

  // ─────────────── чистите половини — проверяват се без база ───────────────

  /**
   * Редовете на {@link #INVOICE_LINES} → статии. Получателят е декларирал по чл. 43 ал. 5, когато
   * декларацията е от датата на фактурата или по-рано (същото правило като при регистъра:
   * декларация след плащането не го заварва).
   */
  static Map<Long, InvoiceMove> invoiceMoves(List<Object[]> rows, Set<Long> foreign) {
    Map<Long, List<IncomeLine>> lines = new LinkedHashMap<>();
    Map<Long, String> references = new LinkedHashMap<>();
    Map<Long, Boolean> selfInsured = new LinkedHashMap<>();
    for (Object[] r : rows) {
      Long move = ((Number) r[0]).longValue();
      references.putIfAbsent(move, r[1] == null ? "#" + move : r[1].toString());
      selfInsured.putIfAbsent(move, declaredBy((LocalDate) r[3], (LocalDate) r[2]));
      lines
          .computeIfAbsent(move, k -> new ArrayList<>())
          .add(
              new IncomeLine(
                  (String) r[4],
                  (String) r[5],
                  (String) r[6],
                  decimal(r[7]),
                  decimal(r[8]),
                  decimal(r[9]),
                  Boolean.TRUE.equals(r[10]),
                  decimal(r[11])));
    }
    Map<Long, InvoiceMove> out = new LinkedHashMap<>();
    for (Map.Entry<Long, List<IncomeLine>> e : lines.entrySet()) {
      Long move = e.getKey();
      out.put(
          move,
          new InvoiceMove(
              references.get(move), selfInsured.get(move), foreign.contains(move), e.getValue()));
    }
    return out;
  }

  /** Декларацията по чл. 43 ал. 5 заварва фактурата, ако е от нейната дата или по-рано. */
  static boolean declaredBy(LocalDate declaredOn, LocalDate invoiceDate) {
    return declaredOn != null && invoiceDate != null && !declaredOn.isAfter(invoiceDate);
  }

  /** id-тата на плащанията от референции „InvoicePayment#123“; чуждите и счупените се пропускат. */
  static List<Long> paymentIds(Collection<String> refs) {
    String prefix = BgIncomePaymentFeedMapper.MODEL_INVOICE_PAYMENT + "#";
    List<Long> out = new ArrayList<>();
    for (String ref : refs) {
      if (ref == null || !ref.trim().startsWith(prefix)) {
        continue;
      }
      try {
        out.add(Long.parseLong(ref.trim().substring(prefix.length())));
      } catch (NumberFormatException e) {
        // Счупена референция не е чуждестранно лице — регистърът пише само „<Модел>#<id>“.
      }
    }
    return out;
  }

  /** Парчета по {@link #CHUNK} — {@code IN} с десетки хиляди параметъра не минава навсякъде. */
  static List<List<Long>> chunks(Collection<Long> ids) {
    List<Long> all = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
    List<List<Long>> out = new ArrayList<>();
    for (int from = 0; from < all.size(); from += CHUNK) {
      out.add(all.subList(from, Math.min(all.size(), from + CHUNK)));
    }
    return out;
  }

  /** Перата с инициализирани фондове — планът ги чете и след {@code JPA.clear()}. */
  private static List<BgPaySlipLine> initialized(List<BgPaySlipLine> lines) {
    List<BgPaySlipLine> out = new ArrayList<>();
    if (lines == null) {
      return out;
    }
    for (BgPaySlipLine line : lines) {
      BgContributionFund fund = line.getBgContributionFund();
      if (fund != null) {
        fund.getCode();
      }
      out.add(line);
    }
    return out;
  }

  private static BigDecimal decimal(Object value) {
    if (value == null || value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(value.toString());
  }
}
