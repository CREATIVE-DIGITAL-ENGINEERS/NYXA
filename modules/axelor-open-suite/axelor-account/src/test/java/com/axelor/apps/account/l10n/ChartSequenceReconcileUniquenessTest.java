/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.l10n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against a second {@code Sequence} row per company with the same {@code codeSelect}.
 *
 * <p><b>What went wrong.</b> BG_COA's {@code base_sequence.csv} shipped two rows with {@code
 * codeSelect="reconcile"} (importId 65 "Account reconcile" and importId 101 "Прихващане на
 * сметки"), both with the same prefix/suffix. {@code chart-config.xml} imports this file per
 * company ({@code search="self.importId = :importId AND self.company.id = :_companyId"}), so every
 * company on BG_COA ended up with two {@code reconcile} sequences. {@code
 * SequenceRepository.find(codeSelect, company)} does {@code .fetchOne()} with no {@code ORDER BY},
 * so {@code ReconcileSequenceService} picked whichever row Postgres returned first —
 * nondeterministic, and silent, because the generic lookup never checks for more than one match.
 *
 * <p>GBR_COA, FRA_PCG and the generic en/fr {@code init-data} charts each carry exactly one {@code
 * reconcile} row; this test locks BG_COA to the same invariant.
 *
 * <p>Needs no database: the seed file is read straight off the classpath.
 */
class ChartSequenceReconcileUniquenessTest {

  private static final String[] SEQUENCE_CSVS = {
    "/l10n/l10n_bgr/BG_COA/base_sequence.csv",
    "/l10n/l10n_gbr/GBR_COA/base_sequence.csv",
    "/l10n/l10n_fra/FRA_PCG/base_sequence.csv",
    "/apps/init-data/en/base_sequence.csv",
    "/apps/init-data/fr/base_sequence.csv",
  };

  /**
   * Every sequence code the reconciliation chain resolves by {@code (codeSelect, company)}:
   * ReconcileSequenceService for {@code reconcile}, ReconcileGroupSequenceServiceImpl for the two
   * lettering codes.
   */
  private static final String[] RECONCILE_CODES = {
    "reconcile", "reconcileGroupDraft", "reconcileGroupFinal",
  };

  @Test
  @DisplayName("each chart seeds at most one sequence per reconciliation codeSelect")
  void atMostOneReconcileSequencePerChart() throws Exception {
    for (String resource : SEQUENCE_CSVS) {
      for (String code : RECONCILE_CODES) {
        List<String> names = names(resource, code);
        assertTrue(
            names.size() <= 1,
            resource
                + " seeds "
                + names.size()
                + " rows with codeSelect='"
                + code
                + "' (expected at most 1): "
                + names
                + " — SequenceRepository.find(codeSelect, company) picks one nondeterministically"
                + " when more than one exists per company.");
      }
    }
  }

  /**
   * The same rule for fixed assets: with two 'fixedAsset' sequences per company (e.g. one with and
   * one without a yearly reset, both prefixed {@code IMMO%YYYY}), {@code
   * FixedAssetGenerationServiceImpl.generateSequence} picks one at random, and the inventory number
   * is the SAF-T AssetID (MF.A.2), unique and never reused per company. BG_COA keeps importId 104
   * "№ на дълготраен актив"; existing companies lose the second one through {@code
   * db/migration/2026-10-23-bg-coa-duplicate-fixed-asset-sequence.sql}.
   */
  @Test
  @DisplayName("each chart's base_sequence.csv seeds at most one 'fixedAsset' sequence")
  void atMostOneFixedAssetSequencePerChart() throws Exception {
    for (String resource : SEQUENCE_CSVS) {
      List<String> names = names(resource, "fixedAsset");
      assertTrue(
          names.size() <= 1,
          resource
              + " seeds "
              + names.size()
              + " rows with codeSelect='fixedAsset' (expected at most 1): "
              + names
              + " — FixedAssetGenerationServiceImpl.generateSequence(fixedAsset) picks one"
              + " nondeterministically when more than one exists per company.");
    }
  }

  /**
   * Draft and final lettering must not share an alphabet.
   *
   * <p><b>What went wrong.</b> BG_COA seeded {@code reconcileGroupFinal} as LOWERCASE and {@code
   * reconcileGroupDraft} as UPPERCASE — inverted from FRA_PCG and the generic charts — on top of
   * the canonical rows, so the surviving draft and final sequences both generated lowercase
   * letters. Each code counts from its own {@code nextNum}, so the second code hands out {@code
   * aaaa} again; {@code SequenceService.isSequenceAlreadyExisting} then refuses it against the
   * ReconcileGroup the first code already created, and refuses it BEFORE incrementing {@code
   * nextNum} — so every later attempt fails on the same value forever.
   */
  @Test
  @DisplayName("draft and final lettering use different letter cases in every chart")
  void draftAndFinalLetteringDoNotShareAnAlphabet() throws Exception {
    for (String resource : SEQUENCE_CSVS) {
      List<String> draft = cases(resource, "reconcileGroupDraft");
      List<String> last = cases(resource, "reconcileGroupFinal");
      if (draft.isEmpty() || last.isEmpty()) {
        continue; // chart does not seed lettering at all
      }
      assertFalse(
          draft.get(0).equals(last.get(0)),
          resource
              + " seeds reconcileGroupDraft and reconcileGroupFinal both as "
              + draft.get(0)
              + " — the two codes then generate the same letters from separate counters and"
              + " collide on the first final lettering after any draft lettering.");
    }
  }

  private static List<String> names(String resource, String code) throws IOException {
    return column(resource, code, "name");
  }

  private static List<String> cases(String resource, String code) throws IOException {
    return column(resource, code, "sequenceLettersTypeSelect");
  }

  private static List<String> column(String resource, String code, String column)
      throws IOException {
    List<String> headers = split(header(resource));
    int codeSelectIndex = headers.indexOf("codeSelect");
    int valueIndex = headers.indexOf(column);
    assertTrue(codeSelectIndex >= 0, resource + ": no codeSelect column");
    List<String> out = new ArrayList<>();
    for (List<String> cells : rows(resource)) {
      if (codeSelectIndex < cells.size() && code.equals(cells.get(codeSelectIndex))) {
        out.add(valueIndex >= 0 && valueIndex < cells.size() ? cells.get(valueIndex) : "?");
      }
    }
    return out;
  }

  private static String header(String resource) throws IOException {
    return lines(resource).get(0);
  }

  private static List<List<String>> rows(String resource) throws IOException {
    List<String> lines = lines(resource);
    List<List<String>> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (!lines.get(i).trim().isEmpty()) {
        out.add(split(lines.get(i)));
      }
    }
    return out;
  }

  private static List<String> lines(String resource) throws IOException {
    try (InputStream in =
        ChartSequenceReconcileUniquenessTest.class.getResourceAsStream(resource)) {
      assertFalse(in == null, "missing resource " + resource);
      List<String> lines = new ArrayList<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          lines.add(line);
        }
      }
      return lines;
    }
  }

  /** Splits a semicolon-separated row, respecting quotes (names contain commas/dashes). */
  private static List<String> split(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ';' && !quoted) {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
