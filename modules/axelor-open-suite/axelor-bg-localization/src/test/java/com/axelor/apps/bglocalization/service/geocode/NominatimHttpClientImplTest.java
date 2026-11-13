package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.studio.db.AppBgLocalization;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.http.HttpRequest;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Nominatim User-Agent is a compile-time constant, and these tests exist to keep it one.
 *
 * <p>It is not read from {@code AppBgLocalization.nominatimUserAgent}: an unset value there would
 * make the client refuse every geocode call, so a settings screen could silently disable geocoding.
 * The header is fixed in code and no configuration, user preference or database row can influence
 * it.
 *
 * <p>Nothing here touches the network: {@code buildRequest} produces the very {@link HttpRequest}
 * {@code search()} would send, so the header can be read straight off it without sending anything.
 */
class NominatimHttpClientImplTest {

  private static final String EXPECTED_USER_AGENT = "NyxaERP/0.5 (+https://github.com/)";

  @Test
  @DisplayName("the request carries exactly the hardcoded User-Agent")
  void requestCarriesTheHardcodedUserAgent() {
    HttpRequest request = new NominatimHttpClientImpl().buildRequest("София, България", "bg");

    assertEquals(
        EXPECTED_USER_AGENT,
        request.headers().firstValue("User-Agent").orElse(null),
        "The User-Agent actually attached to the outgoing request must be the hardcoded constant.");
  }

  /**
   * A blank {@code AppBgLocalization.nominatimUserAgent} must not stop the client from calling
   * Nominatim. The row is built here and deliberately never handed to the client -- it cannot be,
   * because the client accepts none. That is the assertion.
   */
  @Test
  @DisplayName("a blank AppBgLocalization value cannot affect the header")
  void blankAppConfigCannotAffectTheHeader() {
    AppBgLocalization blank = new AppBgLocalization();
    blank.setNominatimUserAgent("   ");

    HttpRequest request = new NominatimHttpClientImpl().buildRequest("София, България", "bg");

    assertEquals(EXPECTED_USER_AGENT, request.headers().firstValue("User-Agent").orElse(null));
    assertTrue(
        blank.getNominatimUserAgent().trim().isEmpty(),
        "Control: the app config row really is blank, so this test would catch a client that "
            + "started reading it again and fell back to refusing the call.");
  }

  /**
   * A constructor parameter or field of the repository type would let Guice inject it and make the
   * header depend on a database row, while the two tests above still passed on a client built by
   * hand.
   */
  @Test
  @DisplayName("the client has no dependency on the app configuration")
  void clientHasNoAppConfigurationDependency() {
    Constructor<?>[] constructors = NominatimHttpClientImpl.class.getDeclaredConstructors();
    assertEquals(1, constructors.length, "Expected a single constructor.");
    assertEquals(
        0,
        constructors[0].getParameterCount(),
        "The client must take no injected configuration: "
            + Arrays.toString(constructors[0].getParameterTypes()));

    for (Field field : NominatimHttpClientImpl.class.getDeclaredFields()) {
      assertFalse(
          field.getType().getName().contains("AppBgLocalization"),
          "Field " + field.getName() + " reintroduces the app configuration dependency.");
    }
  }

  /**
   * The constant is interpolated into a header. A line break or control character in it would let
   * it become a second header; with the value hardcoded, the literal itself is the thing to check.
   */
  @Test
  @DisplayName("the constant cannot be split into a second header")
  void constantContainsNoControlCharacters() {
    for (char c : EXPECTED_USER_AGENT.toCharArray()) {
      assertFalse(
          Character.isISOControl(c),
          "Control character 0x" + Integer.toHexString(c) + " in the User-Agent constant.");
    }
  }

  @Test
  @DisplayName("a blank query still short-circuits before any request is built")
  void blankQueryReturnsEmpty() {
    NominatimHttpClientImpl client = new NominatimHttpClientImpl();
    assertTrue(client.search("", "bg").isEmpty());
    assertTrue(client.search(null, "bg").isEmpty());
  }
}
