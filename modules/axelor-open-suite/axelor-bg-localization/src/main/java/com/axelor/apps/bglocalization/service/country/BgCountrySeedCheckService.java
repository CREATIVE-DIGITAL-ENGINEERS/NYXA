/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service.country;

import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.service.country.BgCountrySeedCheck.CountryRow;
import com.axelor.apps.bglocalization.service.country.BgCountrySeedCheck.Snapshot;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.mail.db.MailFlags;
import com.axelor.mail.db.MailMessage;
import com.axelor.mail.db.repo.MailFollowerRepository;
import com.axelor.mail.db.repo.MailMessageRepository;
import com.axelor.studio.db.AppBase;
import com.google.inject.Singleton;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ПРОВЕРКАТА ПРИ СТАРТ на България в номенклатурата — виж {@link BgCountrySeedCheck} за правилото и
 * случката. Само чете държавите; пише единствено известието до администраторите.
 *
 * <p><b>Не спира приложението.</b> Повредената номенклатура не е причина системата да не тръгне —
 * тя е грешка в данните, която се поправя с файл през пайплайна за писане. Затова: ERROR в лога на
 * всеки старт, докато не е поправено, и съобщение във „Входящи“ на администраторите.
 *
 * <p><b>Известието.</b> Съобщение на записа на държавата (стандартното „Входящи“ на платформата
 * показва съобщенията на записите, които потребителят следва), затова всеки администратор става
 * последовател на този запис. ⚠️ Тип {@code comment}, НЕ {@code notification}: за notification
 * {@code MailMessageRepository#save} праща имейл на всички последователи — тоест при всеки boot.
 * Същият текст не се повтаря: ново съобщение се пише само когато нарушенията са други.
 */
@Singleton
public class BgCountrySeedCheckService {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static final String SUBJECT = "Номенклатурата на държавите: България";

  /** Групата и ролята на техническия администратор. */
  static final String ADMIN_GROUP = "admins";

  static final String ADMIN_ROLE = "nyxa.admin";

  /** Чете, съди, записва в лога и известява. Никога не хвърля. */
  public void run() {
    try {
      Snapshot snapshot = snapshot();
      String summary = BgCountrySeedCheck.summary(BgCountrySeedCheck.violations(snapshot));
      if (summary != null) {
        log.error(summary);
        notifyAdmins(summary, target(snapshot));
      }
      String warning = BgCountrySeedCheck.defaultPartnerLocalizationWarning(defaultLocalization());
      if (warning != null) {
        log.warn(warning);
      }
    } catch (Exception e) {
      log.error("Проверката на България в номенклатурата на държавите не мина.", e);
    }
  }

  public Snapshot snapshot() {
    List<CountryRow> rows = new ArrayList<>();
    for (Object[] r :
        JPA.em()
            .createQuery(
                "SELECT c.id, c.importId, c.alpha2Code, c.alpha3Code, r.importId FROM Country c"
                    + " LEFT JOIN c.registrationNumberTemplate r"
                    + " WHERE upper(c.alpha2Code) IN (:bg, :la) OR r.importId = :rnt"
                    + " ORDER BY c.id",
                Object[].class)
            .setParameter("bg", BgCountrySeedCheck.BG_ALPHA2)
            .setParameter("la", BgCountrySeedCheck.LA_ALPHA2)
            .setParameter("rnt", BgCountrySeedCheck.RNT_BG)
            .getResultList()) {
      rows.add(
          new CountryRow((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4]));
    }
    List<Long> bgLocalization =
        JPA.em()
            .createQuery(
                "SELECT c.id FROM Localization l LEFT JOIN l.country c WHERE l.code = :code",
                Long.class)
            .setParameter("code", BgCountrySeedCheck.BG_LOCALIZATION)
            .getResultList();
    return new Snapshot(
        rows, !bgLocalization.isEmpty(), bgLocalization.isEmpty() ? null : bgLocalization.get(0));
  }

  private String defaultLocalization() {
    AppBase appBase = Beans.get(AppBaseService.class).getAppBase();
    Localization localization = appBase == null ? null : appBase.getDefaultPartnerLocalization();
    return localization == null ? null : localization.getCode();
  }

  /** Записът, на който виси съобщението: България, иначе редът с RNT-BG, иначе първият. */
  private static Long target(Snapshot snapshot) {
    Long fallback = null;
    for (CountryRow row : snapshot.countries) {
      if (BgCountrySeedCheck.BG_ALPHA2.equalsIgnoreCase(String.valueOf(row.alpha2).trim())) {
        return row.id;
      }
      if (fallback == null || BgCountrySeedCheck.RNT_BG.equals(row.rntImportId)) {
        fallback = row.id;
      }
    }
    return fallback;
  }

  void notifyAdmins(String summary, Long countryId) {
    if (countryId == null) {
      return;
    }
    JPA.runInTransaction(
        () -> {
          Country country = JPA.find(Country.class, countryId);
          if (country == null) {
            return;
          }
          String model = Country.class.getName();
          String body = "<p>" + escape(summary) + "</p>";
          MailMessageRepository messages = Beans.get(MailMessageRepository.class);
          boolean alreadyPosted =
              messages
                      .all()
                      .filter(
                          "self.relatedModel = :model AND self.relatedId = :id"
                              + " AND self.subject = :subject AND self.body = :body")
                      .bind("model", model)
                      .bind("id", countryId)
                      .bind("subject", SUBJECT)
                      .bind("body", body)
                      .count()
                  > 0;
          if (alreadyPosted) {
            return;
          }

          List<User> admins = admins();
          MailFollowerRepository followers = Beans.get(MailFollowerRepository.class);
          for (User admin : admins) {
            followers.follow(country, admin);
          }

          MailMessage message = new MailMessage();
          message.setType("comment");
          message.setSubject(SUBJECT);
          message.setBody(body);
          message.setRelatedModel(model);
          message.setRelatedId(countryId);
          message.setRelatedName(country.getName());
          message = messages.save(message);

          // Второ съобщение на същия запис става отговор на първото, а „Входящи“ показва само
          // корена — затова коренът се връща като непрочетен за администраторите.
          MailMessage root = message.getRoot();
          if (root != null) {
            for (User admin : admins) {
              JPA.all(MailFlags.class)
                  .filter("self.message = :m AND self.user = :u")
                  .bind("m", root)
                  .bind("u", admin)
                  .update("isRead", false);
            }
          }
          log.info("Известие до {} администратори за номенклатурата на държавите.", admins.size());
        });
  }

  private static List<User> admins() {
    return JPA.em()
        .createQuery(
            "SELECT DISTINCT u FROM User u LEFT JOIN u.group g LEFT JOIN u.roles r"
                + " LEFT JOIN g.roles gr"
                + " WHERE (u.blocked IS NULL OR u.blocked = false)"
                + " AND (g.code = :group OR r.name = :role OR gr.name = :role)",
            User.class)
        .setParameter("group", ADMIN_GROUP)
        .setParameter("role", ADMIN_ROLE)
        .getResultList();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
