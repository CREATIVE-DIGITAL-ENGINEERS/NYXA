package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureGrain;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes telemetry that is past its usefulness.
 *
 * <h2>Why each series has its own window</h2>
 *
 * The three tables age at completely different rates. Local per-request rows are the finest-grained
 * and the most numerous, and are worth months. The hourly Azure series exists only to draw "today"
 * and is worthless within a week — kept for a year it would be hundreds of thousands of rows that
 * no query selects. The daily series is the opposite: small, and the only record of a month ago, so
 * it is kept far longer.
 *
 * <p>Bulk JPQL rather than loading and removing: the same reason {@code
 * BgOcrIntakeService.markFailed} does it — a delete of tens of thousands of rows through the
 * persistence context is a memory problem and nothing here needs entity callbacks.
 */
@Singleton
public class AiAccountantRetentionService {

  private static final Logger LOG = LoggerFactory.getLogger(AiAccountantRetentionService.class);

  private final AiAccountantConfig config;

  @Inject
  public AiAccountantRetentionService(AiAccountantConfig config) {
    this.config = config;
  }

  /**
   * Runs every prune in one pass. Returns how many rows went.
   *
   * <p>Skipped while the AI Accountant application is not installed/active: deactivating the app
   * stops its background maintenance too, and a skipped pass never deletes anything, so this never
   * costs data — it only ever means the tables temporarily grow a little past their usual window.
   */
  public int pruneAll() {
    if (!config.isAppActive()) {
      LOG.debug("AI Accountant: application is not installed/active; skipping retention.");
      return 0;
    }
    int removed = 0;
    removed += pruneUsageStats();
    removed += pruneSnapshots(AiAzureGrain.HOURLY, config.azureMonitorHourlyRetentionDays());
    removed += pruneSnapshots(AiAzureGrain.DAILY, config.azureMonitorDailyRetentionDays());
    if (removed > 0) {
      LOG.info("AI Accountant: retention removed {} telemetry row(s).", removed);
    }
    return removed;
  }

  @Transactional
  public int pruneUsageStats() {
    int days = Math.max(1, config.usageStatRetentionDays());
    return JPA.em()
        .createQuery("DELETE FROM AiUsageStat self WHERE self.requestDateTime < :cutoff")
        .setParameter("cutoff", cutoff(days))
        .executeUpdate();
  }

  /**
   * Prunes one grain, by that grain's own window.
   *
   * <p>The grain is always part of the predicate. Deleting by age alone would apply the hourly
   * series' short window to the daily series as well and quietly destroy the only long-range data
   * the page has.
   */
  @Transactional
  public int pruneSnapshots(String grain, int retentionDays) {
    int days = Math.max(1, retentionDays);
    return JPA.em()
        .createQuery(
            "DELETE FROM AiAzureMetricSnapshot self"
                + " WHERE self.intervalGrain = :grain AND self.bucketTimestamp < :cutoff")
        .setParameter("grain", grain)
        .setParameter("cutoff", cutoff(days))
        .executeUpdate();
  }

  protected LocalDateTime cutoff(int days) {
    return LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
  }
}
