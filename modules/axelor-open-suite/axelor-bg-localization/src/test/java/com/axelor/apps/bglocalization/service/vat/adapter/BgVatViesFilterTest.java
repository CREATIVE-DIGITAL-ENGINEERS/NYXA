package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Кои документи стават VIR ред. ЗДДС чл. 125 ал. 2: VIES е за ВОД, доставки като посредник в
 * тристранна операция и услуги по чл. 21 ал. 2 — към лице с ДДС номер в ДРУГА държава членка.
 */
class BgVatViesFilterTest {

  private final BgVatViesAdapter adapter = new BgVatViesAdapter(new BgVatSalesAdapter());

  private static BgVatDocumentDto doc(String type, String vin, BgVatSalesColumn column) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType(type);
    d.setDocumentNumber("1");
    d.setCounterpartyVatNumber(vin);
    d.addSalesAmount(column, new BigDecimal("100"));
    return d;
  }

  /** VIR: таг 3 + № 5 + ИН 15, после ВОД / тристранни / услуги по 12 знака. */
  private List<String> virs(BgVatDocumentDto... docs) {
    return adapter
        .buildLines(
            Arrays.asList(docs),
            new BgVatViesAdapter.RegisteredPerson("BG100000033", "Х", "Х"),
            new BgVatViesAdapter.Declarant("1", "Х", "Х", "1000", "Х", "Х"),
            "06/2026")
        .stream()
        .filter(l -> l.startsWith("VIR"))
        .map(
            l ->
                l.substring(8, 23).trim()
                    + "|"
                    + amount(l, 23)
                    + "|"
                    + amount(l, 35)
                    + "|"
                    + amount(l, 47))
        .collect(Collectors.toList());
  }

  private static String amount(String vir, int at) {
    return vir.substring(at, at + 12).trim();
  }

  @Test
  @DisplayName("ВОП протокол на доставчик от ЕС не е VIES ред")
  void icaProtocol_ofAnEuSupplier_isNotAViesRow() {
    BgVatDocumentDto vop = doc("09", "DE123456789", BgVatSalesColumn.K13_BASE_ICA);
    BgVatDocumentDto vod = doc("01", "FR12345678901", BgVatSalesColumn.K20_BASE_ICS);

    assertEquals(Arrays.asList("FR12345678901|100.00|0.00|0.00"), virs(vop, vod));
  }

  @Test
  @DisplayName("Северна Ирландия е във VIES само за стоки")
  void northernIreland_goodsOnly() {
    BgVatDocumentDto goods = doc("01", "XI123456789", BgVatSalesColumn.K20_BASE_ICS);
    BgVatDocumentDto services = doc("01", "XI987654321", BgVatSalesColumn.K22_BASE_EU_SERVICES);

    assertEquals(Arrays.asList("XI123456789|100.00|0.00|0.00"), virs(goods, services));
  }

  @Test
  @DisplayName("Номер извън ЕС, „999…“ и български номер не са VIES ред")
  void onlyAnotherMemberStatesVatNumber() {
    assertEquals(
        Arrays.asList("DE123456789|0.00|0.00|100.00"),
        virs(
            doc("01", "CHE123456789", BgVatSalesColumn.K22_BASE_EU_SERVICES),
            doc("01", "GB123456789", BgVatSalesColumn.K22_BASE_EU_SERVICES),
            doc("01", BgVatCounterpartyId.NO_ID, BgVatSalesColumn.K22_BASE_EU_SERVICES),
            doc("01", "BG123456786", BgVatSalesColumn.K22_BASE_EU_SERVICES),
            doc("01", null, BgVatSalesColumn.K22_BASE_EU_SERVICES),
            doc("01", "DE123456789", BgVatSalesColumn.K22_BASE_EU_SERVICES)));
  }

  @Test
  @DisplayName("VIES чете ДДС номера, не ИН-а на дневника")
  void readsTheVatNumber_notTheDiaryId() {
    BgVatDocumentDto d = doc("01", null, BgVatSalesColumn.K22_BASE_EU_SERVICES);
    d.setCounterpartyDiaryId("123456786");

    assertEquals(Arrays.asList(), virs(d));
  }

  @Test
  @DisplayName("Тристранна операция като посредник е VIES ред")
  void triangular_isAViesRow() {
    assertEquals(
        Arrays.asList("AT12345678|0.00|100.00|0.00"),
        virs(doc("01", "AT12345678", BgVatSalesColumn.K25_BASE_TRIANGULAR)));
  }
}
