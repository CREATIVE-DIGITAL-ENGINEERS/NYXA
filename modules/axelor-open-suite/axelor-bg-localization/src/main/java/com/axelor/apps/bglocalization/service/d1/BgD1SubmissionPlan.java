package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsDocumentTypeMatrix;
import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * КОИ ФАЙЛА ВЛИЗАТ В ЕДНО ПОДАВАНЕ, В КАКЪВ РЕД И С КАКВИ МЕТАДАННИ — <b>решенията, отделени от
 * записването</b>.
 *
 * <p><b>Защо отделен клас.</b> {@link BgD1SubmissionService} иска {@code MetaFiles} и три
 * хранилища, тоест жива Guice и база. Решенията обаче не искат нищо: „Д1 е {@code DEC_1} и излиза
 * ПЪРВИ“, „Д6 е {@code DEC_6} и носи ЕДИН запис“, „комбинацията трябва да е ПОТВЪРДЕНА от
 * матрицата“. Слети в услугата, те стават проверими само с база — тоест на практика непроверими.
 * Същото разделение като {@code BgD1RecordBuilder} / {@code BgD1Emitter}.
 *
 * <p><b>⚠️ МАТРИЦАТА СЕ ПИТА ТУК, А НЕ СЕ ПРЕДПОЛАГА.</b> {@code DeclarationsDocumentTypeMatrix}
 * пази САМО изрично потвърдените от документацията комбинации и отхвърля всичко останало. Питането
 * ѝ преди записването значи, че непотвърдена комбинация пада ТУК, при планирането, а не в мапера
 * след като вече са създадени артефакти и подаване.
 */
public final class BgD1SubmissionPlan {

  /** Услугата, която двата файла заедно обслужват — „Декларация обр. 1 и обр. 6“. */
  public static final ServiceDocumentType SERVICE = ServiceDocumentType.DEC_1_6;

  private BgD1SubmissionPlan() {}

  /** Един планиран файл: метаданните на договора плюс байтовете. */
  public static final class PlannedFile {

    private final int sequence;
    private final String fileName;
    private final FileDocumentType documentType;
    private final int numRecords;
    private final byte[] bytes;

    PlannedFile(
        int sequence,
        String fileName,
        FileDocumentType documentType,
        int numRecords,
        byte[] bytes) {
      this.sequence = sequence;
      this.fileName = fileName;
      this.documentType = documentType;
      this.numRecords = numRecords;
      this.bytes = bytes;
    }

    /** Позицията в подаването. ⚠️ Маперът подрежда файловете точно по нея. */
    public int getSequence() {
      return sequence;
    }

    public String getFileName() {
      return fileName;
    }

    public FileDocumentType getDocumentType() {
      return documentType;
    }

    /** Броят РЕДОВЕ в този файл — различно число за Д1 и за Д6. */
    public int getNumRecords() {
      return numRecords;
    }

    /** Байтовете, както излизат от {@link BgD1FileService} — CP1251, CRLF, CTRL+Z. */
    public byte[] getBytes() {
      return bytes;
    }
  }

  /**
   * Планът за едно подаване: Д1 на позиция 0, Д6 на позиция 1.
   *
   * <p><b>⚠️ РЕДЪТ Е ЧАСТ ОТ ДОГОВОРА, не подредба за екрана.</b> {@code
   * DeclarationsSubmissionMapper#build} сортира {@code files} по {@code sequence} и ги подава на
   * API-то в този ред.
   *
   * <p><b>⚠️ И ДВЕТЕ NUMRECORDS СЕ СМЯТАТ ПООТДЕЛНО.</b> Д1 носи по един запис на ЛИЦЕ; файлът на
   * Д6 носи точно ЕДИН запис, защото декларацията е агрегат на ДРУЖЕСТВОТО. Пренесено едно към
   * едно, {@code numRecords} на Д6 щеше да е броят на лицата — число, което изглежда напълно
   * разумно и не отговаря на нито един ред във файла, а НАП сверява точно него.
   *
   * @param records записите на Д1; празен списък е ОТКАЗ, не празен файл
   * @param d6 декларацията обр. 6, или {@code null} за подаване само с Д1
   * @param sourceFlag поле 53 на Д1
   */
  /**
   * ПОЛЕ 53 НА Д1 — ЕИК НА РАЗРАБОТЧИКА НА СОФТУЕРА: причина за отказ, или {@code null}.
   *
   * <p>Заповедта по чл. 8 ал. 5: „ЕИК по БУЛСТАТ или ЕИК по Търговския регистър на разработчика на
   * софтуер за подаване на данните“, {@code Character} с размер 9, 10 или 13. Стойността е
   * НАСТРОЙКА НА ИНСТАЛАЦИЯТА ({@code AppBgLocalization.bgSoftwareDeveloperEik}), не на
   * дружеството, и НЕ се подменя с нищо друго: празна или неправилна, Д1 не се генерира.
   */
  public static String sourceFlagRefusal(String sourceFlag) {
    String v = sourceFlag == null ? "" : sourceFlag.trim();
    if (v.isEmpty()) {
      return "Декларация обр. 1 не може да се генерира: поле 53 („флаг за източник“) е ЕИК на"
          + " РАЗРАБОТЧИКА на софтуера, а настройката „ЕИК на разработчика на софтуера (Д1, поле"
          + " 53)“ е празна. Попълни я в Администрация › Приложения › Българска локализация ›"
          + " Настройки › „Декларация обр. 1 — софтуер, създал файла“ и повтори.";
    }
    if (!v.matches("\\d{9}|\\d{10}|\\d{13}")) {
      return "Декларация обр. 1 не може да се генерира: настройката „ЕИК на разработчика на"
          + " софтуера (Д1, поле 53)“ е „"
          + v
          + "“, а заповедта иска ЕИК от 9, 10 или 13 цифри. Поправи я и повтори.";
    }
    return null;
  }

  public static List<PlannedFile> plan(
      BgD1FileService fileService, List<BgD1Record> records, BgD6Declaration d6, String sourceFlag)
      throws IOException {
    return plan(fileService, records, d6, BgD6CompensationLedger.none(), sourceFlag);
  }

  /** Същото, с обезщетенията по вид 27 на месеца — свои блокове в Д6 ({@link BgD6Blocks}). */
  public static List<PlannedFile> plan(
      BgD1FileService fileService,
      List<BgD1Record> records,
      BgD6Declaration d6,
      BgD6CompensationLedger ledger,
      String sourceFlag)
      throws IOException {

    // ⚠️ ПЪРВО ПОЛЕ 53, ПРЕДИ ВСИЧКО ДРУГО: без него файлът не може да е верен, каквито и да са
    // записите. Дотук тук стоеше ЕИК-ът на осигурителя (т. 4) — стойност, която заповедта не дава.
    String sourceFlagRefusal = sourceFlagRefusal(sourceFlag);
    if (sourceFlagRefusal != null) {
      throw new IllegalStateException(sourceFlagRefusal);
    }

    int d1Records = count(records);
    if (d1Records == 0) {
      // ⚠️ ОТКАЗ, А НЕ ФАЙЛ С НУЛА РЕДА. Празният файл е ВАЛИДЕН файл: НАП го приема като „нищо за
      // деклариране“ за целия месец. Тоест мълчаливо подадена празна Д1 не изглежда като грешка —
      // тя изглежда като изявление.
      throw new IllegalStateException(
          "Декларация обр. 1 няма нито един запис — няма какво да се закачи. ⚠️ Файл с НУЛА реда е"
              + " ВАЛИДЕН файл: НАП го чете като „за този месец не се декларира нищо“. Генерирай"
              + " записите първо.");
    }

    List<PlannedFile> out = new ArrayList<>(2);
    out.add(
        planned(
            0,
            BgD1SubmissionService.D1_FILE_NAME,
            FileDocumentType.DEC_1,
            d1Records,
            fileService.d1Bytes(records, sourceFlag)));

    // ⚠️ Д6 НЕ Е ЗАДЪЛЖИТЕЛНА ТУК. Подаване само с Д1 е потвърдена комбинация; дали НАП го приема
    // без
    // Д6 е въпрос към ТЕСТОВОТО подаване, тоест към човек. Мълчаливо добавена празна Д6 би
    // декларирала НУЛА задължения за месеца — изявление, което никой не е правил.
    if (d6 != null) {
      out.add(
          planned(
              1,
              BgD1SubmissionService.D6_FILE_NAME,
              FileDocumentType.DEC_6,
              1,
              fileService.d6Bytes(d6, ledger, sourceFlag)));
    }
    return out;
  }

  /**
   * Един ред от плана — с ПИТАНЕ на матрицата.
   *
   * <p>⚠️ Непотвърдена комбинация пада ТУК, преди да са създадени артефакти и подаване. Пусната
   * нататък, тя пада в мапера — тоест след като базата вече носи следа от опит, който не може да
   * успее.
   */
  private static PlannedFile planned(
      int sequence, String fileName, FileDocumentType type, int numRecords, byte[] bytes) {
    if (!DeclarationsDocumentTypeMatrix.isConfirmed(SERVICE, type)) {
      throw new IllegalStateException(
          "комбинацията "
              + SERVICE
              + " / "
              + type
              + " не е сред ИЗРИЧНО ПОТВЪРДЕНИТЕ от документацията на НАП"
              + " (DeclarationsDocumentTypeMatrix). Отказът е нарочен: матрицата не се допълва по"
              + " догадка.");
    }
    return new PlannedFile(sequence, fileName, type, numRecords, bytes);
  }

  /** Броят на НЕПРАЗНИТЕ записи — тоест броят редове, които файлът наистина ще носи. */
  static int count(List<BgD1Record> records) {
    if (records == null) {
      return 0;
    }
    int count = 0;
    for (BgD1Record record : records) {
      if (record != null) {
        count++;
      }
    }
    return count;
  }
}
