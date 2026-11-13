package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * AssetTransactions (SourceDocumentsAnnual) — транзакциите с активи за годината.
 *
 * <p>⚠️ Схемата иска поне една транзакция, а книгата (SD.AT.2) допуска брой 0 без нито една. При
 * година без транзакции се подава нулевата структура по QA IV.2.6 — виж {@code
 * BgSaftZeroStructures}.
 */
public final class SaftAssetTransactions {

  /** SD.AT.1 — броят на транзакциите за годината. */
  private Long numberOfAssetTransactions;

  /** SD.AT.2 — поне една. */
  private final List<SaftAssetTransaction> assetTransactions = new ArrayList<>();

  public Long getNumberOfAssetTransactions() {
    return numberOfAssetTransactions;
  }

  public SaftAssetTransactions numberOfAssetTransactions(Long value) {
    this.numberOfAssetTransactions = value;
    return this;
  }

  public List<SaftAssetTransaction> getAssetTransactions() {
    return assetTransactions;
  }

  public SaftAssetTransactions addAssetTransaction(SaftAssetTransaction value) {
    this.assetTransactions.add(value);
    return this;
  }
}
