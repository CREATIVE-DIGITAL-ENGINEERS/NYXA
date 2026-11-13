package com.axelor.apps.bglocalization.service.etz;

/**
 * Една констатация от проверката — по образеца на {@code BgApp9Finding}.
 *
 * <p>Отделен тип от {@link BgEtzGap}: дупката е причина файлът ДА НЕ СЕ произведе, констатацията е
 * бележка ВЪРХУ произведен файл. Смесени, двете биха дали списък, в който „схемата не е набавена“ и
 * „ден извън 1–31“ стоят един до друг, без да се вижда кое спира подаването.
 */
public final class BgEtzFinding {

  /** Откъде идва констатацията. */
  public enum Source {
    /** Структурна проверка срещу XSD схемата. */
    SCHEMA,
    /** Кръстосан контрол, който схемата не може да изрази. */
    CONTROL,
    /** Самата проверка не е могла да се извърши. */
    GATE
  }

  private final Source source;
  private final String where;
  private final String message;

  private BgEtzFinding(Source source, String where, String message) {
    this.source = source;
    this.where = where;
    this.message = message;
  }

  public static BgEtzFinding schema(String where, String message) {
    return new BgEtzFinding(Source.SCHEMA, where, message);
  }

  public static BgEtzFinding control(String where, String message) {
    return new BgEtzFinding(Source.CONTROL, where, message);
  }

  /** Проверката НЕ е извършена — най-важният вид, защото се бърка със „няма грешки“. */
  public static BgEtzFinding gate(String where, String message) {
    return new BgEtzFinding(Source.GATE, where, message);
  }

  public Source getSource() {
    return source;
  }

  public String getWhere() {
    return where;
  }

  public String getMessage() {
    return message;
  }

  public String describe() {
    return source + " · " + where + ": " + message;
  }

  @Override
  public String toString() {
    return describe();
  }
}
