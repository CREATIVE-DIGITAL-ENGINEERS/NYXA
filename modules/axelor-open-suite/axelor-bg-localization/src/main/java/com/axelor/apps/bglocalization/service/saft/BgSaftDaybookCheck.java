package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * СТАТИЯ В ДНЕВНИКА (DAYBOOK) В МЕСЕЦА — ГРЕШКА, ФАЙЛ НЯМА (G6b).
 *
 * <p>Статия в DAYBOOK е въведена, но не е осчетоводена: не е във файла (той носи само ACCOUNTED —
 * {@link BgSaftMoveScope}), а е част от счетоводството на месеца. Файл без нея е непълен. G6a я
 * показва като ПРЕДУПРЕЖДЕНИЕ заедно с черновите и симулираните ({@code SAFT_ENV_UNPOSTED_MOVE});
 * тук, в слоя на екрана, тя става ГРЕШКА, а нейното предупреждение отпада, за да не е два пъти.
 * Генераторът не се пипа — закованите отпечатъци на файла остават същите.
 *
 * <p>Черновите (NEW) и симулираните остават ПРЕДУПРЕЖДЕНИЯ: черновата може да е изоставена; дали и
 * тя да е ГРЕШКА — отворен въпрос в доклада на G6b.
 *
 * <p>Дневникът е изключен за всички дружества, но заварените статии в DAYBOOK остават — за тях е
 * проверката.
 */
public final class BgSaftDaybookCheck {

  public static final String CODE = "SAFT_DAYBOOK_MOVE";

  /** Кодът на G6a, чието предупреждение се заменя от ГРЕШКАТА тук. */
  static final String UNPOSTED_WARNING = "SAFT_ENV_UNPOSTED_MOVE";

  /** Статиите на дружеството в DAYBOOK с дата в месеца. */
  public static final String DAYBOOK_MOVES =
      "SELECT self.id, self.reference FROM Move self"
          + " WHERE self.company.id = :companyId"
          + " AND self.date >= :fromDate"
          + " AND self.date <= :toDate"
          + " AND self.statusSelect = "
          + MoveRepository.STATUS_DAYBOOK
          + " ORDER BY self.date, self.id";

  private BgSaftDaybookCheck() {}

  /** Статия от заявката. */
  public static final class Row {
    final Long id;
    final String reference;

    public Row(Long id, String reference) {
      this.id = id;
      this.reference = reference;
    }

    String label() {
      return reference == null || reference.isBlank() ? "#" + id : reference.trim();
    }
  }

  /** По ГРЕШКА за всяка статия в DAYBOOK, с линк към нея. */
  public static List<BgDeclarationFileFinding> errors(Collection<Row> moves) {
    List<BgDeclarationFileFinding> errors = new ArrayList<>();
    for (Row move : moves) {
      errors.add(
          BgDeclarationFileFinding.error(
                  CODE,
                  "Статия в дневника (DAYBOOK) с дата в месеца — не е осчетоводена и не е във"
                      + " файла. Осчетоводете я или я върнете в чернова преди генерирането: "
                      + move.label())
              .at(BgSaftRef.MOVE, move.id));
    }
    return errors;
  }

  /**
   * Находките на генератора плюс ГРЕШКИТЕ за DAYBOOK; предупреждението на G6a за същите статии
   * отпада.
   */
  public static List<BgDeclarationFileFinding> combine(
      Collection<BgDeclarationFileFinding> generator, Collection<Row> daybook) {
    Set<Long> ids = new HashSet<>();
    for (Row move : daybook) {
      ids.add(move.id);
    }
    List<BgDeclarationFileFinding> out = new ArrayList<>();
    for (BgDeclarationFileFinding finding : generator) {
      boolean replaced =
          UNPOSTED_WARNING.equals(finding.getCode())
              && BgSaftRef.MOVE.equals(finding.getTargetModel())
              && ids.contains(finding.getTargetId());
      if (!replaced) {
        out.add(finding);
      }
    }
    out.addAll(errors(daybook));
    return out;
  }
}
