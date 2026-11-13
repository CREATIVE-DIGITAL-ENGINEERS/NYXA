package com.axelor.apps.bglocalization.service.bnb;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Amount;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Balance;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Period;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Result;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodLedger;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * СПБ-6А/6Б НА БНБ — САЛДАТА ОТ ДВИГАТЕЛЯ НА ГОД, ПАРТНЬОРИТЕ, КУРСОВЕТЕ НА ЕЦБ И EXCEL-ЪТ.
 *
 * <p>Правилата са в {@link BgBnbSpb6}; тук е само базата. Курсовете са тези, които системата вече
 * има (CurrencyConversionLine от ЕЦБ) — последният ред с дата до датата на салдото, пряк или
 * обратен, както при протокола по чл. 117 ЗДДС. Нищо не се записва освен файла.
 */
public class BgBnbSpb6Service {

  protected final BgNsiGodLedger ledger;
  protected final MetaFiles metaFiles;

  @Inject
  public BgBnbSpb6Service(BgNsiGodLedger ledger, MetaFiles metaFiles) {
    this.ledger = ledger;
    this.metaFiles = metaFiles;
  }

  public Result compute(Company company, int year, int quarter) {
    Period period = Period.of(year, quarter);
    Map<String, BigDecimal[]> opening = ledger.partnerCurrencyBalances(company, period.opening);
    Map<String, BigDecimal[]> closing = ledger.partnerCurrencyBalances(company, period.closing);

    Map<Long, Object[]> partners = partners(opening, closing);
    Map<String, Balance> balances = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    collect(balances, partners, opening, true, problems);
    collect(balances, partners, closing, false, problems);

    Result result = BgBnbSpb6.build(period, new ArrayList<>(balances.values()), this::eurPerUnit);
    result.warnings.addAll(0, problems);
    return result;
  }

  public MetaFile export(Company company, int year, int quarter) throws IOException {
    Result result = compute(company, year, quarter);
    String eik = company.getPartner() == null ? null : company.getPartner().getRegistrationCode();
    byte[] bytes = BgBnbSpb6Workbook.write(result, company.getName(), eik);
    String name =
        "SPB-6A-6B_"
            + (eik == null ? company.getCode() : eik)
            + "_"
            + year
            + (quarter == 0 ? "_godina" : "_Q" + quarter)
            + ".xlsx";
    return metaFiles.upload(new ByteArrayInputStream(bytes), name);
  }

  private void collect(
      Map<String, Balance> balances,
      Map<Long, Object[]> partners,
      Map<String, BigDecimal[]> amounts,
      boolean isOpening,
      List<String> problems) {
    for (Map.Entry<String, BigDecimal[]> e : amounts.entrySet()) {
      String[] key = e.getKey().split("\\|", -1);
      BigDecimal[] v = e.getValue();
      Amount amount =
          BgBnbSpb6.inCurrency(
              key[2].isEmpty() ? null : key[2],
              new BigDecimal[] {v[0], v[1]},
              new BigDecimal[] {v[2], v[3]});
      long partnerId = Long.parseLong(key[1]);
      Object[] p = partners.get(partnerId);
      String country = p == null ? null : (String) p[2];
      if (amount.problem != null) {
        if (BgBnbSpb6.isForeign(country)) {
          problems.add(
              "Не влиза в справката — "
                  + amount.problem
                  + ": "
                  + (p == null ? partnerId : p[0])
                  + ", сметка "
                  + key[0]
                  + (isOpening ? ", в началото" : ", в края"));
        }
        continue;
      }
      Balance b =
          balances.computeIfAbsent(
              key[0] + "|" + partnerId + "|" + amount.currency,
              k ->
                  new Balance(
                      key[0],
                      partnerId,
                      p == null ? String.valueOf(partnerId) : (String) p[0],
                      p != null
                          && Integer.valueOf(PartnerRepository.PARTNER_TYPE_INDIVIDUAL)
                              .equals(p[1]),
                      country,
                      amount.currency));
      if (isOpening) {
        b.opening = b.opening.add(amount.value);
      } else {
        b.closing = b.closing.add(amount.value);
      }
    }
  }

  /** [име, вид партньор, държава ISO alpha-2 от основния адрес] по id. */
  protected Map<Long, Object[]> partners(
      Map<String, BigDecimal[]> opening, Map<String, BigDecimal[]> closing) {
    List<Long> ids = new ArrayList<>();
    for (Map<String, BigDecimal[]> m : List.of(opening, closing)) {
      for (String k : m.keySet()) {
        Long id = Long.valueOf(k.split("\\|", -1)[1]);
        if (!ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    Map<Long, Object[]> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT p.id, COALESCE(p.fullName, p.name), p.partnerTypeSelect, c.alpha2Code"
                    + " FROM Partner p LEFT JOIN p.mainAddress ad LEFT JOIN ad.country c"
                    + " WHERE p.id IN :ids",
                Object[].class)
            .setParameter("ids", ids)
            .getResultList();
    for (Object[] r : rows) {
      String country = r[3] == null || ((String) r[3]).isBlank() ? null : (String) r[3];
      out.put((Long) r[0], new Object[] {r[1], r[2], country});
    }
    return out;
  }

  /** Евро за една единица към дата; null — няма курс. */
  protected BigDecimal eurPerUnit(String currency, LocalDate date) {
    CurrencyConversionLine direct = latest(currency, "EUR", date);
    CurrencyConversionLine inverse = latest("EUR", currency, date);
    CurrencyConversionLine chosen;
    if (direct == null || inverse == null) {
      chosen = direct == null ? inverse : direct;
    } else {
      chosen = direct.getFromDate().isBefore(inverse.getFromDate()) ? inverse : direct;
    }
    if (chosen == null
        || chosen.getExchangeRate() == null
        || chosen.getExchangeRate().signum() == 0) {
      return null;
    }
    return chosen == direct
        ? chosen.getExchangeRate()
        : BigDecimal.ONE.divide(chosen.getExchangeRate(), 10, RoundingMode.HALF_UP);
  }

  protected CurrencyConversionLine latest(String start, String end, LocalDate date) {
    return Query.of(CurrencyConversionLine.class)
        .filter(
            "self.startCurrency.codeISO = :start AND self.endCurrency.codeISO = :end"
                + " AND self.fromDate <= :date")
        .bind("start", start)
        .bind("end", end)
        .bind("date", date)
        .order("-fromDate")
        .fetchOne();
  }
}
