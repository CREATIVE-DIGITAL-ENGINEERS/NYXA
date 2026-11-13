/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Сглобката: рутиране по префикс → БГ контролна цифра ИЛИ жива VIES → съобщение за екрана.
 */
package com.axelor.apps.bglocalization.service.vies;

import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator.Routing;
import com.google.inject.Singleton;
import javax.inject.Inject;

/**
 * Проверката на {@code Partner.taxNbr} — цялото правило на едно място, без Axelor контекст.
 *
 * <p><b>Защо е тук, а не в контролера.</b> Контролерът не може да бъде тестван без Guice, а модулът
 * няма mock-подпора. Всичко, което решава нещо, живее в този клас: той приема ЕДИН низ и връща ЕДИН
 * изход, тоест всяко правило има свидетел в тест. Контролерът остава три реда, които само подават
 * стойността и показват съобщението.
 *
 * <p><b>Мрежата се пипа САМО за чужд номер.</b> Българският се решава от контролната цифра на място
 * — мигновено и вярно и при паднал VIES. Това е и причината записът на български партньор да не се
 * бави никога, независимо какво прави услугата на Комисията.
 *
 * <p>⚠️ <b>Нищо тук не блокира.</b> Изходът е съобщение, не отказ; повикващият го показва с {@code
 * response.setNotify}, което не спира записа. Дори категоричното „не е намерен" е предупреждение,
 * защото регистрацията по ДДС е правен статут, който системата не бива да оспорва — а от 01.01.2026
 * (изм. ДВ бр. 115 от 2025 г.) чл. 94, ал. 2 ЗДДС вече дори не извежда номера от ЕИК, тоест
 * „изчислено невалиден" не е основание да се откаже запис.
 */
@Singleton
public class BgVatCheckService {

  /** Колко силно е съобщението. И двете се показват НЕПРЕКЪСВАЩО — разликата е в текста. */
  public enum Level {
    /** Няма какво да се каже. */
    NONE,
    /** Информация: не можах да проверя, или номерът не подлежи на проверка. */
    INFO,
    /** Предупреждение: номерът не излиза по контролна цифра или го няма в регистъра. */
    WARNING
  }

  /** Изходът на проверката: ниво + готов за показване текст. */
  public static final class Outcome {
    private final Level level;
    private final String message;

    Outcome(Level level, String message) {
      this.level = level;
      this.message = message;
    }

    public Level getLevel() {
      return level;
    }

    public String getMessage() {
      return message;
    }

    public boolean hasMessage() {
      return message != null;
    }
  }

  private static final Outcome SILENT = new Outcome(Level.NONE, null);

  private final BgViesClient viesClient;

  @Inject
  public BgVatCheckService(BgViesClient viesClient) {
    this.viesClient = viesClient;
  }

  /**
   * Присъдата за един ДДС номер.
   *
   * <p>⚠️ <b>Мълчанието при ВАЛИДЕН номер е нарочно.</b> Проверката се пуска на всеки запис на
   * партньора, включително когато е пипнато съвсем друго поле; потвърждение „всичко е наред" при
   * всеки такъв запис би било шум, който учи потребителя да затваря съобщенията, без да ги чете — и
   * точно тогава истинското предупреждение би минало незабелязано.
   */
  public Outcome check(String taxNbr) {
    Routing routing = BgVatNumberValidator.routeOf(taxNbr);
    switch (routing) {
      case BG_LOCAL:
        return checkBulgarian(taxNbr);
      case VIES:
        return checkInVies(taxNbr);
      case SMALL_ENTERPRISE:
        return new Outcome(
            Level.INFO,
            "Номерът е по режима за малки предприятия в ЕС (чл. 168ж, ал. 11 ЗДДС) и не подлежи"
                + " на проверка във VIES.");
      default:
        return SILENT;
    }
  }

  /** Българският номер: контролна цифра на ЕИК, без мрежа. */
  private Outcome checkBulgarian(String taxNbr) {
    if (BgVatNumberValidator.isBgChecksumViolation(taxNbr)) {
      return new Outcome(
          Level.WARNING, "Идент. № по ДДС: контролната цифра не излиза. Сверете номера.");
    }
    return SILENT;
  }

  /**
   * Чуждият номер: жива справка.
   *
   * <p>⚠️ Само {@link BgViesResult.Status#INVALID} е предупреждение. Недостъпност е информация, а
   * изключена проверка е мълчание — така нито паднала услуга, нито затворен изход могат да
   * произведат обвинение срещу верен номер.
   */
  private Outcome checkInVies(String taxNbr) {
    BgViesResult result =
        viesClient.check(
            BgVatNumberValidator.countryCode(taxNbr), BgVatNumberValidator.nationalNumber(taxNbr));
    switch (result.getStatus()) {
      case INVALID:
        return new Outcome(
            Level.WARNING,
            "Идент. № по ДДС: не е намерен като активно регистриран във VIES. Сверете номера.");
      case INVALID_INPUT:
        return new Outcome(Level.INFO, "VIES не разпозна формата на този идент. № по ДДС.");
      case UNAVAILABLE:
        return new Outcome(
            Level.INFO, "Не можах да проверя идент. № по ДДС чрез VIES (услугата е недостъпна).");
      default:
        // VALID и DISABLED — виж бележката при check(): тишина.
        return SILENT;
    }
  }
}
