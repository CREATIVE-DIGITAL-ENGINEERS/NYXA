package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.payroll.BgEarningsResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАБРАНАТА ПО КТ ЧЛ. 142 АЛ. 5 — ЕДНО ПРАВИЛО, ЧЕТИРИ ЪГЪЛА.
 *
 * <p>Правилото е чиста функция от две полета на договора и се проверява без база, без Guice и без
 * домейн. Двамата му читателя ({@code BgEmploymentContractManagementRepository} и {@code
 * BgEarningsResolver}) имат СВОИ свидетели за това КЪДЕ се задава въпросът; тук стои само КАКВО
 * отговаря той.
 *
 * <p>⚠️ Ръбовете не са украса. Трите от четирите състояния на двойката са ДОПУСТИМИ и точно те
 * трябва да мълчат: ненормиран ден при подневно отчитане е обичайният случай (чл. 139а), а СИРВ без
 * ненормиран ден е също толкова обичайният (чл. 142 ал. 2). Правило, което отказва и тях, би спряло
 * всеки втори договор в страната.
 */
class BgWorkTimeModeRuleTest {

  /** Подневно отчитане — стойността „1“ на {@code bg.work.time.mode.select}. */
  private static final String BY_DAY = "1";

  private static final String SIRV = BgEarningsResolver.WORK_TIME_MODE_SIRV;

  /**
   * ⚠️ ЕДИНСТВЕНАТА НЕДОПУСТИМА ОТ ЧЕТИРИТЕ — и съобщението назовава И ДВЕТЕ полета.
   *
   * <p>КТ чл. 142 ал. 5 дословно: „Не се допуска сумирано изчисляване на работното време за
   * работниците и служителите с ненормиран работен ден.“ Съобщението носи нормата, защото адресатът
   * му е операторът на договора, а не читателят на кода.
   *
   * <p>FAIL-FIRST: правилото беше написано да пита само за ненормирания ден ({@code return
   * unregulatedWorkDay;}) → паднаха двата свидетеля с вдигната отметка (подневно отчитане и
   * непопълнен режим), а третият — „СИРВ без ненормиран ден“ — ОСТАНА зелен, защото мутацията гледа
   * точно другото поле. Оттам и трите: един свидетел на двойката не различава кое от двете полета е
   * прочетено.
   */
  @Test
  @DisplayName("⚠️ чл. 142 ал. 5 · ненормиран ден + СИРВ е НЕДОПУСТИМО")
  void theUnregulatedDayWithSirvIsForbidden() {
    assertTrue(BgWorkTimeModeRule.isForbiddenPair(true, SIRV));

    String refusal = BgWorkTimeModeRule.forbiddenPairRefusal(true, SIRV);
    assertNotNull(refusal, "недопустимата двойка трябва да има ИМЕ, а не само булев отговор");
    assertTrue(refusal.contains("чл. 142, ал. 5"), refusal);
    assertTrue(
        refusal.contains("ненормиран работен ден") && refusal.contains("СИРВ"),
        "⚠️ И ДВЕТЕ полета в съобщението: сгрешено може да е което и да е от тях, а едно-полево"
            + " съобщение праща оператора да поправя другото. Получено: "
            + refusal);
  }

  /**
   * ⚠️ НЕНОРМИРАН ДЕН ПРИ ПОДНЕВНО ОТЧИТАНЕ Е ОБИЧАЙНИЯТ СЛУЧАЙ, НЕ ИЗКЛЮЧЕНИЕ.
   *
   * <p>Чл. 139а ал. 1 го установява със заповед за определени длъжности и нищо в кодекса не го
   * забранява при подневно отчитане. Отречен и тук, пазачът би спрял всеки договор с ненормиран ден
   * — тоест правилото щеше да мери ОТМЕТКАТА, а не двойката.
   */
  @Test
  @DisplayName("ненормиран ден + подневно отчитане · ДОПУСТИМО")
  void theUnregulatedDayByItselfIsAllowed() {
    assertFalse(BgWorkTimeModeRule.isForbiddenPair(true, BY_DAY));
    assertNull(BgWorkTimeModeRule.forbiddenPairRefusal(true, BY_DAY));
  }

  /** СИРВ без ненормиран ден — чл. 142 ал. 2, също обичайният случай. */
  @Test
  @DisplayName("СИРВ без ненормиран ден · ДОПУСТИМО")
  void sirvByItselfIsAllowed() {
    assertFalse(BgWorkTimeModeRule.isForbiddenPair(false, SIRV));
    assertNull(BgWorkTimeModeRule.forbiddenPairRefusal(false, SIRV));
  }

  /**
   * ⚠️ НЕПОПЪЛНЕН РЕЖИМ НЕ Е ПОДНЕВНО ОТЧИТАНЕ, НО НЕ Е И ЗАБРАНА.
   *
   * <p>Празното поле е незададен въпрос и си има собствен изход: перото за работа на официален
   * празник го ОТКАЗВА по своя причина, защото разликата между двата режима е сто на сто от часа.
   * Превърнато тук в забрана по чл. 142 ал. 5, то би пратило оператора да оправя ненормирания ден,
   * а липсва режимът.
   */
  @Test
  @DisplayName("режим непопълнен · нито забрана, нито отговор — чужд въпрос")
  void aMissingModeIsNotTheProhibition() {
    assertFalse(BgWorkTimeModeRule.isForbiddenPair(true, null));
    assertFalse(BgWorkTimeModeRule.isForbiddenPair(true, ""));
    assertFalse(BgWorkTimeModeRule.isForbiddenPair(true, "   "));
    assertNull(BgWorkTimeModeRule.forbiddenPairRefusal(true, null));
  }

  /**
   * ⚠️ РЕЖИМЪТ СЕ СРАВНЯВА ОКАСТРЕН — полето минава през импорт и през REST.
   *
   * <p>„ 2 “ е СЪЩИЯТ режим като „2“. Сравнено дословно, забраната щеше да мълчи точно за редовете,
   * дошли по път без екран — а те са заварените, за които защитният пазач в енджина съществува.
   */
  @Test
  @DisplayName("⚠️ режим с празни знаци отстрани · СЪЩАТА забрана")
  void theModeIsComparedTrimmed() {
    assertTrue(BgWorkTimeModeRule.isForbiddenPair(true, " 2 "));
    assertNotNull(BgWorkTimeModeRule.forbiddenPairRefusal(true, "\t2\n"));
  }
}
