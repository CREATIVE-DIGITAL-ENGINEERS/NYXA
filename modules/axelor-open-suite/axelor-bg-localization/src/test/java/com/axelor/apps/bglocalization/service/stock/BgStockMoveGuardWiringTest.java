package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.module.BgStockWiringTestModule;
import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.service.StockMoveService;
import com.axelor.apps.supplychain.service.StockMoveServiceSupplychainImpl;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Прихващането на складовите движения ДЕЙСТВА в обекта, който Guice строи от истинските модули —
 * stock → supplychain → BgLocalizationModule. Отказът и реализирането минават през {@code
 * StockMoveService}, както ги вика екранът.
 *
 * <p>Движенията тук не се записват: правилото отказва ПРЕДИ Axelor да пипне каквото и да е, тоест
 * отказаното движение остава непроменено.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgStockWiringTestModule.class)
@Tag("integration")
class BgStockMoveGuardWiringTest extends JpaSupport {

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  /** Дружество с отворена фискална 2026 г. и затворен първи период. */
  private static Company company(String tag) {
    return tx(
        () -> {
          if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne()
              == null) {
            JPA.save(new com.axelor.studio.db.AppBase());
          }
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("G" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("Склад " + tag);
          company.setCode("G-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);

          Year year = new Year();
          year.setName("2026 " + tag);
          year.setCode("FY-" + tag);
          year.setCompany(company);
          year.setTypeSelect(1); // TYPE_FISCAL
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setStatusSelect(1);
          year = JPA.save(year);
          period(year, "01", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 2); // затворен
          period(year, "02-12", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31), 1);
          return company;
        });
  }

  private static void period(Year year, String name, LocalDate from, LocalDate to, int status) {
    Period p = new Period();
    p.setName(name + " " + year.getCode());
    p.setYear(year);
    p.setFromDate(from);
    p.setToDate(to);
    p.setStatusSelect(status);
    JPA.save(p);
  }

  private static StockLocation location(Company company, int type) {
    StockLocation l = new StockLocation();
    l.setCompany(company);
    l.setTypeSelect(type);
    l.setName(type == 3 ? "Виртуална" : "Склад");
    return l;
  }

  private static StockMove move(Company company, int status, int fromType, int toType) {
    StockMove m = new StockMove();
    m.setCompany(company);
    m.setStatusSelect(status);
    m.setFromStockLocation(location(company, fromType));
    m.setToStockLocation(location(company, toType));
    return m;
  }

  @Test
  @DisplayName("Guice строи услугата през прихващането — наследник на supplychain, усилен от Guice")
  void услугата_е_прихваната() {
    StockMoveService service = Beans.get(StockMoveService.class);
    assertTrue(service instanceof StockMoveServiceSupplychainImpl, service.getClass().getName());
    assertNotEquals(
        StockMoveServiceSupplychainImpl.class,
        service.getClass(),
        "Guice трябва да е подкласирал услугата заради прихващането");
  }

  @Test
  @DisplayName("отказът на реализирано движение през StockMoveService се отказва; статусът остава")
  void отказът_се_отказва() {
    Company company = company("CNL");
    StockMove realized = move(company, 3, 1, 3);
    realized.setRealDate(LocalDate.of(2026, 3, 15));
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).cancel(realized));
    assertEquals(BgStockMoveGuard.CANCEL_REFUSAL, e.getMessage());
    assertEquals(3, realized.getStatusSelect(), "статусът не е пипнат");
    assertEquals(LocalDate.of(2026, 3, 15), realized.getRealDate(), "датата не е презаписана");
  }

  @Test
  @DisplayName("реализиране с дата на документа в бъдещето — отказ")
  void бъдещата_дата_се_отказва() {
    Company company = company("FUT");
    StockMove planned = move(company, 2, 3, 1);
    planned.setBgDocumentDate(LocalDate.now().plusDays(10));
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
    assertTrue(e.getMessage().contains("бъдещето"), e.getMessage());
    assertEquals(2, planned.getStatusSelect());
  }

  @Test
  @DisplayName("реализиране с дата в затворен период — отказ")
  void затвореният_период_се_отказва() {
    Company company = company("CLS");
    StockMove planned = move(company, 2, 3, 1);
    planned.setBgDocumentDate(LocalDate.of(2026, 1, 15));
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
    assertTrue(e.getMessage().contains("затворен"), e.getMessage());
  }

  @Test
  @DisplayName("ИЗП: дата в период „временно затворен“ или „в затваряне“ — отказ, и за двата")
  void периодът_в_затваряне_се_отказва() {
    int[] statuses = {
      PeriodRepository.STATUS_TEMPORARILY_CLOSED, PeriodRepository.STATUS_CLOSURE_IN_PROGRESS
    };
    for (int status : statuses) {
      Company company = company("CL" + status);
      tx(
          () -> {
            Period open =
                JPA.all(Period.class)
                    .filter(
                        "self.year.company = ?1 AND self.fromDate = ?2",
                        company,
                        LocalDate.of(2026, 2, 1))
                    .fetchOne();
            open.setStatusSelect(status);
            return JPA.save(open);
          });
      StockMove planned = move(company, 2, 3, 1);
      planned.setBgDocumentDate(LocalDate.of(2026, 3, 15));
      AxelorException e =
          assertThrows(
              AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
      assertTrue(
          e.getMessage().contains("в ход на счетоводно затваряне"), status + ": " + e.getMessage());
      assertEquals(2, planned.getStatusSelect(), "движението остава планирано");
    }
  }

  @Test
  @DisplayName("реализиране с дата без фискален период — отказ")
  void липсващият_период_се_отказва() {
    Company company = company("MIS");
    StockMove planned = move(company, 2, 3, 1);
    planned.setBgDocumentDate(LocalDate.of(2025, 6, 30));
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
    assertTrue(e.getMessage().contains("няма фискален период"), e.getMessage());
  }

  @Test
  @DisplayName("„Липса с виновно лице“ без лице — реализирането се отказва")
  void липса_без_лице() {
    Company company = company("PRT");
    StockMove planned = move(company, 2, 1, 3); // от склада към инвентаризацията — изход
    planned.setBgDocumentDate(LocalDate.of(2026, 3, 1));
    StockCorrectionReason shortage = new StockCorrectionReason();
    shortage.setName("Липса с виновно лице");
    shortage.setBgStockMovementCode("120");
    shortage.setBgRequiresPartner(true);
    planned.setStockCorrectionReason(shortage);
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
    assertTrue(e.getMessage().contains("виновно лице"), e.getMessage());
  }

  @Test
  @DisplayName("обработчик, закачен от друг модул през Multibinder, стига до прихващането (ИЗП)")
  void обработчикът_на_изп() {
    Injector injector = Beans.get(Injector.class);
    Set<BgStockMoveRealizedHandler> handlers = injector.getInstance(BgStockMoveGuard.HANDLERS);
    BgStockWiringTestModule.RecordingRealizedHandler recording =
        injector.getInstance(BgStockWiringTestModule.RecordingRealizedHandler.class);
    assertTrue(handlers.contains(recording), "множеството от BgLocalizationModule + закаченият");
    StockMove realized = move(company("HND"), 3, 1, 3);
    BgStockMoveGuard.afterRealize(realized, handlers);
    assertTrue(recording.seen.contains(realized));
  }

  @Test
  @DisplayName("корекция „Брак“ (160) като вход — отказ по посоката на кода")
  void посоката_на_причината() {
    Company company = company("DIR");
    StockMove planned = move(company, 2, 3, 1); // от инвентаризацията към склада — вход
    planned.setBgDocumentDate(LocalDate.of(2026, 3, 1));
    StockCorrectionReason scrap = new StockCorrectionReason();
    scrap.setName("Брак");
    scrap.setBgStockMovementCode("160");
    planned.setStockCorrectionReason(scrap);
    AxelorException e =
        assertThrows(
            AxelorException.class, () -> Beans.get(StockMoveService.class).realize(planned));
    assertTrue(
        e.getMessage().contains("„160“") && e.getMessage().contains("изход"), e.getMessage());
  }
}
