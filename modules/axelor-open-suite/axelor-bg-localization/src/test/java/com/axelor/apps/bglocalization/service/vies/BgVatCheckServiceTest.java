package com.axelor.apps.bglocalization.service.vies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vies.BgVatCheckService.Level;
import com.axelor.apps.bglocalization.service.vies.BgVatCheckService.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Сглобката рутиране + жива VIES, изцяло върху СТЪБ.
 *
 * <p>⚠️ <b>Нито един тест тук не пипа мрежата.</b> Истинска заявка към Европейската комисия в CI би
 * била бавна, чуплива (услугата пада редовно) и невъзпитана — а най-важното, тя не може да
 * произведе НАРОЧНО падането, което точно тези тестове трябва да докажат. Стъбът може.
 *
 * <p>Най-съществените редове са тези за {@link BgViesResult.Status#UNAVAILABLE} и за хвърлящия
 * клиент: те пазят правилото, че липсата на отговор НИКОГА не става обвинение срещу номера.
 */
class BgVatCheckServiceTest {

  /** Стъб, който връща предварително зададено и помни какво са го питали. */
  private static final class StubViesClient implements BgViesClient {
    private final BgViesResult answer;
    private final RuntimeException blowUp;
    final List<String> calls = new ArrayList<>();

    StubViesClient(BgViesResult answer) {
      this.answer = answer;
      this.blowUp = null;
    }

    StubViesClient(RuntimeException blowUp) {
      this.answer = null;
      this.blowUp = blowUp;
    }

    @Override
    public BgViesResult check(String countryCode, String vatNumber) {
      calls.add(countryCode + "|" + vatNumber);
      if (blowUp != null) {
        throw blowUp;
      }
      return answer;
    }
  }

  private static BgVatCheckService with(BgViesResult answer) {
    return new BgVatCheckService(new StubViesClient(answer));
  }

  private static BgVatCheckService neverCalled() {
    return new BgVatCheckService(
        new StubViesClient(new IllegalStateException("VIES не биваше да бъде викан")));
  }

  // ------------------------------------------------------------------ рутиране

  @ParameterizedTest(name = "български номер НЕ вика VIES: {0}")
  @ValueSource(strings = {"BG100000065", "BG1301750000015", "100000065", "BG130175001"})
  void bulgarianNumbersNeverTouchTheNetwork(String vat) {
    // Стъбът хвърля, ако бъде извикан — тоест тестът пада, ако някой пусне БГ номер към мрежата.
    Outcome outcome = neverCalled().check(vat);
    assertNotNull(outcome, vat);
    // И присъдата идва изцяло от контролната цифра, а не от мълчание по подразбиране.
    assertEquals(vat.contains("130175001") ? Level.WARNING : Level.NONE, outcome.getLevel(), vat);
  }

  @ParameterizedTest(name = "чужд номер се изпраща към VIES: {0}")
  @ValueSource(strings = {"DE811907980", "RO12345678", "FR40303265045", "EL123456789"})
  void foreignNumbersAreRoutedToVies(String vat) {
    StubViesClient stub = new StubViesClient(BgViesResult.valid("ACME", "Somewhere"));
    new BgVatCheckService(stub).check(vat);
    assertEquals(1, stub.calls.size(), vat);
    assertTrue(stub.calls.get(0).startsWith(vat.substring(0, 2) + "|"), stub.calls.get(0));
  }

  @Test
  @DisplayName("разлагането подава на VIES кода и номера ПООТДЕЛНО, без интервали")
  void decompositionIsPassedApart() {
    StubViesClient stub = new StubViesClient(BgViesResult.valid(null, null));
    new BgVatCheckService(stub).check("de 811 907 980");
    assertEquals("DE|811907980", stub.calls.get(0));
  }

  @ParameterizedTest(name = "извън обхвата на VIES — нито мрежа, нито съобщение: {0}")
  @ValueSource(strings = {"CH1234567", "US123456789", "GB123456789", "", "   "})
  void nonViesCountriesAreSkippedSilently(String vat) {
    Outcome outcome = neverCalled().check(vat);
    assertEquals(Level.NONE, outcome.getLevel(), vat);
    assertFalse(outcome.hasMessage(), vat);
  }

  // ------------------------------------------------------------------ „-EX“ прескок

  @ParameterizedTest(name = "режим за малки предприятия: бележка, без VIES: {0}")
  @ValueSource(strings = {"BG100000065-EX", "DE811907980-EX", "RO12345678EX"})
  void smallEnterpriseNumbersSkipViesWithANote(String vat) {
    Outcome outcome = neverCalled().check(vat);
    assertEquals(Level.INFO, outcome.getLevel(), vat);
    assertTrue(outcome.getMessage().contains("малки предприятия"), outcome.getMessage());
    // И най-важното: НЕ казва „невалиден“.
    assertFalse(outcome.getMessage().toLowerCase().contains("невалид"), outcome.getMessage());
  }

  // ------------------------------------------------------------------ БГ контролна цифра

  @ParameterizedTest(name = "валиден БГ номер мълчи: {0}")
  @ValueSource(strings = {"BG100000065", "BG130175000", "BG1301750000015", "BG8121140690011"})
  void validBulgarianNumbersAreSilent(String vat) {
    Outcome outcome = neverCalled().check(vat);
    assertEquals(Level.NONE, outcome.getLevel(), vat);
  }

  @ParameterizedTest(name = "невалиден БГ номер предупреждава: {0}")
  @ValueSource(strings = {"BG812114060", "BG130175001", "BG1301750000016", "BG1301750010015"})
  void invalidBulgarianNumbersWarn(String vat) {
    Outcome outcome = neverCalled().check(vat);
    assertEquals(Level.WARNING, outcome.getLevel(), vat);
    assertTrue(outcome.getMessage().contains("контролната цифра"), outcome.getMessage());
  }

  // ------------------------------------------------------------------ присъдите на VIES

  @Test
  @DisplayName("VALID мълчи — потвърждение на всеки запис е шум, който заглушава истинското")
  void validIsSilent() {
    Outcome outcome = with(BgViesResult.valid("ACME GmbH", "Berlin")).check("DE811907980");
    assertEquals(Level.NONE, outcome.getLevel());
    assertNull(outcome.getMessage());
  }

  @Test
  @DisplayName("INVALID казва „не е намерен като активно регистриран“, а НЕ „невалиден номер“")
  void invalidSaysNotRegisteredNotWrongNumber() {
    Outcome outcome = with(BgViesResult.of(BgViesResult.Status.INVALID)).check("DE811907980");
    assertEquals(Level.WARNING, outcome.getLevel());
    // Формулировката е част от правилото: структурно верен номер на дерегистрирала се фирма дава
    // точно INVALID, тоест „невалиден номер“ би пратил човека да поправя нещо вярно.
    assertTrue(outcome.getMessage().contains("активно регистриран"), outcome.getMessage());
    assertFalse(outcome.getMessage().toLowerCase().contains("невалиден"), outcome.getMessage());
  }

  // ------------------------------------------------------------------ GRACEFUL DEGRADATION

  @Test
  @DisplayName("недостъпен VIES → бележка „не можах да проверя“, НЕ предупреждение")
  void unavailableNeverAccusesTheNumber() {
    Outcome outcome = with(BgViesResult.of(BgViesResult.Status.UNAVAILABLE)).check("DE811907980");
    assertEquals(Level.INFO, outcome.getLevel());
    assertTrue(outcome.getMessage().contains("можах да проверя"), outcome.getMessage());
    assertFalse(outcome.getMessage().contains("активно регистриран"), outcome.getMessage());
  }

  @Test
  @DisplayName("ИЗКЛЮЧЕНА проверка мълчи напълно — kill-switch не бива да шуми на всеки запис")
  void disabledIsCompletelySilent() {
    Outcome outcome = with(BgViesResult.of(BgViesResult.Status.DISABLED)).check("DE811907980");
    assertEquals(Level.NONE, outcome.getLevel());
    assertFalse(outcome.hasMessage());
  }

  @Test
  @DisplayName("INVALID_INPUT е информация за формата, не присъда за регистрацията")
  void invalidInputIsInformational() {
    Outcome outcome = with(BgViesResult.of(BgViesResult.Status.INVALID_INPUT)).check("DE811907980");
    assertEquals(Level.INFO, outcome.getLevel());
    assertFalse(outcome.getMessage().contains("активно регистриран"), outcome.getMessage());
  }

  // ------------------------------------------------------------------ клиентът, който се чупи

  @Test
  @DisplayName("Договорът е клиентът да НЕ хвърля — а ако все пак хвърли, вината е негова")
  void aThrowingClientIsAContractViolationNotAVerdict() {
    // Този тест закрепва границата: BgViesRestClient превежда ВСЯКО изключение на UNAVAILABLE
    // (виж неговия catch-all), тоест до услугата не стига хвърлящ клиент. Ако някой някога върже
    // друга реализация, която хвърля, тя ще стигне до контролера — и точно затова контролерът има
    // свой catch-all. Тук само документираме, че услугата нарочно НЕ поглъща чужди грешки:
    // мълчаливото поглъщане на всяко изключение би скрило и истински дефект в рутирането.
    BgVatCheckService service =
        new BgVatCheckService(new StubViesClient(new RuntimeException("мрежата гръмна")));
    try {
      service.check("DE811907980");
      org.junit.jupiter.api.Assertions.fail("очаквано пропагиране към контролера");
    } catch (RuntimeException expected) {
      assertEquals("мрежата гръмна", expected.getMessage());
    }
  }
}
