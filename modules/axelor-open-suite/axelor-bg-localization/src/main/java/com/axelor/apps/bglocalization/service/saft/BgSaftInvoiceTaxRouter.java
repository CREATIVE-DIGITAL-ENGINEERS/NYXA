package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseCreditTypeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ДАНЪЧНИЯТ КОД НА РЕД ОТ ФАКТУРА (S.TI.1/S.TI.2) — чиста функция. Решава КОЙ код е; сумата на
 * данъка е на assembler-а.
 *
 * <p><b>⚠️ КОДЪТ НЕ СЕ СГЛОБЯВА ОТ НИЗОВЕ.</b> Фиксираните данъци носят прекия код от {@code
 * BgSafTaxMapping}; всички останали стигат до КЛЕТКА (02-xx/03-xx) през чистите функции на Блок Б —
 * {@link BgVatPurchaseCreditTypeMapping#forCreditType} за покупките, {@link
 * BgVatSalesTaxCodeMapping#forTaxCode} за продажбите — и {@link BgSaftVatCell}, а кодът на клетката
 * идва от {@code BgSafVatCellCode}. Капанът „100 + номер“ (02-20/23/24, 03-43 срещу 100242) няма
 * къде да влезе.
 *
 * <p><b>⚠️ ПОСОКАТА Е НА ФАКТУРАТА, НЕ НА ДАНЪКА.</b> Продажба или покупка решава {@code
 * operationTypeSelect}; данък от другата страна е ГРЕШКА, не повод за тих превод.
 *
 * <p><b>⚠️ ВСЕКИ ОТ 50-ТЕ ДАНЪКА Е В ТОЧНО ЕДНО СЕМЕЙСТВО.</b> Кои са и защо:
 *
 * <ul>
 *   <li><b>Продажби, фиксиран код</b> — клетката не зависи от сделката.
 *   <li><b>Покупки по кредита</b> — 100330/100331/100332 по {@code bgVatCreditType}; празно = пълен
 *       кредит с предупреждение, както в дневника. Покупка с 9 % също е 100331 (QA с. 81).
 *   <li><b>BG_ZRP → 03-30</b>: к.9 на дневника за покупки е „…без право на данъчен кредит <i>или
 *       без данък</i>“ (ППЗДДС Прил. № 11), а QA (с. 81) дава 100330 за „фактури без начислен ДДС“
 *       — кредитният тип тук няма значение.
 *   <li><b>BG_ZRS → по колоната на Блок Б</b>: 0 % по глава трета е к.19 (02-14); ВОД (глава пета)
 *       минава само през BG_VOD. Без колона в Блок Б — ГРЕШКА.
 *   <li><b>BG_OSS_EU / BG_MOSS → 02-18</b> (100218, ЗДДС чл. 69 ал. 2 т. 1).
 *   <li><b>Нерешени</b> — маржовата схема (маржът не е в данните; облагаемият марж е 100211,
 *       нулевата ставка по чл. 140/146 — 100216) и известията с „родов“ данък (клетката е на
 *       оригинала, който редът не носи). ГРЕШКА, никога празен таг (QA IV.2.3).
 *   <li><b>Инвестиционното злато</b> — освободеното по чл. 160 (100219) и обратното начисляване по
 *       чл. 161 не се различават в данните. ГРЕШКА „нерешен код“.
 *   <li><b>Протоколни данъци</b> — самоначисляване (ВОП, чл. 82, чл. 163а), внос по чл. 167а,
 *       протоколи по чл. 117/151в, корекциите по чл. 79/79а. Протоколът се подава от ръчната статия
 *       ({@link BgSaftProtocolInvoices}); във фактура данъкът е ГРЕШКА с това указание.
 * </ul>
 *
 * <p><b>⚠️ ДВА ИЗХОДА ИЗВЪН {@link #route}</b> — по отговора на НАП по имейл от 19.10.2026; решава
 * ги {@link BgSaftInvoicesAssembler} по документа, не по данъка:
 *
 * <ul>
 *   <li><b>Получената фактура по чл. 163а</b> (Прил. № 2 — {@link #ART163A_RECEIVED_TAXES}) →
 *       03-30, 100330, с данък 0: „Фактури за получени … стоки и/или услуги по чл. 163а … се
 *       отразяват в подсекция PurchaseInvoice … 100330“ — {@link #art163aReceivedInvoice()}.
 *   <li><b>Получен протокол по чл. 151в ал. 3/7</b> (вид 91/93) с {@code BG_PROTO_151V} → по
 *       кредита 100330/100331/100332 — {@link #byCredit}.
 * </ul>
 */
public final class BgSaftInvoiceTaxRouter {

  /** Какъв е изходът за един данък на един ред. */
  public enum Kind {
    /** Прекият код от {@code BgSafTaxMapping}. */
    CODE,
    /** Клетка — кодът ѝ е в {@code BgSafVatCellCode}. */
    CELL,
    /** Ред без данък извън дневниците — 000/000000 (QA с. 80; TAX-IMP, бележка R5). */
    NOT_APPLICABLE,
    /** Протоколен данък във фактура — протоколът е ръчна статия (G5b). ГРЕШКА. */
    PROTOCOL,
    /** Кодът не може да се определи от реда. ГРЕШКА. */
    UNRESOLVED,
    /** Кодът чака отговор от НАП ({@link BgSaftNapPending}). ГРЕШКА. */
    NAP_PENDING,
    /** Данъкът е от другата страна на сделката. ГРЕШКА. */
    WRONG_DIRECTION,
    /** Данък без ред в {@code BgSafTaxMapping}, без семейство или с разминал се мапинг. ГРЕШКА. */
    UNKNOWN
  }

  /** Решението за един данък на един ред. */
  public static final class Route {
    private final Kind kind;
    private final String value;
    private final String reason;
    private final boolean creditDefaulted;

    private Route(Kind kind, String value, String reason, boolean creditDefaulted) {
      this.kind = kind;
      this.value = value;
      this.reason = reason;
      this.creditDefaulted = creditDefaulted;
    }

    public Kind getKind() {
      return kind;
    }

    /** Кодът при {@link Kind#CODE}, клетката при {@link Kind#CELL}; иначе {@code null}. */
    public String getValue() {
      return value;
    }

    /** Защо — за находката; {@code null} при успех. */
    public String getReason() {
      return reason;
    }

    /** Кредитният тип е празен и е приет пълен кредит. */
    public boolean isCreditDefaulted() {
      return creditDefaulted;
    }
  }

  /** Ред без данък на покупка, която влиза в дневника — к.9 „…или без данък“ (QA с. 81). */
  public static final String UNTAXED_PURCHASE_CELL = "03-30";

  static final Set<String> SALES_FIXED =
      set(
          "BG_SRS_20",
          "BG_LRS_9",
          "BG_LRS_9_BOOK",
          "BG_LRS_9_BABY",
          "BG_LRS_9_REST",
          "BG_EX_S",
          "BG_VOD",
          "BG_EU_S_RCM_S",
          "BG_EXP",
          "BG_NON_EU_SERV_S",
          "BG_TRIANG_S");

  /** Кодът идва от колоната на Блок Б за данъка. */
  static final Set<String> SALES_BY_DIARY_COLUMN = set("BG_ZRS");

  static final Map<String, String> SALES_CELLS = map("BG_OSS_EU", "02-18", "BG_MOSS", "02-18");

  static final Set<String> PURCHASE_FIXED = set("BG_EX_P", "BG_TRIANG_P");

  static final Set<String> PURCHASE_BY_CREDIT =
      set("BG_SRP_20", "BG_LRP_9", "BG_LRP_9_BOOK", "BG_LRP_9_BABY", "BG_LRP_9_REST", "BG_IMP_STD");

  static final Map<String, String> PURCHASE_CELLS = map("BG_ZRP", UNTAXED_PURCHASE_CELL);

  static final Map<String, String> UNRESOLVED = unresolved();

  static final Map<String, String> PROTOCOL = protocol();

  /**
   * Покупните данъци по Прил. № 2 ЗДДС: част I — отпадъци, част II — зърно и технически култури.
   */
  static final Set<String> ART163A_RECEIVED_TAXES = set("BG_RCM_SCRAP_P", "BG_RCM_GRAIN_P");

  /** Данъкът на протокола по чл. 151в. */
  static final String CASH_PROTOCOL_TAX = "BG_PROTO_151V";

  private BgSaftInvoiceTaxRouter() {}

  private static Map<String, String> unresolved() {
    String margin =
        "маржова схема — маржът не е в данните на реда (облагаем марж → 100211 с основа маржа,"
            + " нулева ставка по чл. 140/146 → 100216)";
    String notice =
        "известие с родов данък — клетката е на основната доставка, която редът не носи";
    Map<String, String> m = new LinkedHashMap<>();
    m.put("BG_MARGIN", margin);
    m.put("BG_TOUR", margin);
    m.put("BG_SECONDHAND", margin);
    m.put("BG_ADJ_CREDIT", notice);
    m.put("BG_ADJ_DEBIT", notice);
    m.put(
        "BG_GOLD_ART163G",
        "инвестиционно злато — освободеното по чл. 160 (100219) и обратното начисляване по"
            + " чл. 161 не се различават в данните");
    return Collections.unmodifiableMap(m);
  }

  private static Map<String, String> protocol() {
    String vop = "самоначисляване по ВОП — протоколът е в двете секции";
    String art82 = "самоначисляване по чл. 82 — протоколът е в двете секции";
    String art163a = "чл. 163а — частта на Приложение № 2 и протоколът на получателя";
    Map<String, String> m = new LinkedHashMap<>();
    m.put("BG_VOP_ACQ", vop);
    m.put("BG_VOP_SELF", vop);
    m.put("BG_EU_S_RCM_P", art82);
    m.put("BG_NON_EU_SERV_P", art82);
    m.put("BG_RCM_ART82_P", art82);
    m.put("BG_RCM_ART82_S", art82);
    for (String tax :
        new String[] {
          "BG_RCM_BUILD_P", "BG_RCM_BUILD_S", "BG_RCM_SCRAP_P", "BG_RCM_SCRAP_S",
          "BG_RCM_GRAIN_P", "BG_RCM_GRAIN_S", "BG_RCM_MOBILE_P", "BG_RCM_MOBILE_S",
          "BG_RCM_ENERGY_P", "BG_RCM_ENERGY_S"
        }) {
      m.put(tax, art163a);
    }
    m.put("BG_IMP_ART57", "внос по чл. 167а — ЕАД и протоколът по чл. 117");
    m.put("BG_PROTO_117", "протокол по чл. 117 — кодът зависи от ДДС операцията");
    m.put(
        "BG_PROTO_151V",
        "касова отчетност по чл. 151в — във фактура се подава само полученият протокол (вид 91/93)");
    m.put("BG_ADJ_ART79", "корекция по чл. 79 — протокол с основа 0");
    m.put("BG_ADJ_ART79A", "корекция по чл. 79а/79б — протокол с основа 0");
    return Collections.unmodifiableMap(m);
  }

  /**
   * Кодът на един данък на един ред.
   *
   * @param sales фактурата е продажба ({@code operationTypeSelect} 3/4), иначе покупка (1/2)
   * @param tax кодът на данъка ({@code Tax.code})
   * @param creditType {@code InvoiceLine.bgVatCreditType}
   * @param mapping редът на данъка в {@code BgSafTaxMapping}; {@code null}, когато няма такъв
   */
  public static Route route(
      boolean sales, String tax, String creditType, BgSaftTaxTableAssembler.MappingRow mapping) {
    String code = tax == null ? "" : tax.trim();
    // Чака НАП — преди всичко останало, по един списък с дневника и протоколите (G6a, решение 6).
    if (BgSaftNapPending.TAXES.containsKey(code)) {
      return failure(Kind.NAP_PENDING, BgSaftNapPending.reasonFor(code));
    }
    if (mapping == null) {
      return failure(Kind.UNKNOWN, "няма ред в BgSafTaxMapping");
    }
    if (PROTOCOL.containsKey(code)) {
      return failure(Kind.PROTOCOL, PROTOCOL.get(code));
    }
    if (UNRESOLVED.containsKey(code)) {
      return failure(Kind.UNRESOLVED, UNRESOLVED.get(code));
    }
    boolean salesTax =
        SALES_FIXED.contains(code)
            || SALES_BY_DIARY_COLUMN.contains(code)
            || SALES_CELLS.containsKey(code);
    boolean purchaseTax =
        PURCHASE_FIXED.contains(code)
            || PURCHASE_BY_CREDIT.contains(code)
            || PURCHASE_CELLS.containsKey(code);
    if (!salesTax && !purchaseTax) {
      return failure(Kind.UNKNOWN, "данъкът не е в нито едно семейство на рутера");
    }
    if (salesTax != sales) {
      return failure(
          Kind.WRONG_DIRECTION,
          sales
              ? "покупен данък във фактура за продажба"
              : "продажбен данък във фактура за покупка");
    }

    if (SALES_FIXED.contains(code) || PURCHASE_FIXED.contains(code)) {
      String fixed = trim(mapping.getSafTaxCode());
      if (mapping.isDynamic() || fixed == null) {
        return failure(Kind.UNKNOWN, "BgSafTaxMapping вече не го води като фиксиран");
      }
      return new Route(Kind.CODE, fixed, null, false);
    }
    if (SALES_CELLS.containsKey(code)) {
      return new Route(Kind.CELL, SALES_CELLS.get(code), null, false);
    }
    if (PURCHASE_CELLS.containsKey(code)) {
      return new Route(Kind.CELL, PURCHASE_CELLS.get(code), null, false);
    }
    if (SALES_BY_DIARY_COLUMN.contains(code)) {
      Optional<BgVatSalesTaxCodeMapping.ColumnTarget> target =
          BgVatSalesTaxCodeMapping.forTaxCode(code);
      String cell = target.map(t -> BgSaftVatCell.ofSales(t.getBaseColumn())).orElse(null);
      return cell == null
          ? failure(Kind.UNRESOLVED, "Блок Б няма колона за данъка")
          : new Route(Kind.CELL, cell, null, false);
    }
    return byCredit(creditType);
  }

  /**
   * Покупка по кредита — 03-30/03-31/03-32 със същата функция, с която колекторът пълни к.9/к.10/
   * к.12; празният вид кредит е пълен, с предупреждение.
   */
  public static Route byCredit(String creditType) {
    String cell =
        BgSaftVatCell.ofPurchase(BgVatPurchaseCreditTypeMapping.forCreditType(creditType));
    return new Route(Kind.CELL, cell, null, BgVatPurchaseCreditTypeMapping.isDefaulted(creditType));
  }

  /** Получената фактура по чл. 163а → 03-30 (100330), без данък — отговорът на НАП, 19.10.2026. */
  public static Route art163aReceivedInvoice() {
    return new Route(Kind.CELL, UNTAXED_PURCHASE_CELL, null, false);
  }

  /**
   * Ред без данък: в покупка, която влиза в дневника за покупки — к.9 (QA с. 81); иначе
   * неприложимост 000/000000 (QA с. 80).
   */
  public static Route untaxed(boolean inPurchaseDiary) {
    return inPurchaseDiary
        ? new Route(Kind.CELL, UNTAXED_PURCHASE_CELL, null, false)
        : new Route(Kind.NOT_APPLICABLE, null, null, false);
  }

  /** Всички данъци, които рутерът познава — по едно семейство на данък. */
  public static Set<String> taxes() {
    Set<String> all = new LinkedHashSet<>();
    all.addAll(SALES_FIXED);
    all.addAll(SALES_BY_DIARY_COLUMN);
    all.addAll(SALES_CELLS.keySet());
    all.addAll(PURCHASE_FIXED);
    all.addAll(PURCHASE_BY_CREDIT);
    all.addAll(PURCHASE_CELLS.keySet());
    all.addAll(UNRESOLVED.keySet());
    all.addAll(PROTOCOL.keySet());
    return Collections.unmodifiableSet(all);
  }

  private static Route failure(Kind kind, String reason) {
    return new Route(kind, null, reason, false);
  }

  private static Set<String> set(String... values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
  }

  private static Map<String, String> map(String... pairs) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(pairs[i], pairs[i + 1]);
    }
    return Collections.unmodifiableMap(m);
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}
