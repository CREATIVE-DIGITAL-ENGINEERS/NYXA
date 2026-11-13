package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.noi.BgApp9DeadlineService;
import com.axelor.apps.bglocalization.service.noi.BgApp9DueScope;
import com.axelor.apps.bglocalization.service.noi.BgApp9SubmissionService;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.CallMethod;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.Set;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * „ПРИЛ. 9 ЗА ПОДАВАНЕ“ — списъкът, броячът в менюто, „Опресни сроковете“ и отмяната на отметка.
 *
 * <p><b>⚠️ ЗАЩО БРОЯЧ В МЕНЮТО, А НЕ ЗАДАЧА ИЛИ ИМЕЙЛ.</b> Броячът се смята при всяко зареждане на
 * менюто — не може тихо „да не се е пуснал“. Quartz е изключен в комитнатия конфиг, а напомняне за
 * срок, което мълчи, защото планировчикът е спрян, е най-лошият провал. Имейлът иска SMTP и роля
 * „отговорни служители“, каквато няма.
 *
 * <p><b>⚠️ ЕДИН ФИЛТЪР.</b> Списъкът и броячът четат {@link BgApp9DueScope}; друг път до обхвата
 * няма.
 */
@Singleton
public class BgApp9DueController {

  /**
   * Отваря списъка на АКТИВНОТО дружество. Опреснява сроковете САМО с право за запис ({@code
   * Capabilities.NOI_WRITE}): опресняването ЗАПИСВА листовете. Без него списъкът се отваря без
   * запис и казва кога сроковете са опреснени за последно.
   */
  public void openDueList(ActionRequest request, ActionResponse response) {
    try {
      User user = AuthUtils.getUser();
      Set<Long> companies = BgApp9DueScope.activeCompanyIds(user);
      if (companies.isEmpty()) {
        response.setError(I18n.get(BgApp9DueScope.NO_ACTIVE_COMPANY));
        return;
      }
      BgApp9DeadlineService deadlines = Beans.get(BgApp9DeadlineService.class);
      String note = null;
      if (mayWrite()) {
        // Срокът зависи и от неща ИЗВЪН листа — границата на дружеството, заредените години на
        // календара. Опресняването пише само променените листове.
        deadlines.refresh(companies);
      } else {
        note = deadlines.refreshedNote(user.getActiveCompany());
      }
      response.setView(
          ActionView.define("Прил. 9 за подаване")
              .model(BgSickLeave.class.getName())
              .add("grid", "bg-app9-due-grid")
              .add("form", "bg-sick-leave-form")
              .domain(BgApp9DueScope.listDomain(companies))
              .map());
      if (note != null) {
        response.setNotify(I18n.get(note));
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Има ли потребителят право за запис по Прил. 9 — без да отказва (за отварянето на списъка). */
  protected boolean mayWrite() {
    try {
      CapabilityGate.requireRole("refresh App. 9 deadlines", Capabilities.NOI_WRITE);
      return true;
    } catch (UnauthorizedException e) {
      return false;
    }
  }

  /** „Опресни сроковете“ — например след като календарът за новата година е зареден. */
  public void refreshDeadlines(ActionRequest request, ActionResponse response) {
    try {
      // Прил. 9 — Capabilities.NOI_WRITE. Опресняването ЗАПИСВА сроковете на
      // листовете.
      CapabilityGate.requireRole("refresh App. 9 deadlines", Capabilities.NOI_WRITE);
      Set<Long> companies = BgApp9DueScope.activeCompanyIds(AuthUtils.getUser());
      if (companies.isEmpty()) {
        response.setError(I18n.get(BgApp9DueScope.NO_ACTIVE_COMPANY));
        return;
      }
      int changed = Beans.get(BgApp9DeadlineService.class).refresh(companies);
      response.setInfo(
          String.format(I18n.get("Сроковете са опреснени. Променени листове: %s."), changed));
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * БРОЯЧЪТ В МЕНЮТО — просрочените, днешните и тези до 3 работни дни, още неподадени.
   *
   * <p>⚠️ Само чете: менюто се зарежда често и не бива да пише.
   */
  @CallMethod
  public String dueMenuTag() {
    long count =
        Beans.get(BgApp9DeadlineService.class).countUrgent(AuthUtils.getUser(), LocalDate.now());
    return count == 0 ? null : String.valueOf(count);
  }

  /** „Отмени отметката“ — от формата на подаването към НОИ; причината е задължителна. */
  public void withdrawSubmission(ActionRequest request, ActionResponse response) {
    try {
      NapSubmission context = request.getContext().asType(NapSubmission.class);
      if (context.getId() == null) {
        return;
      }
      // Прил. 9 — Capabilities.NOI_WRITE. Отмяната връща удостоверенията в
      // „генерирано“.
      CapabilityGate.requireRole("withdraw an App. 9 submission", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(NapSubmission.class, context.getId());
      NapSubmission submission = Beans.get(NapSubmissionRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(submission == null ? null : submission.getCompany());
      Beans.get(BgApp9SubmissionService.class)
          .withdraw(submission, context.getActionReason(), AuthUtils.getUser());
      response.setInfo(
          "Отметката е отменена. Удостоверенията са отново „генерирано“ и срокът им се показва.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
