package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiEvidenceNotReadyException;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.context.AiTargetRelationship;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AiWriteAuthorisation#checkTargets}/{@link AiWriteAuthorisation#checkCapability}: a target
 * that is not itself authorised may still be reached when a {@link AiTargetRelationship} proves it
 * is tied to one that is — a supplier update or creation from inside an authorised invoice/upload
 * job — and that related match, unlike a direct one, is exempt from the capability/job domain
 * comparison (a {@code PARTNER}-capability call is never the same domain as a {@code
 * SUPPLIER_INVOICE} job by construction). The concrete resolvers ({@code
 * InvoicePartnerRelationship}, {@code UploadSupplierIdentityRelationship}) live in feature modules
 * this one does not depend on, so a fake relationship stands in for them here.
 */
class AiWriteAuthorisationTest {

  private static AiWorkflow jobOn(String target) {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.addTarget(target);
    workflow.setAuthorisedTargets(Collections.singletonList(target));
    return workflow;
  }

  private static AiTargetRegistry registryWith(AiTargetRelationship relationship) {
    return new AiTargetRegistry() {
      @Override
      protected List<AiTargetIdentity> discover() {
        return Collections.singletonList(ref -> ref);
      }

      @Override
      protected List<AiTargetRelationship> discoverRelationships() {
        return Collections.singletonList(relationship);
      }
    };
  }

  private static AiTool toolWithCapability(AiCapability capability) {
    return new AiTool() {
      @Override
      public String name() {
        return "fake_tool";
      }

      @Override
      public String description() {
        return "fake";
      }

      @Override
      public JsonNode parametersSchema() {
        return null;
      }

      @Override
      public AiToolKind kind() {
        return AiToolKind.WRITE;
      }

      @Override
      public AiCapability capability() {
        return capability;
      }

      @Override
      public Object execute(JsonNode args) throws AiToolException {
        return null;
      }
    };
  }

  @Test
  void aSupplierUpdateIsPermittedWhenItIsTheAuthorisedInvoicesPartner() {
    AiWorkflow workflow = jobOn("Invoice#606");
    AiTargetRegistry registry =
        registryWith(
            (a, b) -> "Invoice#606".equals(a) && "Partner#12".equals(b) ? Boolean.TRUE : null);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "Partner#12", registry);

    assertNull(targets.refusal());
    assertTrue(targets.related());
    assertNull(
        AiWriteAuthorisation.checkCapability(
            toolWithCapability(AiCapability.PARTNER), workflow, targets.related()));
  }

  @Test
  void aSupplierUpdateIsRefusedWhenUnrelatedToTheAuthorisedInvoice() {
    AiWorkflow workflow = jobOn("Invoice#606");
    AiTargetRegistry registry = registryWith((a, b) -> null);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "Partner#99", registry);

    assertNotNull(targets.refusal());
  }

  @Test
  void aSupplierCreateIsPermittedWhenItsEikMatchesTheAuthorisedUpload() {
    AiWorkflow workflow = jobOn("AiOcrUpload#946");
    AiTargetRegistry registry =
        registryWith(
            (a, b) ->
                "AiOcrUpload#946".equals(a) && "PartnerCreate:1:203970390".equals(b)
                    ? Boolean.TRUE
                    : null);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "PartnerCreate:1:203970390", registry);

    assertNull(targets.refusal());
    assertTrue(targets.related());
  }

  @Test
  void aSupplierCreateIsRefusedWhenItsEikDoesNotMatchTheAuthorisedUpload() {
    AiWorkflow workflow = jobOn("AiOcrUpload#946");
    AiTargetRegistry registry = registryWith((a, b) -> Boolean.FALSE);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "PartnerCreate:1:999999999", registry);

    assertNotNull(targets.refusal());
  }

  /**
   * A create target the user's own message froze directly — {@code SupplierMessageTargetResolver},
   * no OCR upload involved — matches only the exact same reference. {@code sameTarget}
   * short-circuits on literal string equality, so this needs no identity resolver at all: the two
   * synthetic refs are simply different strings naming different ЕИК, and a model that tries to
   * reuse the frozen target for a different company's code must not be let through by anything
   * looser than that.
   */
  @Test
  void aFrozenCreateTargetIsPermittedForTheExactSameEik() {
    AiWorkflow workflow = jobOn("PartnerCreate:5:831642181");
    AiTargetRegistry registry = registryWith((a, b) -> null);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "PartnerCreate:5:831642181", registry);

    assertNull(targets.refusal());
    assertTrue(!targets.related());
  }

  @Test
  void aFrozenCreateTargetRefusesADifferentEikTheModelSupplied() {
    AiWorkflow workflow = jobOn("PartnerCreate:5:831642181");
    AiTargetRegistry registry = registryWith((a, b) -> null);

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "PartnerCreate:5:999999999", registry);

    assertNotNull(targets.refusal());
    assertEquals(AiBlockerCode.WRITE_NOT_AUTHORISED, targets.refusal().code());
  }

  /**
   * Genuinely unavailable OCR evidence — the upload has no stored extraction yet — must not be
   * indistinguishable from a contradicting ЕИК. {@code checkTargets} classifies it under {@link
   * com.axelor.apps.aiaccountant.service.AiBlockerCode#OCR_EVIDENCE_NOT_READY}, a retryable
   * technical failure, rather than the ordinary {@code WRITE_NOT_AUTHORISED} refusal.
   */
  @Test
  void unavailableEvidenceIsRetryableRatherThanAPermanentRefusal() {
    AiWorkflow workflow = jobOn("AiOcrUpload#946");
    AiTargetRegistry registry =
        registryWith(
            (a, b) -> {
              throw new AiEvidenceNotReadyException(
                  "The OCR evidence for AiOcrUpload#946 has not finished processing yet.");
            });

    AiWriteAuthorisation.TargetCheck targets =
        AiWriteAuthorisation.checkTargets(workflow, "PartnerCreate:1:203970390", registry);

    assertNotNull(targets.refusal());
    assertEquals(AiBlockerCode.OCR_EVIDENCE_NOT_READY, targets.refusal().code());
    assertEquals(AiBlockerClass.TECHNICAL_FAILURE, targets.refusal().code().classification());
  }
}
