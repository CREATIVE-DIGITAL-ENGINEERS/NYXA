package com.axelor.apps.aiaccountant.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.service.AiTurnMemoryStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AiRouterContract#history} no longer trims a turn's stored text to 300/600 characters on
 * top of {@link AiRouterContract#firstKept}'s own whole-request token budget — see the method
 * javadoc. With no injector here, the budget falls back to {@code AiAccountantConfig}'s own
 * defaults.
 */
class AiRouterContractTest {

  @Test
  void historyDoesNotTrimALongTurn() {
    String longMessage = repeat("a", 1_000);
    String longReply = repeat("b", 2_000);
    List<AiTurnMemoryStore.Reading> history =
        Collections.singletonList(new AiTurnMemoryStore.Reading(longMessage, longReply, null));

    ArrayNode array = AiRouterContract.history(history, 0);

    assertEquals(longMessage, array.get(0).get("user").asText());
    assertEquals(longReply, array.get(0).get("assistant").asText());
  }

  @Test
  void firstKeptDropsOnlyWholeOldestTurnsOnceTheBudgetIsExceeded() {
    String chunk = repeat("x", 10_000);
    List<AiTurnMemoryStore.Reading> history = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      // Each turn is ~7,700 estimated tokens; three of them exceed a 10,000 allowance.
      history.add(new AiTurnMemoryStore.Reading(chunk, chunk, null));
    }

    int firstKept = AiRouterContract.firstKept(history, 10_000);

    assertEquals(2, firstKept, "only the newest whole turn fits");
    assertEquals(
        2, AiRouterContract.firstKept(history, -1), "the newest turn remains even with no room");
  }

  /**
   * The history competes with the rest of the same request: a message that uses most of the
   * envelope leaves out old Bulgarian exchanges that would fit beside a short one.
   */
  @Test
  void theRestOfTheRequestIsChargedBeforeTheHistory() {
    String turn = repeat("фактура ", 3_000); // 24,000 chars, mostly Cyrillic: ~13,200 est. tokens
    List<AiTurnMemoryStore.Reading> history = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      history.add(new AiTurnMemoryStore.Reading(turn, "ок", null));
    }
    String shortRequest = AiRouterContract.request("да", null, null, 1L, null, history);
    String longRequest =
        AiRouterContract.request(repeat("x", 180_000), null, null, 1L, null, history);

    assertTrue(shortRequest.contains("\"historyOmittedTurns\":0"), "all four fit beside «да»");
    assertTrue(longRequest.contains("\"historyTruncated\":true"), "a long message leaves less");
    assertTrue(longRequest.contains("\"historyTotalTurns\":4"));
  }

  @Test
  void continueConditionOneAllowsAnOrderedMultiStepPlan() {
    String instructions = AiRouterContract.instructions();

    assertTrue(
        instructions.contains("ORDERED SEQUENCE"),
        "an ordered multi-step plan toward one outcome must still qualify as one concrete"
            + " change");
    assertTrue(
        instructions.contains("alternative choices"),
        "only alternative/independent options should disqualify a CONTINUE, not step count");
  }

  @Test
  void firstKeptKeepsEverythingUnderTheBudget() {
    List<AiTurnMemoryStore.Reading> history =
        Collections.singletonList(new AiTurnMemoryStore.Reading("hi", "hello", null));

    assertEquals(0, AiRouterContract.firstKept(history, 1_000), "nothing to omit when it all fits");
  }

  private static String repeat(String unit, int times) {
    StringBuilder sb = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      sb.append(unit);
    }
    return sb.toString();
  }
}
