package com.axelor.apps.aiaccountant.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.route.AiDomain;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Routing, which decides which page of instructions the model gets.
 *
 * <p>A wrong route is not a safety problem — every guard is in Java — but it is a quality one: the
 * model looks as though it forgot a rule it was never given. The cases below are the ones where
 * getting it wrong would be invisible in the answer.
 */
class AiTaskResolverTest {

  private static AiConversationState state(String pendingRequest, String shape) {
    AiConversationState s = new AiConversationState();
    if (pendingRequest != null) {
      s.setPending(true);
      s.setRequest(pendingRequest);
    }
    if (shape != null) {
      s.setShape(shape);
      s.setShapeTarget("Invoice#606");
      s.setShapeOk(true);
    }
    return s;
  }

  /**
   * Supplier master data (Purchases → Suppliers) is its own write task. Before, these either
   * authorised nothing or were read as supplier-INVOICE repairs, and the turn ended in a refusal.
   */
  @Test
  void supplierMasterDataRoutesToTheSupplierTasks() {
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiTaskResolver.fromMessage("смени IBAN-а на доставчик Лидл на BG80BNBG96611020345678"));
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiTaskResolver.fromMessage("обнови адреса на доставчика: ул. Витоша 1, София"));
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiTaskResolver.fromMessage("създай доставчик Алфа ООД с ЕИК 203970390"));
    assertEquals(
        AiTask.REVIEW_SUPPLIER, AiTaskResolver.fromMessage("какъв е IBAN-ът на доставчик Лидл?"));
    assertTrue(AiTask.MAINTAIN_SUPPLIER.authorisesWrites());
    assertFalse(AiTask.REVIEW_SUPPLIER.authorisesWrites());
    // Changing WHICH supplier an invoice has is still invoice work.
    assertEquals(
        AiTask.REPAIR_SUPPLIER_INVOICE, AiTaskResolver.fromMessage("смени доставчика на 946"));
    assertEquals(
        AiTask.REPAIR_SUPPLIER_INVOICE,
        AiTaskResolver.fromMessage("смени IBAN-а на доставчика във фактурата"));
    // A change verb outside the resolver's list must still authorise writes.
    assertTrue(AiTaskResolver.fromMessage("актуализирай телефона").authorisesWrites());
    // From the dashboard or the list: the supplier is named by its ЕИК or its name.
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiTaskResolver.fromMessage("смени телефона на доставчик с ЕИК 131071587 на 0888123456"));
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiTaskResolver.fromMessage("промени имейла на доставчик „Алфа Трейд“ ЕООД на a@b.bg"));
    assertEquals(
        AiTask.REPAIR_SUPPLIER_INVOICE,
        AiTaskResolver.fromMessage("Смени доставчика на invoice 946"));
  }

  @Test
  void routesEachDestinationToItsOwnPlaybook() {
    assertEquals(
        AiTask.POST_SUPPLIER_INVOICE, AiTaskResolver.resolve("осчетоводи фактурата", null));
    assertEquals(AiTask.POST_SUPPLIER_INVOICE, AiTaskResolver.resolve("публикувай я", null));
    assertEquals(AiTask.VALIDATE_SUPPLIER_INVOICE, AiTaskResolver.resolve("валидирай я", null));
    assertEquals(
        AiTask.CREATE_SUPPLIER_INVOICE, AiTaskResolver.resolve("направи чернова от 946", null));
    assertEquals(AiTask.REPAIR_SUPPLIER_INVOICE, AiTaskResolver.resolve("оправи редовете", null));
    assertEquals(
        AiTask.REVIEW_SUPPLIER_INVOICE, AiTaskResolver.resolve("провери тази фактура", null));
  }

  /**
   * A create verb with no "чернов"/"draft" word still routes to create — the same {@code isCreate}
   * fallback {@code journalEntryTask}/{@code customerInvoiceTask} already had, applied here too.
   * Before this fix the message fell through to {@code GENERAL} and then, because it mentions
   * "фактура", to {@code REVIEW_SUPPLIER_INVOICE} instead.
   */
  @Test
  void aCreateVerbRoutesToCreateWithoutTheWordDraft() {
    assertEquals(
        AiTask.CREATE_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("създай фактура за доставчика Иванов", null));
  }

  /** The furthest destination wins: the checking and the fixing are steps on the way to it. */
  @Test
  void aDestinationBeatsTheStepsThatReachIt() {
    assertEquals(
        AiTask.POST_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("провери я, оправи я и я осчетоводи", null));
    assertEquals(
        AiTask.VALIDATE_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("оправи грешките и валидирай", null));
    assertEquals(
        AiTask.POST_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("оправи draft-а и я осчетоводи", null),
        "posting wins over the word 'draft'");
  }

  @Test
  void continuationInheritsTheTaskItResumes() {
    // The instruction being resumed is read from the job the SERVER holds, not from the blob the
    // browser returned. Reading it from the blob would let a client choose the next turn's
    // playbook by choosing what to send back — see AiWorkflowController.resume.
    assertEquals(
        AiTask.POST_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("продължи", null, job("оправи ги всичките и ги осчетоводи")));
    assertEquals(
        AiTask.CREATE_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("продължи", null, job("направи чернови от останалите")));
    // Nothing to resume: a bare continuation with no stored job routes to nothing special. A blob
    // that claims an instruction changes nothing, which is the point.
    assertEquals(AiTask.GENERAL, AiTaskResolver.resolve("продължи", null));
    assertEquals(
        AiTask.GENERAL,
        AiTaskResolver.resolve("продължи", state("оправи ги всичките и ги осчетоводи", null)),
        "the blob is not a job");
  }

  /**
   * An active stored job carrying the instruction that authorised it.
   *
   * <p>A destination and at least one target, because that is what {@code AiWorkflow.isActive}
   * requires — and only an active job is resumable, so a helper that produced an inactive one would
   * be testing the fallback rather than the inheritance.
   */
  private static AiWorkflow job(String request) {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setRequest(request);
    workflow.setGoal("POSTED");
    workflow.addTarget("Upload#941");
    return workflow;
  }

  @Test
  void sameAgainTakesTheTaskFromTheRecentShape() {
    assertEquals(
        AiTask.POST_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("същото и с тази", state(null, "EDIT_VALIDATE_VENTILATE")));
    assertEquals(
        AiTask.VALIDATE_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("същото и с тази", state(null, "EDIT_VALIDATE")));
    assertEquals(
        AiTask.REPAIR_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("същото и с тази", state(null, "EDIT")));
  }

  /** GENERAL is for questions that are not supplier-invoice work at all. */
  @Test
  void generalGetsNoPlaybook() {
    assertEquals(AiTask.GENERAL, AiTaskResolver.resolve("коя е активната фирма?", null));
    assertEquals(AiTask.GENERAL, AiTaskResolver.resolve("здравей", null));
    assertEquals(AiTask.GENERAL, AiTaskResolver.resolve(null, null));
    assertNull(AiPlaybookLibrary.playbook(AiTask.GENERAL));
    assertEquals("-", AiTask.GENERAL.playbookId());
  }

  /**
   * A message about an invoice with no verb still gets the review playbook. Guessing GENERAL there
   * would leave the model with no procedure for the thing it is plainly being asked about.
   */
  @Test
  void invoiceTalkWithoutAVerbFallsBackToReview() {
    assertEquals(AiTask.REVIEW_SUPPLIER_INVOICE, AiTaskResolver.resolve("а тази фактура?", null));
    assertEquals(
        AiTask.REVIEW_SUPPLIER_INVOICE,
        AiTaskResolver.resolve("нещо с този запис не е наред", null));
  }

  @Test
  void everyNonGeneralTaskHasAPlaybookThatLoads() {
    for (AiTask task : AiTask.values()) {
      if (task == AiTask.GENERAL) {
        continue;
      }
      String playbook = AiPlaybookLibrary.playbook(task);
      assertNotNull(playbook, task + " has no playbook");
      assertTrue(playbook.startsWith("[Playbook:"), task + " playbook has no header");
      for (String heading : Arrays.asList("GOAL", "PROCEDURE", "DONE WHEN")) {
        assertTrue(playbook.contains(heading), task + " playbook is missing " + heading);
      }
    }
    assertEquals(AiTask.values().length - 1, AiPlaybookLibrary.count());
  }

  /** The task-specific guards, which stay in the playbook that owns the operation. */
  @Test
  void playbooksKeepTheGuardsTheyInherited() {
    String post = AiPlaybookLibrary.playbook(AiTask.POST_SUPPLIER_INVOICE);
    assertTrue(post.contains("acknowledgeDuplicate"), "duplicate acknowledgement");
    assertTrue(post.contains("PFP"));
    assertTrue(post.contains("NEVER POST when the user did not ask you to"));

    String validate = AiPlaybookLibrary.playbook(AiTask.VALIDATE_SUPPLIER_INVOICE);
    assertTrue(validate.contains("NEVER set that flag on your own"));

    String create = AiPlaybookLibrary.playbook(AiTask.CREATE_SUPPLIER_INVOICE);
    assertTrue(create.contains("bankAccount.iban"), "bank details are create's own subject");
    assertTrue(create.contains("settlement"), "so is the address");

    String review = AiPlaybookLibrary.playbook(AiTask.REVIEW_SUPPLIER_INVOICE);
    assertTrue(review.contains("This task writes nothing"));
  }

  /**
   * The cross-task invariants live in one shared file, because the task that needs them is often
   * not the task that owns them — a POST turn repairs lines it was never routed to repair.
   */
  @Test
  void theDomainGuideCarriesTheCrossTaskInvariants() {
    String domain = AiPlaybookLibrary.domainGuide(AiTask.POST_SUPPLIER_INVOICE);
    assertNotNull(domain);
    assertTrue(domain.startsWith("[Domain:"), "the model keys on the header");

    // Line fidelity — the rule with the most expensive failure, and one a POST turn must not
    // arrive without.
    assertTrue(domain.contains("KEEP THE DOCUMENT'S LINES"));
    assertTrue(domain.contains("As per supplier invoice"));
    // Evidence.
    assertTrue(domain.contains("get_ocr_evidence"));
    assertTrue(domain.contains("Never guess a supplier, a product, a VAT rate or an amount"));
    assertTrue(domain.contains("does NOT mean an invoice is usable"));
    // Reconciliation vocabulary and the header-total invariant.
    assertTrue(domain.contains("only EXACT means the figures agree"));
    assertTrue(domain.contains("accountingImpact=NONE"));
    assertTrue(domain.contains("accountingTotals"));
    assertTrue(domain.contains("You can NEVER change them"));
    // Supplier and partner-account prerequisites.
    assertTrue(domain.contains("never create a second partner for one"));
    assertTrue(domain.contains("fix_partner_account"));
    assertTrue(domain.contains("useForPartnerBalance"));
    // Safe deterministic repair.
    assertTrue(domain.contains("ONE CORRECTION, ONE edit_supplier_invoice CALL"));
    assertTrue(domain.contains("Java rejects anything that does not add up"));
  }

  /**
   * Every task about the same kind of document gets the same guide instance; GENERAL gets none.
   *
   * <p>Grouped by {@link AiTask#domain()}, not flattened to one guide for every task: {@code
   * AiTask} now spans four domains (supplier invoice, customer invoice, journal entry, bank
   * statement), each with its own file — see the class javadoc on {@code domain()}, "before there
   * was a second domain, everything that was not GENERAL was a supplier invoice". A single shared
   * guide was only ever true while that was.
   */
  @Test
  void everyTaskSharesItsDomainsOneGuide() {
    Map<AiDomain, String> expectedByDomain = new EnumMap<>(AiDomain.class);
    for (AiTask task : AiTask.values()) {
      if (task == AiTask.GENERAL) {
        assertNull(AiPlaybookLibrary.domainGuide(task), "GENERAL pays for no domain guide");
        assertFalse(task.hasDomainGuide());
        continue;
      }
      String guide = AiPlaybookLibrary.domainGuide(task);
      assertNotNull(guide, task + " must have a domain guide");
      String expected = expectedByDomain.computeIfAbsent(task.domain(), d -> guide);
      assertSame(expected, guide, task + " has its own copy of " + task.domain() + "'s guide");
    }
    assertEquals(
        expectedByDomain.size(),
        AiPlaybookLibrary.domainCount(),
        "one file per domain, counted once, not once per task");
  }

  /**
   * The prompts must not offer a tool the model was never given.
   *
   * <p>The startup listener does the real check against the live registry, which a unit test cannot
   * reach. These two names stand for the two shapes of the problem: {@code lookup_bg_city} does not
   * exist, and {@code apply_exact_product_code} is registered but deliberately unpublished, so a
   * playbook offering it offers a call the model can only fail.
   */
  @Test
  void namesNoToolTheModelCannotCall() {
    Set<String> mentioned = AiPlaybookLibrary.toolNamesMentioned();
    assertTrue(mentioned.contains("edit_supplier_invoice"), "the extractor has to actually work");
    assertTrue(mentioned.size() > 8, "and to find the rest of them");

    assertFalse(mentioned.contains("apply_exact_product_code"), "registered but not published");
    assertFalse(mentioned.contains("lookup_bg_city"), "does not exist; costs an iteration");

    // Tool outcomes are quoted as values, not called, so they must not be mistaken for tools.
    for (String value : Arrays.asList("not_found", "not_executed", "already_failed")) {
      assertFalse(mentioned.contains(value), value + " is a result, not a tool");
    }
  }

  /**
   * The other direction of {@link #playbooksDoNotRestateTheSharedRules()}: the universal layer must
   * not restate the domain guide either — for example the supplier-invoice posting route, which the
   * POST playbook already carries.
   */
  @Test
  void theProcedureDoesNotRestateTheDomainGuide() {
    String procedure = AiOperatingProcedure.text();
    for (String shared :
        Arrays.asList(
            "KEEP THE DOCUMENT'S LINES",
            "ONE CORRECTION, ONE edit_supplier_invoice CALL",
            "useForPartnerBalance",
            "only EXACT means the figures agree",
            "get_ocr_evidence",
            "refresh_supplier_invoice_terms")) {
      assertFalse(procedure.contains(shared), "universal layer restates '" + shared + "'");
    }
    // What it does keep is a genuinely universal rule from the same posting route, not
    // "accountingTotals" itself — that field name is supplier-invoice vocabulary the domain guide
    // owns, and asserting it here would be exactly the restatement this test exists to catch.
    assertTrue(procedure.contains("EMIT THE WHOLE AUTHORISED WORKFLOW IN ONE RESPONSE"));
  }

  /**
   * The shared rules were moved, not copied. A playbook that kept its own version would drift away
   * from the guide the first time one of them was edited.
   */
  @Test
  void playbooksDoNotRestateTheSharedRules() {
    for (AiTask task : AiTask.values()) {
      if (task == AiTask.GENERAL) {
        continue;
      }
      String playbook = AiPlaybookLibrary.playbook(task);
      assertFalse(playbook.contains("KEEP THE DOCUMENT'S LINES"), task + " restates line fidelity");
      assertFalse(playbook.contains("As per supplier invoice"), task + " restates the bad example");
      assertFalse(
          playbook.contains("ONE CORRECTION, ONE edit_supplier_invoice CALL"),
          task + " restates the edit-call rule");
      assertFalse(
          playbook.contains("accountingImpact=NONE"), task + " restates the rounding verdict");
      assertFalse(
          playbook.contains("useForPartnerBalance"), task + " restates the partner-account rule");
    }
  }
}
