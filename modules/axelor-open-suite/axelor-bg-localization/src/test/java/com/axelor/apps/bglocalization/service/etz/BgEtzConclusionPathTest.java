package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СКЛЮЧВАНЕТО С ДАННИ КАТО В ЖИВОТА — без база.
 *
 * <p>Нов договор основание за прекратяване няма, а сийдът носи КИД „62.01“ (не „6201“) — и
 * сключването трябва да минава с точно такива данни на т. 1 и т. 21. Тук са свидетелите за тези две
 * точки, за номенклатурите по ред. ДВ бр. 54/2026 и за отказа, който назовава полето и екрана.
 */
class BgEtzConclusionPathTest {

  private final BgEtzResolver resolver = new BgEtzResolver();

  /** Договорът, както го въвежда човек: без основание за прекратяване, КИД от сийда. */
  private static EmploymentContract newContract() {
    EmploymentContract contract = new BgEtzFixture().contract();
    contract.setEndOfContractReason(null);
    contract.setBgTerminationDate(null);
    BgNkid nkid = new BgNkid();
    nkid.setCode("62.01");
    contract.getPayCompany().setBgNkid(nkid);
    return contract;
  }

  @Test
  @DisplayName(
      "Нов договор (без основание за прекратяване, КИД „62.01“) → т. 1 = 01, т. 21 = 6201,"
          + " 0 пречки и 0 грешки срещу схемата")
  void aNewContractIsAValidConclusion() {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        resolver.resolve(BgEtzFacts.of(newContract()), BgEtzEventKind.CONCLUSION, gaps);

    assertTrue(gaps.isEmpty(), "пречки:\n" + BgEtzScreens.describe(gaps));
    BgEtzRecord row = file.getRows().get(0);
    assertEquals("01", row.get(BgEtz62Field.EMPLOYTYPE), "т. 1 — от вида договор (чл. 67 КТ)");
    assertEquals("6201", row.get(BgEtz62Field.CODEKID), "т. 21 — без точката");
    assertEquals(
        0, new BgEtzValidator().validate(new BgEtzEmitter().emit(file), file).size(), "XSD");
  }

  @Test
  @DisplayName("Видът договор назовава акта: членове от КТ → KT; ДУК, ГД и празно → няма")
  void theContractTypeNamesTheAct() {
    for (String type : new String[] {"67", "68", "111", "114", "121a"}) {
      assertEquals("KT", BgEtzGrounds.legalFamilyOfContractType(type), type);
    }
    for (String type : new String[] {"duk", "gd", "", null}) {
      assertNull(BgEtzGrounds.legalFamilyOfContractType(type), String.valueOf(type));
    }
  }

  @Test
  @DisplayName(
      "ДУК без основание за прекратяване: т. 1 и т. 12 — отказ, не трудово по подразбиране")
  void dukStillRefuses() {
    EmploymentContract contract = newContract();
    contract.setBgContractType("duk");
    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolve(BgEtzFacts.of(contract), BgEtzEventKind.CONCLUSION, gaps);
    String text = BgEtzScreens.describe(gaps);
    assertTrue(text.contains(BgEtz62Field.EMPLOYTYPE.label()), text);
    assertTrue(text.contains("не е трудов договор"), text);
  }

  // ── отказът за човек ──────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Липсващи ЕКАТТЕ, НКПД, КИД и работно време: всяко поле ВЕДНЪЖ, с причината и екрана")
  void everyGapNamesItsFieldAndScreenOnce() {
    EmploymentContract contract = newContract();
    contract.setBgWorkplaceEkatte(null);
    contract.setBgNkpd(null);
    contract.getPayCompany().setBgNkid(null);
    contract.setBgHoursPerDay(BigDecimal.ZERO);
    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolve(BgEtzFacts.of(contract), BgEtzEventKind.CONCLUSION, gaps);
    String text = BgEtzScreens.describe(gaps);

    for (BgEtz62Field field :
        new BgEtz62Field[] {
          BgEtz62Field.CODEEKATTE,
          BgEtz62Field.CODENKPD,
          BgEtz62Field.CODEKID,
          BgEtz62Field.CODEHOURS,
          BgEtz62Field.WORKINGHOURS
        }) {
      String line = lineOf(text, field.label());
      assertNotNull(line, field.label() + " е назован:\n" + text);
      assertTrue(line.contains(" → попълнете: " + BgEtzScreens.of(field)), line);
      assertEquals(
          text.indexOf("• " + field.label() + " "),
          text.lastIndexOf("• " + field.label() + " "),
          "един ред на поле:\n" + text);
    }
  }

  @Test
  @DisplayName("Всеки реквизит, който сключването по КТ взима от екран, има път до полето")
  void everyConclusionFieldHasAScreen() {
    for (BgEtz62Field field :
        new BgEtz62Field[] {
          BgEtz62Field.EIK,
          BgEtz62Field.EMPLOYTYPE,
          BgEtz62Field.EGN,
          BgEtz62Field.EGNTYPE,
          BgEtz62Field.FIRSTNAME,
          BgEtz62Field.THIRDNAME,
          BgEtz62Field.CONTRACTDATE,
          BgEtz62Field.CONTRACTTYPE,
          BgEtz62Field.BEGINDATE,
          BgEtz62Field.SALARY,
          BgEtz62Field.CODENKPD,
          BgEtz62Field.CODEKID,
          BgEtz62Field.CODEEKATTE,
          BgEtz62Field.CODEHOURS,
          BgEtz62Field.WORKINGHOURS,
          BgEtz62Field.VACATIONDAYS
        }) {
      assertNotNull(BgEtzScreens.of(field), field.label());
    }
  }

  // ── номенклатурите по ред. ДВ бр. 54/2026 ─────────────────────────────────

  @Test
  @DisplayName(
      "т. 12 и т. 27 по ред. ДВ бр. 54/2026: КТ — 20 и 64 кода без промяна; ЗДСл — 100–116 и"
          + " 198, вид 03 — само 199; т. 27 ЗДСл добави 129–133")
  void groundsFollowTheJune2026Edition() {
    assertEquals(20, BgEtzGrounds.contractCodesKt().size());
    assertEquals(64, BgEtzGrounds.endCodesKt().size());
    assertEquals(18, BgEtzGrounds.contractCodesZdsl().size());
    assertEquals(33, BgEtzGrounds.endCodesZdsl().size());

    assertTrue(BgEtzGrounds.isContractCode("116", "02"));
    assertTrue(BgEtzGrounds.isContractCode("198", "02"));
    assertFalse(BgEtzGrounds.isContractCode("117", "02"), "117–128 отпаднаха");
    assertFalse(BgEtzGrounds.isContractCode("128", "02"), "117–128 отпаднаха");
    // …а в т. 27 (прекратяване) 117 ОСТАВА — чл. 107 ал. 1 т. 3 ЗДСл: отпаднаха само от т. 12.
    assertTrue(BgEtzGrounds.isEndCode("117", "02"), "т. 27 приема 117");
    assertEquals("чл. 107 ал. 1 т. 3 ЗДСл", BgEtzGrounds.endCodesZdsl().get("117"));
    assertTrue(BgEtzGrounds.isContractCode("199", "03"));
    assertFalse(BgEtzGrounds.isContractCode("100", "03"), "вид 03 има само 199");
    assertTrue(BgEtzGrounds.isEndCode("129", "02"));
    assertTrue(BgEtzGrounds.isEndCode("133", "02"));
    assertTrue(
        BgEtzGrounds.contractCodesZdsl().get("100").contains("конкурс по чл. 10д"),
        "100 по новия текст");
    assertTrue(
        BgEtzGrounds.contractCodesZdsl().get("101").contains("връзки с обществеността"),
        "101 е с НОВ смисъл");
  }

  @Test
  @DisplayName("Срокът е три дни от подписването (чл. 62 ал. 3 КТ), не от началото на работа")
  void theDeadlineIsThreeDaysFromSigning() {
    EmploymentContract contract = newContract();
    contract.setSignatureDate(LocalDate.of(2026, 10, 30));
    contract.setStartDate(LocalDate.of(2026, 11, 9));
    assertEquals(LocalDate.of(2026, 11, 2), BgEtzConclusionService.dueDate(contract));
    contract.setSignatureDate(null);
    assertNull(BgEtzConclusionService.dueDate(contract), "без дата на подписване — без срок");
  }

  private static String lineOf(String text, String label) {
    for (String line : text.split("\n")) {
      if (line.startsWith("• " + label + " ")) {
        return line;
      }
    }
    return null;
  }
}
