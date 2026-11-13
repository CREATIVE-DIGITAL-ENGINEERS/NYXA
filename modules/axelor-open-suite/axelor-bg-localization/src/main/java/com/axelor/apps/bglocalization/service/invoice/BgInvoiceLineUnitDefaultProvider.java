package com.axelor.apps.bglocalization.service.invoice;

import com.axelor.apps.aiaccounting.service.invoice.spi.InvoiceLineUnitDefaultProvider;
import com.axelor.apps.base.db.Unit;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Answers the shared invoice-line defaulting with the unit the BG configuration points at.
 *
 * <h2>A reference, never a lookup</h2>
 *
 * The value is a foreign key to the {@code Unit} row itself ({@code
 * AppBgLocalization.bgDefaultInvoiceLineUnit}), because there is nothing else on {@code Unit} to
 * hold onto: its whole domain is {@code name} (translatable, required, namecolumn), {@code
 * labelToPrinting} (translatable) and {@code unitTypeSelect}. No code, no unique key. Resolving by
 * name would break silently the day somebody renames the unit and would silently pick one of two on
 * a duplicate. The importId ("2" for „Брой“, {@code data-init/input/base_unit.csv}) is used only
 * once, to fill an EMPTY setting — see {@link BgDefaultInvoiceLineUnitSeed}; from then on the
 * reference is what counts.
 *
 * <p>Hence also: no first record, no ordering, no "something similar". Unconfigured returns {@code
 * null} and the shared service turns that into one clear setup error.
 *
 * <p>The singleton config row is read the same way the rest of this module reads it ({@code
 * BgFixedAssetManagementRepository}, {@code BgSubsequentCostService}): {@code all().fetchOne()}. No
 * row at all means the app was never installed, which is the same answer as unconfigured.
 */
@Singleton
public class BgInvoiceLineUnitDefaultProvider implements InvoiceLineUnitDefaultProvider {

  protected final AppBgLocalizationRepository appRepo;

  @Inject
  public BgInvoiceLineUnitDefaultProvider(AppBgLocalizationRepository appRepo) {
    this.appRepo = appRepo;
  }

  @Override
  public Unit defaultInvoiceLineUnit() {
    AppBgLocalization app = appRepo.all().fetchOne();
    return app == null ? null : app.getBgDefaultInvoiceLineUnit();
  }

  @Override
  public String notConfiguredMessage() {
    return "Не е зададена мерна единица по подразбиране на ред от фактура в BG Localization."
        + " Задайте я в Администрация → Управление на приложения → BG Localization → Конфигуриране"
        + " → „Редове на фактура“ → „Мерна единица по подразбиране на ред от фактура“ (обикновено"
        + " „Брой“) и внесете документа отново.";
  }
}
