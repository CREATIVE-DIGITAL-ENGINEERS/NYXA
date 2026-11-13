package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.service.BgDeclarantResolver;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftBankAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftContact;
import com.axelor.apps.bglocalization.service.saft.dto.SaftHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftOwnership;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPersonName;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxRegistration;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.studio.db.AppBgLocalization;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HEADER-ЪТ НА SAF-T ОТ ДРУЖЕСТВОТО, КОНФИГА И ПЕРИОДА — по книгата на НАП, не по образеца.
 *
 * <p><b>Видът и периодът идват като {@link BgSaftSelection}</b>: оттам S.H.11 (M/A/D) и клонът на
 * SelectionCriteria — периодите за месечния и годишния файл, датите за файла при поискване. Всичко
 * останало в Header-а е едно и също за трите вида.
 *
 * <p><b>Чиста функция, нула заявки</b> — по образеца на {@link BgDeclarantResolver}: всичко, което
 * чете, му се подава, тоест се проверява без Postgres.
 *
 * <p><b>⚠️ КНИГАТА Е АВТОРИТЕТЪТ, ОБРАЗЕЦЪТ Е ИЛЮСТРАЦИЯ.</b> Структурната таблица
 * (SAF-T_BG_Structure_Definition_V_1.0.2.xlsx, лист „5. Structures“) казва S.TID.1 {@code
 * TaxRegistrationNumber} = „Регистрационен номер по ДДС“ и S.TID.3 {@code TaxNumber} = данъчният
 * номер; месечният образец на НАП пише ЕИК в първото. Тук е книгата.
 *
 * <p><b>⚠️ ЛИПСАТА ИЗЛИЗА ПРАЗНА И С ПРЕДУПРЕЖДЕНИЕ, НЕ ИЗМИСЛЕНА.</b> Всеки задължителен елемент
 * без източник получава {@code ""} и находка. Изключенията са две и са по книгата, не по удобство:
 * „00“ за предприятието майка (S.O.7–S.O.11) и „BG“ за държава на адрес без държава — дружество по
 * този модул е българско, а празна държава чупи схемата (ISO код от точно два знака).
 *
 * <p><b>⚠️ S.O.7 = „00“ Е ТЪЛКУВАНЕ — ДА СЕ ПОТВЪРДИ.</b> Бележката към S.O.7 изрежда буквално
 * „елементи от S.O.8 до S.O.11“, а S.O.7 също е задължителен (1..n в схемата). Тук „00“ получават и
 * петте.
 */
public final class BgSaftHeaderAssembler {

  /** „BG“ и цифри — ДДС номерът вместо ЕИК. */
  private static final Pattern BG_PREFIXED = Pattern.compile("(?i)BG\\s*[0-9]+");

  /** S.TID.2 по номенклатурата VAT_TaxType: „Данъчно задължено лице, регистрирано за ДДС“. */
  public static final String VAT_TAX_TYPE_REGISTERED = "100010";

  /** S.TID.4 — стойността по подразбиране от книгата. */
  public static final String TAX_AUTHORITY = "NRA";

  /** S.H.9 — единствената стойност, която схемата допуска. */
  public static final String DEFAULT_CURRENCY = "EUR";

  /** S.O.1 „5 — Не“: подразбирането за празно поле. */
  public static final String NOT_IN_GROUP = "5";

  /** H.2 „A — търговски предприятия“: подразбирането за празно поле. */
  public static final String COMMERCIAL_BASIS = "A";

  /** „Неприложимо“ в елементите за предприятието майка (бележката към S.O.7). */
  public static final String NOT_APPLICABLE = "00";

  static final String COUNTRY_BG = "BG";

  private BgSaftHeaderAssembler() {}

  /** Header-ът и находките по пътя. */
  public static final class Result {
    private final SaftHeader header;
    private final List<BgDeclarationFileFinding> findings;

    private Result(SaftHeader header, List<BgDeclarationFileFinding> findings) {
      this.header = header;
      this.findings = Collections.unmodifiableList(findings);
    }

    public SaftHeader getHeader() {
      return header;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param company дружеството, което подава файла
   * @param app конфигът на модула (софтуерът, S.H.5–S.H.7); {@code null} = неконфигуриран
   * @param period отчетният период — месечният файл ({@link BgSaftSelection#monthly(Period)})
   * @param createdOn датата на създаване на файла (S.H.4)
   */
  public static Result assemble(
      Company company, AppBgLocalization app, Period period, LocalDate createdOn) {
    if (company == null) {
      throw new IllegalArgumentException("SAF-T: Header без дружество");
    }
    return assemble(company, app, BgSaftSelection.monthly(period), createdOn);
  }

  /**
   * @param company дружеството, което подава файла
   * @param app конфигът на модула (софтуерът, S.H.5–S.H.7); {@code null} = неконфигуриран
   * @param selection видът на файла и периодът му
   * @param createdOn датата на създаване на файла (S.H.4)
   */
  public static Result assemble(
      Company company, AppBgLocalization app, BgSaftSelection selection, LocalDate createdOn) {
    if (company == null) {
      throw new IllegalArgumentException("SAF-T: Header без дружество");
    }
    if (selection == null) {
      throw new IllegalArgumentException("SAF-T: Header без вид и период на файла");
    }
    if (createdOn == null) {
      throw new IllegalArgumentException("SAF-T: Header без дата на създаване");
    }
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    Partner partner = company.getPartner();

    SaftHeader header =
        new SaftHeader()
            // S.H.1 по таблицата „версия → от дата“ — същата, по която се избира и схемата.
            .auditFileVersion(BgSaftXsd.forDate(createdOn).getAuditFileVersion())
            .auditFileCountry(COUNTRY_BG)
            .auditFileDateCreated(createdOn)
            .softwareCompanyName(
                software(
                    app == null ? null : app.getSaftSoftwareCompanyName(),
                    "SAFT_HEADER_SOFTWARE_COMPANY_MISSING",
                    "Не е попълнена софтуерната компания (S.H.5) в настройките на модула.",
                    findings))
            .softwareId(
                software(
                    app == null ? null : app.getSaftSoftwareId(),
                    "SAFT_HEADER_SOFTWARE_ID_UNCONFIRMED",
                    "Наименованието на софтуера (S.H.6) не е потвърдено — да се потвърди и попълни"
                        + " в настройките на модула.",
                    findings))
            .softwareVersion(
                software(
                    app == null ? null : app.getSaftSoftwareVersion(),
                    "SAFT_HEADER_SOFTWARE_VERSION_MISSING",
                    "Не е попълнена версията на софтуера (S.H.7) в настройките на модула.",
                    findings))
            .company(company(company, partner, findings))
            .ownership(ownership(company, findings))
            .defaultCurrencyCode(DEFAULT_CURRENCY)
            .headerComment(selection.getKind().getHeaderComment())
            .taxAccountingBasis(
                blank(company.getBgSaftTaxAccountingBasis())
                    ? COMMERCIAL_BASIS
                    : company.getBgSaftTaxAccountingBasis().trim());
    selectionCriteria(header, selection);
    return new Result(header, findings);
  }

  /**
   * S.H.10 — ЕДИН клон: периодите (S.SC.5–8) за месечния и годишния файл, датите (S.SC.3–4) за
   * файла при поискване. Годишният излиза 1–12 на годината, защото {@link
   * BgSaftSelection#annual(int)} е цялата календарна година.
   */
  private static void selectionCriteria(SaftHeader header, BgSaftSelection selection) {
    LocalDate from = selection.getFromDate();
    LocalDate to = selection.getToDate();
    if (selection.byPeriods()) {
      header
          .periodStart(from.getMonthValue())
          .periodStartYear(from.getYear())
          .periodEnd(to.getMonthValue())
          .periodEndYear(to.getYear());
    } else {
      header.selectionStartDate(from).selectionEndDate(to);
    }
  }

  // ─────────────── Company ───────────────

  private static SaftCompanyHeader company(
      Company company, Partner partner, List<BgDeclarationFileFinding> findings) {
    String eik = partner == null ? null : trim(partner.getRegistrationCode());
    // „BG“ пред ЕИК е ДДС номерът, не ЕИК: „10BG…“ НАП отказва (QA IV.2.4, с. 96) — и в ID-то на
    // подаващия в дневника, и в името на файла. Махнат, с предупреждение към партньора; формата
    // (девет цифри, Прил. 3 т. I.2) проверява BgSaftFileName.
    if (eik != null && BG_PREFIXED.matcher(eik).matches()) {
      findings.add(
          BgDeclarationFileFinding.warning(
                  "SAFT_HEADER_EIK_BG_PREFIX_REMOVED",
                  "ЕИК на дружеството е с префикс „BG“ („"
                      + eik
                      + "“) — подаден без него, защото „10BG…“ НАП отказва (QA IV.2.4, с. 96); да"
                      + " се поправи в партньора на дружеството.")
              .at(BgSaftRef.PARTNER, partner.getId()));
      eik = eik.substring(2).trim();
    }
    if (eik == null) {
      // ГРЕШКА, не предупреждение: ЕИК е идентичността на подаващия и името на самия файл.
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_HEADER_EIK_MISSING",
              "Дружеството няма ЕИК (S.CMH.1) — файлът не може да бъде идентифициран."));
    }
    SaftCompanyHeader header =
        new SaftCompanyHeader()
            .registrationNumber(eik == null ? "" : eik)
            .name(company.getName() == null ? "" : company.getName())
            .addAddress(address(company.getAddress(), findings))
            .addContact(contact(company, partner, findings));
    String vatNumber = partner == null ? null : trim(partner.getTaxNbr());
    if (vatNumber != null) {
      header.addTaxRegistration(
          new SaftTaxRegistration()
              .taxRegistrationNumber(vatNumber)
              .taxType(VAT_TAX_TYPE_REGISTERED)
              .taxNumber(eik)
              .taxAuthority(TAX_AUTHORITY));
    }
    header.addBankAccount(new SaftBankAccount().ibanNumber(iban(company)));
    return header;
  }

  private static SaftAddress address(Address address, List<BgDeclarationFileFinding> findings) {
    if (address == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_ADDRESS_MISSING", "Дружеството няма адрес (S.CMH.3)."));
      return new SaftAddress().city("").country(COUNTRY_BG);
    }
    String street = trim(address.getStreetName());
    if (street == null) {
      street = trim(address.getAddressL4());
    }
    String city = address.getCity() != null ? trim(address.getCity().getName()) : null;
    if (city == null) {
      city = trim(address.getTownName());
    }
    if (city == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_CITY_MISSING", "Адресът на дружеството няма населено място."));
    }
    String country =
        address.getCountry() == null ? null : trim(address.getCountry().getAlpha2Code());
    if (country == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_COUNTRY_DEFAULTED",
              "Адресът на дружеството няма държава — подадена е BG."));
      country = COUNTRY_BG;
    }
    return new SaftAddress()
        .streetName(street)
        .number(trim(address.getBuildingNumber()))
        .city(city == null ? "" : city)
        .postalCode(trim(address.getZip()))
        .country(country)
        .addressType("StreetAddress");
  }

  /**
   * Контактът е ДЕКЛАРАНТЪТ ПО ПОДРАЗБИРАНЕ; телефонът и имейлът — от партньора на дружеството.
   *
   * <p>Имената в {@link BgDeclarant} са един низ: първата дума е FirstName (S.PN.2), останалото —
   * LastName (S.PN.5), за да не се губи бащиното име. Длъжността (S.PN.8 OtherTitles) е {@code
   * declarantCapacity}.
   */
  private static SaftContact contact(
      Company company, Partner partner, List<BgDeclarationFileFinding> findings) {
    List<BgDeclarant> defaults = BgDeclarantResolver.defaults(company.getBgDeclarantList());
    BgDeclarant declarant = defaults.isEmpty() ? null : defaults.get(0);
    String first = "";
    String last = "";
    String title = "";
    if (declarant == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_CONTACT_MISSING",
              "Дружеството няма декларатор по подразбиране — лицето за контакт (S.CMH.4) е"
                  + " празно."));
    } else {
      String[] names = splitNames(declarant.getDeclarantNames());
      first = names[0];
      last = names[1];
      if (first.isEmpty() || last.isEmpty()) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_HEADER_CONTACT_NAME_INCOMPLETE",
                "Имената на декларатора не дават и име, и фамилия (S.PN.2, S.PN.5)."));
      }
      String capacity = trim(declarant.getDeclarantCapacity());
      if (capacity == null) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_HEADER_CONTACT_TITLE_MISSING",
                "Деклараторът няма длъжност (S.PN.8 OtherTitles)."));
      } else {
        title = capacity;
      }
    }
    SaftContact contact =
        new SaftContact()
            .contactPerson(
                new SaftPersonName().firstName(first).lastName(last).addOtherTitle(title));

    // ⚠️ По книгата (ContactHeaderStructure, S.CTH.2/4) телефонът и имейлът са ЗАДЪЛЖИТЕЛНИ за
    // Header-а, а XSD-то не го налага. Затова липсата им е предупреждение, но елемент не се пише.
    String phone = partner == null ? null : trim(partner.getFixedPhone());
    if (phone == null && partner != null) {
      phone = trim(partner.getMobilePhone());
    }
    if (phone == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_PHONE_MISSING", "Дружеството няма телефон (S.CTH.2)."));
    } else {
      contact.addTelephone(phone);
    }
    String email =
        partner == null || partner.getEmailAddress() == null
            ? null
            : trim(partner.getEmailAddress().getAddress());
    if (email == null) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_HEADER_EMAIL_MISSING", "Дружеството няма имейл (S.CTH.4)."));
    } else {
      contact.addEmail(email);
    }
    return contact;
  }

  /**
   * IBAN-ът на подаващия — нормализиран (без интервали, с главни букви); празен, ако няма.
   * Задължителността и валидността са на {@link BgSaftIbanCheck} (ГРЕШКИ), не тук.
   */
  private static String iban(Company company) {
    BankDetails main = company.getDefaultBankDetails();
    if (main != null && BgSaftIbanCheck.normalize(main.getIban()) != null) {
      return BgSaftIbanCheck.normalize(main.getIban());
    }
    if (company.getBankDetailsList() != null) {
      for (BankDetails details : company.getBankDetailsList()) {
        if (details != null
            && !Boolean.FALSE.equals(details.getActive())
            && BgSaftIbanCheck.normalize(details.getIban()) != null) {
          return BgSaftIbanCheck.normalize(details.getIban());
        }
      }
    }
    return "";
  }

  // ─────────────── Ownership ───────────────

  private static SaftOwnership ownership(Company company, List<BgDeclarationFileFinding> findings) {
    String group =
        blank(company.getBgSaftIsPartOfGroup())
            ? NOT_IN_GROUP
            : company.getBgSaftIsPartOfGroup().trim();
    SaftOwnership ownership = new SaftOwnership().isPartOfGroup(group);
    boolean partOfGroup = "3".equals(group) || "4".equals(group);
    if (!partOfGroup) {
      return ownership
          .addUltimateOwnerNameCyrillicBg(NOT_APPLICABLE)
          .addUltimateOwnerUicBg(NOT_APPLICABLE)
          .addUltimateOwnerNameCyrillicForeign(NOT_APPLICABLE)
          .addUltimateOwnerNameLatinForeign(NOT_APPLICABLE)
          .addCountryForeign(NOT_APPLICABLE);
    }
    String nameBg = trim(company.getBgSaftUltimateOwnerNameBg());
    String nameForeignCyr = trim(company.getBgSaftUltimateOwnerNameForeignCyr());
    String nameForeignLat = trim(company.getBgSaftUltimateOwnerNameForeignLat());
    if (nameBg == null && nameForeignCyr == null && nameForeignLat == null) {
      // ГРЕШКА: при 3 и 4 книгата прави S.O.7–S.O.11 задължителни, а „00“ навсякъде би казало,
      // че дружеството е част от група без предприятие майка.
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_HEADER_GROUP_OWNER_MISSING",
              "Дружеството е част от група (S.O.1 = "
                  + group
                  + "), но няма попълнено крайно предприятие майка (S.O.7–S.O.10)."));
    }
    // Попълнено е или българското, или чуждестранното; неприложимите получават „00“ (S.O.7).
    return ownership
        .addUltimateOwnerNameCyrillicBg(orNotApplicable(nameBg))
        .addUltimateOwnerUicBg(orNotApplicable(trim(company.getBgSaftUltimateOwnerEikBg())))
        .addUltimateOwnerNameCyrillicForeign(orNotApplicable(nameForeignCyr))
        .addUltimateOwnerNameLatinForeign(orNotApplicable(nameForeignLat))
        .addCountryForeign(orNotApplicable(trim(company.getBgSaftUltimateOwnerCountry())));
  }

  // ─────────────── помощници ───────────────

  private static String software(
      String value, String code, String message, List<BgDeclarationFileFinding> findings) {
    String v = trim(value);
    if (v == null) {
      findings.add(BgDeclarationFileFinding.warning(code, message));
      return "";
    }
    return v;
  }

  /** Първата дума и останалото; празни низове, когато няма. */
  static String[] splitNames(String names) {
    String t = trim(names);
    if (t == null) {
      return new String[] {"", ""};
    }
    String[] parts = t.split("\\s+", 2);
    return new String[] {parts[0], parts.length > 1 ? parts[1] : ""};
  }

  private static String orNotApplicable(String value) {
    return value == null ? NOT_APPLICABLE : value;
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trim(String value) {
    return blank(value) ? null : value.trim();
  }
}
