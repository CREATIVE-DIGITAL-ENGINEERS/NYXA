package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgEgnValidationService;
import com.axelor.apps.bglocalization.service.BgEikValidationService;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ДЕМО НАБОРЪТ СЕ ЧЕТЕ И Е ВЪТРЕШНО СВЪРЗАН — без база (върви в CI).
 *
 * <ul>
 *   <li>всеки файл се чете с точния брой колони;
 *   <li>документите сочат съществуващи партньори и стоки, плащанията — съществуващи документи;
 *   <li>идентификаторите са ИЗМИСЛЕНИ и ВАЛИДНИ: ЕИК 000000NNN (шест водещи нули — неприсвоими) с
 *       контролна цифра по Nyxa, ЕГН с контролна цифра, български ДДС номер = BG + ЕИК, чуждите — с
 *       код на държавата по VIES (Гърция EL), IBAN с код на банката DEMO и контролни цифри по ISO
 *       13616 (мод 97);
 *   <li>всяка встъпителна статия е балансирана във валутата си;
 *   <li>датите съществуват и във високосна, и в невисокосна година, и са в 01.01–31.10.
 * </ul>
 */
class BgDemoDataFilesTest {

  private static final Pattern FICTIONAL_EIK = Pattern.compile("000000\\d{3}");
  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{8,30}");

  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void everyFileIsReadableAndCoherent(BgDemoDataset ds) {
    for (String file :
        new String[] {"company", "banks", "partners", "products", "opening", "invoices"}) {
      assertFalse(BgDemoCsv.read(ds, file).isEmpty(), ds + ": липсва или е празен " + file);
    }
    Set<String> partners = codes(BgDemoCsv.read(ds, "partners"), "code");
    Set<String> products = codes(BgDemoCsv.read(ds, "products"), "code");
    Set<String> banks = codes(BgDemoCsv.read(ds, "banks"), "code");
    Set<String> docs = new HashSet<>();
    List<String> wrong = new ArrayList<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "invoices")) {
      docs.add(r.get("doc"));
      if (!partners.contains(r.get("partner"))) {
        wrong.add(r.where() + " партньор " + r.get("partner"));
      }
      if (!products.contains(r.get("product"))) {
        wrong.add(r.where() + " стока " + r.get("product"));
      }
      BgDemoInvoiceStep.operationType(r.get("kind"));
      if (r.has("refundOf") && !docs.contains(r.get("refundOf"))) {
        wrong.add(r.where() + " кредитното известие сочи по-късен или липсващ документ");
      }
    }
    Map<String, String> docDate = new HashMap<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "invoices")) {
      docDate.putIfAbsent(r.get("doc"), r.get("date"));
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "payments")) {
      if (docDate.containsKey(r.get("doc"))
          && r.get("date").compareTo(docDate.get(r.get("doc"))) < 0) {
        wrong.add(r.where() + " плащане преди документа " + r.get("doc"));
      }
      if (!docs.contains(r.get("doc"))) {
        wrong.add(r.where() + " плащане по липсващ документ " + r.get("doc"));
      }
      if (!"CASH".equals(r.get("bank")) && !banks.contains(r.get("bank"))) {
        wrong.add(r.where() + " банка " + r.get("bank"));
      }
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "opening")) {
      if (r.has("partner") && !partners.contains(r.get("partner"))) {
        wrong.add(r.where() + " партньор " + r.get("partner"));
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  /**
   * Служителите: ЕГН с вярна контролна цифра, чиято дата е рождената дата (за родените след 2000 —
   * месец +40), IBAN с код DEMO, код по НКПД от 8 цифри, заплата над нулата.
   */
  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void employeesAreFictionalAndValid(BgDemoDataset ds) {
    BgEgnValidationService egn = new BgEgnValidationService();
    List<String> wrong = new ArrayList<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "employees")) {
      String e = r.get("egn");
      if (!egn.validateEgn(e)) {
        wrong.add(r.where() + " ЕГН");
      }
      LocalDate born = LocalDate.parse(r.get("birthDate"));
      int month = Integer.parseInt(e.substring(2, 4));
      int year = Integer.parseInt(e.substring(0, 2)) + (month > 40 ? 2000 : 1900);
      LocalDate fromEgn =
          LocalDate.of(year, month > 40 ? month - 40 : month, Integer.parseInt(e.substring(4, 6)));
      if (!born.equals(fromEgn)) {
        wrong.add(r.where() + " рождената дата не е тази от ЕГН");
      }
      checkIban(r.get("iban"), r.where(), wrong);
      if (!r.get("nkpd").matches("\\d{8}")) {
        wrong.add(r.where() + " НКПД " + r.get("nkpd"));
      }
      if (r.decimal("baseSalary").signum() <= 0) {
        wrong.add(r.where() + " заплата");
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  /**
   * Извлеченията са по сметки от {@code banks.csv}, за месеците 01–10; преоценката — по 504 във
   * валута на сметка от набора, само към края на тримесечие.
   */
  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void statementsAndRevaluationPointAtTheBanks(BgDemoDataset ds) {
    Map<String, String> currencyOf = new HashMap<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "banks")) {
      currencyOf.put(r.get("code"), r.get("currency"));
    }
    List<String> wrong = new ArrayList<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "bank_statements")) {
      if (!currencyOf.containsKey(r.get("bank"))) {
        wrong.add(r.where() + " банка " + r.get("bank"));
      }
      if (r.integer("month") < 1 || r.integer("month") > BgDemoDates.LAST_MONTH.getValue()) {
        wrong.add(r.where() + " месец " + r.get("month"));
      }
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "revaluation")) {
      if (!r.get("account").startsWith("504")) {
        wrong.add(r.where() + " сметка " + r.get("account"));
      }
      if (!currencyOf.containsValue(r.get("currency")) || "EUR".equals(r.get("currency"))) {
        wrong.add(r.where() + " валута " + r.get("currency"));
      }
      if (!Set.of("03-31", "06-30", "09-30", "12-31").contains(r.get("date"))) {
        wrong.add(r.where() + " не е край на тримесечие: " + r.get("date"));
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  /**
   * Плащанията са с режим, който SAF-T описва: „Банков превод“ на набора или каса. „Друго
   * постъпление/плащане“ (INC_OTH/OUT_OTH) е извън Nom_PaymentMethod (SAFT_PAY_METHOD_EXCLUDED).
   */
  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void paymentModesAreDescribedBySaft(BgDemoDataset ds) {
    Set<String> allowed = new HashSet<>(List.of("INC_CSH", "OUT_CSH"));
    for (String[] m : BgDemoCompanyStep.BANK_TRANSFER_MODES) {
      allowed.add(m[0]);
    }
    List<String> wrong = new ArrayList<>();
    Map<String, String> currencyOf = new HashMap<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "banks")) {
      currencyOf.put(r.get("code"), r.get("currency"));
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "payments")) {
      if (!allowed.contains(r.get("mode"))) {
        wrong.add(r.where() + " режим " + r.get("mode"));
      }
      // Режимът „Банков превод“ според валутата на сметката: EUR → 503, USD → 504, CHF → 5042.
      String currency = currencyOf.get(r.get("bank")); // каса — няма сметка
      String suffix =
          currency == null
              ? null
              : Map.of("EUR", "_BT", "USD", "_BTV", "CHF", "_BTC").get(currency);
      if (r.get("mode").contains("_BT") && (suffix == null || !r.get("mode").endsWith(suffix))) {
        wrong.add(r.where() + " режим " + r.get("mode") + " за сметка " + r.get("bank"));
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  /**
   * Центровете: всяка аналитична сметка е на обявена ос, а кодовете, по които стъпката разпределя
   * редовете (Администрация/Продажби, Услуги/Стоки), са в набора.
   */
  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void analyticCentresAreComplete(BgDemoDataset ds) {
    List<BgDemoCsv.Row> rows = BgDemoCsv.read(ds, "analytics");
    if (rows.isEmpty()) {
      return;
    }
    Set<String> axes = new HashSet<>();
    Set<String> accounts = new HashSet<>();
    List<String> wrong = new ArrayList<>();
    for (BgDemoCsv.Row r : rows) {
      if ("AXIS".equals(r.get("kind"))) {
        axes.add(r.get("code"));
      }
    }
    for (BgDemoCsv.Row r : rows) {
      if ("ACCOUNT".equals(r.get("kind"))) {
        accounts.add(r.get("code"));
        if (!axes.contains(r.get("axis"))) {
          wrong.add(r.where() + " ос " + r.get("axis"));
        }
      } else if (!"AXIS".equals(r.get("kind"))) {
        wrong.add(r.where() + " вид " + r.get("kind"));
      }
    }
    for (String code : new String[] {"ADM", "SAL", "SRV", "GDS"}) {
      if (!accounts.contains(code)) {
        wrong.add("analytics: липсва " + code);
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void identifiersAreFictionalAndValid(BgDemoDataset ds) {
    BgEikValidationService eik = new BgEikValidationService();
    BgEgnValidationService egn = new BgEgnValidationService();
    List<String> wrong = new ArrayList<>();
    Map<String, String> company = BgDemoCompanyStep.keyValues(BgDemoCsv.read(ds, "company"));
    checkEik(company.get("eik"), company.get("vat"), "company", eik, wrong);
    if (company.containsKey("declarantEgn") && !egn.validateEgn(company.get("declarantEgn"))) {
      wrong.add("ЕГН на декларатора");
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "partners")) {
      if ("I".equals(r.get("type"))) {
        if (!egn.validateEgn(r.get("egn"))) {
          wrong.add(r.where() + " ЕГН");
        }
      } else if ("BG".equals(r.get("country"))) {
        checkEik(r.get("eik"), r.get("vat"), r.where(), eik, wrong);
      } else if (r.has("vat")) {
        String cc = BgVatNumberValidator.countryCode(r.get("vat"));
        if (!BgVatNumberValidator.isViesCountry(cc)) {
          wrong.add(r.where() + " ДДС номерът не е с код по VIES: " + r.get("vat"));
        }
      }
      if (r.has("iban")) {
        checkIban(r.get("iban"), r.where(), wrong);
      }
    }
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "banks")) {
      checkIban(r.get("iban"), r.where(), wrong);
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void openingMovesAreBalanced(BgDemoDataset ds) {
    Map<String, BigDecimal> balance = new HashMap<>();
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "opening")) {
      balance.merge(
          r.get("move"), r.decimal("debit").subtract(r.decimal("credit")), BigDecimal::add);
    }
    balance.forEach((k, v) -> assertEquals(0, v.signum(), ds + " статия " + k + ": " + v));
  }

  /**
   * Сметките, които наборът ползва изрично, са в BG_COA и са активни (неактивна не се
   * осчетоводява).
   */
  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void accountsAreActiveInBgCoa(BgDemoDataset ds) throws Exception {
    java.nio.file.Path coa =
        com.axelor.apps.bglocalization.testsupport.BgDomainColumns.modulesRoot()
            .resolve(
                "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/account_account.csv");
    Map<String, String> status = new HashMap<>();
    List<String> lines = java.nio.file.Files.readAllLines(coa);
    List<String> header = BgDemoCsv.split(lines.get(0));
    int code = header.indexOf("code");
    int st = header.indexOf("statusSelect");
    for (String line : lines.subList(1, lines.size())) {
      List<String> cells = BgDemoCsv.split(line);
      status.put(cells.get(code), cells.get(st));
    }
    List<String> wrong = new ArrayList<>();
    List<BgDemoCsv.Row> used = new ArrayList<>(BgDemoCsv.read(ds, "opening"));
    for (BgDemoCsv.Row r : BgDemoCsv.read(ds, "invoices")) {
      if (r.has("account")) {
        used.add(r);
      }
    }
    for (BgDemoCsv.Row r : used) {
      String a = r.get("account");
      if (!"1".equals(status.get(a))) {
        wrong.add(
            r.where() + " сметка " + a + ": " + (status.containsKey(a) ? "неактивна" : "няма я"));
      }
    }
    assertEquals(new ArrayList<String>(), wrong);
  }

  @ParameterizedTest
  @EnumSource(BgDemoDataset.class)
  void datesExistInEveryYearAndStayInThePeriod(BgDemoDataset ds) {
    for (int year : new int[] {2026, 2027, 2028}) {
      BgDemoDates dates = new BgDemoDates(year);
      for (String file : new String[] {"invoices", "payments"}) {
        for (BgDemoCsv.Row r : BgDemoCsv.read(ds, file)) {
          LocalDate d = dates.of(r.get("date"));
          assertTrue(
              !d.isBefore(dates.first()) && !d.isAfter(dates.last()),
              r.where() + " извън 01.01–31.10: " + d);
          if (r.has("taxEvent")) {
            dates.of(r.get("taxEvent"));
          }
        }
      }
    }
  }

  private static void checkEik(
      String eik, String vat, String where, BgEikValidationService v, List<String> wrong) {
    if (!FICTIONAL_EIK.matcher(eik).matches()) {
      wrong.add(where + " ЕИК не е от измисления обхват 000000NNN: " + eik);
    }
    if (!v.validateEik(eik)) {
      wrong.add(where + " ЕИК с грешна контролна цифра: " + eik);
    }
    if (!("BG" + eik).equals(vat)) {
      wrong.add(where + " ДДС номерът не е BG + ЕИК: " + vat);
    }
  }

  /** ISO 13616: буквите → числа (A=10…), първите 4 знака отзад, остатък по модул 97 = 1. */
  private static void checkIban(String iban, String where, List<String> wrong) {
    if (!IBAN.matcher(iban).matches()) {
      wrong.add(where + " IBAN формат: " + iban);
      return;
    }
    if (iban.startsWith("BG") && !iban.substring(4, 8).equals("DEMO")) {
      wrong.add(where + " българският IBAN не е с код на банката DEMO: " + iban);
    }
    String moved = iban.substring(4) + iban.substring(0, 4);
    StringBuilder digits = new StringBuilder();
    for (char c : moved.toCharArray()) {
      digits.append(Character.getNumericValue(c));
    }
    if (new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() != 1) {
      wrong.add(where + " IBAN контролни цифри: " + iban);
    }
  }

  private static Set<String> codes(List<BgDemoCsv.Row> rows, String col) {
    Set<String> out = new HashSet<>();
    rows.forEach(r -> out.add(r.get(col)));
    return out;
  }
}
