/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.base.service.app;

import com.axelor.app.AppSettings;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.exceptions.BaseExceptionMessage;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.meta.db.repo.MetaModuleRepository;
import com.axelor.meta.loader.AppVersionService;
import com.axelor.studio.app.service.AppServiceImpl;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppRepository;
import com.axelor.studio.service.AppSettingsStudioService;
import com.axelor.utils.helpers.date.LocalDateTimeHelper;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;

@Singleton
public class AppBaseServiceImpl extends AppServiceImpl implements AppBaseService {

  protected static String DEFAULT_LOCALE = "en_GB";

  @Inject
  public AppBaseServiceImpl(
      AppRepository appRepo,
      MetaFiles metaFiles,
      AppVersionService appVersionService,
      AppSettingsStudioService appSettingsService,
      MetaModuleRepository metaModuleRepo,
      MetaFileRepository metaFileRepo) {
    super(appRepo, metaFiles, appVersionService, appSettingsService, metaModuleRepo, metaFileRepo);
  }

  /**
   * weAccount: the apps' dependencies are saved on fresh copies of the {@code App} records.
   *
   * <p>At every startup Studio's {@code initApps} imports each app ({@code importApp}: saves the
   * App and keeps that object in this map), then imports the app's own config data, which links to
   * the App by code and saves it again in its own transaction, so the row's version moves on. When
   * {@code setAppDependsOn} finally merges the object kept in the map, it is a version behind: "Row
   * was updated or deleted by another transaction … App#N", for nearly every app, on every start
   * (test99, locally), and a changed dependency would never be saved. Reading each App again here
   * gives the save the current version; the dependencies are the ones the import collected.
   */
  @Override
  protected void setAppDependsOn(Map<App, Object> appDependsOnMap) {
    Map<App, Object> current = new LinkedHashMap<>();
    appDependsOnMap.forEach(
        (app, dependsOn) -> {
          App fresh = app.getId() == null ? null : appRepo.find(app.getId());
          if (fresh != null) {
            JPA.refresh(fresh);
          }
          current.put(fresh != null ? fresh : app, dependsOn);
        });
    super.setAppDependsOn(current);
  }

  /** "Import demo data" on an app: refused unless the environment is marked as development. */
  @Override
  public App importDataDemo(App app) throws IOException {
    checkDemoDataAllowed();
    return super.importDataDemo(app);
  }

  /**
   * A plain install (the apps' init data, also App Install at startup) goes on everywhere. With
   * demo data it is refused unless the environment is marked as development, before any app is
   * installed.
   */
  @Override
  public void bulkInstall(Collection<App> apps, boolean importDemo, String language)
      throws IOException {
    if (importDemo) {
      checkDemoDataAllowed();
    }
    super.bulkInstall(apps, importDemo, language);
  }

  /** The value of {@link DemoDataGate#ENVIRONMENT}; a seam for tests. */
  protected String environmentMark() {
    return AppSettings.get().get(DemoDataGate.ENVIRONMENT);
  }

  protected void checkDemoDataAllowed() {
    if (!DemoDataGate.allowed(environmentMark())) {
      throw new IllegalStateException(I18n.get(BaseExceptionMessage.DEMO_DATA_ONLY_IN_DEV));
    }
  }

  @Override
  public AppBase getAppBase() {
    return Query.of(AppBase.class).fetchOne();
  }

  @Override
  public ZonedDateTime getTodayDateTime() {
    return getTodayDateTime(null);
  }

  public ZonedDateTime getTodayDateTime(Company company) {

    ZonedDateTime todayDateTime = ZonedDateTime.now();
    if (company != null) {
      todayDateTime = LocalDateTimeHelper.getTodayDateTime(company.getTimezone());
    }

    String applicationMode = AppSettings.get().get("application.mode", "prod");

    if ("dev".equals(applicationMode)) {
      User user = AuthUtils.getUser();
      if (user != null && user.getTodayDateT() != null) {
        todayDateTime = user.getTodayDateT();
      } else {
        AppBase appBase = getAppBase();
        if (appBase != null && appBase.getTodayDateT() != null) {
          return appBase.getTodayDateT();
        }
      }
    }

    return todayDateTime;
  }

  @Override
  public LocalDate getTodayDate(Company company) {

    return getTodayDateTime(company).toLocalDate();
  }

  @Override
  public int getNbDecimalDigitForUnitPrice() {

    AppBase appBase = getAppBase();

    if (appBase != null) {
      return appBase.getNbDecimalDigitForUnitPrice();
    }

    return DEFAULT_NB_DECIMAL_DIGITS;
  }

  @Override
  public int getGlobalTrackingLogPersistence() {
    AppBase appBase = getAppBase();
    if (appBase != null) {
      return appBase.getGlobalTrackingLogPersistence();
    }
    return DEFAULT_TRACKING_MONTHS_PERSISTENCE;
  }

  @Override
  public int getNbDecimalDigitForQty() {

    AppBase appBase = getAppBase();

    if (appBase != null) {
      return appBase.getNbDecimalDigitForQty();
    }

    return DEFAULT_NB_DECIMAL_DIGITS;
  }

  @Override
  public String getDefaultPartnerLocale() {

    AppBase appBase = getAppBase();

    if (appBase != null) {
      Localization localization = appBase.getDefaultPartnerLocalization();
      if (localization != null && !Strings.isNullOrEmpty(localization.getCode())) {
        return localization.getCode();
      }
    }
    return DEFAULT_LOCALE;
  }

  @Override
  public AddressTemplate getDefaultAddressTemplate() throws AxelorException {
    AppBase appBase = getAppBase();

    if (appBase != null) {
      AddressTemplate addressTemplate = appBase.getDefaultAddressTemplate();
      if (addressTemplate != null) {
        return addressTemplate;
      }
    }
    throw new AxelorException(
        appBase,
        TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
        I18n.get(BaseExceptionMessage.NO_DEFAULT_ADDRESS_TEMPLATE));
  }

  // Conversion de devise

  /**
   * Obtenir la tva à 0%
   *
   * @return
   */
  @Override
  public List<CurrencyConversionLine> getCurrencyConfigurationLineList() {
    AppBase appBase = getAppBase();
    if (appBase != null) {
      return appBase.getCurrencyConversionLineList();
    } else {
      return null;
    }
  }

  @Override
  public BigDecimal getDurationHours(BigDecimal duration) {

    if (duration == null) {
      return null;
    }

    AppBase appBase = this.getAppBase();

    if (appBase != null) {
      String timePref = appBase.getTimeLoggingPreferenceSelect();

      if (timePref.equals("days")) {
        duration = duration.multiply(appBase.getDailyWorkHours());
      } else if (timePref.equals("minutes")) {
        duration = duration.divide(new BigDecimal(60), 2, RoundingMode.HALF_UP);
      }
    }

    return duration;
  }

  @Override
  public BigDecimal getGeneralDuration(BigDecimal duration) {

    if (duration == null) {
      return null;
    }

    AppBase appBase = getAppBase();

    if (appBase != null) {
      String timePref = appBase.getTimeLoggingPreferenceSelect();

      BigDecimal dailyWorkHrs = appBase.getDailyWorkHours();

      if (timePref.equals("days")
          && dailyWorkHrs != null
          && dailyWorkHrs.compareTo(BigDecimal.ZERO) != 0) {
        duration = duration.divide(dailyWorkHrs, 2, RoundingMode.HALF_UP);
      } else if (timePref.equals("minutes")) {
        duration = duration.multiply(new BigDecimal(60));
      }
    }

    return duration;
  }

  @Override
  public Unit getUnitDays() throws AxelorException {
    AppBase appBase = getAppBase();
    Unit daysUnit = appBase.getUnitDays();
    if (daysUnit == null) {
      throw new AxelorException(
          appBase,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(BaseExceptionMessage.APP_BASE_NO_UNIT_DAYS));
    }
    return daysUnit;
  }

  @Override
  public Unit getUnitHours() throws AxelorException {
    AppBase appBase = getAppBase();
    Unit hoursUnit = appBase.getUnitHours();
    if (hoursUnit == null) {
      throw new AxelorException(
          appBase,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(BaseExceptionMessage.APP_BASE_NO_UNIT_HOURS));
    }
    return hoursUnit;
  }

  @Override
  public Unit getUnitMinutes() throws AxelorException {
    AppBase appBase = getAppBase();
    Unit minuteUnit = appBase.getUnitMinutes();
    if (minuteUnit == null) {
      throw new AxelorException(
          appBase,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(BaseExceptionMessage.APP_BASE_NO_UNIT_MINUTES));
    }
    return minuteUnit;
  }

  @Override
  public BigDecimal getDailyWorkHours() throws AxelorException {
    AppBase appBase = getAppBase();
    BigDecimal dailyWorkHours = appBase.getDailyWorkHours();
    if (dailyWorkHours == null || dailyWorkHours.signum() <= 0) {
      throw new AxelorException(
          appBase,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(BaseExceptionMessage.APP_BASE_NO_UNIT_DAILY_WORK_HOURS));
    }
    return dailyWorkHours;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public void setManageMultiBanks(boolean manageMultiBanks) {
    getAppBase().setManageMultiBanks(manageMultiBanks);
  }

  @Override
  public int getProcessTimeout() {
    int processTimeout = getAppBase().getProcessTimeout();
    if (processTimeout < 1) {
      return 10;
    } else {
      return processTimeout;
    }
  }

  @Override
  public String getSireneUrl() throws AxelorException {
    AppBase appBase = getAppBase();
    String sireneUrl = appBase.getSireneUrl();
    if (sireneUrl != null) {
      return sireneUrl;
    } else {
      throw new AxelorException(
          appBase,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(BaseExceptionMessage.APP_BASE_SIRENE_API_URL_MISSING));
    }
  }
}
