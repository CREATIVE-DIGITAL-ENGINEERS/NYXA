package com.axelor.apps.aiaccountant.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The task-to-capability mapping, and the one thing it has to stay true to: the playbooks.
 *
 * <p>No registry here — that check lives beside the tools, where the registry exists. What is
 * asserted is the part that drifts on its own: a playbook gains a sentence naming a tool from an
 * area its task never declared, and the mapping quietly stops describing the system while still
 * looking authoritative.
 */
class AiTaskCapabilityTest {

  /**
   * Which area each tool belongs to, restated here rather than read from the registry.
   *
   * <p>A duplicate on purpose, and a small one: the registry needs Guice and a MetaScanner sweep,
   * which is a heavy dependency for a text check, and stating the expectation independently is what
   * makes this a test rather than a tautology. {@code BgOcrToolCapabilityTest} asserts these same
   * fourteen names against the real registry, so the two cannot drift apart silently.
   */
  private static final Map<String, AiCapability> TOOL_CAPABILITY = new LinkedHashMap<>();

  static {
    TOOL_CAPABILITY.put("get_current_user_info", AiCapability.CORE);
    TOOL_CAPABILITY.put("search_ocr_records", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("get_ocr_record", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("get_ocr_evidence", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("check_invoice_amounts", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("edit_supplier_invoice", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("correct_ocr_document_total", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("validate_supplier_invoice", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("ventilate_supplier_invoice", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("refresh_supplier_invoice_terms", AiCapability.SUPPLIER_INVOICE);
    TOOL_CAPABILITY.put("find_supplier", AiCapability.PARTNER);
    TOOL_CAPABILITY.put("manage_supplier", AiCapability.PARTNER);
    TOOL_CAPABILITY.put("find_product", AiCapability.ACCOUNTING);
    TOOL_CAPABILITY.put("apply_exact_product_code", AiCapability.ACCOUNTING);
    TOOL_CAPABILITY.put("check_partner_accounts", AiCapability.ACCOUNTING);
    TOOL_CAPABILITY.put("fix_partner_account", AiCapability.ACCOUNTING);
    TOOL_CAPABILITY.put("search_moves", AiCapability.JOURNAL_ENTRY);
    TOOL_CAPABILITY.put("get_move", AiCapability.JOURNAL_ENTRY);
    TOOL_CAPABILITY.put("create_move", AiCapability.JOURNAL_ENTRY);
    TOOL_CAPABILITY.put("edit_move", AiCapability.JOURNAL_ENTRY);
    TOOL_CAPABILITY.put("post_move", AiCapability.JOURNAL_ENTRY);
    TOOL_CAPABILITY.put("search_bank_statements", AiCapability.BANK_STATEMENT);
    TOOL_CAPABILITY.put("get_bank_statement", AiCapability.BANK_STATEMENT);
    TOOL_CAPABILITY.put("run_bank_statement_import", AiCapability.BANK_STATEMENT);
    TOOL_CAPABILITY.put("search_customer_invoices", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("get_customer_invoice", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("create_customer_invoice", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("edit_customer_invoice", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("validate_customer_invoice", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("ventilate_customer_invoice", AiCapability.CUSTOMER_INVOICE);
    TOOL_CAPABILITY.put("refresh_customer_invoice_terms", AiCapability.CUSTOMER_INVOICE);
    // A Partner lookup, exactly as find_supplier is: the role narrows the search, it does not make
    // the tool part of the invoice area.
    TOOL_CAPABILITY.put("find_customer", AiCapability.PARTNER);
  }

  @Test
  void everyTaskCarriesCore() {
    for (AiTask task : AiTask.values()) {
      assertTrue(
          task.capabilities().contains(AiCapability.CORE),
          task + " must be able to know who is asking and in which company");
    }
  }

  /** GENERAL is not supplier-invoice work, and its capabilities have to say so. */
  @Test
  void generalIsCoreOnly() {
    assertEquals(1, AiTask.GENERAL.capabilities().size());
    assertTrue(AiTask.GENERAL.capabilities().contains(AiCapability.CORE));
  }

  /**
   * The load-bearing check. Every tool a playbook names must belong to an area its task declares —
   * otherwise the mapping describes a job the playbook is not actually asking for.
   */
  @Test
  void everyToolAPlaybookNamesIsWithinItsTasksCapabilities() {
    for (AiTask task : AiTask.values()) {
      String playbook = AiPlaybookLibrary.playbook(task);
      if (playbook == null) {
        continue;
      }
      Set<AiCapability> declared = task.capabilities();
      for (String tool : AiPromptResource.toolNames(playbook)) {
        AiCapability capability = TOOL_CAPABILITY.get(tool);
        assertTrue(capability != null, tool + " is named by " + task + " but is not classified");
        assertTrue(
            declared.contains(capability),
            task + " names " + tool + " (" + capability + ") but does not declare that capability");
      }
    }
  }

  /**
   * POST is the widest, and for the AI-3.1 reason: reaching POSTED can require repairing a supplier
   * or a payable account first, so its declared set covers the route rather than the last call.
   */
  @Test
  void postingDeclaresTheWholeRouteNotJustTheFinalCall() {
    Set<AiCapability> post = AiTask.POST_SUPPLIER_INVOICE.capabilities();
    assertEquals(4, post.size(), "posting can reach every area today");
    assertTrue(
        post.contains(AiCapability.PARTNER), "a posting turn may still have to fix a supplier");
    assertTrue(post.contains(AiCapability.ACCOUNTING), "and a payable account");
  }

  /** Creating a draft never posts anything, so it has no business in the chart of accounts. */
  @Test
  void creatingADraftDoesNotClaimAccounting() {
    Set<AiCapability> create = AiTask.CREATE_SUPPLIER_INVOICE.capabilities();
    assertTrue(create.contains(AiCapability.PARTNER), "creating is mostly a supplier problem");
    assertFalse(
        create.contains(AiCapability.ACCOUNTING),
        "a DRAFT needs no payable account until it is validated");
  }

  /** Repair and validate work on an existing invoice; neither creates a supplier. */
  @Test
  void repairAndValidateDoNotClaimPartner() {
    for (AiTask task :
        new AiTask[] {AiTask.REPAIR_SUPPLIER_INVOICE, AiTask.VALIDATE_SUPPLIER_INVOICE}) {
      assertFalse(
          task.capabilities().contains(AiCapability.PARTNER),
          task + " has no supplier tool in its allowed list");
      assertTrue(task.capabilities().contains(AiCapability.ACCOUNTING));
    }
  }

  /**
   * The measurement behind the per-task exposure question, kept honest as a test rather than left
   * in a report: on a supplier-invoice turn the shared layers already name tools from every area
   * that domain's tasks declare, so narrowing the tool list per task cannot narrow much while the
   * domain guide stays shared.
   *
   * <p>Scoped to one domain deliberately. It used to assert this over {@code
   * AiCapability.values()}, which was the same statement only while supplier invoices were the only
   * domain with tasks: a journal-entry tool has no business being named in the supplier-invoice
   * guide, and the day a second domain arrived that assertion started measuring nothing.
   */
  @Test
  void theSharedLayersAlreadyReachEveryCapabilityOfTheirOwnDomain() {
    Set<String> shared =
        new LinkedHashSet<>(AiPromptResource.toolNames(AiOperatingProcedure.text()));
    shared.addAll(
        AiPromptResource.toolNames(AiPlaybookLibrary.domainGuide(AiTask.POST_SUPPLIER_INVOICE)));

    for (AiCapability capability : AiTask.POST_SUPPLIER_INVOICE.capabilities()) {
      if (capability == AiCapability.CORE) {
        continue;
      }
      boolean present = false;
      for (String tool : shared) {
        if (capability == TOOL_CAPABILITY.get(tool)) {
          present = true;
          break;
        }
      }
      assertTrue(
          present,
          capability
              + " is not named by the procedure or the domain guide — if that becomes true,"
              + " per-task tool exposure is worth measuring again");
    }
  }
}
