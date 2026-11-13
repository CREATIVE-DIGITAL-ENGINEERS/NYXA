package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter;

/**
 * АДРЕСЪТ ЗА КОРЕСПОНДЕНЦИЯ НА РЕГИСТРИРАНОТО ЛИЦЕ — ЕДИНСТВЕНОТО място в ДДС, откъдето го четат
 * предварителното попълване, VIES.TXT (ред VTR) и печатът на справка-декларацията (секция А).
 *
 * <p><b>Правна основа</b> (ППЗДДС, ред. ДВ бр. 54/2024): чл. 117, ал. 2, т. 1 — „данни за
 * регистрираното лице - име/наименование, идентификационен номер по ДДС, <b>адрес за
 * кореспонденция</b>“; Прил. № 13, секция А — „Наименование и адрес за кореспонденция на
 * регистрираното лице“; Прил. № 15, секция VTR, поле 4 — „Адрес за кореспонденция“, 200 знака.
 *
 * <p><b>Правилото е изричен избор между ДВЕ полета на дружеството</b>, изписан и в етикета: „Адрес
 * за кореспонденция (ако е различен от „Адрес“)“; празно = „Адрес“ на дружеството. ⚠️ ПАРТНЬОРЪТ НА
 * ДРУЖЕСТВОТО НЕ СЕ ЧЕТЕ: ако предварителното попълване вземе само неговия основен адрес, при
 * партньор без адрес VIES тръгва към НАП с празен VTR — без отказ, — докато печатът тихо пада към
 * „Адрес“ на дружеството. Две места, два отговора.
 *
 * <p><b>Без тихо рязане.</b> Файлът реже до 200 знака ({@code padRight}); адрес над 200 знака е
 * отказ, който назовава полето, не изрязан адрес пред НАП. Дължината се мери след {@link
 * BgVatFieldFormatter#field} — точно текстът, който влиза във файла.
 */
public final class BgVatRegisteredAddress {

  /** Прил. № 15, VTR поле 4. */
  public static final int VTR_MAX_LENGTH = 200;

  /** Точното поле и формата, където се попълва — отказът ги назовава. */
  public static final String WHERE = "Дружество › „Адрес“ (или „Адрес за кореспонденция“)";

  private BgVatRegisteredAddress() {}

  /** Полето, което решава: „Адрес за кореспонденция“, ако е попълнено, иначе „Адрес“. */
  static Address sourceOf(Company company) {
    if (company == null) {
      return null;
    }
    Address correspondence = company.getBgVatCorrespondenceAddress();
    return text(correspondence) != null ? correspondence : company.getAddress();
  }

  /** Текстът на адреса — или {@code null}, ако и двете полета са празни. */
  public static String of(Company company) {
    return text(sourceOf(company));
  }

  /** Причината адресът да не става за VIES/справка-декларацията — или {@code null}. */
  public static String refusal(Company company) {
    String text = of(company);
    if (text == null) {
      return "Няма адрес за кореспонденция на регистрираното лице (ППЗДДС чл. 117, ал. 2, т. 1;"
          + " Прил. № 13, секция А; VIES ред VTR). Попълва се: "
          + WHERE
          + ".";
    }
    if (text.length() > VTR_MAX_LENGTH) {
      boolean fromCorrespondence =
          company.getBgVatCorrespondenceAddress() != null
              && sourceOf(company) == company.getBgVatCorrespondenceAddress();
      return "Адресът за кореспонденция на регистрираното лице е "
          + text.length()
          + " знака, а VIES (Прил. № 15, ред VTR, поле 4) допуска "
          + VTR_MAX_LENGTH
          + " — съкратете го; файлът не реже тихо. Поле: Дружество › „"
          + (fromCorrespondence ? "Адрес за кореспонденция" : "Адрес")
          + "“.";
    }
    return null;
  }

  private static String text(Address address) {
    if (address == null || address.getFullName() == null) {
      return null;
    }
    String value = BgVatFieldFormatter.field(address.getFullName());
    return value.isEmpty() ? null : value;
  }
}
