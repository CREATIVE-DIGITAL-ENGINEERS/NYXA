package com.axelor.apps.bglocalization.service.setup;

import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.books;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.compute;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.eq;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.posted;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.report;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.importFromTemplate;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.newCompany;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.packageLikeDataInit;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountingReportConfigLine;
import com.axelor.apps.account.service.custom.AccountingReportValueMoveLineService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.accounting.BgAccountingReportValueMoveLineServiceImpl;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ДВЕ ДРУЖЕСТВА В ЕДНА БАЗА → ВСЯКО ВИЖДА В БАЛАНСА (28) И ОПР (27) САМО СВОИТЕ СУМИ (ДЕФЕКТ Д16).
 *
 * <p>Дружествата са внесени от един шаблон BG_COA и имат РАЗЛИЧНИ статии. Свидетелите:
 *
 * <ul>
 *   <li><b>числата</b> — и двете дружества дават своите числа, второто не е нули, а първото не
 *       получава нищо от второто;
 *   <li><b>вносът (А)</b> — {@code accountSet} на редовете на всяко дружество сочи само негови
 *       сметки ({@code BgImportAccountingReportConfigLine});
 *   <li><b>заварена база (Б)</b> — редовете на второто дружество сочат сметките на първото, както
 *       ги свързваше старият внос; справката пак дава своите числа ({@code
 *       BgAccountingReportValueMoveLineServiceImpl}, сечение по код);
 *   <li><b>наборът от сметки</b> — сметките, по които справката смята всеки ред, са само на
 *       дружеството на справката. Филтърът по дружество в заявката за статиите не се брои: без
 *       филтъра в набора числата остават верни, а тази проверка пада.
 * </ul>
 *
 * <p>Отделен клас: integrationTest върви с forkEvery = 1, тоест класът има своя база и не зависи от
 * {@link BgCoaReportLinesIntegrationTest} и {@link BgCoaReportColumnsIntegrationTest}.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCoaReportTwoCompaniesIntegrationTest extends JpaSupport {

  private final BgCoaReportColumnsIntegrationTest setup = new BgCoaReportColumnsIntegrationTest();

  /** Шаблонът и първата двойка — общи за класа; никой тест не пипа техните редове. */
  private static Long templateId;

  private static Long first;
  private static Long second;

  @BeforeEach
  void prepare() throws Exception {
    setup.applicationLocale();
    setup.hsqldbArrayToString();
    if (templateId == null) {
      templateId = packageLikeDataInit();
    }
    if (first == null) {
      first = company("DUA");
      second = company("DUB");
      postFirst(first);
      postSecond(second);
    }
  }

  @AfterEach
  void logout() {
    setup.logout();
  }

  @Test
  @DisplayName("Две дружества от един шаблон: баланс и ОПР на всяко — само неговите суми")
  void две_дружества_всяко_вижда_своите_суми() throws Exception {
    assertFirstNumbers(first);
    assertSecondNumbers(second);
  }

  @Test
  @DisplayName("Вносът свързва сметките на редовете със сметките на своето дружество")
  void вносът_свързва_сметките_на_своето_дружество() {
    assertImportBoundOwnAccounts(first);
    assertImportBoundOwnAccounts(second);
  }

  @Test
  @DisplayName("Справката смята всеки ред само по сметки на своето дружество")
  void наборът_от_сметки_е_само_на_дружеството_на_справката() {
    assertReportAccountsOwn(first);
    assertReportAccountsOwn(second);
  }

  @Test
  @DisplayName("Заварена база: редовете сочат сметките на друго дружество — числата пак са свои")
  void заварена_база_редовете_сочат_чужди_сметки() throws Exception {
    Long older = company("DUC");
    Long younger = company("DUD");
    postFirst(older);
    postSecond(younger);
    pointLinesTo(younger, older);

    assertSecondNumbers(younger);
    assertFirstNumbers(older);
    assertReportAccountsOwn(younger);
  }

  // --- данни ------------------------------------------------------------------------------------

  private static Long company(String tag) throws Exception {
    Long id = tx(() -> newCompany(tag).getId());
    importFromTemplate(id, templateId);
    JPA.em().clear();
    return id;
  }

  /** Първото дружество: числата на {@link BgCoaReportLinesIntegrationTest}. */
  private static void postFirst(Long companyId) {
    Long[] books = books(companyId);
    posted(companyId, books, "2026-02-01", "503", 600, 0, "101", 0, 600);
    posted(companyId, books, "2026-02-01", "503", 100, 0, "104", 0, 100);
    posted(companyId, books, "2026-02-01", "503", 300, 0, "114", 0, 300);
    posted(companyId, books, "2026-03-01", "494", 50, 0, "115", 0, 50);
    posted(companyId, books, "2026-04-01", "691", 20, 0, "243", 0, 20);
    posted(companyId, books, "2026-05-01", "503", 80, 0, "791", 0, 80);
  }

  /** Второто дружество: други суми по същите сметки. */
  private static void postSecond(Long companyId) {
    Long[] books = books(companyId);
    posted(companyId, books, "2026-02-01", "503", 1000, 0, "101", 0, 1000);
    posted(companyId, books, "2026-04-01", "691", 30, 0, "243", 0, 30);
    posted(companyId, books, "2026-05-01", "503", 150, 0, "791", 0, 150);
  }

  private static void assertFirstNumbers(Long companyId) throws Exception {
    login(companyId);
    // Актив 503 1080 + 494 50 − 243 20 = 1110; пасив 101 600 + 104 100 + 114 300 + 115 50 + 60.
    Map<String, BigDecimal> bs = compute(report(companyId, "28"));
    eq("700", bs, "P_ST1_1");
    eq("300", bs, "P_ST1_2");
    eq("-20", bs, "A_ST1_2");
    eq("1110", bs, "TOT_ASSETS");
    eq("0", bs, "CTRL");
    Map<String, BigDecimal> pl = compute(report(companyId, "27"));
    eq("80", pl, "I_ST1_2");
    eq("20", pl, "E_ST1_7");
    eq("60", pl, "NET");
  }

  private static void assertSecondNumbers(Long companyId) throws Exception {
    login(companyId);
    // Актив 503 1150 − 243 30 = 1120; пасив 101 1000 + резултат 120 = 1120.
    Map<String, BigDecimal> bs = compute(report(companyId, "28"));
    eq("1000", bs, "P_ST1_1");
    eq("0", bs, "P_ST1_2");
    eq("-30", bs, "A_ST1_2");
    eq("1120", bs, "TOT_ASSETS");
    eq("0", bs, "CTRL");
    Map<String, BigDecimal> pl = compute(report(companyId, "27"));
    eq("150", pl, "I_ST1_2");
    eq("30", pl, "E_ST1_7");
    eq("120", pl, "NET");
  }

  /** Изход преди всеки вход: влязъл потребител с „accounting.write“ не може да създаде друг. */
  private static void login(Long companyId) {
    BgRoleLogin.logout();
    JPA.em().clear();
    BgRoleLogin.login(JPA.em().find(Company.class, companyId), "accounting.write");
  }

  /**
   * Както старият внос: всеки ред на {@code younger} сочи сметката със същия код в {@code older}.
   */
  private static void pointLinesTo(Long younger, Long older) {
    tx(
        () -> {
          int moved = 0;
          for (AccountingReportConfigLine line : linesOf(younger)) {
            Set<Account> own = line.getAccountSet();
            if (own == null || own.isEmpty()) {
              continue;
            }
            Set<Account> foreign = new HashSet<>();
            for (Account account : own) {
              Account other =
                  JPA.all(Account.class)
                      .filter("self.company.id = ?1 AND self.code = ?2", older, account.getCode())
                      .fetchOne();
              assertTrue(other != null, "сметка " + account.getCode() + " липсва в първото");
              foreign.add(other);
            }
            line.setAccountSet(foreign);
            JPA.save(line);
            moved++;
          }
          assertTrue(moved > 100, "пренасочени редове: " + moved);
          return moved;
        });
    JPA.em().clear();
  }

  private static List<AccountingReportConfigLine> linesOf(Long companyId) {
    String code = JPA.em().find(Company.class, companyId).getCode();
    return JPA.all(AccountingReportConfigLine.class)
        .filter("self.importId LIKE ?1", code + "-%")
        .fetch();
  }

  // --- проверки на набора ---------------------------------------------------------------------

  /** Свидетел А: след вноса всяка сметка в {@code accountSet} на редовете е на дружеството. */
  private static void assertImportBoundOwnAccounts(Long companyId) {
    JPA.em().clear();
    int bound = 0;
    List<String> foreign = new ArrayList<>();
    for (AccountingReportConfigLine line : linesOf(companyId)) {
      for (Account account : line.getAccountSet()) {
        bound++;
        if (!companyId.equals(account.getCompany().getId())) {
          foreign.add(line.getImportId() + " → " + account.getCode());
        }
      }
    }
    assertTrue(bound > 300, "свързани сметки: " + bound);
    assertEquals(
        List.of(),
        foreign.stream().limit(10).collect(Collectors.toList()),
        "редове със сметки на друго дружество (общо " + foreign.size() + ")");
  }

  /**
   * Сметките, по които справките 27 и 28 смятат всеки ред, са само на дружеството на справката — и
   * поне един ред ги има (иначе проверката би минала и при нули).
   */
  private static void assertReportAccountsOwn(Long companyId) {
    JPA.em().clear();
    AccountingReportValueMoveLineService service =
        Beans.get(AccountingReportValueMoveLineService.class);
    assertTrue(
        service instanceof BgAccountingReportValueMoveLineServiceImpl,
        "изчислението не е BG: " + service.getClass().getName());
    BgAccountingReportValueMoveLineServiceImpl bg =
        (BgAccountingReportValueMoveLineServiceImpl) service;
    Company company = JPA.em().find(Company.class, companyId);
    Set<Company> companySet = Set.of(company);
    int withAccounts = 0;
    List<String> foreign = new ArrayList<>();
    for (String type : List.of("27", "28")) {
      List<AccountingReportConfigLine> lines =
          JPA.all(AccountingReportConfigLine.class)
              .filter(
                  "self.reportType.importId = ?1 AND self.accountCode IS NOT NULL",
                  company.getCode() + "-" + type)
              .fetch();
      assertTrue(lines.size() > 20, type + ": редове с код на сметка " + lines.size());
      for (AccountingReportConfigLine line : lines) {
        Set<Account> accounts = bg.getReportAccountSet(line, companySet);
        if (!accounts.isEmpty()) {
          withAccounts++;
        }
        for (Account account : accounts) {
          if (!companyId.equals(account.getCompany().getId())) {
            foreign.add(type + "/" + line.getCode() + " → " + account.getCode());
          }
        }
      }
    }
    assertTrue(withAccounts > 20, "редове със сметки: " + withAccounts);
    assertEquals(
        List.of(),
        foreign.stream().limit(10).collect(Collectors.toList()),
        "справката смята по сметки на друго дружество (общо " + foreign.size() + ")");
  }
}
