package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Кодовете по номенклатурата „Stock movement“ (SAF-T SD.MG.10) и посоката, в която всеки е
 * допустим. Чиста функция: не чете базата.
 *
 * <p>Посоката идва от името на кода в книгата 1.0.2 (лист Stock movement), не от Axelor:
 *
 * <ul>
 *   <li>само ВХОД: 10 покупка, 20 продукция, 40 връщане на продадени, 60 получени отстъпки в
 *       натура, 110 положителна корекция от инвентаризацията;
 *   <li>само ИЗХОД: 30 продажба, 50 връщане на закупени, 65 предоставени отстъпки в натура, 70 за
 *       производство, 120 отрицателна корекция, 150 безвъзмездно предоставени, 160 брак, 170
 *       изтекъл срок;
 *   <li>всяка посока: 80 вътрешен трансфер (S1: и към подизпълнител, чиято локация е виртуална) и
 *       180 други движения;
 *   <li>само по СТОЙНОСТ: 90, 100, 101, 130, 140 — капитализирани разходи, ценови разлики,
 *       преоценка. Те нямат количество и не стоят на складово движение: моделът им е документ на
 *       ИЗП.
 * </ul>
 */
public final class BgStockMovementCodes {

  /** Посоката на движението спрямо собствените (невиртуални) локации на дружеството. */
  public enum Direction {
    IN("вход"),
    OUT("изход"),
    TRANSFER("вътрешен трансфер");

    private final String label;

    Direction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * {@code StockLocation.typeSelect} = 3 — виртуална локация (stock.stock.location.type.select).
   */
  static final int LOCATION_VIRTUAL = 3;

  private static final Set<Direction> IN = Collections.unmodifiableSet(EnumSet.of(Direction.IN));
  private static final Set<Direction> OUT = Collections.unmodifiableSet(EnumSet.of(Direction.OUT));
  private static final Set<Direction> ANY =
      Collections.unmodifiableSet(EnumSet.allOf(Direction.class));
  private static final Set<Direction> VALUE_ONLY =
      Collections.unmodifiableSet(EnumSet.noneOf(Direction.class));

  /** Всичките 20 кода на номенклатурата, в реда на книгата. */
  static final Map<String, Set<Direction>> ALLOWED;

  static {
    Map<String, Set<Direction>> m = new LinkedHashMap<>();
    m.put("10", IN);
    m.put("20", IN);
    m.put("30", OUT);
    m.put("40", IN);
    m.put("50", OUT);
    m.put("60", IN);
    m.put("65", OUT);
    m.put("70", OUT);
    m.put("80", ANY);
    m.put("90", VALUE_ONLY);
    m.put("100", VALUE_ONLY);
    m.put("101", VALUE_ONLY);
    m.put("110", IN);
    m.put("120", OUT);
    m.put("130", VALUE_ONLY);
    m.put("140", VALUE_ONLY);
    m.put("150", OUT);
    m.put("160", OUT);
    m.put("170", OUT);
    m.put("180", ANY);
    ALLOWED = Collections.unmodifiableMap(m);
  }

  private BgStockMovementCodes() {}

  /** Кодовете на номенклатурата в реда на книгата. */
  public static Set<String> codes() {
    return ALLOWED.keySet();
  }

  /** Посоките, в които кодът е допустим; празно — код само по стойност; {@code null} — непознат. */
  public static Set<Direction> allowed(String code) {
    return ALLOWED.get(code);
  }

  /**
   * Защо кодът не може да стои на движение в тази посока, или {@code null}, когато може. Празен код
   * винаги може (класифицира се автоматично), както и движение без определима посока.
   */
  public static String refusal(String code, Direction direction) {
    if (code == null || code.trim().isEmpty() || direction == null) {
      return null;
    }
    Set<Direction> allowed = ALLOWED.get(code);
    if (allowed == null) {
      return String.format("Кодът „%s“ не е в номенклатурата „Stock movement“ на SAF-T.", code);
    }
    if (allowed.isEmpty()) {
      return String.format(
          "Кодът „%s“ е движение само по стойност (без количество) и не стои на складово"
              + " движение. Ценовите разлики, капитализираните разходи и преоценката се отразяват с"
              + " документ за стойностна корекция.",
          code);
    }
    if (!allowed.contains(direction)) {
      return String.format(
          "Кодът „%s“ по „Stock movement“ е допустим само за %s, а движението е %s.",
          code, labels(allowed), direction.label());
    }
    return null;
  }

  /**
   * Посоката на движението по хедъра: собствена (невиртуална) локация от двете страни — трансфер;
   * само в целта — вход; само в източника — изход. {@code null}, когато не се определя.
   */
  public static Direction directionOf(StockMove move) {
    if (move == null) {
      return null;
    }
    return directionOf(move.getFromStockLocation(), move.getToStockLocation());
  }

  static Direction directionOf(StockLocation from, StockLocation to) {
    if (from == null || to == null) {
      return null;
    }
    boolean ownFrom = isOwn(from);
    boolean ownTo = isOwn(to);
    if (ownFrom && ownTo) {
      return Direction.TRANSFER;
    }
    if (ownTo) {
      return Direction.IN;
    }
    if (ownFrom) {
      return Direction.OUT;
    }
    return null;
  }

  /** Генерираният getter връща 0 вместо null (BgDefaultedGetterTest) — непопълненото е „свое“. */
  private static boolean isOwn(StockLocation location) {
    return location.getTypeSelect() != LOCATION_VIRTUAL;
  }

  private static String labels(Set<Direction> directions) {
    StringBuilder out = new StringBuilder();
    for (Direction d : directions) {
      if (out.length() > 0) {
        out.append(" или ");
      }
      out.append(d.label());
    }
    return out.toString();
  }
}
