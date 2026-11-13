package com.axelor.apps.bglocalization.service.zddfl;

import java.time.LocalDate;

/**
 * СЪСТОЯНИЯТА НА ЕДИН ЗДДФЛ-ОБРАЗЕЦ — стойностите на селекцията {@code
 * bg.art73.report.status.select}, назовани веднъж.
 *
 * <p><b>⚠️ ДОТУК БЯХА ГОЛИ НИЗОВЕ В ДВЕ УСЛУГИ.</b> „GENERATED“ и „SUBMITTED“ се пишеха дословно на
 * шест места, а „ACCEPTED“ и „REJECTED“ ги нямаше НИКЪДЕ в кода — тоест селекцията обещаваше два
 * изхода, до които нищо не водеше. Сгрешен низ не пада никъде: полето е {@code string} и базата
 * приема каквото и да е, а екранът показва празно вместо етикет.
 *
 * <p>⚠️ Потокът е: {@link #DRAFT} → {@link #GENERATED} (има файл) → {@link #SUBMITTED} (файлът е
 * закачен към подаване) → {@link #ACCEPTED} или {@link #REJECTED} (РЪЧЕН изход от портала).
 */
public final class BgDeclarationStatus {

  /** Няма генериран файл. */
  public static final String DRAFT = "DRAFT";

  /** Редовете са изведени и файлът е произведен — сваляем и проверим преди подаване. */
  public static final String GENERATED = "GENERATED";

  /** Файлът е закачен към подаване в одитната следа. */
  public static final String SUBMITTED = "SUBMITTED";

  /** НАП е приела подаването — ръчно въведено с входящ номер и дата. */
  public static final String ACCEPTED = "ACCEPTED";

  /** НАП е отхвърлила подаването — ръчно въведено с входящ номер и дата. */
  public static final String REJECTED = "REJECTED";

  private BgDeclarationStatus() {}

  /**
   * ЗАКЛЮЧЕН ЛИ Е ЗАПИСЪТ — тоест забранено ли е обикновеното повторно генериране.
   *
   * <p><b>⚠️ ЗАКЛЮЧВА ФАЙЛЪТ, А НЕ САМО СЪСТОЯНИЕТО.</b> Щом веднъж е произведен файл, редовете зад
   * него вече са раздадени: човек ги е свалил, може да ги е качил. Мълчаливо презаписани при второ
   * натискане на същия бутон, те биха направили екрана и свалèния файл различни, без нищо да го
   * каже. Оттук нататък пресъздаването е ИЗРИЧНО, със свой бутон.
   *
   * @param status стойност на {@code statusSelect}
   * @param hasFile има ли вече генериран файл
   */
  public static boolean isLocked(String status, boolean hasFile) {
    return hasFile
        || SUBMITTED.equals(status)
        || ACCEPTED.equals(status)
        || REJECTED.equals(status);
  }

  /**
   * ПРЕДУСЛОВИЕТО НА РЪЧНИЯ ИЗХОД — изнесено тук, за да е проверимо БЕЗ база.
   *
   * <p><b>⚠️ ИСКА СВИДЕТЕЛ, А НЕ САМО НАТИСНАТ БУТОН.</b> „Приета“ без входящ номер и без дата не
   * отговаря на нито един въпрос, който някой по-късно ще зададе на този запис: кога, с какъв номер
   * и срещу кой файл. Записано без тях, състоянието изглежда като факт, а е спомен.
   *
   * @param attached закачен ли е образецът към подаване
   * @throws IllegalStateException с обяснение, а не с празен отказ
   */
  public static void requireManualOutcome(
      boolean attached, String incomingNumber, LocalDate responseDate) {
    if (!attached) {
      throw new IllegalStateException(
          "Образецът не е закачен към подаване — няма на какво да се запише изход.");
    }
    requireIncomingNumber(incomingNumber, responseDate);
  }

  /**
   * СВИДЕТЕЛЯТ НА ПОДАВАНЕТО — входящ номер и дата от портала.
   *
   * <p><b>⚠️ ТОВА, А НЕ ФАЙЛЪТ, ПРАВИ „ПОДАДЕНО“ ВЯРНО.</b> Докато форматът на записа не дойде със
   * заповед на НАП, човек подава през портала без наш файл — и подаването пак трябва да има къде да
   * живее, инак за цял период не остава следа. Старото „закачане“ беше кухо не защото нямаше файл,
   * а защото нямаше НИЩО: твърдеше „подадено“ и не сочеше към нито едно събитие в НАП.
   */
  public static void requireIncomingNumber(String incomingNumber, LocalDate date) {
    if (incomingNumber == null || incomingNumber.trim().isEmpty() || date == null) {
      throw new IllegalStateException(
          "Въведи входящ №/протокол и дата от портала — без тях „подадено“ е твърдение без"
              + " свидетел.");
    }
  }
}
