package com.axelor.apps.bglocalization.service.zddfl;

/**
 * ЕДИН ПЛАНИРАН ФАЙЛ — име, брой записи и байтове, преди да е пипната базата.
 *
 * <p><b>⚠️ НЯМА {@code FileDocumentType}, И ТОВА Е ОТКАЗ, А НЕ ПРОПУСК.</b> {@code
 * BgD1SubmissionPlan.PlannedFile} носи своя ({@code DEC_1} / {@code DEC_6}) и го сверява срещу
 * {@code DeclarationsDocumentTypeMatrix}. Enum-ът {@code FileDocumentType} обаче е ФИКСИРАН от
 * договора на Declarations API и познава само Д1/Д6, високорисковите и ЕТЗ — НИТО ЕДНА стойност за
 * образец 4001 или 5-2001. Сложена която и да е от наличните, файлът щеше да тръгне към услугата на
 * ДРУГА декларация. Затова {@code NapSubmissionFile.fileDocumentTypeSelect} остава ПРАЗЕН — точно
 * както {@code serviceDocumentTypeSelect} остава празен днес.
 *
 * <p>⚠️ {@code numRecords} се смята от съдържанието, а не от байтовете: НАП сверява броя ЗАПИСИ, а
 * колко реда е един запис е част от формата.
 */
public final class BgDeclarationPlannedFile {

  private final int sequence;
  private final String fileName;
  private final int numRecords;
  private final byte[] bytes;

  public BgDeclarationPlannedFile(int sequence, String fileName, int numRecords, byte[] bytes) {
    this.sequence = sequence;
    this.fileName = fileName;
    this.numRecords = numRecords;
    this.bytes = bytes == null ? new byte[0] : bytes.clone();
  }

  /** Позицията в подаването. */
  public int getSequence() {
    return sequence;
  }

  public String getFileName() {
    return fileName;
  }

  /** Броят ЗАПИСИ, които файлът носи. */
  public int getNumRecords() {
    return numRecords;
  }

  /** Байтовете, както излизат от писача. */
  public byte[] getBytes() {
    return bytes.clone();
  }
}
