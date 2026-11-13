package com.axelor.apps.aiaccountant.dto;

/**
 * One chat turn from the web client.
 *
 * <p>{@code responseId} is the previous Azure response id, owned and stored by the React chat. The
 * backend keeps no per-user conversation state: history lives in Azure and is chained with {@code
 * previous_response_id}. Null on the first message of a conversation.
 */
public class ChatRequest {

  private String message;
  private String responseId;
  private Long chainCompanyId;
  private String state;
  private String conversationId;
  private String turnKey;
  private boolean companyContextLoaded;
  private Long expectedCompanyId;
  private PageContext context;

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getResponseId() {
    return responseId;
  }

  public void setResponseId(String responseId) {
    this.responseId = responseId;
  }

  /**
   * The company {@code responseId} was issued under, echoed back verbatim from the previous reply.
   *
   * <p>The client never interprets it — it stores whatever the server last reported and returns it.
   * The server compares it with the company actually in force now, and starts a fresh Azure chain
   * when they differ, so one company's documents cannot stay in the model's context while the user
   * works in another. Null from a client that predates this field, which is read as "unknown" and
   * leaves the chain alone rather than resetting every turn for a stale browser bundle.
   */
  public Long getChainCompanyId() {
    return chainCompanyId;
  }

  public void setChainCompanyId(Long chainCompanyId) {
    this.chainCompanyId = chainCompanyId;
  }

  /**
   * The opaque conversation state from the previous reply, echoed back unread.
   *
   * <p>Carries how large the Azure chain had grown and whether an authorised workflow is still
   * unfinished. See {@code AiConversationState}. Null on a fresh conversation, and cleared by New
   * Conversation, which is what makes that button a real reset rather than a visual one.
   */
  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  /**
   * Which conversation this message belongs to — one per chat panel, minted by the client.
   *
   * <h2>What it is for</h2>
   *
   * Everything the assistant carries between turns used to be keyed by user and company alone: the
   * Azure chain through {@code responseId}, and the authorised job through the workflow row. Two
   * browser tabs are one user in one company, so they shared both. That is wrong in each direction
   * — the second tab inherits a model conversation about records the user is not looking at, and,
   * far worse, it inherits an authorisation to write to them. A job authorised by the sentence
   * typed in one tab must not be continuable by «продължи» typed in another.
   *
   * <p>So the conversation is the third part of the key. It is minted per tab and survives a
   * reload; a new tab is a new conversation, with its own chain and its own job, and New
   * Conversation mints another.
   *
   * <p>Opaque to the server, which only ever compares it. Absent or malformed is not an error but
   * it is not a conversation either: the turn runs without chaining onto anything and without
   * resuming any job, which is the safe reading of "I cannot tell you which conversation this is".
   */
  public String getConversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  /**
   * The client's own id for this send, used to make storing the transcript idempotent.
   *
   * <p>Minted fresh by the panel for every message and never reused. The server stores it alongside
   * the user's bubble and the reply, so a request that is retried — by the browser, by a proxy, by
   * a user who refreshed mid-flight — finds its rows already there and adds nothing. See {@code
   * AiChatTranscriptStore}, which is the only thing that reads it.
   *
   * <p>Opaque, and not a key to anything: who and which company are still resolved server-side from
   * the session. Null from a client that predates this field, which simply loses the idempotency
   * guard for that turn rather than failing it.
   */
  public String getTurnKey() {
    return turnKey;
  }

  public void setTurnKey(String turnKey) {
    this.turnKey = turnKey;
  }

  /**
   * Whether this request carries a company context at all — the flag that makes {@link
   * #getExpectedCompanyId()} readable.
   *
   * <h2>Why a null id is not enough on its own</h2>
   *
   * {@code expectedCompanyId} has three meanings and only two of them are a number. "I loaded the
   * transcript and the company is 7", "I loaded the transcript and there was no active company",
   * and "I have no idea, I never loaded one" are different facts, and the last is the dangerous one
   * — yet the middle and the last both arrive as a null id. Treating a missing context as "nothing
   * to contradict" is precisely the hole: a stale browser bundle that predates this field would
   * send no id and be allowed to execute a turn without ever proving which company's conversation
   * the user was reading, which is the whole thing the check exists to establish.
   *
   * <p>So the panel states it explicitly, and this defaults to {@code false}. A JSON body with no
   * such property deserialises to false and is refused — the fail-closed direction is also the
   * default one, which is the point: forgetting to send it cannot be mistaken for sending it.
   *
   * <p>Once true, a null {@code expectedCompanyId} is a positive claim ("no active company") and
   * matches only an actual null. It never matches a company.
   */
  public boolean isCompanyContextLoaded() {
    return companyContextLoaded;
  }

  public void setCompanyContextLoaded(boolean companyContextLoaded) {
    this.companyContextLoaded = companyContextLoaded;
  }

  /**
   * The company the transcript on screen was loaded for — a consistency token, and nothing else.
   *
   * <p>Only meaningful when {@link #isCompanyContextLoaded()} is true; see that field for why. Null
   * with the flag set means "I loaded the transcript and there was no active company", which is a
   * claim like any other and matches only an actual null.
   *
   * <h2>What it is for</h2>
   *
   * The user switches active company in another tab. This panel is still showing company A's chat,
   * still holding A's chain, and the user types "post it". By the time that arrives the server
   * resolves the active company as B, and the instruction — written while reading A's conversation,
   * about A's records — would be carried out in B's books. Nothing downstream can catch that: every
   * check below this point agrees the user is in B and is entitled to act in B.
   *
   * <p>So the panel states which company it believed it was in when it rendered the words the user
   * was reading, and the server refuses the turn when that is not the company now in force. The
   * instruction is never moved from one company to the other; it is refused, the panel reloads B's
   * transcript, and the user decides whether they still want it.
   *
   * <h2>What it is NOT</h2>
   *
   * It is never authorisation and never scope. The company every read, every write and every tool
   * runs under is still resolved server-side by {@code AiCompanyScope} from the session and the
   * user's active company, exactly as before; this value is only ever <em>compared</em> with it.
   * Sending a different number cannot widen anything — a caller who sends company B's id while
   * scoped to A does not gain access to B, they only get their own turn refused.
   */
  public Long getExpectedCompanyId() {
    return expectedCompanyId;
  }

  public void setExpectedCompanyId(Long expectedCompanyId) {
    this.expectedCompanyId = expectedCompanyId;
  }

  public PageContext getContext() {
    return context;
  }

  public void setContext(PageContext context) {
    this.context = context;
  }
}
