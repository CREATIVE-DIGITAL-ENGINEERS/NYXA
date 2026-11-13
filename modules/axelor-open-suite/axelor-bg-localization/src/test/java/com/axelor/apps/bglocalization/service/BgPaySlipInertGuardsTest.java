package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ДВАТА ПРЕДПАЗИТЕЛЯ НЕ СА ИНЕРТНИ.
 *
 * <p>{@code BgPaySlipService#contributions} и {@code #minIncome} са навързани шевове, а {@code
 * contributionRefusal} и {@code minIncomeRefusal} наистина се задействат. Тестът пази това — че
 * шевът НЕ Е върнат обратно към {@code return null}. Разкачи ли го някой, тук става червено, вместо
 * предпазителят тихо да замлъкне.
 *
 * <p><b>⚠️ ЗАЩО ТОВА СЕ ПРОВЕРЯВА БЕЗ БАЗА.</b> Фиш БЕЗ трудов договор не стига до нито един
 * репозиторий: {@code payrollInput} се отказва на първата проверка. Тоест точно този вход изважда
 * наяве дали шевът е жив, без Postgres, без Guice и без Hibernate — а именно живостта му е
 * твърдението. Числата на истински фиш са в {@code BgPaySlipContributionWiringTest}.
 */
class BgPaySlipInertGuardsTest {

  @Test
  @DisplayName("⚠️ Шевът contributions() ВРЪЩА резултат — и отказът проговаря")
  void theContributionSeamIsWiredAndItsRefusalSpeaks() {
    BgPaySlipService service = new BgPaySlipService();
    BgPaySlip slip = new BgPaySlip();

    BgContributionResult result = service.contributions(slip);
    assertNotNull(
        result,
        "шевът contributions() пак връща null — предпазителят за осигуровките е РАЗКАЧЕН."
            + " Върнете навързването; инертният шев изглежда точно като работеща проверка.");
    assertEquals(
        BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
        result.getStatus(),
        "фиш без договор и период не може да сглоби вход — това НЕ е NO_CONTRIBUTIONS_FOUND:"
            + " каталогът изобщо не е питан, а второто би пратило читателя да поправя seed-а");

    // ⚠️ СЛЕДСТВИЕТО: отказът НЕ мълчи.
    String refusal = service.contributionRefusal(slip);
    assertNotNull(
        refusal,
        "contributionRefusal мълчи за фиш, чиито осигуровки не са изчислени — точно тихият провал,"
            + " който този клас съществува да лови");
    assertTrue(refusal.contains("Осигуровките не са изчислени докрай"), refusal);
    assertTrue(refusal.contains("INPUT_NOT_ASSEMBLED"), refusal);
    assertTrue(refusal.contains("трудов договор"), refusal);
  }

  @Test
  @DisplayName("⚠️ Шевът minIncome() е навързан — и мълчи САМО там, където друг отказ говори")
  void theMinIncomeSeamIsWiredAndItsSilenceIsAccountedFor() {
    BgPaySlipService service = new BgPaySlipService();
    BgPaySlip slip = new BgPaySlip();

    // ⚠️ ЕДИНСТВЕНАТА ОСТАНАЛА ТИШИНА, И ТЯ Е ИЗБРАНА. Без договор и период МОД няма какво да
    // търси, а BgMinIncomeResult няма състояние „входът не се сглоби“ — за разлика от
    // BgContributionResult, който получи INPUT_NOT_ASSEMBLED. Вместо да се измисля второ такова
    // състояние, причината се съобщава ВЕДНЪЖ, от съседния отказ: два отказа за една причина
    // карат читателя да поправя два пъти едно нещо.
    assertNull(service.minIncome(slip), "без договор МОД няма какво да търси");
    assertNull(service.minIncomeRefusal(slip), "и мълчи — причината я казва contributionRefusal");
    assertNotNull(
        service.contributionRefusal(slip),
        "⚠️ КОТВАТА на горното: тишината на МОД е приемлива САМО докато съседът говори. Замлъкне"
            + " ли и той, фиш без договор минава без нито един отказ — и тогава това твърдение"
            + " трябва да стане червено, а не да остане обяснение защо мълчанието е наред.");
  }

  @Test
  @DisplayName("⚠️ Липсващият вид осигурен стига до отказа ДОСЛОВНО, не преразказан")
  void theMissingInsuredTypeReasonTravelsVerbatim() {
    // Един текст, един източник. Причината живее в BgPayrollInputBuilder.REFUSALS и се ПРЕПРАЩА;
    // преразказана тук, тя щеше да се разминава с формата, на която операторът я чете.
    String reason = BgPayrollInputBuilder.REFUSALS.get("insuredTypeCode");
    assertNotNull(reason, "отказът за липсващ вид осигурен е изчезнал от REFUSALS");
    assertTrue(reason.contains("EmploymentContract.bgInsuredTypeCode"), reason);
    assertTrue(reason.contains("01 НЕ Е ПОДРАЗБИРАНЕ"), reason);
  }
}
