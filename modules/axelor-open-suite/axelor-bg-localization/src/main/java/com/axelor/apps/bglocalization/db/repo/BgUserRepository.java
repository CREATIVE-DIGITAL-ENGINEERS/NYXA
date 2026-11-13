package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.partner.BgJustInserted;
import com.axelor.apps.bglocalization.service.partner.BgSystemPartnerService;
import com.axelor.apps.hr.db.repo.UserHRRepository;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;
import com.google.inject.servlet.RequestScoper;
import com.google.inject.servlet.ServletScopes;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Опашката на веригата за потребителя (Base → HR → тук): НОВ ПОТРЕБИТЕЛ ПОЛУЧАВА СИСТЕМЕН ПАРТНЬОР.
 *
 * <p>Партньорите на потребителите са системни — без дружество, само за nyxa.admin. Това е
 * единственият път, по който партньор без дружество е позволен ({@link
 * com.axelor.apps.bglocalization.service.partner.BgSystemPartner}). Потребител, който вече има
 * партньор, не се пипа; заварените потребители — също.
 *
 * <p>„Нов“ се познава по {@link BgJustInserted}, не по id: по пътя на екрана записът вече е
 * вмъкнат, когато тази верига тръгне.
 */
public class BgUserRepository extends UserHRRepository {

  @Override
  public User save(User user) {
    boolean isNew = BgJustInserted.consume(user);
    User saved = super.save(user);
    if (isNew && saved != null && saved.getPartner() == null) {
      Partner partner =
          inRequestScope(() -> Beans.get(BgSystemPartnerService.class).createFor(saved));
      saved.setPartner(partner);
    }
    return saved;
  }

  /**
   * {@code work} в обхват на заявка, и когато заявка няма. Първите потребители на нова база се
   * създават при старта (App Install), извън HTTP заявка, а веригата на {@link
   * BgSystemPartnerService} (партньорските хранилища → сметките на партньора) стига до {@code
   * PaymentModeServiceImpl}, който е {@code @RequestScoped}: без обхват Guice не може да я създаде
   * и инсталацията на приложенията пада. Отказът идва при създаването на услугата, преди да е
   * свършено каквото и да е, затова вторият опит в отворен обхват е безопасен. В заявка (екранът
   * „Потребители“, входът) — първият опит минава, както досега.
   */
  static <T> T inRequestScope(Supplier<T> work) {
    try {
      return work.get();
    } catch (ProvisionException e) {
      if (!outOfScope(e)) {
        throw e;
      }
      RequestScoper scope = ServletScopes.scopeRequest(Collections.emptyMap());
      try (RequestScoper.CloseableScope ignored = scope.open()) {
        return work.get();
      }
    }
  }

  private static boolean outOfScope(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof OutOfScopeException) {
        return true;
      }
    }
    return false;
  }
}
