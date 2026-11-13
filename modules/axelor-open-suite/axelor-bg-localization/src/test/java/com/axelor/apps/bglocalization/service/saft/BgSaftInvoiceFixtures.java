package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ФАКТУРИТЕ НА G5a — върху сметкоплана, мапинга и контрагентите на дневника ({@link
 * BgSaftFixtures#glChart()} и сие), за да съвпадат ID-тата, плюс разходна сметка и ДМА.
 *
 * <p>⚠️ МАПИНГЪТ НА ДАНЪЦИТЕ И ТАБЛИЦАТА НА КЛЕТКИТЕ СА ИСТИНСКИТЕ SEED-ОВЕ ({@code
 * bg_saf_tax_mapping.csv}, {@code bg_saf_vat_cell_code.csv}), прочетени от classpath-а, а не
 * преписани: код, разсъгласуван в CSV-то, чупи числата на тестовете.
 */
final class BgSaftInvoiceFixtures {

  static final LocalDate APRIL_10 = LocalDate.of(2026, 4, 10);
  static final LocalDate APRIL_5 = LocalDate.of(2026, 4, 5);
  static final LocalDate APRIL_12 = LocalDate.of(2026, 4, 12);
  static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

  // Сметките: тези на дневника плюс разход (602 → 60201) и съоръжение (204).
  static final long ACCOUNT_CUSTOMERS = 2L; // 4111 → 411
  static final long ACCOUNT_SALES = 3L; // 702
  static final long ACCOUNT_MATERIALS = 5L; // 302
  static final long ACCOUNT_SUPPLIERS = 7L; // 401
  static final long ACCOUNT_EXPENSE = 12L; // 602 → 60201
  static final long ACCOUNT_ASSET = 13L; // 204
  static final long ACCOUNT_UNMAPPED = 14L; // 999 без мапинг
  static final long ACCOUNT_CUSTOMS = 15L; // 458 разчети с митници
  static final long ACCOUNT_GOODS = 16L; // 304 стоки
  static final long ACCOUNT_VAT_PURCHASES = 6L; // 4531
  static final long ACCOUNT_VAT_SALES = 4L; // 4532
  static final long ACCOUNT_INCOME = ACCOUNT_SALES; // 702

  // Партньорите на дневника: 10 Алфа (клиент, ЕИК), 20 Beta (доставчик, ДДС номер в ЕС),
  // 30 Иванов (доставчик, физическо лице без ДДС номер).
  static final long ALFA = 10L;
  static final long BETA = 20L;
  static final long IVANOV = 30L;

  // TaxLine id-та — по едно на данък.
  static final long SRS_20 = 402L;
  static final long LRS_9 = 404L;
  static final long SRP_20 = 401L;
  static final long LRP_9 = 403L;

  private BgSaftInvoiceFixtures() {}

  static List<BgSaftGlAccountsAssembler.AccountRow> chart() {
    List<BgSaftGlAccountsAssembler.AccountRow> chart = new ArrayList<>(BgSaftFixtures.glChart());
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(12L, "602", "Външни услуги", "1", null));
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(13L, "204", "Съоръжения", "1", null));
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(14L, "999", "Без мапинг", "1", null));
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(15L, "458", "Разчети с митници", "3", null));
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(16L, "304", "Стоки", "1", null));
    return chart;
  }

  static Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings() {
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings =
        new LinkedHashMap<>(BgSaftFixtures.glMappings());
    mappings.put("602", new BgSaftGlAccountsAssembler.MappingRow("60201", false));
    mappings.put("204", new BgSaftGlAccountsAssembler.MappingRow("204", false));
    mappings.put("458", new BgSaftGlAccountsAssembler.MappingRow("458", false));
    mappings.put("304", new BgSaftGlAccountsAssembler.MappingRow("304", false));
    return mappings;
  }

  static BgSaftInvoicesAssembler.Result assemble(BgSaftInvoicesAssembler.Source source) {
    return BgSaftInvoicesAssembler.assemble(
        source,
        chart(),
        mappings(),
        BgSaftFixtures.glPartners(),
        seedTaxMappings(),
        seedCellCodes(),
        BgNraNomenclatures.taxCodes(),
        BgNraNomenclatures.invoiceTypes(),
        OWN);
  }

  /** Подаващият — от Header-а на {@link BgSaftFixtures#header()}, по правилото на услугата. */
  static final SaftParty OWN =
      BgSaftAuditFileService.ownParty(
          BgSaftFixtures.header().getCompany(),
          BgSaftAuditFileService.ownId(BgSaftFixtures.header().getCompany()));

  // ─────────────── строители ───────────────

  static BgSaftInvoicesAssembler.InvoiceRow sale(long id, String number, int operation) {
    return new BgSaftInvoicesAssembler.InvoiceRow(id)
        .number(number, null)
        .operationType(operation)
        .documentType(operation == 4 ? "03" : "01")
        .dates(APRIL_10, null)
        .partner(ALFA, ACCOUNT_CUSTOMERS)
        .move(1000 + id, "MOV-" + number, PERIOD);
  }

  static BgSaftInvoicesAssembler.InvoiceRow purchase(
      long id, String supplierNumber, long partner, int operation) {
    return new BgSaftInvoicesAssembler.InvoiceRow(id)
        .number("PUR-" + id, supplierNumber)
        .operationType(operation)
        .documentType(operation == 2 ? "03" : "01")
        .dates(APRIL_12, APRIL_5)
        .partner(partner, ACCOUNT_SUPPLIERS)
        .move(1000 + id, "MOV-PUR-" + id, PERIOD);
  }

  /** Ред с данък: основа и ДДС във валутата на дружеството, ставката в проценти. */
  static BgSaftInvoicesAssembler.LineRow line(
      long id, long invoice, int sequence, long account, String exTax, String vat) {
    BigDecimal base = new BigDecimal(exTax);
    return new BgSaftInvoicesAssembler.LineRow(id, invoice)
        .sequence(sequence)
        .type(0)
        .qty(BigDecimal.ONE)
        .amounts(base, base.add(new BigDecimal(vat)))
        .accountId(account);
  }

  static BgSaftInvoicesAssembler.InvoiceTaxRow invoiceTax(long invoice, long taxLine, String vat) {
    return new BgSaftInvoicesAssembler.InvoiceTaxRow(invoice, taxLine, new BigDecimal(vat), false);
  }

  // ─────────────── истинските seed-ове ───────────────

  /** {@code BgSafVatCellCode}: ключ на клетката → TAX-IMP код. */
  static Map<String, String> seedCellCodes() {
    Map<String, String> codes = new LinkedHashMap<>();
    for (String[] f :
        seed("bg_saf_vat_cell_code.csv", "importId;safTaxCode;cellCode;cellValue;description", 5)) {
      codes.put(BgSaftVatCell.key(f[2], f[3]), f[1]);
    }
    return codes;
  }

  /** {@code BgSafTaxMapping} по код на данъка. */
  static Map<String, BgSaftTaxTableAssembler.MappingRow> seedTaxMappings() {
    Map<String, BgSaftTaxTableAssembler.MappingRow> mappings = new LinkedHashMap<>();
    for (String[] f :
        seed(
            "bg_saf_tax_mapping.csv", "importId;taxCode;safTaxType;safTaxCode;isDynamic;note", 6)) {
      mappings.put(
          f[1], new BgSaftTaxTableAssembler.MappingRow(f[2], f[3], Boolean.parseBoolean(f[4])));
    }
    return mappings;
  }

  private static List<String[]> seed(String file, String header, int columns) {
    String resource = "/apps/init-data/en/" + file;
    List<String[]> rows = new ArrayList<>();
    try (InputStream in = BgSaftInvoiceFixtures.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " не е в classpath-а");
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      if (!header.equals(reader.readLine())) {
        throw new IllegalStateException(resource + ": заглавният ред е променен");
      }
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        // Последната колона е свободен текст — не се цепи.
        String[] f = line.split(";", columns);
        if (f.length != columns) {
          throw new IllegalStateException(resource + ": " + f.length + " полета в „" + line + "“");
        }
        rows.add(f);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }
}
