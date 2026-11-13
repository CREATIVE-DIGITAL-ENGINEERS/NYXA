package com.axelor.apps.napsubmissions.services.saft;

import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.i18n.I18n;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ТОЧКА ЗА РАЗШИРЕНИЕ: ПРОВЕРКАТА И ГЕНЕРИРАНЕТО НА SAF-T ФАЙЛА (G6b).
 *
 * <p>Този модул не познава схемите и генератора на SAF-T, затова по подразбиране няма проверка — и
 * отчетът никога не става READY (риск 6 от одита). axelor-bg-localization заменя класа ({@code
 * BgSaftFileEngine}), по образеца на {@link SaftPayloadVersionCheck}.
 */
public class SaftFileEngine {

  /**
   * Пълната проверка — сглобяването, централните правила и XSD-то над самите байтове — и, без
   * ГРЕШКА, файлът.
   *
   * @param report отчетът (дружество, месец)
   * @param today датата на генериране: от нея са S.H.1 и схемата
   * @param lastGenerated кога е генериран предишният файл на отчета; {@code null} — няма такъв
   */
  public SaftCheckResult run(SaftReport report, LocalDate today, LocalDateTime lastGenerated) {
    return SaftCheckResult.unavailable(
        I18n.get("Проверката на SAF-T не е налична: липсва модулът на българската локализация."));
  }
}
