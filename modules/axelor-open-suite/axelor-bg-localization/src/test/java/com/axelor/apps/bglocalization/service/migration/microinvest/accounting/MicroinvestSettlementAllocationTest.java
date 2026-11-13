package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestSettlementAllocation.Allocation;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestSettlementAllocation.Endpoint;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestSettlementAllocation.Plan;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Database-free rules for turning one Microinvest settlement into Axelor reconciliations.
 *
 * <p>The numbers are the real FY2016 shapes: {@code setl:1} (supplier invoice booked as net 64.95 +
 * VAT 12.99 on 401, paid by one 77.94 debit) and {@code setl:22} (two 499 lines per side, 6076.76 +
 * 1215.35 on each).
 */
class MicroinvestSettlementAllocationTest {

  private static final Long PARTNER = 115L;

  private static Endpoint line(long id, String residual) {
    return new Endpoint(id, PARTNER, new BigDecimal(residual));
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  @Test
  @DisplayName("one debit against one credit needs no allocation")
  void singlePair() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1, "200.00")), List.of(line(2, "200.00")), bd("200.00"));
    assertTrue(plan.isPlanned(), plan.refusal);
    assertEquals(MicroinvestSettlementAllocation.RULE_SINGLE_PAIR, plan.rule);
    assertEquals(1, plan.allocations.size());
    assertEquals(1L, plan.allocations.get(0).debitMoveLineId);
    assertEquals(2L, plan.allocations.get(0).creditMoveLineId);
    assertEquals(0, bd("200.00").compareTo(plan.allocations.get(0).amount));
  }

  @Test
  @DisplayName("a partial single pair is still allowed and settles only the source amount")
  void singlePairPartial() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1, "500.00")), List.of(line(2, "300.00")), bd("120.00"));
    assertTrue(plan.isPlanned(), plan.refusal);
    assertEquals(0, bd("120.00").compareTo(plan.allocations.get(0).amount));
  }

  @Test
  @DisplayName("a single pair that cannot hold the source amount is refused")
  void singlePairTooSmall() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1, "50.00")), List.of(line(2, "300.00")), bd("120.00"));
    assertFalse(plan.isPlanned());
    assertTrue(plan.refusal.contains("не се побира"), plan.refusal);
  }

  @Test
  @DisplayName("setl:1 — one payment debit against the invoice's net and VAT lines")
  void oneToManyNetAndVat() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1080, "77.940")),
            List.of(line(1076, "64.950"), line(1078, "12.990")),
            bd("77.94"));
    assertTrue(plan.isPlanned(), plan.refusal);
    assertEquals(MicroinvestSettlementAllocation.RULE_ONE_TO_MANY, plan.rule);
    assertEquals(2, plan.allocations.size());
    // Smallest balance first, so a replay plans exactly the same pairs.
    assertEquals(1078L, plan.allocations.get(0).creditMoveLineId);
    assertEquals(0, bd("12.990").compareTo(plan.allocations.get(0).amount));
    assertEquals(1076L, plan.allocations.get(1).creditMoveLineId);
    assertEquals(0, bd("64.950").compareTo(plan.allocations.get(1).amount));
    assertEquals(0, bd("77.94").compareTo(total(plan.allocations)));
    for (Allocation allocation : plan.allocations) {
      assertEquals(1080L, allocation.debitMoveLineId);
    }
  }

  @Test
  @DisplayName("the many side may be the debit side just as well")
  void manyToOne() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(10, "64.950"), line(11, "12.990")),
            List.of(line(20, "77.940")),
            bd("77.94"));
    assertTrue(plan.isPlanned(), plan.refusal);
    assertEquals(MicroinvestSettlementAllocation.RULE_ONE_TO_MANY, plan.rule);
    for (Allocation allocation : plan.allocations) {
      assertEquals(20L, allocation.creditMoveLineId);
    }
    assertEquals(0, bd("77.94").compareTo(total(plan.allocations)));
  }

  @Test
  @DisplayName("setl:22 — two lines per side with identical balances pair value with value")
  void exactPairwiseMatch() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1182, "6076.760"), line(1184, "1215.350")),
            List.of(line(1178, "6076.760"), line(1180, "1215.350")),
            bd("7292.11"));
    assertTrue(plan.isPlanned(), plan.refusal);
    assertEquals(MicroinvestSettlementAllocation.RULE_EXACT_MATCH, plan.rule);
    assertEquals(2, plan.allocations.size());
    assertEquals(1184L, plan.allocations.get(0).debitMoveLineId);
    assertEquals(1180L, plan.allocations.get(0).creditMoveLineId);
    assertEquals(1182L, plan.allocations.get(1).debitMoveLineId);
    assertEquals(1178L, plan.allocations.get(1).creditMoveLineId);
    assertEquals(0, bd("7292.11").compareTo(total(plan.allocations)));
  }

  @Test
  @DisplayName("a partial amount across several lines is left for an accountant")
  void partialAcrossSeveralLinesIsRefused() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1, "77.940")), List.of(line(2, "64.950"), line(3, "12.990")), bd("50.00"));
    assertFalse(plan.isPlanned());
    assertTrue(plan.refusal.contains("не закрива точно"), plan.refusal);
    assertTrue(plan.refusal.contains("MoveLine 2"), plan.refusal);
    assertTrue(plan.refusal.contains("MoveLine 3"), plan.refusal);
  }

  @Test
  @DisplayName("two partners on one account: the source amount does not say whose balance it is")
  void twoPartnersAreRefused() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(new Endpoint(1, 115L, bd("77.940"))),
            List.of(new Endpoint(2, 115L, bd("64.950")), new Endpoint(3, 130L, bd("12.990"))),
            bd("77.94"));
    assertFalse(plan.isPlanned());
    assertTrue(plan.refusal.contains("партньора"), plan.refusal);
  }

  @Test
  @DisplayName("balances that do not match one-to-one are refused, never split by guesswork")
  void unmatchedBalancesAreRefused() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(
            List.of(line(1, "60.00"), line(2, "40.00")),
            List.of(line(3, "70.00"), line(4, "30.00")),
            bd("100.00"));
    assertFalse(plan.isPlanned());
    assertTrue(plan.refusal.contains("ред по ред"), plan.refusal);
    assertTrue(plan.refusal.contains("MoveLine 1"), plan.refusal);
  }

  @Test
  @DisplayName("an endpoint with nothing open on one side is refused with both candidate lists")
  void emptySideIsRefused() {
    Plan plan =
        MicroinvestSettlementAllocation.plan(List.of(line(1, "10.00")), List.of(), bd("10.00"));
    assertFalse(plan.isPlanned());
    assertTrue(plan.refusal.contains("кредитни"), plan.refusal);
  }

  @Test
  @DisplayName("replaying the same endpoints plans exactly the same pairs")
  void replayIsStable() {
    List<Endpoint> debits = List.of(line(1184, "1215.350"), line(1182, "6076.760"));
    List<Endpoint> credits = List.of(line(1180, "1215.350"), line(1178, "6076.760"));
    Plan first = MicroinvestSettlementAllocation.plan(debits, credits, bd("7292.11"));
    Plan second = MicroinvestSettlementAllocation.plan(debits, credits, bd("7292.11"));
    assertEquals(first.allocations.toString(), second.allocations.toString());
  }

  private static BigDecimal total(List<Allocation> allocations) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Allocation allocation : allocations) {
      sum = sum.add(allocation.amount);
    }
    return sum;
  }
}
