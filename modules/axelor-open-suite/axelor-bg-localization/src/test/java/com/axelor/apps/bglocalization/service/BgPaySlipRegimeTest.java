package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.contract;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.draft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipRegimeChange;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАМРАЗЕНИЯТ РЕЖИМ НА ОСИГУРЯВАНЕ — вноските и данъкът четат лицето и правоотношението ОТ ФИША.
 *
 * <p>Дотук видът осигурен, категорията, кохортата, изборът за ДЗПО, НКИД/НКПД, работното време,
 * нормата разходи, стоящият доход при друг платец и ТЕЛК се четяха ЖИВИ при всяко пресмятане и
 * потвърждаване. Коригиращо копие за март, направено през октомври, щеше да осигурява по
 * октомврийския договор. Истинската услуга с истинския {@code payrollInput} и истинския сглобител
 * ({@link BgSeededPaySlipService}); подменена е само базата.
 *
 * <p>Числата: вид 01, трета категория, роден след 1959, с ДЗПО — лично 13,78 %; данък 10 %; месечно
 * облекчение по ТЕЛК 337,45 (DISABILITY_RELIEF_MONTHLY, сийд).
 */
class BgPaySlipRegimeTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static void same(String expected, BigDecimal actual, String what) {
    assertEquals(
        0, bd(expected).compareTo(actual), what + ": очаквах " + expected + ", а е " + actual);
  }

  /** Делтата и статията на ретро-потвърждаването търсят текущия фиш в базата — вън от теста. */
  private static BgSeededPaySlipService wired() {
    return new BgSeededPaySlipService() {
      @Override
      protected void settleRetroDelta(BgPaySlip retro) {}

      @Override
      protected void generatePayrollMove(BgPaySlip slip) {}

      @Override
      protected void closeAfterAdjustment(Period period) {}
    };
  }

  /** Потвърден мартенски фиш от 1500,00, пресметнат по общия път — режимът е замразен. */
  private static BgPaySlip confirmedOriginal(BgSeededPaySlipService s) throws AxelorException {
    BgPaySlip original = draft(contract());
    s.calculate(original);
    s.confirm(original);
    return original;
  }

  /** ДНЕШНИТЕ данни се сменят СЛЕД пресмятането: друг вид, друга кохорта, вдигнат ТЕЛК. */
  private static void changeTheLiveData(BgPaySlip slip) {
    EmploymentContract c = slip.getEmploymentContract();
    c.setBgInsuredTypeCode("14");
    c.getEmployee().setBgLaborCategory("1");
    c.getEmployee().setBgBornAfter1959(false);
    c.getEmployee().setBgDisabilityRelief(true);
    c.getEmployee().setBgDisabilityValidUntil(null);
  }

  private static List<BgPaySlipRegimeChange> changesOf(BgPaySlip copy, String field) {
    List<BgPaySlipRegimeChange> out = new ArrayList<>();
    if (copy.getBgRegimeChangeList() != null) {
      for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
        if (field.equals(c.getFieldName())) {
          out.add(c);
        }
      }
    }
    return out;
  }

  // ═══ РЕДОВЕН ФИШ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("calculate() замразява режима от договора и служителя")
  void calculateFreezesTheRegime() {
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    assertNull(slip.getBgRegimeFrozenAt(), "котвата: новата чернова няма режим");

    s.calculate(slip);

    assertNotNull(slip.getBgRegimeFrozenAt());
    assertEquals(BgPayrollRegime.CALCULATED, slip.getBgRegimeSource());
    assertEquals("01", slip.getBgRegimeInsuredTypeCode());
    assertEquals("3", slip.getBgRegimeLaborCategory());
    assertTrue(slip.getBgRegimeBornAfter1959());
    assertEquals(BgContributionRequest.NON_TEACHER, slip.getBgRegimeTeacherScope());
    assertEquals("1", slip.getBgRegimeWorkTimeMode());
    same("8.00", slip.getBgRegimeHoursPerDay(), "часове на ден");
    assertEquals(Boolean.FALSE, slip.getBgRegimeDisabilityRelief());
  }

  @Test
  @DisplayName("редовна чернова: НКПД и ТЕЛК сменени след пресмятането → confirm ги назовава")
  void aRegimeChangedAfterTheCalculationRefusesConfirm() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    s.calculate(slip);
    same("129.33", slip.getBgTaxAmount(), "котвата: 10 % от (1500,00 − 206,70)");

    // Договорът получава НКПД, служителят — решение на ТЕЛК, СЛЕД пресмятането.
    BgNkpd nkpd = new BgNkpd();
    nkpd.setCode("24216003");
    slip.getEmploymentContract().setBgNkpd(nkpd);
    slip.getEmploymentContract().getEmployee().setBgDisabilityRelief(true);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertEquals(
        "Режимът е сменен след пресмятането: НКПД — → 24216003; облекчение по ТЕЛК не → да."
            + " Натиснете „Преизчисли“ и потвърдете отново.",
        e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "тихо потвърждаване — НЕ");

    // „Преизчисли“ замразява новото, и потвърждаването минава с него.
    s.calculate(slip);
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
    assertEquals("24216003", slip.getBgRegimeNkpdCode());
    assertTrue(slip.getBgRegimeDisabilityRelief());
    // (1500,00 − 206,70 − 337,45) × 10 % = 955,85 × 10 % = 95,585 → 95,59.
    same("95.59", slip.getBgTaxAmount(), "данъкът — вече с облекчението по ТЕЛК");
  }

  @Test
  @DisplayName("отказът назовава сменения вид осигурен: 01 → 12")
  void theRefusalNamesTheChangedInsuredType() {
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    s.calculate(slip);
    assertNull(BgPayrollRegime.driftRefusal(slip, s.liveRegime(slip)), "без промяна — без отказ");

    slip.getEmploymentContract().setBgInsuredTypeCode("12");
    assertEquals(
        "Режимът е сменен след пресмятането: вид осигурен 01 → 12. Натиснете „Преизчисли“ и"
            + " потвърдете отново.",
        BgPayrollRegime.driftRefusal(slip, s.liveRegime(slip)));
  }

  @Test
  @DisplayName("чернова, пресметната преди замразяването, не се потвърждава по живите данни")
  void aDraftCalculatedBeforeTheFreezeIsNotConfirmedOnLiveData() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    s.calculate(slip);
    // Заварената чернова: отпечатък има, режим няма — пресметната е от кода отпреди замразяването.
    slip.setBgRegimeFrozenAt(null);
    s.seenRequest = null;

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    String refusal = e.getMessage();
    assertTrue(refusal.startsWith("Режимът на осигуряване на фиша не е замразен"), refusal);
    assertTrue(refusal.contains("Натиснете „Преизчисли“"), refusal);
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect());
    assertNull(s.seenRequest, "вноските НЕ са питани — нито по живия договор");

    s.calculate(slip);
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect(), "след „Преизчисли“ минава");
  }

  // ═══ КОПИЕ ═══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("копието наследява замразения режим, а вноските и данъкът го ползват, не живото")
  void aCopyInheritsTheRegimeAndTheContributionsUseIt() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip original = confirmedOriginal(s);
    changeTheLiveData(original);

    BgPaySlip copy = s.retroCopyOf(original, false);
    assertEquals(BgPayrollRegime.COPIED, copy.getBgRegimeSource());
    assertEquals("01", copy.getBgRegimeInsuredTypeCode());
    assertEquals("3", copy.getBgRegimeLaborCategory());
    assertTrue(copy.getBgRegimeBornAfter1959());

    s.seenRequest = null;
    s.contributions(copy);
    assertEquals("01", s.seenRequest.getInsuredTypeCode(), "видът — замразеният, не 14");
    assertEquals("3", s.seenRequest.getLaborCategory(), "категорията — замразената, не 1");
    assertTrue(s.seenRequest.isBornAfter1959(), "кохортата — замразената");
    same("0", s.disabilityRelief(copy), "ТЕЛК, отметнат днес, не влиза в минал месец");

    s.recompute(copy);
    same("129.33", copy.getBgTaxAmount(), "данъкът на копието — по режима на оригинала");
  }

  @Test
  @DisplayName("копието НЕ се сравнява с живите данни: сменен договор, копието се потвърждава")
  void aCopyIsNotComparedWithTheLiveData() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip original = confirmedOriginal(s);
    changeTheLiveData(original);

    BgPaySlip copy = s.retroCopyOf(original, false);
    s.calculate(copy);
    s.confirm(copy);

    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect(), "режимът на онзи месец");
    assertEquals("01", copy.getBgRegimeInsuredTypeCode(), "не 14 от днешния договор");
    same("129.33", copy.getBgTaxAmount(), "без ТЕЛК, отметнат днес");
  }

  @Test
  @DisplayName("копие с ръчно отметнат ТЕЛК → данъкът с облекчението, а одитът има реда")
  void aCopyWithAManuallyChangedReliefIsAudited() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip original = confirmedOriginal(s);
    BgPaySlip copy = s.retroCopyOf(original, false);

    // Операторът отмята ТЕЛК в режима на копието; записът минава през одита (както
    // BgPaySlipManagementRepository#save), после формата пресмята.
    copy.setBgRegimeDisabilityRelief(true);
    LocalDateTime at = LocalDateTime.of(2026, 10, 5, 18, 0);
    BgPaySlipRegimeAudit.sync(copy, null, at);
    s.calculate(copy);

    // (1500,00 − 206,70 − 337,45) × 10 % = 955,85 × 10 % = 95,585 → 95,59.
    same("955.85", copy.getBgTaxBase(), "основата с облекчението по ТЕЛК");
    same("95.59", copy.getBgTaxAmount(), "данъкът с облекчението");

    List<BgPaySlipRegimeChange> rows = changesOf(copy, "bgRegimeDisabilityRelief");
    assertEquals(1, rows.size(), "един ред за ТЕЛК");
    assertEquals("не", rows.get(0).getOldValue());
    assertEquals("да", rows.get(0).getNewValue());
    assertEquals(BgPaySlipRegimeAudit.MANUAL, rows.get(0).getSource());
    assertEquals(at, rows.get(0).getChangedOn());
    assertEquals(
        "Режимът е променен спрямо оригинала: облекчение по ТЕЛК не → да.",
        copy.getBgRegimeChangeSummary());

    BgPaySlipRegimeAudit.sync(copy, null, at.plusMinutes(1));
    assertEquals(1, changesOf(copy, "bgRegimeDisabilityRelief").size(), "без промяна — без ред");
  }

  @Test
  @DisplayName("копието остава по чл. 42, когато договорът по-късно стане граждански (код 14)")
  void aCopyKeepsTheFrozenTaxArticle() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip original = draft(contract());
    // Вид доход с 25 % нормативни разходи — за вид 01 той не значи нищо, за вид 14 мени основата.
    BgIncomeType incomeType = new BgIncomeType();
    incomeType.setExpenseRate(new BigDecimal("25.00"));
    original.getEmploymentContract().setBgIncomeType(incomeType);
    s.calculate(original);
    s.confirm(original);
    same("129.33", original.getBgTaxAmount(), "котвата: чл. 42, 10 % от (1500,00 − 206,70)");

    original.getEmploymentContract().setBgInsuredTypeCode("14");
    BgPaySlip copy = s.retroCopyOf(original, false);
    s.recompute(copy);

    // По чл. 43 щеше да е 10 % от (1500,00 − 375,00 − 206,70) = 91,83.
    same("129.33", copy.getBgTaxAmount(), "копието — по замразения вид 01, тоест чл. 42");
  }

  @Test
  @DisplayName("ръчният МОД на лицето и МОД редът на фирмата са в режима, не живи")
  void theMinIncomeOverridesAreFrozen() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    Company company = new Company();
    company.setBgMinIncomeRowCodeOverride("12");
    slip.getEmploymentContract().setPayCompany(company);
    slip.getEmploymentContract().getEmployee().setBgMinIncomeOverride(new BigDecimal("1800.00"));
    s.calculate(slip);
    assertEquals("12", slip.getBgRegimeMinIncomeRow());
    same("1800.00", slip.getBgRegimeMinIncomeOverride(), "ръчният МОД е замразен");

    // След пресмятането: друг ред, друг ръчен МОД.
    company.setBgMinIncomeRowCodeOverride("13");
    slip.getEmploymentContract().getEmployee().setBgMinIncomeOverride(new BigDecimal("2000.00"));
    same("1800.00", s.minIncomeOverrideOf(slip), "МОД пода чете замразеното");
    assertEquals("12", s.minIncomeRowOf(slip), "и реда — замразения");
    assertEquals(
        "Режимът е сменен след пресмятането: МОД ред на фирмата 12 → 13; ръчен МОД на лицето"
            + " 1800,00 → 2000,00. Натиснете „Преизчисли“ и потвърдете отново.",
        BgPayrollRegime.driftRefusal(slip, s.liveRegime(slip)));

    s.calculate(slip);
    s.confirm(slip);
    BgPaySlip copy = s.retroCopyOf(slip, false);
    company.setBgMinIncomeRowCodeOverride("14");
    slip.getEmploymentContract().getEmployee().setBgMinIncomeOverride(BigDecimal.ZERO);
    same("2000.00", s.minIncomeOverrideOf(copy), "копието наследява ръчния МОД на онзи месец");
    assertEquals("13", s.minIncomeRowOf(copy), "и реда на онзи месец");
  }

  // ═══ ЗАВАРЕН ФИШ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("заварен фиш: копието отказва с изброени полета; „Попълни“ попълва, одит, смята")
  void aLegacyCopyRefusesUntilFilled() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip original = confirmedOriginal(s);
    // Заварен: потвърден преди замразяването — режимът му е празен.
    original.setBgRegimeFrozenAt(null);
    original.setBgRegimeSource(null);
    BgPaySlip copy = s.retroCopyOf(original, false);
    assertNull(copy.getBgRegimeFrozenAt(), "котвата: копието няма режим");

    String expected =
        "Режимът на осигуряване на коригиращото копие не е замразен — фишът е отпреди"
            + " замразяването, а живите данни на договора и служителя не се четат тихо за минал"
            + " месец. Липсват: вид осигурен, категория труд, роден след 1959, избор за ДЗПО,"
            + " обхват на осигуряването, учителство, НКПД, НКИД, раздел по НКИД за ТЗПБ, МОД ред"
            + " на фирмата, режим на работното време, часове на ден, дни в седмицата, нормативни"
            + " разходи, осигурен на друго основание, стоящ доход при друг платец, ръчен МОД на"
            + " лицето, облекчение по ТЕЛК, ТЕЛК валидно до."
            + " Натиснете „Попълни от текущите данни“, проверете стойностите и пресметнете отново.";
    BgRegimeMissingException calc =
        assertThrows(BgRegimeMissingException.class, () -> s.calculate(copy));
    assertEquals(expected, calc.getMessage());
    AxelorException confirm = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertEquals(expected, confirm.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect());

    s.fillRegimeFromCurrent(copy, null);

    assertEquals(BgPayrollRegime.FILLED, copy.getBgRegimeSource());
    assertNotNull(copy.getBgRegimeFrozenAt());
    assertEquals("01", copy.getBgRegimeInsuredTypeCode(), "попълнено от текущия договор");
    assertEquals(
        BgPayrollRegime.fieldsFor(copy).size(),
        copy.getBgRegimeChangeList().size(),
        "по един ред на поле");
    for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
      assertEquals(BgPaySlipRegimeAudit.FILLED, c.getSource(), c.getFieldName());
      assertEquals("—", c.getOldValue(), c.getFieldName());
      assertEquals(LocalDate.now(), c.getChangedOn().toLocalDate(), c.getFieldName());
    }
    assertTrue(
        copy.getBgRegimeChangeSummary().startsWith("Режимът е попълнен от текущите данни на "),
        copy.getBgRegimeChangeSummary());

    s.calculate(copy);
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());

    AxelorException again =
        assertThrows(AxelorException.class, () -> s.fillRegimeFromCurrent(copy, null));
    assertTrue(again.getMessage().contains("копие в чернова"), again.getMessage());
  }

  // ═══ „ОТКАЗАН → ОТКАЗАН“ ═════════════════════════════════════════════════

  @Test
  @DisplayName("отказан фиш не се отказва втори път — причината на първия остава")
  void aCancelledSlipIsNotCancelledAgain() {
    assertEquals(
        "Фишът вече е отказан — втори отказ би подменил причината на първия.",
        BgPaySlipResolver.refuseTransition(
            BgPaySlipResolver.CANCELLED, BgPaySlipResolver.CANCELLED));
    BgSeededPaySlipService s = wired();
    BgPaySlip slip = draft(contract());
    slip.setStatusSelect(BgPaySlipResolver.CANCELLED);
    slip.setBgCancelReason("първата причина");
    AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(slip, "втора"));
    assertEquals(
        "Фишът вече е отказан — втори отказ би подменил причината на първия.", e.getMessage());
    assertEquals("първата причина", slip.getBgCancelReason());
  }

  @Test
  @DisplayName("отказано обезщетение не се отказва втори път — причината на първия остава")
  void aCancelledCompensationIsNotCancelledAgain() {
    assertEquals(
        "Документът вече е отказан — втори отказ би подменил причината на първия.",
        BgCompensationResolver.refuseTransition(
            BgCompensationResolver.CANCELLED, BgCompensationResolver.CANCELLED));
    BgCompensationService service =
        new BgCompensationService() {
          @Override
          protected void save(BgCompensation doc) {}
        };
    BgCompensation doc = new BgCompensation();
    doc.setStatusSelect(BgCompensationResolver.CANCELLED);
    doc.setBgCancelReason("първата причина");
    AxelorException e = assertThrows(AxelorException.class, () -> service.cancel(doc, "втора"));
    assertEquals(
        "Документът вече е отказан — втори отказ би подменил причината на първия.", e.getMessage());
    assertEquals("първата причина", doc.getBgCancelReason());
  }
}
