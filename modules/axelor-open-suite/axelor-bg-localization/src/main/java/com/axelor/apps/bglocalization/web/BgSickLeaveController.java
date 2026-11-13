package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictService;
import com.axelor.apps.bglocalization.service.payroll.BgSickNoteArrivalService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.Map;

/**
 * БОЛНИЧНИЯТ ЛИСТ — единственото действие върху него днес е „отрази пристигнал болничен“.
 *
 * <p><b>ДВЕ СТЪПКИ, ВЕРИЖНО, И ТОВА Е САМИЯТ МОДЕЛ.</b> Бутонът вика {@link #planArrival} и чак
 * след това {@link #applyArrival}: първата показва ПЛАНА в модален диалог, втората го изпълнява.
 * Axelor спира веригата, ако човекът натисне „Отказ“ — тоест потвърждението не е украса, а
 * условието действието изобщо да се случи. Модел (б): предложение с готова корекция, която човек
 * потвърждава; нула тихо местене на пари.
 *
 * <p>⚠️ Отказите се показват със {@code setError}, а планът — със {@code setAlert}. Разликата е
 * видима за оператора: първото спира, второто пита.
 */
public class BgSickLeaveController {

  /**
   * ПЪРВАТА СТЪПКА: казва какво ще се случи, или защо няма да се случи.
   *
   * <p>⚠️ Записът се чете НАНОВО от базата, а не от контекста: контекстът носи каквото е на екрана,
   * а всички откази съдят по онова, което е записано — статуса на листа, статуса на периода,
   * заявките за отпуск.
   */
  public void planArrival(ActionRequest request, ActionResponse response) {
    try {
      BgSickLeave sickLeave = managed(request);
      if (sickLeave == null) {
        response.setError(I18n.get("Отразява се ЗАПИСАН болничен лист — запишете този първо."));
        return;
      }
      BgSickNoteArrivalService service = Beans.get(BgSickNoteArrivalService.class);
      String refusal = service.refusalFor(sickLeave);
      if (refusal != null) {
        response.setError(I18n.get(refusal));
        return;
      }
      response.setAlert(service.plan(sickLeave));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ВТОРАТА СТЪПКА: изпълнява и ОТВАРЯ копието.
   *
   * <p>⚠️ Отварянето не е удобство — същият довод като при „Коригирай“ на фиша: без него операторът
   * вижда „запазено“ и остава пред болничния, тоест или мисли, че нищо не е станало, или търси
   * копието на ръка. Виж {@link #copyView} защо по идентификатор.
   *
   * <p>⚠️ И НЕ СЕ ПОТВЪРЖДАВА САМО. Копието се отваря в ЧЕРНОВА; нетната разлика влиза в текущия
   * фиш чак когато човек натисне „Потвърди“ върху него.
   */
  public void applyArrival(ActionRequest request, ActionResponse response) {
    try {
      BgSickLeave sickLeave = managed(request);
      if (sickLeave == null) {
        response.setError(I18n.get("Отразява се ЗАПИСАН болничен лист — запишете този първо."));
        return;
      }
      CapabilityGate.requireRole("apply an arrived sick note", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgSickLeave.class, sickLeave.getId());
      CapabilityGate.requireWrite(BgPaySlip.class, null);
      BgPaySlip copy = Beans.get(BgSickNoteArrivalService.class).apply(sickLeave);
      response.setReload(true);
      response.setView(copyView(copy));
    } catch (IllegalStateException e) {
      // ⚠️ Отказът на услугата е СЪОБЩЕНИЕ за оператора, не следа в лога: той назовава записа,
      // който трябва да се поправи, и е написан точно за екрана.
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      // ⚠️ Следата commit-ва ВСИЧКО мръсно в контекста на заявката (JPA.runInTransaction).
      // Безопасно е САМО защото apply е @Transactional: rollback-ът кара Hibernate да изчисти
      // контекста, преди да стигнем дотук. Без анотацията тук би оцеляла отменената заявка без
      // копие.
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ИЗГЛЕДЪТ, КОЙТО ОТВАРЯ ТОЧНО СЪЗДАДЕНОТО КОПИЕ — по идентификатора му.
   *
   * <p><b>⚠️ ЗАЩО НЕ ПО ПРЕПРАТКА.</b> Действие, което отваря формата ПЪРВА, с домейн по
   * препратката към оригинала ({@code self.bgCorrectionOf.id = :originalId}) и без запис за
   * показване, кара Axelor да отвори форма за НОВ фиш: празен таб вместо копието. Доводът
   * „идентификаторът се появява чак след flush“ не важи тук: {@code apply} е транзакция и се е
   * затворила, тоест копието е записано и има идентификатор. Домейнът по препратка би показал и
   * отказани копия от предишни опити — {@code _showRecord} показва ЕДНОТО, което човекът току-що е
   * създал.
   */
  static Map<String, Object> copyView(BgPaySlip copy) {
    return ActionView.define("Коригиращ фиш")
        .model(BgPaySlip.class.getName())
        .add("form", "bg-payslip-form")
        .add("grid", "bg-payslip-grid")
        .domain("self.id = :copyId")
        .context("copyId", copy.getId())
        .context("_showRecord", copy.getId())
        .map();
  }

  /**
   * „ОПРЕДЕЛИ ПРАВОТО НАНОВО“ — изричното, одитирано ново смятане на присъдата за правото на
   * обезщетение, с причина.
   *
   * <p>⚠️ ГЕЙТЪТ Е ПО СПОСОБНОСТ И ПО ЗАПИС: {@code NOI_WRITE} (ролите от каталога — засега {@code
   * client.admin}) и право за запис върху ТОЗИ лист, прочетен от базата — каталогът го ограничава
   * до активното дружество. Причината идва от входа на бутона (transient), не от записаното на
   * листа. Отказ на смятането не пипа нищо: старата присъда остава.
   *
   * <p>⚠️ И ИЗРИЧНО АКТИВНОТО ДРУЖЕСТВО (A5) — дружеството-платец по договора на листа, както при
   * контролерите на Приложение № 9. ПРЕДИ {@code requireWrite}: условието на каталога днес пази
   * същото, но е данни — сменено там, то не бива да е единствената стена пред действие, което
   * пренаписва правото на чуждо лице.
   */
  public void redetermineRight(ActionRequest request, ActionResponse response) {
    try {
      BgSickLeave sickLeave = managed(request);
      if (sickLeave == null) {
        response.setError(
            I18n.get("Правото се определя на ЗАПИСАН болничен лист — запишете този първо."));
        return;
      }
      CapabilityGate.requireRole("redetermine the benefit right", Capabilities.NOI_WRITE);
      CapabilityGate.requireActiveCompany(
          sickLeave.getEmploymentContract() == null
              ? null
              : sickLeave.getEmploymentContract().getPayCompany());
      CapabilityGate.requireWrite(BgSickLeave.class, sickLeave.getId());
      String reason =
          request.getContext().asType(BgSickLeave.class).getBgBenefitVerdictReasonInput();
      Beans.get(BgSickBenefitVerdictService.class).redetermine(sickLeave, reason);
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  private BgSickLeave managed(ActionRequest request) {
    BgSickLeave context = request.getContext().asType(BgSickLeave.class);
    if (context.getId() == null) {
      return null;
    }
    return Beans.get(BgSickLeaveRepository.class).find(context.getId());
  }
}
