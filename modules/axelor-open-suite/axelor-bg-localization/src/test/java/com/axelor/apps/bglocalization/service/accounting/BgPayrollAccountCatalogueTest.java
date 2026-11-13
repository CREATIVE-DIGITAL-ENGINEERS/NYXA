package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПОСТИНГ МОДЕЛЪТ СРЕЩУ ДВА ЗАВАРЕНИ ФАЙЛА: сметкоплана BG_COA и речника с фондовете.
 *
 * <p><b>⚠️ ЧЕТЕ СМЕТКОПЛАНА ОТ ДРУГ МОДУЛ, И ТОВА Е СЪЩЕСТВЕНОТО.</b> {@code
 * l10n_bgr/BG_COA/account_account.csv} живее в {@code axelor-account}, не тук — тоест този тест
 * пази граница, която компилаторът не вижда: заплатите ползват сметкоплан, който НЕ им принадлежи и
 * се мени от друг екип. Изтрит или преименуван код там днес се обажда ТУК, а не при първото
 * изплащане на жива база, където изходът е „статия не се генерира“ без причина.
 *
 * <p>⚠️ Пътят е относителен, както при {@link BgSeed}: тестовете на модула се пускат с работна
 * директория самия модул.
 */
class BgPayrollAccountCatalogueTest {

  private static final Path COA =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/account_account.csv");

  private static final Path JOURNALS =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/account_journal.csv");

  /**
   * ВСИЧКИ кодове, които постинг моделът може да поиска.
   *
   * <p>⚠️ Изброени тук, а не извлечени с рефлексия от {@link BgPayrollAccount}: рефлексията щеше да
   * пази „каквото е там“ срещу „каквото е там“, тоест нищо. Нов код в модела трябва да мине и през
   * този списък — това е второто мнение.
   */
  private static final List<String> USED_ACCOUNTS =
      Arrays.asList(
          "304", "401", "40901", "604", "6041", "6042", "6043", "6051", "6052", "6053", "6054",
          "6055", "6056", "6094", "709", "4211", "4212", "4213", "4214", "4541", "4542", "4551",
          "4552", "4553", "4554", "4555", "4556");

  /**
   * Фондовете БЕЗ сметка — обявени поименно, за да не се сметне липсата за пропуск.
   *
   * <p>⚠️ Учителският пенсионен фонд е ЕДИНСТВЕНИЯТ и това чака счетоводно потвърждение. Появи ли
   * се сметка за него в BG_COA, тестът отдолу пада и казва, че списъкът е остарял — точно обратното
   * на тихото попадение в ДОО.
   */
  private static final Set<String> KNOWN_UNMAPPED_FUNDS =
      new LinkedHashSet<>(Arrays.asList("TEACHER_PF"));

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · всяка сметка от модела съществува в BG_COA")
  void everyAccountInTheModelExistsInTheChart() throws IOException {
    Map<String, String[]> chart = chart();
    List<String> missing = new ArrayList<>();
    for (String code : USED_ACCOUNTS) {
      if (!chart.containsKey(code)) {
        missing.add(code);
      }
    }
    assertEquals(List.of(), missing, "кодове, които моделът иска, а сметкопланът няма");
  }

  // ── 2 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("2 · журнал PAY приема типа на всяка сметка от модела")
  void thePayrollJournalAcceptsEveryAccountType() throws IOException {
    Map<String, String[]> chart = chart();
    Set<String> validTypes = payJournalValidAccountTypes();
    assertTrue(validTypes.size() > 0, "журнал PAY трябва да изброява позволени типове сметки");

    List<String> rejected = new ArrayList<>();
    for (String code : USED_ACCOUNTS) {
      String[] row = chart.get(code);
      assertNotNull(row, "липсва сметка " + code);
      String type = row[1];
      if (!validTypes.contains(type)) {
        rejected.add(code + " (тип " + type + ")");
      }
    }
    // ⚠️ MoveLineControlServiceImpl#controlAccountingAccount отказва ред със сметка от непозволен
    // тип. Проверено тук, разминаването се вижда без база; открито там, то е грешка при запис.
    assertEquals(List.of(), rejected, "сметки, които журнал PAY би отказал");
  }

  // ── 3 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("3 · разходните сметки са РАЗХОД, а разчетните са ЗАДЪЛЖЕНИЕ")
  void expenseAccountsAreExpensesAndSettlementAccountsAreLiabilities() throws IOException {
    Map<String, String[]> chart = chart();
    for (String code : USED_ACCOUNTS) {
      String type = chart.get(code)[1];
      if (code.startsWith("30")) {
        // ⚠️ 3 = currentAsset. ЕДИНСТВЕНИЯТ материален запас в набора — 304 „Стоки“, насрещникът
        // на предадената в натура стока. Точно заради него журнал PAY получи и тип 3.
        assertEquals("3", type, "сметка " + code + " трябва да е материален запас");
      } else if (code.startsWith("40")) {
        // 10 = payable. Насрещниците на натурата към трети лица: 401 доставчици, 40901 ваучери.
        assertEquals("10", type, "сметка " + code + " трябва да е задължение към доставчик");
      } else if (code.startsWith("60")) {
        // 13 = charge (Expense)
        assertEquals("13", type, "сметка " + code + " трябва да е разходна");
      } else if (code.startsWith("70")) {
        // ⚠️ 12 = income (Revenue). ЕДИНСТВЕНАТА приходна сметка в набора и единствената причина
        // журнал PAY да получи тип 12 — обезщетението ОТ лицето е приход, не отрицателен разход.
        assertEquals("12", type, "сметка " + code + " трябва да е приходна");
      } else {
        // 9 = debt (Liability)
        assertEquals("9", type, "сметка " + code + " трябва да е разчетна");
      }
    }
  }

  // ── 4 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4 · всеки фонд от речника има сметка, освен обявените изключения")
  void everyFundInTheSeedIsEitherMappedOrDeclaredUnmapped() {
    List<String> header = BgSeed.quotedHeader("bg_contribution_fund.csv");
    Set<String> unmapped = new TreeSet<>();
    int seen = 0;
    for (String[] row : BgSeed.quotedRows("bg_contribution_fund.csv")) {
      String code = BgSeed.col(header, row, "code");
      seen++;
      if (BgPayrollAccount.liabilityForFund(code) == null) {
        unmapped.add(code);
      }
    }
    assertEquals(10, seen, "речникът с фондовете има десет реда");
    assertEquals(
        new TreeSet<>(KNOWN_UNMAPPED_FUNDS),
        unmapped,
        "фонд без сметка, който не е обявен — или обявен, който вече има");
  }

  // ── 5 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5 · всеки окабелен фонд има И разчетна, И разходна сметка")
  void everyMappedFundHasBothSides() {
    for (String fund : BgPayrollAccount.mappedFunds().keySet()) {
      assertNotNull(BgPayrollAccount.liabilityForFund(fund), fund + " без разчетна сметка");
      assertNotNull(BgPayrollAccount.employerExpenseForFund(fund), fund + " без разходна сметка");
    }
  }

  // ── 6 · ИМЕТО НА СМЕТКАТА СРЕЩУ СЪДЪРЖАНИЕТО, КОЕТО МОДЕЛЪТ ПРАЩА В НЕЯ ──

  /**
   * ДУМАТА, КОЯТО ТРЯБВА ДА СТОИ В ИМЕТО НА РАЗЧЕТНАТА СМЕТКА НА ВСЕКИ ФОНД.
   *
   * <p><b>⚠️ ЗАЩО ТОЗИ ПАЗАЧ.</b> Тест 1 пита само „съществува ли кодът“ и минава зелен и когато
   * моделът праща вноската за Безработица в сметка, наречена „ДЗПО – професионален пенсионен фонд“:
   * кодът съществува, тоест нищо не се обажда. Такова разминаване не се вижда в статията — вижда се
   * чак когато счетоводителят сверява салдото с платежното нареждане, месеци по-късно. Тук то е
   * ЧЕРВЕНО при билда.
   *
   * <p>⚠️ По ДУМА, а не по цялото име: имената в BG_COA носят пояснения в скоби и кавички
   * („(НЗОК)“, „(държавно обществено осигуряване)“), а сверяването на цели низове би превърнало
   * пазача в препис на CSV-то. Думата е онова, което различава един фонд от друг.
   */
  private static final Map<String, String> FUND_NAME_MARKER = new LinkedHashMap<>();

  static {
    FUND_NAME_MARKER.put("PENSION", "ДОО");
    FUND_NAME_MARKER.put("PENSION_ART69", "ДОО");
    FUND_NAME_MARKER.put("OZM", "ДОО");
    FUND_NAME_MARKER.put("HEALTH", "здравно");
    FUND_NAME_MARKER.put("UPF", "ДЗПО");
    FUND_NAME_MARKER.put("PPF", "ДЗПО");
    FUND_NAME_MARKER.put("UNEMPLOYMENT", "Безработица");
    FUND_NAME_MARKER.put("TZPB", "Трудова злополука");
    FUND_NAME_MARKER.put("GVRS", "Гарантирани вземания");
  }

  @Test
  @DisplayName("6 · името на сметката по всеки фонд ОПИСВА фонда — и в разчета, и в разхода")
  void theAccountNameDescribesTheFundItReceives() throws IOException {
    Map<String, String> names = names();
    List<String> mismatched = new ArrayList<>();
    for (Map.Entry<String, String> entry : FUND_NAME_MARKER.entrySet()) {
      String fund = entry.getKey();
      String marker = entry.getValue();
      for (String code :
          Arrays.asList(
              BgPayrollAccount.liabilityForFund(fund),
              BgPayrollAccount.employerExpenseForFund(fund))) {
        assertNotNull(code, "фонд " + fund + " без сметка");
        String name = names.get(code);
        assertNotNull(name, "липсва сметка " + code);
        if (!name.contains(marker)) {
          mismatched.add(fund + " → " + code + " „" + name + "“ (търси се „" + marker + "“)");
        }
      }
    }
    assertEquals(
        List.of(),
        mismatched,
        "сметка, чието ИМЕ не описва фонда, който моделът праща в нея — салдото ѝ не се сверява с"
            + " платежното нареждане");
  }

  @Test
  @DisplayName("6а · УПФ и ППФ сочат ЕДНА двойка сметки, а не две")
  void bothSupplementaryPensionFundsShareOnePair() {
    assertEquals(
        BgPayrollAccount.liabilityForFund("UPF"),
        BgPayrollAccount.liabilityForFund("PPF"),
        "разчетът за ДЗПО е ЕДИН — разбивката УПФ/ППФ за Д6 се чете от перото");
    assertEquals(
        BgPayrollAccount.employerExpenseForFund("UPF"),
        BgPayrollAccount.employerExpenseForFund("PPF"));
    // ⚠️ И последната цифра на двете е една и съща — разчетът е ОГЛЕДАЛЕН на разхода. Два разчета
    // (напр. 4553/4554) срещу един разход 6053 нарушават това равенство.
    List<String> crooked = new ArrayList<>();
    for (String fund : BgPayrollAccount.mappedFunds().keySet()) {
      String liability = BgPayrollAccount.liabilityForFund(fund);
      String expense = BgPayrollAccount.employerExpenseForFund(fund);
      if (liability.charAt(liability.length() - 1) != expense.charAt(expense.length() - 1)) {
        crooked.add(fund + ": " + expense + " срещу " + liability);
      }
    }
    assertEquals(List.of(), crooked, "фонд, чийто разчет не е огледален на разхода си");
  }

  @Test
  @DisplayName("6б · 4557 НЕ съществува в BG_COA — ДЗПО е една сметка, седма няма")
  void theSeventhSettlementAccountIsGone() throws IOException {
    // ⚠️ ОБЯВЕНА ЛИПСА, А НЕ ПРОПУСК. ДЗПО заема една сметка, ГВРС стои на 4556 и седма сметка
    // няма какво да носи. Върне ли се 4557 в CSV-то, това е знак, че ДЗПО отново е разделено на
    // две сметки (4553 и 4554) — и тогава номерацията отгоре е наполовина.
    assertTrue(!chart().containsKey("4557"), "4557 не бива да се връща в сметкоплана");
  }

  @Test
  @DisplayName("6в · 6094 се КАЗВА „Социални разходи (СБКО)“ — името описва съдържанието")
  void theSocialExpenseAccountIsNamedAfterItsContent() throws IOException {
    // ⚠️ Класът 2xx има своя сметка, а не 609 „Други разходи“ — сметка, по-широка от
    // съдържанието си и в група „Други такси“. Проверката е същата по вид като тази за фондовете
    // отгоре: името трябва да описва онова, което моделът праща вътре.
    assertEquals(
        "Социални разходи (СБКО)",
        names().get(BgPayrollAccount.EXPENSE_SOCIAL),
        "6094 трябва да носи името на класа, който получава");
    assertEquals("6094", BgPayrollAccount.EXPENSE_SOCIAL, "класът 2xx НЕ отива вече в 609");
  }

  // ── 7 · насрещниците на натурата ────────────────────────────────────────

  /** Какво трябва да пише в името на сметката за всеки вид придобивка. */
  private static final Map<BgPayrollInKind.Kind, String> COUNTERPART_NAME_MARKER =
      new LinkedHashMap<>();

  static {
    COUNTERPART_NAME_MARKER.put(BgPayrollInKind.Kind.VOUCHER, "Ваучери");
    COUNTERPART_NAME_MARKER.put(BgPayrollInKind.Kind.GOODS, "Стоки");
    COUNTERPART_NAME_MARKER.put(BgPayrollInKind.Kind.THIRD_PARTY, "Доставчици");
  }

  @Test
  @DisplayName("7 · всеки вид натура сочи сметка, чието ИМЕ описва вида")
  void everyInKindCounterpartIsNamedAfterItsKind() throws IOException {
    Map<String, String> names = names();
    List<String> mismatched = new ArrayList<>();
    for (Map.Entry<BgPayrollInKind.Kind, String> entry : COUNTERPART_NAME_MARKER.entrySet()) {
      String code = entry.getKey().getAccount();
      assertNotNull(code, entry.getKey() + " без сметка");
      String name = names.get(code);
      assertNotNull(name, "липсва сметка " + code);
      if (!name.contains(entry.getValue())) {
        mismatched.add(
            entry.getKey()
                + " → "
                + code
                + " „"
                + name
                + "“ (търси се „"
                + entry.getValue()
                + "“)");
      }
    }
    assertEquals(
        List.of(),
        mismatched,
        "насрещник, чието име не описва вида придобивка, който моделът праща в него");
    // ⚠️ И „не е натура“ НЯМА сметка — това е класификация, не липса на такава.
    assertNull(
        BgPayrollInKind.Kind.CASH.getAccount(), "придобивката в пари не се приключва в натура");
  }

  @Test
  @DisplayName("7б · ваучерът сочи 40901, а СТАРИЯТ код 409 вече значи друго")
  void theVoucherCounterpartMovedToTheNraSubAccount() throws IOException {
    // ⚠️ ЗАКОВАНО ЧИСЛО, А НЕ ПРЕПИС ОТ КОНСТАНТАТА. Тестове 1 и 7 отгоре питат „съществува ли
    // кодът“ и „описва ли името вида“ — и двата минават зелени с кой да е код, чиято сметка се
    // казва „Ваучери за храна“. Тъкмо това е дупката, която привеждането към НАП отваря: 409 се
    // освобождава за „Други задължения към доставчици“, която в НАП е РОДИТЕЛ на групата. Върне ли
    // се константата на 409, моделът ще праща ваучерите в родителския възел — салдо, което не се
    // сверява с отчета на оператора, и грешка, видима чак при сверяване месеци по-късно.
    assertEquals(
        "40901", BgPayrollAccount.IN_KIND_VOUCHER, "ваучерите слязоха на 40901 под НАП 409");
    assertEquals(
        "40901",
        BgPayrollInKind.Kind.VOUCHER.getAccount(),
        "видът VOUCHER трябва да сочи същата сметка като константата");

    Map<String, String[]> chart = chart();
    assertTrue(chart.containsKey("40901"), "40901 липсва в BG_COA");

    // ⚠️ 409 е заета от НАП с „Други задължения към доставчици“, тъй че празнота не може да е
    // пазачът. Пази се РАЗЛИЧИЕТО: 409 съществува, но НЕ Е ваучерът, и името ѝ го доказва. Това е
    // същата дупка от обратната страна — върне ли се константата на 409, ваучерите ще се слеят с
    // чуждо задължение, а салдото няма да се сверява с отчета на оператора.
    assertTrue(chart.containsKey("409"), "409 липсва — НАП я дава на „Други задължения“");
    assertEquals(
        "10",
        chart.get("409")[1],
        "409 трябва да е задължение към доставчик (10) — това е НАП „Други задължения към"
            + " доставчици“, групата, в която ваучерът е само едно от перата");
    assertTrue(
        !"409".equals(BgPayrollAccount.IN_KIND_VOUCHER),
        "константата на ваучера се върна на 409. Двете сметки са РАЗЛИЧНИ и са братя под C010:"
            + " 409 събира всички други задължения към доставчици, а ваучерът иска собствено"
            + " салдо, което се сверява с отчета на оператора");
  }

  @Test
  @DisplayName("7а · всяко перо от класа 2xx е ИЛИ класифицирано по вид, ИЛИ обявено за неясно")
  void everySocialLineIsEitherClassifiedOrDeclaredUnclassified() {
    // ⚠️ ЧЕТЕ РЕЧНИКА, НЕ СПИСЪК В ТЕСТА, и точно това е второто мнение: ново перо в класа 2xx
    // трябва да мине през BgPayrollInKind, инак ще стигне до 630 без решен вид. Пази границата,
    // която компилаторът не вижда — перата идват от ДВА seed файла.
    Set<Integer> seen = new TreeSet<>();
    List<String> missing = new ArrayList<>();
    for (String file :
        Arrays.asList("bg_pay_slip_line_type.csv", "bg_pay_slip_line_type_social.csv")) {
      List<String> header = BgSeed.header(file);
      for (String[] row : BgSeed.rows(file)) {
        String number = BgSeed.col(header, row, "number");
        if (number == null || !number.startsWith("2")) {
          continue;
        }
        int line = Integer.parseInt(number.trim());
        seen.add(line);
        if (BgPayrollInKind.kindOf(line) == null
            && !BgPayrollInKind.declaredUnclassifiedLines().contains(line)) {
          missing.add(number + " (" + BgSeed.col(header, row, "name") + ")");
        }
      }
    }
    assertTrue(seen.size() >= 25, "речникът трябва да носи поне 25 пера от класа 2xx: " + seen);
    assertEquals(List.of(), missing, "перо от 2xx без решен и без обявено НЕрешен вид придобивка");
    // И обратната посока: обявеното за неясно трябва още да съществува в речника.
    Set<Integer> stale = new TreeSet<>(BgPayrollInKind.declaredUnclassifiedLines());
    stale.removeAll(seen);
    assertEquals(new TreeSet<>(), stale, "обявено за неясно перо, което вече го няма в речника");
    Set<Integer> ghosts = new TreeSet<>(BgPayrollInKind.classifiedLines());
    ghosts.removeAll(seen);
    assertEquals(new TreeSet<>(), ghosts, "класифицирано перо, което го няма в речника");
  }

  // ── 8 · вижда ли се сметката В СПРАВКИТЕ ────────────────────────────────

  private static final Path REPORT_CONFIG =
      Paths.get(
          "../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
              + "account_accountingReportConfigLine.csv");

  /**
   * СМЕТКИТЕ, КОИТО НЕ СЕ ЧЕТАТ В НИТО ЕДНА СПРАВКА — обявени, за да не се смятат за показани.
   *
   * <p><b>✅ ПРАЗЕН — И ТОВА Е ЦЕЛТА, НЕ ПРОПУСК.</b> Сметка от плана, която конфигът на справките
   * не познава, се смята вярно, без да се яви никъде. 40901 и 6094 имат свои детайлни редове и са
   * изброени в правилата на сборните линии, тъй че списъкът е празен.
   *
   * <p>Къде се явяват: 6094 (СБКО) е разход, тъй че се явява и в ДВАТА ОПР (справка 27 на реда
   * „Разходи за осигуровки“, справка 45 на „Разходи за персонала“), и в скрития сбор {@code R_EXP}
   * на ДВАТА баланса — последното е задължително, защото {@code R_PL = R_INC − R_EXP} дава
   * финансовия резултат и разход, забравен там, надценява печалбата. 40901 (ваучери) е задължение и
   * се явява само в двата баланса, при търговските задължения.
   *
   * <p>⚠️ РАВЕНСТВОТО ОТДОЛУ ОСТАВА. Празният списък не изключва пазача — той продължава да пада,
   * ако НОВА сметка от модела влезе в плана, без да стигне до справките. {@code
   * BgCoaReportCoverageTest} го пази и от страната на сметкоплана.
   */
  private static final Set<String> NOT_IN_ANY_REPORT = new LinkedHashSet<>();

  @Test
  @DisplayName("8 · всяка сметка от модела се чете в поне една справка")
  void everyAccountIsReadBySomeReport() throws IOException {
    if (!Files.exists(REPORT_CONFIG)) {
      fail("липсва конфигът на справките: " + REPORT_CONFIG.toAbsolutePath());
    }
    List<String> lines = Files.readAllLines(REPORT_CONFIG, StandardCharsets.UTF_8);
    List<String> header = split(lines.get(0));
    int codeAt = header.indexOf("accountCode");
    assertTrue(codeAt >= 0, "конфигът на справките трябва да носи колона accountCode");
    Set<String> referenced = new LinkedHashSet<>();
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> row = split(lines.get(i));
      if (row.size() > codeAt && !row.get(codeAt).trim().isEmpty()) {
        referenced.add(row.get(codeAt).trim());
      }
    }
    Set<String> invisible = new TreeSet<>();
    for (String code : USED_ACCOUNTS) {
      if (!referenced.contains(code)) {
        invisible.add(code);
      }
    }
    // ⚠️ РАВЕНСТВО, А НЕ „СЪДЪРЖА“: тестът пада и когато се появи невидима сметка, и когато
    // обявена такава БЪДЕ добавена в справките — второто е сигнал списъкът горе да се свие.
    assertEquals(
        new TreeSet<>(NOT_IN_ANY_REPORT),
        invisible,
        "сметка от модела, която не се чете в нито една справка и не е обявена като такава");
  }

  // ── четците ─────────────────────────────────────────────────────────────

  /** „код → име“ от сметкоплана. */
  private static Map<String, String> names() throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> lines = Files.readAllLines(COA, StandardCharsets.UTF_8);
    List<String> header = split(lines.get(0));
    int codeAt = header.indexOf("code");
    int nameAt = header.indexOf("name");
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> row = split(lines.get(i));
      out.put(row.get(codeAt), row.get(nameAt));
    }
    return out;
  }

  /** „код → ред“ от сметкоплана; колона 1 е accountType.importId. */
  private static Map<String, String[]> chart() throws IOException {
    if (!Files.exists(COA)) {
      fail("липсва сметкопланът: " + COA.toAbsolutePath());
    }
    Map<String, String[]> out = new LinkedHashMap<>();
    List<String> lines = Files.readAllLines(COA, StandardCharsets.UTF_8);
    List<String> header = split(lines.get(0));
    int codeAt = header.indexOf("code");
    int typeAt = header.indexOf("accountType.importId");
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> row = split(lines.get(i));
      out.put(row.get(codeAt), new String[] {row.get(codeAt), row.get(typeAt)});
    }
    return out;
  }

  /** Позволените типове сметки на журнал PAY — низът е „4|5|9|10|11|13“. */
  private static Set<String> payJournalValidAccountTypes() throws IOException {
    List<String> lines = Files.readAllLines(JOURNALS, StandardCharsets.UTF_8);
    List<String> header = split(lines.get(0));
    int codeAt = header.indexOf("code");
    int typesAt = header.indexOf("validAccountTypes");
    for (int i = 1; i < lines.size(); i++) {
      List<String> row = split(lines.get(i));
      if ("PAY".equals(row.get(codeAt))) {
        return new LinkedHashSet<>(Arrays.asList(row.get(typesAt).split("\\|")));
      }
    }
    fail("журнал PAY липсва в " + JOURNALS.toAbsolutePath());
    return null;
  }

  /** Цепи ред с кавички и точка и запетая — същият формат като останалите l10n CSV-та. */
  private static List<String> split(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (char c : line.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ';' && !quoted) {
        out.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    out.add(cell.toString());
    return out;
  }
}
