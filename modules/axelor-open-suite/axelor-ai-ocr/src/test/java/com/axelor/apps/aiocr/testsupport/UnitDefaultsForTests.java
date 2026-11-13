package com.axelor.apps.aiocr.testsupport;

import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService;
import com.axelor.apps.aiaccounting.service.invoice.spi.InvoiceLineUnitDefaultProvider;
import com.axelor.apps.base.db.Unit;
import java.util.List;

/**
 * Мерната единица по подразбиране за тестове, без Guice.
 *
 * <p>Услугата открива доставчиците си, като сканира classpath-а през {@code MetaScanner} и ги вади
 * през {@code Beans} — тоест иска жив инжектор, какъвто модулен тест няма. Всеки тест, който кара
 * код, създаващ ред от фактура, подава оттук.
 */
public final class UnitDefaultsForTests {

  private UnitDefaultsForTests() {}

  /** Инсталирана локализация с настроена стойност. */
  public static InvoiceLineUnitDefaultService configured(Unit unit) {
    return withProviders(
        List.of(
            new InvoiceLineUnitDefaultProvider() {
              @Override
              public Unit defaultInvoiceLineUnit() {
                return unit;
              }

              @Override
              public String notConfiguredMessage() {
                return "Default invoice line unit is not configured in BG Localization.";
              }
            }));
  }

  /**
   * Без инсталирана локализация — правилото мълчи и редът остава както го е оставил повикващият.
   * Това е положението за тестовете, които твърдят само сметки (закръгление, ДДС), и е точно
   * поведението на инсталация без българска локализация.
   */
  public static InvoiceLineUnitDefaultService none() {
    return withProviders(List.of());
  }

  private static InvoiceLineUnitDefaultService withProviders(
      List<InvoiceLineUnitDefaultProvider> installed) {
    return new InvoiceLineUnitDefaultService() {
      @Override
      protected List<InvoiceLineUnitDefaultProvider> providers() {
        return installed;
      }
    };
  }
}
