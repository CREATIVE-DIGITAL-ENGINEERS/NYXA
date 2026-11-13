/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Живата проверка в VIES през публичното REST API на Европейската комисия.
 */
package com.axelor.apps.bglocalization.service.vies;

import com.axelor.app.AppSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BgViesClient} през {@code ec.europa.eu/taxation_customs/vies/rest-api}.
 *
 * <p>Услугата е безплатна и БЕЗ ключ или регистрация. Измерено на 17.09.2026: отговор за 0,15–0,68
 * с, а {@code rest-api/check-status} върна 28 обслужвани държави.
 *
 * <p><b>Настройки (axelor-config.properties, по конвенцията {@code AppSettings}):</b>
 *
 * <ul>
 *   <li>{@code bg.vat.vies.enabled} — по подразбиране {@code true}. <b>Убиващ ключ:</b> {@code
 *       false} изключва мрежата изцяло и оставя само офлайн проверката, БЕЗ нов билд. Точно това е
 *       отговорът, ако изходът към {@code ec.europa.eu} се окаже затворен на средата.
 *   <li>{@code bg.vat.vies.baseUrl} — за да може услугата да се насочи другаде без прекомпилиране.
 *   <li>{@code bg.vat.vies.timeoutMs} — по подразбиране 4000. Таванът важи и за свързването, и за
 *       отговора, тоест записът на партньор не може да увисне за повече от толкова.
 * </ul>
 *
 * <p>⚠️ <b>Нито един път оттук не хвърля нагоре.</b> Всяко изключение, всеки не-200 и всеки
 * неразпознат отговор стават {@link BgViesResult.Status#UNAVAILABLE}. Причината е в договора на
 * {@link BgViesClient}: недостъпна услуга НЕ бива да може да отмени запис или да произведе
 * „невалиден".
 */
@Singleton
public class BgViesRestClient implements BgViesClient {

  private static final Logger LOG = LoggerFactory.getLogger(BgViesRestClient.class);

  public static final String KEY_ENABLED = "bg.vat.vies.enabled";
  public static final String KEY_BASE_URL = "bg.vat.vies.baseUrl";
  public static final String KEY_TIMEOUT_MS = "bg.vat.vies.timeoutMs";

  public static final String DEFAULT_BASE_URL =
      "https://ec.europa.eu/taxation_customs/vies/rest-api";
  public static final int DEFAULT_TIMEOUT_MS = 4000;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public BgViesResult check(String countryCode, String vatNumber) {
    if (!isEnabled()) {
      return BgViesResult.of(BgViesResult.Status.DISABLED);
    }
    if (isBlank(countryCode) || isBlank(vatNumber)) {
      return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
    }
    Duration timeout = Duration.ofMillis(timeoutMs());
    try {
      URI uri =
          URI.create(
              String.format(
                  "%s/ms/%s/vat/%s",
                  baseUrl(), countryCode.toUpperCase(Locale.ROOT), vatNumber.trim()));
      HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(timeout)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debug("VIES отговори с HTTP {} за {}{}", response.statusCode(), countryCode, vatNumber);
        return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
      }
      return parse(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
    } catch (Exception e) {
      // Таймаут, DNS, отказан изход, счупен JSON — всичко е „не знам", никога „невалиден".
      LOG.debug("VIES не отговори за {}{}: {}", countryCode, vatNumber, e.toString());
      return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
    }
  }

  /**
   * Тялото на отговора → присъда.
   *
   * <p>⚠️ Водещото поле е {@code isValid}, а {@code userError} се чете само за да се отдели
   * „услугата не разбра входа" от „регистърът каза не". Всяка друга стойност на {@code userError}
   * (MS_UNAVAILABLE, TIMEOUT, SERVICE_UNAVAILABLE, *_MAX_CONCURRENT_REQ, VAT_BLOCKED, IP_BLOCKED) е
   * недостъпност, а не присъда — затова подразбирането при {@code isValid=false} е {@code
   * UNAVAILABLE}, а {@code INVALID} се произнася само при изричния низ.
   */
  protected BgViesResult parse(String body) {
    try {
      JsonNode root = MAPPER.readTree(body);
      String userError = text(root, "userError");
      if (root.path("isValid").asBoolean(false)) {
        return BgViesResult.valid(text(root, "name"), text(root, "address"));
      }
      if ("INVALID".equalsIgnoreCase(userError)) {
        return BgViesResult.of(BgViesResult.Status.INVALID);
      }
      if ("INVALID_INPUT".equalsIgnoreCase(userError)) {
        return BgViesResult.of(BgViesResult.Status.INVALID_INPUT);
      }
      return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
    } catch (Exception e) {
      return BgViesResult.of(BgViesResult.Status.UNAVAILABLE);
    }
  }

  /**
   * ⚠️ Измерено: част от държавите не разкриват име и връщат буквално {@code "---"}. Тук то се
   * свежда до {@code null}, за да не се покаже на потребителя като че ли е име.
   */
  private static String text(JsonNode root, String field) {
    String value = root.path(field).asText("");
    if (value.trim().isEmpty() || "---".equals(value.trim())) {
      return null;
    }
    return value.trim();
  }

  protected boolean isEnabled() {
    return Boolean.parseBoolean(AppSettings.get().get(KEY_ENABLED, "true").trim());
  }

  protected String baseUrl() {
    String url = AppSettings.get().get(KEY_BASE_URL, DEFAULT_BASE_URL);
    url = url == null || url.trim().isEmpty() ? DEFAULT_BASE_URL : url.trim();
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  protected int timeoutMs() {
    int ms = AppSettings.get().getInt(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    return ms > 0 ? ms : DEFAULT_TIMEOUT_MS;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
