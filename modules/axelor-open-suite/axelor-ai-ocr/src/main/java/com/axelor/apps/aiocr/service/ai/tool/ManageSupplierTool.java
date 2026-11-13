package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolBatchRunner;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.supplier.DuplicateSupplierException;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.apps.aiocr.service.supplier.SupplierMaintenanceService;
import com.axelor.apps.aiocr.service.supplier.SupplierView;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: create a supplier, or edit an existing one.
 *
 * <p>Chat-only, always. Creating supplier master data and changing a supplier's bank account or
 * payment terms are both decisions with lasting consequences — the second one decides where money
 * goes — so neither may happen unless a person in the conversation asked for it. The OCR intake
 * pipeline cannot reach this tool at all.
 *
 * <p>Not a field patcher: the schema is a closed set of typed supplier fields, and everything
 * derived (partner sequence, full name, accounting situation, address rendering, IBAN
 * decomposition) is produced by the Axelor services {@link SupplierMaintenanceService} delegates
 * to.
 */
@Singleton
public class ManageSupplierTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "manage_supplier";
  }

  @Override
  public String description() {
    return "Creates a supplier, or edits an existing one (operation=create|update). Creating needs a "
        + "name and a ЕИК read from the document or given by the user; when the user asked for the "
        + "create and both are known, make the call — ask only for what is genuinely missing. On "
        + "update, scalar fields you send (name, eik, vatNumber, phones, email, webSite, payment "
        + "mode/condition, currency, fiscal position) REPLACE the stored value; address, "
        + "bankAccount and contact are ADDED next to the existing ones; nothing is removed or "
        + "cleared. An update always marks the partner as a supplier, so update with only "
        + "supplierId turns an existing customer into one. Only a partner belonging to the ACTIVE "
        + "company alone can be updated; a partner shared with other companies (or with no company "
        + "list) is refused and left unchanged. A second partner for this ЕИК within the ACTIVE "
        + "company is refused; the refusal gives existingSupplierId when you may read it — update "
        + "that one instead. Companies are isolated — a DIFFERENT company may hold its own, "
        + "separate supplier for the same ЕИК; that is never a reason to refuse creating this "
        + "company's own, and it is never visible or editable from here. Every argument is checked "
        + "before anything is saved: an unknown field or a wrongly typed value (e.g. address as a "
        + "string instead of an object) refuses the whole call. An address given here is matched to "
        + "a known Bulgarian settlement, marked as the invoicing address, and copied onto this "
        + "supplier's OCR draft invoices that have none.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    ObjectNode operation = props.putObject("operation");
    operation.put("type", "string");
    operation.putArray("enum").add("create").add("update");
    ObjectNode supplierId = props.putObject("supplierId");
    supplierId.put("type", "integer");
    supplierId.put("description", "Required for update; the id from find_supplier.");
    // uploadId is deliberately gone. It existed only to group this call with an invoice correction
    // into one approvable pending change, and there is no approval queue any more: this tool writes
    // when it is called. Keeping an argument that changes nothing would invite the model to spend
    // effort on it.

    props
        .putObject("name")
        .put("type", "string")
        .put("description", "Company name exactly as printed. Required to create.");
    props
        .putObject("eik")
        .put("type", "string")
        .put("description", "Bulgarian ЕИК, 9-13 digits. Required to create.");
    props.putObject("vatNumber").put("type", "string").put("description", "VAT number as printed.");
    ObjectNode partnerType = props.putObject("partnerType");
    partnerType.put("type", "string");
    partnerType.putArray("enum").add("company").add("individual");
    partnerType.put("description", "Default company. A supplier with an ЕИК is a company.");
    props.putObject("fixedPhone").put("type", "string");
    props.putObject("mobilePhone").put("type", "string");
    props.putObject("email").put("type", "string");
    props.putObject("webSite").put("type", "string");

    ObjectNode address = props.putObject("address");
    address.put("type", "object");
    address.put(
        "description",
        "Postal address: the structured parts, or the whole printed line in 'raw'. The city must be"
            + " a known Nyxa settlement; unknown ones are refused, never created.");
    ObjectNode addressProps = address.putObject("properties");
    addressProps.putObject("street").put("type", "string");
    addressProps.putObject("number").put("type", "string");
    addressProps.putObject("zip").put("type", "string");
    addressProps.putObject("city").put("type", "string");
    addressProps
        .putObject("countryIso")
        .put("type", "string")
        .put("description", "ISO-2, default BG.");
    addressProps.putObject("raw").put("type", "string");
    address.put("additionalProperties", false);

    ObjectNode bank = props.putObject("bankAccount");
    bank.put("type", "object");
    bank.put(
        "description",
        "A bank account for the supplier. The IBAN alone is enough; an account belonging to another"
            + " partner is refused. If only the last digits are printed, read the stored OCR"
            + " (get_ocr_evidence) before asking the user.");
    ObjectNode bankProps = bank.putObject("properties");
    bankProps
        .putObject("iban")
        .put("type", "string")
        .put("description", "The full IBAN as printed. The only required field.");
    bankProps
        .putObject("bic")
        .put("type", "string")
        // The prohibition itself is in the supplier-invoice domain guide and the create playbook,
        // sent per turn; what has to be here is the condition for sending it at all.
        .put(
            "description",
            "SWIFT/BIC ONLY if printed on the document — never derived, recalled or guessed. Matched"
                + " against known banks, never created; one contradicting the IBAN is discarded.");
    bankProps
        .putObject("bankName")
        .put("type", "string")
        .put(
            "description",
            "The bank's name as printed, when no BIC is shown. Matched against registered banks; an"
                + " ambiguous or unknown name links none.");
    bankProps.putObject("label").put("type", "string");
    bank.put("additionalProperties", false);

    props
        .putObject("paymentModeCode")
        .put("type", "string")
        .put("description", "Code of the mode this company pays the supplier by.");
    props.putObject("paymentConditionCode").put("type", "string");
    props.putObject("currencyCode").put("type", "string");
    props.putObject("fiscalPositionCode").put("type", "string");

    ObjectNode contact = props.putObject("contact");
    contact.put("type", "object");
    contact.put("description", "A contact person at the supplier.");
    ObjectNode contactProps = contact.putObject("properties");
    contactProps.putObject("name").put("type", "string");
    contactProps.putObject("firstName").put("type", "string");
    contactProps.putObject("email").put("type", "string");
    contactProps.putObject("phone").put("type", "string");
    contact.put("additionalProperties", false);

    props
        .putObject("reason")
        .put("type", "string")
        .put("description", "Where the values came from, e.g. the document and upload id.");

    schema.putArray("required").add("operation");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.PARTNER;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Never unattended: supplier master data is not something an automatic pass may invent. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /**
   * Before {@code edit_supplier_invoice} (20). Creating the supplier and pointing an invoice at it
   * is one intent, and the supplier has to exist first — so when a reply makes both calls, this is
   * the one to make first.
   */
  @Override
  public int writeOrder() {
    return 5;
  }

  /**
   * The supplier an update changes, so a lost answer can be reconciled against the record itself —
   * or, for a create, the destination the call is for, before that record exists.
   *
   * <h2>Why an update names a real record</h2>
   *
   * An update names an existing partner, so there is a record to fingerprint before the call and
   * compare with afterwards — which is the only way "nothing was written" can ever be proven.
   * Without a declared target (the default looks for an {@code uploadId}, which this schema does
   * not have), every ambiguous failure on supplier master data would be unprovable. For data that
   * decides where money is paid, that is the wrong end of the trade.
   *
   * <h2>Why a create names a synthetic one</h2>
   *
   * A create has no record yet, but refusing it a target at all meant {@code checkTargets} refused
   * every create outright, before {@code checkCapability} — the check that would otherwise permit
   * it — ever ran. So a create names the company and the normalised ЕИК it is for instead: {@code
   * "PartnerCreate:<companyId>:<eik>"}, the exact identity {@code SupplierMaintenanceService}
   * already refuses to duplicate. {@code PartnerCreationTargetIdentity} only proves the reference
   * is well-formed and in this company; whether the call may proceed is decided the same way an
   * update's related-Partner match is, by {@code UploadSupplierIdentityRelationship} proving the
   * ЕИК matches the authorised Upload or Invoice's own extracted supplier identity. A create with
   * no usable ЕИК still gets null, exactly as before — this only adds a target for a create that
   * carries one.
   *
   * <p>What settles a lost create, either way, is {@link #verifyAfterAmbiguousFailure}, against the
   * ЕИК — an identity that exists before the call and does not depend on this target having been
   * used.
   */
  @Override
  public String targetKey(JsonNode args) {
    if (args != null && args.hasNonNull("supplierId")) {
      String id = args.get("supplierId").asText("").trim();
      if (!id.isEmpty()) {
        return "Partner#" + id;
      }
    }
    if (args == null || !"create".equalsIgnoreCase(AiToolArgs.optText(args, "operation"))) {
      return null;
    }
    String eik = effectiveEik(args);
    if (eik == null) {
      return null;
    }
    Long companyId = AiCompanyScope.companyIdOrNull();
    return companyId == null ? null : "PartnerCreate:" + companyId + ":" + eik;
  }

  /**
   * The exact postcondition an update naming a bank account asks for, so the workflow layer can
   * prove it separately from {@link #targetKey}'s own, coarser answer.
   *
   * <h2>Why {@code targetKey}'s own {@code Partner#id} cannot answer this</h2>
   *
   * {@code Partner#84} names the record an update's blast radius covers — right for the repeat
   * guard and for a batch abort, both of which only need to know which record failed. It says
   * nothing about which of a supplier's many editable fields this particular call was for: the
   * record "exists" identically whether an IBAN update on it just landed or was just refused, which
   * is exactly why a completion check keyed on it alone could never distinguish the two — see
   * {@code PartnerBankAccountCompletion}'s own javadoc.
   *
   * <h2>Where each part comes from</h2>
   *
   * <ul>
   *   <li><b>Company</b> — {@link AiCompanyScope#companyIdOrNull()}, the active scope every write
   *       here already runs inside, never anything the arguments could name themselves.
   *   <li><b>Partner id</b> — {@code supplierId}, the same argument {@link #targetKey} already
   *       trusts to name an existing record. Never the create path: a create has no persisted id to
   *       name until after it succeeds, and {@link #targetKey} already answers {@code
   *       PartnerCreate:<companyId>:<eik>} for it, which is what {@code PartnerCreateCompletion}
   *       resolves.
   *   <li><b>IBAN</b> — {@code bankAccount.iban}, normalised exactly as {@link
   *       SupplierMaintenanceService} itself normalises it before persisting, so the reference this
   *       method returns and the value a completion resolver reads back from the database are
   *       written in the same form.
   * </ul>
   *
   * <p>Every one of those is a fact about the request the caller supplied or the scope it ran
   * inside — never the tool's own result, and never a human-readable message. Answering it the same
   * way whether the call is about to run, has just failed, or has just succeeded is what lets a
   * failure and its retry be compared at all: see {@link AiToolBatchRunner#completionTargetOf}.
   *
   * <p>Null for anything else — a create, an update naming no bank account, or one missing a usable
   * IBAN or supplier id — which leaves {@link AiToolBatchRunner} to fall back to {@link
   * #targetKey}'s own answer, exactly as if this method did not exist.
   */
  @Override
  public String completionTarget(JsonNode args) {
    if (args == null || !"update".equalsIgnoreCase(AiToolArgs.optText(args, "operation"))) {
      return null;
    }
    JsonNode bank = args.get("bankAccount");
    if (bank == null || !bank.isObject()) {
      return null;
    }
    String iban = BgOcrBankDetails.normalizeIban(AiToolArgs.optText(bank, "iban"));
    if (iban == null) {
      return null;
    }
    Long supplierId = AiToolArgs.optId(args, "supplierId");
    if (supplierId == null) {
      return null;
    }
    Long companyId = AiCompanyScope.companyIdOrNull();
    return companyId == null
        ? null
        : "PartnerBankAccount:" + companyId + ":" + supplierId + ":" + iban;
  }

  /**
   * After a lost answer: does the supplier this call was creating now exist?
   *
   * <h2>The identity is the ЕИК, and it is established before the call</h2>
   *
   * {@code create} canonicalises the ЕИК and refuses outright if a partner already holds it, so
   * within one company there is at most one partner per ЕИК — enforced by the operation itself, not
   * hoped for. That makes "is there now a partner with this ЕИК?" an exact question about a unique
   * key rather than a search for something plausible.
   *
   * <p>It is checked <em>together with</em> the id the attempt recorded through {@code
   * AiWriteEffects}, which is the server-generated primary key of the row it saved. Two independent
   * identities have to agree before this reports success: the row exists under the id the attempt
   * claimed, and that row carries the ЕИК the call was for. Either one alone could be satisfied by
   * a partner somebody else created in the same seconds.
   *
   * <p>An update is deliberately not verified here. Its effect is "an address, an IBAN or a payment
   * term was added", and deciding whether the right one landed would mean re-deriving the intent
   * from the arguments — a guess. Its target is a real record with a real fingerprint, so the
   * generic reconciliation answers it properly, and an unprovable update ends as uncertain.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    if (args == null || !"create".equalsIgnoreCase(AiToolArgs.optText(args, "operation"))) {
      return AiWriteOutcome.unknown();
    }
    String eik = effectiveEik(args);
    if (eik == null) {
      // No usable identity, so nothing may be concluded. A create with an unreadable ЕИК is refused
      // by the service anyway, which is a business answer and never reaches this path.
      return AiWriteOutcome.unknown();
    }
    Long claimed = probe == null ? null : probe.newId(Partner.class);
    Partner existing = Beans.get(SupplierMaintenanceService.class).findScopedByEik(eik);
    if (existing == null || existing.getId() == null) {
      // The unique key is free. Nothing holds it, so the create did not land — and because the
      // service checks that key before writing anything, this is a proof rather than an absence
      // of evidence.
      return AiWriteOutcome.notWritten();
    }
    if (claimed != null && !claimed.equals(existing.getId())) {
      // Somebody else's partner holds the ЕИК, under a different id than the one this attempt
      // saved. That is not this call's write, and it is not something to report either way.
      return AiWriteOutcome.unknown();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("created", true);
    out.put("supplierId", existing.getId());
    // The same keys, in the same order, with the same types — a published shape must not depend on
    // whether the network held up. What cannot be recovered is the itemised list of what was set,
    // so it is empty and the warning says exactly that rather than implying nothing was set.
    out.put("changes", new ArrayList<String>());
    List<String> warnings = new ArrayList<>();
    warnings.add(
        "The connection was lost while this supplier was being created, but the supplier exists and"
            + " was confirmed by its ЕИК "
            + eik
            + ". The itemised list of what was set could not be recovered — open the supplier and"
            + " check the address, bank account and payment settings before using it.");
    out.put("warnings", warnings);
    out.put("supplier", SupplierView.detail(existing));
    out.put(
        "nextStep",
        "The supplier now exists. Putting it on an OCR draft invoice is edit_supplier_invoice"
            + " (supplierId, or supplierEik when both are parts of one change).");
    return AiWriteOutcome.completed(out);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    // Every argument, before anything else: a wrongly typed nested object must not be dropped
    // silently while the rest of the call saves, or "address added" could be reported for an
    // address that never reached the service.
    validateArguments(args);
    String operation = AiToolArgs.requireText(args, "operation").trim().toLowerCase();
    String reason = AiToolArgs.optText(args, "reason");
    requireData(operation, args);
    SupplierMaintenanceService.SupplierSpec spec = spec(args);
    SupplierMaintenanceService service = Beans.get(SupplierMaintenanceService.class);

    SupplierMaintenanceService.SupplierResult result;
    try {
      switch (operation) {
        case "create":
          result = service.create(spec, reason);
          break;
        case "update":
          result = service.update(AiToolArgs.requireId(args, "supplierId"), spec, reason);
          break;
        default:
          throw new AiToolException(
              "'" + operation + "' is not an operation. Use create or update.");
      }
    } catch (OcrBlocked e) {
      // The refusal already says what kind it is; relaying only its sentence is what forced the
      // workflow to work that out again from the words.
      throw e.asToolException();
    } catch (DuplicateSupplierException e) {
      // The existing record's id and flag as fields, only when the caller may read it.
      throw new AiToolException(e.getMessage(), e.details());
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("created", result.isCreated());
    out.put("supplierId", result.getSupplierId());
    out.put("changes", result.getChanges());
    out.put("warnings", result.getWarnings());
    out.put("supplier", result.getSupplier());
    // Read back, not trusted: the result above is the service's own account of what it did. The
    // record itself is what the user will open, so what it holds is checked and reported — a
    // problem here is stated to the user rather than covered by a "done".
    Verification check = verify(operation, args, result.getSupplierId());
    out.put("verified", check.problems.isEmpty());
    if (!check.problems.isEmpty()) {
      out.put("verificationProblems", check.problems);
    }
    if (!check.missingInfo.isEmpty()) {
      out.put("missingInfo", check.missingInfo);
    }
    if (result.isCreated()) {
      // Only reachable during a rehearsal or an apply — this tool is never executed at propose
      // time — so it describes what is left to do, not what to call next in this reply.
      out.put(
          "nextStep",
          "The supplier now exists. Putting it on an OCR draft invoice is edit_supplier_invoice"
              + " (supplierId, or supplierEik when both are parts of one change)."
              + (check.missingInfo.isEmpty()
                  ? ""
                  : " Tell the user what the supplier still lacks (missingInfo); do not invent it."));
    }
    return out;
  }

  /** The ЕИК a call is for: {@code eik}, else the ЕИК inside a BG {@code vatNumber}. */
  static String effectiveEik(JsonNode args) {
    if (args == null) {
      return null;
    }
    String eik = SupplierEik.canonical(AiToolArgs.optText(args, "eik"));
    return eik != null ? eik : SupplierEik.canonical(AiToolArgs.optText(args, "vatNumber"));
  }

  /**
   * Refuses, before anything is looked up or saved, a call missing what the operation cannot be
   * made without — naming ALL of it at once and where to get it, so the next call is the right one
   * instead of a guess one field at a time.
   */
  static void requireData(String operation, JsonNode args) throws AiToolException {
    List<String> missing = new ArrayList<>();
    if ("create".equals(operation)) {
      String name = AiToolArgs.optText(args, "name");
      if (name == null || name.trim().isEmpty()) {
        missing.add("name (the supplier's company name as printed)");
      }
      if (effectiveEik(args) == null) {
        String given = AiToolArgs.optText(args, "eik");
        missing.add(
            given == null || given.trim().isEmpty()
                ? "eik (the supplier's ЕИК, 9 or 13 digits — or a BG VAT number it is read from)"
                : "a readable eik ('" + given.trim() + "' is not 9 or 13 digits)");
      }
    } else if ("update".equals(operation)) {
      if (AiToolArgs.optText(args, "supplierId") == null) {
        missing.add("supplierId (call find_supplier with the ЕИК or name first)");
      }
    }
    if (!missing.isEmpty()) {
      throw new OcrBlocked(
              AiBlockerCode.MISSING_SUPPLIER_DATA,
              "Nothing was saved. The supplier "
                  + operation
                  + " is missing: "
                  + String.join("; ", missing)
                  + ". For a supplier from an OCR upload read these from the document"
                  + " (get_ocr_evidence / get_ocr_record) and call again; otherwise ask the user"
                  + " for exactly these, in one question.")
          .asToolException();
    }
  }

  /** What the read-back found: problems with what was asked, and data the supplier still lacks. */
  static final class Verification {
    final List<String> problems = new ArrayList<>();
    final List<String> missingInfo = new ArrayList<>();
  }

  /** Re-reads the saved supplier and compares it with the call. Never throws. */
  Verification verify(String operation, JsonNode args, Long supplierId) {
    Verification v = new Verification();
    try {
      Partner p =
          supplierId == null
              ? null
              : Beans.get(com.axelor.apps.base.db.repo.PartnerRepository.class).find(supplierId);
      if (p == null) {
        v.problems.add("the supplier could not be read back after saving (id " + supplierId + ")");
        return v;
      }
      check(v, p, operation, args, AiCompanyScope.companyIdOrNull());
    } catch (RuntimeException e) {
      v.problems.add("the saved supplier could not be checked: " + e.getMessage());
    }
    return v;
  }

  static void check(
      Verification v, Partner p, String operation, JsonNode args, Long activeCompanyId) {
    if (!Boolean.TRUE.equals(p.getIsSupplier())) {
      v.problems.add("the partner is not marked as a supplier");
    }
    // An update changes the ЕИК only when one is sent as such; a create also takes it from the VAT.
    String eik =
        "create".equals(operation)
            ? effectiveEik(args)
            : SupplierEik.canonical(AiToolArgs.optText(args, "eik"));
    if (eik != null && !eik.equals(p.getRegistrationCode())) {
      v.problems.add(
          "the stored ЕИК is '" + p.getRegistrationCode() + "', not the requested " + eik);
    }
    if ("create".equals(operation)
        && activeCompanyId != null
        && (p.getCompanySet() == null
            || p.getCompanySet().stream()
                .noneMatch(c -> c != null && activeCompanyId.equals(c.getId())))) {
      v.problems.add("the supplier is not associated with the active company");
    }
    JsonNode bank = args == null ? null : args.get("bankAccount");
    String iban =
        bank != null && bank.isObject()
            ? BgOcrBankDetails.normalizeIban(AiToolArgs.optText(bank, "iban"))
            : null;
    if (iban != null
        && (p.getBankDetailsList() == null
            || p.getBankDetailsList().stream()
                .noneMatch(
                    b -> b != null && iban.equals(BgOcrBankDetails.normalizeIban(b.getIban()))))) {
      v.problems.add("the IBAN " + iban + " is not among the supplier's bank accounts");
    }
    JsonNode address = args == null ? null : args.get("address");
    boolean hasAddress =
        p.getMainAddress() != null
            || (p.getPartnerAddressList() != null && !p.getPartnerAddressList().isEmpty());
    if (address != null && address.isObject() && address.size() > 0 && !hasAddress) {
      v.problems.add("an address was given but the supplier has none");
    }
    if (blank(p.getTaxNbr())) {
      v.missingInfo.add("VAT number");
    }
    if (!hasAddress) {
      v.missingInfo.add("address");
    }
    if (p.getBankDetailsList() == null || p.getBankDetailsList().isEmpty()) {
      v.missingInfo.add("bank account (IBAN)");
    }
    if (p.getEmailAddress() == null && blank(p.getFixedPhone()) && blank(p.getMobilePhone())) {
      v.missingInfo.add("phone or e-mail");
    }
  }

  private static boolean blank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /** Top-level arguments read as text (a JSON number is accepted too — an ЕИК often is one). */
  static final Set<String> TEXT_ARGUMENTS =
      new LinkedHashSet<>(
          Arrays.asList(
              "operation",
              "name",
              "eik",
              "vatNumber",
              "partnerType",
              "fixedPhone",
              "mobilePhone",
              "email",
              "webSite",
              "paymentModeCode",
              "paymentConditionCode",
              "currencyCode",
              "fiscalPositionCode",
              "reason"));

  /** The three nested objects and the text fields each may carry — the schema's closed sets. */
  static final Map<String, Set<String>> NESTED_ARGUMENTS = new LinkedHashMap<>();

  static {
    NESTED_ARGUMENTS.put(
        "address",
        new LinkedHashSet<>(Arrays.asList("street", "number", "zip", "city", "countryIso", "raw")));
    NESTED_ARGUMENTS.put(
        "bankAccount", new LinkedHashSet<>(Arrays.asList("iban", "bic", "bankName", "label")));
    NESTED_ARGUMENTS.put(
        "contact", new LinkedHashSet<>(Arrays.asList("name", "firstName", "email", "phone")));
  }

  /**
   * Refuses the whole call, before any lookup or write, when any argument is unknown or wrongly
   * typed — naming every problem at once, so one correction fixes the call.
   *
   * <p>{@link AiToolArgs#optText} reads an object or array as empty, and {@link #spec} skips a
   * nested argument that is not an object, so without this check either mistake would vanish while
   * the rest of the call is saved.
   */
  static void validateArguments(JsonNode args) throws AiToolException {
    if (args == null || !args.isObject()) {
      throw badArguments("The arguments must be a JSON object.");
    }
    List<String> problems = new ArrayList<>();
    Iterator<String> names = args.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      JsonNode value = args.get(field);
      if (TEXT_ARGUMENTS.contains(field)) {
        if (!isScalarText(value)) {
          problems.add(field + " must be a string");
        }
      } else if ("supplierId".equals(field)) {
        if (!(value == null || value.isNull() || value.isIntegralNumber() || value.isTextual())) {
          problems.add("supplierId must be an integer");
        }
      } else if (NESTED_ARGUMENTS.containsKey(field)) {
        if (value == null || value.isNull()) {
          continue;
        }
        if (!value.isObject()) {
          problems.add(
              field
                  + " must be an object with "
                  + String.join("/", NESTED_ARGUMENTS.get(field))
                  + ", not "
                  + value.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
          continue;
        }
        Set<String> allowed = NESTED_ARGUMENTS.get(field);
        Iterator<String> inner = value.fieldNames();
        while (inner.hasNext()) {
          String child = inner.next();
          if (!allowed.contains(child)) {
            problems.add(
                field
                    + "."
                    + child
                    + " is not a field (allowed: "
                    + String.join(", ", allowed)
                    + ")");
          } else if (!isScalarText(value.get(child))) {
            problems.add(field + "." + child + " must be a string");
          }
        }
      } else {
        problems.add(field + " is not an argument of manage_supplier");
      }
    }
    String partnerType =
        isScalarText(args.get("partnerType")) ? AiToolArgs.optText(args, "partnerType") : null;
    if (partnerType != null
        && !"company".equalsIgnoreCase(partnerType)
        && !"individual".equalsIgnoreCase(partnerType)) {
      problems.add("partnerType must be company or individual");
    }
    if (!problems.isEmpty()) {
      throw badArguments(
          "Nothing was saved. Fix these arguments and call again: "
              + String.join("; ", problems)
              + ".");
    }
  }

  private static boolean isScalarText(JsonNode value) {
    return value == null || value.isNull() || value.isTextual() || value.isNumber();
  }

  private static AiToolException badArguments(String message) {
    return new OcrBlocked(AiBlockerCode.BAD_ARGUMENTS, message).asToolException();
  }

  /**
   * The ЕИК handed to the service: as sent, or — for a create sent only a BG VAT number — the ЕИК
   * inside it, which is the same key the intake and {@link #targetKey} already use.
   */
  private static String eikArgument(JsonNode args) {
    String eik = AiToolArgs.optText(args, "eik");
    if ((eik == null || eik.trim().isEmpty())
        && "create".equalsIgnoreCase(AiToolArgs.optText(args, "operation"))) {
      return effectiveEik(args);
    }
    return eik;
  }

  /** Reads the closed set of typed fields. Anything not named here cannot be written. */
  private SupplierMaintenanceService.SupplierSpec spec(JsonNode args) {
    SupplierMaintenanceService.SupplierSpec spec =
        new SupplierMaintenanceService.SupplierSpec()
            .setName(AiToolArgs.optText(args, "name"))
            .setEik(eikArgument(args))
            .setVatNumber(AiToolArgs.optText(args, "vatNumber"))
            .setPartnerType(AiToolArgs.optText(args, "partnerType"))
            .setFixedPhone(AiToolArgs.optText(args, "fixedPhone"))
            .setMobilePhone(AiToolArgs.optText(args, "mobilePhone"))
            .setEmail(AiToolArgs.optText(args, "email"))
            .setWebSite(AiToolArgs.optText(args, "webSite"))
            .setPaymentModeCode(AiToolArgs.optText(args, "paymentModeCode"))
            .setPaymentConditionCode(AiToolArgs.optText(args, "paymentConditionCode"))
            .setCurrencyCode(AiToolArgs.optText(args, "currencyCode"))
            .setFiscalPositionCode(AiToolArgs.optText(args, "fiscalPositionCode"));

    JsonNode address = args == null ? null : args.get("address");
    if (address != null && address.isObject()) {
      spec.setAddress(
          new SupplierMaintenanceService.AddressSpec()
              .setStreet(AiToolArgs.optText(address, "street"))
              .setNumber(AiToolArgs.optText(address, "number"))
              .setZip(AiToolArgs.optText(address, "zip"))
              .setCity(AiToolArgs.optText(address, "city"))
              .setCountryIso(AiToolArgs.optText(address, "countryIso"))
              .setRaw(AiToolArgs.optText(address, "raw")));
    }
    JsonNode bank = args == null ? null : args.get("bankAccount");
    if (bank != null && bank.isObject()) {
      spec.setBankAccount(
          new SupplierMaintenanceService.BankAccountSpec()
              .setIban(AiToolArgs.optText(bank, "iban"))
              .setBic(AiToolArgs.optText(bank, "bic"))
              .setBankName(AiToolArgs.optText(bank, "bankName"))
              .setLabel(AiToolArgs.optText(bank, "label")));
    }
    JsonNode contact = args == null ? null : args.get("contact");
    if (contact != null && contact.isObject()) {
      spec.setContact(
          new SupplierMaintenanceService.ContactSpec()
              .setName(AiToolArgs.optText(contact, "name"))
              .setFirstName(AiToolArgs.optText(contact, "firstName"))
              .setEmail(AiToolArgs.optText(contact, "email"))
              .setPhone(AiToolArgs.optText(contact, "phone")));
    }
    return spec;
  }
}
