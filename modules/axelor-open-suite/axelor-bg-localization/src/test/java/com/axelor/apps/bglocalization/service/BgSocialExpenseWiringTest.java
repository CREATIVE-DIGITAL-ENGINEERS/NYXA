package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДВАТА НОВИ ШЕВА В {@code BgPaySlipService} — навързани ли са, и инертни ли са там, където трябва.
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ ТЕСТ СЪЩЕСТВУВА.</b> Нов шев в {@code recompute()} се вика от всеки тест,
 * който пипа фиш — а те са стотици и почти всички текат БЕЗ Guice. Шев, който вика {@code
 * Beans.get(...)} на първия си ред, поваля всички тях с {@code IllegalStateException}, и то по
 * причина, която няма нищо общо с това, което мерят. Тук се заковава точно обратното: чистият изход
 * стои ПРЕДИ заявката, тоест фиш без перо от 2xx минава без да поиска контейнер.
 *
 * <p>И обратната посока — че шевът НАИСТИНА се вика — също е закована. Инертен шев изглежда като
 * работеща проверка (виж {@code BgInertSeamTest}): месечната ЗКПО основа щеше да остава нула
 * завинаги, а нулата е ВЯРНО число за месец без социални разходи, тоест нищо нямаше да се обади.
 */
class BgSocialExpenseWiringTest {

  /** Стойността, която подменените шевове връщат — разпознаваема, за да не мине по съвпадение. */
  private static final BigDecimal STAMPED = new BigDecimal("13.37");

  private static class Service extends BgPaySlipService {
    int taxBaseCalls;
    int refusalCalls;

    @Override
    protected BigDecimal socialExpenseTaxBase(BgPaySlip slip) {
      taxBaseCalls++;
      return STAMPED;
    }

    @Override
    protected String socialExpenseRefusal(BgPaySlip slip) {
      refusalCalls++;
      return null;
    }

    @Override
    protected BigDecimal disabilityRelief(BgPaySlip slip) {
      return BigDecimal.ZERO;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }
  }

  /**
   * ⚠️ ДВАТА ФЛАГА СЕ СЛАГАТ ИЗРИЧНО, макар домейнът да ги обявява с {@code default="true"}. Обект,
   * сглобен в Java, минава през генерирания getter, а той връща {@code Boolean.FALSE} за
   * непопълнено поле — тоест перо без изричен флаг НЕ влиза нито в данъчната основа, нито в
   * осигурителния доход, и заглавните суми излизат нули по причина, която няма нищо общо с това,
   * което този тест мери.
   */
  private static BgPaySlipLine line(int number, String sign, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setName("перо " + number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsTaxable(true);
    l.setBgIsInsuranceIncome(true);
    return l;
  }

  private static BgPaySlip slipWith(BgPaySlipLine... lines) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    List<BgPaySlipLine> list = new ArrayList<>();
    for (BgPaySlipLine l : lines) {
      list.add(l);
    }
    s.setBgPaySlipLineList(list);
    return s;
  }

  @Test
  @DisplayName("⚠️ recompute() НАИСТИНА вика шева и записва числото му на фиша")
  void recomputeStampsTheMonthlyCompanyBase() {
    Service service = new Service();
    BgPaySlip slip = slipWith(line(100, BgPaySlipResolver.CREDIT, "1000.00"));

    service.recompute(slip);

    assertEquals(1, service.taxBaseCalls, "шевът трябва да се вика ВЕДНЪЖ на преизчисляване");
    assertEquals(
        STAMPED,
        slip.getBgSocialExpenseTaxBase(),
        "⚠️ Числото трябва да СЯДА на фиша. Невързан, шевът щеше да оставя нула — а нулата е ВЯРНО"
            + " число за месец без социални разходи, тоест разликата е невидима");
  }

  @Test
  @DisplayName("⚠️ шестото число НЕ влиза в нито един от петте заглавни сбора")
  void theCompanyBaseIsNotPartOfTheFiveTotals() {
    Service service = new Service();
    BgPaySlip slip = slipWith(line(100, BgPaySlipResolver.CREDIT, "1000.00"));

    service.recompute(slip);

    assertEquals(
        new BigDecimal("1000.00"),
        slip.getBgGrossTotal(),
        "⚠️ Месечната ЗКПО основа е задължение на ПРЕДПРИЯТИЕТО, не доход на лицето — прибавена към"
            + " брутото, тя щеше да вдигне и осигурителния доход, и данъка");
    assertEquals(new BigDecimal("1000.00"), slip.getBgNetTotal());
    assertEquals(new BigDecimal("1000.00"), slip.getBgTaxBase());
  }

  @Test
  @DisplayName("⚠️ ЧИСТИЯТ ИЗХОД: фиш БЕЗ перо от 2xx не иска нито Guice, нито база")
  void aSlipWithoutSocialExpenseLinesNeverAsksForAContainer() {
    // ⚠️ ИСТИНСКИЯТ BgPaySlipService, БЕЗ подмяна на шева — тоест ако чистият изход го нямаше, тук
    // щеше да се стигне до Beans.get(...) и тестът щеше да падне с „Guice is not initialized“.
    // Точно това е твърдението: не че методът връща нула, а че СТИГА до нулата без контейнер.
    BgPaySlipService real = new BgPaySlipService();

    assertEquals(
        BigDecimal.ZERO,
        real.socialExpenseTaxBase(slipWith(line(100, BgPaySlipResolver.CREDIT, "1000.00"))),
        "фиш само с 1xx перо");
    assertEquals(BigDecimal.ZERO, real.socialExpenseTaxBase(slipWith()), "фиш без нито едно перо");
    assertEquals(BigDecimal.ZERO, real.socialExpenseTaxBase(null), "и null фиш");

    assertNull(
        real.socialExpenseRefusal(slipWith(line(900, BgPaySlipResolver.CREDIT, "50.00"))),
        "⚠️ И пазачът при потвърждаване минава по същия чист път — инак всеки заварен фиш щеше да"
            + " иска контейнер само за да получи „няма какво да се откаже“");
    assertNull(real.socialExpenseRefusal(slipWith()));
    assertNull(real.socialExpenseRefusal(null));
  }

  @Test
  @DisplayName("⚠️ признакът „това е 2xx перо“ е ЧИСТ — номерът е копиран на самото перо")
  void theGroupTestReadsTheNumberCopiedOntoTheLine() {
    assertTrue(
        BgSocialExpenseCapResolver.isSocialExpenseLine(
            line(210, BgPaySlipResolver.CREDIT, "150.00")));
    assertTrue(
        BgSocialExpenseCapResolver.isSocialExpenseLine(
            line(271, BgPaySlipResolver.CREDIT, "10.00")));
    assertFalse(
        BgSocialExpenseCapResolver.isSocialExpenseLine(
            line(195, BgPaySlipResolver.CREDIT, "10.00")),
        "болничният от осигурителя е 1xx нарочно — виж бележката на перо 195");
    assertFalse(
        BgSocialExpenseCapResolver.isSocialExpenseLine(
            line(630, BgPaySlipResolver.DEBIT, "150.00")),
        "⚠️ КОНТРА-ПЕРОТО НА НАТУРАТА Е 6xx, НЕ 2xx, и това не е дребно: попаднало в 2xx, то щеше да"
            + " влезе в групирането по капачка и да се извади от начисленото ВЪТРЕ в сметката, вместо"
            + " да излезе от нетото след нея");
    assertFalse(BgSocialExpenseCapResolver.isSocialExpenseLine(null));
    assertFalse(
        BgSocialExpenseCapResolver.isSocialExpenseLine(new BgPaySlipLine()),
        "непопълнен номер дава група 0 — „никоя“, и това е верният отговор");
  }
}
