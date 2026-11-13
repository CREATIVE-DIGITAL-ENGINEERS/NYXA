package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.inject.Beans;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Производственото окабеляване (HSQLDB, истински Guice) с ЕДНА подмяна: услугата на фиша е {@link
 * FailingCalculation} — същият клас, на който генераторите пишат и могат да се провалят насред
 * пресмятането. {@code @Transactional} на {@code BgPaySlipService#calculate} се наследява
 * непокътнат, тоест прихващането е истинското.
 */
public class BgPaySlipCalculateTxTestModule extends AbstractModule {

  @Override
  protected void configure() {
    install(
        Modules.override(new BgLocalizationProductionWiringTestModule())
            .with(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    bind(BgPaySlipService.class).to(FailingCalculation.class);
                  }
                }));
  }

  /**
   * Генератор, който ПИШЕ ИЗВЪН ФИША — на мястото на редовете по т. 9 ({@code generateSickLeave} →
   * {@code BgSickLeaveService#writePeriodLines}), — и следващ генератор, който пада. Пресмятането
   * на екрана минава през {@code previewSickLeave}, който не пише. Останалите стъпки са изключени:
   * фикстурата няма договор.
   */
  public static class FailingCalculation extends BgPaySlipService {

    static final String MARKER = "TX_WITNESS_2A";

    static volatile boolean failAfterWrite;
    static volatile boolean failAtSave;
    static volatile boolean touchLines;

    /**
     * Колко пъти пресмятането е СТИГНАЛО до генераторите. Отказите на „Преизчисли“ — статус,
     * дружество, период — са ПРЕДИ пресмятането: провал след него се връща с rollback, но
     * генераторите вече са чели чуждите данни. Броячът не се връща с транзакцията.
     */
    static final AtomicInteger STARTED = new AtomicInteger();

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return null;
    }

    @Override
    protected void generateSickLeave(BgPaySlip slip) {
      STARTED.incrementAndGet();
      BgPayrollParameter written = new BgPayrollParameter();
      written.setCode(MARKER);
      written.setParamValue(BigDecimal.ONE);
      written.setUnit("EUR");
      written.setValidFrom(LocalDate.of(2026, 3, 1));
      Beans.get(BgPayrollParameterRepository.class).save(written);
    }

    @Override
    protected void previewSickLeave(BgPaySlip slip) {}

    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    @Override
    protected void generateEarnings(BgPaySlip slip) {
      if (touchLines) {
        for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
          line.setBgAmount(new BigDecimal("999.00"));
        }
      }
    }

    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    @Override
    protected void generateContributions(BgPaySlip slip) {}

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {
      if (failAfterWrite) {
        throw new IllegalStateException("провал по средата на пресмятането");
      }
    }

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    /** Провал СЛЕД записа на фиша и т. 9 — последната стъпка на „Преизчисли“. */
    @Override
    protected void save(BgPaySlip slip) {
      super.save(slip);
      if (failAtSave) {
        throw new IllegalStateException("провал след записа на фиша");
      }
    }
  }
}
