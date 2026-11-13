/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.ManyToMany;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Guards the invariant that was silently violated by {@code chart-config-account.xml}: a CSV column
 * that targets a many-to-many field on {@link Account} MUST be bound with a {@code search}.
 *
 * <p>{@code CSVBinder} only resolves an m2m against existing rows when the bind carries both a
 * {@code column} and a {@code search}. Without the {@code search} it falls through to its
 * nested-bean branch and constructs an <em>empty</em> target instance for every CSV line, which the
 * flush then rejects (Tax.code / Tax.name / Tax.taxType are all required). The chart-only import
 * config was missing those binds, so every account row was rejected while the caller still reported
 * success.
 *
 * <p>Auto-bind synthesises a {@code search} on its own for <em>dotted</em> columns ({@code
 * accountType.importId}), which is why only the plain m2m columns are affected.
 */
public class ChartConfigBindParityTest {

  private static final String[] CONFIGS = {
    "/l10n/chart-config.xml", "/l10n/chart-config-account.xml"
  };

  private static final String ACCOUNT_CSV = "account_account.csv";

  private static final String[] CHART_DIRS = {
    "/l10n/l10n_bgr/BG_COA", "/l10n/l10n_fra/FRA_PCG", "/l10n/l10n_gbr/GBR_COA"
  };

  /** Many-to-many field names on Account, read from the generated entity itself. */
  private static Set<String> manyToManyFields() {
    Set<String> names = new LinkedHashSet<>();
    for (Field f : Account.class.getDeclaredFields()) {
      if (f.getAnnotation(ManyToMany.class) != null) {
        names.add(f.getName());
      }
    }
    assertFalse(names.isEmpty(), "no @ManyToMany fields found on Account");
    return names;
  }

  private static Document parse(String resource) throws Exception {
    try (InputStream in = ChartConfigBindParityTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "missing resource " + resource);
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(false);
      return dbf.newDocumentBuilder().parse(in);
    }
  }

  private static List<String> csvHeader(String dir) throws Exception {
    String resource = dir + "/" + ACCOUNT_CSV;
    try (InputStream in = ChartConfigBindParityTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "missing resource " + resource);
      String first =
          new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R", 2)[0].trim();
      List<String> cols = new ArrayList<>();
      for (String c : first.split(";")) {
        cols.add(c.replace("\"", "").trim());
      }
      return cols;
    }
  }

  /** All {@code <input file="account_account.csv">} elements of a config. */
  private static List<Element> accountInputs(Document doc) {
    List<Element> inputs = new ArrayList<>();
    NodeList all = doc.getElementsByTagName("input");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if (ACCOUNT_CSV.equals(e.getAttribute("file"))) {
        inputs.add(e);
      }
    }
    return inputs;
  }

  private static List<Element> binds(Element input) {
    List<Element> out = new ArrayList<>();
    NodeList children = input.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element) {
        Element e = (Element) children.item(i);
        if ("bind".equals(e.getTagName())) {
          out.add(e);
        }
      }
    }
    return out;
  }

  /**
   * For every account input in every chart config, each CSV column that lands on an m2m field must
   * be covered by a bind that carries a {@code search}.
   */
  @Test
  public void everyManyToManyColumnIsBoundWithSearch() throws Exception {
    Set<String> m2m = manyToManyFields();
    List<String> violations = new ArrayList<>();

    for (String config : CONFIGS) {
      Document doc = parse(config);
      List<Element> inputs = accountInputs(doc);
      assertFalse(inputs.isEmpty(), config + " declares no " + ACCOUNT_CSV + " input");

      for (Element input : inputs) {
        List<Element> binds = binds(input);

        for (String dir : CHART_DIRS) {
          for (String column : csvHeader(dir)) {
            if (column.contains(".")) {
              // auto-bind synthesises a search for dotted columns
              continue;
            }

            // Which field does this column target? Either same-named, or remapped by `to=`.
            String target = column;
            Element covering = null;
            for (Element b : binds) {
              if (column.equals(b.getAttribute("column"))) {
                covering = b;
                target = b.getAttribute("to").isEmpty() ? column : b.getAttribute("to");
                break;
              }
              if (b.getAttribute("column").isEmpty() && column.equals(b.getAttribute("to"))) {
                covering = b;
                break;
              }
            }

            if (!m2m.contains(target)) {
              continue;
            }

            String where = config + " :: " + ACCOUNT_CSV + " column '" + column + "'";
            if (covering == null) {
              violations.add(where + " targets m2m '" + target + "' but has no <bind>");
            } else if (covering.getAttribute("search").isEmpty()) {
              violations.add(where + " targets m2m '" + target + "' but its <bind> has no search");
            }
          }
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "m2m columns bound without a search (CSVBinder would build empty nested beans):\n  "
            + String.join("\n  ", new LinkedHashSet<>(violations)));
  }

  /**
   * The chart-only config must not drift from the full one: every m2m bind of the full config's
   * account input has to be present in the chart-only config too.
   */
  @Test
  public void chartOnlyConfigCarriesTheSameManyToManyBinds() throws Exception {
    Set<String> m2m = manyToManyFields();

    Set<String> full = manyToManyBindSignatures("/l10n/chart-config.xml", m2m);
    Set<String> only = manyToManyBindSignatures("/l10n/chart-config-account.xml", m2m);

    assertFalse(full.isEmpty(), "chart-config.xml declares no m2m binds — test is vacuous");

    Set<String> missing = new LinkedHashSet<>(full);
    missing.removeAll(only);

    assertTrue(
        missing.isEmpty(),
        "chart-config-account.xml is missing m2m binds present in chart-config.xml:\n  "
            + String.join("\n  ", missing));
  }

  private static Set<String> manyToManyBindSignatures(String config, Set<String> m2m)
      throws Exception {
    Set<String> sigs = new LinkedHashSet<>();
    for (Element input : accountInputs(parse(config))) {
      for (Element b : binds(input)) {
        String to = b.getAttribute("to");
        if (m2m.contains(to)) {
          sigs.add(b.getAttribute("column") + "->" + to + " search=" + b.getAttribute("search"));
        }
      }
    }
    return sigs;
  }
}
