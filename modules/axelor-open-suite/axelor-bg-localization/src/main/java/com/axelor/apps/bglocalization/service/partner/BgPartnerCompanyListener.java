package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * ДРУЖЕСТВОТО НА ПАРТНЬОРА — попълнено при създаване и неизменно след това, на ниво Hibernate, по
 * ВСЕКИ път.
 *
 * <p><b>При INSERT.</b> Празното {@code company} се попълва по {@link BgPartnerCompany#resolve}
 * (изричното дружество на контекста, после активното на потребителя). Няма ли от какво — отказ:
 * партньор без дружество не се създава.
 *
 * <p><b>При UPDATE.</b> {@code company} се сравнява със старото състояние по id; различно → отказ.
 * Отказва се и попълване на празното: заварените партньори (без дружество) ги попълва само
 * миграцията, с масов UPDATE, който не минава оттук.
 *
 * <p><b>⚠️ ЗАЩО ТУК, А НЕ В РЕПОЗИТОРИТО.</b> {@code Resource.save} (REST и UI) прави {@code
 * JPA.manage} → flush ПРЕДИ {@code repository.save}: новият партньор е вече вмъкнат, когато
 * веригата на репозиторито тръгне, а пазач там, който чете „записаното“, вижда новото. Освен това
 * партньори се записват и покрай репозиторито — каскада от родителя, {@code JPA.save} в услуги,
 * вносът. Слушателят ги хваща всичките. Образецът е {@code NapArtifactImmutabilityListener} (PR
 * 327).
 *
 * <p><b>Системният партньор на потребителя</b> ({@code nxSystem}) е единственото изключение: без
 * дружество, създаден само в пътя „нов потребител“ ({@link BgSystemPartner}); {@code nxSystem} не
 * се сменя, а влязъл потребител, който не е nyxa.admin, не го пипа.
 *
 * <p><b>Какво НЕ минава оттук:</b> масов JPQL UPDATE ({@code mass-update} от UI/REST) — отказва го
 * {@link BgPartnerRequestGuard}; и SQL извън Hibernate.
 */
public class BgPartnerCompanyListener implements PreInsertEventListener, PreUpdateEventListener {

  private static final long serialVersionUID = 1L;

  static final String COMPANY = "company";
  static final String SYSTEM = "nxSystem";

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (!(event.getEntity() instanceof Partner)) {
      return false;
    }
    Partner partner = (Partner) event.getEntity();
    String[] names = event.getPersister().getPropertyNames();
    int index = indexOf(names, COMPANY);
    Object[] state = event.getState();
    if (Boolean.TRUE.equals(state[indexOf(names, SYSTEM)])) {
      // Системният партньор на потребителя: без дружество и само по пътя „нов потребител“.
      if (state[index] != null) {
        throw new BgPartnerCompanyException(BgPartnerCompany.SYSTEM_WITH_COMPANY);
      }
      if (!BgSystemPartner.isCreating()) {
        throw new BgPartnerCompanyException(BgPartnerCompany.SYSTEM_OUTSIDE_NEW_USER);
      }
      return false;
    }
    if (state[index] != null) {
      return false;
    }
    Company company = BgPartnerCompany.resolve(partner);
    if (company == null) {
      throw new BgPartnerCompanyException(BgPartnerCompany.NO_COMPANY);
    }
    // И в състоянието (то отива в INSERT), и в обекта (за да го вижда кодът след записа).
    state[index] = company;
    partner.setCompany(company);
    return false;
  }

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (!(event.getEntity() instanceof Partner)) {
      return false;
    }
    Long id = ((Partner) event.getEntity()).getId();
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new BgPartnerCompanyException(
          "Промяна на партньор #" + id + " не може да се провери: старото състояние липсва.");
    }
    String[] names = event.getPersister().getPropertyNames();
    int index = indexOf(names, COMPANY);
    if (!BgPartnerCompany.same(event.getOldState()[index], event.getState()[index])) {
      throw new BgPartnerCompanyException(BgPartnerCompany.changed(id));
    }
    int system = indexOf(names, SYSTEM);
    boolean wasSystem = Boolean.TRUE.equals(event.getOldState()[system]);
    if (wasSystem != Boolean.TRUE.equals(event.getState()[system])) {
      throw new BgPartnerCompanyException(BgPartnerCompany.systemChanged(id));
    }
    if (systemChangeRefused(wasSystem, BgSystemPartner.isCreating(), writtenByNonAdmin())) {
      throw new BgPartnerCompanyException(BgPartnerCompany.systemAdminOnly(id));
    }
    return false;
  }

  /**
   * Отказва ли се промяна на системен партньор. Само nyxa.admin (или без потребител) го променя —
   * освен в самия път „нов потребител“: там веригата на партньора го записва втори път веднага след
   * вмъкването (PartnerAccountRepository: сметковото положение, после save), а пише влезлият, който
   * създава потребителя — и client.admin при покана от „Потребители“.
   */
  static boolean systemChangeRefused(boolean wasSystem, boolean creating, boolean byNonAdmin) {
    return wasSystem && !creating && byNonAdmin;
  }

  /**
   * Пише ли влязъл потребител, който не е nyxa.admin. Без потребител (стартови стъпки,
   * синхронизация) — не: там няма кой да е „не admin“, а каталогът така или иначе не стига дотам.
   */
  protected boolean writtenByNonAdmin() {
    User user = AuthUtils.getUser();
    return user != null && !AuthUtils.isAdmin(user);
  }

  static int indexOf(String[] names, String property) {
    for (int i = 0; i < names.length; i++) {
      if (property.equals(names[i])) {
        return i;
      }
    }
    // Генерираният Partner няма полето — домейнът на модула не е слят. Не е състояние, което да се
    // прескача: без полето правилото не важи никъде.
    throw new IllegalStateException("Partner has no '" + property + "' property");
  }
}
