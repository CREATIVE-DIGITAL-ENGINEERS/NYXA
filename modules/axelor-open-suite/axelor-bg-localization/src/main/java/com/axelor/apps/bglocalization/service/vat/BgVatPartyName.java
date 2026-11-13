package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;

/**
 * ИМЕТО НА ЛИЦЕ В ДДС ФАЙЛОВЕТЕ ЗА НАП — контрагентът (02-07 / 03-09, позиции 88–137) и
 * регистрираното лице (DEKLAR позиции 16–65, VIES ред VTR). Едно правило за всички.
 *
 * <ul>
 *   <li>Юридическо лице — {@code Partner.name}: регистрираното име с правната форма („… ООД“).
 *   <li>Физическо лице — име и фамилия ({@code firstName} + {@code name}).
 * </ul>
 *
 * <p>⚠️ НЕ {@code Partner.fullName}: в Axelor той е „код - име“ („P0019 - …“), а кодът в полето на
 * името спира файла на portal.nra.bg (проверено на 11.11.2026). И НЕ {@code Company.name}: това е
 * краткото вътрешно име на дружеството, без правната форма.
 */
public final class BgVatPartyName {

  private BgVatPartyName() {}

  /** Името на партньора за файла; {@code null}, когато партньор няма. */
  public static String of(Partner partner) {
    if (partner == null) {
      return null;
    }
    return of(partner.getPartnerTypeSelect(), partner.getFirstName(), partner.getName());
  }

  /**
   * Регистрираното име на дружеството — от партньора му; без партньор (или с празно име) — {@code
   * Company.name}, за да не остане полето празно.
   */
  public static String ofCompany(Company company) {
    if (company == null) {
      return null;
    }
    String registered = of(company.getPartner());
    return isBlank(registered) ? company.getName() : registered;
  }

  /** Чистото правило: физическо лице — „име фамилия“, иначе — името. */
  public static String of(Integer partnerType, String firstName, String name) {
    String last = trim(name);
    if (Integer.valueOf(PartnerRepository.PARTNER_TYPE_INDIVIDUAL).equals(partnerType)) {
      String first = trim(firstName);
      if (first == null) {
        return last;
      }
      return last == null ? first : first + " " + last;
    }
    return last;
  }

  private static String trim(String value) {
    return isBlank(value) ? null : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
