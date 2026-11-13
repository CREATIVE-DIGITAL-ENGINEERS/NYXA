package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Language;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.service.CompanyDateService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PDF СПРАВКИТЕ: ПЕРИОД ВЪВ ФОРМАТ „01.01.2026“, НЕ „1/1/2026“, И ЧАС ПО СОФИЯ, НЕ В UTC.
 *
 * <p>И двете идват от записа на дружеството, не от шаблоните: периодът на custom справките се
 * форматира с {@code CompanyDateService} (Company.localization, иначе application.locale = en), а
 * часът — в {@code Company.timezone} (иначе JVM = UTC). BG модулът ги попълва.
 */
class BgCompanyReportLocaleTest {

  /** Като реда bg_BG от data-init: кодът на Localization се СМЯТА от езика и държавата. */
  private static Localization bulgarian() {
    Language language = new Language();
    language.setCode("bg");
    Country country = new Country();
    country.setAlpha2Code("BG");
    Localization l = new Localization();
    l.setLanguage(language);
    l.setCountry(country);
    return l;
  }

  @Test
  @DisplayName("Празно дружество → периодът на справката е „01.01.2026“-ов, часът — софийски")
  void emptyCompanyPrintsBulgarianDatesAndSofiaTime() throws Exception {
    Company company = new Company();
    company.setName("Демо Холдинг АД");

    assertTrue(BgCompanyReportLocale.fillGaps(company, bulgarian()));

    // Същото, което прави AccountingReportValueAbstractService за колоната „период“.
    DateTimeFormatter format = new CompanyDateService().getDateFormat(company);
    String period =
        LocalDate.of(2026, 1, 1).format(format) + " - " + LocalDate.of(2026, 10, 31).format(format);
    assertFalse(period.contains("/"), period);
    assertTrue(period.startsWith("1.01.2026") && period.contains("31.10.2026"), period);

    // Същото, което прави BaseReportGenerator.getTimezone за BIRT.
    assertEquals("Europe/Sofia", TimeZone.getTimeZone(company.getTimezone()).getID());
  }

  @Test
  @DisplayName("Изборът на администратора не се пипа")
  void existingValuesStay() {
    Company company = new Company();
    Localization french = new Localization();
    french.setCode("fr_FR");
    company.setLocalization(french);
    company.setTimezone("Europe/Paris");

    assertFalse(BgCompanyReportLocale.fillGaps(company, bulgarian()));
    assertSame(french, company.getLocalization());
    assertEquals("Europe/Paris", company.getTimezone());
  }

  @Test
  @DisplayName("Без ред bg_BG в номенклатурата часовата зона пак се попълва")
  void timezoneWithoutTheLocalizationRow() {
    Company company = new Company();
    assertTrue(BgCompanyReportLocale.fillGaps(company, null));
    assertEquals("Europe/Sofia", company.getTimezone());
  }

  @Test
  @DisplayName("Стартът пипа само празните полета, броят се по поле, вторият старт не прави нищо")
  void startupFillCountsAndIsIdempotent() {
    Localization bulgarian = bulgarian();
    Company empty = new Company();
    Company onlyTimezone = new Company();
    onlyTimezone.setTimezone("Europe/Paris");
    Company full = new Company();
    Localization french = new Localization();
    full.setLocalization(french);
    full.setTimezone("America/New_York");

    BgCompanyReportLocale.Summary first =
        BgCompanyReportLocale.fillAll(Arrays.asList(empty, onlyTimezone, full), bulgarian);
    assertEquals(3, first.companiesSeen);
    assertEquals(2, first.companiesFilled);
    assertEquals(1, first.timezonesFilled);
    assertEquals(2, first.localizationsFilled);
    assertTrue(first.message().contains("2 of 3 company(ies) filled"), first.message());
    assertEquals("Europe/Paris", onlyTimezone.getTimezone());
    assertSame(bulgarian, onlyTimezone.getLocalization());
    assertSame(french, full.getLocalization());
    assertEquals("America/New_York", full.getTimezone());

    BgCompanyReportLocale.Summary second =
        BgCompanyReportLocale.fillAll(Arrays.asList(empty, onlyTimezone, full), bulgarian);
    assertEquals(0, second.companiesFilled);
    assertEquals(0, second.timezonesFilled + second.localizationsFilled);
    assertTrue(second.message().contains("0 of 3 company(ies) filled"), second.message());
  }

  @Test
  @DisplayName("Без ред bg_BG: зоната се попълва, а логът казва, че езикът остава празен")
  void missingLocalizationRowIsReported() {
    BgCompanyReportLocale.Summary summary =
        BgCompanyReportLocale.fillAll(Arrays.asList(new Company()), null);
    assertEquals(1, summary.timezonesFilled);
    assertEquals(0, summary.localizationsFilled);
    assertEquals(1, summary.localizationsLeftEmpty);
    assertTrue(summary.message().contains("keep an empty localization"), summary.message());
  }

  @Test
  @DisplayName("Заварените дружества се попълват при старта; Europe/Sofia е в селекцията")
  void wiredAtStartupAndSelectable() throws Exception {
    String listener =
        new String(
            Files.readAllBytes(
                Paths.get(
                    "src/main/java/com/axelor/apps/bglocalization/listener/"
                        + "BgLocalizationServerStartListener.java")),
            StandardCharsets.UTF_8);
    String onStartup = listener.substring(listener.indexOf("public void onStartup("));
    onStartup = onStartup.substring(0, onStartup.indexOf("\n  }\n"));
    assertTrue(onStartup.contains("ensureCompanyReportLocale();"), onStartup);
    String ensure = listener.substring(listener.indexOf("public void ensureCompanyReportLocale("));
    ensure = ensure.substring(0, ensure.indexOf("\n  }\n"));
    assertTrue(
        ensure.contains("log.info(summary.message())"), "броят не се пише в лога: " + ensure);

    String selects =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/views/Selects.xml")),
            StandardCharsets.UTF_8);
    int at = selects.indexOf("<selection name=\"company.timezone.select\"");
    assertTrue(at > 0, "няма разширение на company.timezone.select");
    assertTrue(
        selects.substring(at, selects.indexOf("</selection>", at)).contains("\"Europe/Sofia\""));
  }
}
