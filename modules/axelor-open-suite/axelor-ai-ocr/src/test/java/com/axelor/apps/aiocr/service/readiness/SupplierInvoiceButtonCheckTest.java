package com.axelor.apps.aiocr.service.readiness;

import static com.axelor.apps.aiocr.service.readiness.BgMessages.BG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.aiocr.service.readiness.SupplierInvoiceButtonCheck.Facts;
import com.axelor.apps.aiocr.service.readiness.SupplierInvoiceButtonCheck.Step;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The refusals said before Validate / Ventilate, in Bulgarian and with the place to fix: each one
 * appears exactly when its fact is set, at the step Axelor would refuse at, and never otherwise.
 */
class SupplierInvoiceButtonCheckTest {

  private final SupplierInvoiceButtonCheck check = new SupplierInvoiceButtonCheck();

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

  /** A supplier invoice nothing stands in the way of. */
  private static Facts clean() {
    Facts f = new Facts();
    f.supplierInvoice = true;
    f.today = TODAY;
    f.invoiceDate = TODAY;
    f.originDate = TODAY;
    f.journalCode = "ПОК";
    return f;
  }

  private List<String> validate(Facts f) {
    return check.blockers(f, Step.VALIDATE, BG);
  }

  private List<String> ventilate(Facts f) {
    return check.blockers(f, Step.VENTILATE, BG);
  }

  @Test
  void aCleanInvoiceHasNoBlockerAtEitherStep() {
    assertEquals(List.of(), validate(clean()));
    assertEquals(List.of(), ventilate(clean()));
  }

  @Test
  void aSaleIsNeverChecked() {
    Facts f = clean();
    f.supplierInvoice = false;
    f.partnerAccountMissing = true;
    f.linesWithoutAccount.add(1);
    assertEquals(List.of(), validate(f));
    assertEquals(List.of(), ventilate(f));
  }

  @Test
  void aLineWithoutAccountIsNamedByItsPositionAndTheTabToOpen() {
    Facts f = clean();
    f.linesWithoutAccount.add(2);
    assertEquals(
        List.of("Липсва сметка на ред 2 — отворете реда → раздел „Счетоводство“ → поле „Сметка“."),
        ventilate(f));
    assertEquals(List.of(), validate(f), "Validate does not need line accounts");
  }

  @Test
  void validateStopsAtItsOwnRefusals() {
    Facts f = clean();
    f.paymentModeWrongDirection = "Постъпил банков превод";
    f.journalMissing = true;
    f.paymentConditionMissing = true;
    f.periodMissing = true;
    assertEquals(
        List.of(
            "Начинът на плащане „Постъпил банков превод“ не е за този вид фактура (постъпление"
                + " вместо плащане или обратно). Изберете друг в полето „Начин на плащане“.",
            "Компанията няма дневник за покупки за този вид фактура. Задайте го в счетоводната"
                + " конфигурация на компанията, раздел „Дневници“."),
        validate(f));
  }

  @Test
  void thePartnerAccountIsCheckedAtBothSteps() {
    Facts missing = clean();
    missing.partnerAccountMissing = true;
    assertEquals(1, validate(missing).size());
    assertEquals(1, ventilate(missing).size());
    assertTrue(validate(missing).get(0).startsWith("Няма сметка за доставчика."));

    Facts flags = clean();
    flags.partnerAccountFlagsOff = "401";
    assertEquals(
        List.of(
            "Сметката на доставчика 401 трябва да има включени „Примирими“ и „Включване в"
                + " салдото на партньора“. Отворете сметката в сметкоплана и включете и двете."),
        ventilate(flags));
  }

  @Test
  void ventilateNamesEveryMissingSettingWithItsPlace() {
    Facts f = clean();
    f.paymentConditionMissing = true;
    f.paymentModeMissing = true;
    f.periodMissing = true;
    f.invoiceSequenceMissing = true;
    f.journalSequenceMissing = true;
    f.linesNotInJournal.add(new String[] {"3", "609"});
    List<String> out = ventilate(f);
    assertEquals(6, out.size(), String.join("\n", out));
    assertEquals("Липсва условие за плащане. Попълнете полето „Условие за плащане“.", out.get(0));
    assertEquals("Липсва начин на плащане. Попълнете полето „Начин на плащане“.", out.get(1));
    assertTrue(
        out.get(2).startsWith("Няма отворен счетоводен период за датата на фактурата 2026-10-22."));
    assertTrue(out.get(3).contains("номерация за фактурите от доставчици"));
    assertTrue(out.get(4).startsWith("Дневник ПОК няма номерация на движенията."));
    assertTrue(out.get(5).startsWith("Ред 3: сметка 609 не е разрешена в дневник ПОК."));
  }

  @Test
  void datesInTheFutureAreRefusedAndTodayIsNot() {
    Facts f = clean();
    f.invoiceDate = TODAY.plusDays(1);
    f.originDate = TODAY.plusDays(2);
    assertEquals(
        List.of(
            "Датата на фактурата 2026-10-23 е в бъдещето. Поправете полето „Дата на фактурата“.",
            "Датата на документа на доставчика 2026-10-24 е в бъдещето. Поправете полето „Дата на"
                + " произход“."),
        ventilate(f));
  }

  @Test
  void aMissingOriginDateIsLeftToThePopupThatAsksForIt() {
    Facts f = clean();
    f.originDate = null;
    assertEquals(
        List.of("Липсва датата на документа на доставчика. Попълнете полето „Дата на произход“."),
        ventilate(f));
    f.popupAsksOriginDate = true;
    assertEquals(List.of(), ventilate(f), "Ventilate asks for it in a pop-up first");
  }

  @Test
  void namesAreEscapedBecauseTheMessageIsHtml() {
    Facts f = clean();
    f.paymentModeWrongDirection = "<b>A&B</b>";
    assertTrue(validate(f).get(0).contains("„&lt;b&gt;A&amp;B&lt;/b&gt;“"), validate(f).get(0));
  }

  @Test
  void linesAreNamedByTheirPositionOnTheFormAndTitlesAreSkipped() {
    AccountType expense = new AccountType();
    Account allowed = new Account();
    allowed.setCode("602");
    allowed.setAccountType(expense);
    Account foreign = new Account();
    foreign.setCode("609");
    foreign.setAccountType(new AccountType());
    Journal journal = new Journal();
    journal.setValidAccountTypeSet(new HashSet<>(Set.of(expense)));

    InvoiceLine title = new InvoiceLine();
    title.setTypeSelect(InvoiceLineRepository.TYPE_TITLE);
    InvoiceLine ok = new InvoiceLine();
    ok.setAccount(allowed);
    InvoiceLine noAccount = new InvoiceLine();
    InvoiceLine notInJournal = new InvoiceLine();
    notInJournal.setAccount(foreign);

    Facts f = clean();
    SupplierInvoiceButtonCheck.readLines(f, List.of(title, ok, noAccount, notInJournal), journal);

    assertEquals(
        List.of(3), f.linesWithoutAccount, "the title line is line 1, not skipped in count");
    assertEquals(1, f.linesNotInJournal.size());
    assertEquals("4", f.linesNotInJournal.get(0)[0]);
    assertEquals("609", f.linesNotInJournal.get(0)[1]);
  }

  @Test
  void theJournalAcceptsAnAccountByItselfOrByItsType() {
    AccountType expense = new AccountType();
    Account account = new Account();
    account.setAccountType(expense);
    Journal journal = new Journal();
    assertFalse(
        SupplierInvoiceButtonCheck.journalAccepts(journal, account),
        "a journal with no compatible accounts or types accepts nothing, as in Axelor");

    journal.setValidAccountSet(new HashSet<>(Set.of(account)));
    assertTrue(SupplierInvoiceButtonCheck.journalAccepts(journal, account));

    Journal byType = new Journal();
    byType.setValidAccountTypeSet(new HashSet<>(Set.of(expense)));
    assertTrue(SupplierInvoiceButtonCheck.journalAccepts(byType, account));

    Account other = new Account();
    other.setAccountType(new AccountType());
    assertFalse(SupplierInvoiceButtonCheck.journalAccepts(byType, other));
  }
}
