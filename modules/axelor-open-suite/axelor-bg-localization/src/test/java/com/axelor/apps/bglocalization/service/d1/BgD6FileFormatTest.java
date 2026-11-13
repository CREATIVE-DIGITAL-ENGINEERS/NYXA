package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЕДЪТ НА Д6 ПО ПОДРЕДБАТА НА MICROINVEST — 6 заглавни + 4 блока × 13 + източник = 59 полета.
 *
 * <p>Всяко правило на формата има свой свидетел: празните суми, броят полета, данъкът в блок 2 с
 * вид 8, месецът на блок 2 при вид 6, вид 7 като единственото изключение и отказът без вид плащане.
 * Целият файл, байт по байт, е в {@code BgMicroinvestEtalonFileTest}.
 */
class BgD6FileFormatTest {

  private static final BgD6FileAdapter ADAPTER = new BgD6FileAdapter();

  private static final String SOURCE_FLAG = "876543215";

  /** Позициите на реда, броени от нула. */
  private static final int BLOCK_1 = 6;

  private static final int BLOCK_2 = BLOCK_1 + BgD6FileAdapter.BLOCK_WIDTH;

  /** Отместванията вътре в блока. */
  private static final int MONTH = 0;

  private static final int YEAR = 1;
  private static final int DATE = 2;
  private static final int KIND = 4;
  private static final int DOO = 5;
  private static final int UPF = 7;
  private static final int HEALTH = 9;
  private static final int TAX = 11;
  private static final int ADVANCE_TAX = 12;

  private static BgD6Declaration declaration(String kind, LocalDate paid) {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 9, 1));
    period.setToDate(LocalDate.of(2026, 9, 30));

    BgD6Declaration d = new BgD6Declaration();
    d.setBgPeriod(period);
    d.setT1ObligedPersonCode("123456786");
    d.setT2Name("Тестова фирма ЕООД");
    d.setT3InsuranceFundMember("0");
    d.setT81PaymentKind(kind);
    d.setT18PaymentDate(paid);
    d.setT10Doo(new BigDecimal("793.00"));
    d.setT12Upf(new BigDecimal("75.00"));
    d.setT14Health(new BigDecimal("280.00"));
    return d;
  }

  private static List<String> fields(BgD6Declaration d) {
    return Arrays.asList(ADAPTER.toLine(d, SOURCE_FLAG).split(",", -1));
  }

  // ═══ ПРАЗНИТЕ СУМИ ═══

  @Test
  @DisplayName("⚠️ празната и нулевата сума е НИЩО между запетаите — не „0“ и не „0.00“")
  void emptySumsAreNothingBetweenTheCommas() {
    BgD6Declaration d = declaration("5", LocalDate.of(2026, 9, 30));
    d.setT11TeacherPf(BigDecimal.ZERO); // нула
    d.setT13Ppf(null); //                 липса
    d.setT15Gvrs(new BigDecimal("0.00")); // ГВРС 2026 — нула по закон

    List<String> f = fields(d);

    assertEquals("793.00", f.get(BLOCK_1 + DOO));
    assertEquals("", f.get(BLOCK_1 + DOO + 1), "УчПФ = 0 → празно");
    assertEquals("75.00", f.get(BLOCK_1 + UPF));
    assertEquals("", f.get(BLOCK_1 + UPF + 1), "ППФ = null → празно");
    assertEquals("280.00", f.get(BLOCK_1 + HEALTH));
    assertEquals("", f.get(BLOCK_1 + HEALTH + 1), "ГВРС = 0.00 → празно");
    for (int i = BLOCK_1 + DOO; i < BLOCK_1 + BgD6FileAdapter.BLOCK_WIDTH; i++) {
      assertTrue(
          !"0".equals(f.get(i)) && !"0.00".equals(f.get(i)),
          "поле "
              + (i + 1)
              + " е „"
              + f.get(i)
              + "“ — празните суми на Д6 са празни, за"
              + " разлика от Д1 („0.00“). Така ги носят подадените файлове на Microinvest.");
    }
  }

  // ═══ БРОЯТ ПОЛЕТА ═══

  @Test
  @DisplayName("59 полета — със и без данък, и при празни блокове 3 и 4")
  void theLineHasFiftyNineFields() {
    BgD6Declaration withTax = declaration("5", LocalDate.of(2026, 9, 30));
    withTax.setT16Tax(new BigDecimal("301.77"));
    BgD6Declaration withoutTax = declaration("5", LocalDate.of(2026, 9, 30));

    assertEquals(59, BgD6FileAdapter.FIELD_COUNT);
    assertEquals(59, fields(withTax).size(), "с данък: " + fields(withTax));
    assertEquals(59, fields(withoutTax).size(), "без данък: " + fields(withoutTax));
    assertEquals(
        "\"" + SOURCE_FLAG + "\"",
        fields(withTax).get(58),
        "поле 59 — източникът в кавички, същият като поле 53 на Д1");
    assertEquals(
        Collections.nCopies(2 * BgD6FileAdapter.BLOCK_WIDTH, ""),
        fields(withTax).subList(BLOCK_2 + BgD6FileAdapter.BLOCK_WIDTH, 58),
        "блокове 3 и 4 — изцяло празни, включително месецът и датата");
  }

  @Test
  @DisplayName("заглавието: текстовите в кавички, празните „\"\"“; в блоковете нищо не е в кавички")
  void theHeaderIsQuotedAndTheBlocksAreNot() {
    List<String> f = fields(declaration("5", LocalDate.of(2026, 9, 30)));

    assertEquals(
        Arrays.asList("\"123456786\"", "\"ТЕСТОВА ФИРМА ЕООД\"", "0", "\"\"", "\"\"", "\"\""),
        f.subList(0, 6));
    assertEquals(
        Arrays.asList("09", "2026", "30/09/2026", "0", "5"),
        f.subList(BLOCK_1, BLOCK_1 + 5),
        "месец с водеща нула, датата дд/мм/гггг БЕЗ кавички");
  }

  // ═══ ДАНЪКЪТ В БЛОК 2 ═══

  @Test
  @DisplayName("⚠️ ДДФЛ е в блок 2 с вид 8 (поле 31), а не в поле 18 на блок 1")
  void theTaxGoesToBlockTwoAsKindEight() {
    BgD6Declaration d = declaration("5", LocalDate.of(2026, 9, 30));
    d.setT16Tax(new BigDecimal("301.77"));

    List<String> f = fields(d);

    assertEquals("", f.get(BLOCK_1 + TAX), "поле 18 — празно: блок 1 е за вноските");
    assertEquals("8", f.get(BLOCK_2 + KIND), "поле 24 — вид 8, сам, без избор");
    assertEquals("301.77", f.get(30), "поле 31 — данъкът");
    assertEquals(
        Arrays.asList("09", "2026", "30/09/2026", "0"),
        f.subList(BLOCK_2, BLOCK_2 + 4),
        "при вид 5 блок 2 повтаря месеца, годината, датата и флага на блок 1");
    for (int i = BLOCK_2 + DOO; i < BLOCK_2 + TAX; i++) {
      assertEquals("", f.get(i), "поле " + (i + 1) + " — вноските не се повтарят в блок 2");
    }
  }

  @Test
  @DisplayName("без данък блок 2 е празен като 3 и 4")
  void withoutTaxBlockTwoIsEmpty() {
    List<String> f = fields(declaration("5", LocalDate.of(2026, 9, 30)));

    assertEquals(
        Collections.nCopies(BgD6FileAdapter.BLOCK_WIDTH, ""),
        f.subList(BLOCK_2, BLOCK_2 + BgD6FileAdapter.BLOCK_WIDTH));
  }

  @Test
  @DisplayName("авансовият данък по т. 17 също е в блок 2 (поле 32)")
  void theAdvanceTaxIsInBlockTwoToo() {
    BgD6Declaration d = declaration("5", LocalDate.of(2026, 9, 30));
    d.setT17AdvanceTax(new BigDecimal("12.50"));

    List<String> f = fields(d);

    assertEquals("8", f.get(BLOCK_2 + KIND));
    assertEquals("", f.get(BLOCK_2 + TAX));
    assertEquals("12.50", f.get(BLOCK_2 + ADVANCE_TAX));
    assertEquals("", f.get(BLOCK_1 + ADVANCE_TAX));
  }

  @Test
  @DisplayName("⚠️ вид 6: месецът на блок 2 е месецът на ИЗПЛАЩАНЕ, датите — датата на изплащане")
  void kindSixPutsTheTaxInThePaymentMonth() {
    BgD6Declaration d = declaration("6", LocalDate.of(2026, 10, 28));
    d.setT16Tax(new BigDecimal("301.77"));

    List<String> f = fields(d);

    assertEquals(
        Arrays.asList("09", "2026", "28/10/2026", "0", "6"),
        f.subList(BLOCK_1, BLOCK_1 + 5),
        "блок 1 — месецът на ВНОСКИТЕ, датата на изплащане");
    assertEquals(
        Arrays.asList("10", "2026", "28/10/2026", "0", "8"),
        f.subList(BLOCK_2, BLOCK_2 + 5),
        "блок 2 — месецът на изплащане");
  }

  @Test
  @DisplayName("вид 6 през годината: декември, изплатен през януари → блок 2 е 01 на новата година")
  void kindSixCrossesTheYear() {
    BgD6Declaration d = declaration("6", LocalDate.of(2027, 1, 20));
    d.getBgPeriod().setFromDate(LocalDate.of(2026, 12, 1));
    d.setT16Tax(new BigDecimal("10.00"));

    List<String> f = fields(d);

    assertEquals(Arrays.asList("12", "2026"), f.subList(BLOCK_1, BLOCK_1 + 2));
    assertEquals(Arrays.asList("01", "2027"), f.subList(BLOCK_2, BLOCK_2 + 2));
  }

  @Test
  @DisplayName("⚠️ вид 7 (гъби и плодове): данъкът по чл. 41а остава в блок 1, блок 2 е празен")
  void kindSevenKeepsTheTaxInBlockOne() {
    BgSelfInsuredRegistration forest = new BgSelfInsuredRegistration();
    forest.setBgInsuredTypeCode("41");
    BgD6Declaration d = declaration(BgD6Point.PAYMENT_KIND_ART41A_FOREST, null);
    d.setBgSelfInsuredRegistration(forest);
    d.setT16Tax(new BigDecimal("15.00"));

    List<String> f = fields(d);

    assertEquals("7", f.get(BLOCK_1 + KIND));
    assertEquals("15.00", f.get(BLOCK_1 + TAX), "указание т. 8 „в“: един вид за вноските и данъка");
    assertEquals(
        Collections.nCopies(BgD6FileAdapter.BLOCK_WIDTH, ""),
        f.subList(BLOCK_2, BLOCK_2 + BgD6FileAdapter.BLOCK_WIDTH));
  }

  // ═══ ОТКАЗЪТ БЕЗ ВИД ПЛАЩАНЕ ═══

  @Test
  @DisplayName("⚠️ без вид плащане 5/6 — отказ с подсказката, а не „0“ във файла")
  void withoutPaymentKindTheFileIsRefused() {
    for (String kind : Arrays.asList(null, "", "8", "9", "7")) {
      BgD6Declaration d = declaration(kind, LocalDate.of(2026, 9, 30));
      String refusal = BgD6Emitter.paymentKindRefusal(d);
      assertNotNull(refusal, "вид „" + kind + "“ за вноските — отказ");
      assertTrue(refusal.contains(BgD6Point.PAYMENT_KIND_HINT), refusal);
      IllegalStateException e =
          assertThrows(IllegalStateException.class, () -> ADAPTER.toLine(d, SOURCE_FLAG));
      assertEquals(refusal, e.getMessage(), "файлът отказва със същия текст");
    }
    assertNull(BgD6Emitter.paymentKindRefusal(declaration("5", null)), "5 без дата — приема се");
    assertNull(
        BgD6Emitter.paymentKindRefusal(declaration("6", LocalDate.of(2026, 10, 28))),
        "6 с дата — приема се");
  }

  @Test
  @DisplayName("вид 6 без дата на изплащане — отказ: блок 2 няма от къде да вземе месеца си")
  void kindSixWithoutThePaymentDateIsRefused() {
    String refusal = BgD6Emitter.paymentKindRefusal(declaration("6", null));
    assertNotNull(refusal);
    assertTrue(refusal.contains("т. 18"), refusal);
  }

  @Test
  @DisplayName("подсказката на т. 8.1 е СЪЩИЯТ текст като отказа")
  void theFieldHelpIsTheRefusalHint() throws IOException {
    String domain =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/domains/BgD6Declaration.xml")),
            StandardCharsets.UTF_8);
    int field = domain.indexOf("name=\"t81PaymentKind\"");
    String tag = domain.substring(field, domain.indexOf("/>", field));
    assertTrue(
        tag.contains("help=\"" + BgD6Point.PAYMENT_KIND_HINT + "\""),
        "помощта на т. 8.1 се е разминала с текста на отказа: " + tag);
  }
}
