package com.axelor.apps.aiaccountant.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiaccountant.route.AiSetSelection;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The structured reading of a list instruction, as it arrives from the router.
 *
 * <p>The rule the whole selection mechanism rests on: an unrecognised or incomplete selection is a
 * hard failure, never a silent default. Defaulting it to {@code WHOLE} would authorise every
 * eligible record on a turn where the router meant something narrower.
 */
class AiRouterVerdictSelectionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static AiRouterVerdict parse(String selectionFields) {
    return AiRouterVerdict.parse(
        "{\"status\":\"route\",\"domain\":\"SUPPLIER_INVOICE\","
            + "\"task\":\"POST_SUPPLIER_INVOICE\",\"goal\":\"POSTED\",\"scope\":\"WORKLIST\","
            + "\"explicitness\":\"EXPLICIT\""
            + selectionFields
            + "}",
        MAPPER);
  }

  @Test
  void aWholeSelectionIsCarriedOntoTheRoute() {
    AiRouterVerdict verdict = parse(",\"setSelection\":\"WHOLE\"");
    assertEquals(AiSetSelection.WHOLE, verdict.route().selection());
    assertNull(verdict.route().count());
  }

  @Test
  void aCountSelectionCarriesItsNumber() {
    AiRouterVerdict verdict = parse(",\"setSelection\":\"COUNT\",\"setCount\":2");
    assertEquals(AiSetSelection.COUNT, verdict.route().selection());
    assertEquals(Integer.valueOf(2), verdict.route().count());
  }

  @Test
  void aFilteredSelectionCarriesItsFilterName() {
    AiRouterVerdict verdict =
        parse(",\"setSelection\":\"FILTERED\",\"setFilter\":\"NEEDS_REVIEW\"");
    assertEquals(AiSetSelection.FILTERED, verdict.route().selection());
    assertEquals("NEEDS_REVIEW", verdict.route().filter());
  }

  @Test
  void anAbsentSelectionStaysAbsentRatherThanBecomingTheWholeQueue() {
    assertNull(parse("").route().selection());
    assertNull(parse(",\"setSelection\":null").route().selection());
  }

  @Test
  void anUnrecognisedOrIncompleteSelectionFailsTheWholeAnswer() {
    assertEquals(AiRouterVerdict.Status.FAILED, parse(",\"setSelection\":\"MOST\"").status());
    assertEquals(AiRouterVerdict.Status.FAILED, parse(",\"setSelection\":\"COUNT\"").status());
    assertEquals(
        AiRouterVerdict.Status.FAILED,
        parse(",\"setSelection\":\"COUNT\",\"setCount\":0").status());
    assertEquals(
        AiRouterVerdict.Status.FAILED,
        parse(",\"setSelection\":\"COUNT\",\"setCount\":\"two\"").status());
    assertEquals(AiRouterVerdict.Status.FAILED, parse(",\"setSelection\":\"FILTERED\"").status());
  }

  /**
   * The maintenance operation, which is the only thing telling a supplier create from an update:
   * {@code MAINTAIN_SUPPLIER} is one task for both. Unstated stays unstated — never CREATE.
   */
  @Test
  void theMaintenanceOperationIsCarriedOntoTheRouteAndNeverDefaulted() {
    assertEquals(AiWriteOperation.CREATE, parse(",\"operation\":\"CREATE\"").route().operation());
    assertEquals(AiWriteOperation.UPDATE, parse(",\"operation\":\"update\"").route().operation());
    assertNull(parse("").route().operation());
    assertNull(parse(",\"operation\":null").route().operation());
    assertEquals(AiRouterVerdict.Status.FAILED, parse(",\"operation\":\"REPLACE\"").status());
  }

  /** The selection survives the two places a verified continuation is re-scoped and re-capped. */
  @Test
  void clampingAContinuationKeepsTheSelection() {
    AiRouterVerdict verdict =
        AiRouterVerdict.parse(
            "{\"status\":\"continue\",\"domain\":\"SUPPLIER_INVOICE\","
                + "\"task\":\"POST_SUPPLIER_INVOICE\",\"goal\":\"POSTED\","
                + "\"scope\":\"WORKLIST\",\"explicitness\":\"EXPLICIT\","
                + "\"setSelection\":\"REMAINING\"}",
            MAPPER);

    AiRouteSelector.clampContinuation(
            verdict.route(), com.axelor.apps.aiaccountant.route.AiScope.CONVERSATION)
        .selection();
    assertEquals(
        AiSetSelection.REMAINING,
        AiRouteSelector.clampContinuation(
                verdict.route(), com.axelor.apps.aiaccountant.route.AiScope.CONVERSATION)
            .selection());
  }
}
