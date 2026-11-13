package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.hr.db.Employee;

/**
 * Дава на регистрацията ИМЕ за показване при всеки запис.
 *
 * <p><b>Защо на ниво repository, а не в изгледа.</b> Същият довод като при {@code
 * BgDeclarantManagementRepository} и {@code BgPaySlipManagementRepository}: формата е любезност към
 * човека пред екрана, а REST, импортът и партидата минават покрай нея. Записана през API,
 * регистрацията щеше да се показва като празен ред във всяко m2o поле — включително на фиша, където
 * точно тя казва чий е той.
 *
 * <p>⚠️ ИМЕТО СЕ ПРЕСМЯТА НАНОВО ПРИ ВСЕКИ ЗАПИС, а не само при създаване: смененият вид осигурен
 * или сменената година на обхвата правят старото име невярно, а невярното име е по-лошо от
 * липсващото — то не изглежда като липса.
 */
public class BgSelfInsuredRegistrationManagementRepository
    extends BgSelfInsuredRegistrationRepository {

  @Override
  public BgSelfInsuredRegistration save(BgSelfInsuredRegistration entity) {
    entity.setFullName(nameOf(entity));
    return super.save(entity);
  }

  /**
   * „ПЕТРОВ · вид 12 · 2026“ — лицето, кодът и годината на обхвата.
   *
   * <p>Трите заедно са и уникалното ограничение на ентитета, тоест две регистрации с еднакво име не
   * могат да съществуват. Име, което не различава два реда, не върши работата си.
   */
  protected String nameOf(BgSelfInsuredRegistration entity) {
    Employee employee = entity == null ? null : entity.getEmployee();
    String person =
        employee == null || employee.getName() == null || employee.getName().trim().isEmpty()
            ? "СОЛ"
            : employee.getName().trim();
    String code =
        entity == null || entity.getBgInsuredTypeCode() == null
            ? "?"
            : entity.getBgInsuredTypeCode().trim();
    // ⚠️ Генерираният getter за Integer връща 0, не null, за непопълнена колона
    // (BgDefaultedGetterTest) — затова въпросът е „нула ли е“, а не „null ли е“.
    int year = entity == null ? 0 : entity.getBgScopeYear();
    String yearPart = year == 0 ? "?" : String.valueOf(year);
    return person + " · вид " + code + " · " + yearPart;
  }
}
