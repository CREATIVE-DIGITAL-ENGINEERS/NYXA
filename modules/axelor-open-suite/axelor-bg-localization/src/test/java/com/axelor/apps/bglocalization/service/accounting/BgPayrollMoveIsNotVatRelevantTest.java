package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.bglocalization.service.vat.BgVatDocumentCollector;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕЙТЪТ КЪМ ДДС ПОТОКА: заплатната статия НЕ бива да попада в дневниците по ЗДДС.
 *
 * <p><b>⚠️ ЗАЩО Е СВОЙ ТЕСТ, А НЕ ОЧЕВИДНОСТ.</b> {@code BgVatDocumentCollector} чете ВСИЧКИ
 * осчетоводени статии на дружеството за периода — филтърът му е по фирма и дата, не по журнал.
 * Тоест новите заплатни статии влизат в неговата извадка от първия ден и единственото, което ги
 * държи вън от дневниците, е {@code isVatRelevant}: няма фактура и няма ред с данък. Това е
 * СВОЙСТВО НА СМЕТКИТЕ (целият набор 604x/605x/421x/454x/455x е с {@code isTaxAuthorizedOnMoveLine
 * = false} и празен {@code defaultTaxSet} в BG_COA), а свойство на данни може да се смени с един
 * ред в CSV. Тогава заплатите ще започнат да пълнят дневника по ЗДДС — тихо, защото сумите са
 * правдоподобни.
 *
 * <p>⚠️ Колекторът НЕ се пипа от този клон. Тестът пази границата отвън.
 */
class BgPayrollMoveIsNotVatRelevantTest {

  /** Само за да се стигне до {@code protected boolean isVatRelevant}. Репозиторият не се ползва. */
  private static final class Collector extends BgVatDocumentCollector {
    Collector() {
      super(null);
    }

    boolean relevant(Move move) {
      return isVatRelevant(move);
    }
  }

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · заплатна статия НЕ е ДДС документ — няма фактура и няма данък по редовете")
  void aPayrollMoveIsNotAVatDocument() {
    Move move = payrollMove();
    assertFalse(new Collector().relevant(move), "заплатната статия не бива да влиза в дневниците");
  }

  // ── 2 · контролният опит ────────────────────────────────────────────────

  @Test
  @DisplayName("2 · същата статия С данък по ред ВЛИЗА — тоест тестът не е вакуумно зелен")
  void theSameMoveWithTaxWouldBeRelevant() {
    Move move = payrollMove();
    // Данък на реда (taxLineSet). ⚠️ Не taxAmount: извън сметка за данък той е делът на платения
    // ДДС по плащане, не ДДС ред (BgVatDocumentCollector.carriesTax).
    move.getMoveLineList().get(0).setTaxLineSet(new HashSet<>(Arrays.asList(new TaxLine())));
    assertTrue(
        new Collector().relevant(move),
        "ако това падне, проверката отгоре не доказва нищо — тя минава за всяка статия");
  }

  @Test
  @DisplayName("3 · и статия С фактура влиза, независимо от сметките")
  void aMoveWithAnInvoiceIsRelevant() {
    Move move = payrollMove();
    move.setInvoice(new Invoice());
    assertTrue(new Collector().relevant(move));
  }

  // ── статията ────────────────────────────────────────────────────────────

  /** Двата реда на най-простата заплатна статия: Dr 6041 / Cr 4211. */
  private static Move payrollMove() {
    Move move = new Move();
    List<MoveLine> lines = new ArrayList<>();
    lines.add(moveLine(BgPayrollAccount.EXPENSE_BASIC_WAGE, "1500.00", true));
    lines.add(moveLine(BgPayrollAccount.PERSONNEL_NET_WAGE, "1500.00", false));
    move.setMoveLineList(lines);
    return move;
  }

  private static MoveLine moveLine(String code, String amount, boolean debit) {
    Account account = new Account();
    account.setCode(code);

    MoveLine line = new MoveLine();
    line.setAccount(account);
    line.setDebit(debit ? new BigDecimal(amount) : BigDecimal.ZERO);
    line.setCredit(debit ? BigDecimal.ZERO : new BigDecimal(amount));
    return line;
  }
}
