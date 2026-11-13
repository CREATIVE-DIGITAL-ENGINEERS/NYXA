package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns ONE Microinvest settlement into the exact MoveLine pairs Axelor has to reconcile.
 *
 * <p><b>The grain mismatch.</b> Microinvest settles a payment ENTRY against a settled ENTRY on one
 * account for one amount. It names neither MoveLine nor AccountingDetail, and one entry
 * legitimately carries several lines on the same partner account — a supplier invoice books net and
 * VAT as two separate 401 credits. Axelor reconciles ONE debit MoveLine against ONE credit
 * MoveLine, so a single source settlement is in general 1→N or N→N, not 1→1.
 *
 * <p><b>What this class will and will not do.</b> It allocates only when the endpoints, the
 * account, the partner and the exact remaining balances leave no choice. It never picks the first
 * pair, never matches by ordering or by document order, never invents a source detail id, and never
 * settles more than the source amount or more than a line's remaining balance. Anything else is
 * refused with the candidate ids and the amounts, for an accountant to decide.
 *
 * <p>Database-free on purpose: the caller resolves the endpoints, this decides the allocation.
 */
final class MicroinvestSettlementAllocation {

  /** Exactly one debit and one credit line: the source amount needs no allocation at all. */
  static final String RULE_SINGLE_PAIR = "една двойка";

  /**
   * One side is a single line whose remaining balance equals the settlement amount, and the other
   * side's remaining balances sum to the very same amount. Every line on both sides is therefore
   * fully settled and each pair's amount is the other side's own remaining balance — the single
   * line has to be paired with every one of them, so the allocation is forced, not chosen.
   */
  static final String RULE_ONE_TO_MANY = "един срещу няколко";

  /**
   * Both sides hold several lines, their remaining balances sum to the settlement amount on each
   * side, and the two sorted lists of balances are IDENTICAL. Every line is fully settled in any
   * valid allocation, and pairing equal balance with equal balance is the only allocation that does
   * not split a MoveLine across two reconciliations. Where two balances are equal it does not
   * matter which one is taken: the amounts are the same.
   */
  static final String RULE_EXACT_MATCH = "съвпадащи остатъци";

  private MicroinvestSettlementAllocation() {}

  /** One endpoint MoveLine, with what is still unsettled on its own side. */
  static final class Endpoint {
    final long moveLineId;
    final Long partnerId;
    final BigDecimal residual;

    Endpoint(long moveLineId, Long partnerId, BigDecimal residual) {
      this.moveLineId = moveLineId;
      this.partnerId = partnerId;
      this.residual = residual == null ? BigDecimal.ZERO : residual;
    }
  }

  /** One reconciliation to create: a debit line, a credit line and the amount between them. */
  static final class Allocation {
    final long debitMoveLineId;
    final long creditMoveLineId;
    final BigDecimal amount;

    Allocation(long debitMoveLineId, long creditMoveLineId, BigDecimal amount) {
      this.debitMoveLineId = debitMoveLineId;
      this.creditMoveLineId = creditMoveLineId;
      this.amount = amount;
    }

    @Override
    public String toString() {
      return "Dt " + debitMoveLineId + " / Ct " + creditMoveLineId + " = " + amount;
    }
  }

  /** Either an allocation under a named rule, or a refusal that names the candidates. */
  static final class Plan {
    final String rule;
    final List<Allocation> allocations;
    final String refusal;

    private Plan(String rule, List<Allocation> allocations, String refusal) {
      this.rule = rule;
      this.allocations = allocations;
      this.refusal = refusal;
    }

    boolean isPlanned() {
      return refusal == null;
    }
  }

  private static Plan planned(String rule, List<Allocation> allocations) {
    return new Plan(rule, allocations, null);
  }

  private static Plan refused(String refusal) {
    return new Plan(null, List.of(), refusal);
  }

  static Plan plan(List<Endpoint> debits, List<Endpoint> credits, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      return refused("source сумата на равнението е " + amount + " — няма какво да се равнява.");
    }
    if (debits.isEmpty() || credits.isEmpty()) {
      return refused(
          "по сметката няма неравнени "
              + (debits.isEmpty() ? "дебитни" : "кредитни")
              + " редове и от двете source проводки ("
              + describe(debits, "Dt")
              + "; "
              + describe(credits, "Ct")
              + ").");
    }

    if (debits.size() == 1 && credits.size() == 1) {
      Endpoint debit = debits.get(0);
      Endpoint credit = credits.get(0);
      if (debit.residual.compareTo(amount) < 0 || credit.residual.compareTo(amount) < 0) {
        return refused(
            "source сумата "
                + amount
                + " не се побира в остатъците debit="
                + debit.residual
                + ", credit="
                + credit.residual
                + ".");
      }
      return planned(
          RULE_SINGLE_PAIR, List.of(new Allocation(debit.moveLineId, credit.moveLineId, amount)));
    }

    // Beyond one pair the allocation has to be PROVEN, and the partner is part of the proof: two
    // partners on one account means the source amount does not say whose balance it settles.
    Set<String> partners = new LinkedHashSet<>();
    for (Endpoint endpoint : all(debits, credits)) {
      partners.add(String.valueOf(endpoint.partnerId));
    }
    if (partners.size() != 1) {
      return refused(
          "крайните точки са на "
              + partners.size()
              + " различни партньора ("
              + String.join(", ", partners)
              + ") — source редът не казва чие салдо се закрива.");
    }

    BigDecimal debitSum = sum(debits);
    BigDecimal creditSum = sum(credits);
    if (debitSum.compareTo(amount) != 0 || creditSum.compareTo(amount) != 0) {
      return refused(
          "source сумата "
              + amount
              + " не закрива точно страните (неравнено Dt="
              + debitSum
              + ", Ct="
              + creditSum
              + "): "
              + describe(debits, "Dt")
              + "; "
              + describe(credits, "Ct")
              + " — частично равнение между няколко реда не е еднозначно от source файла.");
    }

    if (debits.size() == 1 || credits.size() == 1) {
      List<Endpoint> many = debits.size() == 1 ? credits : debits;
      Endpoint one = debits.size() == 1 ? debits.get(0) : credits.get(0);
      List<Allocation> allocations = new ArrayList<>();
      for (Endpoint endpoint : sorted(many)) {
        allocations.add(
            debits.size() == 1
                ? new Allocation(one.moveLineId, endpoint.moveLineId, endpoint.residual)
                : new Allocation(endpoint.moveLineId, one.moveLineId, endpoint.residual));
      }
      return planned(RULE_ONE_TO_MANY, allocations);
    }

    List<Endpoint> sortedDebits = sorted(debits);
    List<Endpoint> sortedCredits = sorted(credits);
    if (sortedDebits.size() != sortedCredits.size()) {
      return refused(
          "двете страни имат различен брой неравнени редове ("
              + describe(debits, "Dt")
              + "; "
              + describe(credits, "Ct")
              + ") — разпределението не е определено от source файла.");
    }
    for (int i = 0; i < sortedDebits.size(); i++) {
      if (sortedDebits.get(i).residual.compareTo(sortedCredits.get(i).residual) != 0) {
        return refused(
            "остатъците по двете страни не съвпадат ред по ред ("
                + describe(debits, "Dt")
                + "; "
                + describe(credits, "Ct")
                + ") — разпределението не е определено от source файла.");
      }
    }
    List<Allocation> allocations = new ArrayList<>();
    for (int i = 0; i < sortedDebits.size(); i++) {
      allocations.add(
          new Allocation(
              sortedDebits.get(i).moveLineId,
              sortedCredits.get(i).moveLineId,
              sortedDebits.get(i).residual));
    }
    return planned(RULE_EXACT_MATCH, allocations);
  }

  private static List<Endpoint> all(List<Endpoint> debits, List<Endpoint> credits) {
    List<Endpoint> out = new ArrayList<>(debits);
    out.addAll(credits);
    return out;
  }

  private static BigDecimal sum(List<Endpoint> endpoints) {
    BigDecimal total = BigDecimal.ZERO;
    for (Endpoint endpoint : endpoints) {
      total = total.add(endpoint.residual);
    }
    return total;
  }

  /** Stable order: by remaining balance, then by id, so a replay plans exactly the same pairs. */
  private static List<Endpoint> sorted(List<Endpoint> endpoints) {
    List<Endpoint> out = new ArrayList<>(endpoints);
    out.sort(Comparator.comparing((Endpoint e) -> e.residual).thenComparingLong(e -> e.moveLineId));
    return out;
  }

  /** Candidate ids and amounts, so an unresolved row tells the accountant what to look at. */
  static String describe(List<Endpoint> endpoints, String side) {
    if (endpoints.isEmpty()) {
      return side + ": -";
    }
    StringBuilder sb = new StringBuilder(side).append(": ");
    boolean first = true;
    for (Endpoint endpoint : sorted(endpoints)) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append("MoveLine ").append(endpoint.moveLineId).append(" = ").append(endpoint.residual);
    }
    return sb.toString();
  }
}
