package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T4 (21.10.2026): кодът за ред БЕЗ данък идва само от протокол (вид 09) и само по сметката му
 * S.I.4. Фактурата, сметките на редовете (основата, разходът) и нееднозначното остават без код —
 * дневникът пише за тях 000/000000.
 */
class BgSaftDocumentCodesTest {

  private static final long MOVE = 1L;

  /** Документ с една линия по {@code lineAccount} и код {@code code}. */
  private static SaftInvoice document(String type, String head, String lineAccount, String code) {
    return new SaftInvoice()
        .invoiceType(type)
        .accountId(head)
        .addLine(
            new SaftInvoiceLine()
                .accountId(lineAccount)
                .addTaxInformation(new SaftTaxInformation().taxType("100").taxCode(code)));
  }

  @Test
  @DisplayName("Протокол по чл. 117 в двете секции: 4532 → 100226, 4531 → 100331")
  void протоколът_по_сметката_S_I_4() {
    BgSaftDocumentCodes codes = new BgSaftDocumentCodes();
    codes.add(MOVE, document("09", "4532", "4531", "100226"));
    codes.add(MOVE, document("09", "4531", "4532", "100331"));
    assertEquals("100226", codes.protocolCodeFor(MOVE, "4532"));
    assertEquals("100331", codes.protocolCodeFor(MOVE, "4531"));
  }

  @Test
  @DisplayName("Сметката на линията (основата, разходът) не е S.I.4 — без код")
  void линията_не_е_S_I_4() {
    BgSaftDocumentCodes codes = new BgSaftDocumentCodes();
    codes.add(MOVE, document("09", "4532", "60909", "100211"));
    assertNull(codes.protocolCodeFor(MOVE, "60909"));
    assertEquals("100211", codes.codeFor(MOVE, "60909"), "ред С данък пак взима кода по линията");
  }

  @Test
  @DisplayName("Фактура (вид 01) с S.I.4 = 411 — ред без данък по 411 остава без код")
  void фактурата_не_дава_код() {
    BgSaftDocumentCodes codes = new BgSaftDocumentCodes();
    codes.add(MOVE, document("01", "411", "702", "100211"));
    assertNull(codes.protocolCodeFor(MOVE, "411"));
    assertEquals("100211", codes.codeFor(MOVE, "411"), "ред С данък пак взима кода на фактурата");
  }

  @Test
  @DisplayName("Два протокола с една сметка S.I.4 и различни кодове — нееднозначно, без код")
  void нееднозначно() {
    BgSaftDocumentCodes codes = new BgSaftDocumentCodes();
    codes.add(MOVE, document("09", "4532", "4531", "100226"));
    codes.add(MOVE, document("09", "4532", "60909", "100211"));
    assertNull(codes.protocolCodeFor(MOVE, "4532"));
  }

  @Test
  @DisplayName("Друга статия, без сметка и празният набор — без код")
  void без_документ() {
    BgSaftDocumentCodes codes = new BgSaftDocumentCodes();
    codes.add(MOVE, document("09", "4532", "4531", "100226"));
    assertNull(codes.protocolCodeFor(2L, "4532"));
    assertNull(codes.protocolCodeFor(MOVE, null));
    assertNull(codes.protocolCodeFor(MOVE, ""));
    BgSaftDocumentCodes.EMPTY.add(MOVE, document("09", "4532", "4531", "100226"));
    assertNull(BgSaftDocumentCodes.EMPTY.protocolCodeFor(MOVE, "4532"));
  }
}
