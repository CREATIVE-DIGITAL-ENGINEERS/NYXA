package com.axelor.apps.bglocalization.service.fatca;

import com.axelor.apps.bglocalization.db.BgFatcaAccount;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent.Account;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ЗАДЪЛЖИТЕЛНОТО ПРЕДИ ФАЙЛА — проверките, които схемата не прави или прави с неразбираемо
 * съобщение.
 *
 * <p><b>⚠️ СХЕМАТА Е СЛАБ ПАЗАЧ.</b> В FATCA XML почти всичко на титуляря е {@code minOccurs="0"}:
 * сметка без US TIN и без рождена дата минава схемата, а за съществуваща сметка без данъчен номер
 * се иска рождената дата. Затова тук е първата врата, а XSD — втората; и двете се минават преди да
 * има файл.
 *
 * <p><b>⚠️ НЕПЪЛЕН ФАЙЛ НЕ СЕ ПИШЕ.</b> Сметка на пасивно НФЧП със съществени американски
 * собственици (FATCA102) иска контролиращите лица (SubstantialOwner), а те още не се поддържат —
 * отказ с назована причина, не файл без тях.
 */
public final class BgFatcaFileValidator {

  /** Първата отчетна година по споразумението с САЩ (в сила от 30.06.2015, обмен от 2014 г.). */
  static final int FIRST_YEAR = 2014;

  /** Pub 5124: тези поредици се отхвърлят от IRS като възможна инжекция. */
  private static final String[] FORBIDDEN = {"--", "/*", "&#"};

  private BgFatcaFileValidator() {}

  public static List<BgDeclarationFileFinding> validate(BgFatcaFileContent c, LocalDate today) {
    List<BgDeclarationFileFinding> f = new ArrayList<>();
    reportingFi(c, today, f);
    Set<String> numbers = new HashSet<>();
    for (Account a : c.getAccounts()) {
      account(a, numbers, f);
    }
    if (c.isTestFile()) {
      f.add(
          BgDeclarationFileFinding.warning(
              "FATCA_TEST_FILE",
              "Тестов файл (DocTypeIndic FATCA11) — само за тестовата услуга на портала, не за"
                  + " реално подаване."));
    }
    return f;
  }

  private static void reportingFi(
      BgFatcaFileContent c, LocalDate today, List<BgDeclarationFileFinding> f) {
    if (c.getReportYear() < FIRST_YEAR) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_YEAR_TOO_EARLY",
              "Отчетна година "
                  + c.getReportYear()
                  + ": FATCA се отчита от "
                  + FIRST_YEAR
                  + " г."));
    }
    if (c.getReportYear() >= today.getYear()) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_YEAR_OPEN",
              "Отчетна година "
                  + c.getReportYear()
                  + " още не е приключила — отчетът е за салдата към 31.12."));
    }
    if (BgFatcaGiin.normalize(c.getGiin()) == null) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_GIIN_MISSING", "Няма GIIN — попълни го във „FATCA статус на компанията“."));
    } else if (!BgFatcaGiin.isValid(c.getGiin())) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_GIIN_FORMAT",
              "GIIN „" + c.getGiin() + "“ не е във формата XXXXXX.XXXXX.XX.XXX."));
    } else if (!BgFatcaGiin.isBulgarian(c.getGiin())) {
      f.add(
          BgDeclarationFileFinding.warning(
              "FATCA_GIIN_NOT_BG",
              "GIIN „"
                  + c.getGiin()
                  + "“ не завършва на ."
                  + BgFatcaGiin.BULGARIA
                  + " (България) — провери, че е на тази институция."));
    }
    if (blank(c.getFiName())) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_FI_NAME_MISSING", "Компанията няма наименование (ReportingFI/Name)."));
    }
    if (blank(c.getFiCity()) && blank(c.getFiStreet())) {
      f.add(
          BgDeclarationFileFinding.error(
              "FATCA_FI_ADDRESS_MISSING",
              "Адресът на компанията няма нито населено място, нито улица (ReportingFI/Address)."));
    }
    forbidden("ReportingFI", c.getFiName(), null, f);
    forbidden("ReportingFI", c.getFiCity(), null, f);
    forbidden("ReportingFI", c.getFiStreet(), null, f);
  }

  private static void account(Account a, Set<String> numbers, List<BgDeclarationFileFinding> f) {
    String who = a.label();
    if (blank(a.getAccountNumber())) {
      f.add(error(a, "FATCA_ACCOUNT_NUMBER_MISSING", who + ": липсва номер на сметката."));
    } else if (!numbers.add(a.getAccountNumber().trim())) {
      f.add(
          error(
              a,
              "FATCA_ACCOUNT_DUPLICATE",
              who + ": сметката е въведена повече от веднъж в отчета."));
    }

    if (BgFatcaStatus.INDIVIDUAL.equals(a.getHolderType())) {
      individual(a, who, f);
    } else if (BgFatcaStatus.ORGANISATION.equals(a.getHolderType())) {
      organisation(a, who, f);
    } else {
      f.add(error(a, "FATCA_HOLDER_TYPE_MISSING", who + ": не е посочен видът на титуляря."));
    }

    if (!blank(a.getUsTin()) && !isUsTin(a.getUsTin())) {
      f.add(
          error(
              a,
              "FATCA_US_TIN_FORMAT",
              who + ": US TIN „" + a.getUsTin() + "“ не е 9 цифри (напр. 000-00-0000)."));
    }
    if (blank(a.getCountryCode())) {
      f.add(error(a, "FATCA_COUNTRY_MISSING", who + ": липсва държава в адреса на титуляря."));
    }
    if (blank(a.getCity()) && blank(a.getAddressFree())) {
      f.add(
          error(
              a,
              "FATCA_ADDRESS_MISSING",
              who + ": адресът на титуляря няма нито град, нито свободен текст."));
    }
    // Салдото е задължително поле на сметката и генерираният getter никога не връща null —
    // липсата се пази на екрана, тук остава само знакът.
    if (a.getBalance().signum() < 0) {
      f.add(error(a, "FATCA_BALANCE_NEGATIVE", who + ": салдото е отрицателно."));
    }
    if (blank(a.getCurrencyCode())) {
      f.add(error(a, "FATCA_CURRENCY_MISSING", who + ": липсва валута."));
    }
    for (BigDecimal amount :
        new BigDecimal[] {a.getDividends(), a.getInterest(), a.getGrossProceeds(), a.getOther()}) {
      if (amount != null && amount.signum() < 0) {
        f.add(error(a, "FATCA_PAYMENT_NEGATIVE", who + ": плащане с отрицателна сума."));
        break;
      }
    }
    for (String text :
        new String[] {
          a.getAccountNumber(),
          a.getFirstName(),
          a.getLastName(),
          a.getOrganisationName(),
          a.getCity(),
          a.getAddressFree()
        }) {
      forbidden(who, text, a, f);
    }
  }

  private static void individual(Account a, String who, List<BgDeclarationFileFinding> f) {
    if (blank(a.getFirstName()) || blank(a.getLastName())) {
      f.add(
          error(a, "FATCA_NAME_MISSING", who + ": физическо лице без собствено или фамилно име."));
    }
    if (blank(a.getUsTin()) && a.getBirthDate() == null) {
      f.add(
          error(
              a,
              "FATCA_TIN_OR_BIRTHDATE",
              who + ": физическо лице без US TIN и без дата на раждане — нужно е едно от двете."));
    }
  }

  private static void organisation(Account a, String who, List<BgDeclarationFileFinding> f) {
    if (blank(a.getOrganisationName())) {
      f.add(error(a, "FATCA_ORG_NAME_MISSING", who + ": юридическо лице без наименование."));
    }
    if (a.getAcctHolderType() == null) {
      f.add(
          error(
              a,
              "FATCA_ACCT_HOLDER_TYPE_MISSING",
              who + ": юридическо лице без вид титуляр (AcctHolderType FATCA101–105)."));
    } else if (BgFatcaStatus.PASSIVE_NFFE_WITH_OWNERS.equals(a.getAcctHolderType())) {
      f.add(
          error(
              a,
              "FATCA_SUBSTANTIAL_OWNERS_UNSUPPORTED",
              who
                  + ": пасивно НФЧП със съществени американски собственици (FATCA102) изисква"
                  + " контролиращите лица (SubstantialOwner), а те още не се поддържат. Файлът"
                  + " НЕ се генерира — непълен отчет не се подава."));
    } else if (BgFatcaStatus.SPECIFIED_US_PERSON.equals(a.getAcctHolderType())
        && blank(a.getUsTin())) {
      f.add(
          error(
              a,
              "FATCA_US_TIN_REQUIRED",
              who + ": определено американско лице (FATCA104) без US TIN."));
    }
  }

  /** US TIN: 9 цифри, по желание с тирета или интервали между тях. */
  static boolean isUsTin(String value) {
    return value != null && value.replaceAll("[\\s-]", "").matches("[0-9]{9}");
  }

  private static void forbidden(
      String who, String text, Account a, List<BgDeclarationFileFinding> f) {
    if (text == null) {
      return;
    }
    for (String bad : FORBIDDEN) {
      if (text.contains(bad)) {
        String message =
            who + ": текстът „" + text + "“ съдържа „" + bad + "“, който IRS отхвърля (Pub 5124).";
        f.add(
            a == null
                ? BgDeclarationFileFinding.error("FATCA_FORBIDDEN_CHARS", message)
                : error(a, "FATCA_FORBIDDEN_CHARS", message));
        return;
      }
    }
  }

  private static BgDeclarationFileFinding error(Account a, String code, String message) {
    return BgDeclarationFileFinding.error(code, message)
        .at(BgFatcaAccount.class.getName(), a.getId());
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
