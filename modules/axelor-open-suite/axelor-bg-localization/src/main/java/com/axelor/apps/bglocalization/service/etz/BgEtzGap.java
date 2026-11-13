package com.axelor.apps.bglocalization.service.etz;

/**
 * ЕДНА ОБЯВЕНА ПРЕЧКА — реквизит, който записът иска, а ние нямаме, или стойност, която противоречи
 * на друга.
 *
 * <p><b>Защо пречките са списък, а не изключение при първата.</b> Договор без ЕКАТТЕ, без НКПД и с
 * непреводимо основание има ТРИ пречки. Резолвер, който хвърля при първата, ги показва една по една
 * — по едно пускане на пречка. Събрани, те излизат наведнъж и се поправят в едно минаване.
 *
 * <p><b>⚠️ ЕТИКЕТЪТ Е НИЗ, А НЕ ПОЛЕ ОТ ИЗБРОЯВАНЕ, И ТОВА Е НАРОЧНО.</b> Двете схеми имат два
 * отделни каталога ({@link BgEtz62Field} и {@link BgEtz123Field}) с РАЗЛИЧНИ номера за едни и същи
 * тагове. Общ тип тук би заставил единия каталог да се преведе към другия и точно номерата в
 * отказите — това, което човек чете — биха се разминали с приложението.
 */
public final class BgEtzGap {

  /** Видът на пречката. */
  public enum Kind {
    /** Модулът няма откъде да вземе стойността. */
    NO_DATA_SOURCE("модулът няма откъде да вземе стойността"),
    /** Носителят съществува, но е празен за този договор. */
    EMPTY_VALUE("стойността липсва"),
    /** Кодът не може да се преобразува към номенклатурата на регистъра. */
    UNMAPPED_CODE("няма код в номенклатурата на регистъра"),
    /** Стойността противоречи на друга стойност в същия запис. */
    CROSS_CHECK("кръстосана проверка не минава");

    private final String text;

    Kind(String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }
  }

  private final String where;
  private final Kind kind;
  private final String detail;

  private BgEtzGap(String where, Kind kind, String detail) {
    this.where = where;
    this.kind = kind;
    this.detail = detail;
  }

  /** Пречка, вързана за назован реквизит. */
  public static BgEtzGap of(String where, Kind kind, String detail) {
    return new BgEtzGap(where, kind, detail);
  }

  /** Пречка, чието описание вече назовава реквизита. */
  public static BgEtzGap general(Kind kind, String detail) {
    return new BgEtzGap(null, kind, detail);
  }

  /** Реквизитът, или {@code null}. */
  public String getWhere() {
    return where;
  }

  public Kind getKind() {
    return kind;
  }

  public String getDetail() {
    return detail;
  }

  /** Един ред за отчета. */
  public String describe() {
    return (where == null ? "" : where + " — ")
        + (detail == null || detail.isEmpty() ? kind.getText() : detail);
  }

  @Override
  public String toString() {
    return describe();
  }
}
