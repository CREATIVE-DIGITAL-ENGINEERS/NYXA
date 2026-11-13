package com.axelor.apps.bglocalization.service.invoice;

import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.studio.db.AppBgLocalization;
import com.google.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Попълва „Мерна единица по подразбиране на ред от фактура“ ({@code
 * AppBgLocalization.bgDefaultInvoiceLineUnit}) с „Брой“ — но само ако е празна.
 *
 * <p><b>Откъде се вика.</b> {@code call} на входа {@code studio_appBgLocalization.csv} в {@code
 * apps/init-data/bg-localization-config.xml}. Този вход минава и при App Install (нова инсталация),
 * и при всеки старт през {@code ModuleResourceSyncService} ({@code datainit.sync.modules}), тоест
 * заварена база с празна настройка се попълва при следващия рестарт/деплой — без SQL и без DDL.
 *
 * <p>⚠️ <b>ЗАЩО НЕ КОЛОНА В CSV.</b> Входът е upsert и колона {@code bgDefaultInvoiceLineUnit} щеше
 * да връща „Брой“ при ВСЕКИ старт, върху избора на администратора — мълчаливо. Колбекът получава
 * вече намерения ред, преди {@code JPA.manage}, и пипа само празното.
 *
 * <p><b>„Брой“ по importId, не по име.</b> Редът идва от {@code data-init/input/base_unit.csv} с
 * importId {@value #PIECE_IMPORT_ID}; името е преводимо и може да бъде преименувано. Ако няма точно
 * един такъв ред (още не е внесен или е дублиран), не се избира нищо — само се пише предупреждение;
 * следващият старт опитва пак.
 */
public class BgDefaultInvoiceLineUnitSeed {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** importId на „Брой“ в {@code data-init/input/base_unit.csv}. */
  public static final String PIECE_IMPORT_ID = "2";

  protected final UnitRepository unitRepo;

  @Inject
  public BgDefaultInvoiceLineUnitSeed(UnitRepository unitRepo) {
    this.unitRepo = unitRepo;
  }

  /** Колбекът на CSV вноса. Без „Брой“ редът на настройките пак се внася — само без единица. */
  public Object fillIfEmpty(Object bean, Map<String, Object> values) {
    if (!(bean instanceof AppBgLocalization)) {
      return bean;
    }
    AppBgLocalization app = (AppBgLocalization) bean;
    if (app.getBgDefaultInvoiceLineUnit() != null) {
      return bean;
    }
    Unit piece = findPiece();
    if (piece != null) {
      app.setBgDefaultInvoiceLineUnit(piece);
      LOG.info(
          "BG Localization: default invoice line unit set to '{}' (importId {}).",
          piece.getName(),
          PIECE_IMPORT_ID);
    }
    return bean;
  }

  /** „Брой“ — точно един ред с importId {@value #PIECE_IMPORT_ID}, иначе {@code null}. */
  protected Unit findPiece() {
    List<Unit> found =
        unitRepo
            .all()
            .filter("self.importId = :importId")
            .bind("importId", PIECE_IMPORT_ID)
            .fetch(2);
    if (found.size() == 1) {
      return found.get(0);
    }
    LOG.warn(
        "BG Localization: default invoice line unit left empty — expected exactly one unit with"
            + " importId {} (\"Брой\"), found {}.",
        PIECE_IMPORT_ID,
        found.size());
    return null;
  }
}
