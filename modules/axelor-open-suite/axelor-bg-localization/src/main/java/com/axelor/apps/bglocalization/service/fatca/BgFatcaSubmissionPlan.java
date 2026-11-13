package com.axelor.apps.bglocalization.service.fatca;

import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXmlWriter;
import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXsd;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ДВЕТЕ ВРАТИ ПРЕДИ ФАЙЛА: {@link BgFatcaFileValidator}, после схемата върху ТОЧНИТЕ байтове.
 *
 * <p><b>⚠️ ОТКАЗЪТ Е ПРЕДИ ДА ИМА ФАЙЛ.</b> Блокираща находка спира тук с всички причини наведнъж;
 * нищо не се качва, артефакт не се ражда. Предупрежденията не спират.
 */
public final class BgFatcaSubmissionPlan {

  private BgFatcaSubmissionPlan() {}

  public static BgDeclarationPlannedFile plan(BgFatcaFileContent content, LocalDate today) {
    refuseIfBlocking(
        BgFatcaFileValidator.validate(content, today),
        "FATCA файлът НЕ е генериран — поправи данните:");

    byte[] bytes = BgFatcaXmlWriter.toBytes(content);
    refuseIfBlocking(
        BgXsdValidator.validate(
            bytes, BgFatcaXsd.RESOURCE, BgFatcaXsd.describe(), BgFatcaXsd.CODE_PREFIX),
        "FATCA файлът НЕ е генериран — не отговаря на " + BgFatcaXsd.describe() + ":");

    return new BgDeclarationPlannedFile(
        0,
        BgFatcaXsd.fileName(content.getReportYear(), content.getGiin()),
        content.getAccounts().size(),
        bytes);
  }

  static void refuseIfBlocking(List<BgDeclarationFileFinding> findings, String title) {
    List<BgDeclarationFileFinding> blocking =
        findings.stream().filter(BgDeclarationFileFinding::isBlocking).collect(Collectors.toList());
    if (blocking.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        title
            + "\n"
            + blocking.stream()
                .map(f -> "• [" + f.getCode() + "] " + f.getMessage())
                .collect(Collectors.joining("\n")));
  }
}
