package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgArt55Declaration;
import com.axelor.apps.bglocalization.db.repo.BgArt55DeclarationRepository;
import com.axelor.apps.bglocalization.service.art55.BgArt55Aggregator;
import com.axelor.apps.bglocalization.service.art55.BgArt55DeclarationService;
import com.axelor.apps.bglocalization.service.art55.BgArt55DeclarationValidator;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.util.List;

/**
 * Екранът на декларацията по чл. 55 — извеждане, отбелязване на подаването и ръчният изход.
 *
 * <p><b>⚠️ НЯМА БУТОН ЗА ФАЙЛ, И ТОВА НЕ Е ПРОПУСК.</b> Обр. 4001 се подава през ПОРТАЛА: НАП не е
 * публикувала машинна структура на записа за него. Човек попълва формуляра там и получава входящ
 * номер; екранът изважда числата и казва какво не е наред, преди той да ги препише.
 *
 * <p>Тънък слой нарочно: нито едно правило на образеца не живее тук. Виж {@code BgArt55Aggregator}
 * за разпределянето по редове и {@code BgArt55DeclarationValidator} за проверките.
 */
@Singleton
public class BgArt55DeclarationController {

  /**
   * ГЕНЕРИРА трите реда на Част ІV от регистъра.
   *
   * <p>⚠️ Иска ЗАПИСАНА декларация. Строителят търси съществуващата по (дружество, година,
   * тримесечие) и при незаписан екран би създал ВТОРА за същата тройка — уникалността щеше да я
   * отхвърли със съобщение за база вместо с обяснение.
   *
   * <p>⚠️ Отказва при вече отбелязана като подадена — пресъздаването има свой бутон.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgArt55Declaration declaration = managed(request, response);
      if (declaration == null) {
        return;
      }
      CapabilityGate.requireRole("generate an art. 55 declaration", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt55Declaration.class, declaration.getId());
      // ⚠️ Второто подаване НЕ е файл, а човешко действие в портала: там вече стои декларация с
      // входящ номер. Мълчаливо презаписани, числата на екрана щяха да се разминат с подаденото.
      if (BgDeclarationStatus.isLocked(declaration.getStatusSelect(), false)) {
        response.setInfo(
            "Декларацията вече е отбелязана като подадена. За корекция ползвай „Генерирай НАНОВО“ —"
                + " образецът иска НОВА декларация с всички данни за периода.");
        return;
      }
      rebuild(declaration, response);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ИЗРИЧНО ПРЕСЪЗДАВАНЕ — забравя предишното подаване и строи декларацията наново.
   *
   * <p>⚠️ Образецът, ДОСЛОВНО: „Ако вече сте подали декларация ... но искате да направите корекции
   * в нея, е необходимо да подадете НОВА данъчна декларация, в която се съдържат ВСИЧКИ данни за
   * периода, а не само тези, които променяте.“ Тоест същият запис, построен наново — точно този
   * ход.
   */
  public void regenerate(ActionRequest request, ActionResponse response) {
    try {
      BgArt55Declaration declaration = managed(request, response);
      if (declaration == null) {
        return;
      }
      CapabilityGate.requireRole("regenerate an art. 55 declaration", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt55Declaration.class, declaration.getId());
      boolean wasSubmitted = declaration.getNapSubmission() != null;
      Beans.get(BgArt55DeclarationService.class).invalidateSubmission(declaration);
      rebuild(declaration, response);
      if (wasSubmitted) {
        response.setNotify(
            "⚠️ Декларацията вече е била отбелязана като подадена. Старото подаване ОСТАВА в"
                + " одитната следа; новото ще посочи него като предходно. Подай наново в портала и"
                + " въведи НОВИЯ входящ номер.");
      }
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ОТБЕЛЯЗВА декларацията като ПОДАДЕНА през портала — БЕЗ реално подаване.
   *
   * <p><b>⚠️ Свидетелят е ВХОДЯЩИЯТ НОМЕР.</b> Чл. 56 ал. 2 иска подаване само по електронен път;
   * порталът връща входящ номер и точно той е следата, че подаване е станало. Файл Nyxa не
   * произвежда за този образец, тоест подаването не носи {@code NapSubmissionFile}.
   */
  public void attach(ActionRequest request, ActionResponse response) {
    try {
      BgArt55Declaration form = request.getContext().asType(BgArt55Declaration.class);
      BgArt55Declaration declaration = managed(request, response);
      if (declaration == null) {
        return;
      }
      CapabilityGate.requireRole("attach an art. 55 declaration file", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt55Declaration.class, declaration.getId());
      if (declaration.getNapSubmission() != null
          && !BgDeclarationStatus.GENERATED.equals(declaration.getStatusSelect())) {
        response.setInfo(
            "Декларацията вече е отбелязана като подадена — "
                + declaration.getNapSubmission().getName()
                + ". За корекция ползвай „Генерирай НАНОВО“ и отбележи пак.");
        return;
      }
      Beans.get(BgArt55DeclarationService.class)
          .attach(declaration, form.getNapIncomingNumber(), form.getNapResponseDate());
      response.setNotify(
          "Отбелязано като подадено през портала. Нищо не е изпратено оттук — обр. 4001 се попълва"
              + " на портала на НАП и няма машинен файл.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** РЪЧЕН ИЗХОД: приета. */
  public void markAccepted(ActionRequest request, ActionResponse response) {
    recordOutcome(request, response, true);
  }

  /** РЪЧЕН ИЗХОД: отхвърлена. */
  public void markRejected(ActionRequest request, ActionResponse response) {
    recordOutcome(request, response, false);
  }

  /** Общото тяло на двата ръчни изхода — чете номера и датата от ФОРМАТА. */
  protected void recordOutcome(ActionRequest request, ActionResponse response, boolean accepted) {
    try {
      BgArt55Declaration form = request.getContext().asType(BgArt55Declaration.class);
      BgArt55Declaration declaration = managed(request, response);
      if (declaration == null) {
        return;
      }
      CapabilityGate.requireRole(
          "record the НАП outcome of an art. 55 declaration", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt55Declaration.class, declaration.getId());
      Beans.get(BgArt55DeclarationService.class)
          .recordOutcome(
              declaration,
              accepted,
              form.getNapIncomingNumber(),
              form.getNapResponseDate(),
              form.getNapResponseNote());
      response.setNotify(
          accepted ? "Отбелязана като ПРИЕТА от НАП." : "Отбелязана като ОТХВЪРЛЕНА.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Извежда трите реда и КАЗВА какво не е наред — преди човек да ги препише в портала.
   *
   * <p><b>⚠️ СЪОБЩАВА, НЕ ЗАБРАНЯВА.</b> Няма файл, който да бъде спрян; спирането тук би попречило
   * само на СТРОЕНЕТО, а образецът изрично разчита декларацията да може да се строи наново за
   * корекция. Затова находките излизат като известие, а решението остава на човека — той е този,
   * който носи отговорността по образеца.
   */
  protected void rebuild(BgArt55Declaration declaration, ActionResponse response) throws Exception {
    BgArt55Declaration built =
        Beans.get(BgArt55DeclarationService.class)
            .build(declaration.getCompany(), declaration.getTaxYear(), declaration.getQuarter());

    // ⚠️ Q4 се съобщава ИЗРИЧНО, а не само в границите: нула на ред 1 е точно случаят, в който
    // човек ще реши, че генерирането не е сработило, и ще натисне пак.
    if (BgArt55Aggregator.suppressedInFourthQuarter(declaration.getQuarter())
        && built.getRow1AdvanceTax().signum() == 0) {
      response.setNotify(
          "Ред 1 е нула: за ЧЕТВЪРТОТО тримесечие авансов данък не се удържа (чл. 65, ал. 13)."
              + " Ако лице е поискало ПИСМЕНО удържане, върни сумата ръчно — виж границите.");
    }

    List<BgDeclarationFileFinding> findings = BgArt55DeclarationValidator.validate(built);
    if (!findings.isEmpty()) {
      response.setNotify(
          "Провери преди да попълниш портала:\n" + BgArt55DeclarationValidator.describe(findings));
    }
  }

  /** Записаната декларация по id — или обяснение вместо мълчание. */
  protected BgArt55Declaration managed(ActionRequest request, ActionResponse response) {
    BgArt55Declaration form = request.getContext().asType(BgArt55Declaration.class);
    if (form.getId() == null) {
      response.setInfo(
          "Запиши декларацията (дружество, данъчна година и тримесечие), преди да продължиш.");
      return null;
    }
    BgArt55Declaration declaration =
        Beans.get(BgArt55DeclarationRepository.class).find(form.getId());
    if (declaration == null) {
      response.setInfo(I18n.get("Декларацията вече не съществува — презареди екрана."));
    }
    return declaration;
  }
}
