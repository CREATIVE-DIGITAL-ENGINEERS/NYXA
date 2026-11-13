package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiToolRegistry;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool registry as a whole: that every tool carries the metadata the architecture now reads,
 * and that the metadata says what the classes say.
 *
 * <p>This is the registry-backed half of {@code AiTaskCapabilityTest}, which states the same
 * classifications without Guice. Two independent statements of one fact is the point: if a tool is
 * reclassified in only one of them, one of these tests fails rather than both quietly agreeing on
 * the new answer.
 *
 * <p>What it can see is this module's own classpath: OCR, supplier-invoice, partner and
 * chart-of-accounts tools. The move and bank-statement tools live in sibling feature modules and
 * are asserted by {@code AiTaskCapabilityTest} alone.
 */
class BgOcrToolCapabilityTest extends AiOcrTest {

  private static Map<String, AiTool> registry() {
    return Beans.get(AiToolRegistry.class).all();
  }

  /** Grouping covers everything exactly once, empty groups included. */
  @Test
  void groupingPartitionsTheRegistry() {
    Map<AiCapability, List<AiTool>> grouped = Beans.get(AiToolRegistry.class).byCapability();
    assertEquals(AiCapability.values().length, grouped.size(), "every group is represented");

    int total = 0;
    for (List<AiTool> tools : grouped.values()) {
      total += tools.size();
    }
    assertEquals(registry().size(), total, "every tool lands in exactly one group");
  }

  /**
   * Published is what the model can see; registered is what Java can dispatch. AI-5 kept {@code
   * apply_exact_product_code} internal: nothing reaches it, and publishing it would charge every
   * request for a choice the model has never needed.
   */
  @Test
  void exactlyOneToolIsInternalAndItIsTheProductCodeOne() {
    List<String> internal = new ArrayList<>();
    for (Map.Entry<String, AiTool> e : registry().entrySet()) {
      if (!e.getValue().published()) {
        internal.add(e.getKey());
      }
    }
    assertEquals(List.of("apply_exact_product_code"), internal);
  }

  /**
   * An internal tool is not a hidden tool: Java can still dispatch it, and its guards still apply.
   */
  @Test
  void theInternalToolIsStillRegisteredAndStillGuarded() {
    AiTool tool = Beans.get(AiToolRegistry.class).find("apply_exact_product_code");
    assertNotNull(tool, "unpublished must not mean unregistered");
    assertEquals(AiToolKind.WRITE, tool.kind());
    assertTrue(
        tool.writeModes().contains(AiWriteMode.UNATTENDED),
        "the reason it is kept: an unattended repair pass is what would use it");
  }

  /**
   * Write ordering is real dependency data and must stay consistent with the accounting sequence.
   */
  @Test
  void writeOrderFollowsTheAccountingSequence() {
    Map<String, AiTool> all = registry();
    int partner = all.get("manage_supplier").writeOrder();
    int account = all.get("fix_partner_account").writeOrder();
    int edit = all.get("edit_supplier_invoice").writeOrder();
    int validate = all.get("validate_supplier_invoice").writeOrder();
    int refresh = all.get("refresh_supplier_invoice_terms").writeOrder();
    int ventilate = all.get("ventilate_supplier_invoice").writeOrder();

    assertTrue(account < partner, "an account has to be usable before anything points at it");
    assertTrue(partner < edit, "a supplier has to exist before an invoice references it");
    assertTrue(edit < validate, "corrections land before validation recomputes from them");
    assertTrue(validate < refresh, "terms are regenerated on a validated invoice");
    assertTrue(refresh < ventilate, "and posting is always last");
  }

  /** READ tools must never be reachable from the unattended pipeline's write path. */
  @Test
  void readToolsCarryNoWriteModes() {
    for (Map.Entry<String, AiTool> e : registry().entrySet()) {
      if (e.getValue().kind() == AiToolKind.READ) {
        assertFalse(
            e.getValue().writeModes().contains(AiWriteMode.UNATTENDED),
            e.getKey() + " is a READ tool and must not opt into unattended writes");
      }
    }
  }
}
