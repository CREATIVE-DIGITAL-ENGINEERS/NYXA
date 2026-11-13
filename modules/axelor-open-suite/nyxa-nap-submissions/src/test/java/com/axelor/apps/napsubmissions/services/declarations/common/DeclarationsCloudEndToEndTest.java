package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.QesException;
import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustCloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustConfig;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustDownloadDecryptor;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustEncryptedDownloadFixture;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustSignatureFormat;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustSigner;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustTransport;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.db.User;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЦЕЛИЯТ ОБЛАЧЕН ПЪТ ПРЕЗ PRODUCTION ОРКЕСТРАЦИЯТА, без мрежа и без база.
 *
 * <p>Минава се реалната верига, която стои зад единствения бутон „Подпиши и изпрати“ с метод
 * Evrotrust:
 *
 * <pre>
 *   DeclarationsCloudSignAndSubmitService.start      → EvrotrustCloudQesProvider.startSigning
 *   DeclarationsCloudSignAndSubmitService.status     → EvrotrustCloudQesProvider.pollStatus
 *   DeclarationsCloudSignAndSubmitService.collectAndSubmit
 *        → pollStatus → /document/download → EvrotrustDownloadDecryptor (РЕАЛЕН)
 *        → детачен PKCS#7 + сертификат ОТ САМИЯ подпис
 *        → DeclarationsNapSubmitService.submitSignedTransient
 *        → DeclarationsEvidenceService.verifyTransient → DetachedPkcs7Verifier
 *        → DeclarationsSubmissionService.submit → DeclarationsApiClient (прихванат)
 * </pre>
 *
 * <p>Подменени са точно две неща: HTTP-то на Evrotrust (фалшив {@link EvrotrustTransport}, който
 * обаче връща НАСТОЯЩ шифрован отговор, произведен по документацията) и клиентът на НАП (прихваща
 * заявката вместо да я изпраща). Всичко между тях е production кодът.
 *
 * <p>⚠️ Нито една реална заявка към Evrotrust или НАП.
 */
class DeclarationsCloudEndToEndTest {

  private static final byte[] D1 =
      ("0010010000010001" + "\r\n" + "Д1 тестови запис").getBytes(StandardCharsets.UTF_8);
  private static final byte[] D6 =
      ("0010060000060006" + "\r\n" + "Д6 тестови запис").getBytes(StandardCharsets.UTF_8);

  private final Map<NapArtifact, byte[]> payloadBytes = new IdentityHashMap<>();
  private final TestOnlyQesSigner signer = new TestOnlyQesSigner("ТЕСТ ПОДПИСВАЩ");
  private final CapturingApiClient apiClient = new CapturingApiClient();

  /** Прихваща това, което би тръгнало към НАП. */
  private static final class CapturingApiClient implements DeclarationsApiClient {
    DeclarationsSubmitRequest sent;

    @Override
    public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
      sent = request;
      DeclarationsSubmitResponse response = new DeclarationsSubmitResponse();
      response.documentId = 778899L;
      response.entryNumber = "30E000000777";
      response.entryDate = "2026-04-15";
      return response;
    }

    @Override
    public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
      throw new UnsupportedOperationException("not exercised here");
    }
  }

  /**
   * Фалшив HTTP слой на Evrotrust, който обаче НЕ фалшифицира разшифроването: {@code
   * downloadSignature} произвежда истински шифрован отговор по документацията и го прекарва през
   * РЕАЛНИЯ {@link EvrotrustDownloadDecryptor} с подадения частен ключ.
   */
  private class FakeEvrotrustHttp implements EvrotrustTransport {
    final EvrotrustDownloadDecryptor decryptor = new EvrotrustDownloadDecryptor();
    final Map<String, byte[]> innerZipByTransaction = new LinkedHashMap<>();
    final KeyPair ourKeyPair;
    PrivateKey decryptWith;
    int statusCode = 2;
    boolean processing;
    int nextTransaction = 1;
    final List<String> started = new ArrayList<>();

    FakeEvrotrustHttp(KeyPair ourKeyPair) {
      this.ourKeyPair = ourKeyPair;
      this.decryptWith = ourKeyPair.getPrivate();
    }

    @Override
    public UserCheck userCheck(EvrotrustSigner s) {
      return new UserCheck(true, false, null);
    }

    @Override
    public InitResult initHashSign(InitRequest request) {
      String transactionId = "tx-" + (nextTransaction++);
      started.add(transactionId);
      // Подписваме ТОЧНО съдържанието, чийто хеш е поискан — както би направил доставчикът.
      byte[] payload = payloadForHash(request.getHashBase64());
      innerZipByTransaction.put(transactionId, innerZipFor(payload));
      return new InitResult(transactionId, "thread-1");
    }

    @Override
    public StatusResult status(String transactionId) {
      return new StatusResult(statusCode, processing, null);
    }

    @Override
    public DownloadResult downloadSignature(String transactionId) {
      byte[] innerZip = innerZipByTransaction.get(transactionId);
      byte[] outerZip;
      try {
        outerZip =
            EvrotrustEncryptedDownloadFixture.outerZip(
                innerZip,
                ourKeyPair.getPublic(),
                EvrotrustEncryptedDownloadFixture.symmetricKey('K'),
                EvrotrustEncryptedDownloadFixture.iv("IV0123456789abcd"));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      // РЕАЛНОТО разшифроване и РЕАЛНОТО изваждане на детачния PKCS#7.
      byte[] decrypted = decryptor.decrypt(outerZip, decryptWith);
      return new DownloadResult(
          decryptor.detachedPkcs7From(decrypted), EvrotrustSignatureFormat.DETACHED_PKCS7);
    }

    @Override
    public CertificateResult getCertificate(EvrotrustSigner s) {
      byte[] der = signer.certificateDer();
      return new CertificateResult(
          der,
          com.axelor.apps.napsubmissions.common.signing.evrotrust.FakeEvrotrustTransport.serialHex(
              der));
    }

    /** Вътрешният ZIP, който доставчикът би върнал: детачният подпис на този файл. */
    byte[] innerZipFor(byte[] payload) {
      try {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("signature.p7s", signer.detachedPkcs7(payload));
        return EvrotrustEncryptedDownloadFixture.zip(entries);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Какво е било поискано за подписване — по неговия base64 SHA-256. */
  private byte[] payloadForHash(String hashBase64) {
    for (byte[] candidate : new byte[][] {D1, D6}) {
      byte[] raw;
      try {
        raw = java.security.MessageDigest.getInstance("SHA-256").digest(candidate);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      if (Base64.getEncoder().encodeToString(raw).equals(hashBase64)) {
        return candidate;
      }
    }
    throw new IllegalStateException("unexpected hash requested");
  }

  // --- production оркестрация с две in-memory шевове --------------------------------------------

  private EvrotrustConfig configuredKeyPair() {
    return new EvrotrustConfig() {
      @Override
      public String getPublicKeyBase64() {
        return "cHVibGljLWtleQ==";
      }

      @Override
      public boolean hasPrivateKeySource() {
        return true;
      }
    };
  }

  private DeclarationsCloudSignAndSubmitService service(FakeEvrotrustHttp http) {
    EvrotrustCloudQesProvider provider = new EvrotrustCloudQesProvider(configuredKeyPair(), http);

    DeclarationsEvidenceService evidence =
        new DeclarationsEvidenceService() {
          @Override
          protected byte[] readBytes(NapArtifact artifact, String what) {
            return payloadBytes.get(artifact);
          }
        };
    DeclarationsSubmissionMapper mapper =
        new DeclarationsSubmissionMapper() {
          @Override
          protected byte[] readBytes(NapArtifact artifact) {
            return payloadBytes.get(artifact);
          }
        };
    DeclarationsSubmissionService submissionService =
        new DeclarationsSubmissionService(
            apiClient,
            mapper,
            new DeclarationsResultDecoder(),
            new DeclarationsResultParser(),
            null,
            null,
            null,
            null,
            null) {
          @Override
          protected User currentUser() {
            return null;
          }

          @Override
          protected NapSubmission save(NapSubmission submission) {
            return submission;
          }

          @Override
          protected void log(NapSubmission s, String e, String p, String n, String m) {
            // одитът има свой тест
          }
        };
    NapFilingLock lock =
        new NapFilingLock(null) {
          @Override
          protected NapSubmission filedSibling(NapSubmission submission) {
            return null;
          }
        };
    DeclarationsNapSubmitService submit =
        new DeclarationsNapSubmitService(evidence, submissionService, lock);

    DeclarationsSignAndSubmitService base =
        new DeclarationsSignAndSubmitService(submit, null, null) {
          @Override
          public UserQesProfile recordSigner(NapSubmission submission) {
            return submission.getQesSignerProfile();
          }

          @Override
          protected byte[] readLockedPayload(NapSubmissionFile file) {
            return payloadBytes.get(file.getPayloadArtifact());
          }

          @Override
          protected void logAttempt(NapSubmission submission, String details) {
            // одитът има свой тест; тук се проверява веригата
          }
        };
    return new DeclarationsCloudSignAndSubmitService(base, submit, provider);
  }

  // --- данните ----------------------------------------------------------------------------------

  private NapArtifact lockedPayload(byte[] content) {
    NapArtifact artifact = new NapArtifact();
    artifact.setSha256(RegulatoryDigest.sha256Hex(content));
    artifact.setByteLength((long) content.length);
    artifact.setFinalized(Boolean.TRUE);
    payloadBytes.put(artifact, content);
    return artifact;
  }

  private NapSubmissionFile lockedFile(String docType, byte[] payload, int sequence) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setId((long) (sequence + 1));
    file.setSequence(sequence);
    file.setSubmittedFileName(docType + ".txt");
    file.setFileDocumentTypeSelect(docType);
    file.setFileTypeSelect("txt");
    file.setNumRecords(1);
    file.setPayloadArtifact(lockedPayload(payload));
    file.setQesRequestedSha256(RegulatoryDigest.sha256Hex(payload));
    return file;
  }

  private NapSubmission lockedSubmission(NapSubmissionFile... files) {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));

    UserQesProfile profile = new UserQesProfile();
    profile.setEnabled(Boolean.TRUE);
    profile.setSignerPin("7501011234");
    profile.setSignerPinTypeSelect(PinType.IND_EGN.name());

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);
    submission.setPeriod(period);
    submission.setTaxpayerPin("831234567");
    submission.setTaxpayerPinTypeSelect(PinType.BUS_BULSTAT.name());
    submission.setQesSignerProfile(profile);
    submission.setFiledByInsuranceFund(Boolean.FALSE);
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  private Map<Long, String> transactions(
      List<DeclarationsCloudSignAndSubmitService.StartedSignature> started) {
    Map<Long, String> byFileId = new LinkedHashMap<>();
    for (DeclarationsCloudSignAndSubmitService.StartedSignature one : started) {
      byFileId.put(one.fileId, one.transactionId);
    }
    return byFileId;
  }

  // --- тестовете --------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Д1+Д6 по целия облачен път: шифрованият download се разшифрова, подписите се проверяват"
          + " върху заключените байтове и ЕДНА заявка стига до НАП TEST — без нищо записано")
  void theWholeCloudChainReachesOneNapSubmit() throws Exception {
    KeyPair ours = EvrotrustEncryptedDownloadFixture.rsa2048();
    FakeEvrotrustHttp http = new FakeEvrotrustHttp(ours);
    DeclarationsCloudSignAndSubmitService service = service(http);

    NapSubmissionFile d1 = lockedFile("DEC_1", D1, 0);
    NapSubmissionFile d6 = lockedFile("DEC_6", D6, 1);
    NapSubmission submission = lockedSubmission(d1, d6);

    // 1. старт — по една транзакция на заключен файл
    List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
        service.start(submission);
    assertEquals(2, started.size());
    assertEquals(2, http.started.size());

    // 2. статус — докато човекът не е потвърдил, нищо не тръгва
    http.statusCode = 1;
    Map<String, String> pending = service.status(new ArrayList<>(transactions(started).values()));
    assertTrue(pending.values().stream().allMatch("PENDING"::equals), pending.toString());
    assertNull(apiClient.sent, "до НАП не е тръгнало нищо, докато се чака потвърждение");

    // 3. потвърдено → сваляне, разшифроване, проверка и ЕДНА заявка
    http.statusCode = 2;
    NapSubmission sent = service.collectAndSubmit(submission, transactions(started));

    assertNotNull(apiClient.sent, "заявката е стигнала до клиента на НАП");
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, sent.getSubmissionStatusSelect());
    assertEquals("30E000000777", sent.getEntryNumber());

    DeclarationsSubmitRequest request = apiClient.sent;
    assertEquals(ServiceDocumentType.DEC_1_6, request.serviceDocumentType);
    assertEquals(2, request.files.size());
    // Изпратените байтове са ТОЧНО заключените.
    assertArrayEquals(D1, Base64.getDecoder().decode(request.files.get(0).fileContentBase64));
    assertArrayEquals(D6, Base64.getDecoder().decode(request.files.get(1).fileContentBase64));
    // Сертификатът, с който НАП е уведомена, е ИМЕННО този от подписа — не произволен.
    assertArrayEquals(
        signer.certificateDer(), Base64.getDecoder().decode(request.userSignatureBase64));

    // Нищо от подписа не е останало върху записа.
    for (NapSubmissionFile file : sent.getSubmissionFileList()) {
      assertNull(file.getSignatureArtifact(), "подписът не се записва");
      assertNull(file.getSignerCertificateArtifact(), "сертификатът не се записва");
    }
  }

  @Test
  @DisplayName("Грешен частен ключ: разшифроването отказва — до НАП не тръгва нищо")
  void aWrongPrivateKeyRefusesBeforeAnyNapRequest() throws Exception {
    KeyPair ours = EvrotrustEncryptedDownloadFixture.rsa2048();
    KeyPair other = EvrotrustEncryptedDownloadFixture.rsa2048();
    FakeEvrotrustHttp http = new FakeEvrotrustHttp(ours);
    http.decryptWith = other.getPrivate();
    DeclarationsCloudSignAndSubmitService service = service(http);

    NapSubmission submission = lockedSubmission(lockedFile("DEC_1", D1, 0));
    List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
        service.start(submission);

    assertThrows(
        QesException.class, () -> service.collectAndSubmit(submission, transactions(started)));
    assertNull(apiClient.sent, "до НАП не е тръгнало нищо");
  }

  @Test
  @DisplayName("Подпис върху ДРУГИ байтове: криптографската проверка отказва преди POST")
  void aSignatureOverOtherBytesIsRefusedBeforeThePost() throws Exception {
    KeyPair ours = EvrotrustEncryptedDownloadFixture.rsa2048();
    FakeEvrotrustHttp http =
        new FakeEvrotrustHttp(ours) {
          @Override
          public DownloadResult downloadSignature(String transactionId) {
            // Доставчикът връща валиден PKCS#7, но върху съвсем друго съдържание.
            return new DownloadResult(
                signer.detachedPkcs7("друг файл".getBytes(StandardCharsets.UTF_8)),
                EvrotrustSignatureFormat.DETACHED_PKCS7);
          }
        };
    DeclarationsCloudSignAndSubmitService service = service(http);

    NapSubmission submission = lockedSubmission(lockedFile("DEC_1", D1, 0));
    List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
        service.start(submission);

    assertThrows(
        Exception.class, () -> service.collectAndSubmit(submission, transactions(started)));
    assertNull(apiClient.sent, "до НАП не е тръгнало нищо");
  }

  @Test
  @DisplayName("Повреден контейнер в разшифрования ZIP: отказ, преди да се стигне до НАП")
  void aCorruptSignatureIsRefusedBeforeThePost() throws Exception {
    KeyPair ours = EvrotrustEncryptedDownloadFixture.rsa2048();
    FakeEvrotrustHttp http =
        new FakeEvrotrustHttp(ours) {
          @Override
          byte[] innerZipFor(byte[] payload) {
            try {
              Map<String, byte[]> entries = new LinkedHashMap<>();
              entries.put("signature.p7s", "не е PKCS#7".getBytes(StandardCharsets.UTF_8));
              return EvrotrustEncryptedDownloadFixture.zip(entries);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    DeclarationsCloudSignAndSubmitService service = service(http);

    NapSubmission submission = lockedSubmission(lockedFile("DEC_1", D1, 0));
    List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
        service.start(submission);

    QesException refused =
        assertThrows(
            QesException.class, () -> service.collectAndSubmit(submission, transactions(started)));
    assertTrue(refused.getMessage().contains("detached PKCS#7"), refused.getMessage());
    assertNull(apiClient.sent, "до НАП не е тръгнало нищо");
  }

  @Test
  @DisplayName("Д1 и Д6, подписани от РАЗЛИЧНИ хора: отказ — НАП получава един автор")
  void twoDifferentSignersAreRefused() throws Exception {
    KeyPair ours = EvrotrustEncryptedDownloadFixture.rsa2048();
    TestOnlyQesSigner other = new TestOnlyQesSigner("ДРУГ ПОДПИСВАЩ");
    FakeEvrotrustHttp http =
        new FakeEvrotrustHttp(ours) {
          @Override
          byte[] innerZipFor(byte[] payload) {
            try {
              Map<String, byte[]> entries = new LinkedHashMap<>();
              // Д6 се „подписва“ от друг човек.
              boolean isD6 = Arrays.equals(payload, D6);
              entries.put(
                  "signature.p7s",
                  isD6 ? other.detachedPkcs7(payload) : signer.detachedPkcs7(payload));
              return EvrotrustEncryptedDownloadFixture.zip(entries);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    DeclarationsCloudSignAndSubmitService service = service(http);

    NapSubmission submission =
        lockedSubmission(lockedFile("DEC_1", D1, 0), lockedFile("DEC_6", D6, 1));
    List<DeclarationsCloudSignAndSubmitService.StartedSignature> started =
        service.start(submission);

    Exception refused =
        assertThrows(
            Exception.class, () -> service.collectAndSubmit(submission, transactions(started)));
    // Хваща се още в доставчика: подписалият сертификат не е поисканият (сериен номер).
    assertTrue(refused.getMessage().contains("serial mismatch"), refused.getMessage());
    assertNull(apiClient.sent, "до НАП не е тръгнало нищо");
  }
}
