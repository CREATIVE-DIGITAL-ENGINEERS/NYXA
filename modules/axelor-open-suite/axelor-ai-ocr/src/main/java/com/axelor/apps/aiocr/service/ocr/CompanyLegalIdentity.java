package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.Set;

/**
 * The legal identity (ЕИК/Булстат and ДДС number) of one of THIS installation's companies — the one
 * thing every OCR direction decision is measured against.
 *
 * <h2>Where the identity comes from, and where it deliberately does not</h2>
 *
 * <p>Authoritative and sole source: the {@code Partner} explicitly linked by {@link
 * Company#getPartner()}. Nothing else counts. In particular a Partner that merely <em>looks</em>
 * like the company — the same legal name, or even the same ЕИК — is NOT the company's identity when
 * {@code Company.partner} does not point at it. That distinction is the whole reason this class
 * exists: a supplier record carrying the company's own ЕИК, visible under Purchases → Suppliers
 * because its {@code companySet} contains that company, is an ordinary Partner row like any other.
 * Treating it as the company's identity would make the company "recognise itself" on documents
 * through a record nobody designated for that, and would let an outgoing invoice be booked as a
 * purchase from the company itself.
 *
 * <p>So when {@code Company.partner} is null, or its Partner carries no usable identifier, the
 * answer is {@link #isUsable()} {@code == false} — never a search for a substitute. Callers fail
 * closed on that, and {@link #missingDiagnostic()} says which record an administrator must fix.
 *
 * <p>Canonicalization is {@link BgOcrReviewChecks#canonicalIds}, the same one direction resolution
 * uses, so {@code "BG 100 000 019"} on the company and {@code "100000019"} on the document are one
 * party everywhere.
 *
 * <p>Read-only and side-effect free: it never creates, links or edits a Company or a Partner.
 */
public final class CompanyLegalIdentity {

  private final Company company;
  private final Partner partner;
  private final Set<String> ids;

  private CompanyLegalIdentity(Company company, Partner partner, Set<String> ids) {
    this.company = company;
    this.partner = partner;
    this.ids = ids;
  }

  /** The identity of {@code company}, or an unusable one when it is null or carries none. */
  public static CompanyLegalIdentity of(Company company) {
    Partner partner = company == null ? null : company.getPartner();
    Set<String> ids =
        partner == null
            ? java.util.Collections.emptySet()
            : BgOcrReviewChecks.canonicalIds(partner.getRegistrationCode(), partner.getTaxNbr());
    return new CompanyLegalIdentity(company, partner, ids);
  }

  /** Whether this company can be recognised on a document at all. */
  public boolean isUsable() {
    return !ids.isEmpty();
  }

  public Company company() {
    return company;
  }

  /** The Partner {@code Company.partner} points at, or null when the company links none. */
  public Partner partner() {
    return partner;
  }

  /** The company's registration code as stored, for passing to {@link BgOcrReviewChecks}. */
  public String registrationCode() {
    return partner == null ? null : partner.getRegistrationCode();
  }

  /** The company's VAT number as stored, for passing to {@link BgOcrReviewChecks}. */
  public String taxNbr() {
    return partner == null ? null : partner.getTaxNbr();
  }

  /** Whether {@code candidate} IS the Partner this company is linked to (by id). */
  public boolean isOwnPartner(Partner candidate) {
    return partner != null
        && candidate != null
        && partner.getId() != null
        && partner.getId().equals(candidate.getId());
  }

  /**
   * Whether the given identifiers name this same company — regardless of which Partner row carries
   * them. This is what stops the company's own ЕИК, handed in as a supplier's, from being accepted
   * just because a second Partner row happens to hold it.
   */
  public boolean isSameParty(String eik, String vat) {
    return !ids.isEmpty()
        && !java.util.Collections.disjoint(ids, BgOcrReviewChecks.canonicalIds(eik, vat));
  }

  /** How this company reads in a refusal: {@code "Nyxa BG (Company #1)"}. */
  public String describeCompany() {
    if (company == null) {
      return "The active company";
    }
    String name = company.getName() == null ? "(unnamed)" : company.getName();
    return company.getId() == null ? name : name + " (Company #" + company.getId() + ")";
  }

  /**
   * Why this company has no usable identity, naming the exact record to correct and where to open
   * it — the two cases need different repairs, and "no EIK/VAT configured" told an administrator
   * neither which record was meant nor that a same-named supplier is not it.
   *
   * @return the diagnostic, or null when the identity IS usable
   */
  public String missingDiagnostic() {
    if (isUsable()) {
      return null;
    }
    if (partner == null) {
      return describeCompany()
          + " has no linked Partner, so it has no ЕИК/Булстат or ДДС number of its own and cannot"
          + " be recognised on a document. Open Application Config → Users/Companies → Companies →"
          + " this company and set the 'Partner' field to the Partner that represents it, then fill"
          + " that Partner's 'ЕИК/Булстат' and 'Идент. № по ДДС'. A supplier record with the same"
          + " name or the same ЕИК is NOT this company's identity — only the Partner linked here"
          + " is.";
    }
    return describeCompany()
        + " is linked to "
        + (partner.getId() == null ? "a Partner" : "Partner #" + partner.getId())
        + (partner.getFullName() == null ? "" : " (" + partner.getFullName() + ")")
        + ", which carries no usable ЕИК/Булстат or ДДС number. Open Application Config →"
        + " Referential → Partners, open that Partner and fill 'ЕИК/Булстат' (registrationCode)"
        + " and/or 'Идент. № по ДДС' (taxNbr).";
  }
}
