package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.db.BgIncomePaymentFeedWizard;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomePaymentFeedBoundaries;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomePaymentFeedService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * ЕКРАНЪТ НА ЗАХРАНВАНЕТО — един бутон, който сканира периода и upsert-ва редове.
 *
 * <p>Тънък слой нарочно: нито едно правило не живее тук. Виж {@code BgIncomePaymentFeedMapper} за
 * годността на кандидата и признака по чл. 43 ал. 5, {@code BgIncomePaymentFeedService} за обхода и
 * upsert-а, {@code BgIncomeTaxResolver} за аритметиката.
 *
 * <p><b>⚠️ РАБОТИ ВЪРХУ КОНТЕКСТА НА ФОРМАТА, БЕЗ {@code save}.</b> Wizard-ът е преходен — виж
 * домейна му. Записан, той щеше да остави в базата по един ред на всяко натискане, а нито един от
 * тях не е документ.
 */
@Singleton
public class BgIncomePaymentFeedController {

  /**
   * ПРАЗНОТО ТРИМЕСЕЧИЕ ИДВА КАТО НУЛА, А НЕ КАТО {@code null} — и това е цялата причина този
   * контролер да превежда.
   *
   * <p><b>⚠️ ИЗМЕРЕНО, НЕ ПРЕДПОЛОЖЕНО.</b> Генерираният getter за {@code Integer} връща 0 за
   * непопълнена колона (виж {@code BgDefaultedGetterTest}), тоест „не съм избрал тримесечие“ и
   * „нулево тримесечие“ са НЕРАЗЛИЧИМИ по модела. Подадена направо, нулата би стигнала до {@code
   * BgIncomePaymentFeedMapper#firstDayOf(година, 0)} и би дала 1 януари МИНУС три месеца — тоест
   * обхват от октомври на ПРЕДХОДНАТА година, при напълно зелен екран.
   *
   * <p>Затова нулата се чете тук като „цялата година“ и надолу пътува {@code null} — стойността,
   * която услугата и правилата разбират.
   */
  protected static Integer scopeQuarter(BgIncomePaymentFeedWizard form) {
    int quarter = form.getQuarter();
    return quarter == 0 ? null : quarter;
  }

  /** Подразбирания при отваряне: активното дружество и текущата година. */
  public void prepare(ActionRequest request, ActionResponse response) {
    BgIncomePaymentFeedWizard form = request.getContext().asType(BgIncomePaymentFeedWizard.class);
    // ⚠️ Нулата е „непопълнено“, а не година нула — същият довод като при тримесечието.
    if (form.getTaxYear() == 0) {
      response.setValue("taxYear", java.time.LocalDate.now().getYear());
    }
  }

  /**
   * ПУСКА ПРОГОНА и показва границите му.
   *
   * <p>⚠️ Резултатът се пише в {@code resultSummary} и се ПОКАЗВА, вместо да се съобщи с едноредово
   * известие. Прогонът връща числа по причина за отказ, а известие, което ги събира в едно
   * изречение, е точно видът съобщение, което се затваря, без да се чете.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    BgIncomePaymentFeedWizard form = request.getContext().asType(BgIncomePaymentFeedWizard.class);

    if (form.getCompany() == null) {
      response.setInfo(I18n.get("Избери платец (дружество) — обходът е по него."));
      return;
    }
    CapabilityGate.requireRole("generate income payments", Capabilities.NAP_WRITE);
    CapabilityGate.requireActiveCompany(form.getCompany());
    if (form.getTaxYear() < 2000) {
      response.setInfo(I18n.get("Данъчната година липсва или е неправдоподобна."));
      return;
    }
    Integer quarter = scopeQuarter(form);
    if (quarter != null && (quarter < 1 || quarter > 4)) {
      response.setInfo(I18n.get("Тримесечието е между 1 и 4, или празно за цялата година."));
      return;
    }

    boolean dryRun = !Boolean.FALSE.equals(form.getDryRun());
    BgIncomePaymentFeedBoundaries.Facts facts =
        Beans.get(BgIncomePaymentFeedService.class)
            .run(form.getCompany(), form.getTaxYear(), quarter, dryRun);

    response.setValue("resultSummary", BgIncomePaymentFeedBoundaries.report(facts));

    // ⚠️ Известието казва САМО двете числа, които решават дали да се погледне текстът. Пълният
    // разказ стои в полето и не се затваря с щракване.
    response.setNotify(
        (dryRun ? "ПРОБЕН прогон: " : "Прогонът е записан: ")
            + facts.created
            + " нови, "
            + facts.updated
            + " обновени, "
            + refusedTotal(facts)
            + " отказани. Прочети „Резултат и граници“.");
  }

  private static int refusedTotal(BgIncomePaymentFeedBoundaries.Facts facts) {
    int total = 0;
    for (Integer count : facts.refusals.values()) {
      total += count;
    }
    return total;
  }
}
