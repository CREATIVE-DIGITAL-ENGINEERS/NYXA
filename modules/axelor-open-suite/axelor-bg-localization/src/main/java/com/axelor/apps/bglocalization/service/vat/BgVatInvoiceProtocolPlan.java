package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseCreditTypeMapping;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ПРОТОКОЛИТЕ НА ЕДНА ФАКТУРА И СУМИТЕ ИМ — чисти функции.
 *
 * <p><b>Групи.</b> Един протокол на (случай, ставка): редовете с еднаква операция, к.8а, основание
 * и ставка на данъка за обратно начисляване. Различните случаи имат различни основания (ЗДДС чл.
 * 117 ал. 2 т. 7) и различни колони, а различните ставки — различни клетки в SAF-T (100212 срещу
 * 100220). Ставката в ключа е решение за еднозначност, не изискване на т. 6.
 *
 * <p><b>Суми.</b> Основата (т. 5) е сборът от основите на редовете във валутата на фактурата
 * ({@code InvoiceLine.exTaxTotal}), превърнат веднъж в евро по курса на ЕЦБ към изискуемостта
 * ({@link BgVatProtocolRate}, ЗДДС чл. 26 ал. 6). Данъкът (т. 8) е основата по ставката (т. 6),
 * закръглен до цент (HALF_UP). Основата се пише изрично ({@code Move.bgVatProtocolBase}) —
 * обратното смятане данък / ставка губи центове.
 *
 * <p><b>Дебитите.</b> По кредитния тип на редовете (ППЗДДС чл. 97а ал. 4 б. „б“ и чл. 81 ал. 2 —
 * колоните на покупната страна): пълен и частичен кредит — по сметката за ДДС по покупки, без право
 * на кредит — по сметката на реда (данъкът е разход). Делът е по основата, последният взема
 * остатъка, за да е сборът точно данъкът.
 */
public final class BgVatInvoiceProtocolPlan {

  private BgVatInvoiceProtocolPlan() {}

  /** Един ред на фактурата с обратно начисляване и неговият случай. */
  public static final class Line {
    private final BgVatInvoiceProtocolCase kase;
    private final BigDecimal ratePercent;
    private final BigDecimal currencyBase;
    private final BigDecimal companyBase;
    private final String creditType;
    private final Long accountId;

    /** Фактура във валутата на дружеството — двете основи са една. */
    public Line(
        BgVatInvoiceProtocolCase kase,
        BigDecimal ratePercent,
        BigDecimal base,
        String creditType,
        Long accountId) {
      this(kase, ratePercent, base, base, creditType, accountId);
    }

    /**
     * @param ratePercent ставката на данъка за обратно начисляване, в проценти (20)
     * @param currencyBase основата на реда във валутата на фактурата ({@code
     *     InvoiceLine.exTaxTotal}) — от нея е основата на протокола по курса на ЕЦБ
     * @param companyBase основата по курса на фактурата ({@code InvoiceLine.companyExTaxTotal}) —
     *     само за предупреждението за разлика
     * @param creditType {@code InvoiceLine.bgVatCreditType}
     * @param accountId сметката на реда — за дебита без право на кредит
     */
    public Line(
        BgVatInvoiceProtocolCase kase,
        BigDecimal ratePercent,
        BigDecimal currencyBase,
        BigDecimal companyBase,
        String creditType,
        Long accountId) {
      this.kase = kase;
      this.ratePercent = ratePercent == null ? BigDecimal.ZERO : ratePercent;
      this.currencyBase = currencyBase == null ? BigDecimal.ZERO : currencyBase;
      this.companyBase = companyBase == null ? BigDecimal.ZERO : companyBase;
      this.creditType =
          creditType == null || creditType.trim().isEmpty() ? null : creditType.trim();
      this.accountId = accountId;
    }
  }

  /** Един дебит на протокола. */
  public static final class Debit {
    private final String creditType;
    private final Long accountId;
    private final BigDecimal amount;

    Debit(String creditType, Long accountId, BigDecimal amount) {
      this.creditType = creditType;
      this.accountId = accountId;
      this.amount = amount;
    }

    /** {@code bgVatCreditType} на дебита; {@code null} — непопълнен на фактурата. */
    public String getCreditType() {
      return creditType;
    }

    /**
     * Сметката на реда при „без право на кредит“; иначе {@code null} — сметката за ДДС по покупки.
     * Нарочно не {@code getAccountId}: генерираните getter-и с това име не връщат null, а тук
     * {@code null} е стойност ({@code BgDefaultedGetterTest}).
     */
    public Long expenseAccountId() {
      return accountId;
    }

    public BigDecimal getAmount() {
      return amount;
    }
  }

  /** Един протокол. */
  public static final class Group {
    private final BgVatInvoiceProtocolCase kase;
    private final BigDecimal ratePercent;
    private final List<Line> lines = new ArrayList<>();

    Group(BgVatInvoiceProtocolCase kase, BigDecimal ratePercent) {
      this.kase = kase;
      this.ratePercent = ratePercent;
    }

    public BgVatInvoiceProtocolCase getCase() {
      return kase;
    }

    /** Ставката в проценти (20). */
    public BigDecimal getRatePercent() {
      return ratePercent;
    }

    /** Ставката като дроб (0.2) — {@code Move.bgVatMoveVatRate}. */
    public BigDecimal getRateFraction() {
      return ratePercent.movePointLeft(2).stripTrailingZeros();
    }

    /** Основата (т. 5) във валутата на дружеството — фактура в евро. */
    public BigDecimal base() {
      return base(BgVatProtocolRate.SAME);
    }

    /**
     * Основата (т. 5): сборът във валутата на фактурата, превърнат ВЕДНЪЖ по курса на ЕЦБ към
     * изискуемостта (ЗДДС чл. 26 ал. 6), до цент.
     */
    public BigDecimal base(BgVatProtocolRate rate) {
      BigDecimal sum = BigDecimal.ZERO;
      for (Line line : lines) {
        sum = sum.add(line.currencyBase);
      }
      return rate.toCompanyCurrency(sum);
    }

    /** Основата по курса на фактурата ({@code companyExTaxTotal}) — за разликата с ЕЦБ. */
    public BigDecimal companyBase() {
      BigDecimal sum = BigDecimal.ZERO;
      for (Line line : lines) {
        sum = sum.add(line.companyBase);
      }
      return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Данъкът (т. 8) — фактура в евро. */
    public BigDecimal tax() {
      return tax(BgVatProtocolRate.SAME);
    }

    /** Данъкът (т. 8): основата × ставката, до цент. */
    public BigDecimal tax(BgVatProtocolRate rate) {
      return base(rate).multiply(ratePercent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** Дебитите — фактура в евро. */
    public List<Debit> debits() {
      return debits(BgVatProtocolRate.SAME);
    }

    /**
     * Дебитите на протокола — по кредитния тип (и при „без право“ — по сметката на реда), с дял по
     * основата. Сборът им е точно {@link #tax(BgVatProtocolRate)}.
     */
    public List<Debit> debits(BgVatProtocolRate rate) {
      Map<String, BigDecimal> shares = new LinkedHashMap<>();
      Map<String, Debit> keys = new LinkedHashMap<>();
      for (Line line : lines) {
        boolean noCredit = BgVatPurchaseCreditTypeMapping.CREDIT_NONE.equals(line.creditType);
        Long account = noCredit ? line.accountId : null;
        String key = line.creditType + "|" + account;
        shares.merge(key, line.currencyBase, BigDecimal::add);
        keys.putIfAbsent(key, new Debit(line.creditType, account, null));
      }
      BigDecimal total = BigDecimal.ZERO;
      for (BigDecimal share : shares.values()) {
        total = total.add(share);
      }
      BigDecimal tax = tax(rate);
      List<Debit> debits = new ArrayList<>();
      BigDecimal left = tax;
      List<String> order = new ArrayList<>(shares.keySet());
      for (int i = 0; i < order.size(); i++) {
        String key = order.get(i);
        BigDecimal amount = left;
        if (i < order.size() - 1 && total.signum() != 0) {
          amount = tax.multiply(shares.get(key)).divide(total, 2, RoundingMode.HALF_UP);
          left = left.subtract(amount);
        }
        Debit shape = keys.get(key);
        debits.add(new Debit(shape.creditType, shape.accountId, amount));
      }
      return debits;
    }

    public int lineCount() {
      return lines.size();
    }
  }

  /** Редовете по групи, в реда на първата им поява. Само редовете с {@code PROTOCOL}. */
  public static List<Group> groups(List<Line> lines) {
    Map<String, Group> groups = new LinkedHashMap<>();
    for (Line line : lines) {
      if (line.kase == null
          || line.kase.getOutcome() != BgVatInvoiceProtocolCase.Outcome.PROTOCOL) {
        continue;
      }
      BigDecimal rate = line.ratePercent.stripTrailingZeros();
      String key = line.kase.groupKey() + "|" + rate.toPlainString();
      groups.computeIfAbsent(key, k -> new Group(line.kase, rate)).lines.add(line);
    }
    return Collections.unmodifiableList(new ArrayList<>(groups.values()));
  }

  /**
   * Покрива ли заварен протокол групата: същата операция, същата ставка (когато ръчна е въведена) и
   * същото основание (когато е записано).
   *
   * @param rateFraction {@code Move.bgVatMoveVatRate}; нула — не е въведена
   * @param basis {@code Move.bgVatProtocolBasis}; празно — не е записано (ръчен протокол)
   */
  public static boolean sameProtocol(
      Group group, String operation, BigDecimal rateFraction, String basis) {
    if (!Objects.equals(group.getCase().getOperation(), trim(operation))) {
      return false;
    }
    if (rateFraction != null
        && rateFraction.signum() != 0
        && rateFraction.compareTo(group.getRateFraction()) != 0) {
      return false;
    }
    return trim(basis) == null || trim(basis).equals(group.getCase().getBasis());
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
