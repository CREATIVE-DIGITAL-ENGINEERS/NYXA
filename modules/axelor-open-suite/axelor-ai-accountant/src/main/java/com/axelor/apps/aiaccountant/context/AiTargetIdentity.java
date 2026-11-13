package com.axelor.apps.aiaccountant.context;

/**
 * Says whether two references name the same record, by resolving each to a canonical one.
 *
 * <h2>The problem it exists for</h2>
 *
 * One supplier invoice reaches the workflow under two names: {@code Upload#946}, because every OCR
 * tool addresses its work by upload id, and {@code Invoice#606}, because that is what was actually
 * written. Comparing the strings says they are different records. They are the same document.
 *
 * <p>The first version of the authorisation check handled this by allowing anything whose kind
 * differed from the authorised targets — reasoning that it might be the same document, so refusing
 * would break ordinary work. That reasoning is backwards for a security check. "Might be the same"
 * is also "might be a completely unrelated record", and an authorisation that admits everything it
 * cannot identify admits everything a mistake could produce.
 *
 * <p>So the question is answered properly instead: a module that owns a kind of reference resolves
 * it to a canonical record, in the active company, from the database. Two references are the same
 * target when they resolve to the same canonical one, and <b>only</b> then.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations are found by {@code MetaScanner}, like {@code AiTool} and {@code
 *       AiPageRecordResolver}, so a feature module contributes one without this module depending on
 *       it.
 *   <li>{@link #canonical} returns {@code null} whenever this resolver does not own the kind, or
 *       cannot establish the record <em>in the active company</em>. Null means "I cannot say", and
 *       the caller treats that as a refusal — not as permission.
 *   <li>The returned value is compared, never shown. {@code Invoice#606} is a reasonable canonical
 *       form; so is a fully-qualified model name. What matters is that the same record always
 *       yields the same string and two different records never do.
 *   <li>It must be company-scoped. A reference to another company's record must resolve to null,
 *       not to that record: otherwise an authorisation could be satisfied by a document the caller
 *       cannot even read.
 *   <li>It must not throw, and must be cheap — it runs before every chat write.
 * </ul>
 */
public interface AiTargetIdentity {

  /**
   * @param ref a short reference such as {@code Upload#946} or {@code Move#102}
   * @return the canonical reference for the record it names, or null when this resolver does not
   *     own that kind, the record does not exist, or it is not the active company's
   */
  String canonical(String ref);

  /**
   * Whether the caller may write to the record this reference names.
   *
   * <h2>Why the check lives with the resolver</h2>
   *
   * Deciding it needs the entity class and the id, and only the module that owns the reference kind
   * knows which class that is. Doing it here keeps this module free of every feature module's
   * entities, and keeps the permission check next to the lookup that already proved the record is
   * the active company's.
   *
   * <p>Answer {@code null} for a reference this resolver does not own. A set-scoped authorisation
   * treats null and false alike — a record it cannot confirm is writable is left out of the
   * authorised set, rather than admitted on the grounds that nothing objected.
   */
  default Boolean writable(String ref) {
    return null;
  }
}
