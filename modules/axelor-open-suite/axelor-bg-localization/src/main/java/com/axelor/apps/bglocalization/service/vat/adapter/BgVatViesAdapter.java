package com.axelor.apps.bglocalization.service.vat.adapter;

import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.field;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatAmount;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padLeft;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padRight;

import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/**
 * Builds the <b>VIES.TXT</b> declaration (Приложение №12/№15). Multi-section fixed-width, CP1251,
 * one record per section, CRLF after EVERY record (incl. the last — the writer handles that).
 *
 * <p><b>Variant 1 implemented</b> (VHR·VDR·VTR·TTR·VIR), byte-verified against VIES.txt. The
 * warehousing (call-off stock) variants — sections <b>CHR/COS</b> (варианти 2/3, чл.15а) — are
 * documented in the NAP VAT file specification but NOT implemented: no golden fixture yet.
 *
 * <p>Section lengths: VHR=15, VDR=373, VTR=368, TTR=27, VIR=66.
 *
 * <p>VIR rows are extracted from SALES documents with doc type ∈ {01,02,03,09} whose counterparty
 * VIN is a non-BG EU number, grouped by VIN. The three amounts sum the sales-diary columns via
 * {@link BgVatSalesAdapter} (routing lives there): goods = ВОД (02-15 = {@link
 * BgVatSalesColumn#K20_BASE_ICS}), triangular = 02-25 ({@link
 * BgVatSalesColumn#K25_BASE_TRIANGULAR}), services = 02-17 ({@link
 * BgVatSalesColumn#K22_BASE_EU_SERVICES}).
 */
@Singleton
public class BgVatViesAdapter {

  private static final int LEN_VHR = 15;
  private static final int LEN_VDR = 373;
  private static final int LEN_VTR = 368;
  private static final int LEN_TTR = 27;
  private static final int LEN_VIR = 66;

  private static final int W_AMOUNT = 12; // VIR/TTR value fields

  /** Doc types eligible for a VIES row. */
  private static final Set<String> VIES_DOC_TYPES =
      new HashSet<>(Arrays.asList("01", "02", "03", "09"));

  private static final String BG_PREFIX = "BG";

  /** Префиксът на Северна Ирландия по протокола към Споразумението за оттегляне — само стоки. */
  private static final String NORTHERN_IRELAND = "XI";

  private final BgVatSalesAdapter salesAdapter;

  @Inject
  public BgVatViesAdapter(BgVatSalesAdapter salesAdapter) {
    this.salesAdapter = salesAdapter;
  }

  /** Registered person (VTR): ИН по ДДС, name, address. Phase-later sourced from company config. */
  public static final class RegisteredPerson {
    private final String vatNumber;
    private final String name;
    private final String address;

    public RegisteredPerson(String vatNumber, String name, String address) {
      this.vatNumber = vatNumber;
      this.name = name;
      this.address = address;
    }
  }

  /** Declarant (VDR): ЕГН/ЛНЧ, three names, city, postcode, address, capacity (A/R). */
  public static final class Declarant {
    private final String egn;
    private final String names;
    private final String city;
    private final String postCode;
    private final String address;
    private final String capacity; // "A" = пълномощник, "R" = представляващ

    public Declarant(
        String egn, String names, String city, String postCode, String address, String capacity) {
      this.egn = egn;
      this.names = names;
      this.city = city;
      this.postCode = postCode;
      this.address = address;
      this.capacity = capacity;
    }
  }

  /**
   * One aggregated VIR line: taxable bases per VIN and per period of chargeability (ППЗДДС чл. 117
   * ал. 2 т. 11) — {@code ""} when it is the declaration period.
   */
  private static final class ViesRow {
    final String vin;
    final String period;
    BigDecimal goods = BigDecimal.ZERO; // ВОД (02-15)
    BigDecimal triangular = BigDecimal.ZERO; // тристранни посредник (02-25)
    BigDecimal services = BigDecimal.ZERO; // услуги чл.21 ал.2 (02-17)

    ViesRow(String vin, String period) {
      this.vin = vin;
      this.period = period;
    }
  }

  /**
   * Build the whole VIES.TXT as an ordered list of section records (VHR, VDR, VTR, TTR, VIR…). The
   * writer appends CRLF after every record.
   *
   * @param period the declaration period as {@code "мм/гггг"} (e.g. {@code "06/2026"})
   */
  public List<String> buildLines(
      List<BgVatDocumentDto> salesDocuments,
      RegisteredPerson person,
      Declarant declarant,
      String period) {
    List<BgVatDocumentDto> sales =
        salesDocuments != null ? salesDocuments : Collections.emptyList();
    List<ViesRow> rows = extractRows(sales, period);

    BigDecimal totalAll = BigDecimal.ZERO; // ДО общо (стоки+тристранни+услуги)
    BigDecimal totalGoods = BigDecimal.ZERO; // ДО ВОД (стоки)
    for (ViesRow r : rows) {
      totalAll = totalAll.add(r.goods).add(r.triangular).add(r.services);
      totalGoods = totalGoods.add(r.goods);
    }

    List<String> out = new ArrayList<>();
    out.add(section(vhr(period, rows.size()), LEN_VHR, "VHR"));
    out.add(section(vdr(declarant), LEN_VDR, "VDR"));
    out.add(section(vtr(person), LEN_VTR, "VTR"));
    out.add(section(ttr(totalAll, totalGoods), LEN_TTR, "TTR"));
    int idx = 0;
    for (ViesRow r : rows) {
      idx++;
      out.add(section(vir(idx, r, period), LEN_VIR, "VIR"));
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Row extraction (grouped by VIN and period of chargeability, first-appearance order)
  // ---------------------------------------------------------------------------

  /**
   * VIR редовете. ППЗДДС чл. 117 ал. 5 т. 4: VIES се подава и когато лицето „е отразило пропуснати
   * данни за предходен данъчен период“ — такъв документ е СВОЙ ред с периода на изискуемостта (т.
   * 11, {@link BgVatViesPeriod}), отделен от реда на същия получател за текущия период.
   *
   * @param declarationPeriod периодът на декларацията като „мм/гггг“
   */
  protected List<ViesRow> extractRows(List<BgVatDocumentDto> salesDocs, String declarationPeriod) {
    Map<String, ViesRow> byVin = new LinkedHashMap<>();
    List<BgVatSalesRowDto> salesRows = salesAdapter.buildRows(salesDocs, null);
    for (int i = 0; i < salesDocs.size(); i++) {
      BgVatDocumentDto doc = salesDocs.get(i);
      String vin = doc.getCounterpartyVatNumber();
      if (!isViesDoc(doc, vin)) {
        continue;
      }
      // Само редът: той вече носи и колоните на протоколите (BgVatSalesAdapter.toRow).
      BgVatSalesRowDto row = salesRows.get(i);
      BigDecimal goods = row.getAmount(BgVatSalesColumn.K20_BASE_ICS);
      BigDecimal triangular = row.getAmount(BgVatSalesColumn.K25_BASE_TRIANGULAR);
      // XI (Северна Ирландия) е в VIES само за СТОКИ — за услугите тя не е държава членка.
      BigDecimal services =
          isNorthernIreland(vin)
              ? BigDecimal.ZERO
              : row.getAmount(BgVatSalesColumn.K22_BASE_EU_SERVICES);
      // ЗДДС чл. 125 ал. 2: VIES е за ВОД, доставки като посредник в тристранна операция и услуги
      // по чл. 21 ал. 2 в друга ДЧ. Документ без нито една от трите не е VIES ред — иначе всеки ВОП
      // протокол на доставчик от ЕС излизаше като ред с нули.
      if (goods.signum() == 0 && triangular.signum() == 0 && services.signum() == 0) {
        continue;
      }
      // ППЗДДС чл. 117 ал. 4: документът за аванс по ВОД и авансът, получен от посредник в
      // тристранна операция, не са във VIES; авансът за услуга по чл. 21 ал. 2 е (ал. 2 т. 4 б.
      // „б“).
      if (doc.isAdvancePayment()) {
        goods = BigDecimal.ZERO;
        triangular = BigDecimal.ZERO;
      }
      // Всеки вид по своето правило (BgVatViesPeriod): един документ може да даде няколко реда,
      // ако видовете са станали изискуеми в различни периоди.
      add(
          byVin,
          vin.trim(),
          doc,
          BgVatViesPeriod.Kind.INTRA_COMMUNITY_SUPPLY,
          goods,
          declarationPeriod);
      add(
          byVin,
          vin.trim(),
          doc,
          BgVatViesPeriod.Kind.TRIANGULAR_INTERMEDIARY,
          triangular,
          declarationPeriod);
      add(byVin, vin.trim(), doc, BgVatViesPeriod.Kind.SERVICE, services, declarationPeriod);
    }
    return new ArrayList<>(byVin.values());
  }

  private boolean isViesDoc(BgVatDocumentDto doc, String vin) {
    if (vin == null) {
      return false;
    }
    String v = vin.trim();
    String country = BgVatNumberValidator.countryCode(v);
    // ДДС номер на ДРУГА държава членка (вкл. XI) — не „999…“, не номер извън ЕС, не български.
    return VIES_DOC_TYPES.contains(field(doc.getNapDocumentType()))
        && country != null
        && !BG_PREFIX.equals(country)
        && BgVatNumberValidator.isViesCountry(country);
  }

  private static boolean isNorthernIreland(String vin) {
    return NORTHERN_IRELAND.equals(BgVatNumberValidator.countryCode(vin));
  }

  private static void add(
      Map<String, ViesRow> rows,
      String vin,
      BgVatDocumentDto doc,
      BgVatViesPeriod.Kind kind,
      BigDecimal amount,
      String declarationPeriod) {
    if (amount.signum() == 0) {
      return;
    }
    String period =
        BgVatViesPeriod.label(
            BgVatViesPeriod.chargeability(doc.getDocumentDate(), doc.getTaxEventDate(), kind),
            declarationPeriod);
    ViesRow row = rows.computeIfAbsent(vin + "|" + period, key -> new ViesRow(vin, period));
    switch (kind) {
      case INTRA_COMMUNITY_SUPPLY:
        row.goods = row.goods.add(amount);
        break;
      case TRIANGULAR_INTERMEDIARY:
        row.triangular = row.triangular.add(amount);
        break;
      default:
        row.services = row.services.add(amount);
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // Section builders
  // ---------------------------------------------------------------------------

  private String vhr(String period, int count) {
    return "VHR" + padRight(field(period), 7) + padLeft(Integer.toString(count), 5);
  }

  private String vdr(Declarant d) {
    return "VDR"
        + padRight(field(d.egn), 15)
        + padRight(field(d.names), 150)
        + padRight(field(d.city), 50)
        + padLeft(field(d.postCode), 4)
        + padRight(field(d.address), 150)
        + padRight(field(d.capacity), 1);
  }

  private String vtr(RegisteredPerson p) {
    return "VTR"
        + padRight(field(p.vatNumber), 15)
        + padRight(field(p.name), 150)
        + padRight(field(p.address), 200);
  }

  private String ttr(BigDecimal totalAll, BigDecimal totalGoods) {
    return "TTR"
        + padLeft(formatAmount(totalAll), W_AMOUNT)
        + padLeft(formatAmount(totalGoods), W_AMOUNT);
  }

  private String vir(int index, ViesRow r, String declarationPeriod) {
    // т. 11: празно, когато изискуемостта е в периода на декларацията (обичайният случай).
    String virPeriod = r.period;
    return "VIR"
        + padLeft(Integer.toString(index), 5)
        + padRight(field(r.vin), 15)
        + padLeft(formatAmount(r.goods), W_AMOUNT)
        + padLeft(formatAmount(r.triangular), W_AMOUNT)
        + padLeft(formatAmount(r.services), W_AMOUNT)
        + padRight(field(virPeriod), 7);
  }

  private static String section(String record, int expectedLength, String tag) {
    if (record.length() != expectedLength) {
      throw new IllegalStateException(
          tag + " record must be " + expectedLength + " chars but was " + record.length());
    }
    return record;
  }

  // TODO Variant 2/3 (call-off stock, чл.15а): sections CHR (header) + COS (per-movement rows) —
  // documented in the NAP VAT file specification §6 but not implemented (no golden fixture yet).
}
