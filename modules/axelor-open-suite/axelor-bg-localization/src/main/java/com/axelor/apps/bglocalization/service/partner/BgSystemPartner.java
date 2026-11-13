package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.auth.db.User;
import java.util.function.Supplier;

/**
 * СИСТЕМНИЯТ ПАРТНЬОР НА ПОТРЕБИТЕЛЯ — единственият партньор без дружество.
 *
 * <p>Потребителят на Nyxa не е лице на нито едно дружество, затова партньорът му е системен: {@code
 * nxSystem = true}, {@code company} празно, вижда и пипа го само nyxa.admin. Създава се единствено
 * по пътя „нов потребител“ ({@code BgUserRepository}) и само вътре в {@link #creating}; всеки друг
 * опит за партньор без дружество — или за системен партньор — {@link BgPartnerCompanyListener}
 * отказва.
 *
 * <p><b>Номерът</b> е {@code SYS-<код на потребителя>}: поредиците са по дружество, а системният
 * партньор няма дружество — общ брояч няма. Кодът на потребителя е уникален, тоест и номерът е;
 * заварените без дружество пази частичният индекс.
 */
public final class BgSystemPartner {

  public static final String SEQ_PREFIX = "SYS-";

  private static final ThreadLocal<Boolean> CREATING = new ThreadLocal<>();

  private BgSystemPartner() {}

  /** Вътре ли сме в пътя „нов потребител“. */
  static boolean isCreating() {
    return Boolean.TRUE.equals(CREATING.get());
  }

  /** Изпълнява {@code work} като пътя „нов потребител“ — само там системен партньор е позволен. */
  static <T> T creating(Supplier<T> work) {
    Boolean previous = CREATING.get();
    CREATING.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CREATING.remove();
      } else {
        CREATING.set(previous);
      }
    }
  }

  /** Системният партньор на {@code user} — само строи, не записва. */
  public static Partner newFor(User user) {
    Partner partner = new Partner();
    partner.setNxSystem(true);
    partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    partner.setIsContact(false);
    partner.setName(user.getName() != null ? user.getName() : user.getCode());
    partner.setPartnerSeq(SEQ_PREFIX + user.getCode());
    partner.setLinkedUser(user);
    return partner;
  }
}
