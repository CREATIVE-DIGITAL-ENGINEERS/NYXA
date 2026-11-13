package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileAnnual;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * КОНТРОЛИТЕ ОТ ЗАПОВЕДТА ЗА АЛ. 6 — ТОВА, КОЕТО СХЕМАТА НЕ МОЖЕ ДА ПАЗИ.
 *
 * <p><b>⚠️ {@code dec736} Е ОЩЕ ПО-СЛАБ ПАЗАЧ ОТ {@code dec731}.</b> Там почти всеки елемент на
 * реда е {@code minOccurs="0"}; тук дори целият {@code incomedata} е незадължителен. Файл, който
 * НАП ще отхвърли, минава схемата без нито една забележка. Задължителността ЗА ПРИЕМ и забраната за
 * отрицателни живеят в „Ред и формат“ (заповед № З-ЦУ-1877/05.12.2019 г.) и се пазят ТУК.
 *
 * <p><b>⚠️ ПРИЕМЪТ Е ВСИЧКО-ИЛИ-НИЩО.</b> Заповедта, т. 3: файл с липсващо задължително или
 * некоректно попълнено поле се отхвърля ЦЕЛИЯТ — не редът, файлът. Затова всяка находка тук е
 * БЛОКИРАЩА: „предупреждение“ би подсказало, че част от нарушенията са поносими.
 *
 * <p><b>⚠️ ОТРИЦАТЕЛНИ НЕ СЕ ПРИЕМАТ НИКЪДЕ ОСВЕН В {@code diff18}.</b> Заповедта, изисквания т. 6,
 * дословно: „не се допуска прием на отрицателни стойности с изключение на поле {@code diff18}“.
 * Изключението е логично — {@code diff18} е РАЗЛИКА и знакът ѝ казва дали се доудържа, или се
 * възстановява. Обърнато правило (да се блокира и там) би направило невъзможно възстановяването по
 * чл. 49 ал. 6.
 *
 * <p>Чист клас: без Guice и без хранилища, тоест проверим без Postgres.
 */
public final class BgArt73Para6FileValidator {

  public static final String NO_ROWS = "ART73P6_NO_ROWS";
  public static final String PAYER_IDENTIFIER_MISSING = "ART73P6_PAYER_IDENTIFIER_MISSING";
  public static final String PAYER_NAME_MISSING = "ART73P6_PAYER_NAME_MISSING";
  public static final String PAYER_EIK_LENGTH = "ART73P6_PAYER_EIK_LENGTH";
  public static final String PAYER_EIK_INVALID = "ART73P6_PAYER_EIK_INVALID";
  public static final String CORRECTION_CODE_INVALID = "ART73P6_CORRECTION_CODE_INVALID";
  public static final String FIRST_NAME_MISSING = "ART73P6_FIRST_NAME_MISSING";
  public static final String LAST_NAME_MISSING = "ART73P6_LAST_NAME_MISSING";
  public static final String IDENTIFIER_TYPE_INVALID = "ART73P6_IDENTIFIER_TYPE_INVALID";
  public static final String IDENTIFIER_MISSING = "ART73P6_IDENTIFIER_MISSING";
  public static final String IDENTIFIER_INVALID = "ART73P6_IDENTIFIER_INVALID";
  public static final String DUPLICATE_PERSON = "ART73P6_DUPLICATE_PERSON";
  public static final String MAIN_EMPLOYER_CONFLICT = "ART73P6_MAIN_EMPLOYER_CONFLICT";
  public static final String MAIN_EMPLOYER_WITHOUT_ANNUAL = "ART73P6_MAIN_EMPLOYER_WITHOUT_ANNUAL";
  public static final String ANNUAL_WITHOUT_MAIN_EMPLOYER = "ART73P6_ANNUAL_WITHOUT_MAIN_EMPLOYER";
  public static final String TAX_BASE_251_MISSING = "ART73P6_TAX_BASE_251_MISSING";
  public static final String TAX_BASE_253_MISSING = "ART73P6_TAX_BASE_253_MISSING";
  public static final String TOTAL_TAX_MISSING = "ART73P6_TOTAL_TAX_MISSING";
  public static final String INCOME_CODE_MISSING = "ART73P6_INCOME_CODE_MISSING";
  public static final String INCOME_CODE_LENGTH = "ART73P6_INCOME_CODE_LENGTH";
  public static final String EMPLOYER_EIK_MISSING = "ART73P6_EMPLOYER_EIK_MISSING";
  public static final String EMPLOYER_EIK_LENGTH = "ART73P6_EMPLOYER_EIK_LENGTH";
  public static final String EMPLOYER_EIK_INVALID = "ART73P6_EMPLOYER_EIK_INVALID";
  public static final String EMPLOYER_NAME_MISSING = "ART73P6_EMPLOYER_NAME_MISSING";
  public static final String INCOME_MISSING = "ART73P6_INCOME_MISSING";
  public static final String ADVANCE_TAX_MISSING = "ART73P6_ADVANCE_TAX_MISSING";
  public static final String PREDECESSOR_IS_PAYER = "ART73P6_PREDECESSOR_IS_PAYER";
  public static final String PREDECESSOR_EIK_LENGTH = "ART73P6_PREDECESSOR_EIK_LENGTH";
  public static final String AMOUNT_NEGATIVE = "ART73P6_AMOUNT_NEGATIVE";
  public static final String AMOUNT_SCALE = "ART73P6_AMOUNT_SCALE";

  /** Кодовете по колона 1. */
  private static final Set<String> CORRECTION_CODES = new HashSet<>(Arrays.asList("0", "1", "8"));

  /** ⚠️ САМО ДВЕ: „0“ ЕГН и „1“ ЛН/ЛНЧ/Сл. №. Ал. 1 познава и „2“, и „3“ — тук те падат. */
  private static final Set<String> IDENTIFIER_TYPES = new HashSet<>(Arrays.asList("0", "1"));

  private static final String ID_TYPE_EGN = "0";

  /** Кодът, чиято годишна основа се описва в {@code taxbase251} (чл. 25 ал. 1). */
  private static final String CODE_EMPLOYMENT = "101";

  /** Кодът, чиято годишна основа се описва в {@code taxbase253} (чл. 25 ал. 3, морско лице). */
  private static final String CODE_SEAFARER = "103";

  /** Схемата иска ТОЧНО три знака за кода на дохода. */
  private static final int INCOME_CODE_LENGTH_EXACT = 3;

  /** Сумите се записват с два знака; повече би се закръглило МЪЛЧАЛИВО при писането. */
  private static final int AMOUNT_SCALE_LIMIT = 2;

  private BgArt73Para6FileValidator() {}

  /** Всички находки за подаденото съдържание; празен списък значи „готово за файл“. */
  public static List<BgDeclarationFileFinding> validate(BgArt73Para6FileContent content) {
    List<BgDeclarationFileFinding> out = new ArrayList<>();
    if (content == null) {
      out.add(BgDeclarationFileFinding.error(NO_ROWS, "Няма справка за проверка."));
      return out;
    }
    validateHeader(content, out);

    if (content.getPersons().isEmpty()) {
      out.add(
          BgDeclarationFileFinding.error(
              NO_ROWS, "Справката няма нито едно лице — празен файл не се подава."));
    }

    Set<String> seen = new HashSet<>();
    int index = 0;
    for (BgArt73Para6FilePersonRow person : content.getPersons()) {
      index++;
      validatePerson(person, content.getPayerIdentifier(), "Лице " + index + ": ", out);
      // ⚠️ Т. 14: корекцията е за ЦЯЛАТА Част ІІ на лицето, тоест едно лице = един запис.
      String key = nz(person.getIdentifierType()) + "|" + nz(person.getIdentifier());
      if (!seen.add(key) && person.getIdentifier() != null) {
        out.add(
            BgDeclarationFileFinding.error(
                DUPLICATE_PERSON,
                "Лице "
                    + index
                    + ": идентификаторът „"
                    + person.getIdentifier()
                    + "“ вече е на друг запис. Т. 14: корекцията е за ЦЯЛАТА Част ІІ на лицето,"
                    + " тоест едно лице има точно един запис във файла."));
      }
    }
    return out;
  }

  /** Част І — идентификационните данни на работодателя, който подава. */
  private static void validateHeader(
      BgArt73Para6FileContent content, List<BgDeclarationFileFinding> out) {
    String eik = content.getPayerIdentifier();
    if (eik == null) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_IDENTIFIER_MISSING,
              "Част І, т. 1: липсва ЕИК/Служебен № на работодателя — без него справката не се"
                  + " приема."));
    } else {
      checkPayerEik(eik, "Част І, т. 1: ", PAYER_EIK_LENGTH, PAYER_EIK_INVALID, out);
    }
    if (content.getPayerName() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_NAME_MISSING, "Част І, т. 2: липсва наименование на работодателя."));
    }
  }

  /**
   * ЕИК — САМО 9 И 10 ЗНАКА.
   *
   * <p>⚠️ Заповедта отказва 13-значните (клоновите) номера. Това е ПО-ТЯСНО от общата проверка на
   * ЕИК в модула, която ги приема — тоест напълно валиден ЕИК тук е невалиден ВХОД, и разликата се
   * вижда само ако е написана.
   */
  private static void checkPayerEik(
      String eik,
      String at,
      String lengthCode,
      String invalidCode,
      List<BgDeclarationFileFinding> out) {
    if (eik.length() < 9 || eik.length() > 10) {
      out.add(
          BgDeclarationFileFinding.error(
              lengthCode,
              at
                  + "ЕИК „"
                  + eik
                  + "“ е "
                  + eik.length()
                  + " знака — заповедта приема САМО 9- и 10-значни (13-значните се отхвърлят)."));
    } else if (!BgIdentifierValidator.isValidEik(eik)) {
      out.add(
          BgDeclarationFileFinding.error(
              invalidCode, at + "ЕИК „" + eik + "“ не минава контролната цифра."));
    }
  }

  /** Един запис на лице — Част ІІ. */
  private static void validatePerson(
      BgArt73Para6FilePersonRow person,
      String payerEik,
      String at,
      List<BgDeclarationFileFinding> out) {

    if (person.getCorrectionCode() == null
        || !CORRECTION_CODES.contains(person.getCorrectionCode())) {
      out.add(
          BgDeclarationFileFinding.error(
              CORRECTION_CODE_INVALID,
              at + "кодът корекция трябва да е 0 (основни), 1 (коригиращи) или 8 (заличаващи)."));
    }
    if (person.getFirstName() == null) {
      out.add(BgDeclarationFileFinding.error(FIRST_NAME_MISSING, at + "липсва собствено име."));
    }
    if (person.getLastName() == null) {
      out.add(BgDeclarationFileFinding.error(LAST_NAME_MISSING, at + "липсва фамилно име."));
    }
    validateIdentifier(person, at, out);
    validateMainEmployer(person, at, out);

    if (!person.hasIncomeData()) {
      return;
    }
    int index = 0;
    boolean hasEmploymentCode = false;
    boolean hasSeafarerCode = false;
    for (BgArt73Para6FileIncomeRow row : person.getIncomeRows()) {
      index++;
      validateIncomeRow(row, payerEik, at + "ред " + index + ": ", out);
      hasEmploymentCode |= CODE_EMPLOYMENT.equals(row.getIncomeCode());
      hasSeafarerCode |= CODE_SEAFARER.equals(row.getIncomeCode());
    }

    if (person.getTotalTaxWithheld() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              TOTAL_TAX_MISSING,
              at
                  + "липсва общ размер на удържания данък. Полето е задължително за прием винаги,"
                  + " когато лицето има редове доход."));
    } else {
      checkAmount(person.getTotalTaxWithheld(), at + "общ удържан данък", false, out);
    }

    validateAnnualBases(person, hasEmploymentCode, hasSeafarerCode, at, out);
    if (person.getAnnual() != null) {
      validateAnnual(person.getAnnual(), at, out);
    }
  }

  /**
   * „ЗАДЪЛЖИТЕЛНА ВАЛИДАЦИЯ НА ИДЕНТИФИКАТОРА“ — но само там, където има какво да се свери.
   *
   * <p>⚠️ Тип „0“ е ЕГН и носи контролна цифра, тоест сгрешеният се хваща. Тип „1“ обаче обединява
   * ЛН, ЛНЧ И „Служебен № от регистъра на НАП“; служебният номер се раздава от НАП и НЕ следва
   * алгоритъма на ЕГН. Проверен с него, всеки законен служебен номер щеше да пада — тоест
   * валидаторът щеше да спира точно чуждестранните лица, заради които типът съществува.
   */
  private static void validateIdentifier(
      BgArt73Para6FilePersonRow person, String at, List<BgDeclarationFileFinding> out) {

    String type = person.getIdentifierType();
    if (type == null || !IDENTIFIER_TYPES.contains(type)) {
      out.add(
          BgDeclarationFileFinding.error(
              IDENTIFIER_TYPE_INVALID,
              at
                  + "типът идентификатор трябва да е 0 (ЕГН) или 1 (ЛН/ЛНЧ/Сл. № от регистъра на"
                  + " НАП). По трудово правоотношение лице без идентификатор няма."));
    }
    String ident = person.getIdentifier();
    if (ident == null) {
      out.add(BgDeclarationFileFinding.error(IDENTIFIER_MISSING, at + "липсва идентификатор."));
      return;
    }
    boolean valid;
    if (ID_TYPE_EGN.equals(type)) {
      valid = BgIdentifierValidator.isValidEgn(ident);
    } else {
      valid =
          ident.length() >= 9 && ident.length() <= 10 && ident.chars().allMatch(Character::isDigit);
    }
    if (!valid) {
      out.add(
          BgDeclarationFileFinding.error(
              IDENTIFIER_INVALID,
              at
                  + "идентификаторът „"
                  + ident
                  + "“ не минава валидацията за тип „"
                  + nz(type)
                  + "“ (заповедта иска задължителна валидация)."));
    }
  }

  /**
   * ОСНОВЕН РАБОТОДАТЕЛ ⟺ НАЛИЧЕН БЛОК ПО ЧЛ. 49 — правилото е ДВУПОСОЧНО.
   *
   * <p>⚠️ Проверени поотделно, двете половини щяха да пропускат точно обратните случаи: флаг без
   * блок дава справка, в която НАП чака изравняване и не го намира; блок без флаг дава изравняване,
   * за което никой не твърди, че има право да го прави.
   */
  private static void validateMainEmployer(
      BgArt73Para6FilePersonRow person, String at, List<BgDeclarationFileFinding> out) {

    if (person.isMainEmployerConflict()) {
      out.add(
          BgDeclarationFileFinding.error(
              MAIN_EMPLOYER_CONFLICT,
              at
                  + "записаните редове на лицето се разминават по „основен работодател“. Признакът"
                  + " е на ЛИЦЕТО във файла — избран мълчаливо, той решава вместо човека дали се"
                  + " подава годишно изравняване."));
    }
    boolean hasAnnual = person.getAnnual() != null;
    if (person.isMainEmployer() && !hasAnnual) {
      out.add(
          BgDeclarationFileFinding.error(
              MAIN_EMPLOYER_WITHOUT_ANNUAL,
              at
                  + "лицето е с „основен работодател“, а няма блок за годишно изравняване по чл. 49"
                  + " — при основно трудово правоотношение блокът е задължителен."));
    }
    if (!person.isMainEmployer() && hasAnnual) {
      out.add(
          BgDeclarationFileFinding.error(
              ANNUAL_WITHOUT_MAIN_EMPLOYER,
              at
                  + "лицето носи блок за годишно изравняване по чл. 49, без да е с „основен"
                  + " работодател“ — изравняване прави само работодателят по основното"
                  + " правоотношение."));
    }
  }

  /** {@code taxbase251}/{@code taxbase253} — условно задължителни при основен работодател. */
  private static void validateAnnualBases(
      BgArt73Para6FilePersonRow person,
      boolean hasEmploymentCode,
      boolean hasSeafarerCode,
      String at,
      List<BgDeclarationFileFinding> out) {

    if (!person.isMainEmployer()) {
      return;
    }
    if (hasEmploymentCode && person.getTaxBaseArt25Para1() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              TAX_BASE_251_MISSING,
              at
                  + "лицето има доход с код 101, а годишната основа по чл. 25 ал. 1 липсва —"
                  + " основният работодател я попълва задължително."));
    }
    if (hasSeafarerCode && person.getTaxBaseArt25Para3() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              TAX_BASE_253_MISSING,
              at
                  + "лицето има доход с код 103, а годишната основа по чл. 25 ал. 3 липсва —"
                  + " основният работодател я попълва задължително."));
    }
    checkAmount(person.getTaxBaseArt25Para1(), at + "основа по чл. 25 ал. 1", false, out);
    checkAmount(person.getTaxBaseArt25Para3(), at + "основа по чл. 25 ал. 3", false, out);
  }

  /** Един ред доход — {@code incomedata/incomerows/rowsenum}. */
  private static void validateIncomeRow(
      BgArt73Para6FileIncomeRow row,
      String payerEik,
      String at,
      List<BgDeclarationFileFinding> out) {

    String code = row.getIncomeCode();
    if (code == null) {
      out.add(BgDeclarationFileFinding.error(INCOME_CODE_MISSING, at + "липсва код на дохода."));
    } else if (code.length() != INCOME_CODE_LENGTH_EXACT) {
      out.add(
          BgDeclarationFileFinding.error(
              INCOME_CODE_LENGTH,
              at
                  + "кодът „"
                  + code
                  + "“ е "
                  + code.length()
                  + " знака — схемата иска ТОЧНО 3 (за разлика от ал. 1, където са 3–5)."));
    }
    String employer = row.getEmployerIdentifier();
    if (employer == null) {
      out.add(
          BgDeclarationFileFinding.error(
              EMPLOYER_EIK_MISSING, at + "липсва ЕИК на работодателя, изплатил дохода."));
    } else {
      checkPayerEik(employer, at + "работодател: ", EMPLOYER_EIK_LENGTH, EMPLOYER_EIK_INVALID, out);
    }
    if (row.getEmployerName() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              EMPLOYER_NAME_MISSING, at + "липсва наименование на работодателя."));
    }
    if (row.getIncome() == null) {
      out.add(BgDeclarationFileFinding.error(INCOME_MISSING, at + "липсва доход."));
    }
    if (row.getAdvanceTax() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              ADVANCE_TAX_MISSING,
              at + "липсва авансов данък по чл. 42 — полето е задължително за прием."));
    }
    validatePredecessor(row, payerEik, at, out);

    checkAmount(row.getIncome(), at + "доход", false, out);
    checkAmount(row.getInsuranceBg(), at + "ЗОВ по КСО/ЗЗО", false, out);
    checkAmount(row.getInsuranceForeign(), at + "ЗОВ в чужбина", false, out);
    checkAmount(row.getAdvanceTax(), at + "авансов данък", false, out);
  }

  /**
   * ЕИК на преобразуващото се предприятие.
   *
   * <p>⚠️ То НЕ МОЖЕ да е самият подател: колона 23 съществува, за да каже „този доход е платен от
   * ДРУГО, вече преобразувано дружество“. Равно на ЕИК-а на подателя, то твърди, че предприятието
   * се е преобразувало в себе си — и слива двата реда, които т. 16 изрично разделя.
   */
  private static void validatePredecessor(
      BgArt73Para6FileIncomeRow row,
      String payerEik,
      String at,
      List<BgDeclarationFileFinding> out) {

    String predecessor = row.getPredecessorIdentifier();
    if (predecessor == null) {
      return;
    }
    if (predecessor.length() < 9 || predecessor.length() > 10) {
      out.add(
          BgDeclarationFileFinding.error(
              PREDECESSOR_EIK_LENGTH,
              at
                  + "ЕИК на преобразуващото се предприятие „"
                  + predecessor
                  + "“ е "
                  + predecessor.length()
                  + " знака — приемат се само 9- и 10-значни."));
    }
    if (predecessor.equals(payerEik)) {
      out.add(
          BgDeclarationFileFinding.error(
              PREDECESSOR_IS_PAYER,
              at
                  + "ЕИК на преобразуващото се предприятие съвпада с ЕИК на подателя. Колона 23"
                  + " назовава ДРУГО дружество — по т. 16 тя е това, което разделя платеното от"
                  + " правоприемника от платеното от преобразуващото се."));
    }
  }

  /** Блокът по чл. 49 — всички суми без знак, освен разликата за изравняване. */
  private static void validateAnnual(
      BgArt73Para6FileAnnual annual, String at, List<BgDeclarationFileFinding> out) {

    checkAmount(annual.getTaxBase(), at + "чл. 49: годишна основа", false, out);
    checkAmount(annual.getReliefDisability(), at + "чл. 18: облекчение", false, out);
    checkAmount(
        annual.getReliefVoluntaryPension(), at + "чл. 19: доброволно осигуряване", false, out);
    checkAmount(
        annual.getReliefVoluntaryHealthLife(), at + "чл. 19: здравно и „Живот“", false, out);
    checkAmount(annual.getReliefServiceYears(), at + "чл. 20: осигурителен стаж", false, out);
    checkAmount(annual.getReliefDonation01(), at + "чл. 22: дарения код 01", false, out);
    checkAmount(annual.getReliefDonation02(), at + "чл. 22: дарения код 02", false, out);
    checkAmount(annual.getReliefDonation03(), at + "чл. 22: дарения код 03", false, out);
    checkAmount(annual.getReliefChildren(), at + "чл. 22в: деца", false, out);
    checkAmount(annual.getReliefDisabledChildren(), at + "чл. 22г: деца с увреждания", false, out);
    checkAmount(
        annual.getTaxBaseAfterReliefs(), at + "чл. 49 ал. 1: основа след намаленията", false, out);
    checkAmount(annual.getTax(), at + "чл. 49: дължим годишен данък", false, out);
    // ⚠️ ЕДИНСТВЕНОТО поле със знак — заповедта, изисквания т. 6.
    checkAmount(annual.getSettlement(), at + "разлика за изравняване", true, out);
    checkAmount(annual.getWithheldByJanuary(), at + "удържано до 31 януари", false, out);
    checkAmount(annual.getRefundedByJanuary(), at + "възстановено до 31 януари", false, out);
  }

  /**
   * Една сума — знак и брой дробни знаци.
   *
   * <p>⚠️ Дробните се проверяват, защото писачът закръгля до два знака. Непроверена, сума с три
   * дробни щеше да ТРЪГНЕ КЪМ НАП закръглена — тоест с число, каквото никой не е одобрил.
   *
   * @param signed приема ли полето отрицателна стойност — вярно САМО за {@code diff18}
   */
  private static void checkAmount(
      BigDecimal value, String what, boolean signed, List<BgDeclarationFileFinding> out) {
    if (value == null) {
      return;
    }
    if (!signed && value.signum() < 0) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_NEGATIVE,
              what
                  + ": отрицателна стойност ("
                  + value.toPlainString()
                  + "). Заповедта, изисквания т. 6: отрицателни се приемат САМО в полето за"
                  + " разликата за изравняване."));
    }
    if (value.scale() > AMOUNT_SCALE_LIMIT) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_SCALE,
              what
                  + ": "
                  + value.toPlainString()
                  + " носи повече от два дробни знака — записана във файла, тя би се закръглила"
                  + " мълчаливо."));
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
