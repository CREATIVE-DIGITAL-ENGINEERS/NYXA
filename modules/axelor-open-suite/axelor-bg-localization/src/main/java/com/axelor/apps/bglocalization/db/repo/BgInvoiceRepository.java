package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceRepository;
import com.axelor.apps.bglocalization.service.ai.BgNapInvoiceFieldContributor;
import com.axelor.apps.businessproject.service.app.AppBusinessProjectService;
import com.axelor.inject.Beans;
import com.google.inject.Inject;

/**
 * Прилага българските стойности по подразбиране върху ВСЯКА записвана фактура — не само върху тази,
 * която AI Accountant е създал.
 *
 * <h2>Защо в репозиторията</h2>
 *
 * Правилото „доставна фактура = вид на документа 01 (Фактура)" живее в {@link
 * BgNapInvoiceFieldContributor#applyCreationDefaults(Invoice)} и се прилагаше само по един път:
 * {@code InvoiceCreationService} в ai-accounting, тоест само когато фактурата е създадена от AI
 * инструмент. Фактура, въведена от екрана, дошла от OCR intake или внесена програмно, оставаше без
 * вид на документа и после падаше при подаването на дневника по ДДС.
 *
 * <p>Репозиторият е единствената точка, през която минават и четирите пътя: {@code save} се вика от
 * формата, от REST/API, от импорта и от всяка услуга. Затова тук стои ИЗВИКВАНЕТО, а правилото
 * остава там, където е — втора реализация на същото правило би се разминала с първата още при
 * първата промяна в закона.
 *
 * <h2>Какво НЕ прави</h2>
 *
 * Не пипа попълнена стойност (проверката за празно е в самия contributor), не пипа клиентски
 * фактури и не пипа кредитни известия — за тях 01 е грешният код. Продължава веригата на
 * репозиториите: {@code InvoiceRepository → … → BudgetInvoiceRepository → OcrInvoiceRepository →
 * тук}.
 */
public class BgInvoiceRepository extends OcrInvoiceRepository {

  @Inject
  public BgInvoiceRepository(AppBusinessProjectService appBusinessProjectService) {
    super(appBusinessProjectService);
  }

  @Override
  public Invoice save(Invoice entity) {
    if (entity != null) {
      Beans.get(BgNapInvoiceFieldContributor.class).applyCreationDefaults(entity);
    }
    return super.save(entity);
  }
}
