package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgDeclarant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * „ТОЧНО ЕДИН ДЕКЛАРАТОР ПО ПОДРАЗБИРАНЕ НА ДРУЖЕСТВО“ — правилото, изказано веднъж.
 *
 * <p><b>Чиста функция, нула заявки</b> — по образеца на {@link BgPaySlipResolver}: пазачът се
 * проверява без Postgres, без Guice и без Hibernate.
 *
 * <p><b>⚠️ ЕДНО ПРАВИЛО, ДВА ОТГОВОРА, И ТОВА НЕ Е ДВОЙНО ПИСАНЕ.</b> Двата пътя на записа знаят
 * различно много и затова правят различно:
 *
 * <ul>
 *   <li><b>През самия {@code BgDeclarant}</b> записваният е ЕДИН и е недвусмислено онзи, когото
 *       човекът току-що е вдигнал. Там другите се СВАЛЯТ мълчаливо — виж {@code
 *       BgDeclarantManagementRepository}.
 *   <li><b>През решетката на формата Дружество</b> пристига цялата колекция и в нея могат да са
 *       вдигнати ДВА флага: щракнатият и заварения. Кой от тях е щракнатият, подадените данни НЕ
 *       казват — {@code JPA.edit} вече е нанесъл стойностите, а заявка към базата би дала
 *       auto-flush и би върнала вече променените обекти. Там се ОТКАЗВА с този отказ.
 * </ul>
 *
 * <p><b>⚠️ И ЗАЩО ОТКАЗ, А НЕ ИЗБОР.</b> Всяко правило „печели последният ред“ или „печели онзи,
 * който е бил false в базата“ познава едното щракване и не познава обратното — човекът вдига ПЪРВИЯ
 * ред, а системата мълчаливо оставя втория. Отказът не познава нищо, но и не лъже: казва точно кое
 * му пречи и кой ред да се свали.
 */
public final class BgDeclarantResolver {

  private BgDeclarantResolver() {}

  /** Вдигнатите декларатори в подадената колекция, в реда, в който са дошли. */
  public static List<BgDeclarant> defaults(List<BgDeclarant> declarants) {
    List<BgDeclarant> found = new ArrayList<>();
    if (declarants == null) {
      return found;
    }
    for (BgDeclarant declarant : declarants) {
      if (declarant != null && Boolean.TRUE.equals(declarant.getIsDefault())) {
        found.add(declarant);
      }
    }
    return found;
  }

  /**
   * Отказът при запис на дружество: {@code null}, ако вдигнатите са нула или един, инак съобщение с
   * имената на всички вдигнати.
   *
   * <p>⚠️ НУЛА ВДИГНАТИ НЕ Е ОТКАЗ. Дружество без декларатор по подразбиране е работно състояние —
   * декларацията пита за декларатора си при генериране. Инвариантът е „НЕ ПОВЕЧЕ от един“; „поне
   * един“ би спрял всяко дружество, което още не е стигнало дотам.
   */
  public static String refuseMultipleDefaults(List<BgDeclarant> declarants) {
    List<BgDeclarant> raised = defaults(declarants);
    if (raised.size() < 2) {
      return null;
    }
    StringBuilder names = new StringBuilder();
    for (BgDeclarant declarant : raised) {
      if (names.length() > 0) {
        names.append(", ");
      }
      names.append(label(declarant));
    }
    return "Точно един декларатор може да е „По подразбиране“ за дружеството, а вдигнати са "
        + raised.size()
        + ": "
        + names
        + ". Свалете отметката от излишните и запишете отново.";
  }

  // ---------------------------------------------------------------------------
  // Родителският път ПРЕДИ записа: onChange на колекцията в решетката на Дружеството.
  // ---------------------------------------------------------------------------

  /**
   * СВАЛЯ ОСТАНАЛИТЕ ОКОЛО НОВОПОВДИГНАТИЯ — и връща дали изобщо е пипнато.
   *
   * <p><b>⚠️ ТУК РАЗГРАНИЧИТЕЛЯТ СЪЩЕСТВУВА, А ПРИ ЗАПИСА — НЕ.</b> Това не противоречи на {@link
   * #refuseMultipleDefaults}, а е причината добавката да е възможна. При onChange НИЩО още не е
   * записано: {@code JPA.edit} не е минавал, няма мутирани обекти в контекста и следователно няма
   * какво да се flush-не. Тоест четенето от базата връща ПРЕДИШНОТО състояние, а новоповдигнатият е
   * онзи, който е вдигнат в подаденото, но НЕ е бил вдигнат в базата. При запис същото четене би
   * върнало вече променените обекти — оттам и отказът там.
   *
   * <p><b>⚠️ „НЯМА НОВОПОВДИГНАТ“ Е ТИХО НИЩО, А НЕ „сложи някой“.</b> Редакция по друго поле, нов
   * ред без флаг и просто сваляне на отметката минават оттук на всяко щракване. Да се форсира
   * default в такъв момент значи екранът да решава вместо човека; „поне един default“ не е
   * инвариантът (виж {@link #refuseMultipleDefaults}).
   *
   * <p><b>⚠️ И ДВАМА НОВОПОВДИГНАТИ СЪЩО СА НИЩО.</b> Тогава кой печели пак е неизвестно — точно
   * случаят, за който съществува отказът при записа. По-добре решетката да не пипне нищо, отколкото
   * да избере тихо.
   *
   * @param rows редовете както идват от контекста — ПРОМЕНЯТ СЕ НА МЯСТО, за да не се загубят
   *     полетата, които екранът вече е показал
   * @param persistedDefaultIds номерата на вдигнатите в базата ПРЕДИ това щракване
   */
  public static boolean clearOthersAroundNewlyRaised(
      List<Map<String, Object>> rows, Set<Long> persistedDefaultIds) {
    if (rows == null || rows.isEmpty()) {
      return false;
    }
    List<Map<String, Object>> newlyRaised = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if (row != null && isNewlyRaised(row, persistedDefaultIds)) {
        newlyRaised.add(row);
      }
    }
    if (newlyRaised.size() != 1) {
      return false;
    }
    Map<String, Object> winner = newlyRaised.get(0);
    boolean changed = false;
    for (Map<String, Object> row : rows) {
      if (row != null && row != winner && isRowDefault(row, persistedDefaultIds)) {
        row.put("isDefault", Boolean.FALSE);
        changed = true;
      }
    }
    return changed;
  }

  /** Вдигнат в подаденото, но не и в базата — включително чисто нов, незаписан ред. */
  static boolean isNewlyRaised(Map<String, Object> row, Set<Long> persistedDefaultIds) {
    if (!isRowDefault(row, persistedDefaultIds)) {
      return false;
    }
    Long id = rowId(row);
    return id == null || !persistedDefaultIds.contains(id);
  }

  /**
   * Вдигнат ли е редът.
   *
   * <p>⚠️ ЛИПСВАЩИЯТ КЛЮЧ НЕ Е „НЕ“. Ред, подаден като бланка ({@code {id, version}}), не носи
   * флага си — прочетен като „не е вдигнат“, старият default би останал непокътнат и екранът щеше
   * да покаже ДВА. Затова липсващият ключ пада на състоянието в БАЗАТА.
   */
  static boolean isRowDefault(Map<String, Object> row, Set<Long> persistedDefaultIds) {
    if (row.containsKey("isDefault")) {
      Object value = row.get("isDefault");
      return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
    Long id = rowId(row);
    return id != null && persistedDefaultIds.contains(id);
  }

  /** Номерът на реда, или {@code null} за нов/незаписан (Axelor го подава и като отрицателен). */
  static Long rowId(Map<String, Object> row) {
    Object id = row.get("id");
    if (!(id instanceof Number)) {
      return null;
    }
    long value = ((Number) id).longValue();
    return value > 0L ? Long.valueOf(value) : null;
  }

  /** Име за съобщението: имената на декларатора, а при празни — обяснение вместо „null“. */
  private static String label(BgDeclarant declarant) {
    String names = declarant.getDeclarantNames();
    return names == null || names.trim().isEmpty() ? "(без име)" : names.trim();
  }
}
