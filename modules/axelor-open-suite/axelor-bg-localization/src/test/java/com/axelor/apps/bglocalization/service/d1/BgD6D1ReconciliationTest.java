package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВЕРКАТА Д6 ↔ Д1 — тестово дружество, 09/2026.
 *
 * <p>P0041: FISH-2026-0010, 1500 → ДОО 297,00. P0043: FISH-2026-0014, 1700 → 336,60, и коригиращият
 * FISH-2026-0019, 1840 → 364,32. Сборът на трите е 997,92 — т. 10 на Д6.
 */
class BgD6D1ReconciliationTest {

  private static final String PERIOD = "09/2026";

  private final EmploymentContract p0041 = contract(41L);
  private final EmploymentContract p0043 = contract(43L);
  private final BgPaySlip fish0010 = slip(10L, "FISH-2026-0010", p0041, "297.00");
  private final BgPaySlip fish0014 = slip(14L, "FISH-2026-0014", p0043, "336.60");
  private final BgPaySlip fish0019 = slip(19L, "FISH-2026-0019", p0043, "364.32");

  @Test
  @DisplayName("⚠️ Д1 по оригинала, Д6 по корекцията → отказ с текста и разликата по точки")
  void aD1GeneratedBeforeTheCorrectionIsRefused() {
    String refusal =
        BgD6D1Reconciliation.refuse(
            PERIOD, Arrays.asList(fish0010, fish0019), records(fish0010, fish0014));

    assertNotNull(refusal, "Д1 сочи FISH-2026-0014, който вече не е действащ");
    assertTrue(
        refusal.startsWith(
            "Д1 за „09/2026“ е генерирана преди корекцията на FISH-2026-0014;"
                + " генерирайте Д1 наново."),
        refusal);
    assertTrue(
        refusal.contains("т. 10: +27.72"),
        "⚠️ разликата е 364,32 − 336,60 — само лицето, което Д1 носи: " + refusal);
  }

  @Test
  @DisplayName("Д1 наново по действащите → сверката минава")
  void aD1OnTheEffectiveSlipsPasses() {
    assertNull(
        BgD6D1Reconciliation.refuse(
            PERIOD, Arrays.asList(fish0010, fish0019), records(fish0010, fish0019)));
  }

  @Test
  @DisplayName("⚠️ действащ фиш БЕЗ запис в Д1 (кодове 15/88/89) е допустим")
  void anEffectiveSlipWithoutARecordIsAllowed() {
    BgPaySlip code88 = slip(88L, "FISH-2026-0088", contract(88L), "120.00");

    assertNull(
        BgD6D1Reconciliation.refuse(
            PERIOD, Arrays.asList(fish0010, fish0019, code88), records(fish0010, fish0019)),
        "⚠️ Д1 нарочно отказва кодове 15, 88 и 89 (данните подава НОИ), а вноските им влизат в"
            + " Д6. Сверка по всички действащи би обявила всяко такова лице за разминаване.");
  }

  @Test
  @DisplayName("⚠️ заличен оригинал · Д1 още го носи → отказ, разликата е цялата му вноска")
  void aRecordOnADeletedOriginalIsRefused() {
    String refusal =
        BgD6D1Reconciliation.refuse(
            PERIOD, Collections.singletonList(fish0010), records(fish0010, fish0014));

    assertNotNull(refusal);
    assertTrue(refusal.contains("FISH-2026-0014"), refusal);
    assertTrue(refusal.contains("т. 10: -336.60"), refusal);
  }

  @Test
  @DisplayName("Д1 без записи → сверката няма какво да каже (Д6 обявява липсата отделно)")
  void noRecordsNoRefusal() {
    assertNull(
        BgD6D1Reconciliation.refuse(
            PERIOD, Arrays.asList(fish0010, fish0019), Collections.emptyList()));
    assertNull(BgD6D1Reconciliation.refuse(PERIOD, Arrays.asList(fish0010), null));
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  private static EmploymentContract contract(Long id) {
    EmploymentContract contract = new EmploymentContract();
    contract.setId(id);
    return contract;
  }

  /** Фиш с ЕДНО перо на ДОО (фонд „Пенсии“) — цялата вноска за т. 10. */
  private static BgPaySlip slip(
      Long id, String reference, EmploymentContract contract, String doo) {
    BgContributionFund pension = new BgContributionFund();
    pension.setCode("PENSION");
    pension.setBgD1Field(BgD1Point.T22_DOO);
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(500);
    line.setBgSign(BgPaySlipResolver.CREDIT);
    line.setBgAmount(new BigDecimal(doo));
    line.setBgContributionFund(pension);
    BgPaySlip slip = new BgPaySlip();
    slip.setId(id);
    slip.setReference(reference);
    slip.setEmploymentContract(contract);
    slip.setBgPaySlipLineList(new ArrayList<>(Collections.singletonList(line)));
    return slip;
  }

  private static List<BgD1Record> records(BgPaySlip... slips) {
    List<BgD1Record> out = new ArrayList<>();
    for (BgPaySlip slip : slips) {
      BgD1Record record = new BgD1Record();
      record.setPaySlip(slip);
      out.add(record);
    }
    return out;
  }

  @Test
  @DisplayName("сборът на трите фиша е 997,92 — т. 10 на Д6, за проверка на фикстурата")
  void theFixtureReproducesTheExpectedTotal() {
    assertEquals(
        0,
        new BigDecimal("997.92")
            .compareTo(
                BgD6Aggregator.byPoint(Arrays.asList(fish0010, fish0014, fish0019))
                    .get(BgD6Point.T10_DOO)));
  }
}
