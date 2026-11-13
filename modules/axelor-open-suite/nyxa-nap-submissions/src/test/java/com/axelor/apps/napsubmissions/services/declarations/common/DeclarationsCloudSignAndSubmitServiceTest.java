package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderIds;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.QesSignature;
import com.axelor.apps.napsubmissions.common.signing.QesSigningRequest;
import com.axelor.apps.napsubmissions.common.signing.QesSigningSession;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatus;
import com.axelor.apps.napsubmissions.common.signing.QesSigningStatusResponse;
import com.axelor.apps.napsubmissions.common.signing.UnconfiguredCloudQesProvider;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗИТЕ НА ОБЛАЧНИЯ ПЪТ, БЕЗ БАЗА И БЕЗ МРЕЖА.
 *
 * <p>Тук се проверява точно това, което решава дали изобщо ще се стартира подписване при доставчика
 * (профилът е за АКТИВНИЯ доставчик и носи неговата референция) и какво става, когато доставчикът
 * прекрати заявката. Целият останал път — криптографската проверка на детачния PKCS#7 върху точните
 * заключени байтове и единственото подаване — се покрива от {@code
 * DeclarationsSignAndSubmitEndToEndTest}, защото е общ за двата метода.
 */
class DeclarationsCloudSignAndSubmitServiceTest {

  /** Доставчик, който не прави нищо друго освен да върне предварително зададен статус. */
  private static final class FakeProvider implements CloudQesProvider {
    private final QesSigningStatusResponse response;

    FakeProvider(QesSigningStatusResponse response) {
      this.response = response;
    }

    @Override
    public String getProviderId() {
      return CloudQesProviderIds.EVROTRUST;
    }

    @Override
    public QesSigningSession startSigning(QesSigningRequest request) {
      return new QesSigningSession(
          "tx-1", QesSigningStatus.PENDING, request.getPayloadSha256Hex(), Instant.now());
    }

    @Override
    public QesSigningStatusResponse pollStatus(String externalRequestId) {
      return response;
    }
  }

  private DeclarationsCloudSignAndSubmitService service(QesSigningStatusResponse response) {
    return new DeclarationsCloudSignAndSubmitService(null, null, new FakeProvider(response));
  }

  private UserQesProfile profile(String pin, String pinType) {
    UserQesProfile profile = new UserQesProfile();
    profile.setSignerPin(pin);
    profile.setSignerPinTypeSelect(pinType);
    return profile;
  }

  @Test
  @DisplayName("Облачен доставчик не е конфигуриран → ясен отказ, а не вина към профила")
  void anUnconfiguredProviderRefusesClearlyInsteadOfBlamingTheProfile() {
    DeclarationsCloudSignAndSubmitService service =
        new DeclarationsCloudSignAndSubmitService(null, null, new UnconfiguredCloudQesProvider());
    QesException refused =
        assertThrows(
            QesException.class,
            () -> service.requireCloudProfile(profile("1234567890", "IND_EGN")));
    assertEquals(QesException.Category.CONFIG, refused.getCategory());
    assertTrue(refused.getMessage().contains("not configured"), refused.getMessage());
  }

  @Test
  @DisplayName("Без записано ЕГН → отказ преди всякаква заявка")
  void aProfileWithoutEgnIsRefused() {
    DeclarationsCloudSignAndSubmitService service =
        service(QesSigningStatusResponse.pending("tx-1"));
    QesException refused =
        assertThrows(
            QesException.class, () -> service.requireCloudProfile(profile("  ", "IND_EGN")));
    assertEquals(QesException.Category.CONFIG, refused.getCategory());
  }

  @Test
  @DisplayName("Референцията се извежда от ЕГН — без доставчик/референция/сериен номер в профила")
  void theReferenceIsDerivedFromTheSavedEgnOnly() {
    DeclarationsCloudSignAndSubmitService service =
        service(QesSigningStatusResponse.pending("tx-1"));
    assertEquals("egn=1234567890", service.requireCloudProfile(profile(" 1234567890 ", "IND_EGN")));
  }

  @Test
  @DisplayName("Докато подписващият не е потвърдил, статусът е pending — и нищо не се подава")
  void pendingIsReportedWhileTheSignerHasNotConfirmed() {
    Map<String, String> statuses =
        service(QesSigningStatusResponse.pending("tx-1")).status(Arrays.asList("tx-1"));
    assertEquals(QesSigningStatus.PENDING.name(), statuses.get("tx-1"));
  }

  @Test
  @DisplayName("Отказ/изтекъл срок от доставчика → ясен отказ, а не мълчаливо чакане")
  void aTerminalFailureFromTheProviderIsRefusedLoudly() {
    DeclarationsCloudSignAndSubmitService service =
        service(QesSigningStatusResponse.rejected("tx-1", "signer declined"));
    QesException refused =
        assertThrows(QesException.class, () -> service.status(Arrays.asList("tx-1")));
    assertTrue(refused.getMessage().contains("signer declined"), refused.getMessage());
  }

  @Test
  @DisplayName("Завършило подписване → статусът е COMPLETED и подаването може да продължи")
  void aCompletedSignatureIsReportedAsCompleted() {
    QesSignature signature =
        new QesSignature(
            "pkcs7".getBytes(),
            "cert".getBytes(),
            CloudQesProviderIds.EVROTRUST,
            "tx-1",
            Instant.now(),
            "abc");
    Map<String, String> statuses =
        service(QesSigningStatusResponse.completed("tx-1", signature))
            .status(Arrays.asList("tx-1"));
    assertEquals(QesSigningStatus.COMPLETED.name(), statuses.get("tx-1"));
  }
}
