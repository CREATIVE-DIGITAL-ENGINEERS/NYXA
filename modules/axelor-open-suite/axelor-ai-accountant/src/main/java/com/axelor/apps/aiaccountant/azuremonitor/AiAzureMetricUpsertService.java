package com.axelor.apps.aiaccountant.azuremonitor;

import com.axelor.apps.aiaccountant.db.AiAzureMetricSnapshot;
import com.axelor.apps.aiaccountant.db.repo.AiAzureMetricSnapshotRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Writes datapoints into the snapshot table, replacing rather than adding.
 *
 * <h2>Why upsert is the only correct verb here</h2>
 *
 * Every poll re-reads its whole window, not just what is new, because Azure revises recent buckets
 * after the fact — ask only for new buckets and the first, incomplete figure for each hour is the
 * one that stays forever. Re-reading is what makes those corrections land, and re-reading with
 * INSERT would multiply every bucket by the number of polls that saw it. So the natural key is
 * looked up and the value overwritten: polling twice leaves one row, and polling after Azure has
 * revised a figure leaves the revised one.
 */
@Singleton
public class AiAzureMetricUpsertService {

  /**
   * One transaction for the whole batch.
   *
   * <p>A batch that fails rolls back whole, and that is deliberately fine: the next poll re-reads
   * the same window and writes the same rows, so a lost batch heals itself rather than needing
   * recovery. Per-row transactions would be dozens of commits for the same result.
   */
  @Transactional
  public int upsertAll(String resourceId, List<AiAzureMetricPoint> points) {
    if (points == null || points.isEmpty()) {
      return 0;
    }
    LocalDateTime collectedAt = LocalDateTime.now(ZoneOffset.UTC);
    int written = 0;
    for (AiAzureMetricPoint point : points) {
      AiAzureMetricSnapshot row = find(resourceId, point);
      if (row == null) {
        row = new AiAzureMetricSnapshot();
        row.setResourceId(resourceId);
        row.setMetricName(point.getMetricName());
        row.setAggregation(point.getAggregation());
        row.setIntervalGrain(point.getIntervalGrain());
        row.setBucketTimestamp(point.getBucketTimestamp());
        row.setDimensionKey(point.getDimensionKey());
      }
      row.setModelDeploymentName(point.getModelDeploymentName());
      row.setModelName(point.getModelName());
      row.setUnit(point.getUnit());
      row.setMetricValue(point.getValue());
      row.setCollectedDateTime(collectedAt);
      save(row);
      written++;
    }
    return written;
  }

  protected AiAzureMetricSnapshot save(AiAzureMetricSnapshot row) {
    return repo().save(row);
  }

  /** The natural key, in full. Anything less would let one series overwrite another. */
  protected AiAzureMetricSnapshot find(String resourceId, AiAzureMetricPoint point) {
    return repo()
        .all()
        .filter(
            "self.resourceId = :resourceId"
                + " AND self.metricName = :metricName"
                + " AND self.aggregation = :aggregation"
                + " AND self.intervalGrain = :grain"
                + " AND self.bucketTimestamp = :bucket"
                + " AND self.dimensionKey = :dimensionKey")
        .bind("resourceId", resourceId)
        .bind("metricName", point.getMetricName())
        .bind("aggregation", point.getAggregation())
        .bind("grain", point.getIntervalGrain())
        .bind("bucket", point.getBucketTimestamp())
        .bind("dimensionKey", point.getDimensionKey())
        .fetchOne();
  }

  protected AiAzureMetricSnapshotRepository repo() {
    return Beans.get(AiAzureMetricSnapshotRepository.class);
  }
}
