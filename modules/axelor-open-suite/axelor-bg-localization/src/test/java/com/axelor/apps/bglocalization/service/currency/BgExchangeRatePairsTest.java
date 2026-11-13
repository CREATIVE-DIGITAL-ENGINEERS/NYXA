package com.axelor.apps.bglocalization.service.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.service.currency.CurrencyConversionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

class BgExchangeRatePairsTest {

  private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 6);
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

  @Test
  void parsesTrimsUppercasesAndDropsDuplicatesAndBadEntries() {
    List<String[]> pairs =
        BgExchangeRatePairs.parsePairs(
            " eur/usd, EUR/GBP ,EUR/USD,,USD, EUR/EUR, /GBP, EUR / CHF ");

    assertEquals(
        List.of("EUR/USD", "EUR/GBP", "EUR/CHF"),
        pairs.stream().map(p -> p[0] + "/" + p[1]).collect(Collectors.toList()));
    assertTrue(BgExchangeRatePairs.parsePairs(null).isEmpty());
    assertTrue(BgExchangeRatePairs.parsePairs("").isEmpty());
  }

  @Test
  void previousWorkingDaySkipsTheWeekend() {
    assertEquals(FRIDAY, BgExchangeRatePairs.previousWorkingDay(MONDAY));
    assertEquals(FRIDAY, BgExchangeRatePairs.previousWorkingDay(LocalDate.of(2026, 11, 8)));
    assertEquals(FRIDAY, BgExchangeRatePairs.previousWorkingDay(LocalDate.of(2026, 11, 7)));
    assertEquals(
        LocalDate.of(2026, 11, 10),
        BgExchangeRatePairs.previousWorkingDay(LocalDate.of(2026, 11, 11)));
  }

  @Test
  void addsOnlyMissingPairsAtTheFetchedRateAndSkipsUnknownCurrencies() throws Exception {
    Fake fake = new Fake("EUR/USD, EUR/GBP, EUR/XXX");
    fake.currencies("EUR", "USD", "GBP");
    fake.openPairs.add("EUR/USD");
    fake.rates.put("EUR/GBP", Pair.of(FRIDAY, new BigDecimal("0.83415")));
    fake.today = MONDAY;
    fake.oldest = FRIDAY;

    fake.run();

    assertEquals(List.of("EUR/GBP 2026-11-06 0.83415"), fake.created);
    assertEquals(0, fake.refreshes, "Friday's rates are current on Monday");
  }

  @Test
  void aPairWithoutARateIsNotCreated() throws Exception {
    Fake fake = new Fake("EUR/USD");
    fake.currencies("EUR", "USD");
    fake.rates.put("EUR/USD", Pair.of(FRIDAY, new BigDecimal(-1)));
    fake.today = MONDAY;

    fake.run();

    assertTrue(fake.created.isEmpty(), "AOS signals 'no rate' with -1");
  }

  @Test
  void refreshesWhenTheOldestCurrentRateIsBeforeThePreviousWorkingDay() throws Exception {
    Fake fake = new Fake("EUR/USD");
    fake.currencies("EUR", "USD");
    fake.openPairs.add("EUR/USD");
    fake.today = MONDAY;
    fake.oldest = LocalDate.of(2026, 11, 5);

    fake.run();

    assertEquals(1, fake.refreshes);
  }

  @Test
  void anEmptySettingDoesNothing() throws Exception {
    Fake fake = new Fake("");
    fake.today = MONDAY;
    fake.oldest = LocalDate.of(2013, 5, 2);

    fake.run();

    assertEquals(0, fake.refreshes, "with no pairs configured, the feature is off");
    assertTrue(fake.created.isEmpty());
  }

  /** The real service, with only its settings, database and ECB seams faked. */
  private static class Fake extends BgExchangeRatePairs {
    final String setting;
    final Map<String, Currency> byCode = new HashMap<>();
    final Set<String> openPairs = new HashSet<>();
    final Map<String, Pair<LocalDate, BigDecimal>> rates = new HashMap<>();
    final List<String> created = new ArrayList<>();
    LocalDate today;
    LocalDate oldest;
    int refreshes;

    Fake(String setting) {
      super(null, null, null, null);
      this.setting = setting;
    }

    void currencies(String... codes) {
      for (String code : codes) {
        Currency currency = new Currency();
        currency.setCode(code);
        byCode.put(code, currency);
      }
    }

    @Override
    protected CurrencyConversionService conversionService() {
      return null; // the ECB seams below are faked, so the real service is never called
    }

    @Override
    protected void refresh(CurrencyConversionService service) {
      refreshes++;
    }

    @Override
    protected String readPairsSetting() {
      return setting;
    }

    @Override
    protected Currency findCurrency(String code) {
      return byCode.get(code);
    }

    @Override
    protected boolean hasOpenLine(Currency from, Currency to) {
      return openPairs.contains(from.getCode() + "/" + to.getCode());
    }

    @Override
    protected Pair<LocalDate, BigDecimal> fetchRate(
        CurrencyConversionService service, Currency from, Currency to) {
      return rates.get(from.getCode() + "/" + to.getCode());
    }

    @Override
    protected void createLine(
        CurrencyConversionService service,
        Currency from,
        Currency to,
        LocalDate date,
        BigDecimal rate) {
      created.add(from.getCode() + "/" + to.getCode() + " " + date + " " + rate.toPlainString());
    }

    @Override
    protected LocalDate oldestOpenLineDate() {
      return oldest;
    }

    @Override
    protected LocalDate today() {
      return today;
    }
  }
}
