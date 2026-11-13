package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRefDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Creating a record no longer needs one to be open.
 *
 * <p>A creation has no record to name, so the three document create tools answered {@code null} to
 * {@code targetKey} — and {@code AiWriteAuthorisation.checkTargets} refuses a null target once a
 * job has a closed set and refuses outright when it has none. Both branches refused, so an explicit
 * «създай …» could not be carried out at all. The job now freezes a creation destination of its
 * own, from the route's task, and the tools name the same one.
 */
class AiWorkflowCreationTargetTest {

  private static AiWorkflowController controller() {
    return new AiWorkflowController() {
      @Override
      protected AiTargetRegistry targetRegistry() {
        return new AiTargetRegistry() {
          @Override
          protected List<AiTargetIdentity> discover() {
            return Collections.emptyList();
          }

          @Override
          public String canonical(String ref) {
            // Stands in for AiCreationTargetIdentity, which lives in the accounting module.
            return AiCreationTarget.parse(ref) == null ? null : ref;
          }

          @Override
          public boolean writable(String ref) {
            return AiCreationTarget.parse(ref) != null;
          }
        };
      }

      @Override
      protected int maxAuthorisedTargets() {
        return 25;
      }
    };
  }

  private static AiRoute create(AiTask task, AiScope scope, AiExplicitness explicitness) {
    return new AiRoute(task.domain(), task, null, scope, explicitness);
  }

  private static AiWorkflow open(AiRoute route, String message, PageContext page) {
    return controller().open(message, route, page, null, 7L, "conv-1", Collections.emptyList());
  }

  @Test
  void aSupplierInvoiceCanBeCreatedWithNothingOpenOnScreen() {
    AiWorkflow job =
        open(
            create(AiTask.CREATE_SUPPLIER_INVOICE, AiScope.UNSCOPED, AiExplicitness.EXPLICIT),
            "създай доставна фактура от Лидл за 240 лв",
            null);

    assertTrue(job.hasClosedTargets());
    assertEquals(
        Collections.singletonList("Create:SUPPLIER_INVOICE:7"), job.getAuthorisedTargets());
  }

  @Test
  void aCustomerInvoiceAndAMoveGetTheirOwnDestinations() {
    assertEquals(
        Collections.singletonList("Create:CUSTOMER_INVOICE:7"),
        open(
                create(AiTask.CREATE_CUSTOMER_INVOICE, AiScope.UNSCOPED, AiExplicitness.EXPLICIT),
                "издай фактура на клиента",
                null)
            .getAuthorisedTargets());
    assertEquals(
        Collections.singletonList("Create:JOURNAL_ENTRY:7"),
        open(
                create(AiTask.CREATE_JOURNAL_ENTRY, AiScope.UNSCOPED, AiExplicitness.EXPLICIT),
                "създай статия",
                null)
            .getAuthorisedTargets());
  }

  /**
   * The creation destination is a FALLBACK, not an override: «създай фактурата» with an OCR upload
   * open means "make it from this document", and the upload is the record the instruction is about.
   * Freezing a bare {@code Create:} reference there would lose it.
   */
  @Test
  void anOpenRecordStillWinsOverTheCreationDestination() {
    PageContext page = new PageContext();
    page.setModel("com.axelor.apps.aiocr.db.AiOcrUpload");
    page.setRecordId(18L);

    AiWorkflow job =
        open(
            create(AiTask.CREATE_SUPPLIER_INVOICE, AiScope.OPEN_RECORD, AiExplicitness.EXPLICIT),
            "създай доставната фактура",
            page);

    assertEquals(Collections.singletonList("AiOcrUpload#18"), job.getAuthorisedTargets());
  }

  /** A bare «да» must not mint an authorisation to create something of its own. */
  @Test
  void anInheritedTurnMintsNoCreationDestination() {
    AiWorkflow job =
        open(
            create(AiTask.CREATE_SUPPLIER_INVOICE, AiScope.UNSCOPED, AiExplicitness.IMPLIED),
            "може би трябва фактура",
            null);

    assertTrue(job == null || !job.hasClosedTargets());
  }

  @Test
  void aCreationReferenceCarriesItsOwnDomainForTheCapabilityCheck() {
    assertEquals(AiDomain.SUPPLIER_INVOICE, AiRefDomain.of("Create:SUPPLIER_INVOICE:7"));
    assertEquals(AiDomain.JOURNAL_ENTRY, AiRefDomain.of("Create:JOURNAL_ENTRY:7"));
    assertNull(AiRefDomain.of("Create:NOT_A_DOMAIN:7"));
    assertNotNull(AiCreationTarget.parse("Create:CUSTOMER_INVOICE:2"));
    assertNull(AiCreationTarget.parse("Invoice#641"));
    assertNull(AiCreationTarget.creates(AiTask.POST_SUPPLIER_INVOICE));
  }
}
