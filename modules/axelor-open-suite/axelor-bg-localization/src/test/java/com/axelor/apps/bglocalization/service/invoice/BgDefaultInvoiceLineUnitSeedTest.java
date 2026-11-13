package com.axelor.apps.bglocalization.service.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Unit;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.data.csv.CSVConfig;
import com.axelor.data.csv.CSVInput;
import com.axelor.studio.db.AppBgLocalization;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BgDefaultInvoiceLineUnitSeed}: празната настройка става „Брой“, зададената остава. Без
 * mock-подпора — {@code findPiece} се подменя с наследник, базата не се пипа. Последните три теста
 * пазят връзката: конфигът наистина вика колбека, CSV-то НЕ носи колоната, importId 2 е „Брой“.
 */
class BgDefaultInvoiceLineUnitSeedTest {

  private static final Path MODULE_RESOURCES =
      BgDomainColumns.moduleRoot().resolve("src/main/resources");

  private static class FakeSeed extends BgDefaultInvoiceLineUnitSeed {
    final Unit piece;
    int lookups;

    FakeSeed(Unit piece) {
      super(null);
      this.piece = piece;
    }

    @Override
    protected Unit findPiece() {
      lookups++;
      return piece;
    }
  }

  private static Unit unit(String name) {
    Unit unit = new Unit();
    unit.setName(name);
    return unit;
  }

  private static final Map<String, Object> NO_VALUES = Collections.emptyMap();

  @Test
  @DisplayName("Празна настройка (заварена база) → „Брой“")
  void emptySettingBecomesPiece() {
    Unit piece = unit("Брой");
    FakeSeed seed = new FakeSeed(piece);
    AppBgLocalization app = new AppBgLocalization();

    assertSame(app, seed.fillIfEmpty(app, NO_VALUES));
    assertSame(piece, app.getBgDefaultInvoiceLineUnit());
  }

  @Test
  @DisplayName("Нова инсталация: редът, който binder-ът тъкмо създава, излиза с „Брой“")
  void newInstallationGetsPiece() {
    Unit piece = unit("Брой");
    FakeSeed seed = new FakeSeed(piece);
    // CSVBinder#find не намира ред → newInstance(); колбекът го вижда преди JPA.manage.
    AppBgLocalization fresh = new AppBgLocalization();
    assertNull(fresh.getBgDefaultInvoiceLineUnit());

    seed.fillIfEmpty(fresh, NO_VALUES);

    assertSame(piece, fresh.getBgDefaultInvoiceLineUnit());
  }

  @Test
  @DisplayName("⚠️ Зададена друга единица остава — вносът върви на ВСЕКИ старт")
  void anExistingChoiceIsNeverOverwritten() {
    Unit kilogram = unit("Килограм");
    FakeSeed seed = new FakeSeed(unit("Брой"));
    AppBgLocalization app = new AppBgLocalization();
    app.setBgDefaultInvoiceLineUnit(kilogram);

    seed.fillIfEmpty(app, NO_VALUES);

    assertSame(kilogram, app.getBgDefaultInvoiceLineUnit());
    assertEquals(0, seed.lookups, "при зададена стойност „Брой“ дори не се търси");
  }

  @Test
  @DisplayName("Втори старт не мени нищо — стойността вече е сложена от първия")
  void theSecondRunIsANoOp() {
    Unit piece = unit("Брой");
    FakeSeed seed = new FakeSeed(piece);
    AppBgLocalization app = new AppBgLocalization();

    seed.fillIfEmpty(app, NO_VALUES);
    seed.fillIfEmpty(app, NO_VALUES);

    assertSame(piece, app.getBgDefaultInvoiceLineUnit());
    assertEquals(1, seed.lookups);
  }

  @Test
  @DisplayName("Без „Брой“ (няма или е дублиран) полето остава празно, редът пак се внася")
  void missingPieceLeavesTheSettingEmpty() {
    FakeSeed seed = new FakeSeed(null);
    AppBgLocalization app = new AppBgLocalization();

    assertSame(app, seed.fillIfEmpty(app, NO_VALUES));
    assertNull(app.getBgDefaultInvoiceLineUnit());
  }

  @Test
  @DisplayName("Чужд бийн минава непокътнат")
  void otherBeansPassThrough() {
    FakeSeed seed = new FakeSeed(unit("Брой"));
    Unit other = unit("x");

    assertSame(other, seed.fillIfEmpty(other, NO_VALUES));
    assertEquals(0, seed.lookups);
  }

  // ---------------------------------------------------------- връзката със seed-а

  @Test
  @DisplayName("Конфигът вика колбека на входа studio_appBgLocalization.csv")
  void configCallsTheSeed() throws Exception {
    CSVConfig config =
        CSVConfig.parse(
            MODULE_RESOURCES.resolve("apps/init-data/bg-localization-config.xml").toFile());
    CSVInput input =
        config.getInputs().stream()
            .filter(i -> "studio_appBgLocalization.csv".equals(i.getFileName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("входът studio_appBgLocalization.csv липсва"));

    assertEquals(
        BgDefaultInvoiceLineUnitSeed.class.getName() + ":fillIfEmpty", input.getCallable());
    // CSVInput#call търси точно (Object, Map) — друга сигнатура пада чак при вноса.
    Method method =
        BgDefaultInvoiceLineUnitSeed.class.getMethod("fillIfEmpty", Object.class, Map.class);
    assertNotNull(method);
  }

  @Test
  @DisplayName("⚠️ CSV-то НЕ носи колоната — upsert-ът щеше да презаписва избора при всеки старт")
  void seedCsvDoesNotCarryTheColumn() throws Exception {
    for (String lang : List.of("en", "fr")) {
      Path csv =
          MODULE_RESOURCES.resolve("apps/init-data/" + lang + "/studio_appBgLocalization.csv");
      String header = Files.readAllLines(csv, StandardCharsets.UTF_8).get(0);
      assertFalse(
          header.contains("bgDefaultInvoiceLineUnit"), lang + ": колоната е в CSV-то: " + header);
    }
  }

  @Test
  @DisplayName(
      "importId " + BgDefaultInvoiceLineUnitSeed.PIECE_IMPORT_ID + " в base_unit.csv е „Брой“")
  void pieceImportIdIsPieceInTheUnitSeed() throws Exception {
    List<String> lines =
        Files.readAllLines(
            MODULE_RESOURCES.resolve("data-init/input/base_unit.csv"), StandardCharsets.UTF_8);
    String prefix = "\"" + BgDefaultInvoiceLineUnitSeed.PIECE_IMPORT_ID + "\";";
    String row =
        lines.stream()
            .filter(l -> l.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new AssertionError("няма ред с importId " + prefix));
    assertTrue(row.startsWith(prefix + "\"Брой\";"), row);
    assertEquals(
        1, lines.stream().filter(l -> l.startsWith(prefix)).count(), "importId се повтаря");
  }

  @Test
  @DisplayName("Съобщението на OCR вноса при празна настройка е на български")
  void notConfiguredMessageIsBulgarian() {
    String message = new BgInvoiceLineUnitDefaultProvider(null).notConfiguredMessage();

    assertTrue(message.startsWith("Не е зададена мерна единица по подразбиране"), message);
    assertTrue(message.contains("„Редове на фактура“"), message);
    assertFalse(message.contains("not configured"), message);
  }
}
