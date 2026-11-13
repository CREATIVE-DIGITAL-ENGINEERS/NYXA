package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import java.util.List;

/**
 * ПОДАДЕН ЛИ Е ДАНЪЧНИЯТ ПЕРИОД — едно правило за всички читатели (решение Р8, 02.11.2026).
 *
 * <p>Подаден е периодът, щом има {@code BgVatReturn} на дружеството за периода със статус {@code
 * submitted} или {@code accepted} — оригинал или коригираща, без значение. {@code draft}, {@code
 * generated}, {@code corrected} и {@code rejected} не са подаване: отхвърленият от НАП файл не е
 * приет, тоест отхвърлен оригинал без подадена коригираща оставя периода неподаден.
 *
 * <p>Коригиращата се познава по {@code correctsPrevious}, не по статуса: {@code corrected} е само
 * началният ѝ статус и след „Генерирай“ тя върви по същия път като всяка друга.
 */
public final class BgVatFiledPeriods {

  /** Статусите, при които НАП държи декларацията за периода. */
  public static final List<String> FILED_STATUSES = List.of("submitted", "accepted");

  /** Статусите на коригираща, която още се подготвя — периодът ѝ се отваря за протоколите. */
  public static final List<String> OPEN_CORRECTION_STATUSES = List.of("corrected", "generated");

  private BgVatFiledPeriods() {}

  public static boolean isFiledStatus(String status) {
    return status != null && FILED_STATUSES.contains(status);
  }

  /** Има ли подадена декларация (която и да е от записите) на дружеството за {@code ГГГГММ}. */
  public static boolean isFiled(BgVatReturnRepository repo, Company company, String period) {
    return repo.all()
            .filter(
                "self.company = :company AND self.period = :period AND self.statusSelect IN"
                    + " (:statuses)")
            .bind("company", company)
            .bind("period", period)
            .bind("statuses", FILED_STATUSES)
            .count()
        > 0;
  }

  /**
   * Има ли за периода коригираща в {@code corrected} или {@code generated} — подадената декларация
   * се подава отново (ЗДДС чл. 126 ал. 2), тоест в дневниците ѝ още може да влезе документ.
   * Коригираща в {@code submitted}/{@code accepted} периода не отваря.
   */
  public static boolean hasOpenCorrection(
      BgVatReturnRepository repo, Company company, String period) {
    return repo.all()
            .filter(
                "self.company = :company AND self.period = :period"
                    + " AND self.correctsPrevious IS NOT NULL AND self.statusSelect IN (:statuses)")
            .bind("company", company)
            .bind("period", period)
            .bind("statuses", OPEN_CORRECTION_STATUSES)
            .count()
        > 0;
  }
}
