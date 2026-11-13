package com.axelor.apps.aiaccountant.azuremonitor;

import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.time.LocalDateTime;

/**
 * When each stored series was last written.
 *
 * <h2>Why this is read from the rows and not from the in-memory status</h2>
 *
 * {@link AiAzureMonitorStatus} knows what this node's last attempt did, which is exactly the wrong
 * scope for the question "is the data on this page current". It is empty after a restart and it
 * describes one node. The rows carry {@code collectedDateTime}, written by whichever node collected
 * them, so this answer survives both.
 *
 * <p>Each grain is asked separately because they are refreshed on different schedules: a stale
 * hourly series beside a current daily one is a real and reportable state, and a single "last
 * updated" figure would hide whichever of the two was broken.
 */
@Singleton
public class AiAzureMetricSnapshotFreshness {

  public LocalDateTime lastCollectedHourly() {
    return lastCollected(AiAzureGrain.HOURLY);
  }

  public LocalDateTime lastCollectedDaily() {
    return lastCollected(AiAzureGrain.DAILY);
  }

  /** Null when the series has never been collected, or cannot be read. */
  protected LocalDateTime lastCollected(String grain) {
    try {
      return JPA.em()
          .createQuery(
              "SELECT MAX(self.collectedDateTime) FROM AiAzureMetricSnapshot self"
                  + " WHERE self.intervalGrain = :grain",
              LocalDateTime.class)
          .setParameter("grain", grain)
          .getSingleResult();
    } catch (RuntimeException e) {
      // The banner is a convenience; failing to date it must not fail the page it sits on.
      return null;
    }
  }
}
