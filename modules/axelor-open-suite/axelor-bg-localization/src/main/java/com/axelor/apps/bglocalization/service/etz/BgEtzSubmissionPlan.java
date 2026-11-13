package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsDocumentTypeMatrix;
import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import java.util.Collections;
import java.util.List;

/**
 * КОЙ ФАЙЛ ВЛИЗА В ЕДНО ЕТЗ ПОДАВАНЕ И С КАКВИ МЕТАДАННИ — <b>решенията, отделени от
 * записването</b>.
 *
 * <p>Същото разделение като {@code BgD1SubmissionPlan}: планът не иска нито Guice, нито база, тоест
 * правилата му се проверяват с обикновен тест, докато {@link BgEtzSubmissionService} иска и двете.
 *
 * <p><b>⚠️ ЕДИН ФАЙЛ, НЕ ДВА — И ТОВА Е РАЗЛИКА С Д1/Д6, НЕ ПРОПУСК.</b> {@code DEC_1_6} е услуга с
 * ДВА файла (обр. 1 и обр. 6) и редът им е част от договора. При ЕТЗ услугата и файлът съвпадат по
 * име и са едно към едно: {@code DEC_ETZ62} носи {@code DEC_ETZ62}, {@code DEC_ETZ123} носи {@code
 * DEC_ETZ123} — точно както матрицата ги е записала.
 *
 * <p><b>⚠️ NUMRECORDS НЕ СЕ ПОПЪЛВА.</b> Маперът ({@code DeclarationsSubmissionMapper}) изисква
 * {@code numRecords} САМО за {@code DEC_1} и {@code DEC_6} — проверено в кода му, не предположено.
 * Попълнено „за всеки случай“ тук, то би било число, което никой не сверява, и би създало вид, че е
 * част от договора.
 *
 * <p><b>⚠️ И НЕ СЕ ПОПЪЛВАТ ПЕРИОД, ГОДИНА И ФОНД.</b> По същата причина: {@code taxPeriodFrom},
 * {@code taxPeriodTo}, {@code year} и {@code insuranceFund} са задължителни само за {@code
 * DEC_1_6}. ЕТЗ записът е за СЪБИТИЕ с дата, не за осигурителен период — период върху него би бил
 * измислен реквизит.
 */
public final class BgEtzSubmissionPlan {

  private BgEtzSubmissionPlan() {}

  /** Разширението на файла — ЕТЗ приема САМО xml (изрично в спецификацията на API-то). */
  public static final String FILE_TYPE_XML = "xml";

  /** Един планиран файл: метаданните на договора плюс байтовете. */
  public static final class PlannedFile {

    private final int sequence;
    private final String fileName;
    private final FileDocumentType documentType;
    private final byte[] bytes;

    PlannedFile(int sequence, String fileName, FileDocumentType documentType, byte[] bytes) {
      this.sequence = sequence;
      this.fileName = fileName;
      this.documentType = documentType;
      this.bytes = bytes;
    }

    public int getSequence() {
      return sequence;
    }

    public String getFileName() {
      return fileName;
    }

    public FileDocumentType getDocumentType() {
      return documentType;
    }

    /** Байтовете — WINDOWS-1251, както излизат от {@link BgEtzXmlWriter#toBytes()}. */
    public byte[] getBytes() {
      return bytes;
    }
  }

  /**
   * Планът за едно ЕТЗ подаване.
   *
   * @param kind събитието — то решава и услугата, и типа на файла
   * @param bytes готовите байтове на записа
   * @param eik ЕИК за името на файла
   * @throws IllegalStateException при празни байтове или непотвърдена комбинация
   */
  public static List<PlannedFile> plan(BgEtzEventKind kind, byte[] bytes, String eik) {
    if (kind == null) {
      throw new IllegalStateException(
          "няма вид събитие — без него не се знае коя услуга се ползва");
    }
    if (bytes == null || bytes.length == 0) {
      // ⚠️ ОТКАЗ, А НЕ ПРАЗЕН ФАЙЛ. Празен XML не е „нищо за деклариране“ — той е файл, който пада
      // на структурна проверка и оставя след себе си входящ номер.
      throw new IllegalStateException(
          "ЕТЗ записът е празен — няма какво да се закачи. Емисията трябваше да откаже преди това"
              + " (BgEtzEmitter), а не да върне нула байта.");
    }

    ServiceDocumentType service = kind.getService();
    FileDocumentType file = kind.getFile();
    if (!DeclarationsDocumentTypeMatrix.isConfirmed(service, file)) {
      throw new IllegalStateException(
          "комбинацията "
              + service
              + " / "
              + file
              + " не е сред ИЗРИЧНО ПОТВЪРДЕНИТЕ от документацията на НАП"
              + " (DeclarationsDocumentTypeMatrix). Отказът е нарочен: матрицата не се допълва по"
              + " догадка.");
    }
    return Collections.singletonList(new PlannedFile(0, fileNameFor(kind, eik), file, bytes));
  }

  /**
   * Името на файла — <b>по препоръката на приложенията, с ЕИК в него</b>.
   *
   * <p>Приложение № 1: „Препоръчително наименование на файл може да бъде {@code ETZxxxxxxxxx.XML},
   * където xxx е ЕИК на работодателя“. Приложение № 2 иска {@code ETZ123_xxxxxxxxx.XML}, и там
   * ЕИК-ът е на <b>новия</b> работодател — този, който ПРИЕМА работниците.
   *
   * <p><b>⚠️ „ПРЕПОРЪЧИТЕЛНО“ НЕ Е „ПРОИЗВОЛНО“.</b> Името носи ЕИК, а приложенията изискват един
   * файл да съдържа данни само за един ЕИК — тоест името е и проверка за човека, че не е смесил два
   * работодателя в един файл. Затова ЕИК-ът се подава, а не се пропуска „за простота“.
   *
   * @param eik ЕИК на работодателя; при чл. 123 — на НОВИЯ
   */
  public static String fileNameFor(BgEtzEventKind kind, String eik) {
    String suffix = eik == null ? "" : eik.trim();
    return (kind == BgEtzEventKind.EMPLOYER_CHANGE ? "ETZ123_" : "ETZ") + suffix + ".XML";
  }
}
