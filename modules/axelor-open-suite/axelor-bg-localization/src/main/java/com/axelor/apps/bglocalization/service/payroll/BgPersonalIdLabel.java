package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.hr.db.Employee;

/**
 * „ЕГН 7501011016“ / „ЛНЧ 1001234567“ — личният идентификатор СЪС своя вид, за ЧЕТЕНЕ от човек.
 *
 * <p>⚠️ ВИДЪТ Е КОД, НЕ ДУМА. {@code Employee.bgPersonalIdType} пази стойността от {@code
 * bg.personal.id.type.select}: „1“ ЕГН, „2“ ЛНЧ. Слепен пред номера, САМИЯТ КОД дава „1
 * 7501011016“, което се чете като ЕГН с интервал. Данните и файловете за НАП/НОИ (Д1 т. 5, Д6,
 * Прил. 9, ЕТЗ) не ползват този етикет: те взимат само номера.
 *
 * <p>Само за показване. Непознат или празен вид не се показва изобщо — по-добре само номерът,
 * отколкото цифра, която прилича на част от него.
 */
public final class BgPersonalIdLabel {

  private BgPersonalIdLabel() {}

  /** Думата за вида: „ЕГН“, „ЛНЧ“ или празно. */
  public static String kind(String type) {
    String t = type == null ? "" : type.trim();
    if ("1".equals(t)) {
      return "ЕГН";
    }
    if ("2".equals(t)) {
      return "ЛНЧ";
    }
    return "";
  }

  /** „ЕГН 7501011016“; празно, когато номер няма. */
  public static String of(String type, String number) {
    if (number == null || number.trim().isEmpty()) {
      return "";
    }
    String kind = kind(type);
    return (kind.isEmpty() ? "" : kind + " ") + number.trim();
  }

  public static String of(Employee employee) {
    return employee == null
        ? ""
        : of(employee.getBgPersonalIdType(), employee.getBgPersonalIdNumber());
  }
}
