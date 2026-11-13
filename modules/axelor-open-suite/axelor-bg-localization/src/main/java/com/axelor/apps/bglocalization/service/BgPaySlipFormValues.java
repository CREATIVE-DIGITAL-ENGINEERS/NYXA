package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * КАКВО ОТ ФОРМАТА ВЛИЗА В „ПРЕИЗЧИСЛИ“ ({@code BgPaySlipService#calculateAndSave}).
 *
 * <p>Стойностите идват от клиента и {@code JPA.edit} ги прилага всичките — включително статуса,
 * отпечатъка и признаците на копието — и РЕДАКТИРА ВЛОЖЕНИТЕ ЗАПИСИ: препратка, пратена с {@code
 * version}, се третира като редакция на самия свързан запис ({@code JPA#_edit}). Тоест {@code
 * period: {id, version, statusSelect}} щеше да смени статуса на ПЕРИОДА през бутона на фиша.
 *
 * <p>Затова:
 *
 * <ul>
 *   <li>{@link #SYSTEM} никога не се приемат — пише ги само услугата (преходите, пресмятането,
 *       ретро потокът, плащането, одитът);
 *   <li>{@link #FROZEN} не се приемат за РЕДОВЕН фиш — пресмятането ги замразява наново от
 *       договора; за коригиращо или заличаващо копие (по признака ОТ БАЗАТА) те СА редакцията на
 *       оператора ({@code readonlyIf="!bgIsCorrection …"} във формата) и се одитират при записа
 *       ({@code BgPaySlipRegimeAudit}) — изрязани, редакцията щеше да изчезне тихо;
 *   <li>всяка препратка — и на фиша, и на перата му — се свежда до {@code {id}}: свързаният запис
 *       се ИЗБИРА, не се редактира. Т. 9 живее върху болничния лист, тоест до нея формата на фиша
 *       вече няма път.
 * </ul>
 *
 * <p>⚠️ Статусът не се приема, но и не се пренебрегва мълчаливо: различен от DRAFT е отказ (виж
 * {@link #forgedStatusRefusal}).
 */
public final class BgPaySlipFormValues {

  private BgPaySlipFormValues() {}

  /** Полетата, които пише само услугата. */
  public static final Set<String> SYSTEM =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "statusSelect",
                  "bgCancelReason",
                  "bgCalcFingerprint",
                  "bgGeneratorNotes",
                  "bgGeneratorGaps",
                  "bgGeneratorRefusalCount",
                  "bgGeneratedAt",
                  "bgSickDaysRefusal",
                  "bgCorrectionOf",
                  "bgIsCorrection",
                  "bgDeletionOf",
                  "bgIsDeletion",
                  "bgIsHistoricalImport",
                  "bgPayrollMove",
                  "bgPayrollMoveNote",
                  "bgPaymentBankOrder",
                  "bgRegimeSource",
                  "bgRegimeChangeList",
                  "bgRegimeChangeSummary")));

  /**
   * ЗАМРАЗЕНОТО — режимът, дните и използваното от таваните. Освен изброените, всяко поле с префикс
   * {@code bgRegime}, което не е в {@link #SYSTEM}.
   */
  public static final Set<String> FROZEN =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "bgLeaveDays161",
                  "bgLeaveDays162",
                  "bgLeaveDays163",
                  "bgLeaveDays164",
                  "bgLeaveDays165",
                  "bgLeaveDays16A",
                  "bgLeaveDaysOutside",
                  "bgLeaveDaysFrozenAt",
                  "bgArt177LeaveDays",
                  "bgUnpaidLeaveDaysInD1",
                  "bgCarveDaysFrozenAt",
                  "bgCapUsageList",
                  "bgCapUsageFrozenAt")));

  static boolean isFrozen(String name) {
    return FROZEN.contains(name) || (name.startsWith("bgRegime") && !SYSTEM.contains(name));
  }

  /**
   * Статус, пратен от формата и различен от DRAFT — причина за отказ, или {@code null}.
   * „Преизчисли“ е бутон на черновата; друг статус в заявката е подправена или остаряла форма, не
   * грешка, която да се поправи тихо.
   */
  public static String forgedStatusRefusal(Map<String, Object> values) {
    Object status = values == null ? null : values.get("statusSelect");
    if (status == null || BgPaySlipResolver.DRAFT.equals(String.valueOf(status))) {
      return null;
    }
    return "„Преизчисли“ не сменя статуса: формата прати „"
        + status
        + "“, а статусът се сменя само с бутоните на прехода (Потвърди, Откажи, Коригирай…)."
        + " Презаредете фиша.";
  }

  /**
   * Стойностите, които {@code JPA.edit} получава.
   *
   * @param retro коригиращо или заличаващо копие — ОТ БАЗАТА, не от формата
   */
  public static Map<String, Object> accepted(Map<String, Object> values, boolean retro) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (values == null) {
      return out;
    }
    Mapper mapper = Mapper.of(BgPaySlip.class);
    for (Map.Entry<String, Object> e : values.entrySet()) {
      String name = e.getKey();
      if (SYSTEM.contains(name) || (!retro && isFrozen(name))) {
        continue;
      }
      out.put(name, onlyChosen(mapper.getProperty(name), e.getValue()));
    }
    return out;
  }

  /**
   * Препратките — до {@code {id}}; колекциите — елемент по елемент, със същото правило за техните
   * препратки. Скаларите — каквито са.
   */
  @SuppressWarnings("unchecked")
  private static Object onlyChosen(Property p, Object value) {
    if (p == null || value == null) {
      return value;
    }
    if (p.isReference()) {
      return value instanceof Map ? idOnly((Map<String, Object>) value) : value;
    }
    if (p.isCollection() && value instanceof Collection) {
      Class<? extends Model> target = (Class<? extends Model>) p.getTarget();
      Mapper itemMapper = target == null ? null : Mapper.of(target);
      List<Object> items = new ArrayList<>();
      for (Object item : (Collection<Object>) value) {
        items.add(
            item instanceof Map && itemMapper != null
                ? item((Map<String, Object>) item, itemMapper)
                : item);
      }
      return items;
    }
    return value;
  }

  /** Елементът на колекция (перо, ред от използваното): скаларите му, препратките му — до id. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> item(Map<String, Object> values, Mapper mapper) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : values.entrySet()) {
      Property p = mapper.getProperty(e.getKey());
      if (p != null && p.isCollection()) {
        // ⚠️ Колекция на перото не идва от формата на фиша; пратена, тя би редактирала трето ниво.
        continue;
      }
      if (p != null && p.isReference() && e.getValue() instanceof Map) {
        out.put(e.getKey(), idOnly((Map<String, Object>) e.getValue()));
      } else {
        out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  /** Само идентификаторът: без {@code version} {@code JPA.edit} избира записа и не го пипа. */
  private static Map<String, Object> idOnly(Map<String, Object> reference) {
    Object id = reference.get("id");
    if (id == null) {
      // Нов свързан запис от формата на фиша няма къде да се роди — пратен, той се отказва.
      throw new BgCalculationRefusedException(
          "„Преизчисли“ не създава свързани записи (договор, период, вид перо…) — изберете"
              + " съществуващ и повторете.");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    return out;
  }
}
