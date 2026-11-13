package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * TaxTable (MF.TT.*) ОТ ДАНЪЦИТЕ, ПОЛЗВАНИ В ПЕРИОДА — чиста функция.
 *
 * <p><b>⚠️ САМО ПОЛЗВАНИТЕ КОДОВЕ — ОТ ГОТОВИЯ ФАЙЛ.</b> Таблицата е на ФАЙЛА, не на системата: в
 * нея влизат кодовете по TAX-IMP, които редовете на файла сочат (QA с. 38, MF.TT.1: „за които са
 * подадени данни в останалите секции/подсекции на файла“). От G6a записите се строят с {@link
 * #fromCodes} от кодовете на дневника СЛЕД рутера на 600/700 и на фактурите и протоколите СЛЕД
 * клетките им — преди това TaxTable не виждаше 600/700, клетъчните 100xxx и разминаването на
 * BG_ZRP. {@link #assemble} остава за находките по мапинга.
 *
 * <p><b>⚠️ 000/000000 Е ЗА НЕПРИЛОЖИМИЯ КОД</b> (бележка R5 на лист TAX-IMP). Влиза в два случая:
 * ред в периода без данък — за него дневникът (G3) ще пише точно тази двойка, и това е законната ѝ
 * употреба, без находка; и данък без ред в мапинга или с код, който го няма в TAX-IMP — с
 * предупреждение, защото там кодът не е неприложим, а неизвестен.
 *
 * <p><b>⚠️ „ДА СЕ ПОТВЪРДИ“ НЕ СТАВА 000000.</b> Данъците с празен {@code safTaxCode} имат код —
 * той зависи от сделката (основание, част на Приложение №2). Замяната им с „неприложим“ би подала
 * грешно твърдение; тук те не влизат и излизат с предупреждение, а кодът им се решава на ред.
 *
 * <p><b>⚠️ TaxPercentage = СТАВКАТА В ПРОЦЕНТИ, НЕ ДРОБТА ОТ ФАЙЛА.</b> Колоната „СТАВКА“ в книгата
 * е форматирана „0%“ (вграден формат 9): клетката показва 20 %, а пази 0.2 — оттам и {@code 0.2} в
 * {@code tax_imp.csv}. Образецът на НАП пише {@code <TaxPercentage>20</TaxPercentage>}. Затова
 * дробта се умножава по 100 — само за ДДС (вид 100) и само когато е число от 0 до 1. „Н/П“ и
 * плоските суми по Закона за хазарта (500, 5000, 22000) НЕ са процент и не влизат в това поле.
 */
public final class BgSaftTaxTableAssembler {

  /** TaxType и TaxCode за „не е приложим код от номенклатурата“ (TAX-IMP, бележка R5). */
  public static final String NOT_APPLICABLE_TYPE = "000";

  public static final String NOT_APPLICABLE_CODE = "000000";

  /** Видът на ДДС в TAX-IMP. */
  public static final String VAT = "100";

  /** MF.TT.12 — схемата допуска само BG. */
  public static final String COUNTRY = "BG";

  static final String NOT_APPLICABLE_TYPE_DESCRIPTION = "Неприложим вид данък";

  static final String NOT_APPLICABLE_CODE_DESCRIPTION =
      "Не е приложим код от номенклатурата TAX-IMP";

  private BgSaftTaxTableAssembler() {}

  /** Ред от {@code BgSafTaxMapping}. */
  public static final class MappingRow {
    private final String safTaxType;
    private final String safTaxCode;
    private final boolean dynamic;

    /**
     * @param safTaxType TaxType по TAX-IMP
     * @param safTaxCode TaxCode по TAX-IMP; празен за „да се потвърди“
     */
    public MappingRow(String safTaxType, String safTaxCode) {
      this(safTaxType, safTaxCode, false);
    }

    /**
     * @param dynamic {@code isDynamic}: кодът е подразбиране, истинската клетка се смята на ред
     */
    public MappingRow(String safTaxType, String safTaxCode, boolean dynamic) {
      this.safTaxType = safTaxType;
      this.safTaxCode = safTaxCode;
      this.dynamic = dynamic;
    }

    String getSafTaxType() {
      return safTaxType;
    }

    String getSafTaxCode() {
      return safTaxCode;
    }

    boolean isDynamic() {
      return dynamic;
    }
  }

  /** Редовете на таблицата и находките по пътя. */
  public static final class Result {
    private final List<SaftTaxTableEntry> entries;
    private final List<BgDeclarationFileFinding> findings;

    private Result(List<SaftTaxTableEntry> entries, List<BgDeclarationFileFinding> findings) {
      this.entries = Collections.unmodifiableList(entries);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftTaxTableEntry> getEntries() {
      return entries;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param usedTaxes кодовете на данъците (Tax.code), ползвани по редовете на периода
   * @param mappings {@code BgSafTaxMapping} по код на данъка
   * @param untaxedLines има ли в периода ред без данък
   * @param nomenclature TAX-IMP ({@link BgNraNomenclatures#taxCodes()})
   */
  public static Result assemble(
      Collection<String> usedTaxes,
      Map<String, MappingRow> mappings,
      boolean untaxedLines,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature) {
    // вид → код → ред от номенклатурата (null за 000000); подредени, без повторения
    Map<String, Map<String, BgNraNomenclatures.TaxCode>> codes = new TreeMap<>();
    List<String> unmapped = new ArrayList<>();
    List<String> invalidCode = new ArrayList<>();
    List<String> toConfirm = new ArrayList<>();
    List<String> typeMismatch = new ArrayList<>();

    if (untaxedLines) {
      notApplicable(codes);
    }
    for (String tax : new TreeSet<>(trimmed(usedTaxes))) {
      MappingRow mapping = mappings.get(tax);
      if (mapping == null) {
        unmapped.add(tax);
        notApplicable(codes);
        continue;
      }
      String code = trim(mapping.safTaxCode);
      if (code.isEmpty()) {
        toConfirm.add(tax);
        continue;
      }
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      if (row == null) {
        invalidCode.add(tax + " → „" + code + "“");
        notApplicable(codes);
        continue;
      }
      String type = trim(row.taxType());
      String mappedType = trim(mapping.safTaxType);
      if (!mappedType.isEmpty() && !mappedType.equals(type)) {
        typeMismatch.add(tax + ": „" + mappedType + "“, а " + code + " е от вид " + type);
      }
      codes.computeIfAbsent(type, t -> new TreeMap<>()).put(code, row);
    }

    List<String> nonVat = new ArrayList<>();
    List<SaftTaxTableEntry> entries = new ArrayList<>();
    for (Map.Entry<String, Map<String, BgNraNomenclatures.TaxCode>> type : codes.entrySet()) {
      SaftTaxTableEntry entry = new SaftTaxTableEntry().taxType(type.getKey());
      String description = null;
      for (Map.Entry<String, BgNraNomenclatures.TaxCode> code : type.getValue().entrySet()) {
        BgNraNomenclatures.TaxCode row = code.getValue();
        SaftTaxCodeDetail detail = new SaftTaxCodeDetail().taxCode(code.getKey()).country(COUNTRY);
        if (row == null) {
          description = NOT_APPLICABLE_TYPE_DESCRIPTION;
          detail.description(NOT_APPLICABLE_CODE_DESCRIPTION);
        } else {
          if (description == null) {
            description = trim(row.taxName());
          }
          detail.description(trim(row.description())).taxPercentage(percentage(row));
          if (!VAT.equals(type.getKey())) {
            nonVat.add(code.getKey() + " (ставка „" + trim(row.rate()) + "“)");
          }
        }
        entry.addTaxCodeDetail(detail);
      }
      entries.add(entry.description(description));
    }

    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (entries.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_TT_EMPTY",
              "Няма ред в периода, нито с данък, нито без — схемата иска поне един данъчен код"
                  + " (TaxTable/TaxTableEntry)."));
    }
    for (String item : unmapped) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_TAX_UNMAPPED",
              "Данъци без ред в мапинга към TAX-IMP — подадени като 000/000000 (MF.TT.2/5,"
                  + " бележка R5): "
                  + item));
    }
    for (String item : invalidCode) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_TAX_CODE_INVALID",
              "Мапингът дава код, който го няма в TAX-IMP — подаден като 000/000000"
                  + " (MF.TT.5): "
                  + item));
    }
    for (String item : toConfirm) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_TAX_TO_CONFIRM",
              "Данъци без установен код по TAX-IMP („да се потвърди“) — не влизат в"
                  + " TaxTable, кодът им зависи от сделката и се решава на ред: "
                  + item));
    }
    for (String item : typeMismatch) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_TAX_TYPE_MISMATCH",
              "Видът в мапинга не съвпада с вида на кода в TAX-IMP — подаден е видът от"
                  + " номенклатурата (MF.TT.2): "
                  + item));
    }
    for (String item : nonVat) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_NON_VAT_TO_CONFIRM",
              "Кодове извън ДДС — подадени без TaxPercentage; дали ставката им е процент или"
                  + " плоска сума (MF.TT.9/10) да се потвърди: "
                  + item));
    }
    return new Result(entries, findings);
  }

  /**
   * TaxTable от кодовете, които файлът реално сочи — по един запис на вид, кодовете подредени.
   * 000000 е неприложимият код (бележка R5); код извън TAX-IMP не влиза — ГРЕШКА за него дава
   * {@link BgSaftReferenceCheck}, понеже редът го сочи, а таблицата го няма.
   *
   * @param usedCodes S.TI.2 TaxCode от редовете на дневника, фактурите и протоколите
   */
  public static Result fromCodes(
      Collection<String> usedCodes, Map<String, BgNraNomenclatures.TaxCode> nomenclature) {
    Map<String, Map<String, BgNraNomenclatures.TaxCode>> codes = new TreeMap<>();
    for (String code : new TreeSet<>(trimmed(usedCodes))) {
      if (NOT_APPLICABLE_CODE.equals(code)) {
        notApplicable(codes);
        continue;
      }
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      if (row != null) {
        codes.computeIfAbsent(trim(row.taxType()), t -> new TreeMap<>()).put(code, row);
      }
    }
    List<String> nonVat = new ArrayList<>();
    List<SaftTaxTableEntry> entries = new ArrayList<>();
    for (Map.Entry<String, Map<String, BgNraNomenclatures.TaxCode>> type : codes.entrySet()) {
      SaftTaxTableEntry entry = new SaftTaxTableEntry().taxType(type.getKey());
      String description = null;
      for (Map.Entry<String, BgNraNomenclatures.TaxCode> code : type.getValue().entrySet()) {
        BgNraNomenclatures.TaxCode row = code.getValue();
        SaftTaxCodeDetail detail = new SaftTaxCodeDetail().taxCode(code.getKey()).country(COUNTRY);
        if (row == null) {
          description = NOT_APPLICABLE_TYPE_DESCRIPTION;
          detail.description(NOT_APPLICABLE_CODE_DESCRIPTION);
        } else {
          if (description == null) {
            description = trim(row.taxName());
          }
          detail.description(trim(row.description())).taxPercentage(percentage(row));
          if (!VAT.equals(type.getKey())) {
            nonVat.add(code.getKey() + " (ставка „" + trim(row.rate()) + "“)");
          }
        }
        entry.addTaxCodeDetail(detail);
      }
      entries.add(entry.description(description));
    }
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (entries.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_TT_EMPTY",
              "Файлът не сочи нито един данъчен код — схемата иска поне един"
                  + " (TaxTable/TaxTableEntry)."));
    }
    for (String item : nonVat) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_TT_NON_VAT_TO_CONFIRM",
              "Код извън ДДС — подаден без TaxPercentage; дали ставката му е процент или плоска"
                  + " сума (MF.TT.9/10) да се потвърди: "
                  + item));
    }
    return new Result(entries, findings);
  }

  /**
   * MF.TT.9 в проценти — само за ДДС и само когато колоната „СТАВКА“ е дроб от 0 до 1; иначе {@code
   * null} и елементът не се пише.
   */
  static BigDecimal percentage(BgNraNomenclatures.TaxCode row) {
    if (!VAT.equals(trim(row.taxType()))) {
      return null;
    }
    BigDecimal fraction;
    try {
      fraction = new BigDecimal(trim(row.rate()));
    } catch (NumberFormatException e) {
      return null;
    }
    if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
      return null;
    }
    BigDecimal percent = fraction.movePointRight(2).stripTrailingZeros();
    return percent.scale() < 0 ? percent.setScale(0) : percent;
  }

  // ─────────────── помощници ───────────────

  private static void notApplicable(Map<String, Map<String, BgNraNomenclatures.TaxCode>> codes) {
    codes.computeIfAbsent(NOT_APPLICABLE_TYPE, t -> new TreeMap<>()).put(NOT_APPLICABLE_CODE, null);
  }

  private static List<String> trimmed(Collection<String> values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      String v = trim(value);
      if (!v.isEmpty()) {
        result.add(v);
      }
    }
    return result;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
