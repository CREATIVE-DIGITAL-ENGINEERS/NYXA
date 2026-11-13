package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Localization;
import com.axelor.common.StringUtils;
import java.util.List;

/**
 * Езикът и часовата зона на справките на дружеството: „Български (България)“ и Europe/Sofia, когато
 * дружеството ги няма.
 *
 * <p><b>Защо на дружеството.</b> Всички PDF справки взимат двете оттук, не всяка поотделно:
 *
 * <ul>
 *   <li>датите на периода в custom справките (баланс, ОПР) се форматират в Java при изчислението
 *       ({@code AccountingReportValueAbstractService} → {@code CompanyDateService.checkLocale}):
 *       {@code Company.localization}, иначе {@code application.locale} от конфига — {@code en} →
 *       {@code M/d/yyyy}, тоест „1/1/2026 – 10/31/2026“;
 *   <li>часовата зона на BIRT ({@code BaseReportGenerator.getTimezone}), параметърът {@code
 *       Timezone} на счетоводните справки и „днес/сега“ на {@code AppBaseService}: {@code
 *       Company.timezone}, иначе тази на JVM — UTC в контейнера.
 * </ul>
 *
 * <p>BG модулът не ги попълваше никъде, затова всяко дружество печаташе американски дати и UTC час.
 *
 * <p>⚠️ <b>САМО ПРИ ПРАЗНА СТОЙНОСТ</b> — както {@code ensureDefaultPartnerLocalization}: изборът
 * на администратора не се пипа, запълва се празнота. Решението е отделено от базата, за да има
 * свидетел без Guice.
 */
public final class BgCompanyReportLocale {

  public static final String LOCALIZATION_CODE = "bg_BG";
  public static final String TIMEZONE = "Europe/Sofia";

  private BgCompanyReportLocale() {}

  /** Има ли дружеството празнота, която {@link #fillGaps} би запълнил. */
  public static boolean hasGaps(Company company) {
    return company != null
        && (company.getLocalization() == null || StringUtils.isBlank(company.getTimezone()));
  }

  /**
   * Попълва празните езика и часовата зона на справките.
   *
   * @param bulgarian редът {@code bg_BG} от номенклатурата; {@code null} — езикът остава празен
   * @return {@code true} само ако нещо е сложено сега
   */
  public static boolean fillGaps(Company company, Localization bulgarian) {
    if (company == null) {
      return false;
    }
    boolean changed = false;
    if (StringUtils.isBlank(company.getTimezone())) {
      company.setTimezone(TIMEZONE);
      changed = true;
    }
    if (company.getLocalization() == null && bulgarian != null) {
      company.setLocalization(bulgarian);
      changed = true;
    }
    return changed;
  }

  /**
   * Попълва празнотите на всички подадени дружества и казва колко е попълнил — за лога при старта.
   * Идемпотентно: второ извикване върху същите дружества не сменя нищо и връща нули.
   *
   * @param bulgarian редът {@code bg_BG}; {@code null} — езикът остава празен (виж {@link
   *     Summary#message()})
   */
  public static Summary fillAll(List<Company> companies, Localization bulgarian) {
    int seen = 0;
    int companiesFilled = 0;
    int timezones = 0;
    int localizations = 0;
    int localizationLeftEmpty = 0;
    for (Company company : companies) {
      if (company == null) {
        continue;
      }
      seen++;
      boolean noTimezone = StringUtils.isBlank(company.getTimezone());
      boolean noLocalization = company.getLocalization() == null;
      if (fillGaps(company, bulgarian)) {
        companiesFilled++;
      }
      if (noTimezone) {
        timezones++;
      }
      if (noLocalization) {
        if (bulgarian != null) {
          localizations++;
        } else {
          localizationLeftEmpty++;
        }
      }
    }
    return new Summary(seen, companiesFilled, timezones, localizations, localizationLeftEmpty);
  }

  /** Какво е направил {@link #fillAll}. */
  public static final class Summary {
    public final int companiesSeen;
    public final int companiesFilled;
    public final int timezonesFilled;
    public final int localizationsFilled;
    public final int localizationsLeftEmpty;

    Summary(int seen, int filled, int timezones, int localizations, int leftEmpty) {
      this.companiesSeen = seen;
      this.companiesFilled = filled;
      this.timezonesFilled = timezones;
      this.localizationsFilled = localizations;
      this.localizationsLeftEmpty = leftEmpty;
    }

    /** Ред за лога — пише се при всеки старт, и когато няма какво да се попълва. */
    public String message() {
      StringBuilder out =
          new StringBuilder("Company report locale: ")
              .append(companiesFilled)
              .append(" of ")
              .append(companiesSeen)
              .append(" company(ies) filled (timezone '")
              .append(TIMEZONE)
              .append("': ")
              .append(timezonesFilled)
              .append(", localization '")
              .append(LOCALIZATION_CODE)
              .append("': ")
              .append(localizationsFilled)
              .append("); only empty values are set, existing ones are kept.");
      if (localizationsLeftEmpty > 0) {
        out.append(" ")
            .append(localizationsLeftEmpty)
            .append(" company(ies) keep an empty localization: no '")
            .append(LOCALIZATION_CODE)
            .append("' row in base_localization.");
      }
      return out.toString();
    }
  }
}
