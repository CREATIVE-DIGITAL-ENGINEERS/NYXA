package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.web.BgVatReturnController;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;

/**
 * ДДС ДЕКЛАРАЦИИТЕ ЗА 01–10 — генерирани, НЕ отбелязани като подадени.
 *
 * <p>За всеки месец: нова справка-декларация на дружеството за периода (ГГГГММ) и бутонът
 * „Генерирай“ ({@link BgVatReturnController#generate}) — дневниците, DEKLAR/PRODAGBI/POKUPKI (и
 * VIES, когато има данни), снимката на дневниците, пакетът и статусът „Генерирана“. Деклараторът е
 * този по подразбиране на дружеството ({@code company.csv}). „Подадена“ не се натиска — периодите
 * остават отворени за демонстрация на корекции.
 */
final class BgDemoVatReturnStep {

  private BgDemoVatReturnStep() {}

  static void run(BgDemoContext ctx) throws Exception {
    for (int m = 1; m <= BgDemoDates.LAST_MONTH.getValue(); m++) {
      String period = String.format("%04d%02d", ctx.dates().year(), m);
      Long id =
          BgDemoTx.call(
              () -> {
                Company company = ctx.company();
                BgVatReturn r = new BgVatReturn();
                r.setCompany(company);
                r.setPeriod(period);
                r.setStatusSelect("draft");
                return JPA.save(r).getId();
              });
      BgDemoForm.press(BgVatReturn.class, id, Beans.get(BgVatReturnController.class)::generate);
      String status = BgDemoTx.call(() -> JPA.find(BgVatReturn.class, id).getStatusSelect());
      if (!"generated".equals(status)) {
        throw new IllegalStateException("ДДС декларацията за " + period + " не е генерирана");
      }
    }
    ctx.log("ДДС декларации 01–10: генерирани (не са подадени)");
  }
}
