package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deterministic in-memory {@link EvrotrustTransport} for tests — no HMAC, no network. Each response
 * is scriptable; {@link #initRequest} and {@link #statusCalls} capture what the provider sent so
 * the tests can assert the request was built from the exact bound hash and signer.
 */
public class FakeEvrotrustTransport implements EvrotrustTransport {

  public UserCheck userCheck = new UserCheck(true, false, null);
  public InitResult initResult = new InitResult("tx-1", "thread-1");

  /** A queue of status results consumed one per poll; the last one repeats when drained. */
  public final Deque<StatusResult> statuses = new ArrayDeque<>();

  /**
   * ⚠️ ИСТИНСКИ детачен PKCS#7, не плейсхолдър: доставчикът вече се разчита (и сертификатът на
   * подписващия се вади ОТ него), затова подменен низ тук би минавал за подпис, какъвто никога не е
   * бил.
   */
  public static final TestOnlyQesSigner SIGNER = new TestOnlyQesSigner("ФАЛШИВ ТРАНСПОРТ");

  public static final byte[] SIGNED_PAYLOAD =
      "Д1/Д6 locked bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  public DownloadResult download =
      new DownloadResult(
          SIGNER.detachedPkcs7(SIGNED_PAYLOAD), EvrotrustSignatureFormat.DETACHED_PKCS7);

  /**
   * Отговорът на {@code /user/certificate/get}: по подразбиране сертификатът на {@link #SIGNER} и
   * неговият сериен номер (hex) — взима се преди {@code /document/hash/online}.
   */
  public CertificateResult certificate =
      new CertificateResult(SIGNER.certificateDer(), serialHex(SIGNER.certificateDer()));

  /** Hex сериен номер на DER сертификат — както би го върнал доставчикът. */
  public static String serialHex(byte[] der) {
    try {
      return ((java.security.cert.X509Certificate)
              java.security.cert.CertificateFactory.getInstance("X.509")
                  .generateCertificate(new java.io.ByteArrayInputStream(der)))
          .getSerialNumber()
          .toString(16)
          .toUpperCase();
    } catch (java.security.cert.CertificateException e) {
      throw new IllegalStateException(e);
    }
  }

  public InitRequest initRequest;
  public EvrotrustSigner certificateSigner;
  public int statusCalls;
  public int downloadCalls;
  public int certificateCalls;

  @Override
  public UserCheck userCheck(EvrotrustSigner signer) {
    return userCheck;
  }

  @Override
  public InitResult initHashSign(InitRequest request) {
    this.initRequest = request;
    return initResult;
  }

  @Override
  public StatusResult status(String transactionId) {
    statusCalls++;
    if (statuses.size() > 1) {
      return statuses.pollFirst();
    }
    return statuses.peekFirst();
  }

  @Override
  public DownloadResult downloadSignature(String transactionId) {
    downloadCalls++;
    return download;
  }

  @Override
  public CertificateResult getCertificate(EvrotrustSigner signer) {
    certificateCalls++;
    this.certificateSigner = signer;
    return certificate;
  }

  public FakeEvrotrustTransport withStatus(int code, boolean processing, String reason) {
    statuses.addLast(new StatusResult(code, processing, reason));
    return this;
  }
}
