package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Тестове на ДВЕТЕ условни правила на реда с обезщетение: „изключеното иска причина“ и
 * „потвърденото иска доказване, когато видът го изисква“.
 *
 * <p>Правилата са извадени като чисти статични проверки точно за да се тестват без база — същият
 * шев като между резолверите и услугите в модула.
 */
class BgTerminationCompensationManagementRepositoryTest {

  @Test
  void excludedWithoutAReasonIsRefused() {
    assertTrue(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("EXCLUDED", null));
    assertTrue(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("EXCLUDED", ""));
    assertTrue(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("EXCLUDED", "   "));
  }

  @Test
  void excludedWithAReasonPasses() {
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason(
            "EXCLUDED", "по споразумение с работника"));
  }

  /** Предложеният ред няма какво да обяснява — пасажът го е създал сам. */
  @Test
  void proposedWithoutAReasonPasses() {
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("PROPOSED", null));
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("CONFIRMED", null));
    assertFalse(BgTerminationCompensationManagementRepository.isMissingExclusionReason(null, null));
  }

  // ═══ ДОКАЗВАНЕТО (чл. 222 ал. 1 КТ) ══════════════════════════════════════

  private static final LocalDate PROVED = LocalDate.of(2026, 4, 15);

  @Test
  @DisplayName("⚠️ requiresProof + CONFIRMED без дата на доказване → ОТКАЗВА СЕ")
  void confirmedWithoutProofIsRefused() {
    assertTrue(
        BgTerminationCompensationManagementRepository.isMissingProof("CONFIRMED", true, null),
        "⚠️ Това е самата причина полето да съществува. Обезщетението по чл. 222 ал. 1 КТ се дължи"
            + " само ако лицето ДЕЙСТВИТЕЛНО остане без работа, тоест отговорът НЕ съществува към"
            + " датата на прекратяването. Потвърден без дата, редът твърди доказано право на ден, в"
            + " който доказване не е могло да има — и нищо на екрана не изглежда сгрешено.");
  }

  @Test
  @DisplayName("requiresProof + CONFIRMED С дата → минава")
  void confirmedWithProofPasses() {
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingProof("CONFIRMED", true, PROVED));
  }

  @Test
  @DisplayName("⚠️ вид БЕЗ requiresProof (чл. 224 ал. 1) → CONFIRMED без дата минава, не се пипа")
  void aTypeThatNeedsNoProofIsUntouched() {
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingProof("CONFIRMED", false, null),
        "⚠️ Неползваният отпуск по чл. 224 ал. 1 е ИЗЧИСЛИМ в деня на прекратяването — той се дължи,"
            + " защото правоотношението свърши, а не защото нещо се е случило после. Дата на"
            + " доказване за него е безсмислена и искането ѝ би блокирало обичайния случай.");
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingProof("CONFIRMED", null, null),
        "⚠️ null се чете като „не изисква“, а не като „не знаем“. Генерираният getter за Boolean"
            + " връща FALSE и за непопълнено поле, тоест другият прочит би бил мъртъв код — и щеше"
            + " да иска доказване от всеки вид, за който никой не е казал нищо.");
  }

  @Test
  @DisplayName("⚠️ PROPOSED е състоянието „ЧАКА доказване“ — то НЕ иска дата")
  void proposedIsTheWaitingStateAndNeedsNoProof() {
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingProof("PROPOSED", true, null),
        "⚠️ Точно това е причината трето състояние да НЕ се въвежда: PROPOSED вече значи „чака"
            + " доказване“. Проверка и тук би направила невъзможно самото състояние, в което"
            + " автоматичният пасаж създава реда — тоест всеки запис на прекратен договор щеше да"
            + " пада.");
  }

  @Test
  @DisplayName("⚠️ EXCLUDED пътят остава непокътнат — двете правила не се засичат")
  void theExclusionPathIsUnaffected() {
    // Изключеният ред не се плаща, тоест доказване не му трябва — и обратното: причината за
    // изключване не се иска от потвърдения. Двете проверки гледат различни състояния нарочно.
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingProof("EXCLUDED", true, null),
        "изключено обезщетение по чл. 222 ал. 1 не иска доказване — то няма да се плати");
    assertFalse(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("CONFIRMED", null));
    assertTrue(
        BgTerminationCompensationManagementRepository.isMissingExclusionReason("EXCLUDED", null));
  }
}
