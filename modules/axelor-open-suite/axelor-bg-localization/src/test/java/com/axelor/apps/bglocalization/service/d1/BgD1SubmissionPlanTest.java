package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsDocumentTypeMatrix;
import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАКАЧАНЕТО КЪМ ТРАНСПОРТА — <b>двата файла, метаданните им, и че НЕ се подава</b>.
 *
 * <p><b>⚠️ КАКВО ТЕЗИ СВИДЕТЕЛИ ДОКАЗВАТ И КАКВО НЕ.</b> Те доказват, че планът за подаване носи
 * точно двата файла, с типовете, които документацията на НАП ИЗРИЧНО потвърждава, с верни броеве
 * записи и в реда, по който маперът ги подрежда. Те НЕ доказват, че НАП приема подаването: за това
 * трябва подпис с КЕП и ТЕСТОВА среда — и двете човешки стъпки, които този клон нарочно не прави.
 *
 * <p><b>⚠️ И НЕ ДОКАЗВАТ, ЧЕ ИМЕТО НА ФАЙЛА НА Д6 Е ВЯРНО.</b> За Д1 то е в заповедта по чл. 8 ал.
 * 5 („EMPL2021.TXT“); за Д6 справката по {@code file_3906} дава структурата на записа, но НЕ и
 * задължително име. Свидетелят закрепва избраната стойност, за да се вижда, че е ИЗБОР — а не че е
 * прочетена отнякъде.
 */
class BgD1SubmissionPlanTest {

  private static final BgD1FileService FILES =
      new BgD1FileService(new BgVatFileWriter(), new BgD1FileAdapter(), new BgD6FileAdapter());

  /** Един запис на Д1 — достатъчно попълнен, за да произведе ред. */
  private static BgD1Record record(String personalId) {
    BgD1Record r = new BgD1Record();
    r.setT2Month("05");
    r.setT3Year("2026");
    r.setT4ObligedPersonCode("831919536");
    r.setT5PersonalId(personalId);
    r.setT51PersonalIdCode("0");
    r.setT6Surname("Петров");
    r.setT7Initials("ИГ");
    r.setT12InsuredTypeCode("01");
    r.setT21InsuranceIncome(new BigDecimal("2000.00"));
    return r;
  }

  private static BgD6Declaration d6() {
    BgD6Declaration d = new BgD6Declaration();
    d.setT1ObligedPersonCode("831919536");
    d.setT2Name("Никса ЕООД");
    d.setT3InsuranceFundMember("0");
    d.setT81PaymentKind("5");
    d.setT10Doo(new BigDecimal("227.80"));
    d.setT14Health(new BigDecimal("160.00"));
    d.setT16Tax(new BigDecimal("176.42"));
    d.setT18PaymentDate(LocalDate.of(2026, 5, 31));
    Period p = new Period();
    p.setFromDate(LocalDate.of(2026, 5, 1));
    p.setToDate(LocalDate.of(2026, 5, 31));
    d.setBgPeriod(p);
    return d;
  }

  private static List<BgD1Record> threeRecords() {
    List<BgD1Record> out = new ArrayList<>();
    out.add(record("7501014567"));
    out.add(record("8202024568"));
    out.add(record("9303034569"));
    return out;
  }

  @Test
  @DisplayName("двата файла, в реда на договора: Д1 = DEC_1 на позиция 0, Д6 = DEC_6 на позиция 1")
  void bothFilesCarryTheirConfirmedType() throws IOException {
    List<BgD1SubmissionPlan.PlannedFile> plan =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), "831919536");

    assertEquals(2, plan.size(), "едно подаване, ДВА файла — точно каквото DEC_1_6 значи");

    assertEquals(0, plan.get(0).getSequence());
    assertEquals(FileDocumentType.DEC_1, plan.get(0).getDocumentType());
    assertEquals(BgD1SubmissionService.D1_FILE_NAME, plan.get(0).getFileName());
    assertEquals("EMPL2021.TXT", plan.get(0).getFileName(), "името е от заповедта по чл. 8 ал. 5");

    assertEquals(1, plan.get(1).getSequence());
    assertEquals(FileDocumentType.DEC_6, plan.get(1).getDocumentType());
    assertEquals(
        BgD1SubmissionService.D6_FILE_NAME,
        plan.get(1).getFileName(),
        "⚠️ ИЗБОР, НЕ ПРОЧИТ: за Д6 заповедта не дава задължително име на файл. Стойността е"
            + " закрепена тук, за да се вижда, че е решение, и се сменя на екрана"
            + " (NapSubmissionFile.submittedFileName), без да се пипа код.");

    assertTrue(
        DeclarationsDocumentTypeMatrix.isConfirmed(
                ServiceDocumentType.DEC_1_6, FileDocumentType.DEC_1)
            && DeclarationsDocumentTypeMatrix.isConfirmed(
                ServiceDocumentType.DEC_1_6, FileDocumentType.DEC_6),
        "⚠️ И ДВЕТЕ КОМБИНАЦИИ СА ИЗРИЧНО ПОТВЪРДЕНИ от документацията."
            + " Матрицата отхвърля всичко непотвърдено — тоест планът не може да мине с измислен тип"
            + " файл, дори да е в enum-а.");
  }

  @Test
  @DisplayName("⚠️ numRecords СА ДВЕ РАЗЛИЧНИ ЧИСЛА: Д1 брои ЛИЦА, Д6 носи ЕДИН ред")
  void theTwoRecordCountsAreDifferentNumbers() throws IOException {
    List<BgD1SubmissionPlan.PlannedFile> plan =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), "831919536");

    assertEquals(3, plan.get(0).getNumRecords(), "три записа = три реда в EMPL2021.TXT");
    assertEquals(
        1,
        plan.get(1).getNumRecords(),
        "⚠️ Д6 Е АГРЕГАТ НА ДРУЖЕСТВОТО — файлът ѝ носи точно ЕДИН запис за месеца. Пренесено едно"
            + " към едно от Д1, числото щеше да е 3: напълно разумно на вид и несъответстващо на нито"
            + " един ред във файла, а НАП сверява точно него.");
  }

  @Test
  @DisplayName("байтовете са ТОЧНО файловете от Етап 6 — CTRL+Z накрая, CP1251, CRLF")
  void thePlannedBytesAreTheSerializedFiles() throws IOException {
    List<BgD1SubmissionPlan.PlannedFile> plan =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), "831919536");

    byte[] d1 = plan.get(0).getBytes();
    assertEquals(
        BgD1FileService.END_OF_FILE,
        d1[d1.length - 1],
        "заповедта иска маркер за край на файл CHR(26); той се долепя върху ВЕЧЕ кодираните байтове");
    assertEquals(
        3,
        countLines(d1),
        "три записа → три реда. ⚠️ Броят СЕ ПРЕБРОЯВА в байтовете, а не се приема наум: numRecords"
            + " и действителните редове са двете страни на едно и също твърдение.");

    byte[] d6 = plan.get(1).getBytes();
    assertEquals(BgD1FileService.END_OF_FILE, d6[d6.length - 1]);
    assertEquals(1, countLines(d6), "Д6 е един ред");
  }

  @Test
  @DisplayName(
      "нула записа е ОТКАЗ — файл с нула реда е ВАЛИДЕН файл, който НАП чете като изявление")
  void anEmptyD1IsRefusedNotSerialized() {
    IllegalStateException empty =
        assertThrows(
            IllegalStateException.class,
            () -> BgD1SubmissionPlan.plan(FILES, Collections.emptyList(), d6(), "831919536"));
    assertTrue(
        empty.getMessage().contains("ВАЛИДЕН файл"),
        "⚠️ Причината се НАЗОВАВА, а не само се отказва: празният файл не изглежда като грешка — той"
            + " изглежда като изявление „за този месец не се декларира нищо“. Съобщение: "
            + empty.getMessage());

    assertThrows(
        IllegalStateException.class,
        () -> BgD1SubmissionPlan.plan(FILES, null, d6(), "831919536"),
        "null записи и празен списък дават един и същи отказ — и двете значат „няма какво да се"
            + " закачи“");
  }

  // ═══ ПОЛЕ 53 — ЕИК НА РАЗРАБОТЧИКА НА СОФТУЕРА, ОТ НАСТРОЙКАТА НА ИНСТАЛАЦИЯТА ═══

  /**
   * ЕИК на разработчика. Различен от ЕИК-а на осигурителя (т. 4: 831919536). ⚠️ Тестова стойност,
   * НЕ подразбиране: в кода настройката е празна.
   */
  private static final String DEVELOPER_EIK = "100000019";

  @Test
  @DisplayName("поле 53 · празната настройка СПИРА Д1 поименно — не се подменя с ЕИК-а на фирмата")
  void anEmptyDeveloperEikIsRefusedByName() {
    for (String empty : Arrays.asList(null, "", "   ")) {
      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), empty),
              "празно „" + empty + "“");
      assertTrue(
          refused.getMessage().contains("поле 53")
              && refused.getMessage().contains("ЕИК на разработчика на софтуера (Д1, поле 53)")
              && refused.getMessage().contains("Българска локализация"),
          "⚠️ Отказът НАЗОВАВА полето и къде се попълва: " + refused.getMessage());
    }
  }

  @Test
  @DisplayName("поле 53 · ЕИК, който не е 9, 10 или 13 цифри, също е отказ — с назованата стойност")
  void aMalformedDeveloperEikIsRefused() {
    for (String bad : Arrays.asList("12345", "87654321X", "12345678901")) {
      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), bad));
      assertTrue(
          refused.getMessage().contains("„" + bad + "“")
              && refused.getMessage().contains("9, 10 или 13 цифри"),
          refused.getMessage());
    }
    for (String good : Arrays.asList(DEVELOPER_EIK, "1234567890", "1234567890123")) {
      assertEquals(null, BgD1SubmissionPlan.sourceFlagRefusal(good), good + " е допустим");
    }
  }

  @Test
  @DisplayName("поле 53 · настроеният ЕИК стига точно до поле 53 и не е ЕИК-ът от т. 4")
  void theDeveloperEikLandsInFieldFiftyThree() throws IOException {
    List<BgD1SubmissionPlan.PlannedFile> plan =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), DEVELOPER_EIK);

    String text = new String(plan.get(0).getBytes(), Charset.forName("windows-1251"));
    int lines = 0;
    for (String line : text.split("\r\n")) {
      if (line.isEmpty() || line.charAt(0) == 0x1A) {
        continue;
      }
      String[] f = line.split(",", -1);
      assertEquals(53, f.length, line);
      assertEquals("\"" + DEVELOPER_EIK + "\"", f[52], "поле 53: " + line);
      assertEquals("\"831919536\"", f[2], "поле 3 остава ЕИК-ът на осигурителя");
      lines++;
    }
    assertEquals(3, lines, "трите реда");
  }

  @Test
  @DisplayName("поле 53 · контролерът чете НАСТРОЙКАТА на инсталацията, не ЕИК-а на дружеството")
  void theControllerReadsTheInstallationSetting() throws IOException {
    String source =
        new String(
            Files.readAllBytes(
                Paths.get("src/main/java/com/axelor/apps/bglocalization/web")
                    .resolve("BgD6DeclarationController.java")),
            StandardCharsets.UTF_8);
    int start = source.indexOf("protected String sourceFlagOf(");
    String body = source.substring(start, source.indexOf("\n  }", start));
    assertTrue(body.contains("getBgSoftwareDeveloperEik()"), body);
    assertTrue(
        !body.contains("getRegistrationCode()"),
        "⚠️ Тук не стои ЕИК-ът на осигурителя — заповедта иска този на разработчика: " + body);
  }

  @Test
  @DisplayName("без Д6 планът е с ЕДИН файл — липсата ѝ НЕ се запълва с празна декларация")
  void aMissingD6IsNotInvented() throws IOException {
    List<BgD1SubmissionPlan.PlannedFile> plan =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), null, "831919536");

    assertEquals(1, plan.size());
    assertEquals(FileDocumentType.DEC_1, plan.get(0).getDocumentType());
    assertFalse(
        plan.stream().anyMatch(f -> f.getDocumentType() == FileDocumentType.DEC_6),
        "⚠️ Мълчаливо добавена празна Д6 би декларирала НУЛА задължения за месеца — изявление, което"
            + " никой не е правил, и то на реда, който НАП сверява срещу платените вноски.");
  }

  @Test
  @DisplayName("⚠️ планът НЕ подава и НЕ подписва — това е свойство на договора, не липса")
  void theplanCarriesNoSignature() throws IOException {
    // Свидетелят е ОТРИЦАТЕЛЕН нарочно: той закрепва, че планираният файл НЯМА поле за подпис.
    // Подписът е ДЕТАЧЕН PKCS#7 върху точните байтове, а частният ключ на КЕП никога не влиза в
    // ERP-а — тоест мястото му е на артефакт, създаден от ЧОВЕК, не на план, сметнат от машина.
    BgD1SubmissionPlan.PlannedFile file =
        BgD1SubmissionPlan.plan(FILES, threeRecords(), d6(), "831919536").get(0);

    boolean hasSignatureAccessor = false;
    for (java.lang.reflect.Method m : file.getClass().getMethods()) {
      String name = m.getName().toLowerCase(java.util.Locale.ROOT);
      if (name.contains("signature") || name.contains("pkcs")) {
        hasSignatureAccessor = true;
      }
    }
    assertFalse(
        hasSignatureAccessor,
        "⚠️ Поле за подпис тук би приканило някой да го попълни от кода. DeclarationsSubmissionMapper"
            + " ИЗИСКВА подписан артефакт за ВСЕКИ файл и отказва без него — тоест липсата на такова"
            + " поле е точката, в която човекът задължително влиза.");
  }

  /** Броят редове в байтовете: CRLF-ите, без маркера за край на файл. */
  private static int countLines(byte[] bytes) {
    int lines = 0;
    for (int i = 0; i < bytes.length - 1; i++) {
      if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
        lines++;
      }
    }
    return lines;
  }
}
