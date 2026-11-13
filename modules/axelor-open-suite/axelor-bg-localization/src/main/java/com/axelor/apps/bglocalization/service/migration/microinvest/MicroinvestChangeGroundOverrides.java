package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Explicit, hand-reviewed overrides of {@code BgContractAmendment.bgChangeGround} for one specific
 * (company Bulstat, Microinvest PayrollID) pair, loaded from {@code
 * microinvest/change-ground-overrides.csv}. Consulted BEFORE the normal ContractReasonID lookup
 * ({@link MicroinvestPilotImportService#changeGroundOf}) -- never the other way around, so a future
 * Microinvest data correction (a real ETZ code finally entered on the source row) is not silently
 * shadowed by a stale override.
 *
 * <p>Keyed by Bulstat, not just PayrollID: Microinvest PayrollIDs are only unique within one
 * company's database, so an override for one pilot company must never accidentally apply to a
 * same-numbered PayrollID in another company's export.
 *
 * <p>Deliberately a separate file from {@code formula-line-mapping.csv} (a name-based, cross-
 * company allowlist): this one is a per-row, per-company, individually-reviewed correction of a
 * source data quality gap -- see the CSV's own header comment for why each row exists.
 */
final class MicroinvestChangeGroundOverrides {

  private static final String OVERRIDES_RESOURCE = "/microinvest/change-ground-overrides.csv";

  private static final Map<String, String> CODE_BY_BULSTAT_AND_PAYROLL_ID = load();

  private MicroinvestChangeGroundOverrides() {}

  /** The hand-confirmed change-ground code for this exact (bulstat, PayrollID) pair, or null. */
  static String overrideFor(String companyBulstat, int payrollId) {
    if (companyBulstat == null || companyBulstat.isBlank()) {
      return null;
    }
    return CODE_BY_BULSTAT_AND_PAYROLL_ID.get(key(companyBulstat.trim(), payrollId));
  }

  private static String key(String bulstat, int payrollId) {
    return bulstat + ":" + payrollId;
  }

  private static Map<String, String> load() {
    Map<String, String> map = new HashMap<>();
    try (InputStream in =
        MicroinvestChangeGroundOverrides.class.getResourceAsStream(OVERRIDES_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource: " + OVERRIDES_RESOURCE);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim();
          if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            continue;
          }
          String[] parts = trimmed.split(";", -1);
          if (parts.length < 3) {
            continue;
          }
          String bulstat = parts[0].trim();
          String payrollIdText = parts[1].trim();
          String code = parts[2].trim();
          if (bulstat.isEmpty() || payrollIdText.isEmpty() || code.isEmpty()) {
            continue;
          }
          try {
            int payrollId = Integer.parseInt(payrollIdText);
            map.put(key(bulstat, payrollId), code);
          } catch (NumberFormatException e) {
            // Malformed row -- skipped rather than guessed at, same convention as the other
            // migration mapping loaders in this package.
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + OVERRIDES_RESOURCE, e);
    }
    return Collections.unmodifiableMap(map);
  }
}
