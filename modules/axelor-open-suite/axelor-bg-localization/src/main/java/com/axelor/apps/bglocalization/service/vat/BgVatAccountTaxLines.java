package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.db.JPA;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

/**
 * ДАНЪЧНИЯТ РЕД НА РЕДОВЕТЕ ПО 4531/4532 В СТАТИИТЕ НА МОДУЛА ЗА ДДС.
 *
 * <p>Протоколът по чл. 117, статиите по чл. 79/79а и ДДС статията на авансова фактура са с
 * функционален произход „продажба“ или „покупка“. За такава статия AOS иска данъчен ред на всеки
 * ред по сметка от тип „данък“ ({@code MoveLineTaxServiceImpl#checkEmptyTaxLines}: „Tax line must
 * be filled when an account of type tax is selected“), а в BG_COA 4531 и 4532 са точно такива. Без
 * данъчен ред нито една от тези статии не се осчетоводяваше на BG_COA дружество.
 *
 * <p>Правилото: редът по сметката за ДДС по покупки (4531) получава данъчния ред на правото на
 * кредит, редът по сметката за ДДС по продажби (4532) — на начисления (самоначисления) данък.
 * Сумите не се пипат. Колекторът на дневниците насочва тези статии по операцията им ({@code
 * Move.bgVatMoveVatOperation}), не по данъчните редове — те не местят нищо в дневниците.
 */
public final class BgVatAccountTaxLines {

  private BgVatAccountTaxLines() {}

  /**
   * Слага данъчните редове на редовете по двете сметки за ДДС; редовете по други сметки остават без
   * данък. Липсващ данъчен ред ({@code null}) не се слага.
   */
  public static void apply(
      Collection<MoveLine> lines,
      Account deductible,
      Account charged,
      TaxLine creditTaxLine,
      TaxLine chargedTaxLine) {
    for (MoveLine line : lines) {
      Account account = line.getAccount();
      if (account == null) {
        continue;
      }
      if (same(account, deductible) && creditTaxLine != null) {
        line.setTaxLineSet(new HashSet<>(Collections.singleton(creditTaxLine)));
      } else if (same(account, charged) && chargedTaxLine != null) {
        line.setTaxLineSet(new HashSet<>(Collections.singleton(chargedTaxLine)));
      }
    }
  }

  /** Данъкът на правото на кредит от реда на покупната фактура (собственият данък на реда). */
  public static TaxLine credit(InvoiceLine line) {
    if (line == null || line.getTaxLineSet() == null) {
      return null;
    }
    return line.getTaxLineSet().stream().findFirst().orElse(null);
  }

  /** Самоначисленият данък на реда — активният ред на данъка за обратно начисляване. */
  public static TaxLine selfCharged(InvoiceLine line) {
    if (line == null
        || line.getTaxEquiv() == null
        || line.getTaxEquiv().getReverseChargeTaxSet() == null) {
      return null;
    }
    for (Tax tax : line.getTaxEquiv().getReverseChargeTaxSet()) {
      if (tax != null && tax.getActiveTaxLine() != null) {
        return tax.getActiveTaxLine();
      }
    }
    return null;
  }

  /**
   * Стандартният данък на BG_COA за ставката — за статия без източник с данък (корекция по чл.
   * 79/79а на актив без покупна фактура): покупки BG_SRP_20 / BG_LRP_9 / BG_ZRP, продажби BG_SRS_20
   * / BG_LRS_9 / BG_ZRS. Друга ставка или липсващ данък — {@code null}.
   */
  public static TaxLine standard(boolean purchase, BigDecimal ratePercent) {
    String code = standardCode(purchase, ratePercent);
    if (code == null) {
      return null;
    }
    Tax tax = JPA.all(Tax.class).filter("self.code = ?1", code).fetchOne();
    return tax == null ? null : tax.getActiveTaxLine();
  }

  /**
   * Данъчните редове на корекция по актив (чл. 79/79а): правото на кредит — от реда на покупната
   * фактура на актива; начисленият — стандартният данък за продажби със същата ставка. Актив без
   * покупна фактура — стандартната ставка от 20 % (ЗДДС чл. 66 ал. 1).
   */
  public static TaxLine[] forAsset(com.axelor.apps.account.db.FixedAsset asset) {
    TaxLine credit = asset == null ? null : credit(asset.getInvoiceLine());
    BigDecimal rate = credit != null && credit.getValue() != null ? credit.getValue() : TWENTY;
    if (credit == null) {
      credit = standard(true, rate);
    }
    return new TaxLine[] {credit, standard(false, rate)};
  }

  private static final BigDecimal TWENTY = new BigDecimal("20");

  static String standardCode(boolean purchase, BigDecimal ratePercent) {
    if (ratePercent == null) {
      return null;
    }
    switch (ratePercent.stripTrailingZeros().toPlainString()) {
      case "20":
        return purchase ? "BG_SRP_20" : "BG_SRS_20";
      case "9":
        return purchase ? "BG_LRP_9" : "BG_LRS_9";
      case "0":
        return purchase ? "BG_ZRP" : "BG_ZRS";
      default:
        return null;
    }
  }

  private static boolean same(Account a, Account b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.getId() != null && b.getId() != null) {
      return a.getId().equals(b.getId());
    }
    return a == b;
  }
}
