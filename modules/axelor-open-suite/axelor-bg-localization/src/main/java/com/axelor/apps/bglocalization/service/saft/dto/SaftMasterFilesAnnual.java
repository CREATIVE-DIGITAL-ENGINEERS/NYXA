package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * MasterFilesAnnual — основните данни на годишния файл: Assets с Asset 0..n (MF.A.1).
 *
 * <p>Празният списък дава {@code <Assets/>} — вярно само за лице без активи. Книгата (MF.A.1):
 * „Отчитането на активите е задължително за всички лица, които имат активи.“ Затова годишният файл
 * не се сглобява от {@code BgSaftAuditFileService}, докато извличането на активите (SAF-T A3) не
 * дойде. Owners (2.11) е незадължителна и не се пише.
 */
public final class SaftMasterFilesAnnual {

  /** MF.A.1 — 0..n. */
  private final List<SaftAsset> assets = new ArrayList<>();

  public List<SaftAsset> getAssets() {
    return assets;
  }

  public SaftMasterFilesAnnual addAsset(SaftAsset value) {
    this.assets.add(value);
    return this;
  }
}
