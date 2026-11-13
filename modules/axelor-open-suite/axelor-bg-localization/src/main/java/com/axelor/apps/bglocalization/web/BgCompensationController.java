package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.repo.BgCompensationRepository;
import com.axelor.apps.bglocalization.db.repo.BgCompensationTypeRepository;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.BgCompensationService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Бутоните и подсказките на формата на обезщетението.
 *
 * <p>Тънък слой нарочно: нито едно правило не живее тук. Контролерът чете записа, вика услугата и
 * връща стойности на формата — така правилата остават проверими без екран.
 */
@Singleton
public class BgCompensationController {

  /** Подразбирания от вида и двете дати по чл. 228 — при нов запис и при смяна на вида. */
  public void prepare(ActionRequest request, ActionResponse response) {
    BgCompensation doc = request.getContext().asType(BgCompensation.class);
    BgCompensationService service = Beans.get(BgCompensationService.class);
    service.prepare(doc);
    service.recompute(doc);
    response.setValues(doc);
  }

  /** Преизчисляване при промяна на количество, база, срок или работни дни. */
  public void recompute(ActionRequest request, ActionResponse response) {
    BgCompensation doc = request.getContext().asType(BgCompensation.class);
    BgCompensationService service = Beans.get(BgCompensationService.class);

    if (BgCompensationResolver.NOTICE_DAYS.equals(doc.getBgBaseKind())
        && doc.getBgNoticeStartDate() != null) {
      int derived = service.derivedNoticeQuantity(doc);
      String warning = BgCompensationResolver.noticeQuantityWarning(doc.getBgQuantity(), derived);
      if (warning != null) {
        response.setNotify(I18n.get(warning));
      }
      // ⚠️ Непокритата година се КАЗВА още в черновата, вместо тишината да мине за отговор. Тук е
      // само предупреждение — блокировката е при потвърждаване, и то само ако количеството е още
      // изведеното. Виж BgCompensationResolver#refuseOnUncoveredCalendar.
      String uncovered = service.uncoveredCalendarYears(doc);
      if (!uncovered.isEmpty()) {
        response.setNotify(
            "Срокът навлиза в "
                + uncovered
                + " г., за която НЯМА зареден производствен календар. Работните дни са преброени само"
                + " по понеделник–петък, БЕЗ официалните празници — числото е завишено и"
                + " потвърждаването ще бъде спряно, докато не се допълни календарът или не въведете"
                + " количеството ръчно.");
      }
      response.setValue("bgNoticeCalendarDays", doc.getBgNoticeCalendarDays());
      response.setValue("bgQuantity", doc.getBgQuantity());
    }

    service.recompute(doc);
    response.setValue("bgComputedAmount", doc.getBgComputedAmount());
    response.setValue("bgFinalAmount", doc.getBgFinalAmount());
  }

  public void confirm(ActionRequest request, ActionResponse response) {
    run(request, response, BgCompensationResolver.CONFIRMED);
  }

  public void pay(ActionRequest request, ActionResponse response) {
    run(request, response, BgCompensationResolver.PAID);
  }

  public void cancel(ActionRequest request, ActionResponse response) {
    run(request, response, BgCompensationResolver.CANCELLED);
  }

  /**
   * ⚠️ ПРЕДУПРЕЖДЕНИЕ, НЕ БЛОКИРОВКА. При чл. 222 ал. 2 и ал. 3 законът поставя условие за стаж,
   * което системата не може да провери: няма таблица с историята на стажа по работодатели. Затова
   * се казва КАКВО иска законът и се оставя човекът да въведе числото.
   */
  public void warnSeniority(ActionRequest request, ActionResponse response) {
    BgCompensation doc = request.getContext().asType(BgCompensation.class);
    BgCompensationType type = doc.getBgCompensationType();
    if (type != null && type.getId() != null) {
      type = Beans.get(BgCompensationTypeRepository.class).find(type.getId());
    }
    String warning = BgCompensationResolver.seniorityWarning(type);
    if (warning != null) {
      response.setAlert(I18n.get(warning));
    }
  }

  /**
   * Един път за трите прехода: презарежда записа от базата, вика услугата и презарежда формата.
   *
   * <p>Записът се чете НАНОВО, а не се ползва контекстът: контекстът носи каквото е на екрана, а
   * преходите съдят по статуса, който е в базата.
   *
   * <p>⚠️ САМО ЗАПИСАН ДОКУМЕНТ. При {@code id == null} би се потвърдил ОБЕКТЪТ ОТ КОНТЕКСТА — с
   * какъвто договор клиентът е пратил, а {@code requireWrite(…, null)} проверява само правото за
   * създаване, не дружеството. Затова: без id — отказ; записът е от базата; дружеството му е
   * активното ({@code requireActiveCompany}); гейт по способност (PAYROLL_WRITE) и запис върху
   * самия документ.
   */
  private void run(ActionRequest request, ActionResponse response, String target) {
    try {
      BgCompensation doc = request.getContext().asType(BgCompensation.class);
      if (doc.getId() == null) {
        response.setError(
            "Действието е върху ЗАПИСАН документ — запишете го първо. Незаписан документ не се"
                + " потвърждава, не се изплаща и не се отказва.");
        return;
      }
      CapabilityGate.requireRole("change the status of a compensation", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgCompensation.class, doc.getId());
      BgCompensation saved = Beans.get(BgCompensationRepository.class).find(doc.getId());
      if (saved == null) {
        response.setError(I18n.get("Документът не е намерен."));
        return;
      }
      EmploymentContract contract = saved.getEmploymentContract();
      CapabilityGate.requireActiveCompany(contract == null ? null : contract.getPayCompany());
      // полетата, които формата може да е сменила преди натискането на бутона
      saved.setBgFinalAmount(doc.getBgFinalAmount());
      saved.setBgAdjustmentReason(doc.getBgAdjustmentReason());
      saved.setBgPaidDate(doc.getBgPaidDate());
      saved.setNote(doc.getNote());
      BgCompensationService service = Beans.get(BgCompensationService.class);
      if (BgCompensationResolver.CONFIRMED.equals(target)) {
        service.confirm(saved);
      } else if (BgCompensationResolver.PAID.equals(target)) {
        service.pay(saved);
      } else {
        // ⚠️ ПРИЧИНАТА Е ОТ ВХОДА НА БУТОНА, НЕ ОТ bgCancelReason. То се показва само СЛЕД отказа
        // и само за четене, тоест оттук винаги идваше празно и отказът винаги падаше.
        service.cancel(saved, doc.getBgCancelReasonInput());
      }
      response.setReload(true);
    } catch (AxelorException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
