package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolCase.Outcome;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolCase.Supplier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Чистите правила на протокола от фактура: случаят, изискуемостта, датата и сумите. */
class BgVatInvoiceProtocolRulesTest {

  private static final Supplier GERMAN = new Supplier("DE123456789", "DE", false);
  private static final Supplier BULGARIAN = new Supplier("BG175074752", "BG", false);
  private static final Supplier AMERICAN = new Supplier(null, "US", false);
  private static final Supplier GREEK = new Supplier(null, "GR", false);
  private static final LocalDate EVENT = LocalDate.of(2026, 3, 10);

  private static BgVatInvoiceProtocolCase resolve(
      String self, String own, Boolean service, Supplier supplier, LocalDate event) {
    return BgVatInvoiceProtocolCase.resolve(List.of(self), List.of(own), service, supplier, event);
  }

  // ---------------------------------------------------------------------------
  // Случаят
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ВОП — чл. 84, протокол по чл. 117 ал. 1 т. 1")
  void вопът() {
    BgVatInvoiceProtocolCase vop = resolve("BG_VOP_SELF", "BG_VOP_ACQ", false, GERMAN, EVENT);
    assertEquals(Outcome.PROTOCOL, vop.getOutcome());
    assertEquals("VOP", vop.getOperation());
    assertEquals("чл. 84 ЗДДС", vop.getBasis());
    assertEquals("чл. 117, ал. 1, т. 1 ЗДДС", vop.getIssuedUnder());
    assertNull(vop.getSupplyType());
    assertNull(vop.getMessage());
  }

  @Test
  @DisplayName(
      "Чл. 82 ал. 2 т. 3 (услуги) и т. 1 (енергия) — SERVICE_EU / SERVICE_NONEU, к.14/к.15")
  void чл82() {
    BgVatInvoiceProtocolCase eu = resolve("BG_RCM_ART82_S", "BG_EU_S_RCM_P", true, GERMAN, EVENT);
    assertEquals("SERVICE_EU", eu.getOperation());
    assertEquals("чл. 82, ал. 2, т. 3 ЗДДС", eu.getBasis());
    assertEquals("чл. 117, ал. 1, т. 1 ЗДДС", eu.getIssuedUnder());

    assertEquals(
        "SERVICE_NONEU",
        resolve("BG_RCM_ART82_S", "BG_RCM_ART82_P", true, AMERICAN, EVENT).getOperation());
    assertEquals(
        "SERVICE_EU",
        resolve("BG_RCM_ART82_S", "BG_RCM_ART82_P", true, GREEK, EVENT).getOperation(),
        "Гърция е „EL“ във VIES");

    BgVatInvoiceProtocolCase energy =
        resolve("BG_RCM_ENERGY_S", "BG_RCM_ENERGY_P", false, AMERICAN, EVENT);
    assertEquals(Outcome.PROTOCOL, energy.getOutcome());
    assertEquals("SERVICE_NONEU", energy.getOperation());
    assertEquals("чл. 82, ал. 2, т. 1 ЗДДС", energy.getBasis());
  }

  @Test
  @DisplayName("Ред без продукт по общия чл. 82 — услуга с предупреждение „да се потвърди“")
  void чл82_без_продукт() {
    BgVatInvoiceProtocolCase unknown =
        resolve("BG_RCM_ART82_S", "BG_RCM_ART82_P", null, AMERICAN, EVENT);
    assertEquals(Outcome.PROTOCOL, unknown.getOutcome());
    assertTrue(unknown.getMessage().contains("да се потвърди"), unknown.getMessage());
  }

  @Test
  @DisplayName("163а: отпадъци „01“, зърно „02“ до 31.12.2026 вкл.; протокол по чл. 117 ал. 1 т. 4")
  void чл163а() {
    BgVatInvoiceProtocolCase scrap =
        resolve("BG_RCM_SCRAP_S", "BG_RCM_SCRAP_P", false, BULGARIAN, EVENT);
    assertEquals("ART163A", scrap.getOperation());
    assertEquals("01", scrap.getSupplyType());
    assertEquals("чл. 163а, ал. 2 ЗДДС", scrap.getBasis());
    assertEquals("чл. 117, ал. 1, т. 4 ЗДДС", scrap.getIssuedUnder());
    BgVatInvoiceProtocolCase grain =
        resolve("BG_RCM_GRAIN_S", "BG_RCM_GRAIN_P", false, BULGARIAN, LocalDate.of(2026, 12, 31));
    assertEquals("02", grain.getSupplyType(), "31.12.2026 е последният ден на част втора");
    BgVatInvoiceProtocolCase after =
        resolve("BG_RCM_GRAIN_S", "BG_RCM_GRAIN_P", false, BULGARIAN, LocalDate.of(2027, 1, 1));
    assertEquals(Outcome.ERROR, after.getOutcome());
    assertTrue(after.getMessage().contains("„В сила до 31.12.2026 г.“"), after.getMessage());
  }

  @Test
  @DisplayName(
      "163а от физическо лице без ДДС номер — ГРЕШКА: общият протокол по чл. 163б ал. 1 т. 2")
  void чл163а_от_физическо_лице() {
    BgVatInvoiceProtocolCase farmer =
        resolve("BG_RCM_GRAIN_S", "BG_RCM_GRAIN_P", false, new Supplier(null, "BG", true), EVENT);
    assertEquals(Outcome.ERROR, farmer.getOutcome());
    assertTrue(farmer.getMessage().contains("чл. 163б, ал. 1, т. 2"), farmer.getMessage());
    assertEquals(
        Outcome.PROTOCOL,
        resolve(
                "BG_RCM_GRAIN_S",
                "BG_RCM_GRAIN_P",
                false,
                new Supplier("BG8001011234", "BG", true),
                EVENT)
            .getOutcome(),
        "регистрираният земеделски производител");
  }

  @Test
  @DisplayName("ГРЕШКИ: СМР, мобилни, енергия/услуга от установен, стока по чл. 82, 167а, злато")
  void грешките() {
    assertTrue(
        resolve("BG_RCM_BUILD_S", "BG_RCM_BUILD_P", true, BULGARIAN, EVENT)
            .getMessage()
            .contains("няма основание за обратно начисляване"));
    assertTrue(
        resolve("BG_RCM_MOBILE_S", "BG_RCM_MOBILE_P", false, BULGARIAN, EVENT)
            .getMessage()
            .contains("няма основание за обратно начисляване"));
    assertEquals(
        Outcome.ERROR,
        resolve("BG_RCM_ENERGY_S", "BG_RCM_ENERGY_P", false, BULGARIAN, EVENT).getOutcome());
    assertTrue(
        resolve("BG_RCM_ART82_S", "BG_RCM_ART82_P", true, BULGARIAN, EVENT)
            .getMessage()
            .contains("не е установено на територията на страната"));
    assertTrue(
        resolve("BG_RCM_ART82_S", "BG_RCM_ART82_P", false, AMERICAN, EVENT)
            .getMessage()
            .contains("т. 2 е отменена"));
    assertTrue(
        resolve("BG_RCM_ART82_S", "BG_IMP_ART57", false, AMERICAN, EVENT)
            .getMessage()
            .contains("чл. 167а"));
    assertTrue(
        resolve("BG_GOLD_ART163G", "BG_SRP_20", false, BULGARIAN, EVENT)
            .getMessage()
            .contains("чл. 161"));
    assertEquals(
        Outcome.ERROR,
        BgVatInvoiceProtocolCase.resolve(
                List.of("BG_VOP_SELF", "BG_RCM_ART82_S"),
                List.of("BG_VOP_ACQ"),
                false,
                GERMAN,
                EVENT)
            .getOutcome());
  }

  @Test
  @DisplayName("„-EX“ при услуга → ПРЕДУПРЕЖДЕНИЕ; без обратно начисляване → NONE; бележките")
  void бележките() {
    Supplier small = new Supplier("BG175074752-EX", "AT", false);
    BgVatInvoiceProtocolCase service =
        resolve("BG_RCM_ART82_S", "BG_EU_S_RCM_P", true, small, EVENT);
    assertEquals(Outcome.WARNING, service.getOutcome());
    assertTrue(service.getMessage().contains("чл. 82, ал. 2, т. 3"));
    assertEquals(
        Outcome.NONE,
        BgVatInvoiceProtocolCase.resolve(List.of(), List.of("BG_TRIANG_P"), false, GERMAN, EVENT)
            .getOutcome());
    assertTrue(
        BgVatInvoiceProtocolCase.withoutReverseCharge(List.of("BG_NON_EU_SERV_P"))
            .contains("ЗДДС чл. 21"));
    assertNull(BgVatInvoiceProtocolCase.withoutReverseCharge(List.of("BG_TRIANG_P")));
  }

  // ---------------------------------------------------------------------------
  // Изискуемостта и датата
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "ВОП: 15-о число на следващия месец или датата на фактурата, ако е преди него (чл. 63)")
  void изискуемостта_на_вОП() {
    LocalDate fifteenth = LocalDate.of(2026, 4, 15);
    assertEquals(
        LocalDate.of(2026, 3, 25),
        BgVatProtocolDates.chargeability("VOP", EVENT, LocalDate.of(2026, 3, 25)));
    assertEquals(
        LocalDate.of(2026, 4, 14),
        BgVatProtocolDates.chargeability("VOP", EVENT, LocalDate.of(2026, 4, 14)));
    assertEquals(
        fifteenth,
        BgVatProtocolDates.chargeability("VOP", EVENT, LocalDate.of(2026, 4, 15)),
        "„преди 15-ия ден“ — на самия 15-и важи ал. 3");
    assertEquals(
        fifteenth, BgVatProtocolDates.chargeability("VOP", EVENT, LocalDate.of(2026, 4, 20)));
    assertEquals(fifteenth, BgVatProtocolDates.chargeability("VOP", EVENT, null));
    assertEquals(
        LocalDate.of(2026, 2, 15),
        BgVatProtocolDates.chargeability("VOP", LocalDate.of(2026, 1, 31), null));
    assertEquals(
        EVENT,
        BgVatProtocolDates.chargeability("SERVICE_EU", EVENT, LocalDate.of(2026, 3, 1)),
        "чл. 25 ал. 6");
    assertEquals(EVENT, BgVatProtocolDates.chargeability("ART163A", EVENT, null), "чл. 163а ал. 3");
  }

  @Test
  @DisplayName("Срокът е D_ch + 15 календарни дни; датата е в [D_ch; D_ch + 15]")
  void срокът() {
    LocalDate ch = LocalDate.of(2026, 3, 12);
    assertEquals(LocalDate.of(2026, 3, 27), BgVatProtocolDates.deadline(ch));

    BgVatProtocolDates.Placement byDefault =
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 3, 20), null);
    assertEquals(LocalDate.of(2026, 3, 20), byDefault.getProtocolDate());
    assertEquals(
        LocalDate.of(2026, 3, 20), byDefault.getMoveDate(), "в срока — периодът на издаване");
    assertFalse(byDefault.isLate());
    assertEquals(
        ch,
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 3, 1), null).getProtocolDate(),
        "по подразбиране не по-рано от изискуемостта");
    assertEquals(
        LocalDate.of(2026, 3, 27),
        BgVatProtocolDates.place(ch, null, LocalDate.of(2026, 3, 27)).getProtocolDate(),
        "последният ден е в срока");
    assertEquals(
        ch,
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 3, 25), ch).getProtocolDate(),
        "исканата");
    assertTrue(
        BgVatProtocolDates.place(ch, null, LocalDate.of(2026, 3, 28))
            .getRefusal()
            .contains("след срока по ЗДДС чл. 117, ал. 3 (2026-03-27)"));
    assertTrue(
        BgVatProtocolDates.place(ch, null, LocalDate.of(2026, 3, 11))
            .getRefusal()
            .contains("преди изискуемостта"));
  }

  @Test
  @DisplayName(
      "Закъснял протокол: издаден след срока, статията — в периода на изискуемостта (ППЗДДС чл. 81 ал. 5)")
  void закъснелият() {
    LocalDate ch = LocalDate.of(2026, 3, 12);
    // Фактурата е въведена на 05.04 — след срока 27.03.
    BgVatProtocolDates.Placement late =
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 4, 5), null);
    assertEquals(null, late.getRefusal());
    assertTrue(late.isLate());
    assertEquals(LocalDate.of(2026, 4, 5), late.getProtocolDate(), "датата на издаване");
    assertEquals(ch, late.getMoveDate(), "периодът на изискуемостта");
    // Исканата дата при закъснял протокол — не по-рано от датата на фактурата.
    assertTrue(
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 9)).isLate());
    assertTrue(
        BgVatProtocolDates.place(ch, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 1))
            .getRefusal()
            .contains("преди датата на фактурата"));
    // В срока пак може — тогава не е закъснял.
    assertFalse(BgVatProtocolDates.place(ch, LocalDate.of(2026, 4, 5), ch).isLate());
  }

  // ---------------------------------------------------------------------------
  // Курсът
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Курсът на ЕЦБ: пряк (USD→EUR) — умножение, обратен (EUR→USD, както го дава ЕЦБ) — деление, до цент")
  void курсът() {
    BgVatProtocolRate direct =
        BgVatProtocolRate.of(new BigDecimal("0.925926"), false, LocalDate.of(2026, 6, 12));
    assertEquals(new BigDecimal("925.93"), direct.toCompanyCurrency(new BigDecimal("1000")));
    BgVatProtocolRate ecb =
        BgVatProtocolRate.of(new BigDecimal("1.0800"), true, LocalDate.of(2026, 6, 12));
    assertEquals(new BigDecimal("925.93"), ecb.toCompanyCurrency(new BigDecimal("1000")));
    assertEquals(
        new BigDecimal("1234.57"),
        BgVatProtocolRate.SAME.toCompanyCurrency(new BigDecimal("1234.57")));
    assertTrue(ecb.describe().contains("1 EUR = 1.08"), ecb.describe());
  }

  // ---------------------------------------------------------------------------
  // Сумите
  // ---------------------------------------------------------------------------

  private static BgVatInvoiceProtocolPlan.Line line(
      BgVatInvoiceProtocolCase kase, String rate, String base, String credit, Long account) {
    return new BgVatInvoiceProtocolPlan.Line(
        kase, new BigDecimal(rate), new BigDecimal(base), credit, account);
  }

  @Test
  @DisplayName(
      "Групи по (случай, ставка); данъкът е основата × ставката до цент; дебитите дават данъка")
  void групите_и_сумите() {
    BgVatInvoiceProtocolCase vop = resolve("BG_VOP_SELF", "BG_VOP_ACQ", false, GERMAN, EVENT);
    BgVatInvoiceProtocolCase eu = resolve("BG_RCM_ART82_S", "BG_EU_S_RCM_P", true, GERMAN, EVENT);
    BgVatInvoiceProtocolCase energy =
        resolve("BG_RCM_ENERGY_S", "BG_RCM_ENERGY_P", false, GERMAN, EVENT);
    List<BgVatInvoiceProtocolPlan.Group> groups =
        BgVatInvoiceProtocolPlan.groups(
            List.of(
                line(vop, "20", "100.01", "1", null),
                line(vop, "20.00", "200.02", "2", null),
                line(vop, "9", "50", "1", null),
                line(eu, "20", "10", "1", null),
                line(energy, "20", "10", "1", null)));
    assertEquals(4, groups.size(), "енергията и услугата са различни основания");
    BgVatInvoiceProtocolPlan.Group first = groups.get(0);
    assertEquals(2, first.lineCount(), "20 и 20.00 са една ставка");
    assertEquals(new BigDecimal("300.03"), first.base());
    assertEquals(new BigDecimal("60.01"), first.tax());
    BigDecimal sum = BigDecimal.ZERO;
    for (BgVatInvoiceProtocolPlan.Debit d : first.debits()) {
      sum = sum.add(d.getAmount());
    }
    assertEquals(new BigDecimal("60.01"), sum, "сборът на дебитите е точно данъкът");
    assertEquals(new BigDecimal("0.2"), first.getRateFraction());
    assertEquals(
        new BigDecimal("246.91"),
        BgVatInvoiceProtocolPlan.groups(List.of(line(vop, "20", "1234.57", "1", null)))
            .get(0)
            .tax());
  }

  @Test
  @DisplayName(
      "Валута: основата е сборът във валутата, превърнат ВЕДНЪЖ по курса (400 + 600 USD при 1.08 → 925.93, данък 185.19)")
  void групата_във_валута() {
    BgVatInvoiceProtocolCase vop = resolve("BG_VOP_SELF", "BG_VOP_ACQ", false, GERMAN, EVENT);
    BgVatInvoiceProtocolPlan.Group group =
        BgVatInvoiceProtocolPlan.groups(
                List.of(
                    new BgVatInvoiceProtocolPlan.Line(
                        vop,
                        new BigDecimal("20"),
                        new BigDecimal("400"),
                        new BigDecimal("372"),
                        "1",
                        null),
                    new BgVatInvoiceProtocolPlan.Line(
                        vop,
                        new BigDecimal("20"),
                        new BigDecimal("600"),
                        new BigDecimal("558"),
                        "2",
                        null)))
            .get(0);
    BgVatProtocolRate ecb =
        BgVatProtocolRate.of(new BigDecimal("1.0800"), true, LocalDate.of(2026, 6, 12));
    // 400 / 1.08 + 600 / 1.08 по отделно е 370.37 + 555.56 = 925.93 — тук съвпада, но правилото е
    // сборът, превърнат веднъж.
    assertEquals(new BigDecimal("925.93"), group.base(ecb));
    assertEquals(new BigDecimal("185.19"), group.tax(ecb));
    assertEquals(
        new BigDecimal("930.00"), group.companyBase(), "по курса на фактурата — за разликата");
    BigDecimal sum = BigDecimal.ZERO;
    for (BgVatInvoiceProtocolPlan.Debit d : group.debits(ecb)) {
      sum = sum.add(d.getAmount());
    }
    assertEquals(new BigDecimal("185.19"), sum);
    assertEquals(new BigDecimal("74.08"), group.debits(ecb).get(0).getAmount(), "дял 400/1000");
  }

  @Test
  @DisplayName("Без право на кредит → дебит по сметката на реда; различни сметки — различни дебити")
  void дебитите_без_право() {
    BgVatInvoiceProtocolCase vop = resolve("BG_VOP_SELF", "BG_VOP_ACQ", false, GERMAN, EVENT);
    List<BgVatInvoiceProtocolPlan.Debit> debits =
        BgVatInvoiceProtocolPlan.groups(
                List.of(
                    line(vop, "20", "100", "3", 304L),
                    line(vop, "20", "100", "3", 602L),
                    line(vop, "20", "100", "3", 304L),
                    line(vop, "20", "100", null, 304L)))
            .get(0)
            .debits();
    assertEquals(3, debits.size());
    assertEquals(Long.valueOf(304L), debits.get(0).expenseAccountId());
    assertEquals(new BigDecimal("40.00"), debits.get(0).getAmount());
    assertEquals(Long.valueOf(602L), debits.get(1).expenseAccountId());
    assertNull(debits.get(2).expenseAccountId(), "непопълненият тип е по 4531 — с предупреждение");
    assertNull(debits.get(2).getCreditType());
    assertEquals(new BigDecimal("20.00"), debits.get(2).getAmount());
  }

  @Test
  @DisplayName("Заварен протокол покрива групата: операция, ставка (ако е въведена), основание")
  void същият_протокол() {
    BgVatInvoiceProtocolCase eu = resolve("BG_RCM_ART82_S", "BG_EU_S_RCM_P", true, GERMAN, EVENT);
    BgVatInvoiceProtocolPlan.Group group =
        BgVatInvoiceProtocolPlan.groups(List.of(line(eu, "20", "10", "1", null))).get(0);
    assertTrue(BgVatInvoiceProtocolPlan.sameProtocol(group, "SERVICE_EU", BigDecimal.ZERO, null));
    assertTrue(
        BgVatInvoiceProtocolPlan.sameProtocol(
            group, "SERVICE_EU", new BigDecimal("0.20"), "чл. 82, ал. 2, т. 3 ЗДДС"));
    assertTrue(
        !BgVatInvoiceProtocolPlan.sameProtocol(group, "SERVICE_EU", new BigDecimal("0.09"), null));
    assertTrue(!BgVatInvoiceProtocolPlan.sameProtocol(group, "VOP", BigDecimal.ZERO, null));
    assertTrue(
        !BgVatInvoiceProtocolPlan.sameProtocol(
            group, "SERVICE_EU", BigDecimal.ZERO, "чл. 82, ал. 2, т. 1 ЗДДС"),
        "енергията не покрива услугата");
  }
}
