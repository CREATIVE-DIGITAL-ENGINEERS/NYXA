package com.axelor.apps.bglocalization.service.vat.adapter;

import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ИДЕНТИФИКАЦИОННИЯТ НОМЕР НА КОНТРАГЕНТА В ДНЕВНИЦИТЕ (02-07 / 03-08) И ДДС НОМЕРЪТ ЗА VIES — от
 * едни и същи полета на партньора. Чиста функция.
 *
 * <p>ППЗДДС Прил. № 12, общите изисквания (изм. и доп. ДВ бр. 59/2022, в сила от 01.08.2022):
 *
 * <blockquote>
 *
 * „Полетата "Идентификационен номер на контрагента (доставчик)" и "Идентификационен номер на
 * контрагента (получател)" съдържат идентификационния номер по ДДС на контрагента, когато същият е
 * регистриран за целите на ДДС (в България или в държава членка), и идентификационния номер на
 * контрагента по смисъла на § 1 от допълнителната разпоредба на ППЗДДС, когато контрагентът не е
 * регистрирано по закона лице.
 *
 * <p>Полетата задължително се попълват с код "999999999999999" в случаите, когато контрагентът е
 * чуждестранно лице (физическо или юридическо), което няма регистрация по Данъчно-осигурителния
 * процесуален кодекс и ЗДДС или няма ДДС номер, с който лицето е регистрирано за целите на ДДС в
 * друга държава членка, или е данъчно незадължено физическо лице, в случаите, когато доставчикът
 * няма задължение да посочва в издадения от него данъчен документ идентификационния номер на това
 * лице.“
 *
 * </blockquote>
 *
 * <p>Редът на правилата:
 *
 * <ol>
 *   <li><b>ДДС номер от България или държава членка</b> ({@link
 *       BgVatNumberValidator#isViesCountry}) — той, и в дневника, и за VIES. Номер без буквен
 *       префикс се чете като български (както в {@link BgVatNumberValidator#routeOf}) и получава
 *       „BG“.
 *   <li><b>ИН по § 1 т. 1 ДР ППЗДДС</b> — ЕИК (б. „а“), БУЛСТАТ (б. „б“), служебният номер от
 *       регистъра по чл. 94 ал. 1 ЗДДС (б. „в“) или служебният номер по чл. 84 ал. 3 ДОПК (б. „г“).
 *       От {@code Partner.registrationCode} според {@code bgIdentifierType}: „2“ БУЛСТАТ; „1“ —
 *       само служебен номер на НАП (започва с {@value #NRA_SERVICE_PREFIX}, както в SAF-T), не
 *       ЛН/ЛНЧ; без тип — 307…, ЕИК на българско юридическо лице или валиден ЕИК (БУЛСТАТ) на
 *       физическо.
 *   <li><b>„{@value #NO_ID}“</b> — чуждестранно лице без такава регистрация и без ДДС номер в ДЧ, и
 *       физическо лице без ИН по § 1. ⚠️ <b>ЕГН никога</b>: то не е сред буквите на § 1 т. 1.
 *       Десетцифрен код (ЕГН/ЛНЧ) не става ИН, освен служебен номер 307…: ЕИК е 9 или 13 цифри, а
 *       от 04.01.2022 и БУЛСТАТ на физическо лице е 9-цифрен — старите 10-цифрени (= ЕГН) са
 *       преобразувани служебно (съобщението на НАП; ⚠️ да се потвърди срещу консолидирания текст:
 *       чл. 6 ал. 1 ЗРБ и § 9 ПЗР, ДВ бр. 94/2020).
 * </ol>
 *
 * <p>Българско юридическо лице без ДДС номер и без ЕИК е ЛИПСВАЩИ ДАННИ: ИН е празен и валидаторът
 * предупреждава. Не е „{@value #NO_ID}“, защото такова лице има ЕИК.
 *
 * <p>⚠️ Да се потвърди: номер по чл. 168ж ал. 11 / чл. 168к ал. 5 ЗДДС (режим за малки предприятия
 * в ЕС, „-EX“) — ППЗДДС до ДВ бр. 54/2024 не урежда какво се пише. Тук се чете като ДДС номер на
 * държавата си.
 */
public final class BgVatCounterpartyId {

  /** Кодът за контрагент без ИН по Прил. № 12. */
  public static final String NO_ID = "999999999999999";

  /** Началото на служебния номер от регистъра на НАП (както в SAF-T — MF.C.3, т. 7). */
  public static final String NRA_SERVICE_PREFIX = "307";

  private static final String BG = "BG";

  /** {@code PartnerRepository.PARTNER_TYPE_INDIVIDUAL}. */
  private static final int INDIVIDUAL = 2;

  private static final String ID_SERVICE_NUMBER = "1";
  private static final String ID_BULSTAT = "2";

  /** ЕГН, ЛН/ЛНЧ, чуждестранно лице без идентификатор — нито един не е ИН по § 1 т. 1. */
  private static final Set<String> NOT_AN_ID =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("0", "1", "3")));

  private final String diaryId;
  private final String vatNumber;

  private BgVatCounterpartyId(String diaryId, String vatNumber) {
    this.diaryId = diaryId;
    this.vatNumber = vatNumber;
  }

  /** За 02-07 / 03-08; {@code null}, когато данните на българско юридическо лице липсват. */
  public String getDiaryId() {
    return diaryId;
  }

  /** ДДС номерът (България или ДЧ) — за VIES; {@code null}, когато контрагентът няма такъв. */
  public String getVatNumber() {
    return vatNumber;
  }

  /**
   * @param partnerTypeSelect 1 юридическо, 2 физическо лице
   * @param identifierType {@code Partner.bgIdentifierType}: 0 ЕГН, 1 ЛН/ЛНЧ/служебен №, 2 БУЛСТАТ,
   *     3 чуждестранно лице без идентификатор
   * @param registrationCode {@code Partner.registrationCode}
   * @param taxNbr ДДС номерът — от фактурата ({@code Invoice.partnerTaxNbr}), иначе от партньора
   * @param country ISO2 на държавата от основния адрес
   */
  public static BgVatCounterpartyId of(
      Integer partnerTypeSelect,
      String identifierType,
      String registrationCode,
      String taxNbr,
      String country) {
    String vat = vatNumber(taxNbr);
    if (vat != null) {
      return new BgVatCounterpartyId(vat, vat);
    }
    String code = trim(registrationCode);
    String type = trim(identifierType);
    // „BG“ пред цифрите на регистрационния номер е грешно попълнено поле (както в SAF-T).
    if (code != null
        && code.length() > 2
        && code.regionMatches(true, 0, BG, 0, 2)
        && Character.isDigit(code.charAt(2))) {
      code = code.substring(2);
    }
    if (code != null && isServiceNumber(code, type)) {
      return new BgVatCounterpartyId(code, null);
    }
    if (code != null && code.matches("\\d{10}")) {
      // Десет цифри е ЕГН или ЛНЧ — ЕИК и БУЛСТАТ са 9 или 13. Никога в дневника.
      code = null;
    }
    if (code != null && ID_BULSTAT.equals(type)) {
      return new BgVatCounterpartyId(code, null);
    }
    String iso = trim(country) == null ? null : country.trim().toUpperCase(Locale.ROOT);
    boolean foreign = iso != null && !BG.equals(iso);
    if (foreign || NOT_AN_ID.contains(type)) {
      // Чуждестранно лице без регистрация — и ЕГН, ЛН/ЛНЧ или „без идентификатор“ при всяко лице:
      // никое от тях не е ИН по § 1 т. 1.
      return new BgVatCounterpartyId(NO_ID, null);
    }
    if (partnerTypeSelect != null && partnerTypeSelect == INDIVIDUAL) {
      // Физическо лице без признак: валиден ЕИК е БУЛСТАТ (както в SAF-T); иначе няма ИН по § 1.
      return new BgVatCounterpartyId(
          code != null && BgIdentifierValidator.isValidEik(code) ? code : NO_ID, null);
    }
    // Българско юридическо лице: ЕИК (или БУЛСТАТ); без него — липсващи данни.
    return new BgVatCounterpartyId(code, null);
  }

  /**
   * Служебен номер от регистъра на НАП (б. „в“ и „г“): започва с {@value #NRA_SERVICE_PREFIX}, без
   * признак или с „1“ (както в SAF-T). ЕГН не може да започва с 307: третата му цифра е първата на
   * месеца (0–5).
   */
  private static boolean isServiceNumber(String code, String type) {
    return code.startsWith(NRA_SERVICE_PREFIX) && (type == null || ID_SERVICE_NUMBER.equals(type));
  }

  /**
   * „BG“ + 10 цифри, които минават проверката за ЕГН (контролна цифра и валидна дата) — вероятно
   * ЕГН или стар ДДС номер на физическо лице. От 01.01.2024 ИН на физическо лице с ЕГН или ЛНЧ,
   * което не е в търговския регистър или БУЛСТАТ, е служебният номер от регистъра по чл. 94 ал. 1
   * ЗДДС (§ 1 т. 1 б. „в“ ДР ППЗДДС, изм. ДВ бр. 55/2023). ДДС номерът му е „BG“ + този номер, не
   * „BG“ + ЕГН/ЛНЧ. Само признак за проверка: служебен номер може случайно да мине проверката за
   * ЕГН.
   */
  public static boolean looksLikeEgn(String vatNumber) {
    String value = BgVatNumberValidator.normalize(vatNumber);
    return value.matches("BG\\d{10}") && BgIdentifierValidator.isValidEgn(value.substring(2));
  }

  /** ДДС номер от България или държава членка — нормализиран; иначе {@code null}. */
  private static String vatNumber(String taxNbr) {
    String value = BgVatNumberValidator.normalize(taxNbr);
    if (value == null || value.isEmpty()) {
      return null;
    }
    String prefix = BgVatNumberValidator.countryCode(value);
    if (prefix == null) {
      // Без буквен префикс: чете се като български номер, ако е от цифри.
      return value.matches("\\d+") ? BG + value : null;
    }
    return BgVatNumberValidator.isViesCountry(prefix) ? value : null;
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
