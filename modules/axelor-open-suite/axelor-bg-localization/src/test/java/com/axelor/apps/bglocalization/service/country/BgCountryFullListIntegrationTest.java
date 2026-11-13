package com.axelor.apps.bglocalization.service.country;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.service.address.CountryService;
import com.axelor.apps.bglocalization.module.BgLocalizationTest;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Чиста база: axelor-base data-init носи само 5 държави, демо данните не се внасят. След bg seed-а
 * трябва да има целия ISO 3166-1 списък (249) с българските имена — иначе не може да се въведе
 * партньор от ЕС (ВОД, VIES, SAF-T).
 *
 * <p>Сценарият е в един метод: integrationTest пуска всеки клас в своя JVM с нова HSQLDB в паметта
 * ({@code forkEvery = 1}), тоест броят 249 е броят на чистата база, а не на чужд тест. Файловете и
 * {@code <input>} елементите са ИСТИНСКИТЕ от classpath-а.
 */
class BgCountryFullListIntegrationTest extends BgLocalizationTest {

  private static final int ISO_3166_COUNT = 249;

  private static final List<String> EU =
      List.of(
          "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE",
          "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK");

  private static final List<String> BG_INPUTS =
      List.of(
          "base_registrationNumberTemplate.csv",
          "base_country.csv",
          "bg_country.csv",
          "bg_appBase_addressTemplate.csv");

  @Test
  void cleanInstallGetsTheFullIsoListInBulgarianAndAForeignAddressSaves() throws Exception {
    List<String> errors = new ArrayList<>();

    URL root = resource("data-init/input-config.xml", "axelor-base");
    run(
        root,
        "input/",
        List.of(
            "base_registrationNumberTemplate.csv",
            "base_addressTemplate.csv",
            "base_country.csv",
            "base_language.csv",
            "base_localization.csv"),
        errors);
    assertEquals(
        5, countAll(), "чистата база преди bg seed-а: 5 държави от axelor-base; " + errors);

    // Инсталираното приложение Base без „шаблон по подразбиране“ — какъвто е на чиста база.
    JPA.runInTransaction(
        () -> {
          App base = new App();
          base.setCode("base");
          base.setName("Base");
          base.setActive(true);
          JPA.save(base);
          AppBase appBase = new AppBase();
          appBase.setApp(base);
          appBase.setMapApiSelect(AppBaseRepository.MAP_API_OPEN_STREET_MAP);
          JPA.save(appBase);
        });

    URL bg = resource("apps/init-data/bg-localization-config.xml", "axelor-bg-localization");
    run(bg, "en/", BG_INPUTS, errors);

    JPA.em().clear();
    Map<String, Integer> byAlpha2 = countByAlpha2();
    Country bulgaria = one("BG");
    Country germany = one("DE");
    Country canada = one("CA");
    String diagnosis = "\nгрешки при вноса: " + errors;

    assertAll(
        () -> assertTrue(errors.isEmpty(), "вносът мина без грешки" + diagnosis),
        () -> assertEquals(ISO_3166_COUNT, countAll(), "249 държави (ISO 3166-1)" + diagnosis),
        () ->
            assertEquals(
                Collections.emptyMap(),
                duplicates(byAlpha2),
                "всеки alpha2 точно веднъж" + diagnosis),
        () -> {
          List<String> missing = new ArrayList<>(EU);
          missing.removeAll(byAlpha2.keySet());
          assertEquals(List.of(), missing, "всички 27 държави от ЕС" + diagnosis);
        },
        () -> assertEquals(1, (int) byAlpha2.get("BG"), "България без дубликат" + diagnosis),
        () -> assertEquals("България", bulgaria.getName()),
        () -> assertEquals("BULGARIA", bulgaria.getBgNameEn()),
        () ->
            assertEquals(
                BgCountrySeedCheck.BG_IMPORT_ID,
                bulgaria.getImportId(),
                "importId на България не е пипнат"),
        () ->
            assertEquals(
                BgCountrySeedCheck.RNT_BG,
                bulgaria.getRegistrationNumberTemplate() == null
                    ? null
                    : bulgaria.getRegistrationNumberTemplate().getImportId(),
                "RNT-BG остава на България"),
        () -> assertEquals("Германия", germany.getName()),
        () -> assertEquals("GERMANY", germany.getBgNameEn()),
        () -> assertEquals("5", germany.getImportId(), "importId от списъка на AOS"),
        () -> assertEquals("DEU", germany.getAlpha3Code()),
        () -> assertEquals("DEFAULT", germany.getAddressTemplate().getName()),
        () -> assertEquals("CANADA", canada.getAddressTemplate().getName(), "Канада — свой"),
        () -> assertEquals("Канада", canada.getName()),
        () ->
            assertEquals(
                "DEFAULT",
                appBase().getDefaultAddressTemplate() == null
                    ? null
                    : appBase().getDefaultAddressTemplate().getName(),
                "„Шаблон за адрес по подразбиране“ в AppBase е попълнен"),
        () ->
            assertEquals(
                Collections.emptyList(),
                BgCountrySeedCheck.violations(new BgCountrySeedCheckService().snapshot()),
                "проверката при старт не намира нарушения"));

    // Следващият boot (DataInitSync) пуска същото пак — нищо не се удвоява.
    Long bgId = bulgaria.getId();
    run(bg, "en/", BG_INPUTS, errors);
    JPA.em().clear();
    assertEquals(ISO_3166_COUNT, countAll(), "второ пускане: пак 249" + diagnosis);
    assertEquals(bgId, one("BG").getId(), "второ пускане: същата България");

    // Адрес в Германия на чиста база: записът изчислява formattedFullName по шаблона DEFAULT.
    Long[] addressId = new Long[1];
    JPA.runInTransaction(
        () -> {
          Country de = one("DE");
          City berlin = new City();
          berlin.setName("Berlin");
          berlin.setCountry(de);
          Beans.get(CityRepository.class).save(berlin);

          Address address = new Address();
          address.setCountry(de);
          address.setCity(berlin);
          address.setZip("10115");
          address.setStreetName("Invalidenstraße 1");
          addressId[0] = Beans.get(AddressRepository.class).save(address).getId();
        });
    JPA.em().clear();
    Address saved = Beans.get(AddressRepository.class).find(addressId[0]);
    assertNotNull(saved, "адресът в Германия е записан");
    assertTrue(
        saved.getFormattedFullName() != null && saved.getFormattedFullName().contains("Германия"),
        "адресът носи българското име на държавата: " + saved.getFormattedFullName());

    // REST-ът за адреси създава непозната държава със „шаблона по подразбиране“ — без него гърми.
    Country created = Beans.get(CountryService.class).createAndSaveCountry("Testland", "QZ");
    assertEquals("DEFAULT", created.getAddressTemplate().getName());
  }

  // ---------------------------------------------------------------------------------------------

  private static AppBase appBase() {
    return JPA.all(AppBase.class).fetchOne();
  }

  private static Country one(String alpha2) {
    List<Country> found =
        JPA.all(Country.class).filter("upper(self.alpha2Code) = :a").bind("a", alpha2).fetch();
    assertEquals(1, found.size(), alpha2 + " точно веднъж");
    return found.get(0);
  }

  private static Map<String, Integer> countByAlpha2() {
    Map<String, Integer> out = new TreeMap<>();
    for (Country c : JPA.all(Country.class).fetch()) {
      out.merge(c.getAlpha2Code() == null ? "" : c.getAlpha2Code().toUpperCase(), 1, Integer::sum);
    }
    return out;
  }

  private static Map<String, Integer> duplicates(Map<String, Integer> byAlpha2) {
    Map<String, Integer> out = new TreeMap<>();
    byAlpha2.forEach(
        (k, v) -> {
          if (v > 1) {
            out.put(k, v);
          }
        });
    return out;
  }

  private static long countAll() {
    return JPA.all(Country.class).count();
  }

  private static void run(URL config, String dataDir, List<String> files, List<String> errors)
      throws IOException {
    String xml = read(config);
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(xml);
    assertTrue(head.find(), config.toString());
    Path dir = Files.createTempDirectory("bg-country-full-list");
    StringBuilder composed =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n").append(head.group());
    for (String file : files) {
      composed.append('\n').append(inputOf(xml, file));
      try (InputStream in = new URL(config, dataDir + file).openStream()) {
        Files.write(dir.resolve(file), in.readAllBytes());
      }
    }
    composed.append("\n</csv-inputs>\n");
    Path configFile = dir.resolve("config.xml");
    Files.write(configFile, composed.toString().getBytes(StandardCharsets.UTF_8));

    CSVImporter importer = new CSVImporter(configFile.toString(), dir.toString());
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {}

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(config.getPath().replaceAll(".*/resources/", "") + ": " + e);
          }
        });
    importer.run();
  }

  /** Първият {@code <input>} за файла — и самозатворен, и с {@code <bind>} вътре. */
  private static String inputOf(String config, String file) {
    Matcher m =
        Pattern.compile(
                "<input\\s+file=\"" + Pattern.quote(file) + "\"(?:[^>]*?/>|.*?</input>)",
                Pattern.DOTALL)
            .matcher(config);
    assertTrue(m.find(), file);
    return m.group();
  }

  /** Ресурсът на точно този модул: {@code data-init/input-config.xml} го носят 30 модула. */
  private static URL resource(String name, String module) throws IOException {
    for (URL url :
        Collections.list(
            BgCountryFullListIntegrationTest.class.getClassLoader().getResources(name))) {
      if (url.toString().contains(module)) {
        return url;
      }
    }
    throw new AssertionError(name + " от " + module + " не е на classpath-а");
  }

  private static String read(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
