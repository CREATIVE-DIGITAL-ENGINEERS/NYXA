package com.axelor.apps.aiaccountant.dto;

import java.util.ArrayList;
import java.util.List;

/** The assistant's answer for one turn, plus what it took to produce it. */
public class ChatReply {

  private String reply;
  private String responseId;
  private Long companyId;
  private String state;
  private List<String> toolsUsed = new ArrayList<>();
  private boolean truncated;
  private String error;
  private boolean companyMismatch;
  private WriteResult writeResult;

  /**
   * What this turn actually committed, so the client can refresh a record the user already has
   * open.
   *
   * <p>Only present when something was written and the transaction succeeded. Absent means "nothing
   * changed" — the client must not refresh on it.
   */
  public static class WriteResult {
    private boolean refresh;
    private List<Record> records = new ArrayList<>();

    /** True when at least one record changed and is worth reloading. */
    public boolean isRefresh() {
      return refresh;
    }

    public void setRefresh(boolean refresh) {
      this.refresh = refresh;
    }

    public List<Record> getRecords() {
      return records;
    }

    public void setRecords(List<Record> records) {
      this.records = records;
    }
  }

  /** One changed record: fully-qualified model name plus id, which is what a tab is keyed by. */
  public static class Record {
    private String model;
    private Long id;

    public Record() {}

    public Record(String model, Long id) {
      this.model = model;
      this.id = id;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }
  }

  public WriteResult getWriteResult() {
    return writeResult;
  }

  public void setWriteResult(WriteResult writeResult) {
    this.writeResult = writeResult;
  }

  public static ChatReply of(String reply, String responseId) {
    ChatReply r = new ChatReply();
    r.reply = reply;
    r.responseId = responseId;
    return r;
  }

  public static ChatReply error(String message) {
    ChatReply r = new ChatReply();
    r.error = message;
    return r;
  }

  /**
   * The turn was refused because the active company is no longer the one the panel was showing.
   *
   * <p>Nothing ran: no routing, no model call, no tool, and the message was not even stored. {@code
   * companyId} carries the company now in force, so the client can load that company's transcript
   * and ask the user to send the instruction again — deliberately requiring them to re-issue it
   * rather than replaying words written about a different set of books.
   *
   * @param activeCompanyId the company the server resolved, never the one the client claimed
   */
  public static ChatReply companyMismatch(String message, Long activeCompanyId) {
    ChatReply r = new ChatReply();
    r.error = message;
    r.companyMismatch = true;
    r.companyId = activeCompanyId;
    return r;
  }

  /** True when this turn was refused because the active company changed. See above. */
  public boolean isCompanyMismatch() {
    return companyMismatch;
  }

  public void setCompanyMismatch(boolean companyMismatch) {
    this.companyMismatch = companyMismatch;
  }

  public String getReply() {
    return reply;
  }

  public void setReply(String reply) {
    this.reply = reply;
  }

  public String getResponseId() {
    return responseId;
  }

  public void setResponseId(String responseId) {
    this.responseId = responseId;
  }

  /**
   * The company this turn ran under, to be stored beside {@code responseId} and sent back next turn
   * as {@code chainCompanyId}. See {@code ChatRequest.getChainCompanyId()}.
   */
  public Long getCompanyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  /** Conversation state for the client to store and return next turn; null clears it. */
  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public List<String> getToolsUsed() {
    return toolsUsed;
  }

  public void setToolsUsed(List<String> toolsUsed) {
    this.toolsUsed = toolsUsed;
  }

  /** True when the tool-iteration cap stopped the loop before the model finished. */
  public boolean isTruncated() {
    return truncated;
  }

  public void setTruncated(boolean truncated) {
    this.truncated = truncated;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
