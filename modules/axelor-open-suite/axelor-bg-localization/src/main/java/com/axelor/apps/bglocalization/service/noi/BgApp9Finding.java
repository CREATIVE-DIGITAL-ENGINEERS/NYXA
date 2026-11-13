package com.axelor.apps.bglocalization.service.noi;

/**
 * ЕДНА НАХОДКА НА ВАЛИДАТОРА — грешка, която ще върне файла, или флаг, който иска човешко око.
 *
 * <p><b>⚠️ ТРИТЕ НИВА НЕ СЕ СЛИВАТ, И ТОВА Е ПОУКАТА ОТ ЛИПСИТЕ.</b> „Файлът е структурно
 * невалиден“ (схемата), „НОИ ще го върне на контрол“ (таблицата с контролите) и „това е несигурно и
 * трябва човек да го погледне“ са три различни задължения. Свити до едно „предупреждение“, третото
 * поглъща първите две и отчетът става списък, който никой не чете докрай.
 */
public final class BgApp9Finding {

  /** Тежестта на находката. */
  public enum Level {
    /** Схемата или еднозначен контрол на НОИ са нарушени — файлът НЯМА да бъде приет. */
    ERROR,

    /**
     * ОБЯВЕНА НЕСИГУРНОСТ. Правило, което указанието иска, а модулът не може да провери или да
     * попълни — или разминаване между схемата и Наредбата. Не спира файла и НЕ се мълчи.
     */
    FLAG
  }

  /** Кой слой е проговорил: {@code XSD}, {@code КОНТРОЛ} или {@code РАЗМИНАВАНЕ}. */
  public final String layer;

  /** Точката или елементът. */
  public final String subject;

  /** ЦЯЛО ИЗРЕЧЕНИЕ. */
  public final String message;

  /** ERROR или FLAG. */
  public final Level level;

  private BgApp9Finding(String layer, String subject, String message, Level level) {
    this.layer = layer;
    this.subject = subject;
    this.message = message;
    this.level = level;
  }

  /** Слой 1 — валидацията срещу {@code Pril9.xsd}. */
  public static BgApp9Finding schema(String subject, String message) {
    return new BgApp9Finding("XSD", subject, message, Level.ERROR);
  }

  /** Слой 2 — еднозначен кръстосан контрол от таблицата на НОИ. */
  public static BgApp9Finding control(String subject, String message) {
    return new BgApp9Finding("КОНТРОЛ", subject, message, Level.ERROR);
  }

  /** Обявена несигурност — правило без носител, или разминаване между източниците. */
  public static BgApp9Finding flag(String subject, String message) {
    return new BgApp9Finding("ФЛАГ", subject, message, Level.FLAG);
  }

  public boolean isError() {
    return level == Level.ERROR;
  }

  /** Редът в отчета. */
  public String describe() {
    return (level == Level.ERROR ? "[! " : "[~ ") + layer + "] " + subject + " — " + message;
  }

  @Override
  public String toString() {
    return describe();
  }
}
