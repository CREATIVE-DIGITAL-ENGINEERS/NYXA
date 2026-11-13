package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЕТРО-РЕЖИМЪТ НА НИВО УСЛУГА: кой документ поражда статия, къде сяда тя, и кога перо 620 се
 * изключва тихо (никога).
 *
 * <p><b>⚠️ КАКВО БЕШЕ СЪСТОЯНИЕТО ПРЕДИ ТОЗИ КЛАС.</b> Коригиращият фиш НЕ пораждаше статия — не по
 * решение, а по устройство: генераторът беше окачен само на {@code pay()}, а {@code
 * BgPaySlipResolver#refusePayRetro} отказва изплащането на ретро-фиш на първия ред. Тоест
 * корекцията минаваше през коригираща Д1/Д6 и НЕ влизаше в счетоводството никъде. Тестовете отдолу
 * заковават и двете половини на поправката: че ретро-фишът вече има своя статия (и то в СВОЯ
 * период), и че текущата статия отказва да се роди, докато тази статия липсва.
 *
 * <p>⚠️ Подменени са само трите шева към базата — намирането на сметка, намирането на ретро-фиша и
 * самият запис. Гейтовете и постинг моделът са това, което тестът мери.
 */
class BgPaySlipRetroMoveTest {

  private static final Period MARCH = period("03/2026", LocalDate.of(2026, 3, 1), 31);
  private static final Period MAY = period("05/2026", LocalDate.of(2026, 5, 1), 31);
  private static final Period LAST_DECEMBER = period("12/2025", LocalDate.of(2025, 12, 1), 31);

  // ── 1 · коригиращият фиш вече ражда статия, и то в СВОЯ период ──────────

  @Test
  @DisplayName("1 · коригиращ мартенски фиш поражда статия с ДЕЛТАТА и с мартенска дата")
  void aCorrectingSlipYieldsItsOwnArticleInTheCorrectedMonth() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip original = slip("FISH-03", MARCH, marchAsFiled(), fixture);
    original.setBgPayrollMove(new Move()); // мартенският оригинал е осчетоводен
    BgPaySlip correcting = slip("FISH-03-K", MARCH, marchAsCorrected(), fixture);
    correcting.setBgIsCorrection(true);
    correcting.setBgCorrectionOf(original);

    fixture.service.generate(correcting);

    assertNotNull(correcting.getBgPayrollMove(), correcting.getBgPayrollMoveNote());
    assertNull(correcting.getBgPayrollMoveNote());
    // ⚠️ ДАТАТА Е КРАЯТ НА МАРТ, не „днес“ и не датата на изплащане на майския фиш. Коригиращият
    // фиш не се изплаща, тоест bgPaidDate е празна и остава периодът — неговият период е март.
    assertEquals(LocalDate.of(2026, 3, 31), fixture.date);
    assertTrue(fixture.description.contains("Корекция"), fixture.description);
    assertTrue(fixture.description.contains("03/2026"), fixture.description);

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Cr 4211", "160.56");
    expected.put("Cr 4541", "14.90");
    expected.put("Cr 4551", "40.91");
    expected.put("Cr 4552", "16.95");
    expected.put("Dr 6041", "200.00");
    expected.put("Dr 6051", "23.15");
    expected.put("Dr 6052", "10.17");
    assertEquals(expected, fixture.written(), "в статията влиза САМО разликата");
  }

  @Test
  @DisplayName("2 · заличаващ фиш поражда СТОРНО на целия оригинал")
  void aDeletionSlipReversesTheOriginal() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip original = slip("FISH-03", MARCH, marchAsFiled(), fixture);
    original.setBgPayrollMove(new Move());
    BgPaySlip deleting = slip("FISH-03-Z", MARCH, marchAsFiled(), fixture);
    deleting.setBgIsDeletion(true);
    deleting.setBgDeletionOf(original);

    fixture.service.generate(deleting);

    assertNotNull(deleting.getBgPayrollMove(), deleting.getBgPayrollMoveNote());
    assertTrue(fixture.description.contains("Заличаване"), fixture.description);
    // Нетото на март се връща: 4211 се ДЕБИТИРА с него.
    assertEquals("1181.65", fixture.written().get("Dr 4211"));
    assertEquals("1500.00", fixture.written().get("Cr 6041"), "разходът за март се сторнира цял");
  }

  @Test
  @DisplayName("3 · неосчетоводен оригинал спира ретро-статията с причина, която го НАЗОВАВА")
  void anUnpostedOriginalStopsTheRetroArticle() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip original = slip("FISH-03", MARCH, marchAsFiled(), fixture);
    original.setBgPayrollMoveNote("Заплатният журнал не беше попълнен през март.");
    BgPaySlip correcting = slip("FISH-03-K", MARCH, marchAsCorrected(), fixture);
    correcting.setBgIsCorrection(true);
    correcting.setBgCorrectionOf(original);

    fixture.service.generate(correcting);

    assertNull(correcting.getBgPayrollMove());
    assertTrue(
        correcting.getBgPayrollMoveNote().contains("FISH-03"), correcting.getBgPayrollMoveNote());
    // ⚠️ И ПРИЧИНАТА ЗА ЛИПСАТА НА ОРИГИНАЛНАТА СТАТИЯ СЕ ПРЕПИСВА, а не се търси на друг екран:
    // делтата е безсмислена без нея и поправката е там, не тук.
    assertTrue(
        correcting.getBgPayrollMoveNote().contains("журнал"), correcting.getBgPayrollMoveNote());
    assertEquals(0, fixture.writes, "нищо не се записва");
  }

  // ── 4 · текущата статия и отложеното перо ───────────────────────────────

  @Test
  @DisplayName("4 · майската статия се ражда и НЕ съдържа 620, щом ретро-статията я има")
  void theCurrentArticleIsBornOnceTheRetroArticleExists() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip retro = slip("FISH-03-K", MARCH, marchAsCorrected(), fixture);
    retro.setBgIsCorrection(true);
    retro.setBgPayrollMove(new Move()); // ретро-статията вече съществува
    fixture.retroSlips.put(MARCH, retro);

    BgPaySlip may = slip("FISH-05", MAY, mayWithRetroLine("160.56", MARCH), fixture);
    may.setBgPaidDate(LocalDate.of(2026, 6, 10));

    fixture.service.generate(may);

    assertNotNull(may.getBgPayrollMove(), may.getBgPayrollMoveNote());
    assertNull(may.getBgPayrollMoveNote());
    assertEquals(
        LocalDate.of(2026, 6, 10), fixture.date, "редовната статия е на датата на плащане");
    // 4211 носи майското нето БЕЗ делтата: 1181,65, не 1342,21.
    assertEquals("1500.00", fixture.written().get("Cr 4211"));
    assertEquals("318.35", fixture.written().get("Dr 4211"));
  }

  @Test
  @DisplayName("5 · липсващ ретро-фиш ОТМЕНЯ текущата статия — 620 не се изключва тихо")
  void aMissingRetroSlipCancelsTheCurrentArticle() throws Exception {
    Fixture fixture = new Fixture(); // нито един ретро-фиш
    BgPaySlip may = slip("FISH-05", MAY, mayWithRetroLine("160.56", MARCH), fixture);
    may.setBgPaidDate(LocalDate.of(2026, 6, 10));

    fixture.service.generate(may);

    assertNull(may.getBgPayrollMove(), "тихо изключване би оставило делтата неосчетоводена никъде");
    assertTrue(may.getBgPayrollMoveNote().contains("620"), may.getBgPayrollMoveNote());
    assertTrue(may.getBgPayrollMoveNote().contains("03/2026"), may.getBgPayrollMoveNote());
    assertTrue(may.getBgPayrollMoveNote().contains("не се намира"), may.getBgPayrollMoveNote());
    assertEquals(0, fixture.writes);
  }

  @Test
  @DisplayName("6 · ретро-фиш БЕЗ статия също отменя текущата, и го назовава")
  void aRetroSlipWithoutItsArticleAlsoCancelsTheCurrentOne() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip retro = slip("FISH-03-K", MARCH, marchAsCorrected(), fixture);
    retro.setBgIsCorrection(true); // но без bgPayrollMove
    fixture.retroSlips.put(MARCH, retro);

    BgPaySlip may = slip("FISH-05", MAY, mayWithRetroLine("160.56", MARCH), fixture);
    may.setBgPaidDate(LocalDate.of(2026, 6, 10));

    fixture.service.generate(may);

    assertNull(may.getBgPayrollMove());
    assertTrue(may.getBgPayrollMoveNote().contains("FISH-03-K"), may.getBgPayrollMoveNote());
    assertTrue(may.getBgPayrollMoveNote().contains("без статия"), may.getBgPayrollMoveNote());
  }

  // ── 7 · извън обхвата: корекция през година ─────────────────────────────

  @Test
  @DisplayName("7 · корекция от МИНАЛА ГОДИНА се отказва поименно и сочи 122")
  void aCrossYearCorrectionIsRefusedByName() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip retro = slip("FISH-12-K", LAST_DECEMBER, marchAsCorrected(), fixture);
    retro.setBgIsCorrection(true);
    retro.setBgPayrollMove(new Move()); // ⚠️ дори ГОТОВА ретро-статия не отваря този режим
    fixture.retroSlips.put(LAST_DECEMBER, retro);

    BgPaySlip may = slip("FISH-05", MAY, mayWithRetroLine("80.00", LAST_DECEMBER), fixture);
    may.setBgPaidDate(LocalDate.of(2026, 6, 10));

    fixture.service.generate(may);

    assertNull(may.getBgPayrollMove());
    assertTrue(may.getBgPayrollMoveNote().contains("122"), may.getBgPayrollMoveNote());
    assertTrue(may.getBgPayrollMoveNote().contains("12/2025"), may.getBgPayrollMoveNote());
    assertTrue(
        may.getBgPayrollMoveNote().contains("ДРУГА година"),
        "отказът трябва да казва КОЕ го прави различен: " + may.getBgPayrollMoveNote());
    assertEquals(0, fixture.writes);
  }

  // ── 8 · идемпотентност в ретро-потока ───────────────────────────────────

  @Test
  @DisplayName("8 · второ потвърждаване на ретро-фиш не строи втора статия")
  void aRetroSlipDoesNotGetASecondArticle() throws Exception {
    Fixture fixture = new Fixture();
    BgPaySlip original = slip("FISH-03", MARCH, marchAsFiled(), fixture);
    original.setBgPayrollMove(new Move());
    BgPaySlip correcting = slip("FISH-03-K", MARCH, marchAsCorrected(), fixture);
    correcting.setBgIsCorrection(true);
    correcting.setBgCorrectionOf(original);

    fixture.service.generate(correcting);
    Move first = correcting.getBgPayrollMove();
    fixture.service.generate(correcting);

    assertEquals(1, fixture.writes, "втора статия не се строи");
    assertEquals(first, correcting.getBgPayrollMove());
  }

  // ── подпората ───────────────────────────────────────────────────────────

  /** Услугата с подменени ТРИ шева: сметката, ретро-фишът и записът. */
  private static final class Fixture {

    final Map<Period, BgPaySlip> retroSlips = new LinkedHashMap<>();
    final Recording service = new Recording(this);
    List<BgPayrollPosting> postings = new ArrayList<>();
    LocalDate date;
    String description;
    int writes;

    private EmploymentContract contract;

    /** ЕДИН договор за всички фишове на фикстурата — фирмата, валутата и журналът висят на него. */
    EmploymentContract contract() {
      if (contract == null) {
        Company company = new Company();
        company.setName("Тест ЕООД");
        Currency euro = new Currency();
        euro.setCode("EUR");
        company.setCurrency(euro);
        AccountConfig config = new AccountConfig();
        config.setBgPayrollJournal(journal());
        company.setAccountConfig(config);
        contract = new EmploymentContract();
        contract.setPayCompany(company);
        contract.setBgInsuredTypeCode("01");
      }
      return contract;
    }

    Map<String, String> written() {
      Map<String, String> out = new LinkedHashMap<>();
      for (BgPayrollPosting p : postings) {
        out.put((p.isDebit() ? "Dr " : "Cr ") + p.getAccountCode(), p.getAmount().toPlainString());
      }
      return out;
    }
  }

  private static final class Recording extends BgPaySlipMoveService {

    private final Fixture fixture;

    Recording(Fixture fixture) {
      super(null, null, null, null, null, null, null);
      this.fixture = fixture;
    }

    /** Осчетоводяването на AOS иска контейнер; тук се брои, не се вика. */
    @Override
    protected void post(Move move) {}

    @Override
    protected Account accountFor(String code, Company company) {
      AccountType type = new AccountType();
      type.setTechnicalTypeSelect(
          code.startsWith("60") ? "charge" : code.startsWith("70") ? "income" : "debt");
      Account account = new Account();
      account.setCode(code);
      account.setAccountType(type);
      return account;
    }

    @Override
    protected BgPaySlip retroSlipFor(BgPaySlip slip, Period corrected) {
      return fixture.retroSlips.get(corrected);
    }

    @Override
    protected Move write(
        BgPaySlip slip,
        Company company,
        Journal journal,
        List<BgPayrollPosting> postings,
        String description) {
      fixture.writes++;
      fixture.postings = postings;
      fixture.date = dateOf(slip);
      fixture.description = description;
      Move move = new Move();
      move.setReference("MOVE-" + fixture.writes);
      return move;
    }
  }

  // ── фишовете ────────────────────────────────────────────────────────────

  private static BgPaySlip slip(
      String reference, Period period, List<BgPaySlipLine> lines, Fixture fixture) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference(reference);
    slip.setPeriod(period);
    slip.setEmploymentContract(fixture.contract());
    slip.setBgPaySlipLineList(new ArrayList<>(lines));
    return slip;
  }

  private static List<BgPaySlipLine> marchAsFiled() {
    return month("1500.00", "173.63", "133.24", "76.32", "50.88", "134.23");
  }

  private static List<BgPaySlipLine> marchAsCorrected() {
    return month("1700.00", "196.78", "151.00", "86.49", "57.66", "149.13");
  }

  private static List<BgPaySlipLine> mayWithRetroLine(String delta, Period corrected) {
    List<BgPaySlipLine> lines = new ArrayList<>(marchAsFiled());
    BgPaySlipLine retro = line(620, "Доплащане за минал период", "CREDIT", delta);
    retro.setBgCorrectsPeriod(corrected);
    lines.add(retro);
    return lines;
  }

  private static List<BgPaySlipLine> month(
      String gross,
      String pensionEmployer,
      String pensionEmployee,
      String healthEmployer,
      String healthEmployee,
      String tax) {
    List<BgPaySlipLine> lines = new ArrayList<>();
    BgPaySlipLine wage = line(100, "Основно възнаграждение", "CREDIT", gross);
    wage.setBgFormsGrossWage(true);
    lines.add(wage);
    lines.add(contribution(500, "Пенсии · осигурител", "PENSION", "CREDIT", pensionEmployer));
    lines.add(contribution(501, "Пенсии · лице", "PENSION", "DEBIT", pensionEmployee));
    lines.add(contribution(516, "Здравно · осигурител", "HEALTH", "CREDIT", healthEmployer));
    lines.add(contribution(517, "Здравно · лице", "HEALTH", "DEBIT", healthEmployee));
    lines.add(line(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", tax));
    return lines;
  }

  /** Журнал PAY с типовете от BG_COA — включително „income“, заради 709. */
  private static Journal journal() {
    Journal journal = new Journal();
    journal.setCode("PAY");
    journal.setName("Заплати и осигуровки");
    LinkedHashSet<AccountType> types = new LinkedHashSet<>();
    for (String technical :
        List.of("cash", "receivable", "debt", "payable", "tax", "income", "charge")) {
      AccountType type = new AccountType();
      type.setTechnicalTypeSelect(technical);
      type.setName(technical);
      types.add(type);
    }
    journal.setValidAccountTypeSet(types);
    return journal;
  }

  private static Period period(String name, LocalDate from, int days) {
    Period period = new Period();
    period.setName(name);
    period.setFromDate(from);
    period.setToDate(from.plusDays(days - 1));
    return period;
  }

  private static BgPaySlipLine contribution(
      int number, String name, String fundCode, String sign, String amount) {
    BgPaySlipLine line = line(number, name, sign, amount);
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setName(fundCode);
    line.setBgContributionFund(fund);
    return line;
  }

  private static BgPaySlipLine line(int number, String name, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    return line;
  }
}
