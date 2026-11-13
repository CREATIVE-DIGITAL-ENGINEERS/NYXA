package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import java.util.List;

/**
 * Names the record the user's own words identify by a business key, when nothing on screen does.
 *
 * <h2>Why this exists</h2>
 *
 * «Смени IBAN-а на доставчик с ЕИК 131071587» typed from the dashboard or the supplier list named
 * its record exactly — by its legal identity, not by a row id — and still authorised nothing: no
 * record was open, so the job had no target and every write was refused. The model then asked for a
 * confirmation that could not change that, and the user was stuck in a loop.
 *
 * <p>This reads the user's message (never a tool argument) and answers with the one record a
 * business key in it identifies. It is the same standard {@code AiExplicitReferenceParser} holds
 * row ids to: unmistakable, or nothing. An ambiguous match answers empty.
 *
 * <p>Discovered by {@code MetaScanner} like every other extension point here. Implementations must
 * be company-scoped and must never throw.
 */
public interface AiMessageTargetResolver {

  /**
   * @param domain the kind of record this turn is about
   * @param message the user's own words this turn
   * @return short refs, at most a handful; empty when the message identifies nothing unambiguously
   */
  List<String> resolve(AiDomain domain, String message);

  /**
   * As above, with the whole route — so a resolver can read what the turn was routed AS, not only
   * which kind of record it is about.
   *
   * <h2>Why the route and not more words</h2>
   *
   * Which record a message names and what it asks to be done to that record are different
   * questions, and the second one is not always answerable from a business key. «Create a supplier
   * with ЕИК X» and «change the IBAN of the supplier with ЕИК X» carry the same key and need
   * different targets — a synthetic {@code PartnerCreate:} identity for the first, the existing
   * {@code Partner#id} for the second. {@code SupplierMessageTargetResolver} used to tell them
   * apart with a hand-kept list of Bulgarian and English verbs, which is the magic-phrase interface
   * this layer exists to avoid; {@link com.axelor.apps.aiaccountant.route.AiWriteOperation} on the
   * route carries the answer instead, read once by the router from the user's own sentence.
   *
   * <p>The default delegates, so every existing implementation and every existing caller keeps
   * working untouched — a resolver that does not care about the route never has to mention it.
   */
  default List<String> resolve(AiRoute route, String message) {
    return resolve(route == null ? null : route.domain(), message);
  }

  /**
   * The record the turn is about when the message itself names no business key, read from what the
   * server holds rather than from the words: the document open on screen, or an identity the user
   * gave that the server can match to pending work. «Създай липсващия доставчик» over an OCR upload
   * names its supplier through that upload's own extraction. Pending work the user pointed at
   * nowhere is never a target, however few items there are.
   *
   * <p>Asked only after {@link #resolve(AiRoute, String)} answered nothing. Must be unambiguous or
   * empty, company-scoped, and must never throw. The default answers nothing.
   *
   * @param page what the client reported on screen, or null
   */
  default List<String> resolveFromContext(
      AiRoute route, String message, com.axelor.apps.aiaccountant.dto.PageContext page) {
    return java.util.Collections.emptyList();
  }
}
