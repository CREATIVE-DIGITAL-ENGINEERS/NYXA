package com.axelor.apps.bglocalization.service.etz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ЕДИН РЕД ({@code rowenum}) — стойностите по ТАГ, в реда на изписване.
 *
 * <p>Ключът е тагът, а не изброяването, защото двете схеми ползват едни и същи имена на елементи с
 * различни номера ({@code egn} е т. 5 в едната и т. 6 в другата). Каталозите {@link BgEtz62Field} и
 * {@link BgEtz123Field} остават единственият източник на реда и на номерата за отчета.
 *
 * <p><b>⚠️ ПРАЗНА СТОЙНОСТ И ЛИПСВАЩА СТОЙНОСТ СА РАЗЛИЧНИ, И ТУК РАЗЛИКАТА СЕ ПАЗИ.</b> При НОИ
 * (Приложение № 9) всички тагове се пишат винаги, а неотносимите — празни. Тук е обратното: почти
 * всичко е {@code minOccurs="0"} и празен елемент от вида {@code <begindate></begindate>} НЕ минава
 * срещу {@code xs:date}. Затова неподаденото поле изобщо не влиза в записа — {@link #put} игнорира
 * празното.
 */
public final class BgEtzRecord {

  private final Map<String, String> values = new LinkedHashMap<>();

  /**
   * Слага стойност; празното НЕ се записва.
   *
   * <p>⚠️ Тихото игнориране тук е безопасно точно защото задължителността се пази ОТДЕЛНО, от
   * {@link BgEtzMandatory}, срещу условията на приложенията. Без този пазач мълчанието тук би
   * раждало непълни файлове.
   */
  public void put(String tag, String value) {
    if (value == null) {
      return;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    values.put(tag, trimmed);
  }

  /** Слага стойност по поле на {@code etzemploy}. */
  public void put(BgEtz62Field field, String value) {
    put(field.getTag(), value);
  }

  /** Слага стойност по поле на {@code etz123employer}. */
  public void put(BgEtz123Field field, String value) {
    put(field.getTag(), value);
  }

  /** Стойността на един таг, или {@code null}. */
  public String get(String tag) {
    return values.get(tag);
  }

  public String get(BgEtz62Field field) {
    return values.get(field.getTag());
  }

  public String get(BgEtz123Field field) {
    return values.get(field.getTag());
  }

  /** Дали тагът е попълнен. */
  public boolean has(String tag) {
    return values.containsKey(tag);
  }

  /** Всичко попълнено — за отчет и за тестове. */
  public Map<String, String> getValues() {
    return Collections.unmodifiableMap(values);
  }
}
