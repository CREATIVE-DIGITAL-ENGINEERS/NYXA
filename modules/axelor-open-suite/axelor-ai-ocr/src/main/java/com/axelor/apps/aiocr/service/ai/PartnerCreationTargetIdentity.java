package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.base.db.Partner;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Names the supplier a {@code manage_supplier(create)} call is about, before that partner exists.
 *
 * <h2>Why creation needs a reference kind of its own</h2>
 *
 * Every other {@link AiTargetIdentity} resolves a reference to a record that already exists.
 * Creation has none yet, and {@code ManageSupplierTool.targetKey} used to answer {@code null} for
 * it — which meant {@code AiWriteAuthorisation.checkTargets} refused the call before {@code
 * checkCapability} ever saw it, so a capability-only exception there would never have been reached.
 * The fix instead gives the call a real, independently-verifiable destination: the company and the
 * normalised ЕИК it is for, which is exactly the identity {@code SupplierMaintenanceService.create}
 * already refuses to duplicate — at most one partner per ЕИК, per company.
 *
 * <h2>What this resolver proves, and what it does not</h2>
 *
 * Only that the reference is well-formed and names the active company — the same job every other
 * identity resolver does, and nothing about whether this particular create should be authorised.
 * That question belongs to {@link UploadSupplierIdentityRelationship}, reached through {@code
 * AiTargetRegistry.relatedTarget} exactly like an ordinary related-record update: a synthetic
 * target proves nothing about authorisation by existing, only by being provably tied to a document
 * the job already has.
 */
@Singleton
public class PartnerCreationTargetIdentity implements AiTargetIdentity {

  static final String PREFIX = "PartnerCreate:";

  @Override
  public String canonical(String ref) {
    Parsed parsed = parse(ref);
    // Fail closed like every other resolver here: a synthetic target naming another company's ЕИК
    // does not exist as far as this company's authorisation is concerned.
    return parsed != null && AiCompanyScope.ownsId(parsed.companyId) ? ref.trim() : null;
  }

  /**
   * Whether this user may create partners at all. There is no record yet, so the permission asked
   * is Axelor's create right on Partner.
   *
   * <p>This reference only enters an authorised set from the user's own message — «създай доставчик
   * ... ЕИК 123456789», resolved by {@code SupplierMessageTargetResolver} — never from a tool
   * argument. It used to answer false outright, which made such an instruction impossible to
   * authorise without an OCR upload behind it.
   */
  @Override
  public Boolean writable(String ref) {
    if (canonical(ref) == null) {
      return parse(ref) == null ? null : false;
    }
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_CREATE, Partner.class);
  }

  /** Parses {@code "PartnerCreate:<companyId>:<eik>"}, or null for anything else or malformed. */
  static Parsed parse(String ref) {
    if (ref == null || !ref.trim().startsWith(PREFIX)) {
      return null;
    }
    String rest = ref.trim().substring(PREFIX.length());
    int sep = rest.indexOf(':');
    if (sep <= 0 || sep == rest.length() - 1) {
      return null;
    }
    Long companyId;
    try {
      companyId = Long.parseLong(rest.substring(0, sep).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    String eik = rest.substring(sep + 1).trim();
    return eik.isEmpty() ? null : new Parsed(companyId, eik);
  }

  static final class Parsed {
    final Long companyId;
    final String eik;

    Parsed(Long companyId, String eik) {
      this.companyId = companyId;
      this.eik = eik;
    }
  }
}
