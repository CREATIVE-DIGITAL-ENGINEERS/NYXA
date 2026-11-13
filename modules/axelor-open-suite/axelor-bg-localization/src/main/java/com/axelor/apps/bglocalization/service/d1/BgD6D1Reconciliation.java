package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * СВЕРКАТА Д6 ↔ Д1 — по ФИША, с разликата по точки на Д6. Чиста функция: без база, без Guice.
 *
 * <p><b>ЗАЩО ПО ФИША, А НЕ ПО СУМИТЕ НА ЗАПИСИТЕ.</b> Записът на Д1 не носи вноските по фондове —
 * т. 22 е СТАВКА ({@link BgD6Emitter#checkEquality}). Сравнимото е друго: всеки запис сочи фиша си
 * ({@code BgD1Record.paySlip}), а Д6 се смята от ДЕЙСТВАЩИТЕ фишове ({@code
 * BgEffectivePaySlips#effectiveFor}). Запис, който сочи фиш, вече изместен от корекция, значи Д1,
 * генерирана ПРЕДИ корекцията — и двете декларации за един месец твърдят различни числа (например
 * Д1 с 1700, Д6 с 1840).
 *
 * <p><b>⚠️ ДЕЙСТВАЩ ФИШ БЕЗ ЗАПИС В Д1 Е ДОПУСТИМ.</b> Кодове 15, 88 и 89 Д1 нарочно ОТКАЗВА
 * (данните подава НОИ), а вноските им се дължат и влизат в Д6 ({@link BgD6Emitter#emit}). Затова
 * разликата по точки се смята САМО за лицата (носителите), които Д1 носи — иначе всяко такова лице
 * би изглеждало като разминаване.
 */
public final class BgD6D1Reconciliation {

  private BgD6D1Reconciliation() {}

  /**
   * Причината Д6 да НЕ се генерира срещу тази Д1, или {@code null}.
   *
   * @param periodName името на периода — за текста
   * @param effective действащите фишове, от които се смята Д6
   * @param records записите на РЕДОВНАТА Д1 за същото дружество и месец
   */
  public static String refuse(
      String periodName, List<BgPaySlip> effective, List<BgD1Record> records) {
    if (records == null || records.isEmpty()) {
      return null;
    }
    Set<Long> effectiveIds = new LinkedHashSet<>();
    for (BgPaySlip slip : safe(effective)) {
      if (slip != null && slip.getId() != null) {
        effectiveIds.add(slip.getId());
      }
    }

    List<BgPaySlip> d1Slips = new ArrayList<>();
    Set<String> d1Carriers = new LinkedHashSet<>();
    Set<String> stale = new TreeSet<>();
    for (BgD1Record record : records) {
      BgPaySlip slip = record == null ? null : record.getPaySlip();
      if (slip == null || slip.getId() == null) {
        continue;
      }
      if (!containsId(d1Slips, slip.getId())) {
        d1Slips.add(slip);
      }
      d1Carriers.add(carrierOf(slip));
      if (!effectiveIds.contains(slip.getId())) {
        stale.add(label(slip));
      }
    }
    if (stale.isEmpty()) {
      return null;
    }

    List<BgPaySlip> d6Slips = new ArrayList<>();
    for (BgPaySlip slip : safe(effective)) {
      if (slip != null && d1Carriers.contains(carrierOf(slip))) {
        d6Slips.add(slip);
      }
    }

    return "Д1 за „"
        + (periodName == null ? "(без период)" : periodName)
        + "“ е генерирана преди корекцията на "
        + String.join(", ", stale)
        + "; генерирайте Д1 наново. Записите ѝ сочат фиш, който вече не е действащ — Д6 се смята от"
        + " действащите и двете декларации за месеца биха твърдели различни числа."
        + " Разлика по точки на Д6 (действащите фишове − фишовете на Д1): "
        + differences(BgD6Aggregator.byPoint(d6Slips), BgD6Aggregator.byPoint(d1Slips))
        + ".";
  }

  /** „т. 10: 27.72; т. 14: …“ — само ненулевите разлики, по реда на точките. */
  static String differences(Map<String, BigDecimal> d6, Map<String, BigDecimal> d1) {
    Set<String> points = new TreeSet<>(BgD6D1Reconciliation::byNumber);
    points.addAll(d6.keySet());
    points.addAll(d1.keySet());
    Map<String, BigDecimal> diff = new LinkedHashMap<>();
    for (String point : points) {
      BigDecimal value = orZero(d6.get(point)).subtract(orZero(d1.get(point)));
      if (value.signum() != 0) {
        diff.put(point, value);
      }
    }
    if (diff.isEmpty()) {
      return "няма (фишовете се различават, сумите — не)";
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, BigDecimal> entry : diff.entrySet()) {
      if (out.length() > 0) {
        out.append("; ");
      }
      out.append("т. ").append(entry.getKey()).append(": ");
      if (entry.getValue().signum() > 0) {
        out.append('+');
      }
      out.append(entry.getValue().toPlainString());
    }
    return out.toString();
  }

  /** Носителят на фиша — договорът, или регистрацията на СОЛ. */
  static String carrierOf(BgPaySlip slip) {
    if (slip.getEmploymentContract() != null && slip.getEmploymentContract().getId() != null) {
      return "C" + slip.getEmploymentContract().getId();
    }
    if (slip.getBgSelfInsuredRegistration() != null
        && slip.getBgSelfInsuredRegistration().getId() != null) {
      return "R" + slip.getBgSelfInsuredRegistration().getId();
    }
    return "S" + slip.getId();
  }

  private static int byNumber(String a, String b) {
    try {
      return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
    } catch (NumberFormatException e) {
      return a.compareTo(b);
    }
  }

  private static boolean containsId(List<BgPaySlip> slips, Long id) {
    for (BgPaySlip slip : slips) {
      if (id.equals(slip.getId())) {
        return true;
      }
    }
    return false;
  }

  private static String label(BgPaySlip slip) {
    String reference = slip.getReference();
    return reference == null || reference.trim().isEmpty() ? "фиш #" + slip.getId() : reference;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? new ArrayList<>() : list;
  }
}
