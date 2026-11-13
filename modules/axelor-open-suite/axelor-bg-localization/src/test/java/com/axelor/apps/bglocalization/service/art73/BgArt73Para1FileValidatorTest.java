package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * УКАЗАНИЯТА ПОД ОБРАЗЕЦ 5-2001, всяко със свой свидетел.
 *
 * <p>⚠️ Тези правила НЕ зависят от физическия формат — затова са проверими днес, преди заповедта.
 * Кодовете на находките са закована част от договора на валидатора: по тях екранът и по-късните
 * пазачи ще питат „коя точка на указанията е нарушена“.
 */
class BgArt73Para1FileValidatorTest {

  private static BgArt73Para1FileRow row() {
    BgArt73Para1FileRow row = new BgArt73Para1FileRow();
    row.setCorrectionCode("0");
    row.setIdentifierType("0");
    row.setIdentifier("8001010013");
    row.setFirstName("Иван");
    row.setMiddleName("Петров");
    row.setLastName("Георгиев");
    row.setIncomeCode("307");
    row.setGrossAmount(new BigDecimal("1200.00"));
    row.setInsuranceAmount(new BigDecimal("100.00"));
    row.setTaxAmount(new BigDecimal("110.00"));
    return row;
  }

  private static BgArt73Para1FileContent content(BgArt73Para1FileRow... rows) {
    BgArt73Para1FileContent content = new BgArt73Para1FileContent();
    content.setTaxYear(2026);
    content.setPayerIdentifier("100000033");
    content.setPayerName("НЯКОЕ ЕООД");
    content.setContactPhone("0700 18 700");
    for (BgArt73Para1FileRow row : rows) {
      content.getRows().add(row);
    }
    return content;
  }

  private static boolean has(List<BgDeclarationFileFinding> findings, String code) {
    return findings.stream().anyMatch(f -> code.equals(f.getCode()));
  }

  @Test
  @DisplayName("Редовна справка минава без блокиращи находки")
  void редовната_справка_минава() {
    assertFalse(
        BgArt73Para1FileValidator.hasBlocking(BgArt73Para1FileValidator.validate(content(row()))));
  }

  @Test
  @DisplayName("Празна справка е ОТКАЗ — файл с нула реда е изявление, не грешка")
  void празната_справка_е_отказ() {
    List<BgDeclarationFileFinding> findings = BgArt73Para1FileValidator.validate(content());
    assertTrue(has(findings, BgArt73Para1FileValidator.NO_ROWS));
    assertTrue(BgArt73Para1FileValidator.hasBlocking(findings));
  }

  @Test
  @DisplayName("Част І, т. 1 без ЕИК не се приема — указание т. 6")
  void липсващият_еик_блокира() {
    BgArt73Para1FileContent content = content(row());
    content.setPayerIdentifier(null);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content),
            BgArt73Para1FileValidator.PAYER_IDENTIFIER_MISSING));
  }

  @Test
  @DisplayName(
      "Колони 4 и 5 са взаимноизключващи се — указание за попълване т. 4, и в двете посоки")
  void колони_4_и_5_се_изключват() {
    BgArt73Para1FileRow noIdentifier = row();
    noIdentifier.setIdentifierType("3");
    // ⚠️ и двете попълнени: редът твърди, че лице БЕЗ идентификатор има идентификатор
    noIdentifier.setBirthDate(LocalDate.of(1980, 1, 1));
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(noIdentifier)),
            BgArt73Para1FileValidator.IDENTIFIER_ON_TYPE_3));

    BgArt73Para1FileRow missingBirthDate = row();
    missingBirthDate.setIdentifierType("3");
    missingBirthDate.setIdentifier(null);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(missingBirthDate)),
            BgArt73Para1FileValidator.BIRTH_DATE_MISSING));

    BgArt73Para1FileRow birthDateOnEgn = row();
    birthDateOnEgn.setBirthDate(LocalDate.of(1980, 1, 1));
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(birthDateOnEgn)),
            BgArt73Para1FileValidator.BIRTH_DATE_NOT_ALLOWED));
  }

  @Test
  @DisplayName("Колона 9 е САМО за чуждестранни лица — указание за попълване т. 6")
  void колона_9_е_само_за_чужденци() {
    BgArt73Para1FileRow localWithCountry = row();
    localWithCountry.setCountryCode("DE");
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(localWithCountry)),
            BgArt73Para1FileValidator.COUNTRY_CODE_NOT_ALLOWED));

    BgArt73Para1FileRow foreignWithout = row();
    foreignWithout.setForeignPerson(true);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(foreignWithout)),
            BgArt73Para1FileValidator.COUNTRY_CODE_MISSING));
  }

  @Test
  @DisplayName("Колони 2–9 мълчат за СБОРНИТЕ кодове — указание за попълване т. 5")
  void сборният_ред_не_носи_имена() {
    BgArt73Para1FileRow aggregate = row();
    aggregate.setAggregateRow(true);
    aggregate.setReportsPersonCount(true);
    aggregate.setPersonCount(7);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(aggregate)),
            BgArt73Para1FileValidator.AGGREGATE_COLUMNS_FILLED));
  }

  @Test
  @DisplayName("Колона 14 се иска САМО от кодовете, които я носят — указание за попълване т. 5")
  void брой_лица_само_за_своите_кодове() {
    BgArt73Para1FileRow needsCount = row();
    needsCount.setAggregateRow(true);
    needsCount.setReportsPersonCount(true);
    needsCount.setIdentifierType(null);
    needsCount.setIdentifier(null);
    needsCount.setFirstName(null);
    needsCount.setMiddleName(null);
    needsCount.setLastName(null);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(needsCount)),
            BgArt73Para1FileValidator.PERSON_COUNT_MISSING));

    // ⚠️ Код 820 е СБОРЕН, но брой лица НЕ носи — двата признака НЕ са едно и също твърдение.
    BgArt73Para1FileRow aggregateWithoutCount = row();
    aggregateWithoutCount.setAggregateRow(true);
    aggregateWithoutCount.setReportsPersonCount(false);
    aggregateWithoutCount.setPersonCount(7);
    aggregateWithoutCount.setIdentifierType(null);
    aggregateWithoutCount.setIdentifier(null);
    aggregateWithoutCount.setFirstName(null);
    aggregateWithoutCount.setMiddleName(null);
    aggregateWithoutCount.setLastName(null);
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(aggregateWithoutCount)),
            BgArt73Para1FileValidator.PERSON_COUNT_NOT_ALLOWED));
  }

  @Test
  @DisplayName("Заличаващият ред не носи суми — указание за попълване т. 2")
  void заличаващият_ред_без_суми() {
    BgArt73Para1FileRow deletion = row();
    deletion.setCorrectionCode("8");
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(deletion)),
            BgArt73Para1FileValidator.DELETION_CARRIES_AMOUNTS));
  }

  @Test
  @DisplayName(
      "Сумите са до втория десетичен знак и не са отрицателни — указание за попълване т. 10")
  void сумите_са_левове_и_стотинки() {
    BgArt73Para1FileRow tooPrecise = row();
    tooPrecise.setGrossAmount(new BigDecimal("1200.123"));
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(tooPrecise)),
            BgArt73Para1FileValidator.AMOUNT_SCALE));

    BgArt73Para1FileRow negative = row();
    negative.setTaxAmount(new BigDecimal("-1.00"));
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(negative)),
            BgArt73Para1FileValidator.AMOUNT_NEGATIVE));
  }

  @Test
  @DisplayName(
      "Едно лице + един код + един код корекция стоят на ЕДИН ред — указание за попълване т. 7")
  void дублираният_ред_блокира() {
    assertTrue(
        has(
            BgArt73Para1FileValidator.validate(content(row(), row())),
            BgArt73Para1FileValidator.DUPLICATE_ROW));
  }

  @Test
  @DisplayName("Липсващият контакт е ПРЕДУПРЕЖДЕНИЕ, не отказ — указанията не го изброяват")
  void липсващият_контакт_не_блокира() {
    BgArt73Para1FileContent content = content(row());
    content.setContactPhone(null);
    content.setContactEmail(null);
    List<BgDeclarationFileFinding> findings = BgArt73Para1FileValidator.validate(content);
    assertTrue(has(findings, BgArt73Para1FileValidator.CONTACT_MISSING));
    assertFalse(BgArt73Para1FileValidator.hasBlocking(findings));
  }
}
