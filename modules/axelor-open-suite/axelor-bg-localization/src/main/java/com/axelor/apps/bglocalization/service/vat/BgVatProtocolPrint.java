package com.axelor.apps.bglocalization.service.vat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ЛИСТЪТ НА ПРОТОКОЛА ПО ЧЛ. 117 ЗДДС — стойностите, които печатната форма показва, по реквизитите
 * на ал. 2 т. 1–10 (консолидиран текст на lex.bg, последно изм. ДВ бр. 69/2026, сверено
 * 27.10.2026).
 *
 * <p><b>⚠️ ОТЧЕТЪТ НЕ СМЯТА И НЕ ЧЕТЕ БАЗАТА.</b> Всичко идва оттук като параметри ({@link
 * #params()}), вече форматирано: номерът, датата, основата и данъкът са тези на реда в
 * PRODAGBI/POKUPKI ({@link BgVatProtocolPrintBuilder}), тоест закръглени по документ. Число, което
 * образецът сам би сметнал или прочел от таблица, би имало втори източник.
 */
public final class BgVatProtocolPrint {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /** Разделителите на редовете на т. 3 в параметъра {@code ItemsData} (чете ги скриптът). */
  static final String ROW_SEPARATOR = "\n";

  static final String FIELD_SEPARATOR = "\t";

  /** Един ред на т. 3 — видът на стоката/услугата и количеството ѝ. */
  public static final class Item {
    private final String name;
    private final String quantity;
    private final String unit;

    public Item(String name, String quantity, String unit) {
      this.name = nz(name);
      this.quantity = nz(quantity);
      this.unit = nz(unit);
    }

    public String getName() {
      return name;
    }

    /** Количеството, както се чете („2,5“); празно, когато не е записано. */
    public String getQuantity() {
      return quantity;
    }

    public String getUnit() {
      return unit;
    }
  }

  String number = "";
  LocalDate date;
  String issuedUnder = "";
  String kindLabel = "";
  String issuerName = "";
  String issuerVatNumber = "";
  String supplierName = "";
  String supplierIdPoint = "";
  String supplierIdLabel = "";
  String supplierId = "";
  String invoiceNumber = "";
  LocalDate invoiceDate;
  String customsDocument = "";
  LocalDate customsDate;
  final List<Item> items = new ArrayList<>();
  LocalDate taxEventDate;
  BigDecimal base = BigDecimal.ZERO;
  String rateLabel = "";
  String basis = "";
  BigDecimal tax = BigDecimal.ZERO;
  String currency = "";
  String diaryPeriod = "";
  String supplyType = "";
  final List<String> warnings = new ArrayList<>();

  BgVatProtocolPrint() {}

  /** т. 1 — номерът на протокола, същият като в дневниците. */
  public String getNumber() {
    return number;
  }

  /** т. 1 — датата на издаване, същата като в дневниците (при закъснял — датата на издаване). */
  public LocalDate getDate() {
    return date;
  }

  /** Точката на чл. 117 ал. 1, по която се издава — „чл. 117, ал. 1, т. 1 ЗДДС“. */
  public String getIssuedUnder() {
    return issuedUnder;
  }

  /** Видът на операцията с думи — „Вътреобщностно придобиване на стоки“. */
  public String getKindLabel() {
    return kindLabel;
  }

  /** т. 2 — името на лицето по ал. 1 (регистрираното лице, което издава протокола). */
  public String getIssuerName() {
    return issuerName;
  }

  /** т. 2 — идентификационният му номер по чл. 94 ал. 2. */
  public String getIssuerVatNumber() {
    return issuerVatNumber;
  }

  public String getSupplierName() {
    return supplierName;
  }

  /** „т. 9“, „т. 10“ или празно — коя точка на ал. 2 иска номера на доставчика. */
  public String getSupplierIdPoint() {
    return supplierIdPoint;
  }

  public String getSupplierIdLabel() {
    return supplierIdLabel;
  }

  /** т. 9 / т. 10 — номерът на доставчика, както е в дневниците. */
  public String getSupplierId() {
    return supplierId;
  }

  /** т. 9 / т. 10 — номерът на фактурата на доставчика. */
  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public LocalDate getInvoiceDate() {
    return invoiceDate;
  }

  /** Внос по чл. 167а — митническият документ (ЗДДС чл. 57 ал. 6 т. 2). */
  public String getCustomsDocument() {
    return customsDocument;
  }

  public LocalDate getCustomsDate() {
    return customsDate;
  }

  /** т. 3 — видът и количеството. */
  public List<Item> getItems() {
    return Collections.unmodifiableList(items);
  }

  /** т. 4 — датата на възникване на данъчното събитие; {@code null}, когато не е записана. */
  public LocalDate getTaxEventDate() {
    return taxEventDate;
  }

  /** т. 5 — к.09 на реда в PRODAGBI. */
  public BigDecimal getBase() {
    return base;
  }

  /** т. 6 — „20 %“. */
  public String getRateLabel() {
    return rateLabel;
  }

  /** т. 7 — основанието за начисляване. */
  public String getBasis() {
    return basis;
  }

  /** т. 8 — к.10 на реда в PRODAGBI. */
  public BigDecimal getTax() {
    return tax;
  }

  public String getCurrency() {
    return currency;
  }

  /** Данъчният период на дневниците, в който протоколът е отразен — „ММ.ГГГГ“. */
  public String getDiaryPeriod() {
    return diaryPeriod;
  }

  /** к.8а в дневниците — „01“/„02“ (чл. 163а), „03“ (внос по чл. 167а) или празно. */
  public String getSupplyType() {
    return supplyType;
  }

  /** Какво липсва или е изведено — печата се върху листа с „(!)“. */
  public List<String> getWarnings() {
    return Collections.unmodifiableList(warnings);
  }

  /**
   * Параметрите на {@code BgVatProtocol.rptdesign} — всички низове, вече форматирани. Наборът е
   * затворен: {@code BgVatProtocolPdfTest} го сверява с параметрите, обявени в образеца.
   */
  public Map<String, Object> params() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("ProtocolNumber", number);
    p.put("ProtocolDate", day(date));
    p.put("IssuedUnder", issuedUnder);
    p.put("KindLabel", kindLabel);
    p.put("IssuerName", issuerName);
    p.put("IssuerVatNumber", issuerVatNumber);
    p.put("SupplierName", supplierName);
    p.put("SupplierIdPoint", supplierIdPoint);
    p.put("SupplierIdLabel", supplierIdLabel);
    p.put("SupplierId", supplierId);
    p.put("InvoiceRef", reference(invoiceNumber, invoiceDate));
    p.put("CustomsRef", reference(customsDocument, customsDate));
    p.put("ItemsData", itemsData());
    p.put("TaxEventDate", day(taxEventDate));
    p.put("TaxBase", money(base));
    p.put("TaxRate", rateLabel);
    p.put("Basis", basis);
    p.put("TaxAmount", money(tax));
    p.put("Currency", currency);
    p.put("DiaryPeriod", diaryPeriod);
    p.put("SupplyType", supplyType);
    p.put("Warnings", warningsHtml());
    p.put("GenDate", day(LocalDate.now()));
    return p;
  }

  /** Сума както я чете човек — „1 234,57“ (две цифри, интервал за хилядите, запетая). */
  public static String money(BigDecimal amount) {
    DecimalFormatSymbols symbols = new DecimalFormatSymbols();
    symbols.setGroupingSeparator(' ');
    symbols.setDecimalSeparator(',');
    symbols.setMinusSign('-');
    DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(amount == null ? BigDecimal.ZERO : amount);
  }

  static String day(LocalDate value) {
    return value == null ? "" : value.format(DAY);
  }

  private static String reference(String number, LocalDate date) {
    if (number.isEmpty()) {
      return "";
    }
    return date == null ? number : number + " от " + day(date);
  }

  /** Редовете на т. 3 за скриптовия набор: „№ ⇥ вид ⇥ количество ⇥ мярка“ на ред. */
  private String itemsData() {
    StringBuilder out = new StringBuilder();
    int index = 0;
    for (Item item : items) {
      if (index > 0) {
        out.append(ROW_SEPARATOR);
      }
      index++;
      out.append(index)
          .append(FIELD_SEPARATOR)
          .append(flat(item.getName()))
          .append(FIELD_SEPARATOR)
          .append(flat(item.getQuantity()))
          .append(FIELD_SEPARATOR)
          .append(flat(item.getUnit()));
    }
    return out.toString();
  }

  /**
   * Предупрежденията като HTML за {@code <value-of format="html">}: всяко на свой ред, с „(!)“ —
   * U+26A0 няма глиф в шрифта на PDF-а и изчезва мълчаливо. Текстът се екранира тук, в Java.
   */
  private String warningsHtml() {
    StringBuilder out = new StringBuilder();
    for (String warning : warnings) {
      if (out.length() > 0) {
        out.append("<br/>");
      }
      out.append("(!) ").append(escape(warning));
    }
    return out.toString();
  }

  private static String flat(String value) {
    return value.replace(ROW_SEPARATOR, " ").replace(FIELD_SEPARATOR, " ").replace('\r', ' ');
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  static String nz(String value) {
    return value == null ? "" : value.trim();
  }
}
