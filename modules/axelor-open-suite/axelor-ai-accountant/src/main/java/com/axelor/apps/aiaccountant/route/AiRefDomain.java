package com.axelor.apps.aiaccountant.route;

import com.axelor.inject.Beans;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The domain a canonical reference ({@code Kind#id}) names.
 *
 * <h2>Why this is one class and not two switches</h2>
 *
 * Routing asks it of the records a user typed into their message; authorisation asks it of the
 * records a job was frozen against. Those were separate switches, and separate switches drift: the
 * moment {@code Invoice#} stopped being unambiguously a supplier invoice, one of them would have
 * learned that and the other would not, and the two halves of the same decision would disagree
 * about the same reference. So the mapping lives here, and both callers ask it.
 *
 * <h2>Two kinds of "no answer", and they are not interchangeable</h2>
 *
 * <ul>
 *   <li><b>An unknown kind</b> — {@code Widget#7}. Nothing here claims it, and nothing can be
 *       proved about it. That is what {@link #of} returning null means for such a ref.
 *   <li><b>A known kind whose row decides</b> — {@code Invoice#641} is a supplier invoice or a
 *       customer one depending on {@code operationTypeSelect}, and only the module that owns
 *       invoices can read that. When it cannot — the row is gone, two resolvers disagree, the
 *       injector is unavailable — {@link #of} also returns null, but the situation is the opposite
 *       one: this <em>is</em> a document with a domain, and the domain is unknown.
 * </ul>
 *
 * <p>{@link #rowDependent} tells the two apart, so a caller that must fail closed can. Collapsing
 * them is how a customer-invoice write slips into a supplier-invoice job: the guard reads "no
 * domain to compare" and waves it through, when what actually happened is that the comparison could
 * not be made.
 */
public final class AiRefDomain {

  private static final Logger LOG = LoggerFactory.getLogger(AiRefDomain.class);

  private AiRefDomain() {}

  /**
   * The domain this reference belongs to, or null when it cannot be settled.
   *
   * <p>See the class comment: null has two very different causes, and {@link #rowDependent} says
   * which one applies.
   */
  public static AiDomain of(String ref) {
    // A creation target names its own domain — Create:SUPPLIER_INVOICE:7 — and there is no row to
    // read it from, so it is answered before the Kind#id parsing below, which it is not shaped
    // like.
    com.axelor.apps.aiaccountant.context.AiCreationTarget.Parsed creation =
        com.axelor.apps.aiaccountant.context.AiCreationTarget.parse(ref);
    if (creation != null) {
      return creation.domain();
    }
    String kind = kindOf(ref);
    if (kind == null) {
      return null;
    }
    if (rowDependent(ref)) {
      try {
        return Beans.get(AiRecordDomainRegistry.class).domainOfRef(ref.trim());
      } catch (RuntimeException e) {
        // Routing and authorisation must not fail because a resolver or the injector did. The
        // caller decides what an unresolved row-dependent ref means for it.
        LOG.debug("AI Accountant: could not classify ref '{}': {}", ref, e.getMessage());
        return null;
      }
    }
    return DOMAIN_OF_KIND.get(kind);
  }

  /**
   * The domain an entity name decides on its own, or null when the class is not a complete answer —
   * an {@code Invoice} needs its row ({@link AiRecordDomainRegistry}), and an unknown model is
   * unclassified. Strings only: naming a class here adds no dependency on its module.
   */
  public static AiDomain ofModel(String model) {
    return model == null ? null : DOMAIN_OF_MODEL.get(model);
  }

  /**
   * Entity names whose class alone is the whole answer. Kept beside {@link #DOMAIN_OF_KIND} — it
   * used to be a separate switch in {@code AiRouteAdapter} — so the page path and the reference
   * path are read and changed together; {@code AiExplicitReferenceParserTest} fails if they
   * disagree.
   */
  static final Map<String, AiDomain> DOMAIN_OF_MODEL =
      Map.of(
          // An OCR upload is a supplier document by construction, whatever became of it.
          "com.axelor.apps.aiocr.db.AiOcrUpload", AiDomain.SUPPLIER_INVOICE,
          "com.axelor.apps.account.db.Move", AiDomain.JOURNAL_ENTRY,
          "com.axelor.apps.account.db.MoveLine", AiDomain.JOURNAL_ENTRY,
          "com.axelor.apps.bankpayment.db.BankStatement", AiDomain.BANK_STATEMENT,
          "com.axelor.apps.bankpayment.db.BankStatementLine", AiDomain.BANK_STATEMENT,
          "com.axelor.apps.base.db.Partner", AiDomain.PARTNER);

  /**
   * Reference kinds whose kind alone is the whole answer. {@code Upload} is the OCR tools' short
   * form of {@code AiOcrUpload}. {@code BankStatementLine} is deliberately absent: nothing produces
   * that reference, and an unknown kind must stay unknown to the authorisation path.
   */
  static final Map<String, AiDomain> DOMAIN_OF_KIND =
      Map.of(
          "Upload", AiDomain.SUPPLIER_INVOICE,
          "AiOcrUpload", AiDomain.SUPPLIER_INVOICE,
          "Move", AiDomain.JOURNAL_ENTRY,
          "MoveLine", AiDomain.JOURNAL_ENTRY,
          "BankStatement", AiDomain.BANK_STATEMENT,
          "Partner", AiDomain.PARTNER);

  /**
   * Whether this reference's domain depends on the row rather than on its kind.
   *
   * <p>{@code Invoice#} is the only such kind today: a supplier invoice and a customer invoice are
   * the same entity, told apart by {@code operationTypeSelect} and by nothing else.
   */
  public static boolean rowDependent(String ref) {
    return "Invoice".equals(kindOf(ref));
  }

  /** The kind part of {@code Kind#id}, or null when there is nothing to read. */
  private static String kindOf(String ref) {
    if (ref == null || ref.trim().isEmpty()) {
      return null;
    }
    String trimmed = ref.trim();
    int hash = trimmed.lastIndexOf('#');
    return hash <= 0 ? trimmed : trimmed.substring(0, hash);
  }
}
