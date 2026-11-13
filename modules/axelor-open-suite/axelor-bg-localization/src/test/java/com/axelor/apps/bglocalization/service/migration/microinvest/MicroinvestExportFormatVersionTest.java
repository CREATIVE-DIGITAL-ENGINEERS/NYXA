package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЪВМЕСТИМОСТТА НА ФОРМАТА НА ИЗНОСА — версия 3 добавя, не чупи.
 *
 * <p>Версия 3 (27.09.2026 г.) внася {@code exportFormatVersion}, {@code baseSalaryFormulaId} и три
 * полета на {@code payrolls[]}: {@code baseSalaryHistory}, {@code baseSalaryCurrent} и {@code
 * nkpdCodes}. Всичко останало стои където си беше.
 *
 * <p><b>⚠️ ДВЕТЕ ПОСОКИ СЕ ПАЗЯТ ПООТДЕЛНО.</b> Стар файл в НОВ четец трябва да се разбере и да
 * даде празни списъци (а не {@code null} и {@code NullPointerException} насред вноса); нов файл в
 * СТАР четец се пази от {@code FAIL_ON_UNKNOWN_PROPERTIES = false} на самия парсър. Първото е
 * проверимо тук; второто е свойство на {@link MicroinvestExportFileParser} и се твърди от теста за
 * непознатото поле.
 */
class MicroinvestExportFormatVersionTest {

  private static final String V2_FILE =
      "{"
          + "\"company\":{\"name\":\"Т\",\"bulstat\":\"100000026\"},"
          + "\"employees\":[{\"employeeId\":17,\"name\":\"X\",\"isDeleted\":false,"
          + "  \"payrolls\":[{\"payrollId\":1022,\"employeeId\":17,\"contractId\":1,"
          + "                 \"isDeleted\":false}]}]"
          + "}";

  private static final String V3_FILE =
      "{"
          + "\"exportFormatVersion\":3,"
          + "\"baseSalaryFormulaId\":65,"
          + "\"company\":{\"name\":\"Т\",\"bulstat\":\"100000026\"},"
          + "\"employees\":[{\"employeeId\":17,\"name\":\"X\",\"isDeleted\":false,"
          + "  \"payrolls\":[{\"payrollId\":1022,\"employeeId\":17,\"contractId\":1,"
          + "                 \"isDeleted\":false,"
          + "                 \"baseSalaryCurrent\":465.150000,"
          + "                 \"baseSalaryHistory\":[{\"date\":\"2026-07-01\",\"value\":465.150000}],"
          + "                 \"nkpdCodes\":[{\"payrollKpdId\":2156,\"kpdId\":130837,"
          + "                                 \"kpdVersionId\":22,\"section\":\"9112\","
          + "                                 \"code\":\"0004\",\"fullCode\":\"91120004\","
          + "                                 \"description\":\"Чистач/ Хигиенист\"}]}]}]"
          + "}";

  @Test
  @DisplayName("файл от версия 2 се чете от новия четец и дава ПРАЗНИ списъци, не null")
  void version2FileStillParses() throws Exception {
    MicroinvestSourceModels.ExportRoot root =
        MicroinvestExportFileParser.parse(V2_FILE.getBytes(StandardCharsets.UTF_8));

    assertEquals(0, root.exportFormatVersion, "липсващото поле значи „версия 2 или по-стара“");
    assertNull(root.baseSalaryFormulaId);

    MicroinvestSourceModels.Payroll p = root.employees.get(0).payrolls.get(0);
    assertNotNull(p.baseSalaryHistory, "празен списък, НЕ null — иначе вносът гърми по средата");
    assertTrue(p.baseSalaryHistory.isEmpty());
    assertNotNull(p.nkpdCodes);
    assertTrue(p.nkpdCodes.isEmpty());
    assertNull(p.baseSalaryCurrent);
  }

  @Test
  @DisplayName("файл от версия 3 носи заплатата и НКПД с точната си стойност")
  void version3FileCarriesTheNewFields() throws Exception {
    MicroinvestSourceModels.ExportRoot root =
        MicroinvestExportFileParser.parse(V3_FILE.getBytes(StandardCharsets.UTF_8));

    assertEquals(3, root.exportFormatVersion);
    assertEquals(Integer.valueOf(65), root.baseSalaryFormulaId);

    MicroinvestSourceModels.Payroll p = root.employees.get(0).payrolls.get(0);
    assertEquals(1, p.baseSalaryHistory.size());
    assertEquals(LocalDate.parse("2026-07-01"), p.baseSalaryHistory.get(0).date);
    assertEquals(0, new BigDecimal("465.15").compareTo(p.baseSalaryHistory.get(0).value));
    assertEquals(0, new BigDecimal("465.15").compareTo(p.baseSalaryCurrent));

    assertEquals(1, p.nkpdCodes.size());
    MicroinvestSourceModels.NkpdAssignment a = p.nkpdCodes.get(0);
    assertEquals(22, a.kpdVersionId);
    assertEquals("9112", a.section);
    assertEquals("0004", a.code);
    assertEquals("91120004", a.fullCode);
    assertEquals("91120004", MicroinvestContractPayrollFields.normalizeNkpd(a));
  }

  @Test
  @DisplayName("непознато поле не чупи вноса — стар четец приема по-нов файл")
  void unknownFieldsAreIgnored() throws Exception {
    String withFuture =
        "{\"exportFormatVersion\":4,\"somethingFromTheFuture\":{\"a\":1},"
            + "\"company\":{\"name\":\"Т\",\"bulstat\":\"100000026\"},\"employees\":[]}";
    MicroinvestSourceModels.ExportRoot root =
        MicroinvestExportFileParser.parse(withFuture.getBytes(StandardCharsets.UTF_8));
    assertEquals(4, root.exportFormatVersion);
    assertEquals("100000026", root.company.bulstat);
  }

  /**
   * ⚠️ СРЕЩУ ИСТИНСКИЯ ИЗНОС, когато го има. Синтетичният JSON по-горе пази ФОРМАТА; този тест
   * пази, че истински изнесеното дружество наистина носи трите нови полета — тоест че скриптът и
   * моделът не са се разминали. Пропуска се, когато файлът не е под ръка (той е голям и не се
   * комитва).
   */
  @Test
  @DisplayName("истинският износ носи версия 3, заплати и НКПД")
  void realExportCarriesVersion3() throws Exception {
    Path file = realExportPath();
    if (file == null) {
      com.axelor.apps.bglocalization.testsupport.BgSkip.because(
          "няма локален износ (генерира се с инструмента за износ от Microinvest)");
      return;
    }
    MicroinvestSourceModels.ExportRoot root =
        MicroinvestExportFileParser.parse(Files.readAllBytes(file));

    assertEquals(5, root.exportFormatVersion);
    assertEquals(Integer.valueOf(65), root.baseSalaryFormulaId, "„Основно възнаграждение“");
    assertEquals(Integer.valueOf(75), root.hoursPerDayFormulaId, "„Работно време в часове на ден“");
    assertEquals(Integer.valueOf(240), root.legalHoursPerDayFormulaId, "законоустановени часове");
    assertEquals(Integer.valueOf(44), root.laborCategoryFormulaId, "„Категория труд“");

    int salaryRows = 0;
    int nkpdRows = 0;
    int deleted = 0;
    int hoursRows = 0;
    int categoryRows = 0;
    for (MicroinvestSourceModels.Employee e : root.employees) {
      for (MicroinvestSourceModels.Payroll p : e.payrolls) {
        salaryRows += p.baseSalaryHistory.size();
        nkpdRows += p.nkpdCodes.size();
        hoursRows += p.hoursPerDayHistory.size();
        categoryRows += p.laborCategoryHistory.size();
        if (p.isDeleted) {
          deleted++;
        }
      }
    }
    assertEquals(755, salaryRows, "редове с договорна заплата");
    assertEquals(94, nkpdRows, "присвоени НКПД кодове");
    assertEquals(854, hoursRows, "редове с договорени часове на ден");
    assertEquals(854, categoryRows, "редове с категория труд");
    assertEquals(1, deleted, "точно един заличен Payroll (1019)");
  }

  private static Path realExportPath() {
    String env = System.getenv("BG_MICROINVEST_EXPORT");
    if (env != null && !env.isEmpty() && Files.exists(Paths.get(env))) {
      return Paths.get(env);
    }
    return null;
  }
}
