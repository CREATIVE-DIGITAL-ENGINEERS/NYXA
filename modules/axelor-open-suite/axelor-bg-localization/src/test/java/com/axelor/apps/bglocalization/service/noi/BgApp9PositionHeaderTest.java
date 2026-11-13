package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДЛЪЖНОСТТА В ЗАГЛАВНАТА ЧАСТ — отказът е ПОИМЕНЕН и сочи полето „Длъжност“ на декларатора.
 *
 * <p>Кодът „R“ (законен представител) има един знак: той е точно стойността, която Прил. 9
 * получаваше дотук от „Качество“, и трябва да спре ТУК, а не чак в XSD-то.
 */
class BgApp9PositionHeaderTest {

  private static BgApp9EmitterContext withPosition(String position) {
    return BgApp9EmitterContext.builder()
        .flagDelegated("0")
        .flagKasa("0")
        .bulstatKasa(BgApp9Fixture.COMPANY_BULSTAT)
        .bulName("Никса ЕООД")
        .bulstatMember("")
        .nssiRo(Integer.valueOf(22), "София-град")
        .address(
            "София-град",
            "Столична",
            "София",
            "1000",
            "бул. Витоша 1",
            "029020506",
            "0888123456",
            "office@example.bg")
        .representative("Иван", "Георгиев", "Петров", position, "София")
        .certificateDate(LocalDate.of(2026, 6, 10))
        .exportDate(LocalDate.of(2026, 6, 12))
        .savedAt(LocalDateTime.of(2026, 6, 12, 9, 30, 0))
        .source(BgApp9Fixture.DEVELOPER_EIK)
        .build();
  }

  private static long positionGaps(String position) {
    List<BgApp9Gap> gaps = BgApp9MandatoryPoints.ofHeader(withPosition(position));
    return gaps.stream().filter(g -> "Representative/Position".equals(g.tag)).count();
  }

  @Test
  @DisplayName("„Управител“ минава; празно, „R“ и един знак се отказват поименно")
  void onlyARealPositionPasses() {
    assertEquals(0, positionGaps("Управител"));
    assertEquals(1, positionGaps(null));
    assertEquals(1, positionGaps("  "));
    assertEquals(1, positionGaps("R"), "кодът на „Качество“ не е длъжност");
    assertEquals(1, positionGaps("У"));
  }

  @Test
  @DisplayName("Причината сочи полето „Длъжност“, не „Качество“")
  void theReasonPointsAtThePositionField() {
    BgApp9Gap gap =
        BgApp9MandatoryPoints.ofHeader(withPosition("R")).stream()
            .filter(g -> "Representative/Position".equals(g.tag))
            .findFirst()
            .get();
    assertTrue(gap.reason.contains("поле „Длъжност“"), gap.reason);
  }
}
