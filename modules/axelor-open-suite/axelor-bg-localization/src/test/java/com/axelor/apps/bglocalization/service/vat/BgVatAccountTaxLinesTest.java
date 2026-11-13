package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Редът по 4531 получава данъка на правото на кредит, редът по 4532 — начисления; другите редове
 * остават без данък, сумите не се пипат.
 */
class BgVatAccountTaxLinesTest {

  private static Account account(long id, String code) {
    Account a = new Account();
    a.setId(id);
    a.setCode(code);
    return a;
  }

  private static MoveLine line(Account account, String debit, String credit) {
    MoveLine l = new MoveLine();
    l.setAccount(account);
    l.setDebit(new BigDecimal(debit));
    l.setCredit(new BigDecimal(credit));
    return l;
  }

  @Test
  void eachVatAccountGetsItsOwnSide() {
    Account deductible = account(4531, "4531");
    Account charged = account(4532, "4532");
    Account expense = account(609, "609");
    MoveLine credit = line(deductible, "200", "0");
    MoveLine self = line(charged, "0", "200");
    MoveLine other = line(expense, "100", "0");
    TaxLine acq = new TaxLine();
    TaxLine sale = new TaxLine();

    List<MoveLine> lines = Arrays.asList(credit, self, other);
    BgVatAccountTaxLines.apply(lines, deductible, charged, acq, sale);

    assertSame(acq, credit.getTaxLineSet().iterator().next());
    assertSame(sale, self.getTaxLineSet().iterator().next());
    assertTrue(other.getTaxLineSet() == null || other.getTaxLineSet().isEmpty());
    assertEquals(0, credit.getDebit().compareTo(new BigDecimal("200")));
    assertEquals(0, self.getCredit().compareTo(new BigDecimal("200")));
  }

  @Test
  void missingTaxLineLeavesTheLineAlone() {
    Account deductible = account(4531, "4531");
    MoveLine credit = line(deductible, "200", "0");
    BgVatAccountTaxLines.apply(Arrays.asList(credit), deductible, null, null, null);
    assertTrue(credit.getTaxLineSet() == null || credit.getTaxLineSet().isEmpty());
  }

  @Test
  void standardCodesFollowBgCoa() {
    assertEquals("BG_SRP_20", BgVatAccountTaxLines.standardCode(true, new BigDecimal("20.00")));
    assertEquals("BG_SRS_20", BgVatAccountTaxLines.standardCode(false, new BigDecimal("20")));
    assertEquals("BG_LRP_9", BgVatAccountTaxLines.standardCode(true, new BigDecimal("9")));
    assertEquals("BG_LRS_9", BgVatAccountTaxLines.standardCode(false, new BigDecimal("9.0")));
    assertNull(BgVatAccountTaxLines.standardCode(true, new BigDecimal("7")));
    assertNull(BgVatAccountTaxLines.standardCode(true, null));
  }
}
