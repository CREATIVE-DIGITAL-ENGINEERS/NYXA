package com.axelor.apps.nyxasecurity.gate;

import com.axelor.i18n.I18n;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.persistence.PersistenceException;

/**
 * Every refusal nyxa-security shows the user, and the names of the catalogue roles (the role
 * catalogue section 2) that a refusal names.
 *
 * <p>The keys are English; the Bulgarian text is in {@code i18n/messages_bg.csv} of this module.
 * Axelor's {@code ResponseInterceptor} shows an exception's message as it is, so the message is
 * translated here, where it is made, not where it is shown. The description of an action ("generate
 * a VAT return") is a key of the module that names it and is translated in that module's {@code
 * messages_bg.csv}.
 *
 * <p>A refusal names a role by its translated name and its code, so the user can tell the
 * administrator exactly what is missing: „Счетоводство — запис“ (accounting.write).
 */
public final class Refusals {

  private Refusals() {}

  public static final String NOT_LOGGED_IN = /*$$(*/ "Not logged in." /*)*/;

  /** The action, then the roles that would allow it. */
  public static final String NOT_ALLOWED =
      /*$$(*/ "You are not allowed to %s. Required role: %s. Contact your administrator." /*)*/;

  /** Joins two role names; applied left to right for three or more. */
  public static final String ROLE_OR_ROLE = /*$$(*/ "%s or %s" /*)*/;

  /** A role's name, then its code; the same in every language, so not a translation key. */
  private static final String ROLE_NAME_AND_CODE = "„%s“ (%s)";

  public static final String ONLY_ACTIVE_COMPANY =
      /*$$(*/ "You can only work on your active company." /*)*/;

  public static final String ONLY_OWN_FILES =
      /*$$(*/ "You can only use files you uploaded yourself." /*)*/;

  public static final String NOT_BY_PREPARER =
      /*$$(*/ "You can't %s yourself: it must be done by someone other than its preparer." /*)*/;

  public static final String ONLY_ASSIGNED_COMPANY =
      /*$$(*/ "You can only switch to a company you are assigned to (user %s)." /*)*/;

  public static final String ACTIVE_COMPANY_STAYS_ASSIGNED =
      /*$$(*/ "The active company of user %s must stay one of their assigned companies." /*)*/;

  public static final String OWN_PROFILE_FIELD =
      /*$$(*/ "You can't change '%s' on your own user profile." /*)*/;

  public static final String OTHER_USER_FIELD = /*$$(*/ "You can't change user %s ('%s')." /*)*/;

  public static final String APP_IMPORT_UNAVAILABLE =
      /*$$(*/ "Importing demo data or roles is not available in this application." /*)*/;

  /** The field, then the user. */
  public static final String IDENTITY_PROVIDER_FIELD =
      /*$$(*/ "'%s' comes from the identity provider and can't be changed here (user %s)." /*)*/;

  /** The role that may assign companies. */
  public static final String ONLY_ASSIGNER =
      /*$$(*/ "Only %s can assign companies to users. Contact your administrator." /*)*/;

  public static final String CHOOSE_USER = /*$$(*/ "Choose a user." /*)*/;

  public static final String ONLY_STAFF =
      /*$$(*/ "Only staff users can be assigned this way; administrators are managed separately." /*)*/;

  public static final String CHOOSE_COMPANY = /*$$(*/ "Choose at least one company." /*)*/;

  public static final String NOTHING_TO_CHANGE = /*$$(*/ "Nothing to change." /*)*/;

  public static final String ASSIGNED = /*$$(*/ "Assigned: %s" /*)*/;

  public static final String REMOVED = /*$$(*/ "Removed: %s" /*)*/;

  public static final String AUTO_POSTING_ONLY_STOCK_ISSUES =
      /*$$(*/ "The automatic posting user only posts stock issues in the company's stock issue journal; this entry cannot be validated by it." /*)*/;

  public static final String NO_WRITE_ON_MOVES =
      /*$$(*/ "You do not have write access to accounting entries, so you cannot validate this one." /*)*/;

  /** The document the entry is posted from. */
  public static final String NO_WRITE_ON_SOURCE =
      /*$$(*/ "You do not have write access to the %s this entry is posted from, so you cannot post it." /*)*/;

  /**
   * The catalogue roles in catalogue order, each with its English name. The order is the order in
   * which a refusal lists them: the narrow role first, {@code all.*} and {@code client.admin} last.
   */
  private static final Map<String, String> ROLE_NAMES = new LinkedHashMap<>();

  static {
    area("accounting", /*$$(*/ "Accounting — read" /*)*/, /*$$(*/ "Accounting — write" /*)*/);
    area("invoicing", /*$$(*/ "Invoicing — read" /*)*/, /*$$(*/ "Invoicing — write" /*)*/);
    area(
        "accounting.nap",
        /*$$(*/ "Tax filings (НАП) — read" /*)*/,
        /*$$(*/ "Tax filings (НАП) — write" /*)*/);
    area(
        "accounting.ai",
        /*$$(*/ "AI Accountant — read" /*)*/,
        /*$$(*/ "AI Accountant — write" /*)*/);
    area("hr", /*$$(*/ "Human resources — read" /*)*/, /*$$(*/ "Human resources — write" /*)*/);
    area("hr.payroll", /*$$(*/ "Payroll — read" /*)*/, /*$$(*/ "Payroll — write" /*)*/);
    area(
        "hr.noi",
        /*$$(*/ "Social security (НОИ) — read" /*)*/,
        /*$$(*/ "Social security (НОИ) — write" /*)*/);
    area(
        "budget",
        /*$$(*/ "Budget management — read" /*)*/,
        /*$$(*/ "Budget management — write" /*)*/);
    area("cash", /*$$(*/ "Cash management — read" /*)*/, /*$$(*/ "Cash management — write" /*)*/);
    area("contracts", /*$$(*/ "Contracts — read" /*)*/, /*$$(*/ "Contracts — write" /*)*/);
    area("crm", /*$$(*/ "CRM — read" /*)*/, /*$$(*/ "CRM — write" /*)*/);
    area("sales", /*$$(*/ "Sales — read" /*)*/, /*$$(*/ "Sales — write" /*)*/);
    area("purchases", /*$$(*/ "Purchases — read" /*)*/, /*$$(*/ "Purchases — write" /*)*/);
    area(
        "stock", /*$$(*/ "Stock management — read" /*)*/, /*$$(*/ "Stock management — write" /*)*/);
    area(
        "manufacturing",
        /*$$(*/ "Manufacturing — read" /*)*/,
        /*$$(*/ "Manufacturing — write" /*)*/);
    area(
        "projects",
        /*$$(*/ "Project management — read" /*)*/,
        /*$$(*/ "Project management — write" /*)*/);
    area("helpdesk", /*$$(*/ "Helpdesk — read" /*)*/, /*$$(*/ "Helpdesk — write" /*)*/);
    area("maintenance", /*$$(*/ "Maintenance — read" /*)*/, /*$$(*/ "Maintenance — write" /*)*/);
    area(
        "intervention", /*$$(*/
        "Interventions — read" /*)*/, /*$$(*/
        "Interventions — write" /*)*/);
    area(
        "assets",
        /*$$(*/ "Assets management — read" /*)*/,
        /*$$(*/ "Assets management — write" /*)*/);
    area("quality", /*$$(*/ "Quality — read" /*)*/, /*$$(*/ "Quality — write" /*)*/);
    area("marketing", /*$$(*/ "Marketing — read" /*)*/, /*$$(*/ "Marketing — write" /*)*/);
    area("fleet", /*$$(*/ "Fleet — read" /*)*/, /*$$(*/ "Fleet — write" /*)*/);
    area("gdpr", /*$$(*/ "GDPR — read" /*)*/, /*$$(*/ "GDPR — write" /*)*/);
    area("all", /*$$(*/ "All areas — read" /*)*/, /*$$(*/ "All areas — write" /*)*/);
    ROLE_NAMES.put("client.admin", /*$$(*/ "Client administrator" /*)*/);
    ROLE_NAMES.put("nyxa.admin", /*$$(*/ "Technical administrator" /*)*/);
  }

  private static void area(String area, String readName, String writeName) {
    ROLE_NAMES.put(area + ".read", readName);
    ROLE_NAMES.put(area + ".write", writeName);
  }

  /** Role code → English name, in the order refusals list them. */
  public static Map<String, String> roleNames() {
    return Collections.unmodifiableMap(ROLE_NAMES);
  }

  /** The message for {@code key}, translated for the current user, with its arguments. */
  public static String tr(String key, Object... args) {
    return render(I18n::get, key, args);
  }

  /** Same as {@link #tr}, with the translation passed in (tests render Bulgarian without a DB). */
  public static String render(Function<String, String> translate, String key, Object... args) {
    String text = translate.apply(key);
    return args.length == 0 ? text : String.format(text, args);
  }

  /** „Счетоводство — запис“ (accounting.write); an unknown role is shown by its code alone. */
  public static String role(Function<String, String> translate, String code) {
    String name = ROLE_NAMES.get(code);
    return name == null ? code : String.format(ROLE_NAME_AND_CODE, translate.apply(name), code);
  }

  /** The roles that would allow an action, in catalogue order, joined with "or". */
  public static String roles(Function<String, String> translate, Collection<String> codes) {
    List<String> ordered = new ArrayList<>(codes);
    List<String> catalogue = new ArrayList<>(ROLE_NAMES.keySet());
    ordered.sort(
        (a, b) -> {
          int ia = catalogue.indexOf(a);
          int ib = catalogue.indexOf(b);
          if (ia < 0 && ib < 0) {
            return a.compareTo(b);
          }
          return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
        });
    String joined = null;
    for (String code : ordered) {
      String named = role(translate, code);
      joined = joined == null ? named : render(translate, ROLE_OR_ROLE, joined, named);
    }
    return joined == null ? "" : joined;
  }

  /** The refusal of {@code CapabilityGate.requireRole}. */
  public static String notAllowed(
      Function<String, String> translate, String action, Collection<String> anyOf) {
    return render(translate, NOT_ALLOWED, translate.apply(action), roles(translate, anyOf));
  }

  /** Same, for the current user. */
  public static String notAllowed(String action, Collection<String> anyOf) {
    return notAllowed(I18n::get, action, anyOf);
  }

  /** An argument of a {@link Refusal} that is itself a translation key ("Invoice"). */
  public static final class Term {

    private final String key;

    public Term(String key) {
      this.key = key;
    }

    @Override
    public String toString() {
      return key;
    }
  }

  /**
   * A refusal thrown from a Hibernate listener during a flush. The message is translated only when
   * it is read, after the flush has failed: translating loads the messages with a query, which has
   * no place in the middle of a flush.
   */
  public static final class Refusal extends PersistenceException {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final transient Object[] args;

    public Refusal(String key, Object... args) {
      super(key);
      this.key = key;
      this.args = args;
    }

    public String getKey() {
      return key;
    }

    @Override
    public String getMessage() {
      try {
        return message(I18n::get);
      } catch (RuntimeException e) {
        // No database to translate from: the English text is still the right refusal.
        return message(k -> k);
      }
    }

    /** The message with {@code translate} applied to the key and to every {@link Term}. */
    public String message(Function<String, String> translate) {
      Object[] shown = args == null ? new Object[0] : args.clone();
      for (int i = 0; i < shown.length; i++) {
        if (shown[i] instanceof Term) {
          shown[i] = translate.apply(((Term) shown[i]).key);
        }
      }
      return render(translate, key, shown);
    }

    @Override
    public String getLocalizedMessage() {
      return getMessage();
    }
  }
}
