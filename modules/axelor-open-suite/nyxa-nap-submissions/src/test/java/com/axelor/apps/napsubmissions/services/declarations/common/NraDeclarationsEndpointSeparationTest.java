package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiException.Category;
import org.junit.jupiter.api.Test;

/**
 * НАП TEST and НАП PROD must stay cleanly separated. Exercises the pure {@link
 * NraDeclarationsApiClient#resolveEndpoints} — no container, no network.
 *
 * <p>Both environments share the host {@code public-api.nra.bg} and differ only by path, so every
 * check here is on the FULL URL. PROD accepts only the exact official HTTPS endpoint for each role;
 * TEST is refused whenever it would reach a production path by any scheme, port or decoration.
 */
class NraDeclarationsEndpointSeparationTest {

  /** A non-НАП institution gateway — the only legitimate reason to override an endpoint. */
  private static final String GATEWAY_SUBMIT = "https://gateway.example.test/declaration/submit";

  private static final String GATEWAY_RESULT = "https://gateway.example.test/declaration/result";

  /** Refusal path through the institution-gateway override. */
  private static DeclarationsApiException refusedWithOverride(
      boolean prod, String submitOverride, String resultOverride) {
    return assertThrows(
        DeclarationsApiException.class,
        () ->
            NraDeclarationsApiClient.resolveEndpoints(prod, true, submitOverride, resultOverride));
  }

  // --- defaults: the official НАП endpoints, and nothing else ----------------------------------

  @Test
  void testEnvironmentWithoutOverridesUsesTheOfficialTestEndpoints() {
    NraDeclarationsApiClient.Endpoints e =
        NraDeclarationsApiClient.resolveEndpoints(false, false, null, null);
    assertEquals(NraDeclarationsApiClient.TEST_SUBMIT, e.submitUrl);
    assertEquals(NraDeclarationsApiClient.TEST_RESULT, e.resultUrl);
  }

  @Test
  void prodEnvironmentWithoutOverridesUsesTheOfficialProdEndpoints() {
    NraDeclarationsApiClient.Endpoints e =
        NraDeclarationsApiClient.resolveEndpoints(true, false, null, null);
    assertEquals(NraDeclarationsApiClient.PROD_SUBMIT, e.submitUrl);
    assertEquals(NraDeclarationsApiClient.PROD_RESULT, e.resultUrl);
  }

  /** An override that is switched off changes nothing, even when values are present. */
  @Test
  void testEnvironmentIgnoresAnOverrideThatIsNotEnabled() {
    NraDeclarationsApiClient.Endpoints e =
        NraDeclarationsApiClient.resolveEndpoints(false, false, GATEWAY_SUBMIT, GATEWAY_RESULT);
    assertEquals(NraDeclarationsApiClient.TEST_SUBMIT, e.submitUrl);
    assertEquals(NraDeclarationsApiClient.TEST_RESULT, e.resultUrl);
  }

  @Test
  void testEnvironmentHonoursAnEnabledGatewayOverride() {
    NraDeclarationsApiClient.Endpoints e =
        NraDeclarationsApiClient.resolveEndpoints(false, true, GATEWAY_SUBMIT, GATEWAY_RESULT);
    assertEquals(GATEWAY_SUBMIT, e.submitUrl);
    assertEquals(GATEWAY_RESULT, e.resultUrl);
  }

  // --- PROD: only the exact official HTTPS URL, in its own role ---------------------------------

  @Test
  void prodRefusesHttpInsteadOfHttps() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                true,
                "http://public-api.nra.bg/declaration/api-declarations/",
                NraDeclarationsApiClient.PROD_RESULT)
            .getCategory());
  }

  @Test
  void prodRefusesANonStandardPort() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                true,
                "https://public-api.nra.bg:8443/declaration/api-declarations/",
                NraDeclarationsApiClient.PROD_RESULT)
            .getCategory());
  }

  @Test
  void prodRefusesAQueryString() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                true,
                NraDeclarationsApiClient.PROD_SUBMIT + "?debug=1",
                NraDeclarationsApiClient.PROD_RESULT)
            .getCategory());
  }

  /** Both URLs are official, but each is in the OTHER one's role. */
  @Test
  void prodRefusesSubmitAndResultSwapped() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                true, NraDeclarationsApiClient.PROD_RESULT, NraDeclarationsApiClient.PROD_SUBMIT)
            .getCategory());
  }

  @Test
  void prodRefusesAnOverrideThatReachesTheTestPath() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                true, NraDeclarationsApiClient.TEST_SUBMIT, NraDeclarationsApiClient.TEST_RESULT)
            .getCategory());
  }

  @Test
  void prodRefusesANonOfficialEndpointOverride() {
    assertEquals(Category.CONFIG, refusedWithOverride(true, GATEWAY_SUBMIT, null).getCategory());
  }

  // --- TEST: never reach a PROD path on the official host, by any scheme/port -------------------

  @Test
  void testRefusesAnOverrideThatReachesTheRealProdUrl() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                false, NraDeclarationsApiClient.PROD_SUBMIT, NraDeclarationsApiClient.PROD_RESULT)
            .getCategory());
  }

  @Test
  void testRefusesTheProdPathOverHttp() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(false, "http://public-api.nra.bg/declaration/api-declarations/", null)
            .getCategory());
  }

  @Test
  void testRefusesTheProdPathOnANonStandardPort() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(
                false, "https://public-api.nra.bg:8443/declaration/api-declarations/result", null)
            .getCategory());
  }

  /** Even without its trailing slash, the production address is still the production address. */
  @Test
  void testRefusesTheProdPathWithoutItsTrailingSlash() {
    assertEquals(
        Category.CONFIG,
        refusedWithOverride(false, "https://public-api.nra.bg/declaration/api-declarations", null)
            .getCategory());
  }

  /**
   * The guard keys on host + path, so a gateway on its OWN host stays usable in TEST even when its
   * path happens to be production-shaped.
   */
  @Test
  void testAllowsAGatewayOnItsOwnHostWithAProductionShapedPath() {
    NraDeclarationsApiClient.Endpoints e =
        NraDeclarationsApiClient.resolveEndpoints(
            false,
            true,
            "https://gateway.example.test/declaration/api-declarations/",
            "https://gateway.example.test/declaration/api-declarations/result");
    assertEquals("https://gateway.example.test/declaration/api-declarations/", e.submitUrl);
  }
}
