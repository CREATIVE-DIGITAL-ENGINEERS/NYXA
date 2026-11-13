package com.axelor.apps.bglocalization.service.app;

import com.axelor.inject.Beans;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.axelor.studio.db.repo.AppRepository;
import com.google.inject.persist.Transactional;
import java.util.List;

/**
 * ЗАПИСЪТ НА НАСТРОЙКИТЕ НА ЗАВАРЕНА БАЗА — свързва се с приложението {@code bg-localization}, ако
 * връзката липсва. Вика се при стартиране ({@code BgLocalizationServerStartListener}).
 *
 * <p><b>Защо.</b> „Конфигуриране“ на картата на приложението (axelor-studio 3.5.0, {@code
 * AppController#configure}) стига до {@code app-bg-localization-config-form} само през {@code
 * App.appBgLocalization} — обратната страна на {@code AppBgLocalization.app}. App Install
 * (studio_appBgLocalization.csv) винаги попълва {@code app}; празно може да е само на запис,
 * създаден на ръка през по-стара форма. Без връзката картата казва „No configuration required“ и
 * настройките (сред тях ЕИК-ът за поле 53 на Д1) нямат път от екрана.
 *
 * <p><b>⚠️ ТЯСНО, И ТОВА Е ЦЕЛТА.</b> Свързва САМО при точно един запис, празно {@code app} и
 * налично приложение. Нула записа (App Install не е минал), два и повече (кой е верният решава
 * човек) или липсващо приложение — не пипа нищо и казва защо. Попълнената връзка не се пренаписва.
 * Идемпотентно: второто пускане вижда попълнено {@code app} и излиза.
 */
public class BgAppConfigLinkService {

  public static final String APP_CODE = "bg-localization";

  public enum Outcome {
    LINKED,
    ALREADY_LINKED,
    NO_RECORD,
    MANY_RECORDS,
    NO_APP
  }

  /**
   * Какво е станало и какво да се запише в лога като WARNING — {@code null} за „нищо за казване“.
   */
  public static final class Result {
    public final Outcome outcome;
    public final String message;

    Result(Outcome outcome, String message) {
      this.outcome = outcome;
      this.message = message;
    }
  }

  @Transactional(rollbackOn = Exception.class)
  public Result ensureLinked() {
    List<AppBgLocalization> records = records();
    if (records == null || records.isEmpty()) {
      return new Result(
          Outcome.NO_RECORD,
          "AppBgLocalization: няма запис — App Install на „"
              + APP_CODE
              + "“ не е минал; „Конфигуриране“ на картата няма какво да отвори. Не се създава оттук.");
    }
    if (records.size() > 1) {
      return new Result(
          Outcome.MANY_RECORDS,
          "AppBgLocalization: "
              + records.size()
              + " записа вместо един — не се пипа нищо; кой е верният решава човек"
              + " (db/checks/2026-10-14-app-config-orphans.sql).");
    }
    AppBgLocalization record = records.get(0);
    if (record.getApp() != null) {
      return new Result(Outcome.ALREADY_LINKED, null);
    }
    App app = appByCode(APP_CODE);
    if (app == null) {
      return new Result(
          Outcome.NO_APP,
          "AppBgLocalization #"
              + record.getId()
              + " е с празно app, а приложението „"
              + APP_CODE
              + "“ го няма — не се пипа нищо.");
    }
    record.setApp(app);
    save(record);
    return new Result(
        Outcome.LINKED,
        "AppBgLocalization #"
            + record.getId()
            + " беше с празно app — свързан с приложението „"
            + APP_CODE
            + "“ #"
            + app.getId()
            + ", за да го намира „Конфигуриране“ на картата.");
  }

  /** Всички записи на настройките — шев, за да върви свидетелят без база. */
  protected List<AppBgLocalization> records() {
    return Beans.get(AppBgLocalizationRepository.class).all().fetch();
  }

  /** Приложението по код — шев по същата причина. */
  protected App appByCode(String code) {
    return Beans.get(AppRepository.class).findByCode(code);
  }

  /** Записът — шев по същата причина. */
  protected void save(AppBgLocalization record) {
    Beans.get(AppBgLocalizationRepository.class).save(record);
  }
}
