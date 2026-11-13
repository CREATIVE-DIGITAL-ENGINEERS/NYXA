package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.d1.BgD1Emitter;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD1ReportPrintService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Бутоните на Декларация обр. № 1 — по образеца на {@code BgVatReturnController}.
 *
 * <p>Бутонът е вързан като {@code save,action-…,save}: записът има идентификатор преди генерирането
 * и произведените редове оцеляват след него.
 *
 * <p><b>⚠️ ГЕНЕРИРАНЕТО Е ЗАМЯНА, НЕ ДОПЪЛВАНЕ.</b> Второ натискане изтрива предишните записи и
 * прави нови. Допълването би оставило запис за фиш, който междувременно е отказан — тоест лице,
 * което емитерът вече отказва да декларира, би останало в декларацията от предишния опит.
 */
@Singleton
public class BgD1DeclarationController {

  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgD1Declaration context = request.getContext().asType(BgD1Declaration.class);
      CapabilityGate.requireRole("generate a D1 declaration", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgD1Declaration.class, context.getId());
      // Дружеството — от ЗАПИСАНОТО: клиентът изпраща каквото дружество поиска. Незаписана — от
      // екрана, защото друго няма (и тогава по-долу се иска запис).
      BgD1Declaration stored =
          context.getId() == null
              ? context
              : Beans.get(BgD1DeclarationRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(stored == null ? null : stored.getCompany());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете декларацията, преди да генерирате записите."));
        return;
      }
      if (context.getBgDataPeriod() == null) {
        response.setError(
            "Периодът на ДАННИТЕ е задължителен — той пълни т. 2 и т. 3 на всеки запис, а без месец"
                + " декларацията не се приема (чл. 11 ал. 1).");
        return;
      }
      if (Boolean.TRUE.equals(context.getBgIsHistoricalImport())) {
        response.setError(
            "Исторически внесена декларация (миграция) — генерирането е ЗАМЯНА (виж бележката на"
                + " класа) и би изтрило записите, преписани от вече подадена декларация в"
                + " Microinvest.");
        return;
      }

      BgD1Emitter.Outcome outcome = generateFor(context.getId());

      StringBuilder message = new StringBuilder();
      message.append("Произведени записи: ").append(outcome.getRecords().size()).append('.');
      if (!outcome.getRefusals().isEmpty()) {
        message
            .append(" ⚠️ ОТКАЗАНИ фиша: ")
            .append(outcome.getRefusals().size())
            .append(" — виж панела „Откази“. Те НЕ са в декларацията.");
      }
      if (!outcome.getFindings().isEmpty()) {
        message.append(" Находки: ").append(outcome.getFindings().size()).append('.');
      }
      response.setInfo(message.toString());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Самото генериране — ДЕЛЕГИРА на услугата.
   *
   * <p>⚠️ Тялото е в {@link BgD1GenerationService}, защото твърдението, което иска пазене, е РЕДЪТ
   * (отказът преди изтриването), а той не се доказва с четене. В контролера той би стоял зад четири
   * {@code Beans.get} и би бил недостижим за свидетел.
   */
  protected BgD1Emitter.Outcome generateFor(Long declarationId) {
    return Beans.get(BgD1GenerationService.class).generate(declarationId);
  }

  public void print(ActionRequest request, ActionResponse response) {
    try {
      BgD1Declaration declaration = request.getContext().asType(BgD1Declaration.class);
      CapabilityGate.requireRead(BgD1Declaration.class, declaration.getId());
      if (declaration.getId() == null || "draft".equals(declaration.getStatusSelect())) {
        response.setInfo(I18n.get("Първо генерирайте записите, за да има какво да се печата."));
        return;
      }
      String fileLink =
          Beans.get(BgD1ReportPrintService.class)
              .print(
                  Beans.get(BgD1DeclarationRepository.class).find(declaration.getId()),
                  IReport.BG_D1_DECLARATION,
                  ReportSettings.FORMAT_PDF,
                  "Декларация-обр-1");
      response.setView(
          ActionView.define("Декларация обр. 1")
              .add("html", fileLink)
              .param("download", "false")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
