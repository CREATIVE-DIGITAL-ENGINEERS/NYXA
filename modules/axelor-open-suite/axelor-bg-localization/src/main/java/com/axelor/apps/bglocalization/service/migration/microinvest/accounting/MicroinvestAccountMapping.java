package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.OpeningBalance;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Settlement;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The accountant-approved Microinvest to Nyxa account mapping (03-account-mapping-gaps), applied
 * ONCE to the parsed source before any other Preflight check or import step looks at it.
 *
 * <p>Because the parsed {@code root} is rewritten in place, the resolved target account is the one
 * used by ordinary MoveLines, opening MoveLines, the account/journal/special-account checks and the
 * settlement endpoint lookup alike — there is no second place that could still read a source code.
 *
 * <p>Rules: one pass only (461 → 456 does not then continue 456 → 459); a code that is not in the
 * table keeps its own number and is then proven against the chart by the account check; 489 has NO
 * general mapping and is only accepted for the eight explicit FY2019 entries below. The importer
 * never creates or edits a chart-of-accounts record: a target that does not exist stays a Preflight
 * blocker.
 *
 * <p>Partners: a mapped line keeps its source partner (405*, 419/1, 159*, 498*, 499*); account 493
 * is posted without one.
 */
public final class MicroinvestAccountMapping {

  /** A resolved Nyxa account and how the line's partner is treated. */
  static final class Target {
    final String code;
    final boolean noPartner;

    /** Source partner to use only when the source line has none. */
    final Integer defaultPartnerSourceId;

    Target(String code, boolean noPartner, Integer defaultPartnerSourceId) {
      this.code = code;
      this.noPartner = noPartner;
      this.defaultPartnerSourceId = defaultPartnerSourceId;
    }
  }

  /** What one {@link #apply} pass did; the Preflight turns it into blockers and notes. */
  static final class Outcome {
    /** Source codes with no approved mapping (489 outside the FY2019 exception). */
    final Set<String> unmapped = new LinkedHashSet<>();

    int remapped;
    int negativesFlipped;
  }

  private static final Map<String, Target> TARGETS = new HashMap<>();

  static {
    map("151", "159/1", "159/2");
    map("401", "405/1/1", "405/2/1", "405/3", "405/4");
    map("411", "419/1");
    map("4211", "421");
    map("4531", "453/1");
    map("4532", "453/2");
    map("4538", "453/8");
    map("4539", "453/9");
    map("4548", "454/1");
    map("4542", "454/2");
    map("4541", "454/3");
    map("4551", "455/1", "455/4", "455/9");
    map("4552", "455/2", "455/6", "455/11");
    map("4553", "455/3", "455/5", "455/10");
    map("4555", "455/7");
    map("459", "456", "457", "460/1", "460/2");
    map("456", "461");
    TARGETS.put("493", new Target("493", true, null));
    map("498", "498/1");
    map("499", "498/2", "499/1", "499/2", "499/3", "499/4", "499/5", "499/6", "499/7", "499/8");
    map("499", "499/9");
    map("503", "503/2");
    map("504", "503/3", "504/1");
    map("501", "509/1");
    map("60102", "601/1", "601/4", "602/6");
    map("60105", "601/2");
    map("60103", "601/3");
    map("60101", "601/5");
    map("60202", "602/1");
    map("60212", "602/2");
    map("60201", "602/3");
    map("60206", "602/4");
    map("60211", "602/5");
    map("60207", "602/7", "602/9");
    map("60603", "609/1");
    map("60909", "609/9");
    map("614", "691");
    map("699", "692");
    map("799", "791");
  }

  /**
   * The only place 489 is accepted: FY2019, these source entries, posted to 151 with partner 642.
   */
  static final int LEGACY_489_YEAR = 2019;

  static final Set<Integer> LEGACY_489_ENTRIES =
      Set.of(5245, 6545, 6546, 6549, 6550, 6551, 6553, 6554);

  static final String LEGACY_489_TARGET = "151";

  /** "Мото Пфое". */
  static final int LEGACY_489_PARTNER_SOURCE_ID = 642;

  private MicroinvestAccountMapping() {}

  private static void map(String target, String... sources) {
    for (String source : sources) {
      TARGETS.put(source, new Target(target, false, null));
    }
  }

  /**
   * @return the approved target, the source code itself when the table does not list it, or {@code
   *     null} when the code is explicitly unmapped (489 outside its exception).
   */
  static Target resolve(String sourceCode, Integer fiscalYear, Integer entrySourceId) {
    if (sourceCode == null || sourceCode.trim().isEmpty()) {
      return new Target(sourceCode, false, null);
    }
    String code = sourceCode.trim();
    Target target = TARGETS.get(code);
    if (target != null) {
      return target;
    }
    if ("489".equals(code)) {
      return Integer.valueOf(LEGACY_489_YEAR).equals(fiscalYear)
              && LEGACY_489_ENTRIES.contains(entrySourceId)
          ? new Target(LEGACY_489_TARGET, false, LEGACY_489_PARTNER_SOURCE_ID)
          : null;
    }
    return new Target(code, false, null);
  }

  /** The Nyxa code for a settlement endpoint account; the source code when it has no mapping. */
  static String targetCode(String sourceCode, Integer fiscalYear, Integer entrySourceId) {
    Target target = resolve(sourceCode, fiscalYear, entrySourceId);
    return target == null ? sourceCode : target.code;
  }

  /** Rewrites accounts, partners and negative amounts of the whole parsed file, once. */
  static Outcome apply(ExportRoot root) {
    Outcome outcome = new Outcome();
    Integer year = root.financialYear == null ? null : root.financialYear.year;
    for (Entry entry : root.entries) {
      for (Line line : entry.lines) {
        if (line.sourceAccountNumber != null) {
          continue; // already normalised
        }
        line.sourceAccountNumber = line.accountNumber;
        Target target = resolve(line.accountNumber, year, entry.sourceId);
        if (target == null) {
          outcome.unmapped.add(line.accountNumber.trim());
        } else {
          remap(target, line.accountNumber, outcome);
          line.accountNumber = target.code;
          if (target.noPartner) {
            line.partnerSourceId = null;
          } else if (line.partnerSourceId == null) {
            line.partnerSourceId = target.defaultPartnerSourceId;
          }
        }
        BigDecimal[] sides = flipNegatives(line.debit, line.credit);
        if (sides != null) {
          line.debit = sides[0];
          line.credit = sides[1];
          line.negativeNormalized = true;
          outcome.negativesFlipped++;
        }
      }
    }
    for (OpeningBalance ob : root.openingBalances) {
      if (ob.sourceAccountNumber != null) {
        continue;
      }
      ob.sourceAccountNumber = ob.accountNumber;
      Target target = resolve(ob.accountNumber, year, null);
      if (target == null) {
        outcome.unmapped.add(ob.accountNumber.trim());
      } else {
        remap(target, ob.accountNumber, outcome);
        ob.accountNumber = target.code;
        if (target.noPartner) {
          ob.partnerSourceId = null;
        }
      }
      BigDecimal[] sides = flipNegatives(ob.debit, ob.credit);
      if (sides != null) {
        ob.debit = sides[0];
        ob.credit = sides[1];
        ob.negativeNormalized = true;
        outcome.negativesFlipped++;
      }
    }
    for (Settlement settlement : root.settlements) {
      Integer entryId =
          LEGACY_489_ENTRIES.contains(settlement.paymentEntryId)
              ? settlement.paymentEntryId
              : settlement.settledEntryId;
      settlement.accountNumber = targetCode(settlement.accountNumber, year, entryId);
    }
    return outcome;
  }

  private static void remap(Target target, String source, Outcome outcome) {
    if (!target.code.equals(source.trim())) {
      outcome.remapped++;
    }
  }

  /**
   * Absolute amount on the opposite side for every negative value, so debit minus credit is
   * unchanged. Returns {@code null} when neither side is negative.
   */
  static BigDecimal[] flipNegatives(BigDecimal debit, BigDecimal credit) {
    boolean negDebit = debit != null && debit.signum() < 0;
    boolean negCredit = credit != null && credit.signum() < 0;
    if (!negDebit && !negCredit) {
      return null;
    }
    BigDecimal d = debit == null ? BigDecimal.ZERO : debit;
    BigDecimal c = credit == null ? BigDecimal.ZERO : credit;
    return new BigDecimal[] {
      d.max(BigDecimal.ZERO).add(c.min(BigDecimal.ZERO).negate()),
      c.max(BigDecimal.ZERO).add(d.min(BigDecimal.ZERO).negate())
    };
  }
}
