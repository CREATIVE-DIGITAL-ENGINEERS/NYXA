package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Текстовете след „Генерирай“: защо статии и документи не са в дневниците и диалогът във формата.
 *
 * <p>Чисти функции — без база и без Axelor контекст. Редовете за изключенията отиват в
 * предупрежденията на резултата, тоест и в резюмето на пакета ({@code BgVatPackage.summary}).
 */
public final class BgVatGenerateReport {

  /** Колко номера се изброяват за една причина. */
  static final int SAMPLE_LIMIT = 5;

  /** Колко реда от грешки и предупреждения влизат в диалога; всички са в резюмето на пакета. */
  static final int DIALOG_LINE_LIMIT = 15;

  private BgVatGenerateReport() {}

  /** Един ред на причина, само за непразните — в реда на проверката. */
  public static List<String> exclusionLines(BgVatExclusionsDto exclusions) {
    List<String> lines = new ArrayList<>();
    add(
        lines,
        "Не влизат в дневниците — статии с ДДС в периода „в дневник“ (DAYBOOK), още не"
            + " осчетоводени окончателно",
        exclusions.getDaybookMoves());
    add(
        lines,
        "Не влизат в дневниците — статии с ДДС в периода, които са чернови",
        exclusions.getDraftMoves());
    add(
        lines,
        "Не влизат в дневниците — осчетоводени фактури с дата на данъчното събитие в периода, а"
            + " статията им е с дата извън него (дневниците се водят по датата на статията)",
        exclusions.getOutsidePeriodMoves());
    add(
        lines,
        "Не влизат в дневниците за периода — фактури, отразени в друг данъчен период (полето"
            + " „Период на отразяване по ЗДДС“)",
        exclusions.getReportedElsewhereMoves());
    add(
        lines,
        "Не влизат в декларацията — статии на фактури на дружеството, осчетоводени в друго"
            + " дружество",
        exclusions.getOtherCompanyMoves());
    add(
        lines,
        "Не влизат в дневника за покупки — фактури с нулеви основа и ДДС във валутата на"
            + " дружеството на всеки ред",
        exclusions.getZeroAmountDocuments());
    add(
        lines,
        "Не влизат в дневниците — фактури с обратно начисляване без осчетоводен протокол по чл. 117"
            + " ЗДДС",
        exclusions.getReverseChargeDocuments());
    add(
        lines,
        "Документи с ДДС, които не попаднаха в нито един дневник",
        exclusions.getOtherDroppedDocuments());
    return lines;
  }

  /**
   * Диалогът след „Генерирай“ (HTML за {@code ActionResponse.setInfo}), или {@code null}, когато
   * няма какво да се каже освен броя файлове: дневниците не са празни, няма грешки и няма
   * предупреждения извън обичайните бележки.
   */
  public static String dialog(BgVatExportResultDto result) {
    boolean empty = result.getSalesRows().isEmpty() && result.getPurchaseRows().isEmpty();
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    split(result, errors, warnings);
    if (!empty && errors.isEmpty() && warnings.isEmpty()) {
      return null;
    }

    StringBuilder text = new StringBuilder();
    if (empty) {
      text.append("<b>Дневниците за периода са празни — декларацията е нулева.</b>");
    } else {
      text.append("В дневника за продажби: ")
          .append(result.getSalesRows().size())
          .append(" реда, за покупки: ")
          .append(result.getPurchaseRows().size())
          .append('.');
    }
    text.append("<br/>").append(escape(result.getMessage() == null ? "" : result.getMessage()));
    appendFindings(text, errors, warnings);
    return text.toString();
  }

  /**
   * Задължителните файлове на пакета, които липсват. ППЗДДС чл. 114 ал. 1: пакетът съдържа „Deklar,
   * PRODAGBI, POKUPKI“ (изм. ДВ бр. 24/2017), а ал. 3 — и когато не съдържат информация. Сверено на
   * 23.10.2026 с консолидирания текст на ППЗДДС (последно изм. ДВ бр. 54/2024).
   */
  public static List<String> missingDiaryFiles(BgVatExportResultDto result) {
    List<String> missing = new ArrayList<>();
    for (String name : MANDATORY_FILES) {
      boolean present =
          result.getGeneratedFiles().stream().anyMatch(f -> name.equals(f.getFileName()));
      if (!present) {
        missing.add(name);
      }
    }
    return missing;
  }

  /** Грешката, когато пакетът е непълен: кои файлове липсват и защо — нищо не се записва. */
  public static String incompleteDialog(BgVatExportResultDto result, List<String> missing) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    split(result, errors, warnings);
    StringBuilder text = new StringBuilder("<b>Пакетът за НАП е непълен");
    if (!missing.isEmpty()) {
      text.append(" — липсва ").append(escape(String.join(", ", missing)));
    }
    text.append(". Нищо не е записано и декларацията не е отбелязана като генерирана.</b>");
    appendFindings(text, errors, warnings);
    return text.toString();
  }

  /** Файловете, без които пакетът не се подава (ППЗДДС чл. 114 ал. 1, изм. ДВ бр. 24/2017). */
  static final List<String> MANDATORY_FILES =
      List.of(
          BgVatExportService.DECLARATION_FILE_NAME,
          BgVatExportService.SALES_FILE_NAME,
          BgVatExportService.PURCHASE_FILE_NAME);

  private static void split(
      BgVatExportResultDto result, List<String> errors, List<String> warnings) {
    for (BgVatValidationErrorDto finding : result.getValidationErrors()) {
      if (BgVatValidationErrorDto.SEVERITY_ERROR.equals(finding.getSeverity())) {
        errors.add(finding.getMessage());
      } else {
        warnings.add(finding.getMessage());
      }
    }
    for (String warning : result.getWarnings()) {
      if (!BgVatExportService.isRoutineNotice(warning)) {
        warnings.add(warning);
      }
    }
  }

  private static void appendFindings(
      StringBuilder text, List<String> errors, List<String> warnings) {
    int shown = 0;
    int total = errors.size() + warnings.size();
    for (String error : errors) {
      if (shown++ < DIALOG_LINE_LIMIT) {
        text.append("<br/>❌ ").append(escape(error));
      }
    }
    for (String warning : warnings) {
      if (shown++ < DIALOG_LINE_LIMIT) {
        text.append("<br/>⚠ ").append(escape(warning));
      }
    }
    if (total > DIALOG_LINE_LIMIT) {
      text.append("<br/>… и още ")
          .append(total - DIALOG_LINE_LIMIT)
          .append(" — пълният списък е в „Преглед на файловете“, панел „Обобщение“.");
    }
  }

  private static void add(List<String> lines, String reason, List<String> refs) {
    if (refs.isEmpty()) {
      return;
    }
    StringBuilder line = new StringBuilder(reason).append(": ").append(refs.size()).append(" (");
    line.append(String.join(", ", refs.subList(0, Math.min(SAMPLE_LIMIT, refs.size()))));
    if (refs.size() > SAMPLE_LIMIT) {
      line.append(" и още ").append(refs.size() - SAMPLE_LIMIT);
    }
    lines.add(line.append(")").toString());
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
