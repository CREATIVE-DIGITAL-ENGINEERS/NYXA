package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * ДОКЪДЕ СТИГА ИСТОРИЯТА — границата на импорта от Microinvest, изписана като ЧИСТО правило.
 *
 * <p><b>Защо изобщо я има.</b> Внесените статии са извън ДДС дневниците завинаги ({@code
 * Move.bgMigratedHistorical}), защото периодите им вече са декларирани пред НАП ОТ Microinvest.
 * Това е вярно само докато файлът носи единствено ПОДАДЕНИ периоди. Годишната извадка обаче съдържа
 * и текущия, още неподаден месец — при миграция на 15 септември в нея са и документите от 1–14
 * септември. Внесени, те щяха да изчезнат от септемврийските дневници: нито Microinvest ги е подал,
 * нито Nyxa би ги показал.
 *
 * <p><b>Решението.</b> Счетоводителят заявява ПОСЛЕДНИЯ период, подаден от Microinvest. Всичко след
 * него не се внася изобщо и се въвежда в Nyxa като обикновени фактури — там работи обичайната ДДС
 * логика на Nyxa и дневниците излизат верни без нито едно изключение.
 *
 * <p><b>Границата е ВКЛЮЧВАЩА и е последният календарен ден на заявения месец</b> (ЗДДС чл. 87 ал.
 * 2: данъчният период е календарен месец). За 202608 това е 31.08.2026 — 31 август влиза, 1
 * септември не.
 *
 * <p><b>Без мълчаливо подразбиране.</b> Липсващ или невалиден период е БЛОКЕР, не „внеси всичко“:
 * подразбирането щеше да е точно грешката, която правилото предотвратява.
 */
public final class MicroinvestVatPeriodCutoff {

  /** Форматът на периода — ГГГГММ, както {@code BgVatReturn.period}. */
  private static final int PERIOD_LENGTH = 6;

  private MicroinvestVatPeriodCutoff() {}

  /** ГГГГММ: точно 6 цифри и месец 01–12. {@code null}/празно/друго — не. */
  public static boolean isWellFormed(String period) {
    if (period == null) {
      return false;
    }
    String value = period.trim();
    if (value.length() != PERIOD_LENGTH) {
      return false;
    }
    for (int i = 0; i < PERIOD_LENGTH; i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    int month = Integer.parseInt(value.substring(4));
    return month >= 1 && month <= 12;
  }

  /**
   * Включващата гранична дата: ПОСЛЕДНИЯТ календарен ден на периода. {@code null}, когато периодът
   * не е валиден — повикващият е длъжен да го провери и да блокира.
   */
  public static LocalDate cutoffOf(String period) {
    if (!isWellFormed(period)) {
      return null;
    }
    String value = period.trim();
    return YearMonth.of(
            Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4)))
        .atEndOfMonth();
  }

  /**
   * Извън обхвата ли е тази дата.
   *
   * <p>⚠️ Дата {@code null} НЕ се изключва. Запис без дата е друг дефект и си има своя проверка
   * (периоди/баланси); изхвърлен тихо тук, той щеше да изчезне и от двете системи.
   */
  public static boolean isAfterCutoff(LocalDate date, LocalDate cutoff) {
    return date != null && cutoff != null && date.isAfter(cutoff);
  }

  /** Защо импортът не може да тръгне без валиден период; {@code null}, когато може. */
  public static String refusal(String period) {
    if (period == null || period.trim().isEmpty()) {
      return "Не е посочен последният ДДС период, подаден от Microinvest. Импортът не може да"
          + " реши кои документи са вече декларирани и кои трябва да се въведат в Nyxa, затова"
          + " не се записва нищо. Въведете периода като ГГГГММ (напр. 202608).";
    }
    if (!isWellFormed(period)) {
      return "„"
          + period.trim()
          + "“ не е валиден ДДС период. Очаква се ГГГГММ с месец 01–12 (напр. 202608).";
    }
    return null;
  }

  /** Периодът като текст за доклада: „202608 (включително 31.08.2026)“. */
  public static String describe(String period, LocalDate cutoff) {
    return period.trim()
        + (cutoff == null
            ? ""
            : " (включително "
                + String.format(
                    "%02d.%02d.%04d",
                    cutoff.getDayOfMonth(), cutoff.getMonthValue(), cutoff.getYear())
                + ")");
  }
}
