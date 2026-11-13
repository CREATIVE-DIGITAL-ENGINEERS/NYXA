package com.axelor.apps.base.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.service.app.AppBaseService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ПРИ ЛИПСВАЩ КУРС — ПОСЛЕДНИЯТ ПУБЛИКУВАН.
 *
 * <p>Задачата по график (CurrencyConversionJob) затваря стария ред в деня преди новата публикация и
 * отваря нов без крайна дата. Ден без публикация — събота, неделя, празник на ЕЦБ, или работен ден
 * преди 17:30 — попада в отворения ред, тоест справката дава последния публикуван курс, а не грешка
 * „No currency conversion found“. Тук редовете са такива, каквито задачата ги оставя.
 */
class CurrencyServiceLastPublishedRateTest {

  private static final Currency EUR = currency("EUR");
  private static final Currency USD = currency("USD");

  /** Четвъртък 05.11.2026 — публикуван курс, затворен от петъчния. */
  private static final BigDecimal THURSDAY_RATE = new BigDecimal("1.0800");

  /** Петък 06.11.2026 — последният публикуван; редът е отворен. */
  private static final BigDecimal FRIDAY_RATE = new BigDecimal("1.0900");

  private final CurrencyServiceImpl service = service();

  @Test
  void вПочивенДенЕПоследниятПубликуван() throws Exception {
    assertEquals(FRIDAY_RATE, rate(LocalDate.of(2026, 11, 7)), "събота");
    assertEquals(FRIDAY_RATE, rate(LocalDate.of(2026, 11, 8)), "неделя");
  }

  @Test
  void безНоваПубликацияОставаПоследният() throws Exception {
    // Празник на ЕЦБ в работен ден или задача, която не е минала — пак последният публикуван.
    assertEquals(FRIDAY_RATE, rate(LocalDate.of(2026, 12, 25)), "Коледа (TARGET)");
  }

  @Test
  void вДенСПубликацияЕНеговиятКурс() throws Exception {
    assertEquals(THURSDAY_RATE, rate(LocalDate.of(2026, 11, 5)), "четвъртък");
    assertEquals(FRIDAY_RATE, rate(LocalDate.of(2026, 11, 6)), "петък");
  }

  private BigDecimal rate(LocalDate date) throws Exception {
    return service.getCurrencyConversionRate(EUR, USD, date).setScale(4);
  }

  private static CurrencyServiceImpl service() {
    AppBaseService appBaseService = mock(AppBaseService.class);
    when(appBaseService.getCurrencyConfigurationLineList())
        .thenReturn(
            List.of(
                line(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), THURSDAY_RATE),
                line(LocalDate.of(2026, 11, 6), null, FRIDAY_RATE)));
    return new CurrencyServiceImpl(appBaseService, null);
  }

  private static CurrencyConversionLine line(LocalDate from, LocalDate to, BigDecimal rate) {
    CurrencyConversionLine line = new CurrencyConversionLine();
    line.setStartCurrency(EUR);
    line.setEndCurrency(USD);
    line.setFromDate(from);
    line.setToDate(to);
    line.setExchangeRate(rate);
    return line;
  }

  private static Currency currency(String code) {
    Currency currency = new Currency();
    currency.setCodeISO(code);
    currency.setName(code);
    return currency;
  }
}
