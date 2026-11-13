package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ЧИЙ Е НОВИЯТ ПАРТНЬОР — правилото на едно място.
 *
 * <p>Всеки партньор е на точно едно дружество ({@code Partner.company}) и номерът му идва от
 * поредицата {@code partner} на това дружество. Обща поредица и резервен брояч няма.
 *
 * <p><b>Редът при създаване</b> ({@link #choose}):
 *
 * <ol>
 *   <li>дружеството, вече сложено на записа от кода, който го създава (вносът на Microinvest);
 *   <li>изричното дружество на контекста: {@link BgExplicitCompany} (внос, задачи), иначе
 *       дружеството, обявено от системния контекст на AI ({@code AiExecutionContext.callAsSystem});
 *   <li>активното дружество на потребителя — екранът и REST;
 *   <li>нищо от горните → отказ. Партньор без дружество не се създава.
 * </ol>
 *
 * <p>Клиентът (REST/UI) не избира дружеството: различно от активното се отказва в {@link
 * BgPartnerRequestGuard}. Смяната след създаването се отказва в {@link BgPartnerCompanyListener}.
 */
public final class BgPartnerCompany {

  static final String NO_COMPANY =
      "Партньорът няма дружество: няма активно дружество на потребителя и няма изрично дружество на"
          + " контекста. Изберете активно дружество (или за вноса и задачите — обявете дружеството)"
          + " и опитайте пак.";

  static final String SYSTEM_WITH_COMPANY =
      "Системният партньор (на потребител) е без дружество: той не е лице на нито едно дружество.";

  static final String SYSTEM_OUTSIDE_NEW_USER =
      "Партньор без дружество (системен) се създава само заедно с нов потребител. Всеки друг"
          + " партньор е на дружество.";

  private BgPartnerCompany() {}

  static String systemChanged(Long partnerId) {
    return "Партньор #"
        + partnerId
        + ": това дали е системен (на потребител) не се сменя след създаването му.";
  }

  static String systemAdminOnly(Long partnerId) {
    return "Партньор #"
        + partnerId
        + " е системен (на потребител) — вижда и променя го само nyxa.admin.";
  }

  public static String companyPartnerElsewhere(Company company) {
    return "Партньорът на дружество "
        + label(company)
        + " е в самото дружество: изберете негов партньор или оставете полето празно — при"
        + " създаването на дружеството партньорът му се създава сам.";
  }

  public static final String COMPANY_PARTNER_IS_SYSTEM =
      "Системният партньор на потребител не може да е партньор на дружество.";

  static final String NESTED_COMPANY_PARTNER =
      "Партньорът на ново дружество се създава от сървъра СЛЕД дружеството (в самото дружество)."
          + " Запишете дружеството без нов партньор — партньорът му се създава сам.";

  /**
   * Първото налично по реда на правилото; {@code null} — няма от какво. По-слабите източници се
   * питат само ако по-силните са празни (потребителят — едва накрая).
   */
  static Company choose(Company onRecord, Supplier<Company> explicit, Supplier<Company> active) {
    if (onRecord != null) {
      return onRecord;
    }
    Company declared = explicit.get();
    return declared != null ? declared : active.get();
  }

  /** Дружеството, което получава нов партньор сега; {@code null} — няма от какво. */
  public static Company resolve(Partner partner) {
    return choose(
        partner == null ? null : partner.getCompany(),
        BgPartnerCompany::explicitCompany,
        BgPartnerCompany::activeCompanyOfUser);
  }

  /**
   * Изрично обявеното дружество: {@link BgExplicitCompany}, иначе това на системния контекст на AI
   * (там потребител няма, а {@code AiCompanyScope} вече ограничава работата до това дружество).
   * Препратка без заявка — слушателят вика това по време на flush.
   */
  static Company explicitCompany() {
    Company explicit = BgExplicitCompany.current();
    if (explicit != null) {
      return explicit;
    }
    Long aiCompanyId = AiExecutionContext.systemCompanyId();
    return aiCompanyId == null ? null : JPA.em().getReference(Company.class, aiCompanyId);
  }

  /** Активното дружество на влезлия потребител; {@code null} без потребител или без избор. */
  static Company activeCompanyOfUser() {
    User user = AuthUtils.getUser();
    return user == null ? null : user.getActiveCompany();
  }

  /** Един и същ ред ли са — по id: друг екземпляр (или proxy) на същото дружество не е смяна. */
  public static boolean same(Object before, Object after) {
    return Objects.equals(idOf(before), idOf(after));
  }

  static Long idOf(Object value) {
    return value instanceof Model ? ((Model) value).getId() : null;
  }

  static String changed(Long partnerId) {
    return "Дружеството на партньор #"
        + partnerId
        + " не се сменя след създаването му: всеки партньор е на точно едно дружество.";
  }

  static String noSequence(Company company) {
    return "Дружество "
        + label(company)
        + " няма поредица за номерата на партньорите (код „partner“). Създава се сама при създаване"
        + " на дружеството и при стартиране на приложението; обща поредица за всички дружества"
        + " няма. Рестартирайте приложението или я създайте в Поредици.";
  }

  static String foreignSequence(Company owner, Company requested) {
    return "Номерът на партньор от дружество "
        + label(owner)
        + " се взема от неговата поредица, не от поредицата на "
        + label(requested)
        + ".";
  }

  static String legacyWithoutCompany(Long partnerId) {
    return "Партньор #"
        + partnerId
        + " е заварен без дружество и не получава номер, докато миграцията на партньорите не му"
        + " определи дружество: обща поредица за всички дружества няма.";
  }

  static String clientCompany(Company active) {
    return active == null
        ? "Партньорът получава активното дружество на потребителя, а то не е избрано. Изберете"
            + " активно дружество и опитайте пак."
        : "Нов партньор се създава в активното дружество ("
            + label(active)
            + "); друго дружество не може да се подаде.";
  }

  private static String label(Company company) {
    if (company == null) {
      return "(няма)";
    }
    return company.getCode() != null ? company.getCode() : "#" + company.getId();
  }
}
