package com.axelor.apps.bglocalization.service.currency;

import com.axelor.app.AppSettings;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.repo.CurrencyConversionLineRepository;
import com.axelor.apps.base.db.repo.CurrencyRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.currency.CurrencyConversionFactory;
import com.axelor.apps.base.service.currency.CurrencyConversionService;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoper;
import com.google.inject.servlet.ServletScopes;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Валутните двойки по подразбиране и опресняването им при стартиране.
 *
 * <p>Двойките са в {@value #PAIRS_KEY} (напр. {@code EUR/USD, EUR/GBP}). За всяка двойка без
 * отворен ред (без „до дата“) в курсовете на App Base се взема днешният курс от услугата в App Base
 * (ЕЦБ) и се създава ред — оттам нататък дневният график {@link BgExchangeRateSchedule} го
 * обновява. Изтрит ред се създава отново при следващото стартиране; спрян от администратора ред
 * (със „до дата“) — също, затова двойка, която не трябва да се следи, се маха от настройката.
 *
 * <p><b>Остарели курсове.</b> Ако най-старият отворен ред е отпреди предишния работен ден, всички
 * отворени редове се опресняват веднага, а не чак в 18:00 — иначе нова база щеше да смята със
 * заредените с нея курсове, а спряна за дни инсталация — със старите. (Курс, който не се е променил
 * от дни, също води до опресняване при стартиране — безвредно, само заявки към ЕЦБ.)
 *
 * <p>Всичко минава във фонова нишка: до 30 заявки към ЕЦБ (или изчакване при липса на връзка) не
 * бива да бавят стартирането. Провалът се записва и се опитва отново при следващото стартиране.
 */
public class BgExchangeRatePairs {

  private static final Logger log = LoggerFactory.getLogger(BgExchangeRatePairs.class);

  public static final String PAIRS_KEY = "nyxa.exchange-rates.pairs";

  protected final CurrencyRepository currencyRepo;
  protected final CurrencyConversionLineRepository lineRepo;
  protected final CurrencyConversionFactory conversionFactory;
  protected final AppBaseService appBaseService;

  @Inject
  public BgExchangeRatePairs(
      CurrencyRepository currencyRepo,
      CurrencyConversionLineRepository lineRepo,
      CurrencyConversionFactory conversionFactory,
      AppBaseService appBaseService) {
    this.currencyRepo = currencyRepo;
    this.lineRepo = lineRepo;
    this.conversionFactory = conversionFactory;
    this.appBaseService = appBaseService;
  }

  /**
   * Пуска {@link #run()} във фонова нишка със собствен обхват на заявка (като {@code ThreadedJob}).
   */
  public void runInBackground() {
    Thread thread =
        new Thread(
            () -> {
              RequestScoper scope = ServletScopes.scopeRequest(Collections.emptyMap());
              try (RequestScoper.CloseableScope ignored = scope.open()) {
                run();
              } catch (Exception e) {
                log.error("Exchange rates: the startup update failed: {}", e.getMessage(), e);
              } finally {
                JPA.clear();
              }
            },
            "bg-exchange-rates-startup");
    thread.setDaemon(true);
    thread.start();
  }

  /** Добавя липсващите двойки и опреснява остарелите курсове. */
  public void run() throws Exception {
    List<String[]> pairs = parsePairs(readPairsSetting());
    if (pairs.isEmpty()) {
      return;
    }
    CurrencyConversionService service = conversionService();

    int added = 0;
    for (String[] pair : pairs) {
      if (addIfMissing(service, pair[0], pair[1])) {
        added++;
      }
    }
    if (added > 0) {
      log.info("Exchange rates: added {} currency pair(s) from {}.", added, PAIRS_KEY);
    }

    LocalDate oldest = oldestOpenLineDate();
    if (oldest != null && oldest.isBefore(previousWorkingDay(today()))) {
      log.info("Exchange rates: oldest current rate is from {}; refreshing now.", oldest);
      refresh(service);
    }
  }

  protected CurrencyConversionService conversionService() throws Exception {
    return conversionFactory.getCurrencyConversionService();
  }

  protected void refresh(CurrencyConversionService service) throws Exception {
    service.updateCurrencyConverion();
  }

  /**
   * „EUR/USD, eur/gbp ,EUR/USD“ → [EUR, USD], [EUR, GBP]. Повторенията, празните и неправилните
   * части (без „/“, еднаква валута от двете страни) се пропускат; неправилните се записват.
   */
  static List<String[]> parsePairs(String setting) {
    Set<String> seen = new LinkedHashSet<>();
    List<String[]> pairs = new ArrayList<>();
    if (setting == null) {
      return pairs;
    }
    for (String part : setting.split(",")) {
      String text = part.trim().toUpperCase(Locale.ROOT);
      if (text.isEmpty()) {
        continue;
      }
      String[] codes = text.split("/");
      if (codes.length != 2
          || codes[0].isBlank()
          || codes[1].isBlank()
          || codes[0].trim().equals(codes[1].trim())) {
        log.warn("Exchange rates: ignoring '{}' in {} (expected FROM/TO).", part.trim(), PAIRS_KEY);
        continue;
      }
      String from = codes[0].trim();
      String to = codes[1].trim();
      if (seen.add(from + "/" + to)) {
        pairs.add(new String[] {from, to});
      }
    }
    return pairs;
  }

  /** Работните дни на ЕЦБ без празниците на TARGET: петък за понеделник, вчера за другите. */
  static LocalDate previousWorkingDay(LocalDate date) {
    LocalDate day = date.minusDays(1);
    while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
      day = day.minusDays(1);
    }
    return day;
  }

  protected boolean addIfMissing(CurrencyConversionService service, String fromCode, String toCode)
      throws Exception {
    Currency from = findCurrency(fromCode);
    Currency to = findCurrency(toCode);
    if (from == null || to == null) {
      log.warn(
          "Exchange rates: skipping {}/{} from {}: no currency with code {}.",
          fromCode,
          toCode,
          PAIRS_KEY,
          from == null ? fromCode : toCode);
      return false;
    }
    if (hasOpenLine(from, to)) {
      return false;
    }
    Pair<LocalDate, BigDecimal> rate = fetchRate(service, from, to);
    if (rate == null || rate.getRight() == null || rate.getRight().signum() <= 0) {
      log.warn("Exchange rates: no rate for {}/{}; will try again at the next start.", from, to);
      return false;
    }
    createLine(service, from, to, rate.getLeft(), rate.getRight());
    return true;
  }

  protected String readPairsSetting() {
    return AppSettings.get().get(PAIRS_KEY, "");
  }

  protected Currency findCurrency(String code) {
    return currencyRepo.findByCode(code);
  }

  protected boolean hasOpenLine(Currency from, Currency to) {
    return lineRepo
            .all()
            .filter("self.startCurrency = :from AND self.endCurrency = :to AND self.toDate IS NULL")
            .bind("from", from)
            .bind("to", to)
            .count()
        > 0;
  }

  protected Pair<LocalDate, BigDecimal> fetchRate(
      CurrencyConversionService service, Currency from, Currency to) throws Exception {
    return service.getRateWithDate(from, to);
  }

  protected void createLine(
      CurrencyConversionService service,
      Currency from,
      Currency to,
      LocalDate date,
      BigDecimal rate) {
    service.createCurrencyConversionLine(from, to, date, rate, appBaseService.getAppBase(), null);
  }

  protected LocalDate oldestOpenLineDate() {
    CurrencyConversionLine oldest =
        lineRepo.all().filter("self.toDate IS NULL").order("fromDate").fetchOne();
    return oldest != null ? oldest.getFromDate() : null;
  }

  protected LocalDate today() {
    return appBaseService.getTodayDate(null);
  }
}
