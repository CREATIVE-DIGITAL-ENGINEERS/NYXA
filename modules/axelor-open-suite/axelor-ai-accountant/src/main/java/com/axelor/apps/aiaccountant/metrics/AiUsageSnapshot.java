package com.axelor.apps.aiaccountant.metrics;

import java.time.LocalDateTime;

/**
 * What one request measured, as plain values, ready to cross a thread boundary.
 *
 * <h2>Why a snapshot rather than the entity</h2>
 *
 * The row is written on a different thread from the request that produced it (see {@link
 * AiUsageStatRecorder}), and that thread has its own persistence context. Handing it a JPA entity —
 * or a {@code User}, or a {@code Company} — would hand it an object belonging to a context that is
 * about to close, which is the classic way to turn a metric into a {@code
 * LazyInitializationException} inside the thing being measured. So nothing here is managed by
 * Hibernate: ids, strings and numbers only.
 *
 * <h2>Ids captured here, labels resolved later</h2>
 *
 * The user id has to be read on the request thread — it comes from the thread-bound security
 * subject — so it is captured here. The display labels are not: they are looked up by {@link
 * AiUsageStatWriter} on the writing thread, so that measuring a request costs no query at all on
 * the path the user is waiting on.
 *
 * <p>Populated by its producer and then handed to the queue. It must not be modified afterwards;
 * the queue's own handoff is what makes the values visible to the writer.
 */
public final class AiUsageSnapshot {

  private LocalDateTime requestDateTime;
  private Long userId;
  private Long companyId;
  private String caller;
  private String agentRole;
  private String outcome;
  private boolean chained;
  private String model;
  private String agentVersion;
  private long durationMs;
  private long inputTokens;
  private long cachedInputTokens;
  private long outputTokens;
  private long reasoningTokens;
  private long totalTokens;

  public AiUsageSnapshot at(LocalDateTime when) {
    this.requestDateTime = when;
    return this;
  }

  public AiUsageSnapshot user(Long userId) {
    this.userId = userId;
    return this;
  }

  public AiUsageSnapshot company(Long companyId) {
    this.companyId = companyId;
    return this;
  }

  public AiUsageSnapshot caller(String caller) {
    this.caller = caller;
    return this;
  }

  public AiUsageSnapshot agentRole(String agentRole) {
    this.agentRole = agentRole;
    return this;
  }

  public AiUsageSnapshot outcome(String outcome) {
    this.outcome = outcome;
    return this;
  }

  public AiUsageSnapshot chained(boolean chained) {
    this.chained = chained;
    return this;
  }

  public AiUsageSnapshot model(String model) {
    this.model = model;
    return this;
  }

  public AiUsageSnapshot agentVersion(String agentVersion) {
    this.agentVersion = agentVersion;
    return this;
  }

  public AiUsageSnapshot durationMs(long durationMs) {
    this.durationMs = Math.max(0, durationMs);
    return this;
  }

  /**
   * The provider's own figures.
   *
   * <p>{@code cached} is part of {@code in} and {@code reasoning} is part of {@code out} — they are
   * breakdowns, not additional tokens. {@code total} is what the provider reported, never a sum
   * computed here. Anything aggregating these later must not add a part to its whole.
   */
  public AiUsageSnapshot tokens(long in, long cached, long out, long reasoning, long total) {
    this.inputTokens = Math.max(0, in);
    this.cachedInputTokens = Math.max(0, cached);
    this.outputTokens = Math.max(0, out);
    this.reasoningTokens = Math.max(0, reasoning);
    this.totalTokens = Math.max(0, total);
    return this;
  }

  public LocalDateTime getRequestDateTime() {
    return requestDateTime;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public String getCaller() {
    return caller;
  }

  public String getAgentRole() {
    return agentRole;
  }

  public String getOutcome() {
    return outcome;
  }

  public boolean isChained() {
    return chained;
  }

  public String getModel() {
    return model;
  }

  public String getAgentVersion() {
    return agentVersion;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public long getInputTokens() {
    return inputTokens;
  }

  public long getCachedInputTokens() {
    return cachedInputTokens;
  }

  public long getOutputTokens() {
    return outputTokens;
  }

  public long getReasoningTokens() {
    return reasoningTokens;
  }

  public long getTotalTokens() {
    return totalTokens;
  }
}
