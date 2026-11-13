package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Данъчните временни разлики за ГДД.
 *
 * <p>Еталонът е машина: ДАС 10 000 EUR, категория II (30% годишно), 250 EUR на месец, продадена на
 * 30.06.2028 г. Счетоводният борд дава 30 реализирани реда → начислено 7 500, СБС 2 500. Данъчният
 * е рязан един месец по-рано по чл. 60 ал. 2 → 29 реда, начислено 7 250, ДБС 2 750. Нетото по чл.
 * 66 е −250.
 */
class BgTaxDifferenceRulesTest {

  @Test
  @DisplayName("чл. 54: при счетоводен план = данъчен разликата е НУЛА")
  void чл_54_при_подразбиране() {
    // Категорията носи ЕДИН набор числа, тъй че счетоводният план следва нормата по чл. 55 и
    // двете амортизации съвпадат. Нулата не е случайна — тя е целта.
    assertEquals(
        BigDecimal.ZERO,
        BgTaxDifferenceRules.art54Adjustment(new BigDecimal("3000"), new BigDecimal("3000")),
        "+3 000 счетоводна − 3 000 данъчна = 0");
  }

  @Test
  @DisplayName("⚠️ чл. 54: посоката е +СЧЕТОВОДНА (ал. 2) − ДАНЪЧНА (ал. 1), не обратното")
  void чл_54_посоката_на_алинеите() {
    // Ал. 1 признава ДАНЪЧНИТЕ амортизации (минусът); ал. 2 увеличава резултата със СЧЕТОВОДНИТЕ
    // (плюсът). Разменени, знакът на цялата справка се обръща.
    // Счетоводен срок 6 години срещу данъчни 40 месеца: счетоводно 10 000/6 ≈ 1 666,67 годишно,
    // данъчно 3 000 → разликата е ОТРИЦАТЕЛНА, тоест намалява резултата.
    assertEquals(
        new BigDecimal("-1333.33"),
        BgTaxDifferenceRules.art54Adjustment(new BigDecimal("1666.67"), new BigDecimal("3000.00")),
        "по-дълъг счетоводен срок значи по-малко счетоводна амортизация от данъчната");
    // И обратният случай: счетоводен срок 2 години → 5 000 счетоводно срещу 3 000 данъчно.
    assertEquals(
        new BigDecimal("2000.00"),
        BgTaxDifferenceRules.art54Adjustment(new BigDecimal("5000.00"), new BigDecimal("3000.00")),
        "по-кратък счетоводен срок увеличава данъчния резултат");
  }

  @Test
  @DisplayName("чл. 66: +СБС 2 500 и −ДБС 2 750 — еталонът")
  void чл_66_еталонът() {
    BgFixedAssetWriteOff w = writeOff("2500.00", "2750.00", true);
    assertEquals(
        new BigDecimal("2500.00"),
        BgTaxDifferenceRules.art66Increase(w),
        "ал. 1 увеличава със счетоводната балансова стойност");
    assertEquals(
        new BigDecimal("2750.00"),
        BgTaxDifferenceRules.art66Decrease(w),
        "ал. 2 намалява с данъчната стойност");
    assertEquals(
        new BigDecimal("-250.00"),
        BgTaxDifferenceRules.art66Increase(w).subtract(BgTaxDifferenceRules.art66Decrease(w)),
        "нетото е −250 — едномесечната разлика от рязането по чл. 60 ал. 2");
  }

  @Test
  @DisplayName("⚠️ TIMING GATE: свален isArt60Al2Applied → −ДБС НЕ се чете в тази година")
  void gate_ът_на_чл_60_ал_3() {
    // Целият смисъл на флага. При изключение по чл. 60 ал. 3 активът е отписан счетоводно, но
    // ОСТАВА в ДАП и излиза от него по реда на ал. 1 — при пълно данъчно амортизиране (ал. 4).
    // Тоест +СБС идва сега, а −ДБС в ДРУГА, по-късна година.
    BgFixedAssetWriteOff w = writeOff("2500.00", "2750.00", false);
    assertEquals(
        new BigDecimal("2500.00"),
        BgTaxDifferenceRules.art66Increase(w),
        "увеличението по ал. 1 се дължи веднага — счетоводното отписване е факт");
    assertEquals(
        BigDecimal.ZERO,
        BgTaxDifferenceRules.art66Decrease(w),
        "но намалението по ал. 2 НЕ: активът още е в данъчния амортизационен план. Прочетено сега,"
            + " то би отишло в грешната година и би занижило данъка на тази.");
  }

  @Test
  @DisplayName("чл. 66 ал. 3: изключенията спират и двете страни")
  void изключенията_по_ал_3() {
    // т. 1–3 важат само когато ДБС ПРЕВИШАВА СБС; т. 4 (преобразуване) е безусловна.
    BgFixedAssetWriteOff липси = writeOff("2500.00", "2750.00", true);
    липси.setArt66ExceptionSelect(BgArt66Exception.SHORTAGE);
    assertFalse(BgTaxDifferenceRules.applies(липси), "ДБС 2 750 > СБС 2 500 → т. 1 се прилага");
    assertEquals(BigDecimal.ZERO, BgTaxDifferenceRules.art66Increase(липси));
    assertEquals(BigDecimal.ZERO, BgTaxDifferenceRules.art66Decrease(липси));

    BgFixedAssetWriteOff липсиОбратно = writeOff("2750.00", "2500.00", true);
    липсиОбратно.setArt66ExceptionSelect(BgArt66Exception.SHORTAGE);
    assertTrue(
        BgTaxDifferenceRules.applies(липсиОбратно),
        "ДБС 2 500 НЕ превишава СБС 2 750 → изключението по т. 1 не важи и чл. 66 се прилага");

    BgFixedAssetWriteOff преобразуване = writeOff("2500.00", "2750.00", true);
    преобразуване.setArt66ExceptionSelect(BgArt66Exception.REORGANISATION);
    assertFalse(
        BgTaxDifferenceRules.applies(преобразуване),
        "т. 4 е безусловна — без сравнение на стойности");
  }

  @Test
  @DisplayName("⚠️ чл. 59 ал. 2: непотвърденото е ВИДИМ ред, а не липса на ред")
  void чл_59_потвърждаването_е_гейт() {
    FixedAsset asset = new FixedAsset();
    asset.setBgArt59Al2Reversal(new BigDecimal("3000.000"));
    asset.setBgArt59Al2Year(2027);

    assertTrue(BgTaxDifferenceRules.isArt59Pending(asset), "изчислено, но непотвърдено");
    assertEquals(
        BigDecimal.ZERO,
        BgTaxDifferenceRules.art59Reversal(asset),
        "сумата НЕ влиза в сборовете, докато не е потвърдена");
    assertEquals(
        Integer.valueOf(2027),
        BgTaxDifferenceRules.art59Year(asset),
        "но годината си е там и редът се показва — забравеното потвърждение е пропусната"
            + " ЗАДЪЛЖИТЕЛНА корекция, тоест скрит данъчен пасив");

    asset.setBgIsArt59Al2Confirmed(Boolean.TRUE);
    assertFalse(BgTaxDifferenceRules.isArt59Pending(asset));
    assertEquals(new BigDecimal("3000.000"), BgTaxDifferenceRules.art59Reversal(asset));
  }

  @Test
  @DisplayName("⚠️ потвърждаването НЕ мести годината — тя е закована от ал. 2")
  void годината_не_се_мести() {
    // Ал. 2: „за годината, през която е изтекъл дванадесетмесечният срок по ал. 1“. Потвърждение
    // през 2029 г. за срок, изтекъл през 2027 г., значи КОРИГИРАЩА декларация за 2027 г., а не ред
    // в декларацията за 2029 г.
    FixedAsset asset = new FixedAsset();
    asset.setBgArt59Al2Reversal(new BigDecimal("3000.000"));
    asset.setBgArt59Al2Year(2027);
    asset.setBgIsArt59Al2Confirmed(Boolean.TRUE);
    asset.setBgArt59Al2ConfirmedOn(LocalDate.parse("2029-04-15"));
    assertEquals(
        Integer.valueOf(2027),
        BgTaxDifferenceRules.art59Year(asset),
        "годината остава 2027, независимо че потвърждението е от 2029");
  }

  @Test
  @DisplayName("данъчният план приключва в годината на ПОСЛЕДНИЯ реализиран ред")
  void кога_приключва_данъчният_план() {
    // Нужно, за да отиде отложеното −ДБС по чл. 60 ал. 3/ал. 4 в ВЯРНАТА година.
    FixedAsset asset = new FixedAsset();
    List<FixedAssetLine> lines = new ArrayList<>();
    lines.add(taxLine("2029-11-30", FixedAssetLineRepository.STATUS_REALIZED));
    lines.add(taxLine("2029-12-31", FixedAssetLineRepository.STATUS_REALIZED));
    asset.setFiscalFixedAssetLineList(lines);
    assertTrue(BgTaxDifferenceRules.taxPlanFinishedInYear(asset, 2029));
    assertFalse(BgTaxDifferenceRules.taxPlanFinishedInYear(asset, 2028), "не е приключил по-рано");

    // Планов ред след годината значи, че планът НЕ е приключил.
    lines.add(taxLine("2030-01-31", FixedAssetLineRepository.STATUS_PLANNED));
    assertFalse(
        BgTaxDifferenceRules.taxPlanFinishedInYear(asset, 2029),
        "остава още какво да се начисли — намалението по ал. 2 още не се дължи");
  }

  @Test
  @DisplayName("чл. 60 ал. 3 т. 2: активът се разпознава по СЪБИТИЕТО, не по стойност")
  void материалността_идва_от_събитието() {
    FixedAsset asset = new FixedAsset();
    asset.setGrossValue(new BigDecimal("800.000"));
    asset.setFixedAssetLineList(accountingLine("2027-12-31", "600.000"));
    assertFalse(
        BgAssetWriteOffRules.isArt60Al3Excluded(asset),
        "малката стойност сама по себе си не е основание");
    asset.setBgMaterialityIncrease(new BgAssetMaterialityIncrease());
    assertTrue(
        BgAssetWriteOffRules.isArt60Al3Excluded(asset),
        "със записано вдигане на прага активът остава в ДАП и амортизацията ПРОДЪЛЖАВА");
  }

  // ── помощни ─────────────────────────────────────────────────────────────

  private static BgFixedAssetWriteOff writeOff(String sbs, String dbs, boolean art60Al2) {
    BgFixedAssetWriteOff w = new BgFixedAssetWriteOff();
    w.setAccountingCarryingValue(new BigDecimal(sbs));
    w.setTaxValue(new BigDecimal(dbs));
    w.setIsArt60Al2Applied(art60Al2);
    w.setTaxYear(2028);
    return w;
  }

  private static FixedAssetLine taxLine(String date, int status) {
    FixedAssetLine line = new FixedAssetLine();
    line.setDepreciationDate(LocalDate.parse(date));
    line.setDepreciation(new BigDecimal("250.000"));
    line.setStatusSelect(status);
    return line;
  }

  private static List<FixedAssetLine> accountingLine(String date, String accountingValue) {
    List<FixedAssetLine> lines = new ArrayList<>();
    FixedAssetLine line = new FixedAssetLine();
    line.setDepreciationDate(LocalDate.parse(date));
    line.setAccountingValue(new BigDecimal(accountingValue));
    line.setStatusSelect(FixedAssetLineRepository.STATUS_REALIZED);
    lines.add(line);
    return lines;
  }
}
