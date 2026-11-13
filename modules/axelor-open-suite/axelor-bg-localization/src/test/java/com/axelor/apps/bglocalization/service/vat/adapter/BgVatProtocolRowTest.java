package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОЛОНИТЕ НА ПРОТОКОЛА СТИГАТ ДО РЕДА — И САМО ВЕДНЪЖ.
 *
 * <p>Колекторът пише протоколите по ръчна статия направо в {@link
 * BgVatDocumentDto#getSalesAmounts()}. PRODAGBI.TXT ги пише в реда и в тоталите к.09/к.10, а DEKLAR
 * и VIES ги четат от реда, без да ги добавят втори път.
 */
class BgVatProtocolRowTest {

  private final BgVatSalesAdapter sales = new BgVatSalesAdapter();

  private static BgVatExportRequestDto request() {
    BgVatExportRequestDto r = new BgVatExportRequestDto();
    r.setCompanyVatNumber("BG100000033");
    r.setPeriodFrom(LocalDate.of(2026, 6, 1));
    r.setPeriodTo(LocalDate.of(2026, 6, 30));
    return r;
  }

  private static BgVatDocumentDto protocol(String vin) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("09");
    d.setDocumentNumber("П-1");
    d.setDocumentDate(LocalDate.of(2026, 6, 30));
    d.setCounterpartyVatNumber(vin);
    return d;
  }

  private static String value(String record, int column) {
    // к.09 започва на 167; всяка от 17-те колони е 15 знака.
    int at = 167 + (column - 9) * 15;
    return record.substring(at, at + 15).trim();
  }

  @Test
  @DisplayName("Протоколът по к.14/к.15 излиза в PRODAGBI с колоните си и в к.09/к.10")
  void протоколът_е_в_реда_и_в_тоталите() {
    BgVatDocumentDto d = protocol("DE123456789");
    d.addSalesAmount(BgVatSalesColumn.K14_BASE_RC, new BigDecimal("100"));
    d.addSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC, new BigDecimal("20"));

    String record = sales.buildLines(Collections.singletonList(d), request()).get(0);

    assertEquals("100.00", value(record, 9)); // 02-10 = Σ 02-11…02-16, 02-26
    assertEquals("20.00", value(record, 10)); // 02-20 = Σ 02-21…02-24
    assertEquals("100.00", value(record, 14)); // 02-26
    assertEquals("20.00", value(record, 15)); // 02-22
  }

  @Test
  @DisplayName("Пряката колона се събира с маршрутизираната по данъчен код, не я заменя")
  void пряката_колона_се_събира() {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode("BG_SRS_20");
    a.setBase(new BigDecimal("1000"));
    a.setVatAmount(new BigDecimal("200"));
    BgVatDocumentDto d = protocol("BG100000058");
    d.getAmounts().add(a);
    d.addSalesAmount(BgVatSalesColumn.K12_VAT_20, new BigDecimal("720"));

    String record = sales.buildLines(Collections.singletonList(d), request()).get(0);

    assertEquals("1000.00", value(record, 9));
    assertEquals("920.00", value(record, 10));
    assertEquals("920.00", value(record, 12));
  }

  @Test
  @DisplayName("DEKLAR брои протокола веднъж: кл.20 и кл.22 = 20, не 0 и не 40")
  void декларацията_брои_веднъж() {
    BgVatDocumentDto d = protocol("DE123456789");
    d.addSalesAmount(BgVatSalesColumn.K14_BASE_RC, new BigDecimal("100"));
    d.addSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC, new BigDecimal("20"));

    String record =
        new BgVatDeclarationAdapter(sales)
            .buildLines(
                Collections.singletonList(d),
                Collections.emptyList(),
                new BgVatDeclarationAdapter.Header("BG100000033", "Х", "202606", "Х"),
                new BgVatDeclarationAdapter.ManualCells(null, null, null, null, null, null))
            .get(0);

    assertEquals("100.00", record.substring(151, 166).trim()); // кл.01 = [02-10]
    assertEquals("20.00", record.substring(166, 181).trim()); // кл.20 = [02-20]
    assertEquals("100.00", record.substring(211, 226).trim()); // кл.12 = [02-12] + [02-26]
    assertEquals("20.00", record.substring(226, 241).trim()); // кл.22 = [02-22]
  }

  @Test
  @DisplayName("VIES брои пряката колона веднъж")
  void vies_брои_веднъж() {
    BgVatDocumentDto d = protocol("DE123456789");
    d.addSalesAmount(BgVatSalesColumn.K22_BASE_EU_SERVICES, new BigDecimal("100"));

    List<String> vies =
        new BgVatViesAdapter(sales)
            .buildLines(
                Collections.singletonList(d),
                new BgVatViesAdapter.RegisteredPerson("BG100000033", "Х", "Х"),
                new BgVatViesAdapter.Declarant("1", "Х", "Х", "1000", "Х", "Х"),
                "06/2026");
    String vir = vies.stream().filter(l -> l.startsWith("VIR")).findFirst().get();

    // VIR: таг 3 + № 5 + ИН 15, после ВОД / тристранни / услуги по 12 знака.
    assertEquals("100.00", vir.substring(47, 59).trim());
  }
}
