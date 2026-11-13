package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para6Xsd;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import com.axelor.apps.bglocalization.service.zddfl.BgMissingFileLayoutException;
import java.io.IOException;
import java.util.List;

/**
 * КАКВО ВЛИЗА В ЕДНО ПОДАВАНЕ ПО ЧЛ. 73 АЛ. 6 — решенията, отделени от записването.
 *
 * <p>Огледално на {@link BgArt73Para1SubmissionPlan}, със същото разделение: решенията не искат
 * Guice и база, тоест са проверими без Postgres.
 *
 * <p><b>⚠️ БРОЯТ ЗАПИСИ Е БРОЯТ ЛИЦА, А НЕ БРОЯТ РЕДОВЕ ДОХОД.</b> При ал. 1 записът е плащане и
 * двете числа съвпадат. Тук {@code part2/rowsenum} е ЛИЦЕ — преброени редовете доход, справката би
 * твърдяла пред НАП по-голям брой записи от този, който файлът носи.
 */
public final class BgArt73Para6SubmissionPlan {

  private BgArt73Para6SubmissionPlan() {}

  /**
   * Планира ЕДИНСТВЕНИЯ файл на справката.
   *
   * <p>⚠️ Редът е същият като при ал. 1: първо СЪДЪРЖАНИЕТО, после ФОРМАТЪТ, накрая СХЕМАТА върху
   * байтовете.
   *
   * @throws IllegalStateException при празна справка или блокираща находка на валидатора
   * @throws BgMissingFileLayoutException когато физическият формат не е потвърден
   */
  public static BgDeclarationPlannedFile plan(
      BgArt73Para6FileService fileService, BgArt73Para6FileContent content) throws IOException {

    List<BgDeclarationFileFinding> findings = BgArt73Para6FileValidator.validate(content);
    if (hasBlocking(findings)) {
      throw new IllegalStateException(
          "Справката не може да бъде записана във файл — има блокиращи находки:\n"
              + BgArt73Para1SubmissionPlan.describe(findings));
    }

    byte[] bytes = fileService.toBytes(content);

    // ⚠️ СТРУКТУРНАТА ПРОВЕРКА Е ПОСЛЕДНА, И ТО ВЪРХУ БАЙТОВЕТЕ — пази срещу дефект В ПИСАЧА
    // (сгрешена вложеност, непропуснат елемент, счупено екраниране), не срещу лоши данни.
    List<BgDeclarationFileFinding> schema =
        BgArt73XsdValidator.validate(bytes, BgArt73Para6Xsd.RESOURCE, BgArt73Para6Xsd.describe());
    if (!schema.isEmpty()) {
      throw new IllegalStateException(
          "Генерираният файл не минава схемата "
              + BgArt73Para6Xsd.describe()
              + ":\n"
              + BgArt73Para1SubmissionPlan.describe(schema));
    }
    return new BgDeclarationPlannedFile(
        0, fileService.fileName(), content.getPersons().size(), bytes);
  }

  /** Има ли поне една блокираща находка. */
  public static boolean hasBlocking(List<BgDeclarationFileFinding> findings) {
    for (BgDeclarationFileFinding finding : findings) {
      if (finding.isBlocking()) {
        return true;
      }
    }
    return false;
  }
}
