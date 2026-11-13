package com.axelor.apps.aiaccountant.rest;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiAccountantService;
import com.axelor.apps.aiaccountant.service.AiChatTranscriptStore;
import com.axelor.apps.aiaccountant.tool.AiRoles;
import com.axelor.apps.aiaccountant.tool.AiToolPublisher;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP surface for the global chat widget, served at {@code /ws/ai-accountant/*}.
 *
 * <p>Auto-registered: {@code AppServletModule} binds every classpath type annotated {@code @Path}.
 * Authentication is enforced by the platform's Shiro filter over {@code /ws/*}; the explicit user
 * check below is a second line of defence, since tools run with the caller's permissions.
 *
 * <p>Plain JAX-RS + Jackson on purpose — the AOS {@code com.axelor.utils.api} helpers would add a
 * dependency for no benefit here.
 */
@Path("/ai-accountant")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AiAccountantRestController {

  private static final Logger LOG = LoggerFactory.getLogger(AiAccountantRestController.class);

  /** Lets the web client decide whether to render the floating button at all. */
  @GET
  @Path("/status")
  public Response status() {
    Map<String, Object> out = new LinkedHashMap<>();
    boolean available;
    try {
      available = Beans.get(AiAccountantService.class).isAvailable();
    } catch (Exception e) {
      LOG.warn("AI Accountant: status check failed: {}", e.getMessage());
      available = false;
    }
    // Available to this user: the panel shows only when they may chat (G8), so a user without
    // accounting.ai.read isn't offered an assistant that refuses every message.
    out.put("available", available && AiRoles.mayChat(AuthUtils.getUser()));
    return Response.ok(out).build();
  }

  /**
   * Compares the Java tool registry with the tools declared on the Foundry agent. Read-only and
   * admin-only.
   */
  @GET
  @Path("/tools/diff")
  public Response toolsDiff() {
    if (!AuthUtils.isAdmin(AuthUtils.getUser())) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }
    if (!Beans.get(AiAccountantConfig.class).isAppActive()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      AiToolPublisher.ToolDiff diff = Beans.get(AiToolPublisher.class).diff();
      out.put("inSync", diff.isInSync());
      out.put("missingOnAgent", diff.getMissingOnAgent());
      out.put("staleOnAgent", diff.getStaleOnAgent());
      out.put("outOfDateOnAgent", diff.getOutOfDateOnAgent());
    } catch (Exception e) {
      out.put("error", e.getMessage());
    }
    return Response.ok(out).build();
  }

  /**
   * Publishes the Java tool declarations onto the Foundry agent, creating a new agent version.
   *
   * <p>Required because the Responses API refuses request-local tools when an agent is specified,
   * so tools must live on the agent definition. This writes to a CTO-owned resource, so it is
   * admin-only and additionally gated behind {@code aiaccountant.azure.allow.agent.publish}.
   * Nothing publishes automatically — not at startup, not on demand from the chat.
   */
  @POST
  @Path("/tools/publish")
  public Response publishTools() {
    if (!AuthUtils.isAdmin(AuthUtils.getUser())) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }
    if (!Beans.get(AiAccountantConfig.class).isAppActive()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      out.put("version", Beans.get(AiToolPublisher.class).publish());
      out.put("published", true);
    } catch (Exception e) {
      LOG.error("AI Accountant: publishing tool declarations failed.", e);
      out.put("published", false);
      out.put("error", e.getMessage());
    }
    return Response.ok(out).build();
  }

  /**
   * The visible transcript for the authenticated user in the active company, oldest first.
   *
   * <p>This is what the panel renders on mount. The browser keeps no copy of the conversation any
   * more, so a reload, a new tab, a new session, a container restart or a redeploy all come back
   * here and get the same messages in the same order.
   *
   * <p>Scope is resolved server-side from the session and the active company; there is deliberately
   * no parameter, because a transcript endpoint that accepted one would be an endpoint for reading
   * somebody else's chat.
   *
   * <p>A read that fails answers 500, NOT an empty list. The two mean opposite things and the panel
   * shows an explicit load error with a Retry for the first — presenting a failure as an empty
   * conversation is how a user concludes their history is gone and stops trusting the feature.
   */
  @GET
  @Path("/conversation/history")
  public Response history() {
    if (AuthUtils.getUser() == null) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(ChatReply.error("Not authenticated."))
          .build();
    }
    try {
      List<Map<String, Object>> messages = new ArrayList<>();
      for (AiChatTranscriptStore.Message message : Beans.get(AiAccountantService.class).history()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role(message.role()));
        item.put("text", message.text() == null ? "" : message.text());
        if (!message.tools().isEmpty()) {
          item.put("tools", message.tools());
        }
        messages.add(item);
      }
      Map<String, Object> out = new LinkedHashMap<>();
      // Which company this transcript belongs to. The web client has no notion of an active company
      // — every company decision in this feature is the server's — so this is how it learns that
      // the
      // books changed under it: a chat reply already carries the same field, and a reply whose
      // company differs from the one this transcript was loaded for makes the panel reload.
      out.put("companyId", AiCompanyScope.companyIdOrNull());
      out.put("messages", messages);
      return Response.ok(out).build();
    } catch (Exception e) {
      LOG.error("AI Accountant: reading the conversation history failed.", e);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("error", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(out).build();
    }
  }

  /** The stored role code as the panel's own vocabulary. Unknown codes render as an assistant. */
  private static String role(int roleSelect) {
    switch (roleSelect) {
      case AiChatTranscriptStore.ROLE_USER:
        return "user";
      case AiChatTranscriptStore.ROLE_ERROR:
        return "error";
      default:
        return "assistant";
    }
  }

  /**
   * "Delete conversation": erases the visible transcript, the stored history, the job and the chain
   * measurement for this user in the active company, scoped server-side to the authenticated user
   * and the active company — never to anything the client might send. See {@code
   * AiAccountantService#deleteConversation}.
   *
   * <p>The client is expected to clear its own screen, {@code responseId} and state, and mint a
   * fresh conversation id, ONLY once {@code deleted} comes back true — a failed deletion must not
   * be reported to the user as a cleared conversation.
   */
  @POST
  @Path("/conversation/delete")
  public Response deleteConversation(ChatRequest request) {
    if (AuthUtils.getUser() == null) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(ChatReply.error("Not authenticated."))
          .build();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      out.put("deleted", Beans.get(AiAccountantService.class).deleteConversation());
    } catch (Exception e) {
      LOG.error("AI Accountant: deleting a conversation failed.", e);
      out.put("deleted", false);
      out.put("error", e.getMessage());
    }
    return Response.ok(out).build();
  }

  @POST
  @Path("/chat")
  public Response chat(ChatRequest request) {
    User user = AuthUtils.getUser();
    if (user == null) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(ChatReply.error("Not authenticated."))
          .build();
    }
    // G8: the assistant is a capability, not something every login gets. Admins pass,
    // as they bypass the permission engine anyway.
    if (!AiRoles.mayChat(user)) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(
              ChatReply.error(
                  I18n.get(
                      "You don't have access to the AI Accountant (role accounting.ai.read)."
                          + " Contact your administrator.")))
          .build();
    }
    try {
      return Response.ok(Beans.get(AiAccountantService.class).chat(request)).build();
    } catch (Exception e) {
      // Surface a readable message to the chat panel; the stack trace stays in the log.
      LOG.error("AI Accountant: chat failed.", e);
      return Response.ok(ChatReply.error(e.getMessage())).build();
    }
  }
}
