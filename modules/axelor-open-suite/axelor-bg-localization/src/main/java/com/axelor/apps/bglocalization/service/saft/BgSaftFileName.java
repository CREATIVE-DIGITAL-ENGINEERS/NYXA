package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ИМЕТО НА ФАЙЛА ПО ПРИЛ. 3 Т. I.2 — по него електронната услуга познава вида.
 *
 * <p><b>⚠️ ГРЕШНО ИМЕ = НЕПРИЕТ ФАЙЛ.</b> Прил. 3 т. II: „Файлът не се приема, ако … наименованието
 * на файла не отговаря на конвенцията за съответния вид файл (месечен, годишен, при поискване)“. QA
 * с. 73: HeaderComment е незадължителен, видът се чете от името. Затова името е чиста функция с
 * ГРЕШКА, а не низ, сглобен на мястото на подаването.
 *
 * <ul>
 *   <li>месечен — {@code ЕИК_MM_YYYY.xml};
 *   <li>годишен — {@code ЕИК_YYYY.xml};
 *   <li>при поискване — {@code ЕИК_DD_MM_YYYY_DD_MM_YYYY.xml}, началната и крайната дата.
 * </ul>
 *
 * <p>Шаблоните стоят и в {@link BgSaftXsd#FILE_NAME_MONTHLY} и съседите му.
 *
 * <p><b>⚠️ САМО 9-ЦИФРЕН ЕИК — 13-ЦИФРЕНИЯТ Е ГРЕШКА, НЕ „ДА СЕ ПОТВЪРДИ“.</b> Шаблонът на Прил. 3
 * т. I.2 е с девет позиции. 13-цифреният номер по БУЛСТАТ е на клон, а задължено лице е търговецът:
 * „клонът води търговски книги като самостоятелен търговец, без да съставя отделен баланс …
 * задължение за подаване … има търговецът“ (QA II.1.10, с. 8–10). Тоест данните на клона влизат във
 * файла на търговеца, под неговия ЕИК.
 */
public final class BgSaftFileName {

  /** ЕИК на търговеца — точно девет цифри. */
  private static final Pattern EIK = Pattern.compile("[0-9]{9}");

  /** Номер по БУЛСТАТ на клон — ЕИК плюс четири цифри. */
  private static final Pattern BRANCH = Pattern.compile("[0-9]{13}");

  private static final String EXTENSION = ".xml";

  private BgSaftFileName() {}

  /** Името или — при ГРЕШКА — {@code null} и находката. */
  public static final class Result {
    private final String fileName;
    private final List<BgDeclarationFileFinding> findings;

    private Result(String fileName, List<BgDeclarationFileFinding> findings) {
      this.fileName = fileName;
      this.findings = Collections.unmodifiableList(findings);
    }

    /** {@code null}, когато има ГРЕШКА — име, което НАП ще откаже, не се предлага. */
    public String getFileName() {
      return fileName;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param eik ЕИК на подаващия — същият като в Header (S.CMH.1)
   * @param selection видът и периодът на файла
   */
  public static Result of(String eik, BgSaftSelection selection) {
    if (selection == null) {
      throw new IllegalArgumentException("SAF-T: име на файл без вид и период");
    }
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    String id = eik == null ? "" : eik.trim();
    if (id.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_FILE_NAME_EIK_MISSING",
              "Няма ЕИК — името на файла (Прил. 3 т. I.2) започва с него."));
      return new Result(null, findings);
    }
    if (BRANCH.matcher(id).matches()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_FILE_NAME_BRANCH_EIK",
              "„"
                  + id
                  + "“ е 13-цифрен номер на клон. Файлът се подава от търговеца, под неговия"
                  + " 9-цифрен ЕИК, и включва клоновете (Прил. 3 т. I.2; QA II.1.10)."));
      return new Result(null, findings);
    }
    if (!EIK.matcher(id).matches()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_FILE_NAME_EIK_INVALID",
              "„" + id + "“ не е ЕИК от девет цифри — името на файла (Прил. 3 т. I.2) го иска."));
      return new Result(null, findings);
    }
    return new Result(id + "_" + period(selection) + EXTENSION, findings);
  }

  private static String period(BgSaftSelection selection) {
    LocalDate from = selection.getFromDate();
    switch (selection.getKind()) {
      case ANNUAL:
        return year(from);
      case ON_DEMAND:
        return day(from) + "_" + day(selection.getToDate());
      default:
        return two(from.getMonthValue()) + "_" + year(from);
    }
  }

  /** DD_MM_YYYY. */
  private static String day(LocalDate date) {
    return two(date.getDayOfMonth()) + "_" + two(date.getMonthValue()) + "_" + year(date);
  }

  private static String year(LocalDate date) {
    return String.format("%04d", date.getYear());
  }

  private static String two(int value) {
    return String.format("%02d", value);
  }
}
