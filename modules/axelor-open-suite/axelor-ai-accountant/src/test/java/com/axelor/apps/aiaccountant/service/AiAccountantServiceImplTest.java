package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.client.FoundryResponsesClient;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.metrics.AiTurnMetrics;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.tool.AiToolDispatcher;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tool loop, driven by a scripted client. No network, no Guice, no AppSettings — the same
 * seam-and-stub style as {@code AzureDocumentIntelligenceExtractorTest}.
 */
class AiAccountantServiceImplTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A conversation id in the shape the server accepts, for the state-blob assertions below. */
  private static final String CONVERSATION = "c-test-conversation";

  /** Replays canned response bodies and records what was sent. */
  private static class ScriptedClient implements FoundryResponsesClient {
    final Deque<String> script = new ArrayDeque<>();
    final List<String> sentPrevIds = new ArrayList<>();
    final List<JsonNode> sentInputs = new ArrayList<>();

    @Override
    public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
      sentPrevIds.add(previousResponseId);
      sentInputs.add(input);
      try {
        return new FoundryResponse(MAPPER.readTree(script.removeFirst()));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * A scripted client that also enforces the service's real constraint: chaining onto a response
   * whose {@code function_call} items were never answered is rejected exactly as Foundry rejects
   * it.
   */
  private static class FoundryLikeClient extends ScriptedClient {
    /** response id -> call_ids it is still waiting on. */
    final Map<String, Set<String>> unanswered = new LinkedHashMap<>();

    @Override
    public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
      Set<String> owed = unanswered.get(previousResponseId);
      if (owed != null && !owed.isEmpty()) {
        Set<String> answered = new LinkedHashSet<>();
        if (input != null && input.isArray()) {
          for (JsonNode item : input) {
            if ("function_call_output".equals(item.path("type").asText())) {
              answered.add(item.path("call_id").asText());
            }
          }
        }
        for (String callId : owed) {
          if (!answered.contains(callId)) {
            throw new IllegalStateException(
                "Foundry returned HTTP 400: No tool output found for function call "
                    + callId
                    + ".");
          }
        }
      }
      FoundryResponse response = super.createResponse(previousResponseId, input);
      Set<String> pending = new LinkedHashSet<>();
      for (FoundryResponse.FunctionCall c : response.functionCalls()) {
        pending.add(c.getCallId());
      }
      unanswered.put(response.getId(), pending);
      return response;
    }
  }

  /** Returns a fixed tool result without touching the registry or the security context. */
  private static final class StubDispatcher extends AiToolDispatcher {
    final List<String> calls = new ArrayList<>();

    StubDispatcher() {
      // The two-argument constructor: it supplies a real repeat guard and target-state registry,
      // which is what a stub wants — dispatch() is overridden below, so neither is exercised.
      super(null, null);
    }

    @Override
    public String dispatch(String name, String argumentsJson) {
      calls.add(name);
      return "{\"value\":\"42-QUARTZ\"}";
    }
  }

  private static AiAccountantConfig config(int maxIterations) {
    return new AiAccountantConfig() {
      @Override
      public boolean isEnabled() {
        return true;
      }

      @Override
      public boolean isChatConfigured() {
        return true;
      }

      @Override
      public int maxToolIterations() {
        return maxIterations;
      }

      @Override
      public int maxMessageChars() {
        return 8000;
      }
    };
  }

  private static String messageBody(String text) {
    return "{\"id\":\"resp_final\",\"output\":[{\"type\":\"message\",\"content\":"
        + "[{\"type\":\"output_text\",\"text\":\""
        + text
        + "\"}]}]}";
  }

  private static String toolCallBody(String id, String callId, String tool) {
    return "{\"id\":\""
        + id
        + "\",\"output\":[{\"type\":\"function_call\",\"call_id\":\""
        + callId
        + "\",\"name\":\""
        + tool
        + "\",\"arguments\":\"{}\"}]}";
  }

  private static ChatRequest request(String message) {
    ChatRequest r = new ChatRequest();
    r.setMessage(message);
    // What the panel sends once its transcript has loaded. Required now: a request that cannot say
    // which company's conversation the user was reading is refused before anything runs, and these
    // tests are about what happens after that point. There is no session here so the server
    // resolves
    // no company, which the default null expectation matches exactly.
    r.setCompanyContextLoaded(true);
    return r;
  }

  /**
   * As {@link #request}, but carrying a response id, a conversation id and a stamped state — what a
   * real client sends on a continuing turn, and what {@code chainDecision} needs to keep the id
   * rather than resetting the chain.
   */
  private static ChatRequest continuingRequest(String message, String responseId) {
    ChatRequest r = request(message);
    r.setResponseId(responseId);
    r.setConversationId(CONVERSATION);
    AiConversationState state = new AiConversationState();
    state.setConversationId(CONVERSATION);
    // toJson() returns null for an otherwise-empty state (nothing worth the client storing), which
    // would make it indistinguishable from no state at all. One token of history is enough to force
    // serialisation without meaningfully affecting the chain-budget check below.
    state.setLastInputTokens(1);
    r.setState(state.toJson());
    return r;
  }

  /**
   * A {@link AiWorkflowController} whose {@code pendingProposalStore()} never has anything pending,
   * for a suite that is deliberately no Guice and no database (see the class javadoc): {@link
   * AiWorkflowController#promoteIfConfirmed} no longer guards a broken injector itself — see its
   * own javadoc — so every service built here supplies a working substitute instead.
   */
  private static AiWorkflowController noProposalWorkflowController() {
    return new AiWorkflowController() {
      @Override
      protected AiPendingProposalStore pendingProposalStore() {
        return new NoOpPendingProposalStore();
      }
    };
  }

  /** As {@code service(...)}, wired with {@link #noProposalWorkflowController}. */
  private static AiAccountantServiceImpl service(
      FoundryResponsesClient client, AiToolDispatcher dispatcher, AiAccountantConfig config) {
    return new AiAccountantServiceImpl(client, dispatcher, config) {
      @Override
      protected AiWorkflowController workflowController() {
        return noProposalWorkflowController();
      }
    };
  }

  /**
   * A service whose chain measurement always agrees with a fresh {@link #continuingRequest}: no
   * Guice and no database means {@code chainBudget()} would otherwise fall back to a store that
   * finds no row and resets every chain, which is the one thing these tests are not about.
   */
  private static AiAccountantServiceImpl chainedService(
      FoundryResponsesClient client, AiToolDispatcher dispatcher, AiAccountantConfig config) {
    return new AiAccountantServiceImpl(client, dispatcher, config) {
      @Override
      protected AiChainBudgetStore chainBudget() {
        return new AiChainBudgetStore() {
          @Override
          public Reading load(Long userId, Long companyId, String conversationId) {
            return new Reading(0, 0, 1, "chain-test");
          }
        };
      }

      @Override
      protected AiWorkflowController workflowController() {
        return noProposalWorkflowController();
      }
    };
  }

  @Test
  void plainAnswerWithoutTools() {
    ScriptedClient client = new ScriptedClient();
    client.script.add(messageBody("PONG"));
    StubDispatcher dispatcher = new StubDispatcher();

    ChatReply reply = service(client, dispatcher, config(5)).chat(request("ping"));

    assertEquals("PONG", reply.getReply());
    assertEquals("resp_final", reply.getResponseId());
    assertTrue(reply.getToolsUsed().isEmpty());
    assertFalse(reply.isTruncated());
    assertEquals(1, client.sentPrevIds.size());
  }

  @Test
  void executesToolThenReturnsFinalAnswer() {
    ScriptedClient client = new ScriptedClient();
    client.script.add(toolCallBody("resp_1", "call_abc", "get_demo_value"));
    client.script.add(messageBody("The value is 42-QUARTZ"));
    StubDispatcher dispatcher = new StubDispatcher();

    ChatReply reply =
        service(client, dispatcher, config(5)).chat(request("what is the demo value?"));

    assertEquals("The value is 42-QUARTZ", reply.getReply());
    assertEquals(1, dispatcher.calls.size());
    assertEquals("get_demo_value", dispatcher.calls.get(0));
    assertEquals(java.util.Arrays.asList("get_demo_value"), reply.getToolsUsed());
    assertFalse(reply.isTruncated());

    // Second call must chain on the first response and send a function_call_output item.
    assertEquals("resp_1", client.sentPrevIds.get(1));
    JsonNode second = client.sentInputs.get(1);
    assertTrue(second.isArray());
    assertEquals("function_call_output", second.get(0).path("type").asText());
    assertEquals("call_abc", second.get(0).path("call_id").asText());
    // 'output' must be a JSON string, not an object
    assertTrue(second.get(0).path("output").isTextual());
  }

  @Test
  void iterationCapStopsTheLoop() {
    ScriptedClient client = new ScriptedClient();
    for (int i = 0; i < 10; i++) {
      client.script.add(toolCallBody("resp_" + i, "call_" + i, "get_demo_value"));
    }
    StubDispatcher dispatcher = new StubDispatcher();

    ChatReply reply = service(client, dispatcher, config(3)).chat(request("loop forever"));

    assertTrue(reply.isTruncated());
    // 3 iterations execute tools; the 4th request settles them and asks for an answer, it does not
    // run a 4th tool. Raising the budget is not what prevents a dangling call.
    assertEquals(3, dispatcher.calls.size());
    assertEquals(4, client.sentPrevIds.size());
  }

  @Test
  void capSettlesEveryPendingCallBeforeGivingUp() {
    ScriptedClient client = new ScriptedClient();
    client.script.add(toolCallBody("resp_0", "call_0", "get_demo_value"));
    client.script.add(toolCallBody("resp_1", "call_1", "get_demo_value"));
    client.script.add(messageBody("Here is what I found."));
    StubDispatcher dispatcher = new StubDispatcher();

    ChatReply reply = service(client, dispatcher, config(2)).chat(request("dig"));

    assertTrue(reply.isTruncated());
    // Not a bare equals: "dig" names no provable outcome, so AiTurnTruthCheck correctly leads with
    // its own truncation notice rather than trusting the model's summary outright — the model's
    // closing sentence still survives underneath it, which is what this test is actually about.
    assertTrue(
        reply.getReply().endsWith("Here is what I found."), "the settling turn still answers");
    assertEquals("resp_final", reply.getResponseId());

    // The settling request answers call_1 and, in the same array, tells the model to stop.
    JsonNode last = client.sentInputs.get(2);
    assertEquals("resp_1", client.sentPrevIds.get(2));
    assertEquals("function_call_output", last.get(0).path("type").asText());
    assertEquals("call_1", last.get(0).path("call_id").asText());
    assertEquals("message", last.get(1).path("type").asText());
    assertEquals("user", last.get(1).path("role").asText());
    assertTrue(last.get(1).path("content").get(0).path("text").asText().contains("exhausted"));
  }

  /**
   * The live failure: a turn ended on a response whose function calls were never answered, its id
   * went back to the browser, and every later message chained onto it — so Foundry rejected them
   * all with the same call_id, permanently.
   */
  @Test
  void neverReturnsAResponseIdWithUnansweredFunctionCalls() {
    FoundryLikeClient client = new FoundryLikeClient();
    // Three tool rounds exhaust a budget of 3; the settling request then answers in words.
    for (int i = 0; i < 3; i++) {
      client.script.add(toolCallBody("resp_" + i, "call_" + i, "get_demo_value"));
    }
    client.script.add(messageBody("Partial answer."));
    StubDispatcher dispatcher = new StubDispatcher();
    AiAccountantServiceImpl svc = service(client, dispatcher, config(3));

    ChatReply first = svc.chat(request("why is this needs review?"));
    assertTrue(first.isTruncated());
    assertEquals("resp_final", first.getResponseId());

    // The id handed back must be usable. Before the fix this was "resp_2", whose call_2 was never
    // answered, and every later message died on:
    // "Foundry returned HTTP 400: No tool output found for function call call_2."
    client.script.add(messageBody("A fresh unrelated answer."));
    ChatRequest next = request("something completely different");
    next.setResponseId(first.getResponseId());

    ChatReply second = svc.chat(next);
    assertNull(second.getError(), "a stale continuation id must not poison the conversation");
    assertEquals("A fresh unrelated answer.", second.getReply());
  }

  /** Proof the guard is real: chaining on an unsettled response produces the service's 400. */
  @Test
  void chainingOnAnUnsettledResponseIsWhatProducedTheLive400() {
    FoundryLikeClient client = new FoundryLikeClient();
    client.script.add(toolCallBody("resp_2", "call_2", "get_demo_value"));

    ChatRequest first = request("why is this needs review?");
    service(client, new StubDispatcher(), config(1)).chat(first);

    // What a browser could store and send back: an id still owing call_2.
    ChatRequest next = request("anything at all");
    next.setResponseId("resp_2");
    IllegalStateException boom =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> client.createResponse("resp_2", MAPPER.getNodeFactory().textNode("anything")));
    assertTrue(boom.getMessage().contains("No tool output found for function call call_2"));
  }

  @Test
  void survivesTheSettlingRequestFailing() {
    // If even the settling call fails, the turn must still hand back a usable id — the one the
    // client already had — rather than the dangling one.
    FoundryLikeClient client =
        new FoundryLikeClient() {
          @Override
          public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
            if (sentPrevIds.size() == 2) {
              throw new IllegalStateException("network blew up while settling");
            }
            return super.createResponse(previousResponseId, input);
          }
        };
    client.script.add(toolCallBody("resp_0", "call_0", "get_demo_value"));
    client.script.add(toolCallBody("resp_1", "call_1", "get_demo_value"));

    ChatRequest req = continuingRequest("dig", "resp_clean_from_earlier");
    ChatReply reply = chainedService(client, new StubDispatcher(), config(2)).chat(req);

    assertTrue(reply.isTruncated());
    assertEquals("resp_clean_from_earlier", reply.getResponseId());
    assertNotNull(reply.getReply());
  }

  @Test
  void operatingProcedureIsSentOnceNotOnEveryTurn() {
    ScriptedClient client = new ScriptedClient();
    client.script.add(messageBody("first"));
    client.script.add(messageBody("second"));
    AiAccountantServiceImpl svc = chainedService(client, new StubDispatcher(), config(5));

    svc.chat(continuingRequest("hello", null));
    svc.chat(continuingRequest("and now this", "resp_final"));

    assertTrue(client.sentInputs.get(0).asText().contains("Nyxa AI Accountant"));
    assertFalse(
        client.sentInputs.get(1).asText().contains("Nyxa AI Accountant"),
        "Azure keeps the history; resending the procedure would just burn tokens");
  }

  @Test
  void rejectsBlankAndOversizedMessages() {
    AiAccountantServiceImpl svc = service(new ScriptedClient(), new StubDispatcher(), config(5));
    assertNotNull(svc.chat(request("   ")).getError());
    assertNotNull(svc.chat(new ChatRequest()).getError());

    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 9000; i++) {
      big.append('x');
    }
    assertNotNull(svc.chat(request(big.toString())).getError());
  }

  @Test
  void pageContextNamesTheOpenRecordAndAlwaysEndsWithTheMessage() {
    AiAccountantServiceImpl svc = service(new ScriptedClient(), new StubDispatcher(), config(5));

    // The date is unconditional — it is what "днес"/"this month" resolve against — so even with no
    // page context at all there is a context block. What matters is that the message comes last.
    assertTrue(svc.withContext("hi", null, false).endsWith("hi"));

    PageContext partial = new PageContext();
    partial.setModel("com.axelor.apps.account.db.Invoice");
    String noId = svc.withContext("hi", partial, false);
    assertFalse(noId.contains("The user is viewing"), "no record id -> no open-record line");

    PageContext full = new PageContext();
    full.setModel("com.axelor.apps.account.db.Invoice");
    full.setRecordId(283L);
    String out = svc.withContext("Why is this To Review?", full, false);
    assertTrue(out.contains("com.axelor.apps.account.db.Invoice"));
    assertTrue(out.contains("283"));
    assertTrue(out.endsWith("Why is this To Review?"));
  }

  /**
   * An authorisation is scoped to the records the workflow touched, and it is not recorded at all
   * unless the turn really was left unfinished.
   */
  @Test
  void unfinishedAuthorisationIsScopedAndClearedWhenTheTurnCompletes() {
    AiAccountantServiceImpl svc = service(new ScriptedClient(), new StubDispatcher(), config(5));
    AiWriteEffects.begin();
    AiTurnToolLog.begin();
    AiTurnMetrics.begin(false);
    try {
      AiWriteEffects.touched("com.axelor.apps.account.db.Invoice", 606L);

      AiConversationState done =
          AiConversationState.parse(
              svc.outgoingState("оправи и осчетоводи 606", null, false, null, CONVERSATION));
      assertNull(done, "a completed turn that measured nothing carries no state at all");

      AiConversationState cut =
          AiConversationState.parse(
              svc.outgoingState("оправи и осчетоводи 606", null, true, null, CONVERSATION));
      assertNotNull(cut);
      assertTrue(cut.isPending());
      assertEquals("оправи и осчетоводи 606", cut.getRequest());
      assertEquals(java.util.Collections.singletonList("Invoice#606"), cut.getTouched());

      // «Продължи» keeps the ORIGINAL instruction and adds only records the workflow itself wrote.
      // Both are read from the job the SERVER holds, which is why one is passed here: never from
      // the returned blob, because a scope a caller can supply is a scope a caller can widen.
      AiWriteEffects.touched("com.axelor.apps.account.db.Invoice", 607L);
      AiWorkflow job = new AiWorkflow();
      job.setRequest("оправи и осчетоводи 606");
      // A destination and at least one target: that is what makes a job active, and only an active
      // job carries an authorisation forward.
      job.setGoal("POSTED");
      job.addTarget("Invoice#606");
      AiConversationState resumed =
          AiConversationState.parse(svc.outgoingState("продължи", cut, true, job, CONVERSATION));
      assertNotNull(resumed);
      assertEquals(
          "оправи и осчетоводи 606", resumed.getRequest(), "«продължи» authorises nothing");
      assertTrue(resumed.getTouched().contains("Invoice#606"));
      assertTrue(resumed.getTouched().contains("Invoice#607"));

      // The same word with no stored job inherits nothing: there is no record of an authorisation,
      // so the turn stands on its own words.
      AiConversationState orphan =
          AiConversationState.parse(svc.outgoingState("продължи", cut, true, null, CONVERSATION));
      assertNotNull(orphan);
      assertEquals(
          "продължи", orphan.getRequest(), "a blob alone cannot supply an earlier authorisation");
    } finally {
      AiTurnMetrics.end();
      AiTurnToolLog.end();
      AiWriteEffects.end();
    }
  }

  /** «същото» needs the previous shape to survive read-only turns and chain resets. */
  @Test
  void recentWorkflowShapeIsRecordedAndCarriedForward() {
    AiAccountantServiceImpl svc = service(new ScriptedClient(), new StubDispatcher(), config(5));
    AiWriteEffects.begin();
    AiTurnToolLog.begin();
    AiTurnMetrics.begin(false);
    try {
      AiWriteEffects.touched("com.axelor.apps.account.db.Invoice", 606L);
      AiTurnToolLog.record("edit_supplier_invoice", true, AiTurnToolLog.Status.OK);
      AiTurnToolLog.record("validate_supplier_invoice", true, AiTurnToolLog.Status.OK);
      AiTurnToolLog.record("ventilate_supplier_invoice", true, AiTurnToolLog.Status.OK);

      AiConversationState after =
          AiConversationState.parse(
              svc.outgoingState("оправи, валидирай и осчетоводи", null, false, null, CONVERSATION));
      assertNotNull(after);
      assertEquals("EDIT_VALIDATE_VENTILATE", after.getShape());
      assertEquals("Invoice#606", after.getShapeTarget());
      assertTrue(after.isShapeOk());
    } finally {
      AiTurnMetrics.end();
      AiTurnToolLog.end();
      AiWriteEffects.end();
    }

    // A later turn that writes nothing must not erase what «същото» refers to.
    AiConversationState previous = new AiConversationState();
    previous.setShape("EDIT_VALIDATE_VENTILATE");
    previous.setShapeTarget("Invoice#606");
    previous.setShapeOk(true);
    AiWriteEffects.begin();
    AiTurnToolLog.begin();
    AiTurnMetrics.begin(true);
    try {
      AiConversationState carried =
          AiConversationState.parse(
              svc.outgoingState("а тази готова ли е?", previous, false, null, CONVERSATION));
      assertNotNull(carried);
      assertEquals("EDIT_VALIDATE_VENTILATE", carried.getShape());
    } finally {
      AiTurnMetrics.end();
      AiTurnToolLog.end();
      AiWriteEffects.end();
    }
  }
}
