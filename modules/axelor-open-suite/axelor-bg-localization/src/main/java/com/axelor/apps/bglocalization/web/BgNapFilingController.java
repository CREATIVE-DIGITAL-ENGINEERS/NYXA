package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgNapFilingWizard;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgD6DeclarationRepository;
import com.axelor.apps.bglocalization.service.d1.BgD1D6FilingRules;
import com.axelor.apps.bglocalization.service.d1.BgD1D6FilingService;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Singleton;
import java.time.LocalDate;

/**
 * Третото меню — операторската конзола „Подаване Д1/Д6 към НАП“: избира ГЕНЕРИРАНИТЕ Д1/Д6, създава
 * подаване-чернова през наличния транспорт ({@link BgD1D6FilingService}) и го ЗАКЛЮЧВА.
 *
 * <p><b>⚠️ НЕ Е ВТОРИ МОДЕЛ.</b> Екранът е ПРЕХОДЕН помощник ({@link BgNapFilingWizard});
 * същинското подаване си остава {@link NapSubmission}/{@code NapSubmissionFile}/{@code
 * NapArtifact}. Тук няма качване на външни файлове — избраните са точните непроменими байтове,
 * генерирани в Nyxa.
 *
 * <p><b>⚠️ НЕ ПОДПИСВА И НЕ ПОДАВА.</b> Подписът (детачен PKCS#7 + собствен КЕП) и подаването са
 * човешки стъпки в портала на НАП; тук се стига до ЗАКЛЮЧЕНА чернова, а нататък води „Ръчен портал“
 * на подаването.
 */
@Singleton
public class BgNapFilingController {

  /**
   * По подразбиране екранът сочи ПРЕДХОДНИЯ месец — обичайният период за подаване (срок 25-о
   * число).
   */
  public void onNew(ActionRequest request, ActionResponse response) {
    try {
      User user = AuthUtils.getUser();
      Company company = user == null ? null : user.getActiveCompany();
      response.setValue("company", company);
      response.setValue("companyEik", eikOf(company));
      LocalDate previousMonth = LocalDate.now().minusMonths(1);
      response.setValue("periodYear", previousMonth.getYear());
      response.setValue("periodFromMonth", previousMonth.getMonthValue());
      response.setValue("periodToMonth", previousMonth.getMonthValue());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** При смяна на дружеството — опресни ЕИК-а (той се чете от партньора, не се въвежда ръчно). */
  public void onCompanyChange(ActionRequest request, ActionResponse response) {
    try {
      BgNapFilingWizard wizard = request.getContext().asType(BgNapFilingWizard.class);
      response.setValue("companyEik", eikOf(wizard.getCompany()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Създава ЗАКЛЮЧЕНА чернова от избраните Д1/Д6 и отваря подаването.
   *
   * <p>Комбинацията (само Д1, Д1+Д6 или блокирана „само Д6“) се решава от {@link
   * BgD1D6FilingRules}; сервърната проверка на избора и заключването са в {@link
   * BgD1D6FilingService}.
   */
  public void createAndLock(ActionRequest request, ActionResponse response) {
    try {
      BgNapFilingWizard wizard = request.getContext().asType(BgNapFilingWizard.class);
      CapabilityGate.requireRole(
          "create and lock a NAP D1/D6 submission", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireActiveCompany(wizard.getCompany());

      BgD1Declaration d1 = fetchD1(wizard.getD1Declaration());
      BgD6Declaration d6 = fetchD6(wizard.getD6Declaration());

      BgD1D6FilingRules.Decision decision =
          BgD1D6FilingRules.decide(
              Boolean.TRUE.equals(wizard.getNoBasisD1()),
              d1 != null,
              Boolean.TRUE.equals(wizard.getNoBasisD6()),
              d6 != null);
      if (!decision.getKind().canProceed()) {
        response.setError(I18n.get(decision.getMessage()));
        return;
      }

      // Само Д1 — Д6 не се закача дори да е избрана по погрешка (решението вече е проверило
      // отметките).
      BgD6Declaration effectiveD6 =
          decision.getKind() == BgD1D6FilingRules.Kind.FILE_D1_AND_D6 ? d6 : null;

      NapSubmission submission;
      try {
        submission =
            Beans.get(BgD1D6FilingService.class)
                .createAndLock(
                    d1,
                    effectiveD6,
                    nz(wizard.getPeriodYear()),
                    nz(wizard.getPeriodFromMonth()),
                    nz(wizard.getPeriodToMonth()),
                    sourceFlag());
      } catch (IllegalStateException refused) {
        response.setError(I18n.get(refused.getMessage()));
        return;
      }

      response.setView(
          ActionView.define(I18n.get("NAP submission (locked draft)"))
              .model(NapSubmission.class.getName())
              .add("form", "nap-submission-form")
              .add("grid", "nap-submission-grid")
              .context("_showRecord", submission.getId())
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  protected BgD1Declaration fetchD1(BgD1Declaration selected) {
    if (selected == null || selected.getId() == null) {
      return null;
    }
    return Beans.get(BgD1DeclarationRepository.class).find(selected.getId());
  }

  protected BgD6Declaration fetchD6(BgD6Declaration selected) {
    if (selected == null || selected.getId() == null) {
      return null;
    }
    return Beans.get(BgD6DeclarationRepository.class).find(selected.getId());
  }

  /** Поле 53 на Д1 — ЕИК на разработчика на софтуера, от настройката на инсталацията. */
  protected String sourceFlag() {
    AppBgLocalization app = Beans.get(AppBgLocalizationRepository.class).all().fetchOne();
    return app == null ? null : app.getBgSoftwareDeveloperEik();
  }

  private static String eikOf(Company company) {
    Partner partner = company == null ? null : company.getPartner();
    return partner == null ? null : partner.getRegistrationCode();
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }
}
