package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiCompletionRegistry;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiWorkflowController;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolBatchRunner;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import com.axelor.apps.aiocr.service.ai.PartnerBankAccountCompletion;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The real production path, end to end: {@link ManageSupplierTool}'s own {@code targetKey}/{@code
 * completionTarget} mapping, run through the real {@link AiToolBatchRunner}, folded by the real
 * {@link AiWorkflowController}, and resolved by the real {@link PartnerBankAccountCompletion}.
 *
 * <h2>Why this suite exists</h2>
 *
 * A prior pass proved {@link PartnerBankAccountCompletion} sound against a hand-built {@code
 * PartnerBankAccount:...} string, which proved the resolver but nothing about whether production
 * ever produces that string: {@code ManageSupplierTool.targetKey} still answers a plain {@code
 * Partner#id}, and nothing wired the typed reference into the ledger a real {@code manage_supplier}
 * call feeds the workflow through. These tests run the actual mapping — {@link
 * ManageSupplierTool#completionTarget}, read by {@link AiToolBatchRunner#completionTargetOf} — with
 * no database and no Guice injector: {@code targetKey}/{@code completionTarget} only read the
 * call's own arguments and {@code AiCompanyScope}'s thread-local company id, and {@code dispatch}
 * is stubbed to answer canned envelopes rather than touching {@code SupplierMaintenanceService}.
 */
class ManageSupplierWorkflowTargetTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Long COMPANY_ID = 2L;
  private static final long SUPPLIER_ID = 84L;
  private static final String REQUESTED_IBAN = "BG75 UBBS 8002 1029 8411 18";
  private static final String NORMALISED_IBAN = "BG75UBBS80021029841118";
  private static final String OTHER_IBAN = "BG18RZBB91550123456789";

  private static final String FAILED_ENVELOPE =
      "{\"error\":\"tool_error\",\"message\":\"This IBAN is already registered on a different"
          + " partner.\"}";
  private static final String SUCCESS_ENVELOPE =
      "{\"created\":false,\"supplierId\":" + SUPPLIER_ID + "}";

  /**
   * Runs {@code manage_supplier(operation=update, supplierId, bankAccount.iban)} through the real
   * {@link AiToolBatchRunner} mapping, with {@link #dispatch} stubbed to a canned outcome — exactly
   * one model round trip's worth of work, the same granularity {@code AiAccountantServiceImpl}'s
   * own tool loop calls {@code executeAll} at.
   */
  private static final class Runner extends AiToolBatchRunner {
    private final ManageSupplierTool tool = new ManageSupplierTool();
    private String nextEnvelope;

    Runner() {
      super(null);
    }

    @Override
    protected AiTool findTool(String name) {
      return "manage_supplier".equals(name) ? tool : null;
    }

    @Override
    protected String dispatch(String name, String arguments) {
      return nextEnvelope;
    }

    void run(String bankAccountJson, String envelope) {
      nextEnvelope = envelope;
      String args =
          "{\"operation\":\"update\",\"supplierId\":"
              + SUPPLIER_ID
              + (bankAccountJson == null ? "" : ",\"bankAccount\":" + bankAccountJson)
              + "}";
      FoundryResponse.FunctionCall call =
          new FoundryResponse.FunctionCall("call_1", "manage_supplier", args);
      executeAll(Collections.singletonList(call), new ArrayList<>());
    }

    void runWithIban(String iban, String envelope) {
      run("{\"iban\":\"" + iban + "\"}", envelope);
    }
  }

  /**
   * A partner and its repository, kept apart from any particular set of accounts: {@code
   * PartnerBankAccountCompletion} re-reads {@link #partner} fresh every time it is asked, so a test
   * can mutate it BETWEEN two calls exactly as a successful {@code manage_supplier} write would —
   * proving the resolver reacts to a change in state, not to which call happened first.
   */
  private static final class FakePartner {
    final Partner partner = new Partner();
    final PartnerRepository repository =
        new PartnerRepository() {
          @Override
          public Partner find(Long id) {
            return SUPPLIER_ID == id ? partner : null;
          }
        };

    FakePartner() {
      partner.setBankDetailsList(new ArrayList<>());
    }

    /** The moment a successful write actually lands, as far as this fake is concerned. */
    void addBankAccount(String iban) {
      BankDetails details = new BankDetails();
      details.setIban(iban);
      partner.getBankDetailsList().add(details);
    }
  }

  /** {@code Boolean.TRUE} once one of {@code registeredIbans} (normalised) is on the partner. */
  private static PartnerRepository fakeRepository(String... registeredIbans) {
    FakePartner fake = new FakePartner();
    for (String iban : registeredIbans) {
      fake.addBankAccount(iban);
    }
    return fake.repository;
  }

  /**
   * The real {@link AiWorkflowController}, wired to the real {@link PartnerBankAccountCompletion}.
   */
  private static AiWorkflowController controllerWith(PartnerRepository repository) {
    AiCompletionResolver resolver =
        new PartnerBankAccountCompletion() {
          @Override
          protected PartnerRepository partnerRepository() {
            return repository;
          }
        };
    AiCompletionRegistry registry =
        new AiCompletionRegistry() {
          @Override
          public List<AiCompletionResolver> all() {
            return Collections.singletonList(resolver);
          }
        };
    return new AiWorkflowController() {
      @Override
      protected AiCompletionRegistry completionRegistry() {
        return registry;
      }
    };
  }

  private static String typedTarget(String normalisedIban) {
    return "PartnerBankAccount:" + COMPANY_ID + ":" + SUPPLIER_ID + ":" + normalisedIban;
  }

  private static String plainTarget() {
    return "Partner#" + SUPPLIER_ID;
  }

  /**
   * Authorisation's own identity is untouched: the real mapping still answers {@code Partner#id}.
   */
  @Test
  void authorisationStillUsesThePlainPartnerRef() throws Exception {
    String args =
        "{\"operation\":\"update\",\"supplierId\":"
            + SUPPLIER_ID
            + ",\"bankAccount\":{\"iban\":\""
            + REQUESTED_IBAN
            + "\"}}";
    ManageSupplierTool tool = new ManageSupplierTool();
    JsonNode parsed = MAPPER.readTree(args);

    assertEquals(plainTarget(), tool.targetKey(parsed));
    String completion =
        AiExecutionContext.callAsSystem(COMPANY_ID, "test", () -> tool.completionTarget(parsed));
    assertEquals(typedTarget(NORMALISED_IBAN), completion);
  }

  /**
   * Every scenario below needs the same company scope live for BOTH halves of the real path: {@code
   * completionTarget} reads it while a call runs, and {@link PartnerBankAccountCompletion} reads it
   * again afterwards, from {@code AiWorkflowController.update}/{@code settledAfterThisTurn} — so
   * the whole scenario runs inside one open scope rather than opening and closing it per call.
   */
  private static void inCompanyScope(Runnable work) {
    AiExecutionContext.runAsSystem(COMPANY_ID, "test", work);
  }

  @Test
  void aFailedBankAccountUpdateEmitsAndBlocksTheTypedTarget() {
    Runner runner = new Runner();
    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      inCompanyScope(
          () -> {
            runner.runWithIban(REQUESTED_IBAN, FAILED_ENVELOPE);

            List<AiTurnToolLog.Entry> entries = AiTurnToolLog.entries();
            assertEquals(1, entries.size());
            assertEquals(typedTarget(NORMALISED_IBAN), entries.get(0).getTarget());
            assertEquals(AiTurnToolLog.Status.FAILED, entries.get(0).getStatus());

            AiWorkflow workflow = new AiWorkflow();
            workflow.setWriteJob(true);
            workflow.setTask("REPAIR_SUPPLIER_INVOICE");
            AiWorkflowController controller = controllerWith(fakeRepository());
            controller.update(workflow, null);

            assertEquals(1, workflow.blocked().size());
            assertEquals(typedTarget(NORMALISED_IBAN), workflow.blocked().get(0).getRef());
            assertNull(
                workflow.find(plainTarget()),
                "the plain ref never enters the ledger for this call");
          });
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }
  }

  @Test
  void aSuccessfulRetryForTheExactIbanClearsTheBlocker() {
    Runner runner = new Runner();
    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    boolean[] settled = new boolean[2];
    try {
      inCompanyScope(
          () -> {
            AiWorkflow workflow = new AiWorkflow();
            workflow.setWriteJob(true);
            workflow.setTask("REPAIR_SUPPLIER_INVOICE");
            FakePartner fake = new FakePartner();
            AiWorkflowController controller = controllerWith(fake.repository);

            // The refusal: nothing on the partner yet, so the real resolver cannot say complete.
            runner.runWithIban(REQUESTED_IBAN, FAILED_ENVELOPE);
            settled[0] = controller.settledAfterThisTurn(workflow, null);

            // The moment the retry actually lands, exactly as a real write would leave it.
            fake.addBankAccount(NORMALISED_IBAN);
            runner.runWithIban(REQUESTED_IBAN, SUCCESS_ENVELOPE);
            settled[1] = controller.settledAfterThisTurn(workflow, null);
            controller.update(workflow, null);

            assertTrue(workflow.getCompleted().contains(typedTarget(NORMALISED_IBAN)));
            assertTrue(
                workflow.blocked().isEmpty(),
                "the exact-IBAN retry must clear the earlier refusal");
          });
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertFalse(settled[0], "the limit warning must survive while the refusal stands");
    assertTrue(settled[1], "the real retry path settles the job once it lands");
  }

  @Test
  void aRetryForADifferentIbanDoesNotClearTheOriginalBlocker() {
    Runner runner = new Runner();
    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      inCompanyScope(
          () -> {
            FakePartner fake = new FakePartner();

            runner.runWithIban(REQUESTED_IBAN, FAILED_ENVELOPE);
            // The partner ends up with the OTHER account, never the one the first call asked for.
            fake.addBankAccount(OTHER_IBAN);
            runner.runWithIban(OTHER_IBAN, SUCCESS_ENVELOPE);

            AiWorkflow workflow = new AiWorkflow();
            workflow.setWriteJob(true);
            workflow.setTask("REPAIR_SUPPLIER_INVOICE");
            AiWorkflowController controller = controllerWith(fake.repository);
            controller.update(workflow, null);

            assertFalse(workflow.getCompleted().contains(typedTarget(NORMALISED_IBAN)));
            assertEquals(1, workflow.blocked().size());
            assertEquals(typedTarget(NORMALISED_IBAN), workflow.blocked().get(0).getRef());
          });
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }
  }

  /**
   * The other required guarantee: an ordinary successful update — no bank account at all — falls
   * back to the plain {@code Partner#id} exactly as {@link AiTool#completionTarget}'s default does,
   * and {@code AiWorkflowController.isAffectedRecordOnly} keeps that ref out of Remaining.
   */
  @Test
  void aSuccessfulPlainUpdateNeverEntersRemaining() {
    Runner runner = new Runner();
    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      inCompanyScope(
          () -> {
            runner.run(null, SUCCESS_ENVELOPE);

            List<AiTurnToolLog.Entry> entries = AiTurnToolLog.entries();
            assertEquals(1, entries.size());
            assertEquals(plainTarget(), entries.get(0).getTarget());

            AiWorkflow workflow = new AiWorkflow();
            workflow.setWriteJob(true);
            workflow.setTask("REPAIR_SUPPLIER_INVOICE");
            AiWorkflowController controller = controllerWith(fakeRepository());
            controller.update(workflow, null);

            assertNull(workflow.find(plainTarget()));
            assertFalse(workflow.remaining().contains(plainTarget()));
          });
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }
  }
}
