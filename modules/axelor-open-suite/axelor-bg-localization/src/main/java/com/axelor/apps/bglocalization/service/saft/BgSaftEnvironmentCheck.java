package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ГОТОВА ЛИ Е СРЕДАТА ЗА ФАЙЛА — проверките извън съдържанието му (G6a). Чисти функции: данните им
 * подава {@link BgSaftAuditFileService}.
 *
 * <ul>
 *   <li><b>Периодът е цял календарен месец</b> — ГРЕШКА иначе. Името на месечния файл е {@code
 *       ЕИК_MM_YYYY} (Прил. 3 т. I.2), тоест периодът е месецът; период 01–30.12 губи 31.12 тихо, а
 *       отделен период 31.12–31.12 (приключване) дава файл от един ден със същото име.
 *   <li><b>Таблиците от App Install</b> — ГРЕШКА „направи App Install“ за всяка, в която липсва
 *       ключовият ѝ ред. ⚠️ По ПРИСЪСТВИЕ на познат ред, не по брой: броят се мени с всеки seed, а
 *       присъствието различава „не е инсталирано“ от „инсталирано по-старо“.
 *   <li><b>Неосчетоводени статии в месеца</b> (чернова, дневник, симулирани) — ПРЕДУПРЕЖДЕНИЕ по
 *       статия: обхватът е само {@link MoveRepository#STATUS_ACCOUNTED}, тоест те не са във файла.
 *   <li><b>Статии, създадени след последното генериране</b> — ПРЕДУПРЕЖДЕНИЕ по статия: QA с. 10
 *       (II.2.1) — подават се записванията, „въведени … до датата на генериране на файла“, тоест
 *       предишният файл е остарял. Записът за вече подаден месец (QA с. 10 срещу с. 47) — да се
 *       потвърди с НАП.
 * </ul>
 */
public final class BgSaftEnvironmentCheck {

  /**
   * Таблицата → ключовият ред, който App Install внася. Ключовете са от seed-а в {@code
   * apps/init-data/en/}; редът е стабилен между версиите.
   */
  static final Map<String, String> SENTINELS = sentinels();

  private BgSaftEnvironmentCheck() {}

  private static Map<String, String> sentinels() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("BgSafAccountMapping", "411");
    m.put("BgSafTaxMapping", "BG_SRS_20");
    m.put("BgSafPaymentMapping", "INC_WT");
    m.put("BgSafVatCellCode", "02-11");
    m.put("BgSafWithholdingMapping", "301");
    return m;
  }

  /** Статия извън файла или след генерирането — id, номер и статус. */
  public static final class MoveRow {
    private final Long id;
    private final String reference;
    private final Integer status;

    public MoveRow(Long id, String reference, Integer status) {
      this.id = id;
      this.reference = reference;
      this.status = status;
    }

    private String label() {
      return reference == null || reference.trim().isEmpty() ? "#" + id : reference.trim();
    }
  }

  /** ГРЕШКА, ако периодът не е от първия до последния ден на един месец. */
  public static List<BgDeclarationFileFinding> period(Long periodId, LocalDate from, LocalDate to) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (from == null || to == null) {
      return findings;
    }
    YearMonth month = YearMonth.from(from);
    if (!from.equals(month.atDay(1)) || !to.equals(month.atEndOfMonth())) {
      findings.add(
          BgDeclarationFileFinding.error(
                  "SAFT_PERIOD_NOT_CALENDAR_MONTH",
                  "Периодът "
                      + from
                      + " – "
                      + to
                      + " не е цял календарен месец — месечният файл е за месеца (името му е"
                      + " ЕИК_MM_YYYY, Прил. 3 т. I.2); приключването в отделен период не е"
                      + " отделен файл.")
              .at(BgSaftRef.PERIOD, periodId));
    }
    return findings;
  }

  /**
   * ГРЕШКА за всяка таблица от App Install, в която ключовият ред липсва.
   *
   * @param loaded таблицата → заредените ѝ ключове
   */
  public static List<BgDeclarationFileFinding> referenceTables(
      Map<String, ? extends Collection<String>> loaded) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    for (Map.Entry<String, String> sentinel : SENTINELS.entrySet()) {
      Collection<String> keys = loaded.get(sentinel.getKey());
      if (keys == null || !keys.contains(sentinel.getValue())) {
        findings.add(
            BgDeclarationFileFinding.error(
                "SAFT_ENV_APP_INSTALL",
                "Таблицата "
                    + sentinel.getKey()
                    + " е празна или непълна (няма ред „"
                    + sentinel.getValue()
                    + "“) — направете App Install на приложението на модула axelor-bg-localization;"
                    + " без таблицата кодовете по НАП не се намират."));
      }
    }
    return findings;
  }

  /** ПРЕДУПРЕЖДЕНИЕ за всяка неосчетоводена статия с дата в периода. */
  public static List<BgDeclarationFileFinding> unposted(Collection<MoveRow> moves) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    for (MoveRow move : moves) {
      findings.add(
          BgDeclarationFileFinding.warning(
                  "SAFT_ENV_UNPOSTED_MOVE",
                  "Статия с дата в периода, която не е осчетоводена ("
                      + status(move.status)
                      + ") — не е във файла; осчетоводете я или я изтрийте преди подаването: "
                      + move.label())
              .at(BgSaftRef.MOVE, move.id));
    }
    return findings;
  }

  /** ПРЕДУПРЕЖДЕНИЕ за всяка статия на периода, създадена след последното генериране. */
  public static List<BgDeclarationFileFinding> createdAfter(
      Collection<MoveRow> moves, LocalDateTime lastGenerated) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    for (MoveRow move : moves) {
      findings.add(
          BgDeclarationFileFinding.warning(
                  "SAFT_ENV_CREATED_AFTER_GENERATION",
                  "Статия с дата в периода, създадена след последното генериране ("
                      + lastGenerated.withNano(0)
                      + ") — предишният файл не я съдържа (QA с. 10: записите, въведени до"
                      + " генерирането); запис за вече подаден месец — да се потвърди с НАП (QA"
                      + " с. 47): "
                      + move.label())
              .at(BgSaftRef.MOVE, move.id));
    }
    return findings;
  }

  private static String status(Integer status) {
    if (status == null) {
      return "без статус";
    }
    switch (status) {
      case MoveRepository.STATUS_NEW:
        return "чернова";
      case MoveRepository.STATUS_DAYBOOK:
        return "в дневника, неосчетоводена";
      case MoveRepository.STATUS_SIMULATED:
        return "симулирана";
      default:
        return "статус " + status;
    }
  }
}
