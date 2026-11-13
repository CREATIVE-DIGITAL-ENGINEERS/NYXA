package com.axelor.apps.bglocalization.job;

import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.apps.bglocalization.service.stock.BgStockValuationProcessor;
import com.google.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ИЗП: обработва задачите „чака оценка“ на всяка минута — „текущо“ осчетоводяване значи почти
 * веднага след реализирането.
 *
 * <p>{@link ThreadedJob} по същата причина като {@code BgFixedAssetRealizationJob}: само така
 * задача на Quartz получава работещ Guice обхват. {@link #RUNNING} не пуска втори ход, докато
 * първият работи — редът на обработка е редът на реализиране и два паралелни хода биха го
 * разбъркали.
 */
@DisallowConcurrentExecution
public class BgStockValuationJob extends ThreadedJob {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  protected final BgStockValuationProcessor processor;

  @Inject
  public BgStockValuationJob(BgStockValuationProcessor processor) {
    this.processor = processor;
  }

  @Override
  public void executeInThread(JobExecutionContext context) {
    if (!RUNNING.compareAndSet(false, true)) {
      return;
    }
    try {
      int done = processor.processPending();
      if (done > 0) {
        LOG.info("Stock valuation: {} stock move(s) valued and posted.", done);
      }
    } catch (Exception e) {
      LOG.error("Stock valuation run failed: {}", e.getMessage(), e);
    } finally {
      RUNNING.set(false);
    }
  }
}
