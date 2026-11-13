package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrection;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrectionLine;
import com.axelor.apps.bglocalization.db.repo.BgVatAnnualCorrectionLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatAnnualCorrectionRepository;
import com.axelor.apps.bglocalization.service.vat.BgArt79aProtocolService;
import com.axelor.apps.bglocalization.service.vat.BgVatAnnualCorrectionService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.List;

/**
 * Годишната корекция по чл. 79а/79б ЗДДС — бутоните.
 *
 * <p>Трите действия са нарочно РАЗДЕЛЕНИ, а не едно „Направи всичко“: предлагането е четене,
 * преизчисляването е след ръчна преценка, а осчетоводяването може да хвърли заради затворен период.
 * Слети, едно хвърляне при осчетоводяване би върнало и предложения списък.
 */
public class BgVatAnnualCorrectionController {

  /** Обхожда държаните активи и предлага по един ред за всеки в прозореца. */
  public void propose(ActionRequest request, ActionResponse response) {
    try {
      BgVatAnnualCorrection context = request.getContext().asType(BgVatAnnualCorrection.class);
      if (context.getCompany() == null || context.getYear() == 0) {
        response.setError(I18n.get("Изберете дружество и година преди да предложите редове."));
        return;
      }
      CapabilityGate.requireRole("propose an annual VAT correction", Capabilities.NAP_WRITE);
      CapabilityGate.requireActiveCompany(context.getCompany());
      BgVatAnnualCorrection saved =
          Beans.get(BgVatAnnualCorrectionService.class)
              .propose(context.getCompany(), context.getYear());
      response.setValue("id", saved.getId());
      response.setReload(true);
      response.setNotify(I18n.get("Предложението е готово. Прегледайте границите на изведеното."));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Преизчислява всички неосчетоводени редове след ръчна намеса в Кх или в използването. */
  public void recompute(ActionRequest request, ActionResponse response) {
    try {
      BgVatAnnualCorrection context = request.getContext().asType(BgVatAnnualCorrection.class);
      CapabilityGate.requireRole("recompute an annual VAT correction", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatAnnualCorrection.class, context.getId());
      BgVatAnnualCorrection correction =
          Beans.get(BgVatAnnualCorrectionRepository.class).find(context.getId());
      if (correction == null) {
        return;
      }
      BgVatAnnualCorrectionService service = Beans.get(BgVatAnnualCorrectionService.class);
      BgVatAnnualCorrectionLineRepository lineRepository =
          Beans.get(BgVatAnnualCorrectionLineRepository.class);
      List<BgVatAnnualCorrectionLine> lines = openLines(correction, lineRepository);
      for (BgVatAnnualCorrectionLine line : lines) {
        service.recompute(line);
        lineRepository.save(line);
      }
      response.setReload(true);
      response.setNotify(String.format(I18n.get("Преизчислени редове: %s"), lines.size()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Потвърждава корекцията и съставя протоколите.
   *
   * <p>⚠️ Липсващ протокол НЕ проваля останалите: всеки ред има своя транзакция, а причината се
   * записва в забележките му. Спрян на първия отказ, пакетът би скрил останалите дължими корекции.
   */
  public void confirm(ActionRequest request, ActionResponse response) {
    try {
      BgVatAnnualCorrection context = request.getContext().asType(BgVatAnnualCorrection.class);
      CapabilityGate.requireRole("confirm an annual VAT correction", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatAnnualCorrection.class, context.getId());
      BgVatAnnualCorrectionRepository repository = Beans.get(BgVatAnnualCorrectionRepository.class);
      BgVatAnnualCorrection correction = repository.find(context.getId());
      if (correction == null) {
        return;
      }
      BgArt79aProtocolService protocolService = Beans.get(BgArt79aProtocolService.class);
      BgVatAnnualCorrectionLineRepository lineRepository =
          Beans.get(BgVatAnnualCorrectionLineRepository.class);

      int posted = 0;
      int skipped = 0;
      for (BgVatAnnualCorrectionLine line : openLines(correction, lineRepository)) {
        if (protocolService.generate(line) != null) {
          posted++;
        } else {
          skipped++;
        }
      }
      correction.setStatusSelect(BgVatAnnualCorrectionService.STATUS_CONFIRMED);
      repository.save(correction);
      response.setReload(true);
      response.setNotify(
          String.format(I18n.get("Съставени протоколи: %s; без протокол: %s."), posted, skipped));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Редовете, които още нямат статия — само те се пипат. */
  protected List<BgVatAnnualCorrectionLine> openLines(
      BgVatAnnualCorrection correction, BgVatAnnualCorrectionLineRepository lineRepository) {
    return lineRepository
        .all()
        .filter("self.annualCorrection = :c AND self.correctionMove IS NULL")
        .bind("c", correction)
        .fetch();
  }
}
