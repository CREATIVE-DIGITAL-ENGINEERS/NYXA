package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G3b В ДНЕВНИКА: кодовете 600/700 от {@link BgSaftWithholdingRouter} стоят на реда след ДДС кода,
 * а ред само с тях НЕ носи 000/000000. XSD 1.0.2 (ред 505) допуска TaxInformation {@code
 * maxOccurs="unbounded"}, тоест един ред с 100331 и 600010 минава схемата — проверено тук.
 *
 * <p>Фикстурата на дневника е същата като в {@link BgSaftGlEntriesAssemblerTest}; месечният файл
 * БЕЗ операции 600/700 остава байт по байт същият ({@link BgSaftMonthlyRegressionTest}).
 */
class BgSaftGlEntriesWithholdingTest {

  /** Покупката ACH/0001 като представителен разход: 302 носи 600010, 401 — с 0.00. */
  private static Map<Long, List<SaftTaxInformation>> withholdings() {
    Map<Long, List<SaftTaxInformation>> map = new HashMap<>();
    map.put(9011L, Collections.singletonList(info("600", "600010", "5.00")));
    map.put(9013L, Collections.singletonList(info("600", "600010", "0.00")));
    return map;
  }

  @Test
  @DisplayName("600/700 след ДДС кода; ред само с 600/700 е без 000; ред без нищо — 000/000000")
  void редовете() {
    SaftGeneralLedger ledger = ledger(withholdings());
    List<SaftTransactionLine> purchase = lines(ledger, "ACH/0001");
    assertEquals(List.of("100/100331/0.00", "600/600010/5.00"), taxes(purchase.get(0)));
    assertEquals(List.of("100/100331/0.00"), taxes(purchase.get(1)), "ДДС-редът — без промяна");
    assertEquals(List.of("600/600010/0.00"), taxes(purchase.get(2)), "насрещният — без 000");
    List<SaftTransactionLine> sale = lines(ledger, "VEN/0001");
    assertEquals(List.of("000/000000/0.00"), taxes(sale.get(0)));
  }

  @Test
  @DisplayName("Без кодове 600/700 дневникът е същият като от старата сигнатура")
  void безКодове() {
    String before = xml(BgSaftFixtures.assembledGlEntries());
    String after = xml(ledger(Collections.emptyMap()));
    assertEquals(before, after);
  }

  @Test
  @DisplayName("Ред със 100331 И 600010 минава XSD 1.0.2")
  void схемата() {
    SaftGeneralLedger ledger = ledger(withholdings());
    byte[] file = BgSaftXmlWriter.toBytes(BgSaftMonthlyRegressionTest.rich().generalLedger(ledger));
    assertEquals(List.of(), BgSaftFixtures.validate(file));
  }

  // ─────────────── помощни ───────────────

  private static SaftGeneralLedger ledger(Map<Long, List<SaftTaxInformation>> withholdings) {
    return BgSaftGlEntriesAssembler.assemble(
            BgSaftFixtures.glLines(),
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            withholdings)
        .getGeneralLedger();
  }

  private static String xml(SaftGeneralLedger ledger) {
    return new String(
        BgSaftXmlWriter.toBytes(BgSaftMonthlyRegressionTest.rich().generalLedger(ledger)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private static SaftTaxInformation info(String type, String code, String amount) {
    return new SaftTaxInformation()
        .taxType(type)
        .taxCode(code)
        .taxAmount(SaftAmount.eur(new BigDecimal(amount)));
  }

  private static List<SaftTransactionLine> lines(SaftGeneralLedger ledger, String id) {
    List<SaftTransactionLine> out = new ArrayList<>();
    ledger
        .getJournals()
        .forEach(
            j ->
                j.getTransactions()
                    .forEach(
                        t -> {
                          if (((SaftTransaction) t).getTransactionId().equals(id)) {
                            ((SaftTransaction) t).getLines().forEach(out::add);
                          }
                        }));
    return out;
  }

  private static List<String> taxes(SaftTransactionLine line) {
    return line.getTaxInformation().stream()
        .map(t -> t.getTaxType() + "/" + t.getTaxCode() + "/" + t.getTaxAmount().getAmount())
        .collect(Collectors.toList());
  }
}
