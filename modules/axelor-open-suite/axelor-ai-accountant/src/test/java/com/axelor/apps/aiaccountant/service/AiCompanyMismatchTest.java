package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.client.FoundryResponsesClient;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.tool.AiToolDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A turn written for one company must never be carried out in another.
 *
 * <h2>The window this closes</h2>
 *
 * The user switches active company in another tab. This panel still shows company A's conversation
 * and still holds A's chain. The user reads it, types "post it", and presses send. The server now
 * resolves the active company as B — and every check downstream agrees: the user is authenticated,
 * they are entitled to act in B, the tools are scoped to B. Nothing below this point can tell that
 * the instruction was written about A's records, because by the time it is read there is no trace
 * of A left in the request except the transcript the user was looking at.
 *
 * <p>So the panel sends the company its transcript was loaded for, and the server refuses the turn
 * when that is not the company in force. These tests assert the part that matters: on a mismatch
 * nothing runs. Not the model, not a tool, not the transcript write.
 */
class AiCompanyMismatchTest {

  /** Fails the test if the model is ever called. */
  private static class NeverCalledClient implements FoundryResponsesClient {
    final List<String> calls = new ArrayList<>();

    @Override
    public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
      calls.add(String.valueOf(previousResponseId));
      throw new AssertionError("The model was called on a turn that should have been refused.");
    }
  }

  /** Fails the test if any tool is ever dispatched. */
  private static final class NeverCalledDispatcher extends AiToolDispatcher {
    final List<String> calls = new ArrayList<>();

    NeverCalledDispatcher() {
      super(null, null);
    }

    @Override
    public String dispatch(String name, String argumentsJson) {
      calls.add(name);
      throw new AssertionError("A tool was dispatched on a turn that should have been refused.");
    }
  }

  private static AiAccountantConfig config() {
    AiAccountantConfig config = Mockito.mock(AiAccountantConfig.class);
    Mockito.when(config.isEnabled()).thenReturn(true);
    Mockito.when(config.isChatConfigured()).thenReturn(true);
    Mockito.when(config.maxMessageChars()).thenReturn(4000);
    return config;
  }

  /**
   * As {@code service(...)}, wired with an {@link AiWorkflowController} whose {@code
   * pendingProposalStore()} never has anything pending — this suite has no Guice injector, and
   * {@link AiWorkflowController#promoteIfConfirmed} no longer guards a broken one itself, see its
   * own javadoc.
   */
  private static AiAccountantServiceImpl service(
      FoundryResponsesClient client, AiToolDispatcher dispatcher, AiAccountantConfig config) {
    return new AiAccountantServiceImpl(client, dispatcher, config) {
      @Override
      protected AiWorkflowController workflowController() {
        return new AiWorkflowController() {
          @Override
          protected AiPendingProposalStore pendingProposalStore() {
            return new NoOpPendingProposalStore();
          }
        };
      }
    };
  }

  /**
   * A request that loaded its company context and states this company.
   *
   * <p>There is no authenticated session in these tests, so {@code AiCompanyScope} resolves no
   * company at all. That makes {@code requestExpecting(null)} the matching case and any number the
   * mismatching one — which is exactly the pairing the null rules need exercised.
   */
  private static ChatRequest requestExpecting(Long companyId) {
    ChatRequest r = loadedRequest();
    r.setExpectedCompanyId(companyId);
    return r;
  }

  /** A request whose context was loaded, before a company is stated. */
  private static ChatRequest loadedRequest() {
    ChatRequest r = new ChatRequest();
    r.setMessage("Осчетоводи фактура 576");
    r.setConversationId("c-test-conversation");
    r.setTurnKey("t-1");
    r.setCompanyContextLoaded(true);
    return r;
  }

  /**
   * The headline case, and also the non-null-against-null half of the null rule: the client states
   * company 42, the server resolves none, and they do not agree. A stated company is never
   * satisfied by an absent one.
   */
  @Test
  void aCompanyMismatchInvokesNoModelAndNoTools() {
    NeverCalledClient client = new NeverCalledClient();
    NeverCalledDispatcher dispatcher = new NeverCalledDispatcher();

    ChatReply reply = service(client, dispatcher, config()).chat(requestExpecting(42L));

    // The two assertions this test exists for.
    assertTrue(client.calls.isEmpty(), "the model must not be called");
    assertTrue(dispatcher.calls.isEmpty(), "no tool must be dispatched");

    assertTrue(reply.isCompanyMismatch());
    assertTrue(reply.getError() != null && !reply.getError().isEmpty());
    // Nothing was produced, so there is nothing for the panel to chain onto or resume.
    assertNull(reply.getReply());
    assertNull(reply.getResponseId());
    assertNull(reply.getState());
    assertTrue(reply.getToolsUsed().isEmpty());
  }

  /** The refusal reports the company actually in force, so the client knows what to load. */
  @Test
  void theRefusalCarriesTheServersCompanyNotTheClientsClaim() {
    ChatReply reply =
        service(new NeverCalledClient(), new NeverCalledDispatcher(), config())
            .chat(requestExpecting(42L));

    assertEquals(null, reply.getCompanyId());
    assertTrue(reply.isCompanyMismatch());
  }

  /**
   * A request that never loaded a company context is refused — the blocker this field exists for.
   *
   * <p>A stale browser bundle sends no such property, it deserialises to false, and the turn stops
   * here. Before this, a missing context was read as "no claim to contradict" and the turn ran: an
   * instruction executed without the client ever having proved which company's conversation the
   * user was reading, which is the entire thing the check is supposed to establish.
   */
  @Test
  void aRequestThatNeverLoadedItsCompanyContextIsRefused() {
    NeverCalledClient client = new NeverCalledClient();
    NeverCalledDispatcher dispatcher = new NeverCalledDispatcher();
    ChatRequest stale = loadedRequest();
    stale.setCompanyContextLoaded(false);

    ChatReply reply = service(client, dispatcher, config()).chat(stale);

    assertTrue(client.calls.isEmpty(), "the model must not be called");
    assertTrue(dispatcher.calls.isEmpty(), "no tool must be dispatched");
    assertTrue(reply.isCompanyMismatch());
    // Told to reload, not to resend: a bundle that cannot state its context now will not manage it
    // on the next attempt either, and "try again" would loop forever.
    assertTrue(reply.getError().contains("reload the page"));
    assertNull(reply.getReply());
  }

  /** The default is the safe one: a freshly deserialised body proves nothing until it says so. */
  @Test
  void theContextFlagDefaultsToNotLoaded() {
    assertFalse(new ChatRequest().isCompanyContextLoaded());
  }

  /**
   * "There is no active company" matches only an actual absence — it is a claim, not a wildcard.
   *
   * <p>The server resolves no company in these tests, so this is the exact-match case, and reaching
   * the model is the proof. {@link NeverCalledClient} throws an {@code AssertionError} the moment
   * it is called; an {@code Error} is not caught by the service's {@code catch (RuntimeException)},
   * so it arrives here intact. The same tripwire that fails the refusal tests is what passes this
   * one.
   */
  @Test
  void aStatedNullCompanyMatchesAnActualNullCompany() {
    NeverCalledClient client = new NeverCalledClient();
    AiAccountantServiceImpl service = service(client, new NeverCalledDispatcher(), config());

    AssertionError reached =
        assertThrows(AssertionError.class, () -> service.chat(requestExpecting(null)));

    assertTrue(reached.getMessage().contains("The model was called"));
    assertEquals(1, client.calls.size());
  }
}
