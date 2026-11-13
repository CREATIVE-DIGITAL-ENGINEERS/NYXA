package com.axelor.apps.bglocalization.service.etz;

import java.util.ArrayList;
import java.util.List;

/**
 * УСЛОВНАТА ЗАДЪЛЖИТЕЛНОСТ — <b>това, което схемата не може да каже</b>.
 *
 * <p>Правилата са дословно от колона 9 („Задължителна контрола при последваща обработка и прием на
 * данните“) на Приложения № 1 и № 2 към Заповед {@value BgEtzXsd#ORDER_NUMBER}.
 *
 * <p><b>⚠️ БЕЗ ТОЗИ КЛАС ВАЛИДАТОРЪТ ЩЕШЕ ДА Е ДЕКОР.</b> В {@code etz_employ_restrict.xsd} 24 от
 * 33-те полета са {@code minOccurs="0"} — тоест запис само с деветте структурно задължителни полета
 * минава схемата с 0 грешки. Приложението обаче казва, че при тип на документа 3 са задължителни и
 * т. 26, и т. 27, а при 1 и 2 — още осем. „Файлът не се приема, ако не съдържа задължителните за
 * попълване полета“ е в самото приложение.
 *
 * <p><b>⚠️ И ЗАТОВА РЕДЪТ Е: УСЛОВИЯТА ПРЕДИ СХЕМАТА.</b> Схемата хваща формата (тип, дължина,
 * шаблон); тя НЕ хваща липсата. Обратният ред би дал „0 грешки“ върху непълен файл.
 *
 * <p><b>⚠️ ВИДЪТ ПРАВООТНОШЕНИЕ 03 СЕ ДЪРЖИ КАТО СЛУЖЕБНО, НО НЕ НАВСЯКЪДЕ.</b> Код 03 е
 * „прекратени преди 1 юни 2026 г. служебни правоотношения“. Приложението го изброява заедно с 02
 * при т. 13, 20 и 32, но при т. 11 и т. 17 пише САМО 02. Разликата е пренесена буквално — 03 не е
 * синоним на 02.
 */
public final class BgEtzMandatory {

  private BgEtzMandatory() {}

  /** Вид правоотношение — трудово. */
  public static final String EMPLOY_LABOUR = "01";

  /** Вид правоотношение — служебно. */
  public static final String EMPLOY_CIVIL = "02";

  /** Вид правоотношение — служебно, прекратено преди 01.06.2026 г. */
  public static final String EMPLOY_CIVIL_LEGACY = "03";

  /** Тип на документа — сключване / възникване. */
  public static final String DOC_CONCLUSION = "1";

  /** Тип на документа — изменение. */
  public static final String DOC_AMENDMENT = "2";

  /** Тип на документа — прекратяване. */
  public static final String DOC_TERMINATION = "3";

  /**
   * Проверява един ред на {@code etzemploy} срещу условията на Приложение № 1.
   *
   * @return изброените липси; празен списък значи „условията са спазени“
   */
  public static List<BgEtzGap> check62(BgEtzRecord row) {
    List<BgEtzGap> gaps = new ArrayList<>();
    if (row == null) {
      throw new IllegalArgumentException("няма ред за проверка");
    }

    String employ = nz(row.get(BgEtz62Field.EMPLOYTYPE));
    String doc = nz(row.get(BgEtz62Field.DOCUMENTTYPE));

    // ── структурно задължителните по схемата ────────────────────────────────
    for (BgEtz62Field field : BgEtz62Field.values()) {
      if (field == BgEtz62Field.EIK) {
        continue; // заглавната част се проверява на файла, не на реда
      }
      if (field.isSchemaRequired() && !row.has(field.getTag())) {
        gaps.add(required(field, "схемата го иска във всеки запис"));
      }
    }

    boolean conclusionOrAmendment = DOC_CONCLUSION.equals(doc) || DOC_AMENDMENT.equals(doc);
    boolean civil = EMPLOY_CIVIL.equals(employ) || EMPLOY_CIVIL_LEGACY.equals(employ);

    // ── по ВИД ПРАВООТНОШЕНИЕ и ТИП ДОКУМЕНТ ────────────────────────────────
    // т. 11 — само 02 (НЕ 03), при тип 1 и 2
    if (EMPLOY_CIVIL.equals(employ) && conclusionOrAmendment) {
      requireIfMissing(
          gaps, row, BgEtz62Field.CONTRACTNO, "вид правоотношение 02 и тип на документа 1 или 2");
    }
    // т. 13 — 02 или 03, при тип 1 или 2
    if (civil && conclusionOrAmendment) {
      requireIfMissing(
          gaps,
          row,
          BgEtz62Field.EMPLOEERANK,
          "вид правоотношение 02 или 03 и тип на документа 1 или 2");
      requireIfMissing(
          gaps,
          row,
          BgEtz62Field.CODEPOSITION,
          "вид правоотношение 02 или 03 и тип на документа 1 или 2");
    }
    // т. 14 и т. 19 — само 01, при тип 1 или 2
    if (EMPLOY_LABOUR.equals(employ) && conclusionOrAmendment) {
      requireIfMissing(
          gaps, row, BgEtz62Field.BEGINDATE, "вид правоотношение 01 и тип на документа 1 или 2");
      requireIfMissing(
          gaps, row, BgEtz62Field.CODENKPD, "вид правоотношение 01 и тип на документа 1 или 2");
      requireIfMissing(
          gaps, row, BgEtz62Field.VACATIONDAYS, "вид правоотношение 01 и тип на документа 1 или 2");
    }
    // т. 17 — само 02, при тип 2
    if (EMPLOY_CIVIL.equals(employ) && DOC_AMENDMENT.equals(doc)) {
      requireIfMissing(
          gaps, row, BgEtz62Field.CHANGENO, "вид правоотношение 02 и тип на документа 2");
    }

    // ── по ТИП ДОКУМЕНТ ─────────────────────────────────────────────────────
    if (conclusionOrAmendment) {
      requireIfMissing(gaps, row, BgEtz62Field.SALARY, "тип на документа 1 или 2");
      requireIfMissing(gaps, row, BgEtz62Field.CODEKID, "тип на документа 1 или 2");
      requireIfMissing(gaps, row, BgEtz62Field.CODEEKATTE, "тип на документа 1 или 2");
      requireIfMissing(gaps, row, BgEtz62Field.CODEHOURS, "тип на документа 1 или 2");
      requireIfMissing(gaps, row, BgEtz62Field.WORKINGHOURS, "тип на документа 1 или 2");
    }
    if (DOC_AMENDMENT.equals(doc)) {
      requireIfMissing(gaps, row, BgEtz62Field.CHANGEDATE, "тип на документа 2");
      requireIfMissing(gaps, row, BgEtz62Field.ENTRYDATE, "тип на документа 2");
    }
    if (DOC_TERMINATION.equals(doc)) {
      requireIfMissing(gaps, row, BgEtz62Field.ENDDATE, "тип на документа 3");
      requireIfMissing(gaps, row, BgEtz62Field.ENDREASON, "тип на документа 3");
      if (EMPLOY_LABOUR.equals(employ)) {
        requireIfMissing(
            gaps, row, BgEtz62Field.USEDVACATION, "вид правоотношение 01 и тип на документа 3");
        requireIfMissing(
            gaps, row, BgEtz62Field.EXPERIENCE, "вид правоотношение 01 и тип на документа 3");
        // ⚠️ т. 31 НЕ се иска безусловно: приложението казва „само когато ИМА продължителност на
        // време, което не се признава“. Тоест липсата ѝ е допустимо състояние, а не пропуск.
      }
      if (civil) {
        requireIfMissing(
            gaps,
            row,
            BgEtz62Field.WORKPERIOD,
            "вид правоотношение 02 или 03 и тип на документа 3");
      }
    }

    gaps.addAll(crossChecks62(row, employ, doc));
    return gaps;
  }

  /**
   * Кръстосаните проверки, които не са „липсва поле“.
   *
   * <p>Отделени, защото са друг вид грешка: стойността Е там, но противоречи на друга стойност.
   */
  private static List<BgEtzGap> crossChecks62(BgEtzRecord row, String employ, String doc) {
    List<BgEtzGap> gaps = new ArrayList<>();

    // т. 23А зависи от т. 23: 1–8 при дневно, 1–40 при седмично (Прил. № 1 и Наредбата, т. 23А).
    String codeHours = nz(row.get(BgEtz62Field.CODEHOURS));
    String hours = nz(row.get(BgEtz62Field.WORKINGHOURS));
    if (!codeHours.isEmpty() && !hours.isEmpty()) {
      int max = "1".equals(codeHours) ? 8 : "2".equals(codeHours) ? 40 : -1;
      Integer value = toInt(hours);
      if (max > 0 && value != null && (value < 1 || value > max)) {
        gaps.add(
            BgEtzGap.general(
                BgEtzGap.Kind.CROSS_CHECK,
                BgEtz62Field.WORKINGHOURS.label()
                    + " е "
                    + value
                    + ", а при код за работно време "
                    + codeHours
                    + " ("
                    + ("1".equals(codeHours) ? "дневно" : "седмично")
                    + ") допустимите стойности са 1–"
                    + max));
      }
    }

    // т. 28 — само при вид правоотношение 01 (Прил. № 1, колона 9).
    if (row.has(BgEtz62Field.COMPENSATION222.getTag()) && !EMPLOY_LABOUR.equals(employ)) {
      gaps.add(
          BgEtzGap.general(
              BgEtzGap.Kind.CROSS_CHECK,
              BgEtz62Field.COMPENSATION222.label()
                  + " се попълва САМО при вид правоотношение 01, а тук е „"
                  + employ
                  + "“"));
    }

    // т. 24 и 25 не се попълват при вид правоотношение 02 (Наредбата, т. 24–25).
    if (EMPLOY_CIVIL.equals(employ)) {
      for (BgEtz62Field field :
          new BgEtz62Field[] {BgEtz62Field.VACATIONDAYS, BgEtz62Field.USEDVACATION}) {
        if (row.has(field.getTag())) {
          gaps.add(
              BgEtzGap.general(
                  BgEtzGap.Kind.CROSS_CHECK,
                  field.label() + " НЕ се попълва при вид правоотношение 02"));
        }
      }
    }

    // Кодът по т. 12 трябва да е от подноменклатурата на този вид правоотношение.
    String contractType = nz(row.get(BgEtz62Field.CONTRACTTYPE));
    if (!contractType.isEmpty() && !BgEtzGrounds.isContractCode(contractType, employ)) {
      gaps.add(
          BgEtzGap.general(
              BgEtzGap.Kind.UNMAPPED_CODE,
              BgEtz62Field.CONTRACTTYPE.label()
                  + " носи код „"
                  + contractType
                  + "“, който не е в номенклатурата за вид правоотношение „"
                  + employ
                  + "“"));
    }
    String endReason = nz(row.get(BgEtz62Field.ENDREASON));
    if (!endReason.isEmpty() && !BgEtzGrounds.isEndCode(endReason, employ)) {
      gaps.add(
          BgEtzGap.general(
              BgEtzGap.Kind.UNMAPPED_CODE,
              BgEtz62Field.ENDREASON.label()
                  + " носи код „"
                  + endReason
                  + "“, който не е в номенклатурата за вид правоотношение „"
                  + employ
                  + "“"));
    }
    // ⚠️ „doc“ участва само в горните условия; тук се пази, за да не се чете като неизползван.
    if (DOC_TERMINATION.equals(doc)
        && row.has(BgEtz62Field.BEGINDATE.getTag())
        && !row.has(BgEtz62Field.ENDDATE.getTag())) {
      gaps.add(
          BgEtzGap.general(
              BgEtzGap.Kind.CROSS_CHECK, "тип на документа 3 без " + BgEtz62Field.ENDDATE.label()));
    }
    return gaps;
  }

  /**
   * Проверява един ред на {@code etz123employer} срещу условията на Приложение № 2.
   *
   * <p>⚠️ Тук условията са ЕДНО: т. 14 (ЕКАТТЕ) е задължителна при вид правоотношение 01 или 02 и
   * код корекция 0 или 1. Всичко останало е безусловно задължително и се пази от схемата.
   */
  public static List<BgEtzGap> check123(BgEtzRecord row) {
    List<BgEtzGap> gaps = new ArrayList<>();
    if (row == null) {
      throw new IllegalArgumentException("няма ред за проверка");
    }
    for (BgEtz123Field field : BgEtz123Field.values()) {
      if (field == BgEtz123Field.EIK || field == BgEtz123Field.FORMEREIK) {
        continue;
      }
      if (field.isSchemaRequired() && !row.has(field.getTag())) {
        gaps.add(required123(field, "схемата го иска във всеки запис"));
      }
    }
    String employ = nz(row.get(BgEtz123Field.EMPLOYTYPE));
    String correction = nz(row.get(BgEtz123Field.CODECORRECTION));
    boolean employCovered = EMPLOY_LABOUR.equals(employ) || EMPLOY_CIVIL.equals(employ);
    boolean correctionCovered = "0".equals(correction) || "1".equals(correction);
    if (employCovered && correctionCovered && !row.has(BgEtz123Field.CODEEKATTE.getTag())) {
      gaps.add(
          required123(
              BgEtz123Field.CODEEKATTE, "вид правоотношение 01 или 02 и код корекция 0 или 1"));
    }
    return gaps;
  }

  private static void requireIfMissing(
      List<BgEtzGap> gaps, BgEtzRecord row, BgEtz62Field field, String condition) {
    if (!row.has(field.getTag())) {
      gaps.add(required(field, condition));
    }
  }

  private static BgEtzGap required(BgEtz62Field field, String condition) {
    return BgEtzGap.general(
        BgEtzGap.Kind.EMPTY_VALUE,
        field.label() + " е задължително при " + condition + ", а е празно");
  }

  private static BgEtzGap required123(BgEtz123Field field, String condition) {
    return BgEtzGap.general(
        BgEtzGap.Kind.EMPTY_VALUE,
        field.label() + " е задължително при " + condition + ", а е празно");
  }

  private static Integer toInt(String value) {
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value.trim();
  }
}
