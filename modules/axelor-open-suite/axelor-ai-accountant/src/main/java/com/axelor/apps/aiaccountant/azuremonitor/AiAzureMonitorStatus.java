package com.axelor.apps.aiaccountant.azuremonitor;

import com.google.inject.Singleton;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Whether the last Azure poll worked, so the Stats page can say so plainly.
 *
 * <h2>Why this is needed at all</h2>
 *
 * An empty chart has two very different causes: nothing happened, or nothing could be read. Without
 * somewhere to record the second, a missing {@code Monitoring Reader} role looks exactly like a
 * quiet afternoon, and the page quietly lies. The snapshot rows can say when data was last
 * collected but not why the last attempt failed.
 *
 * <h2>Why in memory and not a table</h2>
 *
 * It is the state of this node's last attempt, it is worthless after a restart, and it changes on a
 * schedule rather than on a transaction. A table would need retention of its own and would be read
 * once a day by one screen. The trade is that a multi-node deployment reports per node —
 * acceptable, because the durable answer to "is the data fresh" is the {@code collectedDateTime} on
 * the rows themselves, which every node writes and any node can read.
 */
@Singleton
public class AiAzureMonitorStatus {

  private volatile LocalDateTime lastAttemptAt;
  private volatile LocalDateTime lastSuccessAt;
  private volatile AiAzureMonitorUnavailable.Reason lastFailureReason;
  private volatile String lastMessage;
  private volatile int lastPointCount;

  public synchronized void recordSuccess(int points) {
    lastAttemptAt = LocalDateTime.now(ZoneOffset.UTC);
    lastSuccessAt = lastAttemptAt;
    lastFailureReason = null;
    lastMessage = null;
    lastPointCount = points;
  }

  public synchronized void recordFailure(AiAzureMonitorUnavailable.Reason reason, String message) {
    lastAttemptAt = LocalDateTime.now(ZoneOffset.UTC);
    lastFailureReason = reason;
    lastMessage = message;
  }

  public boolean isHealthy() {
    return lastFailureReason == null && lastSuccessAt != null;
  }

  public LocalDateTime getLastAttemptAt() {
    return lastAttemptAt;
  }

  public LocalDateTime getLastSuccessAt() {
    return lastSuccessAt;
  }

  public AiAzureMonitorUnavailable.Reason getLastFailureReason() {
    return lastFailureReason;
  }

  public String getLastMessage() {
    return lastMessage;
  }

  public int getLastPointCount() {
    return lastPointCount;
  }
}
