package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.mail.db.MailMessage;
import com.axelor.mail.db.repo.MailFollowerRepository;
import com.axelor.mail.db.repo.MailMessageRepository;
import com.google.inject.servlet.RequestScoper;
import com.google.inject.servlet.ServletScopes;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * НАЛИВАНЕТО ВЪВ ФОНОВАТА НИШКА — със свой обхват на заявка, следа при грешка и известие накрая.
 *
 * <p><b>⚠️ СВОЙ REQUEST SCOPE, ОТВОРЕН ВЪТРЕ В НИШКАТА.</b> Бутонът пуска наливането в нишка на
 * {@code ControllerCallableTool} ({@code TenantAware}), а тя няма HTTP заявка. Услугите на AOS с
 * {@code @RequestScoped} (напр. {@code PaymentModeServiceImpl}, до която стига още {@code
 * Beans.get(PartnerService.class)} през хранилището на партньора) хвърлят там {@code
 * OutOfScopeException} — без обхват наливането пада, без нищо записано и без дума към потребителя.
 * Обхватът се отваря тук, в нишката, както в {@code ThreadedJob} и {@link
 * com.axelor.apps.bglocalization.service.currency.BgExchangeRatePairs#runInBackground}. НЕ {@code
 * ServletScopes.transferRequest}: заявката свършва след {@code processTimeout} секунди, а
 * наливането трае около час.
 *
 * <p><b>⚠️ НИЩО НЕ ИЗЛИЗА ОТ НИШКАТА.</b> {@code ControllerCallableTool} не връща резултата на
 * задачата (бъдещето винаги е {@code null}), а изключение в {@code TenantAware} отива само в
 * stderr. Затова всеки изход се казва оттук: грешката — в лога (ERROR) и в „Проследявания“ ({@link
 * TraceBackService}); и при успех, и при грешка — известие до натисналия бутона във „Входящи“
 * (коментар на дружеството, без имейл; той става последовател на записа).
 */
public final class BgDemoBackgroundRun {

  private static final Logger LOG = LoggerFactory.getLogger(BgDemoBackgroundRun.class);

  static final String SUBJECT_DONE = "Демо данни: готово";

  static final String SUBJECT_FAILED = "Демо данни: грешка";

  /** Изходът на наливането — за екрана, ако е свършило, докато бутонът чака. */
  public static final class Outcome {
    public final boolean ok;
    public final String text;

    Outcome(boolean ok, String text) {
      this.ok = ok;
      this.text = text;
    }
  }

  private BgDemoBackgroundRun() {}

  /**
   * Изпълнява {@code work} в текущата (фонова) нишка в свой обхват на заявка. Никога не хвърля:
   * грешката е в лога, в „Проследявания“ и в известието.
   */
  public static Outcome run(Long companyId, Long userId, Callable<BgDemoDataService.Result> work) {
    Outcome outcome;
    RequestScoper scope = ServletScopes.scopeRequest(Collections.emptyMap());
    try (RequestScoper.CloseableScope ignored = scope.open()) {
      try {
        BgDemoDataService.Result result = work.call();
        outcome = new Outcome(true, String.join("\n", result.log));
      } catch (BgDemoDataService.Refused e) {
        // Отказ (непразно дружество, вече налято ТРЗ…) — не е дефект, затова без следа.
        outcome = new Outcome(false, String.join("\n", e.reasons));
      } catch (Exception | Error e) {
        outcome = failed(e);
      }
      notifyUser(companyId, userId, outcome);
    } finally {
      clear();
    }
    return outcome;
  }

  private static Outcome failed(Throwable e) {
    String reason = BgDemoDataService.reason(e);
    LOG.error("Демо данни: наливането във фоновата нишка спря — {}", reason, e);
    try {
      clear();
      TraceBackService.trace(e);
    } catch (Exception traceFailed) {
      LOG.error("Демо данни: и следата в „Проследявания“ не се записа", traceFailed);
    }
    return new Outcome(false, reason);
  }

  /** Известие във „Входящи“ на натисналия бутона. Грешка тук се пише само в лога. */
  static void notifyUser(Long companyId, Long userId, Outcome outcome) {
    try {
      JPA.runInTransaction(
          () -> {
            Company company = JPA.find(Company.class, companyId);
            User user = userId == null ? null : JPA.find(User.class, userId);
            if (company == null) {
              return;
            }
            if (user != null) {
              Beans.get(MailFollowerRepository.class).follow(company, user);
            }
            MailMessage message = new MailMessage();
            // ⚠️ comment, НЕ notification: notification праща имейл на всички последователи.
            message.setType("comment");
            message.setSubject(I18n.get(outcome.ok ? SUBJECT_DONE : SUBJECT_FAILED));
            message.setBody("<p>" + escape(outcome.text).replace("\n", "<br/>") + "</p>");
            message.setRelatedModel(Company.class.getName());
            message.setRelatedId(companyId);
            message.setRelatedName(company.getName());
            if (user != null) {
              message.setAuthor(user);
            }
            Beans.get(MailMessageRepository.class).save(message);
          });
    } catch (Exception e) {
      LOG.error("Демо данни: известието до потребителя не се записа", e);
    }
  }

  /** Редовете на изхода — за екрана. */
  public static List<String> lines(Outcome outcome) {
    return List.of(outcome.text.split("\n"));
  }

  private static void clear() {
    try {
      JPA.clear();
    } catch (Exception e) {
      LOG.warn("Демо данни: JPA.clear() — {}", e.getMessage());
    }
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
