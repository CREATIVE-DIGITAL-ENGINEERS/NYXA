package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.context.AiTargetRelationship;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Ties the record a creation job just produced to the {@code Create:<DOMAIN>:<companyId>} target it
 * was authorised under.
 *
 * <h2>The step that would otherwise be missing</h2>
 *
 * «Създай доставна фактура … и я валидирай» is one instruction. The job freezes the creation
 * destination, {@code create_supplier_invoice} runs against it, and then {@code
 * validate_supplier_invoice} arrives naming {@code Invoice#651} — a reference that is not in the
 * authorised set and never could have been, because the invoice did not exist when the set was
 * frozen. Without this resolver that second call is refused, and the user is told their own
 * instruction was not authorised halfway through carrying it out.
 *
 * <h2>What counts as proof, and why it is narrow</h2>
 *
 * Only a record <em>this turn committed</em>: {@link AiWriteEffects} is the server's own
 * turn-scoped ledger, written by the creation service inside its transaction, and a rolled-back
 * write never reaches it. Add the domain and the company, both checked against the record itself by
 * {@link AiCreationCompletion#matches}, and what is proved is exactly "the job's own creation made
 * this record" — not "an invoice of this kind exists", which is true of every company and would let
 * a creation job reach any document in the books.
 *
 * <p>Null for every pairing that is not a creation target against a record, so the registry's own
 * fail-closed-on-silence rule is untouched.
 */
@Singleton
public class AiCreationTargetRelationship implements AiTargetRelationship {

  @Override
  public Boolean isRelated(String canonicalA, String canonicalB) {
    AiCreationTarget.Parsed creation = AiCreationTarget.parse(canonicalA);
    String record = canonicalB;
    if (creation == null) {
      creation = AiCreationTarget.parse(canonicalB);
      record = canonicalA;
    }
    if (creation == null || AiCreationTarget.parse(record) != null) {
      // Not this pairing: neither side is a creation target, or both are.
      return null;
    }
    if (AiCreationTargetIdentity.entity(creation.domain()) == null
        || !AiCompanyScope.ownsId(creation.companyId())) {
      return null;
    }
    AiCreationCompletion matcher = Beans.get(AiCreationCompletion.class);
    for (AiWriteEffects.Ref effect : AiWriteEffects.snapshot()) {
      if (!matcher.matches(creation.domain(), effect)) {
        continue;
      }
      // The ledger holds the model and id; the authorised reference is the short form of the same
      // pair, which is how AiWorkflowController names every target it records.
      String written = shortRef(effect.getModel(), effect.getId());
      if (written.equals(record)) {
        return Boolean.TRUE;
      }
    }
    // The pairing IS one this resolver owns, and nothing this turn created that record under this
    // authorisation. A real "no", not silence.
    return Boolean.FALSE;
  }

  /** {@code Invoice#651} — the same short form {@code AiWorkflowController.shortRef} produces. */
  static String shortRef(String model, Long id) {
    String name = model == null ? "?" : model;
    int dot = name.lastIndexOf('.');
    return (dot < 0 ? name : name.substring(dot + 1)) + "#" + id;
  }
}
