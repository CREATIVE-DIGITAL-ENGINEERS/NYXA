package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Xsd;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import com.axelor.apps.bglocalization.service.zddfl.BgMissingFileLayoutException;
import java.io.IOException;
import java.util.List;

/**
 * КАКВО ВЛИЗА В ЕДНО ПОДАВАНЕ ПО ЧЛ. 73 АЛ. 1 — <b>решенията, отделени от записването</b>.
 *
 * <p><b>Защо отделен клас.</b> Същото разделение като {@code BgD1SubmissionPlan}: услугата, която
 * записва, иска {@code MetaFiles} и хранилища, тоест жива Guice и база. Решенията обаче не искат
 * нищо — „справка без редове е ОТКАЗ“, „валидатор с блокираща находка е ОТКАЗ“, „броят записи е
 * броят редове на Част ІІ“. Слети в услугата, те стават проверими само с база, тоест на практика
 * непроверими.
 *
 * <p><b>⚠️ ОТКАЗИТЕ ПАДАТ ТУК, ПРЕДИ БАЗАТА ДА НОСИ СЛЕДА.</b> Планът се прави ПРЕДИ да се качи
 * {@code MetaFile} и преди да се създаде подаване — тоест неуспял опит не оставя нито файл на
 * диска, нито ред в одитната следа.
 */
public final class BgArt73Para1SubmissionPlan {

  private BgArt73Para1SubmissionPlan() {}

  /**
   * Планира ЕДИНСТВЕНИЯ файл на справката.
   *
   * <p>⚠️ Редът на проверките не е произволен: първо СЪДЪРЖАНИЕТО (валидатор), после ФОРМАТЪТ.
   * Обърнат, отказът винаги щеше да е „форматът липсва“ и никой никога нямаше да види, че справката
   * освен това е и непълна — тоест в деня, в който заповедта дойде, щяха да излязат наведнъж всички
   * премълчани грешки по данните.
   *
   * @throws IllegalStateException при празна справка или блокираща находка на валидатора
   * @throws BgMissingFileLayoutException когато физическият формат не е потвърден
   */
  public static BgDeclarationPlannedFile plan(
      BgArt73Para1FileService fileService, BgArt73Para1FileContent content) throws IOException {

    List<BgDeclarationFileFinding> findings = BgArt73Para1FileValidator.validate(content);
    if (BgArt73Para1FileValidator.hasBlocking(findings)) {
      throw new IllegalStateException(
          "Справката не може да бъде записана във файл — има блокиращи находки:\n"
              + describe(findings));
    }

    byte[] bytes = fileService.toBytes(content);

    // ⚠️ СТРУКТУРНАТА ПРОВЕРКА Е ПОСЛЕДНА, И ТО ВЪРХУ БАЙТОВЕТЕ. Тя не замества валидатора —
    // схемата приема почти всичко (виж BgArt73XsdValidator) — а пази срещу дефект В ПИСАЧА: сгрешен
    // ред на таговете, непропуснат елемент, счупено екраниране. Минала над низа, тя щеше да мери
    // друго съдържание от това, което тръгва към НАП.
    List<BgDeclarationFileFinding> schema = BgArt73XsdValidator.validate(bytes);
    if (!schema.isEmpty()) {
      throw new IllegalStateException(
          "Генерираният файл не минава схемата "
              + BgArt73Xsd.describe()
              + ":\n"
              + describe(schema));
    }
    return new BgDeclarationPlannedFile(0, fileService.fileName(), content.getRows().size(), bytes);
  }

  /** Само блокиращите, по един на ред — това е, което човекът трябва да поправи. */
  static String describe(List<BgDeclarationFileFinding> findings) {
    StringBuilder sb = new StringBuilder();
    for (BgDeclarationFileFinding finding : findings) {
      if (finding.isBlocking()) {
        if (sb.length() > 0) {
          sb.append("\n");
        }
        sb.append("• ").append(finding.getMessage());
      }
    }
    return sb.toString();
  }
}
