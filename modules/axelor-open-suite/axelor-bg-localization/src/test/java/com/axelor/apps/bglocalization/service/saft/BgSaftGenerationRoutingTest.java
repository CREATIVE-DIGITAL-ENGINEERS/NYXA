package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МАРШРУТЪТ ПО ВИДА (T1), без база: месечният отчет отива в {@link BgSaftAuditFileService},
 * годишният и при поискване — в {@link BgSaftAnnualOnDemandService}. Двете услуги са подменени със
 * свидетели; интеграционният тест на екрана подменя целия {@code generate()} и маршрута не вижда.
 */
class BgSaftGenerationRoutingTest {

  private static final BgSaftAuditFileService.Result MONTHLY =
      BgSaftAuditFileService.Result.of(null, List.of(), "monthly");
  private static final BgSaftAuditFileService.Result OTHER =
      BgSaftAuditFileService.Result.of(null, List.of(), "other");

  private static final BgSaftGenerationService SERVICE =
      new BgSaftGenerationService(
          new BgSaftAuditFileService(null, null, null, null, null, null, null, null) {
            @Override
            public Result assemble(
                SaftReport report, LocalDate createdOn, LocalDateTime lastGenerated) {
              return MONTHLY;
            }
          },
          new BgSaftAnnualOnDemandService(null, null) {
            @Override
            public BgSaftAuditFileService.Result assemble(SaftReport report, LocalDate createdOn) {
              return OTHER;
            }
          });

  private static BgSaftAuditFileService.Result routed(String type) {
    SaftReport report = new SaftReport();
    report.setReportTypeSelect(type);
    return SERVICE.check(report, LocalDate.of(2027, 1, 15), null);
  }

  @Test
  @DisplayName("Месечният — към месечното сглобяване; годишният и при поискване — към новото")
  void маршрутът() {
    assertSame(MONTHLY, routed(SaftReportRepository.REPORT_TYPE_MONTHLY));
    assertSame(OTHER, routed(SaftReportRepository.REPORT_TYPE_ANNUAL));
    assertSame(OTHER, routed(SaftReportRepository.REPORT_TYPE_ON_DEMAND));
  }
}
