package com.axelor.apps.bglocalization.service.fatca;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFatcaAccount;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.db.BgFatcaReport;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * ОТ ЗАПИСАНОТО КЪМ СЪДЪРЖАНИЕТО НА ФАЙЛА — без база, без мрежа.
 *
 * <p><b>⚠️ ИДЕНТИФИКАТОРИТЕ СЕ РАЖДАТ ТУК.</b> {@code MessageRefId} и всеки {@code DocRefId} са
 * „GIIN.уникална стойност“ (Pub 5124, т. 3 и 4.2 — препоръчаният формат). Префиксът GIIN ги прави
 * отделни за всяко дружество без споделен брояч; уникалната част идва от {@code ids} — в работа
 * UUID, в тестовете предвидима поредица.
 */
public final class BgFatcaFileCollector {

  static final String DEFAULT_COUNTRY = "BG";

  private BgFatcaFileCollector() {}

  public static BgFatcaFileContent collect(
      BgFatcaReport report, BgFatcaProfile profile, Supplier<String> ids, LocalDateTime now) {
    String giin = profile == null ? null : BgFatcaGiin.normalize(profile.getGiin());
    String prefix = giin == null ? "" : giin + ".";
    Company company = report.getCompany();

    BgFatcaFileContent c =
        new BgFatcaFileContent()
            .setReportYear(report.getReportYear())
            .setTestFile(Boolean.TRUE.equals(report.getTestFile()))
            .setGiin(giin)
            .setFiName(companyName(company))
            .setMessageRefId(prefix + ids.get())
            .setFiDocRefId(prefix + ids.get())
            .setTimestamp(now);
    fiAddress(company == null ? null : company.getAddress(), c);

    List<BgFatcaAccount> accounts =
        report.getAccountList() == null
            ? new ArrayList<>()
            : new ArrayList<>(report.getAccountList());
    accounts.sort(Comparator.comparing(a -> a.getId() == null ? Long.MAX_VALUE : a.getId()));
    for (BgFatcaAccount a : accounts) {
      c.addAccount(account(a, prefix + ids.get()));
    }
    if (c.isNil()) {
      c.setNilDocRefId(prefix + ids.get());
    }
    return c;
  }

  static BgFatcaFileContent.Account account(BgFatcaAccount a, String docRefId) {
    return new BgFatcaFileContent.Account()
        .setId(a.getId())
        .setAccountNumber(a.getAccountNumber())
        .setClosed(Boolean.TRUE.equals(a.getClosedAccount()))
        .setHolderType(a.getHolderTypeSelect())
        .setFirstName(a.getFirstName())
        .setLastName(a.getLastName())
        .setBirthDate(a.getBirthDate())
        .setOrganisationName(a.getOrganisationName())
        .setAcctHolderType(a.getAcctHolderTypeSelect())
        .setUsTin(a.getUsTin())
        .setCountryCode(a.getCountry() == null ? null : upper(a.getCountry().getAlpha2Code()))
        .setCity(a.getCity())
        .setAddressFree(a.getAddressFree())
        .setBalance(a.getBalance())
        .setCurrencyCode(a.getCurrency() == null ? null : upper(a.getCurrency().getCodeISO()))
        .setDividends(a.getDividendsAmount())
        .setInterest(a.getInterestAmount())
        .setGrossProceeds(a.getGrossProceedsAmount())
        .setOther(a.getOtherPaymentsAmount())
        .setDocRefId(docRefId);
  }

  /** Наименованието на дружеството — от партньора му, иначе от самото дружество. */
  static String companyName(Company company) {
    if (company == null) {
      return null;
    }
    if (company.getPartner() != null && trim(company.getPartner().getName()) != null) {
      return trim(company.getPartner().getName());
    }
    return trim(company.getName());
  }

  /** Адресът на институцията — по реда на SAF-T: улица, после населено място, после държава. */
  static void fiAddress(Address address, BgFatcaFileContent c) {
    if (address == null) {
      c.setFiCountryCode(DEFAULT_COUNTRY);
      return;
    }
    String street = trim(address.getStreetName());
    if (street == null) {
      street = trim(address.getAddressL4());
    }
    String city = address.getCity() == null ? null : trim(address.getCity().getName());
    if (city == null) {
      city = trim(address.getTownName());
    }
    String country =
        address.getCountry() == null ? null : upper(address.getCountry().getAlpha2Code());
    c.setFiStreet(street)
        .setFiPostCode(trim(address.getZip()))
        .setFiCity(city)
        .setFiCountryCode(country == null ? DEFAULT_COUNTRY : country);
  }

  private static String upper(String value) {
    String t = trim(value);
    return t == null ? null : t.toUpperCase(java.util.Locale.ROOT);
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
