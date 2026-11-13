package com.axelor.apps.bglocalization.service.country;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.app.AppModule;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.auth.db.User;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaModule;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.AbstractModule;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Нашият data-init, после пълният списък държави на AOS — върху една и съща база.
 *
 * <p>Редът е: (1) кореновият data-init на axelor-base (държави, езици, локализации) при създаването
 * на базата; (2) bg seed-ът (шаблонът RNT-BG и закачането му); (3) демо данните на axelor-base —
 * пълният списък държави и локализациите на AOS, в реда на {@code apps/demo-data/base-config.xml};
 * (4) пак bg seed-ът, защото DataInitSync го пуска на всеки boot. Файловете и {@code <input>}
 * елементите са ИСТИНСКИТЕ от classpath-а — тестът не носи свое копие на данните, тоест следи всяка
 * тяхна промяна.
 *
 * <p>Тестът пази от два сблъсъка на importId: България с importId 99, който в списъка на AOS е
 * Лаос, би презаписала реда на LAO, RNT-BG би останал на него, а новата България — без шаблон; и
 * bg_BG с importId 3, който в демото е fr_CA, би се преименувал на fr_CA и всичко, вързано за него
 * отпреди (тук: един потребител), би станало „French (CANADA)“.
 *
 * <p>Последната проверка е същото правило, с което се пуска проверката при старт ({@link
 * BgCountrySeedCheck}), тоест сценарият, който то пази, е доказан и тук.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgCountryImportIdCollisionIntegrationTest.Wiring.class)
@Tag("integration")
class BgCountryImportIdCollisionIntegrationTest extends JpaSupport {

  public static class Wiring extends AbstractModule {
    @Override
    protected void configure() {
      install(new JpaModule("testUnit", true, true));
      install(new BaseModule());
      install(new AuthModule());
      install(new AppModule());
    }
  }

  @Test
  void bulgariaSurvivesTheFullAosCountryListAfterOurDataInit() throws Exception {
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

    URL bg = resource("apps/init-data/bg-localization-config.xml", "axelor-bg-localization");
    List<String> bgInputs = List.of("base_registrationNumberTemplate.csv", "base_country.csv");
    run(bg, "en/", bgInputs, errors);

    // Нова инсталация БЕЗ демо данни — проверява се тук, преди вноса. Това е мястото, където
    // частична смяна на importId-тата мълчи: кореновият data-init с България 30, а bg seed-ът още
    // търси 99 — търсенето не намира ред, update="true" прескача и RNT-BG просто не се закача.
    JPA.em().clear();
    List<Country> freshBulgaria = countries("BG");
    String freshDiagnosis = "\nсъстояние: " + describe() + "\nгрешки при вноса: " + errors;
    assertEquals(1, freshBulgaria.size(), "нова инсталация: България (BG) веднъж" + freshDiagnosis);
    Long freshBgId = freshBulgaria.get(0).getId();
    assertAll(
        () ->
            assertEquals(
                BgCountrySeedCheck.BG_IMPORT_ID,
                freshBulgaria.get(0).getImportId(),
                "нова инсталация: България с importId 30" + freshDiagnosis),
        () ->
            assertEquals(
                List.of(freshBgId),
                ids(countriesWithRnt()),
                "нова инсталация: RNT-BG е закачен за България и само за нея" + freshDiagnosis),
        () ->
            assertEquals(
                freshBgId,
                countryIdOf(BgCountrySeedCheck.BG_LOCALIZATION),
                "нова инсталация: bg_BG сочи България" + freshDiagnosis));

    // Запис с bg_BG отпреди демото — като партньорите, потребителите и дружествата на заварена
    // база. (Потребител, а не партньор: формулите на Partner са PostgreSQL и HSQLDB не ги чете.)
    Long bgBgBefore = localizationId(BgCountrySeedCheck.BG_LOCALIZATION);
    assertNotNull(bgBgBefore, "кореновият data-init не създаде bg_BG; грешки: " + errors);
    AtomicReference<Long> probe = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          User user = new User();
          user.setCode("bg-country-probe");
          user.setName("Проба");
          user.setPassword("probe-only");
          user.setLocalization(JPA.find(Localization.class, bgBgBefore));
          probe.set(JPA.save(user).getId());
        });
    Long userId = probe.get();

    URL demo = resource("apps/demo-data/base-config.xml", "axelor-base");
    run(
        demo,
        "en/",
        List.of(
            "base_addressTemplate.csv",
            "base_registrationNumberTemplate.csv",
            "base_economicArea.csv",
            "base_country.csv",
            "base_language.csv",
            "base_localization.csv"),
        errors);

    run(bg, "en/", bgInputs, errors);

    JPA.em().clear();
    List<Country> bulgaria = countries("BG");
    List<Country> laos = countries("LA");
    List<Country> withRnt = countriesWithRnt();
    Localization bgBg =
        JPA.all(Localization.class)
            .filter("self.code = :code")
            .bind("code", BgCountrySeedCheck.BG_LOCALIZATION)
            .fetchOne();
    String diagnosis = "\nсъстояние: " + describe() + "\nгрешки при вноса: " + errors;

    assertTrue(
        countAll() > 200, "пълният списък на AOS не е внесен (" + countAll() + ")" + diagnosis);
    assertAll(
        () -> assertEquals(1, bulgaria.size(), "България (BG) точно веднъж" + diagnosis),
        () ->
            assertEquals(
                BgCountrySeedCheck.BG_IMPORT_ID,
                bulgaria.isEmpty() ? null : bulgaria.get(0).getImportId(),
                "България с importId 30, както в пълния списък на AOS" + diagnosis),
        () ->
            assertEquals(
                bulgaria.size() == 1 ? List.of(bulgaria.get(0).getId()) : List.of(),
                ids(withRnt),
                "RNT-BG е закачен само за България" + diagnosis),
        () -> assertNotNull(bgBg, "локализацията bg_BG съществува" + diagnosis),
        () ->
            assertEquals(
                bulgaria.size() == 1 ? bulgaria.get(0).getId() : null,
                bgBg == null || bgBg.getCountry() == null ? null : bgBg.getCountry().getId(),
                "bg_BG сочи България" + diagnosis),
        // Сблъсъкът fr_CA/importId 3: демото на AOS внася fr_CA с importId 3, който нашият
        // data-init
        // даваше на bg_BG — вносът търси по importId и ПРЕИМЕНУВА реда bg_BG на fr_CA.
        () ->
            assertEquals(
                bgBgBefore,
                bgBg == null ? null : bgBg.getId(),
                "bg_BG е същият ред като преди демото (не е преименуван и създаден наново)"
                    + diagnosis),
        () ->
            assertEquals(
                "bg_BG",
                bgBg == null ? null : bgBg.getImportId(),
                "bg_BG е с importId, който демото не ползва" + diagnosis),
        () ->
            assertEquals(
                "bg_BG",
                localizationCodeOfUser(userId),
                "записът с bg_BG отпреди демото още е bg_BG, не fr_CA" + diagnosis),
        () ->
            assertEquals(
                "3 CA",
                localizationOf("fr_CA"),
                "fr_CA от демото е свой ред (importId 3, Канада)" + diagnosis),
        () -> assertEquals(1, laos.size(), "Лаос (LA) точно веднъж" + diagnosis),
        () ->
            assertEquals(
                "LAO",
                laos.isEmpty() ? null : laos.get(0).getAlpha3Code(),
                "LA е Лаос (LAO)" + diagnosis),
        () ->
            assertNull(
                laos.isEmpty() || laos.get(0).getRegistrationNumberTemplate() == null
                    ? null
                    : laos.get(0).getRegistrationNumberTemplate().getImportId(),
                "Лаос е без RNT-BG" + diagnosis),
        () ->
            assertEquals(
                Collections.emptyList(),
                BgCountrySeedCheck.violations(new BgCountrySeedCheckService().snapshot()),
                "проверката при старт не намира нарушения" + diagnosis));
  }

  // ---------------------------------------------------------------------------------------------

  private static void run(URL config, String dataDir, List<String> files, List<String> errors)
      throws IOException {
    String xml = read(config);
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(xml);
    assertTrue(head.find(), config.toString());
    Path dir = Files.createTempDirectory("bg-country-import");
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
    for (URL url : Collections.list(getClassLoader().getResources(name))) {
      if (url.toString().contains(module)) {
        return url;
      }
    }
    throw new AssertionError(name + " от " + module + " не е на classpath-а");
  }

  private static ClassLoader getClassLoader() {
    return BgCountryImportIdCollisionIntegrationTest.class.getClassLoader();
  }

  private static String read(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<Country> countries(String alpha2) {
    return JPA.all(Country.class)
        .filter("upper(self.alpha2Code) = :a")
        .bind("a", alpha2)
        .order("id")
        .fetch();
  }

  private static List<Country> countriesWithRnt() {
    return JPA.all(Country.class)
        .filter("self.registrationNumberTemplate.importId = :rnt")
        .bind("rnt", BgCountrySeedCheck.RNT_BG)
        .order("id")
        .fetch();
  }

  private static Long countryIdOf(String localizationCode) {
    Localization l =
        JPA.all(Localization.class).filter("self.code = :c").bind("c", localizationCode).fetchOne();
    return l == null || l.getCountry() == null ? null : l.getCountry().getId();
  }

  private static Long localizationId(String code) {
    Localization l =
        JPA.all(Localization.class).filter("self.code = :c").bind("c", code).fetchOne();
    return l == null ? null : l.getId();
  }

  private static String localizationCodeOfUser(Long userId) {
    User user = JPA.find(User.class, userId);
    return user == null || user.getLocalization() == null ? null : user.getLocalization().getCode();
  }

  /** „importId alpha2“ на локализацията с този код; {@code null}, ако я няма. */
  private static String localizationOf(String code) {
    Localization l =
        JPA.all(Localization.class).filter("self.code = :c").bind("c", code).fetchOne();
    if (l == null) {
      return null;
    }
    return l.getImportId() + " " + (l.getCountry() == null ? "-" : l.getCountry().getAlpha2Code());
  }

  private static long countAll() {
    return JPA.all(Country.class).count();
  }

  private static List<Long> ids(List<Country> countries) {
    List<Long> out = new ArrayList<>();
    countries.forEach(c -> out.add(c.getId()));
    return out;
  }

  private static String describe() {
    StringBuilder sb = new StringBuilder();
    for (Object[] r :
        JPA.em()
            .createQuery(
                "SELECT c.id, c.importId, c.alpha2Code, c.name, r.importId FROM Country c"
                    + " LEFT JOIN c.registrationNumberTemplate r"
                    + " WHERE upper(c.alpha2Code) IN ('BG', 'LA') OR r.importId = 'RNT-BG'"
                    + " ORDER BY c.id",
                Object[].class)
            .getResultList()) {
      sb.append(String.format("[id %s importId %s %s %s rnt %s] ", r[0], r[1], r[2], r[3], r[4]));
    }
    for (Object[] r :
        JPA.em()
            .createQuery(
                "SELECT l.importId, l.code, c.alpha2Code FROM Localization l LEFT JOIN l.country c"
                    + " ORDER BY l.id",
                Object[].class)
            .getResultList()) {
      sb.append(String.format("[loc %s %s → %s] ", r[0], r[1], r[2]));
    }
    return sb.toString();
  }
}
