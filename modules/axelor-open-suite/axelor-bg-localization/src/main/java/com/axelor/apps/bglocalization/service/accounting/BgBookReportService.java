package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.db.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Three read-only reports. Source amounts and aggregate reporting amounts are explicitly separated.
 */
public class BgBookReportService {
  public static final class Totals {
    private final BigDecimal[] bgn = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    private final BigDecimal[] eur = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};

    public void add(String currency, boolean opening, BigDecimal debit, BigDecimal credit) {
      if (!"BGN".equals(currency) && !"EUR".equals(currency)) {
        throw new IllegalArgumentException("Unsupported book currency: " + currency);
      }
      BigDecimal[] values = "BGN".equals(currency) ? bgn : eur;
      if (opening) {
        values[0] = values[0].add(debit.subtract(credit));
      } else {
        values[1] = values[1].add(debit);
        values[2] = values[2].add(credit);
      }
    }

    public BigDecimal value(int component, String currency) {
      return BgAccountingCurrencyPolicy.component(bgn[component], eur[component], currency);
    }

    public BigDecimal closing(String currency) {
      return value(0, currency).add(value(1, currency)).subtract(value(2, currency));
    }
  }

  public String generate(Company company, LocalDate from, LocalDate to, String kind) {
    if (company == null) {
      throw new IllegalArgumentException("Изберете активна компания.");
    }
    if (!List.of("TRIAL", "GENERAL", "PARTNER").contains(kind)) {
      throw new IllegalArgumentException("Неизвестна справка.");
    }
    String output = BgAccountingCurrencyPolicy.reportCurrency(from, to);
    // Earlier standard Moves form the opening balance. For EUR periods, the accumulated historical
    // BGN component is converted once at the fixed transition rate; no duplicate opening Move
    // exists.
    LocalDate epoch = LocalDate.of(1, 1, 1);
    List<MoveLine> lines =
        Query.of(MoveLine.class)
            .filter(
                "self.move.company = :company AND self.move.statusSelect = :status"
                    + " AND (self.move.ignoreInAccountingOk IS NULL OR self.move.ignoreInAccountingOk = false)"
                    + " AND self.date >= :epoch AND self.date <= :to")
            .bind("company", company)
            .bind("status", MoveRepository.STATUS_ACCOUNTED)
            .bind("epoch", epoch)
            .bind("to", to)
            .order("date")
            .order("move.id")
            .order("counter")
            .order("id")
            .fetch();
    Map<String, Totals> groups = new LinkedHashMap<>();
    StringBuilder ledger = new StringBuilder();
    boolean historicalBgnConverted = false;
    for (MoveLine line : lines) {
      Move move = line.getMove();
      boolean openingMove =
          move.getFunctionalOriginSelect() == MoveRepository.FUNCTIONAL_ORIGIN_OPENING;
      if ("PARTNER".equals(kind)
          && !Boolean.TRUE.equals(line.getAccount().getUseForPartnerBalance())) {
        continue;
      }
      String currency =
          move.getBgBookCurrency() != null
              ? move.getBgBookCurrency().getCodeISO()
              : BgAccountingCurrencyPolicy.bookCurrency(move.getPeriod().getYear());
      if (!currency.equals(BgAccountingCurrencyPolicy.expectedCurrency(line.getDate()))) {
        throw new IllegalStateException("Валута/дата не съвпадат за Move " + move.getId());
      }
      if ("EUR".equals(output) && "BGN".equals(currency)) {
        historicalBgnConverted = true;
      }
      String key = line.getAccount().getCode();
      if ("PARTNER".equals(kind)) {
        key +=
            " / "
                + (line.getPartner() == null
                    ? "(без партньор)"
                    : line.getPartner().getId() + " " + line.getPartner().getFullName());
      }
      Totals total = groups.computeIfAbsent(key, unused -> new Totals());
      boolean opening =
          line.getDate().isBefore(from) || (openingMove && line.getDate().equals(from));
      total.add(currency, opening, line.getDebit(), line.getCredit());
      if ("GENERAL".equals(kind) && !opening) {
        // Individual entries remain in their original book currency. The running reporting
        // balance converts the accumulated BGN component once, so rounding never compounds.
        ledger
            .append(key)
            .append('\t')
            .append(line.getDate())
            .append('\t')
            .append(move.getId())
            .append('\t')
            .append(move.getReference())
            .append('\t')
            .append(currency)
            .append('\t')
            .append(line.getDebit())
            .append('\t')
            .append(line.getCredit())
            .append('\t')
            .append(total.closing(output))
            .append('\n');
      }
    }
    StringBuilder result =
        new StringBuilder(kind + " | " + from + " .. " + to + " | " + output + "\n");
    if (historicalBgnConverted) {
      result.append(
          "Валутно правило: историческата BGN част е преобразувана в EUR по фиксиран курс "
              + "1 EUR = 1.95583 BGN. Изходните суми в проводките не са променени.\n");
    }
    result.append("Сметка / партньор\tНачално салдо\tДебит\tКредит\tКрайно салдо\n");
    groups.forEach(
        (key, total) ->
            result
                .append(key)
                .append('\t')
                .append(total.value(0, output))
                .append('\t')
                .append(total.value(1, output))
                .append('\t')
                .append(total.value(2, output))
                .append('\t')
                .append(total.closing(output))
                .append('\n'));
    if ("GENERAL".equals(kind)) {
      result
          .append(
              "\nСметка\tДата\tMove ID\tДокумент\tИзходна валута\tИзходен дебит\tИзходен кредит\tТекущо салдо ")
          .append(output)
          .append('\n')
          .append(ledger);
    }
    if ("PARTNER".equals(kind)) {
      result
          .append("\nСалда по проводки към датата; това не е списък на доказано неплатени фактури.")
          .append(" Неразрешените исторически равнения се виждат в доклада от импорта.\n");
    }
    return result.toString();
  }
}
