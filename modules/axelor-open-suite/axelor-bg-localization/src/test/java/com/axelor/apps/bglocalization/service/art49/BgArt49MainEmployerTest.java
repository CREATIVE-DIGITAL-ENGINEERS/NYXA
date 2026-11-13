package com.axelor.apps.bglocalization.service.art49;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.hr.db.EmploymentContract;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОГО ПРЕИЗЧИСЛЯВА РАБОТОДАТЕЛЯТ — чл. 49 ал. 1, със свидетели с ДАТИ.
 *
 * <p>Въпросът има три оси и всяка от тях може да отговори „не“ сама: <b>кой</b> е работодателят,
 * <b>основен</b> ли е договорът и <b>действа</b> ли към 31 декември. Свидетелите ги менят една по
 * една, защото слети в един случай, две грешки биха се компенсирали.
 */
class BgArt49MainEmployerTest {

  private static final int TAX_YEAR = 2026;

  @Test
  @DisplayName("СВИДЕТЕЛ · основен договор, действащ на 31 декември → преизчислява се")
  void aPrimaryContractOpenAtYearEndIsRecalculated() {
    Company us = company();

    assertTrue(
        BgArt49MainEmployer.recalculates(
            Collections.singletonList(contract(us, true, "2026-03-01", null)), us, TAX_YEAR),
        "чл. 49 ал. 1: към 31 декември дружеството е работодател по основното ТПО");
  }

  /**
   * ⚠️ ЛИЦЕ БЕЗ ОСНОВНО ТПО КЪМ 31 ДЕКЕМВРИ НЕ СЕ ПРЕИЗЧИСЛЯВА — И ТОВА НЕ Е НЕПЪЛНОТА НА
   * СПРАВКАТА. Указание т. 7: колона 6 на редове 8.1–8.3 и колони 17–21 се попълват САМО от
   * работодателя по основното трудово правоотношение. За такова лице годишният данък се определя
   * другаде: при другия работодател по чл. 49 или от самото лице с декларация по чл. 50. Попълнени
   * тук, тези колони биха били твърдение, което този работодател няма право да прави — и биха
   * поискали от лицето да плати или да получи данък по чужда сметка.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 49 АЛ. 1 · допълнителен договор НЕ дава преизчисляване")
  void anAdditionalContractIsNotRecalculated() {
    Company us = company();

    assertFalse(
        BgArt49MainEmployer.recalculates(
            Collections.singletonList(contract(us, false, "2026-01-01", null)), us, TAX_YEAR),
        "договор за допълнителен труд не прави дружеството работодател по ОСНОВНОТО ТПО");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 49 АЛ. 1 · напусналият на 15 декември НЕ се преизчислява")
  void aContractEndedBeforeYearEndIsNotRecalculated() {
    Company us = company();

    assertFalse(
        BgArt49MainEmployer.recalculates(
            Collections.singletonList(contract(us, true, "2026-01-01", "2026-12-15")),
            us,
            TAX_YEAR),
        "единайсет месеца и половина фишове не менят датата, към която гледа нормата");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · постъпилият на 20 декември СЕ преизчислява")
  void aContractStartedJustBeforeYearEndIsRecalculated() {
    Company us = company();

    assertTrue(
        BgArt49MainEmployer.recalculates(
            Collections.singletonList(contract(us, true, "2026-12-20", null)), us, TAX_YEAR),
        "мярката на чл. 49 ал. 1 е ДАТАТА, не доходът — дванайсет дни стигат");
  }

  /**
   * ⚠️ ЧУЖДИЯТ ДОГОВОР НЕ ВЪРШИ РАБОТА, КОЛКОТО И ОСНОВЕН ДА Е. Чл. 49 ал. 1 казва „когато към 31
   * декември ТОЙ е работодател по основното трудово правоотношение“ — тоест подателят на справката.
   * Без проверка за дружеството лице с основен договор при друг работодател и допълнителен при нас
   * би получило колони 17–22 в НАШАТА справка.
   */
  @Test
  @DisplayName(
      "⚠️ ЧЛ. 49 АЛ. 1 · основен договор при ДРУГО дружество не ни прави основен работодател")
  void aPrimaryContractAtAnotherCompanyDoesNotCount() {
    Company us = company();
    Company them = company();

    assertFalse(
        BgArt49MainEmployer.recalculates(
            Arrays.asList(
                contract(them, true, "2026-01-01", null), contract(us, false, "2026-01-01", null)),
            us,
            TAX_YEAR),
        "при нас лицето е на допълнителен договор — основният е чужд");
  }

  /**
   * ⚠️ ДОГОВОР БЕЗ НАЧАЛНА ДАТА НЕ СЕ ЧЕТЕ КАТО „ОТВСЯКОГА“. Той е непопълнен запис, а не безсрочно
   * действащ. Прочетен като действащ, той би вкарал в преизчисляването лице, за което никой не е
   * казал кога е постъпило — и колони 17–22 биха се появили от липсваща стойност.
   */
  @Test
  @DisplayName("⚠️ БЕЗ ДАТА · договор без начална дата не дава преизчисляване")
  void aContractWithoutAStartDateIsNotRecalculated() {
    Company us = company();

    assertFalse(
        BgArt49MainEmployer.recalculates(
            Collections.singletonList(contract(us, true, null, null)), us, TAX_YEAR),
        "липсващата начална дата е непопълнен запис, а не безсрочен договор");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · без нито един договор няма преизчисляване")
  void noContractsMeansNoRecalculation() {
    Company us = company();

    assertFalse(BgArt49MainEmployer.recalculates(null, us, TAX_YEAR), "null се чете като „няма“");
    assertFalse(
        BgArt49MainEmployer.recalculates(Collections.emptyList(), us, TAX_YEAR), "празен списък");
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /**
   * ⚠️ ДРУЖЕСТВАТА СЕ СРАВНЯВАТ ПО ИДЕНТИЧНОСТ, ЗАЩОТО ТУК НЯМАТ id. Незаписан {@code Company} няма
   * първичен ключ и {@code equals} на {@code AuditableModel} пада обратно към сравнение по обект —
   * тоест два отделни {@code new Company()} са различни, което е точно каквото свидетелят иска.
   */
  private static Company company() {
    return new Company();
  }

  private static EmploymentContract contract(
      Company payCompany, boolean primary, String start, String end) {
    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(payCompany);
    contract.setBgIsPrimaryContract(primary);
    contract.setStartDate(start == null ? null : LocalDate.parse(start));
    contract.setEndDate(end == null ? null : LocalDate.parse(end));
    return contract;
  }
}
