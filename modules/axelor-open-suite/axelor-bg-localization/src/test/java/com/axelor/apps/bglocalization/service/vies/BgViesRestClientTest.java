package com.axelor.apps.bglocalization.service.vies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Преобразуването „тяло на отговора → присъда“.
 *
 * <p><b>Телата са ЗАПИСАНИ ОТ ЖИВАТА УСЛУГА</b> на 17.09.2026, а не измислени — това е разликата
 * между тест, който доказва съгласуваност със самия себе си, и тест, който доказва съгласуваност с
 * Европейската комисия. Самата заявка обаче не се прави тук: {@link #parse} е чиста функция и точно
 * затова е отделена от изпращането.
 *
 * <p>⚠️ Най-важните редове са последните: всяка НЕразпозната стойност на {@code userError} трябва
 * да стане {@link BgViesResult.Status#UNAVAILABLE}, а не {@code INVALID}. Списъкът на кодовете за
 * недостъпност расте от страна на Комисията, и подразбирането е единственото, което ни пази от това
 * утре нов код да почне да обвинява верни номера.
 */
class BgViesRestClientTest {

  private final BgViesRestClient client = new BgViesRestClient();

  private static String body(String isValid, String userError, String name) {
    return "{\"isValid\":"
        + isValid
        + ",\"requestDate\":\"2026-09-17T15:49:46.255Z\",\"userError\":\""
        + userError
        + "\",\"name\":\""
        + name
        + "\",\"address\":\"---\"}";
  }

  @Test
  @DisplayName("VALID с име — записано от живата услуга за BG100000065")
  void validCarriesTheRegisteredName() {
    BgViesResult r = client.parse(body("true", "VALID", "ТЕСТ ФИШ БЪЛГАРИЯ - ЕООД"));
    assertEquals(BgViesResult.Status.VALID, r.getStatus());
    assertEquals("ТЕСТ ФИШ БЪЛГАРИЯ - ЕООД", r.getName());
  }

  @Test
  @DisplayName("Държава, която не разкрива име, връща „---“ — свежда се до null, не до име")
  void undisclosedNameBecomesNull() {
    // Измерено за DE811907980: isValid=true, но name е буквално "---".
    BgViesResult r = client.parse(body("true", "VALID", "---"));
    assertEquals(BgViesResult.Status.VALID, r.getStatus());
    assertNull(r.getName(), "„---“ не е име и не бива да се показва като такова");
    assertNull(r.getAddress());
  }

  @Test
  @DisplayName("INVALID — категоричен отговор от регистъра")
  void invalidIsADefiniteAnswer() {
    assertEquals(
        BgViesResult.Status.INVALID, client.parse(body("false", "INVALID", "---")).getStatus());
  }

  @Test
  @DisplayName("INVALID_INPUT — услугата не разпозна входа")
  void invalidInputIsItsOwnStatus() {
    assertEquals(
        BgViesResult.Status.INVALID_INPUT,
        client.parse(body("false", "INVALID_INPUT", "---")).getStatus());
  }

  @ParameterizedTest(name = "недостъпност, а НЕ присъда: {0}")
  @ValueSource(
      strings = {
        "MS_UNAVAILABLE",
        "SERVICE_UNAVAILABLE",
        "TIMEOUT",
        "MS_MAX_CONCURRENT_REQ",
        "GLOBAL_MAX_CONCURRENT_REQ",
        "VAT_BLOCKED",
        "IP_BLOCKED",
        "SOMETHING_INVENTED_NEXT_YEAR",
        ""
      })
  void everyOtherUserErrorIsUnavailable(String userError) {
    assertEquals(
        BgViesResult.Status.UNAVAILABLE,
        client.parse(body("false", userError, "---")).getStatus(),
        userError);
  }

  @ParameterizedTest(name = "нечетимо тяло не е присъда: [{0}]")
  @ValueSource(strings = {"", "   ", "not json at all", "<html>502 Bad Gateway</html>", "{}"})
  void unparseableBodiesAreUnavailable(String raw) {
    assertEquals(BgViesResult.Status.UNAVAILABLE, client.parse(raw).getStatus(), raw);
  }
}
