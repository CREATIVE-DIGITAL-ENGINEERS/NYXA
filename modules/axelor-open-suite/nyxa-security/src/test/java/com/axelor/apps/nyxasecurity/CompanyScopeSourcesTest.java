package com.axelor.apps.nyxasecurity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * One active company, also where Axelor's own views and services offer companies (the role
 * catalogue §7, 2026-10-21). These are client-side domains and Groovy expressions in other modules,
 * which no permission covers, so the sources themselves are checked.
 */
class CompanyScopeSourcesTest {

  private static final String SUITE = "modules/axelor-open-suite/";

  /** The company pickers that offered every assigned company now offer only the active one. */
  @Test
  void companyPickersOfferOnlyTheActiveCompany() throws IOException {
    String[][] pickers = {
      {
        "axelor-account/src/main/resources/views/MoveLineQuery.xml",
        "action-move-line-query-attrs-set-company-domain"
      },
      {
        "axelor-account/src/main/resources/views/AnalyticMoveLineQuery.xml",
        "action-analytic-move-line-query-attrs-set-company-domain"
      },
      {
        "axelor-account/src/main/resources/views/PaymentSession.xml",
        "action-attrs-payment-session-set-company-domain"
      },
      {"axelor-sale/src/main/resources/views/Cart.xml", "action-cart-attrs-set-company-domain"},
    };
    List<Executable> checks = new ArrayList<>();
    for (String[] picker : pickers) {
      String action = element(read(picker[0]), "action-attrs", picker[1]);
      checks.add(
          () ->
              assertTrue(
                  action.contains("'self.id = ' + (__user__?.activeCompany?.id ?: 0)")
                      && !action.contains("companySet"),
                  picker[1] + " must offer only the active company:\n" + action));
    }
    assertAll(checks);
  }

  /** The one switch: no company is ever found as the interco counterpart. */
  @Test
  void intercoFindsNoCompany() throws IOException {
    String source =
        read(
            "axelor-supplychain/src/main/java/com/axelor/apps/supplychain/service/"
                + "IntercoServiceImpl.java");
    Matcher m =
        Pattern.compile("public Company findIntercoCompany\\(Partner partner\\) \\{\\s*(.*?)\\s*}")
            .matcher(source);
    assertTrue(m.find(), "findIntercoCompany not found in IntercoServiceImpl");
    assertEquals("return null;", m.group(1));
  }

  /** Every path that decides "interco" asks that switch instead of looking a company up itself. */
  @Test
  void intercoPathsDoNotLookUpACompanyByItsPartner() throws IOException {
    String[] java = {
      "axelor-supplychain/src/main/java/com/axelor/apps/supplychain/service/IntercoServiceImpl.java",
      "axelor-supplychain/src/main/java/com/axelor/apps/supplychain/service/saleorder/"
          + "SaleOrderIntercoServiceImpl.java",
      "axelor-supplychain/src/main/java/com/axelor/apps/supplychain/service/saleorder/"
          + "SaleOrderPurchaseServiceImpl.java",
      "axelor-production/src/main/java/com/axelor/apps/production/service/manuforder/"
          + "ManufOrderCreatePurchaseOrderServiceImpl.java",
    };
    List<Executable> checks = new ArrayList<>();
    for (String path : java) {
      String source = read(path);
      checks.add(
          () ->
              assertFalse(
                  source.contains("self.partner"), path + " looks a company up by partner"));
    }
    for (String path :
        new String[] {
          "axelor-supplychain/src/main/resources/views/SaleOrder.xml",
          "axelor-supplychain/src/main/resources/views/PurchaseOrder.xml",
          "axelor-account/src/main/resources/views/Invoice.xml",
        }) {
      String view = read(path);
      checks.add(
          () ->
              assertFalse(
                  view.contains("__repo__(Company).all().filter('self.partner"),
                  path + " looks a company up by partner"));
    }
    assertAll(checks);
  }

  /** The interco flag is never set and never shown; the app settings for it are not offered. */
  @Test
  void intercoIsNeverSetNorShown() throws IOException {
    String[][] records = {
      {
        "axelor-supplychain/src/main/resources/views/SaleOrder.xml",
        "action-sale-order-record-interco"
      },
      {
        "axelor-supplychain/src/main/resources/views/PurchaseOrder.xml",
        "action-purchase-order-record-interco"
      },
      {
        "axelor-account/src/main/resources/views/Invoice.xml",
        "action-invoice-record-interco-supplychain"
      },
    };
    List<Executable> checks = new ArrayList<>();
    for (String[] record : records) {
      String action = element(read(record[0]), "action-record", record[1]);
      checks.add(
          () ->
              assertTrue(
                  action.contains("<field name=\"interco\" expr=\"eval: false\"/>"),
                  record[1] + " must never set interco:\n" + action));
    }
    String[][] attrs = {
      {
        "axelor-supplychain/src/main/resources/views/SaleOrder.xml",
        "action-sale-order-attrs-interco",
        "interco"
      },
      {
        "axelor-supplychain/src/main/resources/views/PurchaseOrder.xml",
        "action-purchase-order-attrs-interco",
        "interco"
      },
      {
        "axelor-account/src/main/resources/views/Invoice.xml",
        "action-invoice-attrs-interco-supplychain",
        "intercoPanel"
      },
    };
    for (String[] attr : attrs) {
      String action = element(read(attr[0]), "action-attrs", attr[1]);
      checks.add(
          () ->
              assertTrue(
                  action.contains(
                      "<attribute for=\"" + attr[2] + "\" name=\"hidden\" expr=\"eval: true\"/>"),
                  attr[1] + " must always hide " + attr[2] + ":\n" + action));
    }
    String app = read("axelor-supplychain/src/main/resources/views/AppSupplychain.xml");
    checks.add(
        () ->
            assertTrue(
                app.contains("<panel name=\"intercoPanel\" title=\"Interco\" hidden=\"true\">"),
                "the interco app settings must be hidden"));
    assertAll(checks);
  }

  private static String element(String xml, String tag, String name) {
    Matcher m =
        Pattern.compile(
                "<" + tag + " name=\"" + Pattern.quote(name) + "\".*?</" + tag + ">",
                Pattern.DOTALL)
            .matcher(xml);
    assertTrue(m.find(), tag + " " + name + " not found");
    return m.group();
  }

  private static String read(String suitePath) throws IOException {
    Path root = repositoryRoot();
    assertNotNull(root, "repository root (settings.gradle + modules/) not found");
    return new String(Files.readAllBytes(root.resolve(SUITE + suitePath)), StandardCharsets.UTF_8);
  }

  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("settings.gradle"))
          && Files.isDirectory(p.resolve("modules"))) {
        return p;
      }
    }
    return null;
  }
}
