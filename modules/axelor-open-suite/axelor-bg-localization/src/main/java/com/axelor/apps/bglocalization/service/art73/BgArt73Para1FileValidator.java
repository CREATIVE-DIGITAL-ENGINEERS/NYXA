package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ВРАТАТА ПРЕДИ ФАЙЛА ПО ЧЛ. 73 АЛ. 1 — правилата на УКАЗАНИЯТА, а не на формата.
 *
 * <p><b>⚠️ ТОВА, КОЕТО ТУК СЕ ПРОВЕРЯВА, НЕ ЗАВИСИ ОТ ЗАПОВЕДТА.</b> Кои колони се попълват, кои се
 * изключват взаимно и кои мълчат за сборните кодове е писано в указанията ПОД образеца и важи
 * независимо дали записът е с фиксирана ширина или със запетаи. Затова валидаторът е готов и
 * проверим ДНЕС, при липсващ формат — той е и единственото, което може да бъде верифицирано преди
 * заповедта.
 *
 * <p><b>⚠️ ГРЕШКАТА БЛОКИРА ПРОИЗВЕЖДАНЕТО, А НЕ САМО ОЦВЕТЯВА ЕКРАНА.</b> Указание т. 6, дословно:
 * „Справка, която не съдържа всички изискуеми данни, НЕ СЕ СЧИТА ЗА ДОСТОВЕРНА И НЕ СЕ ПРИЕМА!“ —
 * тоест произведен въпреки грешката, файлът пътува до портала само за да бъде отхвърлен там, а
 * подателят научава за това след срока.
 *
 * <p>Чист клас: без Guice и без база.
 */
public final class BgArt73Para1FileValidator {

  public static final String NO_ROWS = "ART73_NO_ROWS";
  public static final String PAYER_IDENTIFIER_MISSING = "ART73_PAYER_IDENTIFIER_MISSING";
  public static final String PAYER_NAME_MISSING = "ART73_PAYER_NAME_MISSING";
  public static final String CONTACT_MISSING = "ART73_CONTACT_MISSING";
  public static final String INCOME_CODE_MISSING = "ART73_INCOME_CODE_MISSING";
  public static final String CORRECTION_CODE_INVALID = "ART73_CORRECTION_CODE_INVALID";
  public static final String IDENTIFIER_TYPE_INVALID = "ART73_IDENTIFIER_TYPE_INVALID";
  public static final String IDENTIFIER_MISSING = "ART73_IDENTIFIER_MISSING";
  public static final String IDENTIFIER_ON_TYPE_3 = "ART73_IDENTIFIER_ON_TYPE_3";
  public static final String BIRTH_DATE_MISSING = "ART73_BIRTH_DATE_MISSING";
  public static final String BIRTH_DATE_NOT_ALLOWED = "ART73_BIRTH_DATE_NOT_ALLOWED";
  public static final String LAST_NAME_MISSING = "ART73_LAST_NAME_MISSING";
  public static final String COUNTRY_CODE_MISSING = "ART73_COUNTRY_CODE_MISSING";
  public static final String COUNTRY_CODE_NOT_ALLOWED = "ART73_COUNTRY_CODE_NOT_ALLOWED";
  public static final String AGGREGATE_COLUMNS_FILLED = "ART73_AGGREGATE_COLUMNS_FILLED";
  public static final String PERSON_COUNT_MISSING = "ART73_PERSON_COUNT_MISSING";
  public static final String PERSON_COUNT_NOT_ALLOWED = "ART73_PERSON_COUNT_NOT_ALLOWED";
  public static final String PREDECESSOR_NOT_ALLOWED = "ART73_PREDECESSOR_NOT_ALLOWED";
  public static final String DELETION_CARRIES_AMOUNTS = "ART73_DELETION_CARRIES_AMOUNTS";
  public static final String AMOUNT_NEGATIVE = "ART73_AMOUNT_NEGATIVE";
  public static final String AMOUNT_SCALE = "ART73_AMOUNT_SCALE";
  public static final String DUPLICATE_ROW = "ART73_DUPLICATE_ROW";
  public static final String PAYER_EIK_LENGTH = "ART73_PAYER_EIK_LENGTH";
  public static final String PAYER_EIK_INVALID = "ART73_PAYER_EIK_INVALID";
  public static final String IDENTIFIER_INVALID = "ART73_IDENTIFIER_INVALID";

  /** Кодовете по колона 1 — указание за попълване т. 2. */
  private static final Set<String> CORRECTION_CODES = new HashSet<>(Arrays.asList("0", "1", "8"));

  /** Кодовете по колона 3 — указание за попълване т. 4. */
  private static final Set<String> IDENTIFIER_TYPES =
      new HashSet<>(Arrays.asList("0", "1", "2", "3"));

  /** Тип „3“ — чуждестранно физическо лице без идентификатор. */
  private static final String ID_TYPE_NO_IDENTIFIER = "3";

  /** Тип „0“ — ЕГН; носи контролна цифра. */
  private static final String ID_TYPE_EGN = "0";

  /** Тип „2“ — физическо лице с БУЛСТАТ; носи контролна цифра. */
  private static final String ID_TYPE_BULSTAT = "2";

  /** Указание за попълване т. 10 — „в левове и стотинки до втория десетичен знак“. */
  private static final int AMOUNT_SCALE_LIMIT = 2;

  private BgArt73Para1FileValidator() {}

  /** Всички находки за справката — заглавната част и всеки ред поотделно. */
  public static List<BgDeclarationFileFinding> validate(BgArt73Para1FileContent content) {
    List<BgDeclarationFileFinding> out = new ArrayList<>();
    if (content == null) {
      out.add(BgDeclarationFileFinding.error(NO_ROWS, "Няма справка за проверка."));
      return out;
    }
    validateHeader(content, out);

    // ⚠️ ОТКАЗ, А НЕ ПРАЗЕН ФАЙЛ — същият довод като при Д1 ({@code BgD1SubmissionPlan}): файл с
    // нула реда е ВАЛИДЕН файл и НАП го чете като изявление „през годината не са изплащани доходи“.
    // Мълчаливо подаден, той не изглежда като грешка.
    if (content.getRows().isEmpty()) {
      out.add(
          BgDeclarationFileFinding.error(
              NO_ROWS,
              "Справката няма нито един ред в Част ІІ. ⚠️ Файл с НУЛА реда е валиден файл и НАП го"
                  + " чете като „през годината не са изплащани доходи“ — генерирай редовете"
                  + " първо."));
    }

    Set<String> seen = new HashSet<>();
    for (int i = 0; i < content.getRows().size(); i++) {
      validateRow(content.getRows().get(i), i + 1, seen, out);
    }
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

  /** Част І — идентификационните данни на платеца. */
  private static void validateHeader(
      BgArt73Para1FileContent content, List<BgDeclarationFileFinding> out) {
    String eik = trimmed(content.getPayerIdentifier());
    if (eik == null) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_IDENTIFIER_MISSING,
              "Част І, т. 1: липсва ЕИК/Служебен № на платеца — без него справката не се приема"
                  + " (указание т. 6)."));
    } else {
      // ⚠️ САМО 9 и 10 ЗНАКА. Заповедта, раздел ІІ т. 2, дословно: „Справката се подава само от 9 и
      // 10-значни ЕИК. Не се допуска подаването на данни от 13-значни ЕИК.“ Това е ПО-ТЯСНО от
      // общата проверка на ЕИК в модула, която приема и 13-значния клонов номер — тоест напълно
      // валиден ЕИК тук е невалиден ВХОД, и разликата се вижда само ако е написана.
      if (eik.length() < 9 || eik.length() > 10) {
        out.add(
            BgDeclarationFileFinding.error(
                PAYER_EIK_LENGTH,
                "Част І, т. 1: ЕИК „"
                    + eik
                    + "“ е "
                    + eik.length()
                    + " знака — заповедта приема САМО 9- и 10-значни (13-значните се отхвърлят)."));
      } else if (!BgIdentifierValidator.isValidEik(eik)) {
        out.add(
            BgDeclarationFileFinding.error(
                PAYER_EIK_INVALID, "Част І, т. 1: ЕИК „" + eik + "“ не минава контролната цифра."));
      }
    }
    if (isBlank(content.getPayerName())) {
      out.add(
          BgDeclarationFileFinding.error(
              PAYER_NAME_MISSING, "Част І, т. 2: липсва наименование/име на платеца."));
    }
    // ⚠️ ПРЕДУПРЕЖДЕНИЕ, А НЕ ГРЕШКА: образецът иска „За контакт“, но указанията НЕ изброяват
    // телефона и e-mail-а сред изискуемите данни. Обявени за грешка, те биха блокирали справка,
    // която НАП приема — тоест щяхме да сме по-строги от органа и да спрем подаване в срок.
    if (isBlank(content.getContactPhone()) && isBlank(content.getContactEmail())) {
      out.add(
          BgDeclarationFileFinding.warning(
              CONTACT_MISSING,
              "Част І, т. 3: няма нито телефон, нито e-mail за контакт. Попълни поне единия — по"
                  + " тях НАП търси подателя при въпрос по справката."));
    }
  }

  /** Един ред от Част ІІ — колони 1 до 15. */
  private static void validateRow(
      BgArt73Para1FileRow row, int number, Set<String> seen, List<BgDeclarationFileFinding> out) {

    String at = "Ред " + number + ": ";

    if (isBlank(row.getIncomeCode())) {
      out.add(
          BgDeclarationFileFinding.error(
              INCOME_CODE_MISSING, at + "колона 10 е празна — редът няма код на дохода."));
    }
    if (!CORRECTION_CODES.contains(row.getCorrectionCode())) {
      out.add(
          BgDeclarationFileFinding.error(
              CORRECTION_CODE_INVALID,
              at
                  + "колона 1 е „"
                  + nz(row.getCorrectionCode())
                  + "“ — указание т. 2 познава само „0“, „1“ и „8“."));
    }

    if (row.isAggregateRow()) {
      validateAggregateRow(row, at, out);
    } else {
      validateIdentityColumns(row, at, out);
    }
    validatePersonCount(row, at, out);
    validateAmounts(row, at, out);

    // ⚠️ ДУБЛИРАНЕТО Е ГРЕШКА, А НЕ ОСОБЕНОСТ: указание за попълване т. 7 казва, че РАЗЛИЧНИТЕ
    // кодове отиват на отделни редове — обратното значи, че един код за едно лице стои на ЕДИН ред.
    // Два реда със същата тройка биха удвоили дохода пред НАП, а екранът ги показва като два
    // напълно редовни реда.
    String key = key(row);
    if (!seen.add(key)) {
      out.add(
          BgDeclarationFileFinding.error(
              DUPLICATE_ROW,
              at
                  + "същото лице и същият код на дохода вече са на друг ред. Заповедта, т. 9–12:"
                  + " най-много ЕДИН ред на (лице, код) във файл — не се допускат нито два основни,"
                  + " нито смесени основни/коригиращи/заличаващи. Изключение е само преобразуването"
                  + " (т. 13), различено по колона 15."));
    }
  }

  /** Сборните кодове — колони 2–9 и 15 мълчат (указание за попълване т. 5 и т. 8). */
  private static void validateAggregateRow(
      BgArt73Para1FileRow row, String at, List<BgDeclarationFileFinding> out) {
    boolean filled =
        row.isForeignPerson()
            || !isBlank(row.getIdentifierType())
            || !isBlank(row.getIdentifier())
            || row.getBirthDate() != null
            || !isBlank(row.getFirstName())
            || !isBlank(row.getMiddleName())
            || !isBlank(row.getLastName())
            || !isBlank(row.getCountryCode());
    if (filled) {
      out.add(
          BgDeclarationFileFinding.error(
              AGGREGATE_COLUMNS_FILLED,
              at
                  + "кодът е СБОРЕН, а колони 2–9 са попълнени — указание т. 5 ги оставя празни за"
                  + " тези кодове."));
    }
    if (!isBlank(row.getPredecessorIdentifier())) {
      out.add(
          BgDeclarationFileFinding.error(
              PREDECESSOR_NOT_ALLOWED,
              at + "кодът е СБОРЕН, а колона 15 е попълнена — указание т. 8 я оставя празна."));
    }
  }

  /** Колони 3–9 за поименните редове. */
  private static void validateIdentityColumns(
      BgArt73Para1FileRow row, String at, List<BgDeclarationFileFinding> out) {

    if (!IDENTIFIER_TYPES.contains(row.getIdentifierType())) {
      out.add(
          BgDeclarationFileFinding.error(
              IDENTIFIER_TYPE_INVALID,
              at
                  + "колона 3 е „"
                  + nz(row.getIdentifierType())
                  + "“ — указание т. 4 познава само „0“, „1“, „2“ и „3“."));
    }

    // ⚠️ КОЛОНИ 4 И 5 СА ВЗАИМНОИЗКЛЮЧВАЩИ СЕ, И ТО В ДВЕТЕ ПОСОКИ. Указание т. 4, дословно: при
    // тип „3“ „колона 4 НЕ се попълва, а се попълва датата на раждане в колона 5“. Попълнени и
    // двете, редът твърди, че лице без идентификатор има идентификатор.
    boolean noIdentifier = ID_TYPE_NO_IDENTIFIER.equals(row.getIdentifierType());
    if (noIdentifier) {
      if (!isBlank(row.getIdentifier())) {
        out.add(
            BgDeclarationFileFinding.error(
                IDENTIFIER_ON_TYPE_3,
                at
                    + "тип идентификатор „3“, а колона 4 е попълнена — указание т. 4 я оставя"
                    + " празна."));
      }
      if (row.getBirthDate() == null) {
        out.add(
            BgDeclarationFileFinding.error(
                BIRTH_DATE_MISSING,
                at + "тип идентификатор „3“ без дата на раждане в колона 5 (указание т. 4)."));
      }
    } else {
      if (isBlank(row.getIdentifier())) {
        out.add(
            BgDeclarationFileFinding.error(
                IDENTIFIER_MISSING, at + "колона 4 е празна за тип идентификатор, който я иска."));
      } else {
        checkIdentifier(row, at, out);
      }
      if (row.getBirthDate() != null) {
        out.add(
            BgDeclarationFileFinding.error(
                BIRTH_DATE_NOT_ALLOWED,
                at
                    + "колона 5 е попълнена при тип идентификатор „"
                    + nz(row.getIdentifierType())
                    + "“ — датата на раждане е САМО за тип „3“."));
      }
    }

    if (isBlank(row.getLastName())) {
      out.add(
          BgDeclarationFileFinding.error(
              LAST_NAME_MISSING,
              at + "колона 8 е празна — фамилното име се попълва на всеки ред."));
    }

    // ⚠️ И В ДВЕТЕ ПОСОКИ: указание т. 6 казва „САМО за чуждестранни физически лица“. Попълнена за
    // местно лице, колоната твърди чуждо гражданство; празна за чуждестранно — липсва изискуем
    // данък.
    if (row.isForeignPerson() && isBlank(row.getCountryCode())) {
      out.add(
          BgDeclarationFileFinding.error(
              COUNTRY_CODE_MISSING,
              at + "лицето е чуждестранно, а колона 9 е празна (указание т. 6)."));
    }
    if (!row.isForeignPerson() && !isBlank(row.getCountryCode())) {
      out.add(
          BgDeclarationFileFinding.error(
              COUNTRY_CODE_NOT_ALLOWED,
              at
                  + "колона 9 е попълнена за МЕСТНО лице — указание т. 6 я иска само за"
                  + " чуждестранни."));
    }
  }

  /** Колона 14 — указание за попълване т. 5. */
  private static void validatePersonCount(
      BgArt73Para1FileRow row, String at, List<BgDeclarationFileFinding> out) {
    if (!row.carriesAmounts()) {
      return;
    }
    if (row.isReportsPersonCount() && row.getPersonCount() == null) {
      out.add(
          BgDeclarationFileFinding.error(
              PERSON_COUNT_MISSING,
              at + "кодът иска колона 14 „Брой лица“, а тя е празна (указание т. 5)."));
    }
    if (!row.isReportsPersonCount() && row.getPersonCount() != null) {
      out.add(
          BgDeclarationFileFinding.error(
              PERSON_COUNT_NOT_ALLOWED,
              at
                  + "колона 14 е попълнена за код, който НЕ я иска — указание т. 5 я дава само на"
                  + " кодовете за брой лица."));
    }
  }

  /** Колони 11, 12 и 13 — знак и точност. */
  private static void validateAmounts(
      BgArt73Para1FileRow row, String at, List<BgDeclarationFileFinding> out) {

    if (!row.carriesAmounts()) {
      // ⚠️ Заличаващият ред НЕ носи суми — указание т. 2. Събирачът вече ги е оставил празни;
      // попълнени въпреки това, те идват от ръчна редакция и трябва да бъдат видени.
      if (nonZero(row.getGrossAmount())
          || nonZero(row.getInsuranceAmount())
          || nonZero(row.getTaxAmount())
          || row.getPersonCount() != null
          || !isBlank(row.getPredecessorIdentifier())) {
        out.add(
            BgDeclarationFileFinding.error(
                DELETION_CARRIES_AMOUNTS,
                at
                    + "заличаващ ред (колона 1 = „8“) носи суми — указание т. 2 попълва само колони"
                    + " 1, 2, 3, 4 или 5, 6, 7, 8 и 10."));
      }
      return;
    }
    checkAmount(row.getGrossAmount(), at, "колона 11", out);
    checkAmount(row.getInsuranceAmount(), at, "колона 12", out);
    checkAmount(row.getTaxAmount(), at, "колона 13", out);
  }

  private static void checkAmount(
      BigDecimal value, String at, String column, List<BgDeclarationFileFinding> out) {
    if (value == null) {
      return;
    }
    if (value.signum() < 0) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_NEGATIVE,
              at + column + " е отрицателна — образецът не познава отрицателни суми."));
    }
    if (value.stripTrailingZeros().scale() > AMOUNT_SCALE_LIMIT) {
      out.add(
          BgDeclarationFileFinding.error(
              AMOUNT_SCALE,
              at
                  + column
                  + " има повече от два десетични знака — указание т. 10 иска левове и стотинки."));
    }
  }

  /**
   * КЛЮЧЪТ НА ЕДИН РЕД — <b>БЕЗ кода корекция</b>.
   *
   * <p><b>⚠️ ЗАПОВЕДТА ЗАБРАНЯВА И СМЕСВАНЕТО, НЕ САМО ПОВТАРЯНЕТО.</b> Т. 9: не повече от един
   * запис с ОСНОВНИ данни за едно лице и код. Т. 10–12: не се допуска в ЕДИН файл основни и
   * коригиращи, основни и заличаващи, или коригиращи и заличаващи за същото лице и код. Сборът им е
   * едно правило — НАЙ-МНОГО ЕДИН ред на (лице, код) във файл — и затова кодът корекция е ИЗВАН
   * ключа. Вътре в него всяка от четирите забрани трябваше да се пише поотделно, а смесването
   * основни+коригиращи щеше да мине.
   *
   * <p>⚠️ ЕИК НА ПРЕОБРАЗУВАЩОТО СЕ ПРЕДПРИЯТИЕ Е ЧАСТ ОТ КЛЮЧА — т. 13 е изричното изключение:
   * когато и правоприемникът, и преобразуващото се дружество са платили на едно лице по един код,
   * данните отиват на ОТДЕЛНИ редове, различени точно по колона 15.
   *
   * <p>⚠️ За сборните кодове ключът е САМО кодът — т. 6: ключовите полета там са ЕИК на платеца и
   * код на дохода, защото ред „на лице“ не съществува.
   */
  private static String key(BgArt73Para1FileRow row) {
    String person =
        row.isAggregateRow()
            ? "*"
            : nz(row.getIdentifier())
                + "|"
                + (row.getBirthDate() == null ? "" : row.getBirthDate())
                + "|"
                + nz(row.getFirstName())
                + "|"
                + nz(row.getLastName());
    return person + "#" + nz(row.getIncomeCode()) + "#" + nz(row.getPredecessorIdentifier());
  }

  /**
   * „ЗАДЪЛЖИТЕЛНА ВАЛИДАЦИЯ НА ИДЕНТИФИКАТОРА“ — заповедта, поле {@code ident}.
   *
   * <p><b>⚠️ САМО ТАМ, КЪДЕТО ИМА КАКВО ДА СЕ СВЕРИ.</b> Тип „0“ е ЕГН и „2“ е БУЛСТАТ — и двата
   * носят контролна цифра, тоест сгрешеният се хваща тук. Тип „1“ обаче обединява ЛН, ЛНЧ И
   * „Служебен № от регистъра на НАП“; служебният номер се раздава от НАП и НЕ следва алгоритъма на
   * ЕГН. Проверен с него, всеки законен служебен номер щеше да пада — тоест валидаторът щеше да
   * спира точно чуждестранните лица, заради които типът съществува. Затова там се сверява само
   * формата (дължина и цифри), а верността остава за приемащата система.
   */
  private static void checkIdentifier(
      BgArt73Para1FileRow row, String at, List<BgDeclarationFileFinding> out) {

    String ident = row.getIdentifier().trim();
    String type = row.getIdentifierType();
    boolean valid;
    if (ID_TYPE_EGN.equals(type)) {
      valid = BgIdentifierValidator.isValidEgn(ident);
    } else if (ID_TYPE_BULSTAT.equals(type)) {
      valid = BgIdentifierValidator.isValidEik(ident);
    } else {
      valid =
          ident.length() >= 9 && ident.length() <= 10 && ident.chars().allMatch(Character::isDigit);
    }
    if (!valid) {
      out.add(
          BgDeclarationFileFinding.error(
              IDENTIFIER_INVALID,
              at
                  + "колона 4 „"
                  + ident
                  + "“ не минава валидацията за тип идентификатор „"
                  + nz(type)
                  + "“ (заповедта иска задължителна валидация)."));
    }
  }

  private static String trimmed(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static boolean nonZero(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
