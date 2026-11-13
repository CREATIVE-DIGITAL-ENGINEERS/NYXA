package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import java.util.List;

/** Entry point for one chat turn, including any tool round-trips it needs. */
public interface AiAccountantService {

  ChatReply chat(ChatRequest request);

  /** Whether the assistant is switched on and configured; the UI hides itself when false. */
  boolean isAvailable();

  /**
   * The visible transcript for the authenticated user in the active company, oldest first.
   *
   * <p>This is what the panel renders after a reload, a new tab, a new session or a redeploy — the
   * browser keeps no copy of it any more. Scoped server-side from the session and the active
   * company scope, never from anything the caller sends.
   *
   * <p>Propagates the failure rather than returning an empty list: an unreadable transcript and an
   * empty one are opposite facts, and the panel must show a load error for the first rather than
   * present the second as success.
   */
  List<AiChatTranscriptStore.Message> history();

  /**
   * Deletes every server-side row for this user's chat in the active company — the visible
   * transcript, the stored conversation history, the authorised job and the chain measurement — for
   * "Delete conversation".
   *
   * <p>Scoped to the authenticated user and the active company; nothing about who or which company
   * is ever taken from the caller. Runs in one transaction, so the answer is either "the whole
   * conversation is gone" or "nothing was touched" — never a partial deletion reported as success,
   * and never a transcript cleared while the chain that produced it survives.
   *
   * @return true only when the deletion is known to have committed
   */
  boolean deleteConversation();
}
