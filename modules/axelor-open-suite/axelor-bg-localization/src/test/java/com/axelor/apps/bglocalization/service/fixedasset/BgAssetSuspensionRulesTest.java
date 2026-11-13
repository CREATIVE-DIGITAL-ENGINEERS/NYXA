package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Преустановяване на данъчната амортизация по чл. 59 ЗКПО.
 *
 * <p>Еталонът е машина: ДАС 10 000 EUR, категория II по чл. 55 (30% годишно), тоест 40 месеца по
 * 250 EUR. Числата се извеждат от плана, не се измислят.
 */
class BgAssetSuspensionRulesTest {

  private static final BigDecimal МЕСЕЧНА = new BigDecimal("250.000");

  @Test
  @DisplayName("⚠️ консервация за ТРИ месеца не спира нищо — срокът е „по-дълъг от 12“")
  void кратката_консервация_не_задейства() {
    // Чл. 59 ал. 1: „когато съответният актив временно не се използва … през период, ПО-ДЪЛЪГ ОТ
    // ДВАНАДЕСЕТ МЕСЕЦА“. Това е разликата, която прави „спряна за N месеца → спряна амортизация“
    // невярно за всяко N до дванайсет включително.
    LocalDate спрян = LocalDate.parse("2027-01-10");
    LocalDate върнат = LocalDate.parse("2027-04-01");
    FixedAsset asset = assetWithMonthlyTaxLines("2026-01-31", 30);

    assertFalse(
        BgAssetSuspensionRules.isSuspensionDue(спрян, върнат, LocalDate.parse("2027-12-31")),
        "върнат след три месеца — периодът не е по-дълъг от дванадесет и чл. 59 не се задейства");
    assertNull(
        BgAssetSuspensionRules.suspensionFor(asset, спрян, върнат),
        "тъй че дата на спиране не се произвежда");
    assertEquals(
        BigDecimal.ZERO,
        BgAssetSuspensionRules.reversalFor(asset, спрян, върнат),
        "и няма какво да се обръща по ал. 2 — макар планът да носи дванадесет реализирани реда в"
            + " периода, самото обръщане виси на дължимото спиране");
    assertNull(BgAssetSuspensionRules.reversalYearFor(asset, спрян, върнат));

    // ⚠️ Контролата: същият актив, но БЕЗ връщане — тогава и трите излизат.
    assertEquals(
        LocalDate.parse("2028-01-01"), BgAssetSuspensionRules.suspensionFor(asset, спрян, null));
    assertEquals(
        new BigDecimal("3000.000"), BgAssetSuspensionRules.reversalFor(asset, спрян, null));
  }

  @Test
  @DisplayName(
      "спирането е от началото на ТРИНАЙСЕТИЯ месец, възобновяването — от месеца НА връщането")
  void датите_на_спиране_и_възобновяване() {
    LocalDate спрян = LocalDate.parse("2027-01-01");
    assertEquals(
        LocalDate.parse("2028-01-01"),
        BgAssetSuspensionRules.suspensionStart(спрян),
        "дванадесетте месеца са януари–декември 2027, тъй че спирането е от 01.01.2028");
    assertEquals(
        LocalDate.parse("2027-12-31"),
        BgAssetSuspensionRules.lastChargedDay(спрян),
        "последният начислен ден е краят на дванадесетия месец");

    // ⚠️ Асиметрията е в закона: спирането е от СЛЕДВАЩИЯ месец, възобновяването — от ТЕКУЩИЯ.
    assertEquals(
        LocalDate.parse("2028-05-01"),
        BgAssetSuspensionRules.resumptionStart(LocalDate.parse("2028-05-17")),
        "„възобновява се от началото на месеца на връщането на актива в експлоатация“");
  }

  @Test
  @DisplayName("началото на месеца, а не денят: спрян по средата на месеца брои от първия ден")
  void срокът_се_мери_в_месеци() {
    // Законът мери в месеци и всички граници в текста са „началото на месеца“. Броене по дни би
    // разместило спирането с до месец според датата на заповедта.
    assertEquals(
        LocalDate.parse("2028-01-01"),
        BgAssetSuspensionRules.suspensionStart(LocalDate.parse("2027-01-31")),
        "спрян на 31.01.2027 дава същото спиране като спрян на 01.01.2027");
  }

  @Test
  @DisplayName("ал. 2 обръща точно дванадесетте месеца: 12 × 250 = 3 000")
  void обръщането_по_ал_2() {
    // Асетът носи РЕАЛИЗИРАНИ данъчни редове от 31.01.2026 нататък. Неползването започва
    // 01.01.2027, тъй че обърнатите месеци са януари–декември 2027 — дванадесет реда по 250.
    FixedAsset asset = assetWithMonthlyTaxLines("2026-01-31", 30);
    LocalDate спрян = LocalDate.parse("2027-01-01");

    assertEquals(
        new BigDecimal("3000.000"),
        BgAssetSuspensionRules.reversalAmount(asset, спрян),
        "дванадесет месеца по 250 EUR — с тази сума се НАМАЛЯВА годишната данъчна амортизация и се"
            + " УВЕЛИЧАВА данъчната стойност (ал. 2 т. 1 и т. 2)");
    assertEquals(
        Integer.valueOf(2027),
        BgAssetSuspensionRules.reversalYear(спрян),
        "„годината, през която е изтекъл дванадесетмесечният срок“ — годината на ПОСЛЕДНИЯ начислен"
            + " месец");
  }

  @Test
  @DisplayName("⚠️ обръщането брои само РЕАЛИЗИРАНИ редове — плановите са бъдеще")
  void плановите_редове_не_се_обръщат() {
    // Ал. 2 обръща НАЧИСЛЕНОТО. Сбор с плановите редове би върнал амортизация, която никога не е
    // влизала в данъчния финансов резултат — същият капан като при чл. 60 ал. 3 т. 1.
    FixedAsset asset = new FixedAsset();
    List<FixedAssetLine> lines = new ArrayList<>();
    lines.add(taxLine("2027-03-31", FixedAssetLineRepository.STATUS_REALIZED));
    lines.add(taxLine("2027-04-30", FixedAssetLineRepository.STATUS_PLANNED));
    asset.setFiscalFixedAssetLineList(lines);

    assertEquals(
        new BigDecimal("250.000"),
        BgAssetSuspensionRules.reversalAmount(asset, LocalDate.parse("2027-01-01")),
        "само реализираният ред влиза в обръщането");
  }

  @Test
  @DisplayName("срок, изтичащ през декември, слага намалението в ПРЕДХОДНАТА година")
  void годината_на_намалението() {
    // Спрян от 01.12.2026 → дванадесетте месеца са декември 2026 – ноември 2027 → срокът изтича
    // през ноември 2027, а спирането е от 01.12.2027. Обратно: спрян от 01.01.2027 дава срок,
    // изтичащ през декември 2027 — тоест намалението е за 2027, макар спирането да е вече 2028.
    assertEquals(
        Integer.valueOf(2027), BgAssetSuspensionRules.reversalYear(LocalDate.parse("2026-12-01")));
    assertEquals(
        Integer.valueOf(2027), BgAssetSuspensionRules.reversalYear(LocalDate.parse("2027-01-01")));
    assertEquals(
        LocalDate.parse("2028-01-01"),
        BgAssetSuspensionRules.suspensionStart(LocalDate.parse("2027-01-01")),
        "спирането е в 2028, а намалението — за 2027; двете нарочно не съвпадат");
  }

  @Test
  @DisplayName("⚠️ ал. 4: активите по чл. 60 ал. 3 НЕ се спират — и двете точки")
  void изключението_по_ал_4() {
    // „Не се преустановява начисляването на данъчни амортизации за активите по чл. 60, ал. 3.“
    // Ал. 4 препраща към ЦЯЛАТА ал. 3, тъй че проверка само на напълно амортизираните пропуска
    // активите по вдигнатия праг.
    FixedAsset обикновен = new FixedAsset();
    обикновен.setFiscalFixedAssetLineList(new ArrayList<>());
    обикновен.setFixedAssetLineList(accountingLines("2027-01-31", "5000.000"));
    assertFalse(
        BgAssetSuspensionRules.isSuspensionExcluded(обикновен),
        "актив с остатъчна счетоводна стойност се спира по общия ред");

    FixedAsset напълноАмортизиран = new FixedAsset();
    напълноАмортизиран.setFixedAssetLineList(accountingLines("2027-01-31", "0.000"));
    assertTrue(
        BgAssetSuspensionRules.isSuspensionExcluded(напълноАмортизиран),
        "чл. 60 ал. 3 т. 1 — напълно амортизиран за счетоводни цели");

    FixedAsset подВдигнатПраг = new FixedAsset();
    подВдигнатПраг.setFixedAssetLineList(accountingLines("2027-01-31", "5000.000"));
    подВдигнатПраг.setBgMaterialityIncrease(new BgAssetMaterialityIncrease());
    assertTrue(
        BgAssetSuspensionRules.isSuspensionExcluded(подВдигнатПраг),
        "чл. 60 ал. 3 т. 2 — отписан заради увеличение в прага; ал. 4 покрива и него");

    // И следствието: за изключен актив дата на спиране НЕ се произвежда, дори да е обявена дата на
    // неползване и срокът да е изтекъл отдавна.
    assertNull(
        BgAssetSuspensionRules.suspensionFor(подВдигнатПраг, LocalDate.parse("2020-01-01"), null),
        "„Не се преустановява начисляването на данъчни амортизации за активите по чл. 60, ал. 3“");
    assertEquals(
        BigDecimal.ZERO,
        BgAssetSuspensionRules.reversalFor(подВдигнатПраг, LocalDate.parse("2020-01-01"), null),
        "и обръщане по ал. 2 също не се дължи");
  }

  @Test
  @DisplayName("липсващата дата не произвежда дати")
  void без_дата_няма_срок() {
    assertNull(BgAssetSuspensionRules.suspensionStart(null));
    assertNull(BgAssetSuspensionRules.lastChargedDay(null));
    assertNull(BgAssetSuspensionRules.reversalYear(null));
    assertNull(BgAssetSuspensionRules.resumptionStart(null));
    assertFalse(BgAssetSuspensionRules.isSuspensionDue(null, null, LocalDate.parse("2030-01-01")));
    assertEquals(BigDecimal.ZERO, BgAssetSuspensionRules.reversalAmount(new FixedAsset(), null));
  }

  // ── помощни ─────────────────────────────────────────────────────────────

  /** Актив с реализирани ДАНЪЧНИ редове по 250 EUR, месец след месец от дадената дата. */
  private static FixedAsset assetWithMonthlyTaxLines(String firstDate, int count) {
    FixedAsset asset = new FixedAsset();
    // ⚠️ ОТЧЕТНАТА СТОЙНОСТ Е ЗАДЪЛЖИТЕЛНА В ФИКСТУРАТА, и това е находка на теста.
    // BgAssetWriteOffRules.carryingValue пада обратно на grossValue, когато няма нито един
    // РЕАЛИЗИРАН счетоводен ред — вярното поведение: актив, на който още нищо не е начислено, носи
    // пълната си стойност. Но генерираният getter връща ZERO за незададена колона, тъй че актив без
    // отчетна стойност изглежда напълно амортизиран за счетоводни цели и чл. 59 ал. 4 го изключва
    // от спиране. Празната фикстура мереше точно това вместо правилото.
    asset.setGrossValue(new BigDecimal("10000.000"));
    List<FixedAssetLine> lines = new ArrayList<>();
    LocalDate date = LocalDate.parse(firstDate);
    for (int i = 0; i < count; i++) {
      lines.add(taxLine(date.toString(), FixedAssetLineRepository.STATUS_REALIZED));
      date = date.withDayOfMonth(1).plusMonths(2).minusDays(1);
    }
    asset.setFiscalFixedAssetLineList(lines);
    return asset;
  }

  private static FixedAssetLine taxLine(String date, int status) {
    FixedAssetLine line = new FixedAssetLine();
    line.setDepreciationDate(LocalDate.parse(date));
    line.setDepreciation(МЕСЕЧНА);
    line.setStatusSelect(status);
    return line;
  }

  private static List<FixedAssetLine> accountingLines(String date, String accountingValue) {
    List<FixedAssetLine> lines = new ArrayList<>();
    FixedAssetLine line = new FixedAssetLine();
    line.setDepreciationDate(LocalDate.parse(date));
    line.setAccountingValue(new BigDecimal(accountingValue));
    line.setStatusSelect(FixedAssetLineRepository.STATUS_REALIZED);
    lines.add(line);
    return lines;
  }
}
