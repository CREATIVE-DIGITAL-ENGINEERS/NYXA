package com.axelor.apps.aiaccountant.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What counts as the user naming a record, and — mostly — what does not.
 *
 * <p>The parser is the only thing that can turn text into an authorised target, so the tests that
 * matter are the negative ones: every string here that must NOT match is a document somebody could
 * otherwise have had acted on because a number in their sentence looked like an id.
 */
class AiExplicitReferenceParserTest {

  /**
   * The reference syntax, the reference-kind table and the entity-name table are three lists of the
   * same vocabulary. Every kind the parser can produce must be classifiable — by its row ({@code
   * Invoice}) or by its kind — and every kind-only answer must agree with the page answer for the
   * entity of the same name.
   */
  @Test
  void theParsedKindsAndBothDomainTablesAgree() {
    List<String> parsed =
        new java.util.ArrayList<>(AiExplicitReferenceParser.KIND_OF_FIELD.values());
    parsed.addAll(AiExplicitReferenceParser.KIND_OF_ENTITY.values());
    for (String kind : parsed) {
      String ref = kind + "#1";
      assertTrue(
          AiRefDomain.rowDependent(ref) || AiRefDomain.of(ref) != null,
          "the parser produces a kind nothing can classify: " + kind);
    }
    AiRefDomain.DOMAIN_OF_KIND.forEach(
        (kind, domain) -> {
          String simpleName = "Upload".equals(kind) ? "AiOcrUpload" : kind;
          long agreeing =
              AiRefDomain.DOMAIN_OF_MODEL.entrySet().stream()
                  .filter(e -> e.getKey().endsWith(".db." + simpleName))
                  .filter(e -> e.getValue() == domain)
                  .count();
          assertEquals(1, agreeing, "kind and page tables disagree about " + kind);
        });
    assertEquals(null, AiRefDomain.ofModel("com.axelor.apps.account.db.Invoice"), "row decides");
  }

  @Test
  void readsTheFieldForm() {
    assertEquals(
        List.of("Invoice#641"), AiExplicitReferenceParser.parse("валидирай invoiceId=641"));
    assertEquals(List.of("Invoice#641"), AiExplicitReferenceParser.parse("invoiceId: 641"));
    assertEquals(List.of("Invoice#641"), AiExplicitReferenceParser.parse("invoiceid = 641 please"));
  }

  @Test
  void readsTheEntityForm() {
    assertEquals(List.of("Invoice#641"), AiExplicitReferenceParser.parse("fix Invoice#641"));
    assertEquals(List.of("Invoice#641"), AiExplicitReferenceParser.parse("invoice # 641"));
    assertEquals(List.of("Upload#946"), AiExplicitReferenceParser.parse("Upload#946"));
    assertEquals(List.of("AiOcrUpload#946"), AiExplicitReferenceParser.parse("AiOcrUpload#946"));
    assertEquals(List.of("Move#102"), AiExplicitReferenceParser.parse("post Move#102"));
    assertEquals(
        List.of("BankStatement#7"), AiExplicitReferenceParser.parse("BankStatement#7 please"));
    assertEquals(List.of("Partner#12"), AiExplicitReferenceParser.parse("Partner#12"));
  }

  @Test
  void readsTheOtherFieldNames() {
    assertEquals(List.of("Upload#946"), AiExplicitReferenceParser.parse("uploadId=946"));
    assertEquals(List.of("Move#102"), AiExplicitReferenceParser.parse("moveId=102"));
    assertEquals(List.of("BankStatement#7"), AiExplicitReferenceParser.parse("bankStatementId=7"));
    assertEquals(List.of("Partner#12"), AiExplicitReferenceParser.parse("partnerId=12"));
  }

  /** Several explicitly tagged references are one exact set, not an ambiguity. */
  @Test
  void severalTaggedReferencesAreOneSet() {
    assertEquals(
        List.of("Invoice#641", "Invoice#642"),
        AiExplicitReferenceParser.parse("осчетоводи invoiceId=641 и invoiceId=642"));
    assertEquals(
        List.of("Invoice#641", "Move#102"),
        AiExplicitReferenceParser.parse("Invoice#641 produced Move#102"));
  }

  /** The same record named twice is one reference, and a leading zero is the same number. */
  @Test
  void deduplicatesAndNormalises() {
    assertEquals(
        List.of("Invoice#641"),
        AiExplicitReferenceParser.parse("invoiceId=641 — да, Invoice#641, точно нея"));
    assertEquals(List.of("Invoice#641"), AiExplicitReferenceParser.parse("invoiceId=0641"));
  }

  /**
   * A bare number is never a reference.
   *
   * <p>This is the whole safety argument. An accountant's sentence is full of numbers that are
   * amounts, dates, VAT rates, percentages and document numbers, and acting on one because it could
   * have been a primary key is how the wrong invoice gets posted.
   */
  @Test
  void bareNumbersAreNotReferences() {
    assertTrue(AiExplicitReferenceParser.parse("валидирай 641").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("фактурата за 1250.00 лв").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("invoice 641").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("20% ДДС на 2024-03-15").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("виж фактура 641").isEmpty());
  }

  /**
   * «№» is the printed document number, not the row id.
   *
   * <p>The most natural Bulgarian phrasing, and deliberately not matched: the invoice numbered 641
   * and the invoice whose id is 641 are different documents, and the sequence that produces the
   * printed number has nothing to do with the primary key. Reading one as the other would authorise
   * a confidently-named wrong record.
   */
  @Test
  void theBulgarianNumberSignIsNotAnId() {
    assertTrue(AiExplicitReferenceParser.parse("фактура №641").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("фактура № 641").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("осчетоводи фактура №641").isEmpty());
  }

  @Test
  void ignoresUnknownKindsAndNonPositiveIds() {
    assertTrue(AiExplicitReferenceParser.parse("Employee#5").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("productId=5").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("invoiceId=0").isEmpty());
  }

  @Test
  void handlesNothingToRead() {
    assertTrue(AiExplicitReferenceParser.parse(null).isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("").isEmpty());
    assertTrue(AiExplicitReferenceParser.parse("коя е активната фирма?").isEmpty());
  }

  /** A pasted report cannot build an unbounded set. */
  @Test
  void isBounded() {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= AiExplicitReferenceParser.MAX_PARSED + 50; i++) {
      sb.append("invoiceId=").append(i).append(' ');
    }
    assertEquals(
        AiExplicitReferenceParser.MAX_PARSED,
        AiExplicitReferenceParser.parse(sb.toString()).size());
  }
}
