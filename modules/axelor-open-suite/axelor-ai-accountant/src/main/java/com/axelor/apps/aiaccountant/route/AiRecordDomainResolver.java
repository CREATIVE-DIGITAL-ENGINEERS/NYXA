package com.axelor.apps.aiaccountant.route;

/**
 * Says which {@link AiDomain} owns a model or a reference.
 *
 * <h2>Why this had to become an extension point</h2>
 *
 * Two hardcoded {@code switch} statements used to answer this — one on the entity class name in
 * {@code AiRouteAdapter}, one on the reference prefix in {@code AiWriteAuthorisation} — both living
 * in this module and both naming classes that belong to other ones. That worked while a model name
 * was a complete answer, and stopped the moment two domains shared an entity: a supplier invoice
 * and a customer invoice are both {@code com.axelor.apps.account.db.Invoice}, told apart only by
 * {@code operationTypeSelect}, which is a fact about the row rather than about the class.
 *
 * <p>Answering that needs to read the record, and reading an {@code Invoice} needs {@code
 * axelor-account}, which this module deliberately does not depend on. So the question is asked of
 * whichever module already owns the document, and this module keeps only the vocabulary.
 *
 * <h2>One owner per model and per reference kind</h2>
 *
 * Implementations must return null for anything they do not own, and exactly one implementation may
 * own any given model or reference kind. {@link AiRecordDomainRegistry} does not pick a winner when
 * two disagree — it refuses, and the turn proceeds as though the domain were unknown. Two resolvers
 * quietly competing for the same record is the failure this design exists to make impossible, and a
 * silent scan-order-dependent answer is worse than no answer.
 */
public interface AiRecordDomainResolver {

  /**
   * The domain that owns this exact entity name, or null when this resolver does not own the model.
   *
   * @param model the fully-qualified entity name the client reported, never null when called
   * @param recordId the open record's id, or null when the page names a model but no record yet — a
   *     resolver whose answer depends on the row must return null rather than guess in that case
   */
  AiDomain domainOfModel(String model, Long recordId);

  /**
   * The domain that owns this canonical reference ({@code Kind#id}), or null when this resolver
   * does not own that kind.
   *
   * <p>Defaulted because a resolver may legitimately answer for a model without answering for a
   * reference — the page context and the authorisation path ask different questions.
   */
  default AiDomain domainOfRef(String ref) {
    return null;
  }
}
