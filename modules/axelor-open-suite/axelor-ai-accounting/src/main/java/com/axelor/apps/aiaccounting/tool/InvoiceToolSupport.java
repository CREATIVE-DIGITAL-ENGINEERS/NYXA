package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceBlocked;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSearchService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiaccounting.service.invoice.PartnerSearchService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small pieces every invoice tool needs, kept in one place rather than in each of them. */
final class InvoiceToolSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private InvoiceToolSupport() {}

  /**
   * A status name as the number the column holds, or null when none was given.
   *
   * <p>Names rather than numbers in the schema, because a model asked for "the validated ones"
   * should not have to know that validated is 2 — and an out-of-range number silently matching
   * nothing is worse than a refusal.
   */
  static Integer statusValue(String status) {
    if (status == null || status.trim().isEmpty()) {
      return null;
    }
    switch (status.trim().toUpperCase(Locale.ROOT)) {
      case "DRAFT":
        return InvoiceRepository.STATUS_DRAFT;
      case "VALIDATED":
        return InvoiceRepository.STATUS_VALIDATED;
      case "VENTILATED":
      case "POSTED":
        return InvoiceRepository.STATUS_VENTILATED;
      case "CANCELED":
      case "CANCELLED":
        return InvoiceRepository.STATUS_CANCELED;
      default:
        throw new AiToolException(
            "Unknown status '" + status + "'. Use DRAFT, VALIDATED, VENTILATED or CANCELED.");
    }
  }

  /**
   * Declares in the schema that exactly one of two alternative arguments must be given.
   *
   * <p>{@code oneOf} of two single-{@code required} branches says precisely that: with both present
   * both branches match and {@code oneOf} fails; with neither, neither matches. So the schema now
   * states the same rule {@link #requireExactlyOne} enforces, instead of leaving the model to
   * discover it from a refusal after the call. Sits beside {@code additionalProperties: false}
   * without weakening it — that governs which names may appear, this governs which combinations of
   * them are a valid call.
   */
  static void declareExactlyOne(ObjectNode schema, String first, String second) {
    ArrayNode oneOf = schema.putArray("oneOf");
    oneOf.addObject().putArray("required").add(first);
    oneOf.addObject().putArray("required").add(second);
  }

  /**
   * Declares that two alternative arguments may not both be given, while neither is required.
   *
   * <p>For a pair that is optional as a pair — {@code customerId}/{@code customerCode} on an edit,
   * where moving the invoice to another customer is one thing the call may do and usually is not.
   * {@code oneOf} would make the pair mandatory; this says only what is actually true.
   */
  static void declareAtMostOne(ObjectNode schema, String first, String second) {
    ArrayNode both = schema.putObject("not").putArray("required");
    both.add(first);
    both.add(second);
  }

  /**
   * Exactly one of two alternative arguments must be present.
   *
   * <p>Enforced in Java as well as declared in the schema by {@link #declareExactlyOne}: the schema
   * tells the model the rule, and this is what actually holds when a call arrives that does not
   * follow it. Both present is a refusal rather than a preference, because silently choosing one is
   * how an invoice ends up pointing at whichever record the tool happened to look at first.
   */
  static void requireExactlyOne(JsonNode args, String first, String second) {
    boolean hasFirst = args != null && args.hasNonNull(first);
    boolean hasSecond = args != null && args.hasNonNull(second);
    if (hasFirst && hasSecond) {
      throw new AiToolException("Give either " + first + " or " + second + ", not both.");
    }
    if (!hasFirst && !hasSecond) {
      throw new AiToolException("Give either " + first + " or " + second + ".");
    }
  }

  /**
   * The partner an id-or-code pair names, checked for the role the caller needs.
   *
   * <h2>Why a partner without the role is a refusal, not a warning</h2>
   *
   * Raising an invoice for a partner nobody has marked as a customer — or a supplier — commits it
   * to a role it was not given. The lookup tools ({@code find_customer}, {@code find_supplier})
   * report such a partner rather than hiding it, precisely so the caller can say "this company
   * exists but is not marked as a customer" and ask. Doing that quietly here instead, on a write,
   * would set the role by side effect of the first invoice.
   *
   * <p>A code that matches more than one partner is likewise a refusal. There is no tiebreak worth
   * having: an invoice addressed to the wrong company is a wrong bill sent to a real person.
   *
   * @param idField the argument holding a database id, e.g. {@code customerId}
   * @param codeField the argument holding the ЕИК or VAT number, e.g. {@code customerCode}
   */
  static Partner resolvePartner(
      JsonNode args,
      PartnerSearchService partners,
      PartnerSearchService.Role role,
      String idField,
      String codeField) {
    Long partnerId = AiToolArgs.optId(args, idField);
    Partner partner;
    if (partnerId != null) {
      partner = partners.findScoped(partnerId);
      if (partner == null) {
        throw new AiToolException("No partner with id " + partnerId + " in this company.");
      }
    } else {
      String code = AiToolArgs.requireText(args, codeField);
      partner = byCode(partners, role, code);
    }
    if (!partners.hasRole(partner, role)) {
      throw new AiToolException(
          "Partner "
              + partner.getId()
              + " ("
              + partner.getFullName()
              + ") is not marked as a "
              + role.label()
              + ". Say so and ask whether the flag should be set, rather than creating a second"
              + " partner for the same company.");
    }
    return partner;
  }

  /** The one partner a registration code or VAT number names, or a refusal. */
  private static Partner byCode(
      PartnerSearchService partners, PartnerSearchService.Role role, String code) {
    PartnerSearchService.SearchResult found;
    try {
      // One search covers both: as a VAT number the code is normalised to the ЕИК when it is a BG
      // one (matching registrationCode or taxNbr), and compared as stored when it is foreign.
      PartnerSearchService.Criteria byCode = new PartnerSearchService.Criteria();
      byCode.role = role;
      byCode.taxNumber = code;
      byCode.limit = 2;
      found = partners.search(byCode);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
    if (found.getMatches().isEmpty()) {
      throw new AiToolException(
          "No partner has the registration code or VAT number '" + code + "'.");
    }
    if (!found.isUnique()) {
      throw new AiToolException("More than one partner has '" + code + "'; give the id instead.");
    }
    return found.sole();
  }

  /** Turns a business refusal into the tool exception that carries its code. */
  static AiToolException asToolException(RuntimeException e) {
    if (e instanceof InvoiceBlocked) {
      return ((InvoiceBlocked) e).asToolException();
    }
    return new AiToolException(e.getMessage());
  }

  /**
   * A schema object whose properties are exactly the fields the services will accept.
   *
   * <h2>Generated, not written out</h2>
   *
   * The alternative is a hand-written schema beside a hand-written allowlist, and they agree until
   * somebody edits one of them. Deriving the schema from the same specs the apply code checks
   * against makes that impossible — and it is the only way a field a localisation module
   * contributes can appear in the schema at all, since nothing here knows those exist until they
   * are asked for.
   *
   * @param specs the field list, from {@code InvoiceHeaderEditService.fieldSpecs} or the line one
   * @param description what this object is, for the model
   */
  static ObjectNode fieldObjectSchema(List<AiInvoiceFieldSpec> specs, String description) {
    ObjectNode object = MAPPER.createObjectNode();
    object.put("type", "object");
    object.put("description", description);
    ObjectNode properties = object.putObject("properties");
    for (AiInvoiceFieldSpec spec : specs) {
      ObjectNode property = properties.putObject(spec.getName());
      declareType(property, spec);
      String text = spec.getDescription() == null ? "" : spec.getDescription();
      if (spec.isClearable() && !mentionsClearing(text)) {
        text = text.isEmpty() ? "Send null to clear." : text + " Send null to clear.";
      }
      if (!text.isEmpty()) {
        property.put("description", text);
      }
      if (!spec.getAllowedValues().isEmpty()) {
        ArrayNode allowed = property.putArray("enum");
        spec.getAllowedValues().forEach(allowed::add);
        if (spec.isClearable()) {
          // An enum without null in it rejects the very value the field documents as its way of
          // being cleared.
          allowed.addNull();
        }
      }
    }
    declareAtMostOnePerPair(object, properties);
    // Closed: a field name that is not here is a mistake worth telling the model about rather than
    // one to discover from a rejection after the call.
    object.put("additionalProperties", false);
    return object;
  }

  /**
   * Whether a field description already tells the model that null clears it.
   *
   * <p>The specs say it in two wordings ("Send null to clear", "Null clears it"); appending the
   * generic hint to either repeats the same sentence in every clearable property.
   */
  static boolean mentionsClearing(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return lower.contains("null to clear") || lower.contains("null clears");
  }

  /**
   * The JSON type, widened to accept null exactly where the apply code does.
   *
   * <p>{@code "type": "integer"} and a description reading "Null clears it" contradict each other,
   * and a validating client resolves the contradiction by refusing the clear. So a clearable field
   * declares {@code ["integer", "null"]} and a non-clearable one declares the bare type — which is
   * now also a true statement, because the apply code refuses null on those.
   */
  private static void declareType(ObjectNode property, AiInvoiceFieldSpec spec) {
    if (!spec.isClearable()) {
      property.put("type", spec.getType().json());
      return;
    }
    ArrayNode types = property.putArray("type");
    types.add(spec.getType().json());
    types.add("null");
  }

  /**
   * At most one of each {@code …Id}/{@code …Code} pair the object happens to declare.
   *
   * <p>The two name the same reference from two sides, so sending both is a caller that does not
   * know which record it means — and the apply loop walks the object's keys in order, which would
   * quietly make the later one win. {@code allOf} of {@code not: {required: [both]}} is the plain
   * way to say "never both" without making either one required: the whole object stays optional, as
   * it must, since a header edit may name neither.
   *
   * <p>Derived from the properties actually present rather than from a hardcoded list of fields, so
   * a pair a contributed field adds is covered by the same rule. {@code bankDetailsId}/{@code
   * bankIban} is the one pair whose two names do not follow the {@code …Id}/{@code …Code}
   * convention, so it is named.
   */
  private static void declareAtMostOnePerPair(ObjectNode object, ObjectNode properties) {
    ArrayNode exclusions = MAPPER.createArrayNode();
    Iterator<String> names = properties.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!name.endsWith("Id")) {
        continue;
      }
      String alternative =
          "bankDetailsId".equals(name) ? "bankIban" : name.substring(0, name.length() - 2) + "Code";
      if (properties.has(alternative)) {
        ArrayNode both = exclusions.addObject().putObject("not").putArray("required");
        both.add(name);
        both.add(alternative);
      }
    }
    if (exclusions.size() > 0) {
      object.set("allOf", exclusions);
    }
  }

  /**
   * Whether an invoice creation whose answer was lost actually landed.
   *
   * <h2>An existence test on a key the attempt itself produced</h2>
   *
   * {@code InvoiceCreationService} saves through the repository and flushes inside its transaction,
   * and reports the saved id through {@code AiWriteEffects} before that transaction commits. So
   * after a lost answer the primary key is known and only the <em>commit's</em> outcome is in
   * doubt: the row is there, so the commit landed; it is not, so the transaction rolled back and
   * nothing was created. Either answer is a proof, which is what keeps the model from repeating a
   * create that may already be in the books.
   *
   * <p>Deliberately NOT a search for an invoice with this partner, date and total. Two identical
   * invoices are a legitimate thing for an accountant to have, so such a search would confirm the
   * wrong one as readily as the right one — and a wrong "created" ends the conversation on a
   * duplicate nobody goes looking for again.
   *
   * <p>{@code findScoped} is company-scoped and side-checked, so another company's row, or an
   * invoice on the other side, never confirms this write.
   */
  static AiWriteOutcome verifyCreated(AiWriteProbe probe, InvoiceSide side) {
    Long created = probe == null ? null : probe.newId(Invoice.class);
    if (created == null) {
      // The attempt never got as far as saving, so there is no identity to check. Informative, but
      // not proof: the reporting call sits inside the same transaction, and a failure between the
      // save and it would look identical from here.
      return AiWriteOutcome.unknown();
    }
    Invoice invoice = Beans.get(InvoiceSearchService.class).findScoped(created, side);
    if (invoice == null) {
      return AiWriteOutcome.notWritten();
    }
    Map<String, Object> out =
        new LinkedHashMap<>(
            Beans.get(InvoiceViewService.class)
                .writeSummary(invoice, Collections.singletonList("created")));
    out.put("created", true);
    out.put(
        "nextStep",
        "The invoice is a DRAFT. NOTE: the connection was lost while it was being created, so it"
            + " was confirmed by re-reading it rather than reported by the write itself — show the"
            + " user what is on it before anything else is done to it, and do NOT create another"
            + " one.");
    return AiWriteOutcome.completed(out);
  }
}
