package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileAnnual;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОНТРОЛИТЕ ОТ ЗАПОВЕДТА ЗА АЛ. 6 — това, което схемата пропуска.
 *
 * <p>⚠️ Всеки свидетел тук е за правило от „Ред и формат“ (заповед № З-ЦУ-1877/05.12.2019 г.), а не
 * за предположение: приемът е всичко-или-нищо (т. 3), затова всяка находка е блокираща.
 *
 * <p><b>⚠️ ЗНАКЪТ Е ЗАКОВАН ПООТДЕЛНО ЗА ТРИТЕ ЗАДЪЛЖИТЕЛНИ ЗА ПРИЕМ СУМИ.</b> {@code income},
 * {@code advancetax} и {@code sumtaxdeducted} минават през ЕДИН И СЪЩ {@code checkAmount(…, false,
 * …)}, тоест общ свидетел за единия „покрива“ и другите два — но само докато флагът им не се смени.
 * Обърнат на едно от трите извиквания, той не би повалил нито един тест, а файлът щеше да тръгне
 * към НАП с отрицателна стойност в поле, което заповедта НЕ допуска (изисквания т. 6). Затова тук
 * има три отделни свидетеля, по един на извикване.
 *
 * <p>⚠️ Останалите суми ({@code healthinsbg}, {@code healthinsforeign}, двете основи по чл. 25 и
 * полетата на {@code taxbase49}) НАРОЧНО нямат свой свидетел: те са незадължителни за прием, тоест
 * тежестта им е друга, а изключението за {@code diff18} вече е заковано от двойката „разликата
 * минава / възстановеното пада“.
 */
class BgArt73Para6FileValidatorTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /** Напълно изряден ред доход. */
  private static BgArt73Para6FileIncomeRow row() {
    BgArt73Para6FileIncomeRow row = new BgArt73Para6FileIncomeRow();
    row.setIncomeCode("101");
    row.setEmployerIdentifier("100000033");
    row.setEmployerName("НЯКОЕ ЕООД");
    row.setIncome(bd("24000.00"));
    row.setAdvanceTax(bd("2280.00"));
    return row;
  }

  /** Напълно изрядно лице БЕЗ основно правоотношение (тоест без блок по чл. 49). */
  private static BgArt73Para6FilePersonRow person() {
    BgArt73Para6FilePersonRow person = new BgArt73Para6FilePersonRow();
    person.setCorrectionCode("0");
    person.setFirstName("Иван");
    person.setLastName("Георгиев");
    person.setIdentifierType("0");
    person.setIdentifier("8001010013");
    person.getIncomeRows().add(row());
    person.setTotalTaxWithheld(bd("2280.00"));
    return person;
  }

  private static BgArt73Para6FileContent content(BgArt73Para6FilePersonRow... persons) {
    BgArt73Para6FileContent content = new BgArt73Para6FileContent();
    content.setTaxYear(2026);
    content.setPayerIdentifier("100000033");
    content.setPayerName("НЯКОЕ ЕООД");
    for (BgArt73Para6FilePersonRow person : persons) {
      content.getPersons().add(person);
    }
    return content;
  }

  private static List<String> codes(BgArt73Para6FileContent content) {
    List<String> codes = new ArrayList<>();
    for (BgDeclarationFileFinding finding : BgArt73Para6FileValidator.validate(content)) {
      codes.add(finding.getCode());
    }
    return codes;
  }

  @Test
  @DisplayName("Изрядна справка не дава нито една находка")
  void изрядната_минава() {
    assertEquals(new ArrayList<String>(), codes(content(person())));
  }

  @Test
  @DisplayName("⚠️ 13-значният ЕИК се ОТКАЗВА, макар че е напълно валиден ЕИК")
  void тринайсетзначният_еик_пада() {
    BgArt73Para6FileContent content = content(person());
    content.setPayerIdentifier("1000000330001");
    assertTrue(codes(content).contains(BgArt73Para6FileValidator.PAYER_EIK_LENGTH));
  }

  @Test
  @DisplayName("⚠️ Типът идентификатор „2“ (БУЛСТАТ) е валиден за ал. 1, но НЕ за ал. 6")
  void типът_две_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.setIdentifierType("2");
    person.setIdentifier("100000033");
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.IDENTIFIER_TYPE_INVALID));
  }

  @Test
  @DisplayName("⚠️ Код на дохода с 4 знака пада — схемата иска ТОЧНО 3")
  void кодът_е_точно_три_знака() {
    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setIncomeCode("1011");
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.INCOME_CODE_LENGTH));
  }

  @Test
  @DisplayName("⚠️ Отрицателен доход пада — изисквания т. 6")
  void отрицателният_доход_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setIncome(bd("-1.00"));
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName("⚠️ Отрицателен АВАНСОВ ДАНЪК пада — изисквания т. 6")
  void отрицателният_авансов_данък_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setAdvanceTax(bd("-1.00"));
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName("⚠️ Отрицателен ОБЩ УДЪРЖАН ДАНЪК пада — изисквания т. 6")
  void отрицателният_общ_удържан_данък_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.setTotalTaxWithheld(bd("-1.00"));
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName("⚠️ Отрицателна РАЗЛИКА ЗА ИЗРАВНЯВАНЕ обаче МИНАВА — единственото изключение")
  void отрицателната_разлика_минава() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployer(true);
    person.setTaxBaseArt25Para1(bd("28080.00"));

    BgArt73Para6FileAnnual annual = new BgArt73Para6FileAnnual();
    annual.setTaxBase(bd("28080.00"));
    annual.setTax(bd("2208.00"));
    annual.setSettlement(bd("-642.00"));
    person.setAnnual(annual);

    assertFalse(
        codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_NEGATIVE),
        "diff18 е изричното изключение по т. 6");
  }

  @Test
  @DisplayName("⚠️ Отрицателно ВЪЗСТАНОВЕНО пада — изключението е САМО за разликата")
  void отрицателното_възстановено_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployer(true);
    BgArt73Para6FileAnnual annual = new BgArt73Para6FileAnnual();
    annual.setRefundedByJanuary(bd("-1.00"));
    person.setAnnual(annual);
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName("⚠️ Основен работодател БЕЗ блок по чл. 49 пада")
  void основен_без_блок_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployer(true);
    assertTrue(
        codes(content(person)).contains(BgArt73Para6FileValidator.MAIN_EMPLOYER_WITHOUT_ANNUAL));
  }

  @Test
  @DisplayName("⚠️ Блок по чл. 49 БЕЗ основен работодател също пада — правилото е двупосочно")
  void блок_без_основен_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.setAnnual(new BgArt73Para6FileAnnual());
    assertTrue(
        codes(content(person)).contains(BgArt73Para6FileValidator.ANNUAL_WITHOUT_MAIN_EMPLOYER));
  }

  @Test
  @DisplayName("⚠️ Разминаване по „основен работодател“ е НАХОДКА, не мълчалив избор")
  void противоречието_се_назовава() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployerConflict(true);
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.MAIN_EMPLOYER_CONFLICT));
  }

  @Test
  @DisplayName("Код 101 при основен работодател иска основа по чл. 25 ал. 1")
  void код_101_иска_основа_251() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployer(true);
    person.setAnnual(new BgArt73Para6FileAnnual());
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.TAX_BASE_251_MISSING));
  }

  @Test
  @DisplayName("Код 103 (морско лице) при основен работодател иска основа по чл. 25 ал. 3")
  void код_103_иска_основа_253() {
    BgArt73Para6FilePersonRow person = person();
    person.setMainEmployer(true);
    person.setAnnual(new BgArt73Para6FileAnnual());
    person.setTaxBaseArt25Para1(bd("1.00"));
    person.getIncomeRows().get(0).setIncomeCode("103");
    List<String> found = codes(content(person));
    assertTrue(found.contains(BgArt73Para6FileValidator.TAX_BASE_253_MISSING), found.toString());
  }

  @Test
  @DisplayName("⚠️ Общият удържан данък липсва при наличен блок доходи — задължителен за прием")
  void сборът_е_задължителен() {
    BgArt73Para6FilePersonRow person = person();
    person.setTotalTaxWithheld(null);
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.TOTAL_TAX_MISSING));
  }

  @Test
  @DisplayName("⚠️ ЕИК на преобразуващото се НЕ МОЖЕ да е самият подател")
  void преобразуващото_се_не_е_подателят() {
    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setPredecessorIdentifier("100000033");
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.PREDECESSOR_IS_PAYER));
  }

  @Test
  @DisplayName("⚠️ Едно лице два пъти пада — т. 14: корекцията е за ЦЯЛАТА Част ІІ на лицето")
  void едно_лице_два_пъти_пада() {
    assertTrue(
        codes(content(person(), person())).contains(BgArt73Para6FileValidator.DUPLICATE_PERSON));
  }

  @Test
  @DisplayName("⚠️ Сума с три дробни знака пада — инак писачът я закръгля МЪЛЧАЛИВО")
  void третият_дробен_знак_пада() {
    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setIncome(bd("24000.001"));
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.AMOUNT_SCALE));
  }

  @Test
  @DisplayName("Празна справка и липсващ авансов данък се назовават")
  void празнотата_се_назовава() {
    assertTrue(codes(content()).contains(BgArt73Para6FileValidator.NO_ROWS));

    BgArt73Para6FilePersonRow person = person();
    person.getIncomeRows().get(0).setAdvanceTax(null);
    assertTrue(codes(content(person)).contains(BgArt73Para6FileValidator.ADVANCE_TAX_MISSING));
  }
}
