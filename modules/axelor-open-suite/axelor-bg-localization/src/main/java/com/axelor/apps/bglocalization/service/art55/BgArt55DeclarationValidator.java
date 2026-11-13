package com.axelor.apps.bglocalization.service.art55;

import com.axelor.apps.bglocalization.db.BgArt55Declaration;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ПРОВЕРКИТЕ НА ОБРАЗЕЦ 4001 — преди човекът да ги изтрака в ПОРТАЛА.
 *
 * <p><b>⚠️ СЪОБЩАВА, НЕ ЗАБРАНЯВА.</b> Обр. 4001 се подава през портала, а не с файл — тоест няма
 * стъпка, която този валидатор да пази. Смисълът му е друг: екранът да каже какво е сбъркано ДОКАТО
 * човек още гледа него, а не след като го е преписал в портала и НАП го е отхвърлила. Образецът,
 * ДОСЛОВНО: „За деклариране на неверни данни се носи наказателна отговорност по Наказателния
 * кодекс.“
 *
 * <p><b>⚠️ ЧЕТЕ ЗАПИСА ПРЯКО, БЕЗ МЕЖДИНЕН DTO.</b> Дотук между него и декларацията стоеше {@code
 * BgArt55FileRow} — слой, който съществуваше САМО за да храни писача. Писач няма и той изчезна
 * заедно с него; оставен, той щеше да е превод без получател.
 *
 * <p>⚠️ И тук НЯМА null-проверки върху сумите: генерираните getter-и връщат {@code
 * BigDecimal.ZERO}, не {@code null} — проверката би била мъртъв код.
 *
 * <p>Проверим без Postgres: записът се строи с {@code new}.
 */
public final class BgArt55DeclarationValidator {

  public static final String PAYER_IDENTIFIER_MISSING = "ART55_PAYER_IDENTIFIER_MISSING";
  public static final String PAYER_NAME_MISSING = "ART55_PAYER_NAME_MISSING";
  public static final String PAYER_CODE_TYPE_INVALID = "ART55_PAYER_CODE_TYPE_INVALID";
  public static final String QUARTER_INVALID = "ART55_QUARTER_INVALID";
  public static final String TAX_YEAR_INVALID = "ART55_TAX_YEAR_INVALID";
  public static final String AMOUNT_NEGATIVE = "ART55_AMOUNT_NEGATIVE";
  public static final String AMOUNT_SCALE = "ART55_AMOUNT_SCALE";
  public static final String REPRESENTATIVE_MISSING = "ART55_REPRESENTATIVE_MISSING";
  public static final String REPRESENTATIVE_NOT_ALLOWED = "ART55_REPRESENTATIVE_NOT_ALLOWED";
  public static final String CONTACT_MISSING = "ART55_CONTACT_MISSING";
  public static final String ALL_ROWS_ZERO = "ART55_ALL_ROWS_ZERO";

  /** Кодовете по Част ІІ, т. 2 — образецът изброява точно пет. */
  private static final Set<String> PAYER_CODE_TYPES =
      new HashSet<>(Arrays.asList("1", "2", "3", "4", "5"));

  /** Кодът „1“ значи ЕИК, тоест подателят е ЮРИДИЧЕСКО лице — и тогава т. 7 се попълва. */
  private static final String PAYER_CODE_EIK = "1";

  /** ЗДДФЛ е в сила от 1 януари 2007 г. — по-ранна данъчна година не съществува за този образец. */
  private static final int FIRST_TAX_YEAR = 2007;

  private static final int AMOUNT_SCALE_LIMIT = 2;

  private BgArt55DeclarationValidator() {}

  /** Всички находки за декларацията. */
  public static List<BgDeclarationFileFinding> validate(BgArt55Declaration declaration) {
    List<BgDeclarationFileFinding> out = new ArrayList<>();
    if (declaration == null) {
      out.add(
          BgDeclarationFileFinding.error(PAYER_IDENTIFIER_MISSING, "Няма декларация за проверка."));
      return out;
    }
    validatePeriod(declaration, out);
    validatePayer(declaration, out);
    validateAmounts(declaration, out);
    return out;
  }

  /** Има ли поне една блокираща находка. */
  public static boolean hasBlocking(List<BgDeclarationFileFinding> findings) {
    for (BgDeclarationFileFinding finding : findings) {
      if (finding.isBlocking()) {
        return true;
      }
    }
    return false;
  }

  /** Находките, по една на ред — това е, което човекът трябва да поправи. */
  public static String describe(List<BgDeclarationFileFinding> findings) {
    StringBuilder sb = new StringBuilder();
    for (BgDeclarationFileFinding finding : findings) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append(finding.isBlocking() ? "⛔ " : "⚠️ ").append(finding.getMessage());
    }
    return sb.toString();
  }

  /** Част І, т. 3.1 и 3.2. */
  private static void validatePeriod(
      BgArt55Declaration declaration, List<BgDeclarationFileFinding> out) {
    int quarter = declaration.getQuarter();
    int taxYear = declaration.getTaxYear();
    if (quarter < 1 || quarter > 4) {
      out.add(
          BgDeclarationFileFinding.error(
              QUARTER_INVALID,
              "Част І, т. 3.2: тримесечието е „"
                  + quarter
                  + "“ — образецът познава само І, ІІ, ІІІ и ІV."));
    }
    if (taxYear < FIRST_TAX_YEAR) {
      out.add(
          BgDeclarationFileFinding.error(
              TAX_YEAR_INVALID,
              "Част І, т. 3.1: данъчната година е „"
                  + taxYear
                  + "“ — ЗДДФЛ е в сила от "
                  + FIRST_TAX_YEAR
                  + " г."));
    }
  }

  /** Част ІІ — данните за данъчно задълженото лице. */
  private static void validatePayer(
      BgArt55Declaration declaration, List<BgDeclarationFileFinding> out) {

    if (isBlank(declaration.getPayerIdentifier())) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_IDENTIFIER_MISSING,
              "Част ІІ, т. 1: липсва ЕИК/ЕГН/ЛН/ЛНЧ/Служебен № на задълженото лице."));
    }
    if (isBlank(declaration.getPayerName())) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_NAME_MISSING, "Част ІІ, т. 3: липсва наименование/име."));
    }
    String codeType = trim(declaration.getPayerCodeType());
    if (!PAYER_CODE_TYPES.contains(codeType)) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_CODE_TYPE_INVALID,
              "Част ІІ, т. 2: кодът по т. 1 е „"
                  + nz(codeType)
                  + "“ — образецът познава „1“ (ЕИК), „2“ (ЕГН), „3“ (ЛН), „4“ (ЛНЧ) и „5“"
                  + " (служебен № от регистъра на НАП)."));
    }

    // ⚠️ Т. 7 СЕ ЧЕТЕ ОТ КОДА ПО Т. 2, А НЕ ОТ ТИПА НА ЗАПИСА В БАЗАТА. Образецът, ДОСЛОВНО:
    // „попълват се само когато декларацията се подава от юридическо лице“. Юридическото лице е
    // точно носителят на ЕИК, тоест код „1“ — а Company в базата може да носи и ЕГН на
    // самоосигуряващо се лице.
    boolean legalEntity = PAYER_CODE_EIK.equals(codeType);
    if (legalEntity && isBlank(declaration.getRepresentativeName())) {
      out.add(
          BgDeclarationFileFinding.error(
              REPRESENTATIVE_MISSING,
              "Част ІІ, т. 7.1: подателят е юридическо лице (код „1“), а представляващият"
                  + " липсва."));
    }
    if (!legalEntity
        && (!isBlank(declaration.getRepresentativeName())
            || !isBlank(declaration.getRepresentativeIdentifier()))) {
      out.add(
          BgDeclarationFileFinding.error(
              REPRESENTATIVE_NOT_ALLOWED,
              "Част ІІ, т. 7: попълнен представляващ при подател, който НЕ е юридическо лице (код „"
                  + nz(codeType)
                  + "“)."));
    }
    if (isBlank(declaration.getContactPhone()) && isBlank(declaration.getContactEmail())) {
      out.add(
          BgDeclarationFileFinding.warning(
              CONTACT_MISSING, "Част ІІ, т. 4: няма нито телефон, нито e-mail за контакт."));
    }
  }

  /** Част ІV, колона 3 — трите реда. */
  private static void validateAmounts(
      BgArt55Declaration declaration, List<BgDeclarationFileFinding> out) {

    checkAmount(declaration.getRow1AdvanceTax(), "ред 1", out);
    checkAmount(declaration.getRow2DividendTax(), "ред 2", out);
    checkAmount(declaration.getRow3OtherFinalTax(), "ред 3", out);

    // ⚠️ ПРЕДУПРЕЖДЕНИЕ, А НЕ ГРЕШКА: нулева декларация СЪЩЕСТВУВА и се подава. Но нула на трите
    // реда изглежда еднакво при „няма удържан данък“ и при „генерирането не е сработило“ — и точно
    // тогава човек натиска пак, вместо да провери регистъра.
    if (declaration.getRow1AdvanceTax().signum() == 0
        && declaration.getRow2DividendTax().signum() == 0
        && declaration.getRow3OtherFinalTax().signum() == 0) {
      out.add(
          BgDeclarationFileFinding.warning(
              ALL_ROWS_ZERO,
              "И трите реда на Част ІV са нула. Ако това е вярно — подай; ако не, провери регистъра"
                  + " и границите на изведеното (за ІV тримесечие виж чл. 65, ал. 13)."));
    }
  }

  private static void checkAmount(BigDecimal value, String at, List<BgDeclarationFileFinding> out) {
    if (value.signum() < 0) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_NEGATIVE,
              "Част ІV, " + at + ", к. 3 е отрицателна — образецът не познава отрицателен данък."));
    }
    if (value.stripTrailingZeros().scale() > AMOUNT_SCALE_LIMIT) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_SCALE, "Част ІV, " + at + ", к. 3 има повече от два десетични знака."));
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
