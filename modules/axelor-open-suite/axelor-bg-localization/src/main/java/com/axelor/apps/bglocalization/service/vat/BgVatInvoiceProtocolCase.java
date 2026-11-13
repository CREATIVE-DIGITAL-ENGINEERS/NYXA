package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxEquiv;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * СЛУЧАЯТ НА ПРОТОКОЛА ПО ЧЛ. 117 ЗДДС ЗА ЕДИН РЕД НА ПОКУПНА ФАКТУРА С ОБРАТНО НАЧИСЛЯВАНЕ — чиста
 * функция.
 *
 * <p>Входът е данъкът за обратно начисляване на реда ({@code TaxEquiv.reverseChargeTaxSet}),
 * собственият данък на реда ({@code InvoiceLine.taxLineSet}), видът на продукта и доставчикът.
 * Изходът е операцията на протокола ({@code bg.vat.move.operation.select} — същата като на ръчния),
 * к.8а и основанието по чл. 117 ал. 2 т. 7 — или защо протокол не се съставя.
 *
 * <p><b>Протокол</b> се съставя за:
 *
 * <ul>
 *   <li><b>ВОП</b> ({@code BG_VOP_SELF}) — ЗДДС чл. 84: „Данъкът при вътреобщностни придобивания е
 *       изискуем от лицето, което извършва придобиването.“ Протоколът — чл. 117 ал. 1 т. 1.
 *   <li><b>Услуга по чл. 82 ал. 2 т. 3</b> ({@code BG_RCM_ART82_S} до {@code BG_EU_S_RCM_P} или до
 *       общия {@code BG_RCM_ART82_P}) — „доставка на услуги - когато получателят е данъчно
 *       задължено лице по чл. 3, ал. 1, 5 и 6, с изключение на случаите, когато доставчикът има
 *       регистрация за прилагане в страната на режима за малки предприятия в Европейския съюз.“
 *       Протоколът — чл. 117 ал. 1 т. 1.
 *   <li><b>Енергия по чл. 82 ал. 2 т. 1</b> ({@code BG_RCM_ENERGY_S}) — само когато доставчикът „не
 *       е установено на територията на страната“ (ал. 2, отварящото изречение). Колоните са тези на
 *       услугата — к.14/к.15 („ДО на получени доставки по чл. 82, ал. 2 - 6 ЗДДС“).
 *   <li><b>Чл. 163а</b> — само Прил. № 2: {@code BG_RCM_SCRAP_S} (част първа → к.8а „01“) и {@code
 *       BG_RCM_GRAIN_S} (част втора → „02“), ППЗДДС чл. 97а ал. 1 б. „а“ и „б“. Протоколът — чл.
 *       117 ал. 1 т. 4 („в случаите по чл. 161 и 163а, ал. 2“). Част втора е „(В сила до 31.12.2026
 *       г. …)“ — събитие след тази дата е ГРЕШКА.
 * </ul>
 *
 * <p><b>ГРЕШКА</b> (протокол не се съставя): {@code BG_RCM_BUILD_S} и {@code BG_RCM_MOBILE_S} —
 * няма ги нито в Прил. № 2, нито в чл. 82, тоест обратното начисляване няма основание; енергия или
 * услуга от доставчик, установен в страната; внос по чл. 167а ({@code BG_IMP_ART57} — протоколът е
 * по ЕАД); стока по общия {@code BG_RCM_ART82_P} — чл. 82 ал. 2 т. 2 е „(отм. - ДВ, бр. 115 от 2025
 * г., в сила от 01.01.2026 г.)“; чл. 163а от физическо лице без ДДС номер — общият протокол по чл.
 * 163б ал. 1 т. 2 е извън обхвата; всеки друг данък за обратно начисляване (злато по чл. 161, част
 * трета на Прил. № 2 и т.н.).
 *
 * <p><b>ПРЕДУПРЕЖДЕНИЕ</b> без протокол: услуга от доставчик с ДДС номер „-EX“ — изключението на
 * чл. 82 ал. 2 т. 3 („…режима за малки предприятия в Европейския съюз“).
 *
 * <p>Посредникът в тристранна операция ({@code BG_TRIANG_P}) и услугата извън ЕС ({@code
 * BG_NON_EU_SERV_P}) нямат обратно начисляване на реда и не стигат дотук — виж {@link
 * #withoutReverseCharge}.
 */
public final class BgVatInvoiceProtocolCase {

  /** Какво става с реда. */
  public enum Outcome {
    /** Съставя се протокол. */
    PROTOCOL,
    /** Протокол не се съставя, а редът носи предупреждение. */
    WARNING,
    /** Протокол не се съставя, а редът е грешка. */
    ERROR,
    /** Редът няма обратно начисляване и не е работа на протокола. */
    NONE
  }

  public static final String TAX_VOP_SELF = "BG_VOP_SELF";

  /** Самоначисляването по чл. 82 — общото за услугите от ЕС, общия чл. 82 и вноса по чл. 57. */
  public static final String TAX_ART82_SELF = "BG_RCM_ART82_S";

  public static final String TAX_EU_SERVICE = "BG_EU_S_RCM_P";
  public static final String TAX_IMPORT_DEFERRED = "BG_IMP_ART57";
  public static final String TAX_SCRAP_SELF = "BG_RCM_SCRAP_S";
  public static final String TAX_GRAIN_SELF = "BG_RCM_GRAIN_S";
  public static final String TAX_BUILD_SELF = "BG_RCM_BUILD_S";
  public static final String TAX_MOBILE_SELF = "BG_RCM_MOBILE_S";
  public static final String TAX_ENERGY_SELF = "BG_RCM_ENERGY_S";
  public static final String TAX_NON_EU_SERVICE = "BG_NON_EU_SERV_P";

  /** к.8а по ППЗДДС чл. 97а ал. 1 б. „а“ и „б“. */
  public static final String SUPPLY_ANNEX2_PART1 = "01";

  public static final String SUPPLY_ANNEX2_PART2 = "02";

  /** Прил. № 2, част втора: „(В сила до 31.12.2026 г. …)“. */
  public static final LocalDate ANNEX2_PART2_LAST_DAY = LocalDate.of(2026, 12, 31);

  /** Основанието за начисляване — ЗДДС чл. 117 ал. 2 т. 7. */
  public static final String BASIS_VOP = "чл. 84 ЗДДС";

  public static final String BASIS_ART82_SERVICES = "чл. 82, ал. 2, т. 3 ЗДДС";
  public static final String BASIS_ART82_ENERGY = "чл. 82, ал. 2, т. 1 ЗДДС";
  public static final String BASIS_ART163A = "чл. 163а, ал. 2 ЗДДС";

  /** Задължението да се издаде протоколът — ЗДДС чл. 117 ал. 1. */
  public static final String ISSUED_UNDER_117_1_1 = "чл. 117, ал. 1, т. 1 ЗДДС";

  public static final String ISSUED_UNDER_117_1_4 = "чл. 117, ал. 1, т. 4 ЗДДС";

  private final Outcome outcome;
  private final String operation;
  private final String supplyType;
  private final String basis;
  private final String issuedUnder;
  private final String message;

  private BgVatInvoiceProtocolCase(
      Outcome outcome,
      String operation,
      String supplyType,
      String basis,
      String issuedUnder,
      String message) {
    this.outcome = outcome;
    this.operation = operation;
    this.supplyType = supplyType;
    this.basis = basis;
    this.issuedUnder = issuedUnder;
    this.message = message;
  }

  public Outcome getOutcome() {
    return outcome;
  }

  /**
   * Операцията на протокола ({@code bg.vat.move.operation.select}); само при {@link
   * Outcome#PROTOCOL}.
   */
  public String getOperation() {
    return operation;
  }

  /** к.8а на протокола или {@code null}. */
  public String getSupplyType() {
    return supplyType;
  }

  /** Основанието по ЗДДС чл. 117 ал. 2 т. 7. */
  public String getBasis() {
    return basis;
  }

  /** Точката на чл. 117 ал. 1, по която протоколът се издава. */
  public String getIssuedUnder() {
    return issuedUnder;
  }

  /** Защо протокол не се съставя, или предупреждението към протокола. */
  public String getMessage() {
    return message;
  }

  public boolean isArt163a() {
    return BgVatMoveOperationRouting.OPERATION_ART163A.equals(operation);
  }

  /**
   * Ключът, по който редовете се събират в един протокол: операцията, к.8а и основанието. Ставката
   * е отделно — виж {@link BgVatInvoiceProtocolPlan}.
   */
  public String groupKey() {
    return operation + "|" + (supplyType == null ? "" : supplyType) + "|" + basis;
  }

  private static BgVatInvoiceProtocolCase protocol(
      String operation, String supplyType, String basis, String issuedUnder) {
    return protocol(operation, supplyType, basis, issuedUnder, null);
  }

  private static BgVatInvoiceProtocolCase protocol(
      String operation, String supplyType, String basis, String issuedUnder, String warning) {
    return new BgVatInvoiceProtocolCase(
        Outcome.PROTOCOL, operation, supplyType, basis, issuedUnder, warning);
  }

  private static BgVatInvoiceProtocolCase error(String message) {
    return new BgVatInvoiceProtocolCase(Outcome.ERROR, null, null, null, null, message);
  }

  private static BgVatInvoiceProtocolCase warning(String message) {
    return new BgVatInvoiceProtocolCase(Outcome.WARNING, null, null, null, null, message);
  }

  private static final BgVatInvoiceProtocolCase NONE =
      new BgVatInvoiceProtocolCase(Outcome.NONE, null, null, null, null, null);

  /** Доставчикът — колкото е нужно на правилата. */
  public static final class Supplier {
    private final String vatNumber;
    private final String countryAlpha2;
    private final boolean individual;

    /**
     * @param vatNumber ДДС номерът му ({@code Invoice.partnerTaxNbr}, иначе {@code Partner.taxNbr})
     * @param countryAlpha2 държавата от основния адрес
     * @param individual физическо лице ({@code Partner.partnerTypeSelect})
     */
    public Supplier(String vatNumber, String countryAlpha2, boolean individual) {
      this.vatNumber = vatNumber;
      this.countryAlpha2 = countryAlpha2;
      this.individual = individual;
    }

    /** Режимът за малки предприятия в ЕС — номер по чл. 168ж ал. 11 с окончание „-EX“. */
    boolean isSmallEnterprise() {
      return BgVatNumberValidator.isSmallEnterpriseNumber(vatNumber);
    }

    /**
     * Установен в страната: адресът е в България. ⚠️ Българският ДДС номер не е признак —
     * чуждестранно лице може да е регистрирано по ЗДДС, без да е установено (чл. 82 ал. 2).
     */
    boolean isEstablishedInBulgaria() {
      return "BG".equalsIgnoreCase(trim(countryAlpha2));
    }

    /** От държава членка: чужд ДДС номер от ЕС или адрес в ЕС (Гърция е „EL“ в номера). */
    boolean isFromOtherMemberState() {
      if (BgVatNumberValidator.isForeign(vatNumber)
          && BgVatNumberValidator.isViesCountry(BgVatNumberValidator.countryCode(vatNumber))) {
        return true;
      }
      String country = trim(countryAlpha2);
      if (country == null || "BG".equalsIgnoreCase(country)) {
        return false;
      }
      return BgVatNumberValidator.isViesCountry(
          "GR".equalsIgnoreCase(country) ? "EL" : country.toUpperCase());
    }

    /** Физическо лице без ДДС номер. */
    boolean isIndividualWithoutVatNumber() {
      return individual && trim(vatNumber) == null;
    }
  }

  /**
   * Случаят на реда.
   *
   * @param reverseChargeTaxCodes кодовете на {@code TaxEquiv.reverseChargeTaxSet}; празно — редът
   *     няма обратно начисляване
   * @param lineTaxCodes кодовете на {@code InvoiceLine.taxLineSet}
   * @param service продуктът на реда е услуга; {@code null} — редът няма продукт
   * @param taxEventDate датата на данъчното събитие (за част втора на Прил. № 2)
   */
  public static BgVatInvoiceProtocolCase resolve(
      Collection<String> reverseChargeTaxCodes,
      Collection<String> lineTaxCodes,
      Boolean service,
      Supplier supplier,
      LocalDate taxEventDate) {
    Collection<String> rc =
        reverseChargeTaxCodes == null ? Collections.emptyList() : reverseChargeTaxCodes;
    Collection<String> own = lineTaxCodes == null ? Collections.emptyList() : lineTaxCodes;
    if (rc.isEmpty()) {
      return NONE;
    }
    if (rc.size() > 1) {
      return error(
          "редът носи няколко данъка за обратно начисляване "
              + rc
              + " — протоколът по чл. 117 е за една ставка и едно основание");
    }
    String code = trim(rc.iterator().next());
    code = code == null ? "" : code;
    switch (code) {
      case TAX_VOP_SELF:
        return protocol(
            BgVatMoveOperationRouting.OPERATION_VOP, null, BASIS_VOP, ISSUED_UNDER_117_1_1);
      case TAX_ART82_SELF:
        return art82(own, service, supplier);
      case TAX_SCRAP_SELF:
        return art163a(SUPPLY_ANNEX2_PART1, supplier);
      case TAX_GRAIN_SELF:
        if (taxEventDate != null && taxEventDate.isAfter(ANNEX2_PART2_LAST_DAY)) {
          return error(
              "доставка по Прил. № 2, част втора, със събитие "
                  + taxEventDate
                  + " — частта е „В сила до 31.12.2026 г.“; обратно начисляване по чл. 163а няма");
        }
        return art163a(SUPPLY_ANNEX2_PART2, supplier);
      case TAX_BUILD_SELF:
      case TAX_MOBILE_SELF:
        return error(
            "данъкът „"
                + code
                + "“ няма основание за обратно начисляване — доставката я няма нито в Прил. № 2"
                + " ЗДДС (чл. 163а), нито в чл. 82; протокол не се съставя, поправете данъка на"
                + " реда");
      case TAX_ENERGY_SELF:
        if (supplier == null || supplier.isEstablishedInBulgaria()) {
          return error(
              "енергия с обратно начисляване от доставчик, установен в страната — чл. 82, ал. 2,"
                  + " т. 1 ЗДДС важи само за доставчик, който „не е установено на територията на"
                  + " страната“; протокол не се съставя");
        }
        return protocol(foreignOperation(supplier), null, BASIS_ART82_ENERGY, ISSUED_UNDER_117_1_1);
      default:
        return error(
            "данъкът за обратно начисляване „"
                + code
                + "“ няма протокол по чл. 117 в Nyxa (напр. злато по чл. 161, Прил. № 2 част"
                + " трета) — съставете протокола като ръчна статия");
    }
  }

  private static BgVatInvoiceProtocolCase art82(
      Collection<String> own, Boolean service, Supplier supplier) {
    if (own.contains(TAX_IMPORT_DEFERRED)) {
      return error(
          "внос с отложено начисляване по чл. 167а — протоколът е по митническия документ (ЕАД),"
              + " не по фактурата; съставете го като ръчна статия");
    }
    if (supplier != null && supplier.isEstablishedInBulgaria()) {
      return error(
          "обратно начисляване по чл. 82, ал. 2 от доставчик, установен в страната — алинеята е"
              + " за доставчик, който „не е установено на територията на страната“; протокол не се"
              + " съставя");
    }
    boolean euService = own.contains(TAX_EU_SERVICE);
    if (!euService && Boolean.FALSE.equals(service)) {
      return error(
          "стока с обратно начисляване по чл. 82, ал. 2 — т. 2 е отменена (ДВ, бр. 115 от 2025"
              + " г., в сила от 01.01.2026 г.), а т. 1 е само енергията (данък „"
              + TAX_ENERGY_SELF
              + "“); уточнете данъка на реда");
    }
    if (supplier != null && supplier.isSmallEnterprise()) {
      return warning(
          "доставчикът е с ДДС номер „-EX“ (режим за малки предприятия в ЕС) — по чл. 82, ал. 2,"
              + " т. 3 ЗДДС данъкът тогава не е изискуем от получателя; протокол не се съставя,"
              + " поправете данъка на фактурата");
    }
    String operation =
        euService ? BgVatMoveOperationRouting.OPERATION_SERVICE_EU : foreignOperation(supplier);
    String note =
        service == null && !euService
            ? "редът няма продукт — приет за услуга по чл. 82, ал. 2, т. 3 ЗДДС (стоката няма"
                + " обратно начисляване по чл. 82 от 01.01.2026 г.); да се потвърди"
            : null;
    return protocol(operation, null, BASIS_ART82_SERVICES, ISSUED_UNDER_117_1_1, note);
  }

  private static BgVatInvoiceProtocolCase art163a(String supplyType, Supplier supplier) {
    if (supplier != null && supplier.isIndividualWithoutVatNumber()) {
      return error(
          "доставка по чл. 163а от физическо лице без ДДС номер — ако то не е данъчно задължено,"
              + " данъкът се начислява с общ протокол за периода (чл. 163б, ал. 1, т. 2 ЗДДС), който"
              + " Nyxa не съставя; съставете протокола като ръчна статия");
    }
    return protocol(
        BgVatMoveOperationRouting.OPERATION_ART163A,
        supplyType,
        BASIS_ART163A,
        ISSUED_UNDER_117_1_4);
  }

  private static String foreignOperation(Supplier supplier) {
    return supplier != null && supplier.isFromOtherMemberState()
        ? BgVatMoveOperationRouting.OPERATION_SERVICE_EU
        : BgVatMoveOperationRouting.OPERATION_SERVICE_NONEU;
  }

  /**
   * Ред БЕЗ обратно начисляване, който все пак иска бележка. Услугата от трета страна ({@code
   * BG_NON_EU_SERV_P}, 0 %, „извън обхват“) може да е с място на изпълнение в страната по чл. 21
   * ал. 2 и тогава данъкът е изискуем от получателя (чл. 82 ал. 2 т. 3) — данъкът на реда не го
   * казва.
   *
   * <p>Посредникът в тристранна операция ({@code BG_TRIANG_P}) НЕ е бележка: той остава в к.15 на
   * покупките без протокол — ППЗДДС чл. 113 ал. 12: „…колони от 9 до 14 не се попълват … а в колона
   * "ДО при придобиване на стоки от посредник в тристранна операция" се посочва данъчната основа…“.
   * Протоколът по чл. 82 ал. 3 е на КРАЙНИЯ придобиващ, а за него в seed-а данък няма.
   *
   * @return предупреждението или {@code null}
   */
  public static String withoutReverseCharge(Collection<String> lineTaxCodes) {
    if (lineTaxCodes != null && lineTaxCodes.contains(TAX_NON_EU_SERVICE)) {
      return "услуга от трета страна с данък „"
          + TAX_NON_EU_SERVICE
          + "“ (извън обхват) — проверете мястото на изпълнение (ЗДДС чл. 21): ако е в страната,"
          + " данъкът е изискуем от получателя (чл. 82, ал. 2, т. 3) и се начислява с протокол";
    }
    return null;
  }

  /** Кодовете на {@code TaxEquiv.reverseChargeTaxSet}, когато редът е с обратно начисляване. */
  public static List<String> reverseChargeTaxCodes(InvoiceLine line) {
    TaxEquiv equiv = line.getTaxEquiv();
    List<String> codes = new ArrayList<>();
    if (equiv == null
        || !Boolean.TRUE.equals(equiv.getReverseCharge())
        || equiv.getReverseChargeTaxSet() == null) {
      return codes;
    }
    for (Tax tax : equiv.getReverseChargeTaxSet()) {
      if (tax != null && trim(tax.getCode()) != null) {
        codes.add(tax.getCode().trim());
      }
    }
    return codes;
  }

  /** Кодовете на собствения данък на реда ({@code InvoiceLine.taxLineSet}). */
  public static List<String> taxCodes(InvoiceLine line) {
    List<String> codes = new ArrayList<>();
    if (line.getTaxLineSet() != null) {
      for (TaxLine taxLine : line.getTaxLineSet()) {
        if (taxLine != null
            && taxLine.getTax() != null
            && trim(taxLine.getTax().getCode()) != null) {
          codes.add(taxLine.getTax().getCode().trim());
        }
      }
    }
    return codes;
  }

  /** Продуктът на реда е услуга; {@code null}, когато редът няма продукт. */
  public static Boolean isService(InvoiceLine line) {
    if (line.getProduct() == null || line.getProduct().getProductTypeSelect() == null) {
      return null;
    }
    return ProductRepository.PRODUCT_TYPE_SERVICE.equals(line.getProduct().getProductTypeSelect());
  }

  /**
   * Ставката на данъка за обратно начисляване на реда, в ПРОЦЕНТИ — активният ред на първия данък в
   * {@code TaxEquiv.reverseChargeTaxSet}; нула, когато няма. Едно правило за колектора, услугата и
   * печата на протокола.
   */
  public static BigDecimal reverseChargeRate(InvoiceLine line) {
    for (Tax tax : line.getTaxEquiv().getReverseChargeTaxSet()) {
      if (tax != null && tax.getActiveTaxLine() != null) {
        return tax.getActiveTaxLine().getValue();
      }
    }
    return BigDecimal.ZERO;
  }

  /** Случаят на реда на фактурата. */
  public static BgVatInvoiceProtocolCase of(
      InvoiceLine line, Supplier supplier, LocalDate taxEventDate) {
    return resolve(
        reverseChargeTaxCodes(line), taxCodes(line), isService(line), supplier, taxEventDate);
  }

  /** Видовете на известията по {@code bg.nap.document.type.select}. */
  private static final Set<String> NOTE_DOCUMENT_TYPES =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("02", "03", "12", "13", "23")));

  /**
   * Защо от покупната фактура (или известие) с обратно начисляване Nyxa НЕ съставя протокол; {@code
   * null}, когато съставя. Едно правило за услугата и за дневника.
   *
   * <ul>
   *   <li>Известие (КИ от доставчик или вид 02/03/12/13/23) — нужен е протокол по ЗДДС чл. 117 ал.
   *       4 („номерът и датата на първоначалния протокол … увеличението/намалението на данъчната
   *       основа … на данъка“) — отделна задача.
   *   <li>Авансова фактура — изискуемостта е при плащането (чл. 25 ал. 7), а при ВОП фактурата за
   *       аванс не я определя (чл. 63 ал. 5).
   *   <li>Балансова фактура след аванс — основата е намалена с аванса.
   * </ul>
   */
  public static String invoiceRefusal(Invoice invoice) {
    String type = trim(invoice.getNapDocumentTypeSelect());
    if (invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
        || (type != null && NOTE_DOCUMENT_TYPES.contains(type))) {
      return "известие"
          + (type == null ? "" : " (вид „" + type + "“)")
          + " — нужен е протокол по ЗДДС чл. 117, ал. 4 (номер и дата на първоначалния протокол,"
          + " основание, изменението на основата и данъка), който Nyxa още не съставя";
    }
    int subType = invoice.getOperationSubTypeSelect();
    if (subType == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE) {
      return "авансова фактура — изискуемостта е при плащането (ЗДДС чл. 25, ал. 7), а при ВОП"
          + " фактурата за аванс не я определя (чл. 63, ал. 5); Nyxa още не съставя такъв"
          + " протокол";
    }
    if (subType == InvoiceRepository.OPERATION_SUB_TYPE_BALANCE) {
      return "балансова фактура след аванс — основата е намалена с аванса, а Nyxa още не"
          + " съставя такъв протокол";
    }
    return null;
  }

  /**
   * Доставчикът на фактурата: ДДС номерът от фактурата, иначе от партньора; държавата от адреса.
   */
  public static Supplier supplierOf(Invoice invoice) {
    Partner partner = invoice.getPartner();
    String vat = invoice.getPartnerTaxNbr();
    if (trim(vat) == null && partner != null) {
      vat = partner.getTaxNbr();
    }
    String country =
        partner != null
                && partner.getMainAddress() != null
                && partner.getMainAddress().getCountry() != null
            ? partner.getMainAddress().getCountry().getAlpha2Code()
            : null;
    boolean individual =
        partner != null
            && Integer.valueOf(PartnerRepository.PARTNER_TYPE_INDIVIDUAL)
                .equals(partner.getPartnerTypeSelect());
    return new Supplier(vat, country, individual);
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
