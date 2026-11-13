package com.axelor.apps.bglocalization.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldContributor;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.repo.BgIncomeTypeRepository;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Прави българските НАП полета редактируеми от AI Accountant, без общият слой да знае за тях.
 *
 * <h2>Защо през разширителна точка, а не директно</h2>
 *
 * Полетата {@code napDocumentTypeSelect} и {@code bgVatSupplyType} върху {@code Invoice}, както и
 * {@code bgVatCreditType} и {@code bgIncomeType} върху {@code InvoiceLine}, се добавят от този
 * модул чрез domain XML — тоест те съществуват върху генерираните entity класове и технически са
 * достъпни отвсякъде. Но кодовете, режимите по чл.163а/167а и правилото кога едно поле има смисъл
 * са българско данъчно законодателство. Ако общият {@code InvoiceHeaderEditService} ги изброяваше
 * сам, данъчният режим на една държава щеше да живее в слоя, който обслужва всички.
 *
 * <p>Затова тук стои и <b>декларацията</b> (за да влязат в JSON схемата на инструментите, със
 * точните допустими стойности), и <b>записът</b> (през собствените сетъри на този модул). Няма
 * reflection: общият слой пита „кой поема това поле", а не „намери ми property по име".
 *
 * <h2>Какво НЕ прави</h2>
 *
 * Не се досеща. Видът на доставката по чл.163а/167а не се извежда от сумите, от контрагента или от
 * сметките — празно е правилната стойност за обикновена доставка, а специален режим се въвежда само
 * когато някой го е казал. Същото за данъчния кредит: „пълен" не е безопасно предположение.
 */
@Singleton
public class BgNapInvoiceFieldContributor implements AiInvoiceFieldContributor {

  static final String F_NAP_DOCUMENT_TYPE = "napDocumentTypeSelect";
  static final String F_BG_VAT_SUPPLY_TYPE = "bgVatSupplyType";
  static final String F_BG_VAT_CREDIT_TYPE = "bgVatCreditType";
  static final String F_BG_INCOME_TYPE_ID = "bgIncomeTypeId";
  static final String F_BG_INCOME_TYPE_CODE = "bgIncomeTypeCode";
  static final String F_BG_VAT_TAX_EVENT_DATE = "bgVatTaxEventDate";

  /** bg.nap.document.type.select — точните кодове от Selects.xml, не етикетите. */
  private static final List<String> NAP_DOCUMENT_TYPES =
      Collections.unmodifiableList(Arrays.asList("01", "02", "03", "07", "09"));

  /** bg.vat.supply.type.select — точните кодове от Selects.xml. */
  private static final List<String> VAT_SUPPLY_TYPES =
      Collections.unmodifiableList(
          Arrays.asList("01", "02", "03", "07", "08", "41", "43", "46", "48"));

  /** bg.vat.credit.type.select — точните кодове от Selects.xml. */
  private static final List<String> VAT_CREDIT_TYPES =
      Collections.unmodifiableList(Arrays.asList("1", "2", "3"));

  @Override
  public List<AiInvoiceFieldSpec> headerFields() {
    List<AiInvoiceFieldSpec> specs = new ArrayList<>();
    specs.add(
        new AiInvoiceFieldSpec(
            F_NAP_DOCUMENT_TYPE,
            AiInvoiceFieldSpec.Type.STRING,
            "NAP document type (вид на документа) for the VAT diary. 01 фактура, 02 дебитно"
                + " известие, 03 кредитно известие, 07 митническа декларация, 09 протокол или друг"
                + " документ. Send the code, never the label.",
            NAP_DOCUMENT_TYPES,
            true,
            AiInvoiceFieldSpec.Applies.BOTH));
    specs.add(
        new AiInvoiceFieldSpec(
            F_BG_VAT_SUPPLY_TYPE,
            AiInvoiceFieldSpec.Type.STRING,
            "Supply type under чл.163а/167а (вид на доставката). Leave empty for an ordinary"
                + " supply — this is only for the special regimes: 01 отпадъци, 02"
                + " зърнени/технически, 03 внос по Приложение №3, 07 хляб, 08 брашно, 41/43/46/48"
                + " складиране до поискване по чл.15а. Never inferred from the amounts.",
            VAT_SUPPLY_TYPES,
            true,
            AiInvoiceFieldSpec.Applies.BOTH));
    specs.add(
        new AiInvoiceFieldSpec(
            F_BG_VAT_TAX_EVENT_DATE,
            AiInvoiceFieldSpec.Type.STRING,
            "Дата на данъчното събитие, as printed on the document, ISO yyyy-MM-dd. Only from"
                + " the document's own «дата на данъчното събитие» — never copied from the invoice"
                + " date or the due date. Left empty, the VAT diary uses the supplier's document"
                + " date.",
            null,
            true,
            AiInvoiceFieldSpec.Applies.SUPPLIER_ONLY));
    return specs;
  }

  @Override
  public List<AiInvoiceFieldSpec> lineFields() {
    List<AiInvoiceFieldSpec> specs = new ArrayList<>();
    specs.add(
        new AiInvoiceFieldSpec(
            F_BG_VAT_CREDIT_TYPE,
            AiInvoiceFieldSpec.Type.STRING,
            "VAT credit right for this line (данъчен кредит), which decides the purchase-diary"
                + " column: 1 пълен (к.10/к.11), 2 частичен (к.12/к.13), 3 без право/без данък"
                + " (к.9). Meaningful on purchases; never guessed — 'пълен' is not a safe default.",
            VAT_CREDIT_TYPES,
            true,
            AiInvoiceFieldSpec.Applies.BOTH));
    specs.add(
        new AiInvoiceFieldSpec(
            F_BG_INCOME_TYPE_ID,
            AiInvoiceFieldSpec.Type.INTEGER,
            "Вид доход по ЗДДФЛ, by id — only when the supplier is a natural person, for the"
                + " Art.73(1) register. Leave empty for a company.",
            null,
            true,
            AiInvoiceFieldSpec.Applies.SUPPLIER_ONLY));
    specs.add(
        new AiInvoiceFieldSpec(
            F_BG_INCOME_TYPE_CODE,
            AiInvoiceFieldSpec.Type.STRING,
            "Вид доход по ЗДДФЛ, by its income code from the НАП catalogue — the alternative to"
                + " bgIncomeTypeId. Supplier invoices only.",
            null,
            true,
            AiInvoiceFieldSpec.Applies.SUPPLIER_ONLY));
    return specs;
  }

  /** {@code bg.nap.document.type.select} value "01" — виж Selects.xml. */
  private static final String NAP_DOCUMENT_TYPE_INVOICE = "01";

  /**
   * Defaults {@code napDocumentTypeSelect} to "01 – Фактура" on a brand-new, otherwise-blank
   * SUPPLIER PURCHASE invoice. This is the one NAP field with a safe default: an ordinary purchase
   * invoice genuinely is a "Фактура" unless somebody says otherwise, and {@link
   * InvoiceRepository#OPERATION_TYPE_SUPPLIER_PURCHASE} already excludes the cases where it would
   * not be — a supplier credit note ({@code OPERATION_TYPE_SUPPLIER_REFUND}, NAP type "03") and
   * every customer document are both left untouched. Contrast {@code bgVatCreditType} below, which
   * has no safe default at all.
   */
  @Override
  public void applyCreationDefaults(Invoice invoice) {

    if (invoice.getOperationTypeSelect() != InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE) {
      return;
    }

    if (invoice.getNapDocumentTypeSelect() == null
        || invoice.getNapDocumentTypeSelect().trim().isEmpty()) {
      invoice.setNapDocumentTypeSelect(NAP_DOCUMENT_TYPE_INVOICE);
    }
  }

  @Override
  public AiInvoiceFieldChange applyHeaderField(Invoice invoice, String field, JsonNode value) {
    if (F_NAP_DOCUMENT_TYPE.equals(field)) {
      String before = invoice.getNapDocumentTypeSelect();
      String after = selection(field, value, NAP_DOCUMENT_TYPES);
      invoice.setNapDocumentTypeSelect(after);
      return new AiInvoiceFieldChange(field, before, after);
    }
    if (F_BG_VAT_SUPPLY_TYPE.equals(field)) {
      String before = invoice.getBgVatSupplyType();
      String after = selection(field, value, VAT_SUPPLY_TYPES);
      invoice.setBgVatSupplyType(after);
      return new AiInvoiceFieldChange(field, before, after);
    }
    if (F_BG_VAT_TAX_EVENT_DATE.equals(field)) {
      LocalDate before = invoice.getBgVatTaxEventDate();
      LocalDate after = date(field, value);
      invoice.setBgVatTaxEventDate(after);
      return new AiInvoiceFieldChange(
          field,
          before == null ? null : before.toString(),
          after == null ? null : after.toString());
    }
    return null;
  }

  /** ISO дата или null при явно изчистване; всичко друго е отказ, никога догадка. */
  private static LocalDate date(String field, JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "'" + text + "' не е дата за " + field + "; подайте я във формат yyyy-MM-dd.");
    }
  }

  @Override
  public AiInvoiceFieldChange applyLineField(InvoiceLine line, String field, JsonNode value) {
    if (F_BG_VAT_CREDIT_TYPE.equals(field)) {
      String before = line.getBgVatCreditType();
      String after = selection(field, value, VAT_CREDIT_TYPES);
      line.setBgVatCreditType(after);
      return new AiInvoiceFieldChange(field, before, after);
    }
    if (F_BG_INCOME_TYPE_ID.equals(field) || F_BG_INCOME_TYPE_CODE.equals(field)) {
      BgIncomeType before = line.getBgIncomeType();
      BgIncomeType after = null;
      if (value != null && !value.isNull()) {
        after = F_BG_INCOME_TYPE_ID.equals(field) ? byId(field, value) : byCode(field, value);
      }
      line.setBgIncomeType(after);
      return new AiInvoiceFieldChange(
          field, before == null ? null : before.getCode(), after == null ? null : after.getCode());
    }
    return null;
  }

  private static BgIncomeType byId(String field, JsonNode value) {
    long id = id(field, value);
    BgIncomeType found = Beans.get(BgIncomeTypeRepository.class).find(id);
    if (found == null) {
      throw new IllegalArgumentException("няма вид доход по ЗДДФЛ с id " + id + ".");
    }
    return found;
  }

  private static BgIncomeType byCode(String field, JsonNode value) {
    String code = value.asText("").trim();
    if (code.isEmpty()) {
      throw new IllegalArgumentException("'" + field + "' не може да е празен.");
    }
    List<BgIncomeType> matches =
        Beans.get(BgIncomeTypeRepository.class)
            .all()
            .filter("LOWER(self.code) = :code")
            .bind("code", code.toLowerCase(Locale.ROOT))
            .fetch(2);
    if (matches.isEmpty()) {
      throw new IllegalArgumentException("няма вид доход по ЗДДФЛ с код '" + code + "'.");
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "повече от един вид доход има код '" + code + "'; подайте bgIncomeTypeId.");
    }
    return matches.get(0);
  }

  @Override
  public Map<String, Object> readHeaderFields(Invoice invoice) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(F_NAP_DOCUMENT_TYPE, invoice.getNapDocumentTypeSelect());
    values.put(F_BG_VAT_SUPPLY_TYPE, invoice.getBgVatSupplyType());
    LocalDate taxEvent = invoice.getBgVatTaxEventDate();
    values.put(F_BG_VAT_TAX_EVENT_DATE, taxEvent == null ? null : taxEvent.toString());
    return values;
  }

  @Override
  public Map<String, Object> readLineFields(InvoiceLine line) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(F_BG_VAT_CREDIT_TYPE, line.getBgVatCreditType());
    BgIncomeType incomeType = line.getBgIncomeType();
    values.put(F_BG_INCOME_TYPE_ID, incomeType == null ? null : incomeType.getId());
    values.put(F_BG_INCOME_TYPE_CODE, incomeType == null ? null : incomeType.getCode());
    return values;
  }

  /**
   * Стойност от точната селекция, или null при явно изчистване.
   *
   * <p>Отказът при непозната стойност е важният случай: код извън списъка би влязъл в дневника и би
   * го направил невалиден при подаване, а по-късно никой не помни откъде е дошъл.
   */
  private static String selection(String field, JsonNode value, List<String> allowed) {
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    // Нормализира „1" към „1" и „01" към „01" — но НЕ ги приравнява: за НАП това са различни
    // селекции с различни списъци, и мълчаливото допълване с нула би записало друг режим.
    String candidate = text.toLowerCase(Locale.ROOT);
    for (String option : allowed) {
      if (option.toLowerCase(Locale.ROOT).equals(candidate)) {
        return option;
      }
    }
    throw new IllegalArgumentException(
        "'"
            + text
            + "' не е допустима стойност за "
            + field
            + "; допустими: "
            + String.join(", ", allowed)
            + ".");
  }

  private static long id(String field, JsonNode value) {
    long id;
    if (value.isNumber()) {
      id = value.asLong();
    } else {
      try {
        id = Long.parseLong(value.asText("").trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("'" + field + "' трябва да е числов id.");
      }
    }
    if (id <= 0) {
      throw new IllegalArgumentException("'" + field + "' трябва да е положителен id.");
    }
    return id;
  }
}
