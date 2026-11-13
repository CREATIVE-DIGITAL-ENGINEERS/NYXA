/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Идентификационен номер по ДДС (Partner.taxNbr) — нормализация, рутиране по префикс и
 * БЪЛГАРСКАТА контролна цифра. Чиста функция, без мрежа.
 */
package com.axelor.apps.bglocalization.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Идентификационният номер по ДДС на партньора ({@code Partner.taxNbr}).
 *
 * <p><b>Този клас отговаря на ЕДИН въпрос: „излиза ли контролната цифра на българския номер".</b>
 * Той НЕ отговаря на „регистрирано ли е лицето по ДДС" — това е правен статут, който се знае само
 * от регистъра, и на него отговаря VIES. Двата въпроса нарочно живеят на различни места: този тук е
 * мигновен, офлайн и верен и когато мрежата я няма; другият иска чужда услуга, която пада.
 *
 * <p><b>Рутирането е по префикса на САМИЯ номер, не по адреса на партньора.</b> Чуждестранен
 * доставчик носи чужд ДДС номер и има български адрес далеч по-рядко, отколкото обратното; гейт по
 * адреса би проверявал грешния номер с грешните правила.
 *
 * <p><b>Какво НЕ се проверява тук и защо — всяко от тези е решение, не пропуск:</b>
 *
 * <ul>
 *   <li><b>Чужд ДДС номер</b> — пропуска се. Структурен регекс за 27-те държави е 27 правила без
 *       нито един реален еталон в репото, а модулът няма mock-подпора, тоест никое от тях не би
 *       имало свидетел с числа. Присъдата за чуждите идва от VIES, който е ИЗТОЧНИКЪТ, а не негово
 *       копие.
 *   <li><b>Номер с окончание „-EX"</b> — пропуска се. По <b>чл. 168ж, ал. 11 ЗДДС</b> (нова, ДВ бр.
 *       115 от 2025 г., в сила от 01.01.2026 г.): „на лицето се издава идентификационен номер, пред
 *       който е поставен знакът „BG", а след него е поставено окончание „-EX"". Това е режимът за
 *       малки предприятия в ЕС и номерът се води в ОТДЕЛЕН регистър (ал. 12 препраща към чл. 94,
 *       ал. 4) — нито контролната цифра на ЕИК важи за него, нито VIES го познава. ⚠️ Сверено
 *       дословно срещу консолидирания текст на lex.bg към 17.09.2026 (последно изм. ДВ бр. 69 от
 *       31.07.2026, което НЕ засяга нито чл. 168ж, нито чл. 94).
 *   <li><b>Десетзначен български номер</b> — пропуска се. Това е номерът на физическо лице,
 *       регистрирано по ЗДДС; контролната цифра на ЕГН би отказала законен ЛНЧ или служебен номер
 *       от НАП, а последният изобщо няма контролна цифра. Арбитърът е VIES, който признава {@code
 *       BG} + 9 ИЛИ 10 цифри.
 * </ul>
 *
 * <p>⚠️ <b>Защо чл. 94, ал. 2 не се ползва за по-строга проверка.</b> До 31.12.2025 номерът беше
 * номерът по чл. 84 ДОПК с префикс „BG", тоест изводим от ЕИК/ЕГН. От 01.01.2026 (изм. ДВ бр. 115
 * от 2025 г.) текстът гласи, че номерът <b>се определя</b> с акта за регистрация — препратката към
 * чл. 84 ДОПК е махната от алинеята. На практика НАП още издава „BG" + ЕИК, но законът вече не го
 * гарантира. Затова и деветзначният случай тук само ПРЕДУПРЕЖДАВА и никога не блокира.
 *
 * <p>⚠️ Класът НЕ пише нищо и нарочно не пипа {@code Partner.registrationCode} — ЕИК проверката е
 * отделна и живее в {@link BgIdentifierValidator}.
 */
public final class BgVatNumberValidator {

  private static final BgEikValidationService EIK = new BgEikValidationService();

  /** Префиксът на българския номер по чл. 94, ал. 2 ЗДДС. */
  public static final String BG_PREFIX = "BG";

  /** Окончанието по чл. 168ж, ал. 11 ЗДДС — режим за малки предприятия в ЕС. */
  public static final String SMALL_ENTERPRISE_SUFFIX = "-EX";

  /**
   * Двубуквените кодове, които VIES обслужва: 27-те държави членки плюс {@code XI} (Северна
   * Ирландия по Протокола). ⚠️ Гърция е {@code EL}, а НЕ {@code GR} — това е кодът за целите на ДДС
   * и разминаването с ISO е точно мястото, където подобен списък обикновено е сгрешен.
   *
   * <p>⚠️ Списъкът НЕ е формат на номера — той отговаря само на „има ли смисъл да питаме VIES".
   * Структурата на номера умишлено не се проверява тук: арбитърът е самата услуга, която за
   * непознат вход връща {@code INVALID_INPUT}. Броят е сверен срещу живия {@code
   * rest-api/check-status}, който връща 28 държави.
   */
  private static final Set<String> VIES_COUNTRIES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "FR", "HR",
                  "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI",
                  "SK", "XI")));

  /** Накъде отива номерът — единственото решение, което повикващият трябва да вземе. */
  public enum Routing {
    /** Български номер: контролна цифра на място, БЕЗ мрежа. */
    BG_LOCAL,
    /** Чужд номер на държава, която VIES обслужва: жива проверка. */
    VIES,
    /** Номер по режима за малки предприятия („-EX“): не подлежи на VIES, само бележка. */
    SMALL_ENTERPRISE,
    /** Празно, без префикс или извън обхвата на VIES: нищо не се проверява. */
    SKIP
  }

  /** Присъдата на ОФЛАЙН слоя. „Не проверен" НЕ е „валиден" и не бива да се чете така. */
  public enum Verdict {
    /** Български номер, чиято контролна цифра излиза. */
    OK,
    /** Български 9- или 13-значен номер, чиято контролна цифра НЕ излиза. Само предупреждение. */
    BG_CHECKSUM_FAILED,
    /** Празно, чужд номер, „-EX" или българска дължина извън 9/13. Офлайн няма какво да каже. */
    NOT_CHECKED
  }

  private BgVatNumberValidator() {}

  /**
   * Накъде отива номерът. <b>Решава се от САМИЯ номер, не от адреса на партньора</b> — това е
   * цялата разлика спрямо заварения гейт при {@code registrationCode}.
   *
   * <p>Редът на проверките е част от правилото: окончанието „-EX" се гледа ПЪРВО, защото такъв
   * номер носи и код на държава, и би бил изпратен към VIES, който не го познава и би върнал {@code
   * INVALID} — тоест фалшива тревога по напълно законен номер.
   *
   * <p>⚠️ Стойност без буквен префикс се чете като БЪЛГАРСКА, а не се пропуска: така обичайното
   * въвеждане „203970390" получава контролна цифра, а мрежа не се вика (виж {@link #isForeign}).
   */
  public static Routing routeOf(String raw) {
    String value = normalize(raw);
    if (value.isEmpty()) {
      return Routing.SKIP;
    }
    if (isSmallEnterpriseNumber(value)) {
      return Routing.SMALL_ENTERPRISE;
    }
    String cc = countryCode(value);
    if (cc == null) {
      // Без префикс: чете се като български, ако изобщо прилича на номер.
      return nationalNumber(value).matches("\\d+") ? Routing.BG_LOCAL : Routing.SKIP;
    }
    if (BG_PREFIX.equals(cc)) {
      return Routing.BG_LOCAL;
    }
    return VIES_COUNTRIES.contains(cc) ? Routing.VIES : Routing.SKIP;
  }

  /**
   * Обслужва ли VIES двубуквения код — 27-те държави членки (Гърция като {@code EL}) плюс {@code
   * XI}. SAF-T го ползва, за да разграничи тип 11 (ЕС) от тип 12 (извън ЕС) на
   * CustomerID/SupplierID по същия списък, по който номерът се праща към VIES.
   */
  public static boolean isViesCountry(String code) {
    return code != null && VIES_COUNTRIES.contains(code.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Главната врата: какво може да се каже за номера БЕЗ мрежа.
   *
   * <p>Празната стойност дава {@link Verdict#NOT_CHECKED} — този клас отговаря на „има ли грешка",
   * а не на „попълнено ли е". Задължителността е друг въпрос и не се решава тук.
   */
  public static Verdict checkLocally(String raw) {
    String value = normalize(raw);
    if (value.isEmpty() || isSmallEnterpriseNumber(value) || isForeign(value)) {
      return Verdict.NOT_CHECKED;
    }
    String digits = nationalNumber(value);
    if (!digits.matches("\\d+")) {
      return Verdict.NOT_CHECKED;
    }
    if (digits.length() != 9 && digits.length() != 13) {
      return Verdict.NOT_CHECKED;
    }
    return EIK.validateEik(digits) ? Verdict.OK : Verdict.BG_CHECKSUM_FAILED;
  }

  /**
   * Единственото, за което офлайн слоят предупреждава — за {@code <alert>} израз и за контролера.
   */
  public static boolean isBgChecksumViolation(String raw) {
    return checkLocally(raw) == Verdict.BG_CHECKSUM_FAILED;
  }

  /**
   * Носи ли номерът ЧУЖД (не-BG) двубуквен префикс — тоест дали авто-проверката на запис изобщо има
   * работа за VIES.
   *
   * <p>⚠️ Стойност без буквен префикс НЕ е чужда: вижда се като българска (виж {@link
   * #nationalNumber}). Така авто-проверката никога не праща към мрежата номер, който не е разпознат
   * като чужд, и български запис не се бави.
   */
  public static boolean isForeign(String raw) {
    String cc = countryCode(raw);
    return cc != null && !BG_PREFIX.equals(cc);
  }

  /**
   * Номер по чл. 168ж, ал. 11 ЗДДС (режим за малки предприятия) — разпознава се по окончанието.
   *
   * <p>Законовата форма е с тире („-EX"), но се приема и слятото „…EX" след ЦИФРА: така се преписва
   * от фактура достатъчно често, а цената на пропуска е фалшива тревога по законен номер.
   * Изискването „след цифра" пази буквен формат, който случайно свършва на EX, да бъде сбъркан с
   * режима.
   */
  public static boolean isSmallEnterpriseNumber(String raw) {
    String value = normalize(raw);
    if (value.endsWith(SMALL_ENTERPRISE_SUFFIX)) {
      return true;
    }
    return value.length() >= 3
        && value.endsWith("EX")
        && Character.isDigit(value.charAt(value.length() - 3));
  }

  /**
   * Двубуквеният код на държавата от началото на номера, или {@code null}, когато номерът не почва
   * с две букви. Не се проверява дали кодът е на съществуваща държава — това е работа на VIES,
   * който връща {@code INVALID_INPUT} за непознат код и няма нужда от втори списък тук.
   */
  public static String countryCode(String raw) {
    String value = normalize(raw);
    if (value.length() < 3) {
      return null;
    }
    return Character.isLetter(value.charAt(0)) && Character.isLetter(value.charAt(1))
        ? value.substring(0, 2)
        : null;
  }

  /**
   * Номерът БЕЗ кода на държавата и без окончанието „-EX" — това е, което VIES очаква във втория си
   * параметър, и това, върху което се смята контролната цифра.
   */
  public static String nationalNumber(String raw) {
    String value = normalize(raw);
    if (isSmallEnterpriseNumber(value)) {
      value = value.substring(0, value.lastIndexOf("EX"));
    }
    if (countryCode(raw) != null) {
      value = value.substring(2);
    }
    return value.replace("-", "");
  }

  /**
   * Главни букви, без интервали и пунктуация; тиретата се пазят, за да оцелее окончанието „-EX".
   *
   * <p>⚠️ Тук нарочно се чисти повече, отколкото в {@link BgEikValidationService#toDigits}. Там
   * стойността е ЕИК и полето трябва да приеме само чист код; тук стойността е ДДС номер, който
   * човек преписва от фактура — „BG 203 970 390" и „BG203970390" са един и същи номер.
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
  }
}
