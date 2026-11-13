package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;

/**
 * The destination of a change that creates a record, named before that record exists.
 *
 * <h2>Why creation needed a reference of its own</h2>
 *
 * Every authorisation in this module is "may this turn write to THIS record". A creation has no
 * record yet, so {@code create_supplier_invoice}, {@code create_customer_invoice} and {@code
 * create_move} all answered {@code null} to {@code targetKey} — and {@code
 * AiWriteAuthorisation.checkTargets} refuses a null target outright once a job has a closed set,
 * and refuses the turn outright when it has none. Both branches refused, so a complete, explicit
 * instruction — «създай доставна фактура от Лидл за 240 лв» with nothing open on screen — could not
 * be carried out at all, whatever the user said.
 *
 * <p>{@code manage_supplier(create)} had already solved this for partners, with {@code
 * PartnerCreate:<companyId>:<eik>}: a synthetic reference naming the identity the creation is for.
 * This is the same idea for documents that have no business key before they exist — an invoice gets
 * its number from Axelor, a move its sequence — so what it names is the <em>kind</em> of document
 * and the company: {@code Create:SUPPLIER_INVOICE:7}.
 *
 * <h2>What it authorises, and what it cannot</h2>
 *
 * It matches no existing record: {@link AiTargetRegistry#sameTarget} compares canonical forms, and
 * nothing else in the system canonicalises to this string. So a job frozen on it may create a
 * document of that domain in that company and may do nothing else — it cannot post an invoice that
 * is already there, cannot edit one, and cannot reach another company's books. The permission is
 * still Axelor's: {@link AiCreationTargetIdentity} asks {@code JpaSecurity} for the create right on
 * the entity itself.
 *
 * <p>It is frozen by {@code AiWorkflowController} from the route's own task — never from a tool
 * argument — exactly as every other target is.
 */
public final class AiCreationTarget {

  static final String PREFIX = "Create:";

  private AiCreationTarget() {}

  /**
   * {@code Create:<DOMAIN>:<companyId>} for the active company, or null outside one.
   *
   * @param domain the kind of document that would be created
   */
  public static String ref(AiDomain domain) {
    Long companyId = AiCompanyScope.companyIdOrNull();
    return ref(domain, companyId);
  }

  /** As above, for a stated company. Null when either half is missing. */
  public static String ref(AiDomain domain, Long companyId) {
    if (domain == null || companyId == null) {
      return null;
    }
    return PREFIX + domain.name() + ':' + companyId;
  }

  /**
   * The document a creation task would produce, or null when the task creates nothing.
   *
   * <p>Read from the task rather than from the tool, because the job's set is frozen before any
   * tool runs — that ordering is the whole point of the authorisation, see {@code
   * AiWorkflowController.authoriseTargets}.
   */
  public static AiDomain creates(AiTask task) {
    if (task == null) {
      return null;
    }
    switch (task) {
      case CREATE_SUPPLIER_INVOICE:
        return AiDomain.SUPPLIER_INVOICE;
      case CREATE_CUSTOMER_INVOICE:
        return AiDomain.CUSTOMER_INVOICE;
      case CREATE_JOURNAL_ENTRY:
        return AiDomain.JOURNAL_ENTRY;
      default:
        // MAINTAIN_SUPPLIER creates too, but it already has a better reference of its own —
        // PartnerCreate:<company>:<eik> names the exact identity its own duplicate check enforces.
        return null;
    }
  }

  /** Parses {@code "Create:<DOMAIN>:<companyId>"}, or null for anything else or malformed. */
  public static Parsed parse(String ref) {
    if (ref == null || !ref.trim().startsWith(PREFIX)) {
      return null;
    }
    String rest = ref.trim().substring(PREFIX.length());
    int sep = rest.indexOf(':');
    if (sep <= 0 || sep == rest.length() - 1) {
      return null;
    }
    AiDomain domain;
    try {
      domain = AiDomain.valueOf(rest.substring(0, sep).trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    Long companyId;
    try {
      companyId = Long.parseLong(rest.substring(sep + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    return new Parsed(domain, companyId);
  }

  /** What a creation reference names. */
  public static final class Parsed {

    private final AiDomain domain;
    private final Long companyId;

    Parsed(AiDomain domain, Long companyId) {
      this.domain = domain;
      this.companyId = companyId;
    }

    public AiDomain domain() {
      return domain;
    }

    public Long companyId() {
      return companyId;
    }
  }
}
