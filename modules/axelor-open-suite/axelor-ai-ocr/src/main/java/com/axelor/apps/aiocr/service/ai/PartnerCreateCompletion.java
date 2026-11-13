package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.service.supplier.SupplierMaintenanceService;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Whether a {@code manage_supplier(create)} job's synthetic target has actually been reached.
 *
 * <h2>Completion needs no upload backlink</h2>
 *
 * {@link UploadSupplierIdentityRelationship} is what an upload's own stored evidence <em>proves
 * related</em> before the create may run at all — authorisation is about the document that
 * justifies the write. Completion is a different question: whether the thing the write was for now
 * exists. {@code PartnerCreate:<company>:<eik>} names a partner by its company and its canonical
 * ЕИК alone, and that identity is exactly what {@code SupplierMaintenanceService.create} refuses to
 * duplicate — at most one partner per ЕИК, per company. So the same lookup that enforces the
 * uniqueness is the one that proves completion: it needs the company and the ЕИК the target already
 * carries, never a link back to whichever upload happened to authorise the create.
 *
 * <h2>Why the old blocker used to survive a successful create</h2>
 *
 * Nothing recognised the {@code "PartnerCreate:"} prefix, so {@code
 * AiWorkflowController.markWorkDone} asked {@link
 * com.axelor.apps.aiaccountant.context.AiCompletionRegistry} about it, got null from every
 * resolver, and left it blocked forever — even once the partner it named had been created under the
 * very same target ref, moments earlier in the same turn.
 */
@Singleton
public class PartnerCreateCompletion implements AiCompletionResolver {

  @Override
  public Boolean isComplete(String ref, String task) {
    PartnerCreationTargetIdentity.Parsed parsed = PartnerCreationTargetIdentity.parse(ref);
    if (parsed == null) {
      return null;
    }
    if (!AiCompanyScope.ownsId(parsed.companyId)) {
      // Not this company's target to answer for; leave it to whatever else might recognise it
      // (nothing does today, so the caller leaves it active, which is the safe default).
      return null;
    }
    Partner partner = supplierMaintenanceService().findScopedByEik(parsed.eik);
    return partner != null;
  }

  /**
   * The seam a test overrides to substitute a fake lookup, without a running Guice injector and
   * without touching {@link SupplierMaintenanceService} itself — the same idiom {@code
   * AiWorkflowController.targetRegistry()} uses. Production always resolves the real, Guice-bound
   * service; only a test built with no injector at all needs to override this.
   */
  protected SupplierMaintenanceService supplierMaintenanceService() {
    return Beans.get(SupplierMaintenanceService.class);
  }
}
