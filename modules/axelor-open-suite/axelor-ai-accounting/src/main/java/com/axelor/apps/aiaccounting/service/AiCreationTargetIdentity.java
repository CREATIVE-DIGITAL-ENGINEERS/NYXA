package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Identifies {@code Create:<DOMAIN>:<companyId>} — the destination of a document that does not
 * exist yet.
 *
 * <p>The same shape, and the same reasoning, as {@code PartnerCreationTargetIdentity} in the OCR
 * module: a creation has no record to point at, so {@code targetKey} answered null and {@code
 * AiWriteAuthorisation.checkTargets} refused every create before any other check ran. See {@link
 * AiCreationTarget} for why documents name their kind rather than a business key.
 *
 * <h2>What this proves</h2>
 *
 * Only that the reference is well-formed, names a document kind this module can create, and belongs
 * to the <em>active</em> company — the same job every other identity resolver does. It matches no
 * existing record, so a job frozen on it can create and can do nothing else.
 *
 * <p>{@link #writable} asks Axelor the one question that makes sense before a record exists: may
 * this user create one of these at all? A user without the create permission gets the target in the
 * set marked unwritable, which {@code AiWorkflowController} reports as blocked rather than dropping
 * silently.
 */
@Singleton
public class AiCreationTargetIdentity implements AiTargetIdentity {

  @Override
  public String canonical(String ref) {
    AiCreationTarget.Parsed parsed = AiCreationTarget.parse(ref);
    if (parsed == null || entity(parsed.domain()) == null) {
      return null;
    }
    // Fail closed like every other resolver here: a creation target naming another company is not
    // this company's to authorise.
    return AiCompanyScope.ownsId(parsed.companyId()) ? ref.trim() : null;
  }

  @Override
  public Boolean writable(String ref) {
    AiCreationTarget.Parsed parsed = AiCreationTarget.parse(ref);
    if (parsed == null) {
      return null;
    }
    Class<?> entity = entity(parsed.domain());
    if (entity == null) {
      return null;
    }
    if (canonical(ref) == null) {
      return false;
    }
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_CREATE, cast(entity));
  }

  /** The entity a document domain creates, or null for a domain this module does not create. */
  static Class<?> entity(AiDomain domain) {
    if (domain == null) {
      return null;
    }
    switch (domain) {
      case SUPPLIER_INVOICE:
      case CUSTOMER_INVOICE:
        return Invoice.class;
      case JOURNAL_ENTRY:
        return Move.class;
      default:
        return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Class<? extends com.axelor.db.Model> cast(Class<?> entity) {
    return (Class<? extends com.axelor.db.Model>) entity;
  }
}
