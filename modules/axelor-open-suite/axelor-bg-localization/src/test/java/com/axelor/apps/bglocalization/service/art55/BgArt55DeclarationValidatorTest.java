package com.axelor.apps.bglocalization.service.art55;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgArt55Declaration;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВИЛАТА НА ОБРАЗЕЦ 4001 — проверени срещу самия ЗАПИС.
 *
 * <p>⚠️ Валидаторът пази ЕКРАНА, не файл: обр. 4001 се подава през портала. Затова находките тук
 * СЪОБЩАВАТ — човекът решава дали да ги поправи преди да препише числата.
 */
class BgArt55DeclarationValidatorTest {

  private static BgArt55Declaration declaration() {
    BgArt55Declaration declaration = new BgArt55Declaration();
    declaration.setTaxYear(2026);
    declaration.setQuarter(2);
    declaration.setPayerIdentifier("100000033");
    declaration.setPayerCodeType("1");
    declaration.setPayerName("НЯКОЕ ЕООД");
    declaration.setContactPhone("0700 18 700");
    declaration.setRepresentativeName("Иван Петров Георгиев");
    declaration.setRepresentativeIdentifier("8001010010");
    declaration.setRow1AdvanceTax(new BigDecimal("120.00"));
    declaration.setRow2DividendTax(new BigDecimal("50.00"));
    declaration.setRow3OtherFinalTax(BigDecimal.ZERO);
    return declaration;
  }

  private static boolean has(List<BgDeclarationFileFinding> findings, String code) {
    return findings.stream().anyMatch(f -> code.equals(f.getCode()));
  }

  @Test
  @DisplayName("Редовна декларация минава без блокиращи находки")
  void редовната_декларация_минава() {
    assertFalse(
        BgArt55DeclarationValidator.hasBlocking(
            BgArt55DeclarationValidator.validate(declaration())));
  }

  @Test
  @DisplayName("Нулевият ред 3 е валидно съдържание, а не липсващ ред")
  void нулевият_ред_е_валиден() {
    List<BgDeclarationFileFinding> findings = BgArt55DeclarationValidator.validate(declaration());
    assertFalse(has(findings, BgArt55DeclarationValidator.AMOUNT_NEGATIVE));
    assertFalse(has(findings, BgArt55DeclarationValidator.ALL_ROWS_ZERO));
  }

  @Test
  @DisplayName("Празна декларация дава находки, а не изключение")
  void празната_декларация_не_гърми() {
    assertTrue(BgArt55DeclarationValidator.hasBlocking(BgArt55DeclarationValidator.validate(null)));
    // ⚠️ Нов запис: getQuarter() връща 0, не null — тоест периодът е невалиден, а не „липсващ".
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(new BgArt55Declaration()),
            BgArt55DeclarationValidator.QUARTER_INVALID));
  }

  @Test
  @DisplayName("Тримесечие извън І–ІV е отказ — образецът познава само четири")
  void тримесечието_е_едно_от_четири() {
    BgArt55Declaration declaration = declaration();
    declaration.setQuarter(5);
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(declaration),
            BgArt55DeclarationValidator.QUARTER_INVALID));
  }

  @Test
  @DisplayName("Кодът по т. 2 е една от петте стойности на образеца")
  void кодът_по_точка_2_е_от_образеца() {
    BgArt55Declaration declaration = declaration();
    declaration.setPayerCodeType("9");
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(declaration),
            BgArt55DeclarationValidator.PAYER_CODE_TYPE_INVALID));
  }

  @Test
  @DisplayName("Т. 7 се чете от КОДА по т. 2 — и в двете посоки")
  void представляващият_следва_кода() {
    BgArt55Declaration legalEntity = declaration();
    legalEntity.setRepresentativeName(null);
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(legalEntity),
            BgArt55DeclarationValidator.REPRESENTATIVE_MISSING));

    // ⚠️ Код „2“ значи ЕГН, тоест самоосигуряващо се лице — то подава от свое име и т. 7 мълчи.
    BgArt55Declaration selfInsured = declaration();
    selfInsured.setPayerCodeType("2");
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(selfInsured),
            BgArt55DeclarationValidator.REPRESENTATIVE_NOT_ALLOWED));
  }

  @Test
  @DisplayName("Три нули са ПРЕДУПРЕЖДЕНИЕ, не отказ — нулевата декларация съществува")
  void нулевата_декларация_не_блокира() {
    BgArt55Declaration zero = declaration();
    zero.setRow1AdvanceTax(BigDecimal.ZERO);
    zero.setRow2DividendTax(BigDecimal.ZERO);
    List<BgDeclarationFileFinding> findings = BgArt55DeclarationValidator.validate(zero);
    assertTrue(has(findings, BgArt55DeclarationValidator.ALL_ROWS_ZERO));
    assertFalse(BgArt55DeclarationValidator.hasBlocking(findings));
  }

  @Test
  @DisplayName("Отрицателен данък е отказ — образецът не го познава")
  void отрицателният_данък_блокира() {
    BgArt55Declaration negative = declaration();
    negative.setRow2DividendTax(new BigDecimal("-1.00"));
    assertTrue(
        has(
            BgArt55DeclarationValidator.validate(negative),
            BgArt55DeclarationValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName("Описанието различава блокиращо от предупреждение")
  void описанието_разграничава_тежестта() {
    BgArt55Declaration broken = declaration();
    broken.setQuarter(9);
    broken.setContactPhone(null);
    String text =
        BgArt55DeclarationValidator.describe(BgArt55DeclarationValidator.validate(broken));
    assertTrue(text.contains("⛔"), text);
    assertTrue(text.contains("⚠️"), text);
  }
}
