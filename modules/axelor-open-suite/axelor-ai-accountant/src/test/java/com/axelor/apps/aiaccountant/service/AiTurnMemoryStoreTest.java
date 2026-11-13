package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code AiTurnMemoryStore} no longer caps a message or a reply to a fixed length before storing it
 * — see {@link AiTurnMemoryStore#persist}, which used to call a {@code cap(value, 300/600)} helper
 * before this. {@code persist}/{@code loadHistory} themselves need a live persistence context this
 * module's test suite has none of, so this exercises the same serialise/parse round-trip {@code
 * persist} feeds, through the package-private accessors it uses.
 */
class AiTurnMemoryStoreTest {

  @Test
  void aLongMessageAndReplyRoundTripUncapped() {
    String longMessage = repeat("a", 1_000);
    String longReply = repeat("b", 2_000);
    List<AiTurnMemoryStore.Reading> history =
        Collections.singletonList(
            new AiTurnMemoryStore.Reading(longMessage, longReply, "Invoice#606"));

    String json = AiTurnMemoryStore.serialize(history);
    List<AiTurnMemoryStore.Reading> parsed = AiTurnMemoryStore.parse(json);

    assertEquals(1, parsed.size());
    assertEquals(longMessage, parsed.get(0).userMessage());
    assertEquals(longReply, parsed.get(0).assistantReply());
  }

  private static String repeat(String unit, int times) {
    StringBuilder sb = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      sb.append(unit);
    }
    return sb.toString();
  }
}
