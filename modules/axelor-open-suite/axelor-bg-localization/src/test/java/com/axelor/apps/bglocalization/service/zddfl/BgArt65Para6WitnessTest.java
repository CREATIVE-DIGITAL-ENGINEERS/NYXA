package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.service.art55.BgArt55Aggregator;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧЛ. 65 АЛ. 6 — ЦЕЛИЯТ ПЪТ: КАТАЛОГЪТ → РЕЗОЛВЕРЪТ → ЧАСТ ІV НА ОБРАЗЕЦ 4001.
 *
 * <p>Чл. 65 ал. 6, ДОСЛОВНО: „Данъкът по чл. 46 за доходите по чл. 37, ал. 1, т. 10 - 12 и чл. 38,
 * ал. 5 се внася по реда на чл. 67 от лицето, придобило дохода.“ Чл. 67 ал. 1 казва къде отива
 * тогава: „Когато платецът на дохода не е задължен да удържа и внася данъка, данъкът по чл. 43, 44
 * и 46 се внася от ЛИЦЕТО, ПРИДОБИЛО ДОХОДА“ — тоест в КОЛОНА 4 на НЕГОВАТА декларация, не в колона
 * 3 на нашата.
 *
 * <p><b>Защо тестът тръгва от SEED-а, а не от изписан {@code BgIncomeType}.</b> Твърдението не е
 * „резолверът уважава един {@code boolean}“ — това {@link BgIncomeTaxResolverTest} вече го пита.
 * Твърдението е, че КАТАЛОГЪТ носи верния булев за тези четири кода и че сложен в машината, той
 * стига до вярната колона. Изписан в теста, признакът щеше да мери теста, а не seed-а.
 *
 * <p><b>⚠️ ПО ОБРАЗЕЦА НА СВИДЕТЕЛЯ ЗА ДЕКЛАРИРАЛО СОЛ</b> ({@code
 * BgIncomePaymentFeedChainTest#serviceToDeclaredSelfInsuredStaysOutOfColumnThree}), защото
 * хипотезата е СЪЩАТА — платецът не удържа, лицето внася само — само основанието е друго: там чл.
 * 43 ал. 5 и 6 и признак на ПЛАЩАНЕТО, тук чл. 65 ал. 6 и признак на КОДА.
 *
 * <p><b>⚠️ И КОНТРОЛЕН СВИДЕТЕЛ, ЗАЩОТО ЧЕТИРИ ЗЕЛЕНИ РЕДА НЕ РАЗЛИЧАВАТ ПОПРАВКАТА ОТ „ВИНАГИ
 * FALSE“.</b> Код 808 е по чл. 37 ал. 1 т. 8 — съседна точка на СЪЩАТА алинея, която обаче попада в
 * чл. 65 ал. 1 и се удържа от платеца. Той минава по същия път и ТРЯБВА да излезе в колона 3.
 */
class BgArt65Para6WitnessTest {

  private static final String FILE = "bg_income_type.csv";

  /** Брутото на свидетеля — кръгло, за да се чете данъкът наум: 10 на сто са 100,00. */
  private static final BigDecimal GROSS = new BigDecimal("1000.00");

  private static final BigDecimal ZERO = new BigDecimal("0.00");

  private static final int TAX_YEAR = 2025;
  private static final int Q1 = 1;
  private static final LocalDate PAID = LocalDate.of(2025, 2, 10);

  // ═══ ЧЕТИРИТЕ КОДА ПО ЧЛ. 65 АЛ. 6 ══════════════════════════════════════════

  /**
   * Чл. 37 ал. 1 т. 10 — продажба на НЕДВИЖИМО имущество от чуждестранно физическо лице. Данъкът е
   * 10 на сто по чл. 46 ал. 1, тоест 100,00 при бруто 1000,00 — и точно тези 100,00 НЕ са на
   * платеца.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · 810 (чл. 37 ал. 1 т. 10) → данък 0, ред 3 остава 0")
  void immovablePropertySaleIsPaidByTheRecipient() {
    assertRecipientPays("810", "чл. 37, ал. 1, т. 10");
  }

  /** Чл. 37 ал. 1 т. 11 — лизинг с изрично предвидено прехвърляне на собствеността. */
  @Test
  @DisplayName("СВИДЕТЕЛ · 811 (чл. 37 ал. 1 т. 11) → данък 0, ред 3 остава 0")
  void leaseWithTransferOfTitleIsPaidByTheRecipient() {
    assertRecipientPays("811", "чл. 37, ал. 1, т. 11");
  }

  /** Чл. 37 ал. 1 т. 12 — продажба на акции, дялове и други финансови активи. */
  @Test
  @DisplayName("СВИДЕТЕЛ · 812 (чл. 37 ал. 1 т. 12) → данък 0, ред 3 остава 0")
  void financialAssetSaleIsPaidByTheRecipient() {
    assertRecipientPays("812", "чл. 37, ал. 1, т. 12");
  }

  /**
   * Чл. 38 ал. 5 — замяна на акции и дялове при преобразуване по глава деветнадесета, раздел ІІ от
   * ЗКПО.
   *
   * <p><b>⚠️ ТУК ЧЛ. 65 АЛ. 6 НЕ Е ЕДИНСТВЕНОТО ОСНОВАНИЕ.</b> Чл. 67 ал. 3 казва и КОГА: данъкът
   * „определен към момента на замяната по чл. 38, ал. 5, се внася от лицето, придобило дохода, в
   * срок до края на месеца, следващ тримесечието, през което е извършена ПОСЛЕДВАЩА ПРОДАЖБА ИЛИ
   * ЗАМЯНА“ — събитие, което може да е години след замяната и което платецът изобщо не вижда.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · 815 (чл. 38 ал. 5) → данък 0, ред 3 остава 0")
  void shareSwapOnReorganisationIsPaidByTheRecipient() {
    assertRecipientPays("815", "чл. 38, ал. 5");
  }

  // ═══ КОНТРОЛНИЯТ СВИДЕТЕЛ ═══════════════════════════════════════════════════

  /**
   * ОБРАТНАТА ПОЛОВИНА. Чл. 65 ал. 1, ДОСЛОВНО: „Данъкът по чл. 46 за доходите по чл. 37, ал. 1, т.
   * 1 - 9 и чл. 38, ал. 12 се удържа и внася от предприятието или от самоосигуряващото се лице -
   * платец на дохода“. Код 808 е т. 8 от същата алинея — съседна точка, ДРУГА хипотеза.
   */
  @Test
  @DisplayName("КОНТРОЛЕН · 808 (чл. 37 ал. 1 т. 8) НЕ е сменен → 100,00 в к.3, ред 3")
  void theNeighbouringPointStaysWithThePayer() {
    List<String> header = BgSeed.quotedHeader(FILE);
    String[] row = rowFor(header, "808");

    assertEquals("чл. 37, ал. 1, т. 8", BgSeed.col(header, row, "article"));
    assertEquals(
        "true",
        BgSeed.col(header, row, "withheldByPayer"),
        "чл. 65 ал. 1 възлага т. 1–9 на платеца — този код НЕ се пипа");

    BgIncomeTaxResult tax = resolve(header, row);
    assertEquals(new BigDecimal("100.00"), tax.taxAmount, "чл. 46 ал. 1 — 10 на сто върху 1000,00");
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, tax.taxKind);

    BgArt55Aggregator.Result out = aggregate(header, row, tax);
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(out.getRow3()),
        "окончателният данък по чл. 37 влиза в ред 3, колона 3 — това е НАШЕТО задължение");
    assertEquals(1, out.getRow3Rows());
    assertEquals(0, out.getNotWithheldCount());
  }

  /**
   * ⚠️ И ДИВИДЕНТЪТ ОСТАВА НА ПЛАТЕЦА — втори контролен, защото 814 е съседът на 815 по номер и
   * първият кандидат за „сменен наред“. Чл. 65 ал. 2: данъкът по чл. 38 ал. 2 „се удържа и внася от
   * предприятието - платец на дохода“. Ставката е 5 на сто по чл. 46 ал. 3 и редът отива в РЕД 2,
   * не в ред 3.
   */
  @Test
  @DisplayName("КОНТРОЛЕН · 814 (чл. 38 ал. 1) НЕ е сменен → 50,00 в к.3, ред 2")
  void dividendsStayWithThePayerAndLandOnRowTwo() {
    List<String> header = BgSeed.quotedHeader(FILE);
    String[] row = rowFor(header, "814");

    assertEquals("true", BgSeed.col(header, row, "withheldByPayer"), "чл. 65 ал. 2 — платецът");

    BgIncomeTaxResult tax = resolve(header, row);
    assertEquals(new BigDecimal("50.00"), tax.taxAmount, "чл. 46 ал. 3 — 5 на сто върху 1000,00");
    assertEquals(BgIncomeTaxResolver.TAX_FINAL, tax.taxKind);

    BgArt55Aggregator.Result out = aggregate(header, row, tax);
    assertEquals(0, new BigDecimal("50.00").compareTo(out.getRow2()), "дивидентите са РЕД 2");
    assertEquals(0, BigDecimal.ZERO.compareTo(out.getRow3()));
  }

  // ═══ ОБЩОТО ТВЪРДЕНИЕ ═══════════════════════════════════════════════════════

  /**
   * ⚠️ И НИТО ЕДИН ДРУГ КОД ОТ ГЛАВА ШЕСТА НЕ Е ЗАГУБИЛ ДАНЪКА СИ. Четирите свидетеля показват, че
   * поправката действа; този показва, че тя не е попаднала другаде — двайсет и двата кода с шаблон
   * C минават през резолвера и ТОЧНО четири излизат без данък.
   */
  @Test
  @DisplayName("SEED · точно четири кода от глава шеста излизат с taxKind = NONE")
  void exactlyFourChapterSixCodesAreNotWithheld() {
    List<String> header = BgSeed.quotedHeader(FILE);
    StringBuilder notWithheld = new StringBuilder();
    int seen = 0;

    for (String[] row : BgSeed.quotedRows(FILE)) {
      if (!BgIncomeTaxResolver.PATTERN_FINAL.equals(BgSeed.col(header, row, "taxPattern"))) {
        continue;
      }
      seen++;
      if (BgIncomeTaxResolver.TAX_NONE.equals(resolve(header, row).taxKind)) {
        notWithheld
            .append(notWithheld.length() == 0 ? "" : ",")
            .append(BgSeed.col(header, row, "code"));
      }
    }

    assertEquals(22, seen, "кодове с шаблон C");
    assertEquals("810,811,812,815", notWithheld.toString(), "кодовете по чл. 65 ал. 6");
  }

  // ═══ СРЕДАТА ════════════════════════════════════════════════════════════════

  /**
   * ЕДИН СВИДЕТЕЛ, ТРИ ТВЪРДЕНИЯ: каталогът носи „не се удържа“, резолверът връща нула данък и
   * {@code NONE}, а агрегаторът НЕ слага реда в колона 3 — а го БРОИ.
   *
   * <p>⚠️ Броенето е част от твърдението, не украса. Ред, който изчезва тихо, изглежда еднакво с
   * ред, който изобщо не е стигнал до регистъра — а човекът, който подписва образец 4001, трябва да
   * види, че сумата съществува и е чужда.
   */
  private static void assertRecipientPays(String code, String article) {
    List<String> header = BgSeed.quotedHeader(FILE);
    String[] row = rowFor(header, code);

    assertEquals(article, BgSeed.col(header, row, "article"), "код " + code + ": разпоредба");
    assertEquals(
        "false",
        BgSeed.col(header, row, "withheldByPayer"),
        "код " + code + ": чл. 65 ал. 6 — данъкът се внася от лицето, придобило дохода");
    assertEquals(
        "10.00",
        BgSeed.col(header, row, "finalTaxRate"),
        "код " + code + ": чл. 46 ал. 1 — данъкът СЕ ДЪЛЖИ, просто не от платеца");

    BgIncomeTaxResult tax = resolve(header, row);
    assertTrue(tax.isResolved(), "редът СЕ ЗАПИСВА — чл. 73 ал. 1 иска брутото независимо от това");
    assertEquals(GROSS, tax.taxableBase, "основата е брутото и се показва за сведение");
    assertEquals(ZERO, tax.taxAmount, "платецът не удържа нищо");
    assertEquals(BgIncomeTaxResolver.TAX_NONE, tax.taxKind);
    assertNotNull(tax.note);

    BgArt55Aggregator.Result out = aggregate(header, row, tax);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getRow3()),
        "данъкът е в колона 4 на НЕГОВАТА декларация по чл. 67, не в нашата колона 3");
    assertEquals(0, out.getRow3Rows(), "ред 3 няма зад себе си нито един ред на регистъра");
    assertEquals(1, out.getPeriodRows(), "редът Е в тримесечието — не е изпаднал по друга причина");
    assertEquals(1, out.getNotWithheldCount(), "редът се БРОИ, а не изчезва тихо");
    assertEquals(
        0,
        out.getRecipientPaidFinalCount(),
        "броячът по чл. 65 ал. 6 е за редове, СТИГНАЛИ до ред 3 — този не стига дотам");
    assertTrue(out.isEmpty(), "Част ІV няма какво да декларира за този ред");
  }

  /**
   * Редът на каталога, минат през СЪЩИЯ резолвер, който вика и {@code
   * BgIndividualIncomePaymentService}.
   */
  private static BgIncomeTaxResult resolve(List<String> header, String[] row) {
    return BgIncomeTaxResolver.resolve(
        new BgIncomeTaxRequest(
            BgSeed.col(header, row, "taxPattern"),
            new BigDecimal(BgSeed.col(header, row, "expenseRate")),
            new BigDecimal(BgSeed.col(header, row, "finalTaxRate")),
            new BigDecimal(BgSeed.col(header, row, "advanceTaxRate")),
            Boolean.parseBoolean(BgSeed.col(header, row, "withheldByPayer")),
            GROSS,
            BigDecimal.ZERO,
            false));
  }

  /** Редът на регистъра, какъвто услугата го оставя след сметката — и трите реда на Част ІV. */
  private static BgArt55Aggregator.Result aggregate(
      List<String> header, String[] row, BgIncomeTaxResult tax) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(BgSeed.col(header, row, "code"));
    type.setArticle(BgSeed.col(header, row, "article"));
    type.setTaxPattern(BgSeed.col(header, row, "taxPattern"));
    type.setDeclarations(BgSeed.col(header, row, "declarations"));

    BgIndividualIncomePayment payment = new BgIndividualIncomePayment();
    payment.setIncomeType(type);
    payment.setPaymentDate(PAID);
    payment.setGrossAmount(GROSS);
    payment.setInsuranceAmount(BigDecimal.ZERO);
    payment.setTaxableBase(tax.taxableBase);
    payment.setTaxAmount(tax.taxAmount);
    payment.setTaxKind(tax.taxKind);

    return BgArt55Aggregator.aggregate(Collections.singletonList(payment), TAX_YEAR, Q1);
  }

  /** Редът за кода — и то единственият; вторият би направил избора мълчалив. */
  private static String[] rowFor(List<String> header, String code) {
    String[] found = null;
    int seen = 0;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      if (code.equals(BgSeed.col(header, row, "code"))) {
        found = row;
        seen++;
      }
    }
    assertEquals(1, seen, "код " + code + ": очаквах точно един ред в каталога");
    return found;
  }
}
