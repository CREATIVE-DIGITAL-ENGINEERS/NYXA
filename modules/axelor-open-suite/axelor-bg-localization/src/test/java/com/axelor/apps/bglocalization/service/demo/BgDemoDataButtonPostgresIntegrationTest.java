package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgDemoDataController;
import com.axelor.db.JPA;
import com.axelor.db.tenants.TenantAware;
import com.axelor.mail.db.MailMessage;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

/**
 * БУТОНЪТ „НАЛЕЙ ДЕМО ДАННИ“ ПО ПЪТЯ НА ЕКРАНА — контролерът и фоновата нишка на {@code
 * ControllerCallableTool}, върху своя PostgreSQL база ({@link BgDemoPostgresModule}).
 *
 * <p>Без обхват на заявка наливането пада във фоновата нишка с {@code OutOfScopeException} за
 * {@code PaymentModeServiceImpl} ({@code @RequestScoped}), без нищо записано и без дума към
 * потребителя. {@link BgDemoDataPostgresIntegrationTest} не го вижда: вика услугата направо в
 * тестовата нишка, а {@code GuiceExtension} отваря обхват на заявка около всеки тестов метод — с
 * истинския {@code ServletScopes.REQUEST}, но само в ТАЗИ нишка. Тук тестовата нишка играе HTTP
 * нишката (тя има обхват), а наливането върви в нишката на бутона, която няма.
 *
 * <p>Неприхванатото изключение на фоновата нишка се събира и проваля теста с него; после — данните
 * (партньори, фактури, ДДС декларации 01–10) и известието „готово“ във „Входящи“. Пуска се ръчно
 * срещу СВОЯ база ({@code -Ddemo.db.url/username/password}, {@code -Ddemo.db.ddl=update}); без
 * адрес се пропуска (CI няма база).
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgDemoDataButtonPostgresIntegrationTest {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void theTradeButtonLoadsInItsBackgroundThreadAndTellsTheUser() throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    String tag = Long.toString(System.currentTimeMillis() % 100000);
    Long companyId = BgDemoInstallation.newCompany("Бутон демо " + tag, "DB" + tag);
    BgDemoInstallation.loginAdmin(companyId);

    List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
    ActionResponse response = new ActionResponse();
    try {
      Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
      Map<String, Object> context = new HashMap<>();
      context.put("_model", Company.class.getName());
      context.put("id", companyId);
      context.put("_signal", "bgDemoTradeBtn");
      ActionRequest request = new ActionRequest();
      request.setModel(Company.class.getName());
      request.setData(Map.of("context", context));
      new BgDemoDataController().load(request, response);
      // Бутонът се връща след processTimeout секунди; наливането продължава в нишката си.
      for (Thread t : Thread.getAllStackTraces().keySet()) {
        if (t instanceof TenantAware && !before.contains(t)) {
          t.join(TimeUnit.HOURS.toMillis(3));
        }
      }
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
    if (!uncaught.isEmpty()) {
      throw new AssertionFailedError(
          "фоновата нишка на бутона падна: " + uncaught.get(0), uncaught.get(0));
    }
    assertNull(BgDemoForm.item(response).get("error"), "грешка на екрана: " + response.getData());

    JPA.em().clear();
    assertEquals(
        List.of(BgDemoBackgroundRun.SUBJECT_DONE),
        JPA
            .all(MailMessage.class)
            .filter(
                "self.relatedModel = ?1 AND self.relatedId = ?2 AND self.subject LIKE 'Демо данни%'",
                Company.class.getName(), companyId)
            .fetch()
            .stream()
            .map(MailMessage::getSubject)
            .collect(java.util.stream.Collectors.toList()),
        "известието във „Входящи“");

    long partners = JPA.all(Partner.class).filter("self.company.id = ?1", companyId).count();
    assertTrue(partners > 1, "партньори на дружеството: " + partners);
    long invoices =
        JPA.all(Invoice.class)
            .filter(
                "self.company.id = ?1 AND self.statusSelect = ?2",
                companyId,
                InvoiceRepository.STATUS_VENTILATED)
            .count();
    assertTrue(invoices > 0, "осчетоводени фактури: " + invoices);
    int year = LocalDate.now().getYear();
    for (int m = 1; m <= BgDemoDates.LAST_MONTH.getValue(); m++) {
      String period = String.format("%04d%02d", year, m);
      long generated =
          JPA.all(BgVatReturn.class)
              .filter(
                  "self.company.id = ?1 AND self.period = ?2 AND self.statusSelect = 'generated'",
                  companyId,
                  period)
              .count();
      assertEquals(1, generated, "ДДС декларация за " + period);
    }
  }
}
