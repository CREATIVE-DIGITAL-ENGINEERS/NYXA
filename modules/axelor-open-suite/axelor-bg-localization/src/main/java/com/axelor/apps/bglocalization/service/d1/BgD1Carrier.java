package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredActivityPeriod;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ЧИЙ Е ЗАПИСЪТ — една врата за двата носителя на фиша.
 *
 * <p><b>Защо съществува.</b> {@link BgD1RecordBuilder} чете шест неща от трудовия договор: лицето,
 * вида осигурен, кода на задълженото лице (т. 4), НКПД и НКИД (т. 12.1–12.3) и границите на
 * осигуряването (т. 14/15). Пет от шестте имат СЪВСЕМ ДРУГ източник, когато фишът е на
 * самоосигуряващо се лице — а шестото (НКПД/НКИД) при него изобщо не се попълва.
 *
 * <p><b>⚠️ ЕДИН НОСИТЕЛ, А НЕ ШЕСТ ТЕРНАРНИ ИЗРАЗА.</b> Разпръснато като {@code contract == null ?
 * … : …} на шест места, разклонението се разминава при първото добавяне на седмо поле: едно от
 * шестте остава на стария източник и записът излиза наполовина от единия носител и наполовина от
 * другия — всяко число поотделно правдоподобно.
 *
 * <p>⚠️ ЧИСТА ФУНКЦИЯ, БЕЗ ЗАЯВКИ. Всичко се чете от вече заредените обекти, точно както {@link
 * BgD1RecordBuilder} е построен.
 */
public final class BgD1Carrier {

  private final EmploymentContract contract;
  private final BgSelfInsuredRegistration registration;

  private BgD1Carrier(EmploymentContract contract, BgSelfInsuredRegistration registration) {
    this.contract = contract;
    this.registration = registration;
  }

  /**
   * Носителят на този фиш. Празният фиш дава носител без нито една страна — всичко е {@code null}.
   */
  public static BgD1Carrier of(BgPaySlip slip) {
    if (slip == null) {
      return new BgD1Carrier(null, null);
    }
    return new BgD1Carrier(slip.getEmploymentContract(), slip.getBgSelfInsuredRegistration());
  }

  public boolean isSelfInsured() {
    return registration != null;
  }

  public EmploymentContract getContract() {
    return contract;
  }

  public BgSelfInsuredRegistration getRegistration() {
    return registration;
  }

  /** Лицето — по договора или по регистрацията. */
  public Employee employee() {
    if (registration != null) {
      return registration.getEmployee();
    }
    return contract == null ? null : contract.getEmployee();
  }

  /** Видът осигурен (т. 12), необрязан от празни низове. */
  public String insuredTypeCode() {
    String code =
        registration != null
            ? registration.getBgInsuredTypeCode()
            : (contract == null ? null : contract.getBgInsuredTypeCode());
    if (code == null) {
      return null;
    }
    String trimmed = code.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Т. 4 — КОДЪТ НА ЗАДЪЛЖЕНОТО ЛИЦЕ, и двата източника са РАЗЛИЧНИ по устройство.
   *
   * <p>При наетия това е ЕИК по БУЛСТАТ на ПЛАЩАЩОТО дружество — задължено по наредбата е онова,
   * което плаща по договора. При самоосигуряващия се дружество няма: Наредба Н-13, указания за Д1
   * т. 4, дословно иска „ЕИК по БУЛСТАТ, ЕИК по ЗТРРЮЛНЦ, служебен номер, издаден от НАП“ и НЕ
   * различава случаите — за свободната професия това е собственият БУЛСТАТ на лицето, а за
   * съдружника, който се самоосигурява ЧРЕЗ дружеството, това е ЕИК на дружеството.
   *
   * <p>⚠️ Затова при него кодът се ВЪВЕЖДА на регистрацията, а не се извежда: извеждането му от
   * основанието щеше да е правило, което нормата не дава.
   */
  public String obligedPersonCode() {
    if (registration != null) {
      String code = registration.getBgObligedPersonCode();
      return code == null || code.trim().isEmpty() ? null : code.trim();
    }
    Company company = contract == null ? null : contract.getPayCompany();
    Partner partner = company == null ? null : company.getPartner();
    String code = partner == null ? null : partner.getRegistrationCode();
    return code == null || code.trim().isEmpty() ? null : code.trim();
  }

  /**
   * Дружеството, чиито НКПД/НКИД пълнят т. 12.1–12.3, или {@code null}.
   *
   * <p>⚠️ ПРИ САМООСИГУРЯВАЩИЯ СЕ ВИНАГИ {@code null}, И ТОВА НЕ Е ЛИПСА. Указанието към т.
   * 12.1/12.2/12.3 ги иска само за 18-те кода, които прилагат МОД, а нито един самоосигуряващ се
   * код не е сред тях ({@code bgAppliesMod = false} за 12, 13, 22 и 41 — измерено в seed-а). Тоест
   * дори да имаше дружество, точките нямаше да се попълнят.
   */
  public Company company() {
    if (registration != null) {
      return null;
    }
    return contract == null ? null : contract.getPayCompany();
  }

  /**
   * ДЕНЯТ, В КОЙТО ОСИГУРЯВАНЕТО ВЪЗНИКВА — началото на договора или на дейността.
   *
   * <p>⚠️ ПРИ САМООСИГУРЯВАЩИЯ СЕ ТОВА Е НАЙ-РАННИЯТ ПЕРИОД НА ДЕЙНОСТ, а не дата на регистрацията:
   * регистрацията е административен факт, а осигуряването тръгва от УПРАЖНЯВАНЕТО на трудова
   * дейност (КСО чл. 4 ал. 3 — „лицата, упражняващи трудова дейност като…“). Регистриран, но
   * неупражняващ дейност, човек не дължи вноски.
   */
  public LocalDate insuranceStart() {
    if (registration == null) {
      return contract == null ? null : contract.getStartDate();
    }
    LocalDate earliest = null;
    for (BgSelfInsuredActivityPeriod period : activityPeriods()) {
      LocalDate from = period.getFromDate();
      if (from != null && (earliest == null || from.isBefore(earliest))) {
        earliest = from;
      }
    }
    return earliest;
  }

  /**
   * ПОСЛЕДНИЯТ ДЕН В ОСИГУРЯВАНЕ, или {@code null}, когато то продължава.
   *
   * <p>⚠️ ПРАЗНОТО НА ПОСЛЕДНИЯ ПЕРИОД ЗНАЧИ „ВСЕ ОЩЕ ТЕЧЕ“, а не „свършва в деня, в който е
   * започнало“. Прочетено обратно, действащата регистрация щеше да дава по един ден в осигуряване
   * на месец — правдоподобно число, което никой контрол по обща сума не хваща.
   */
  public LocalDate insuranceEnd() {
    if (registration == null) {
      if (contract == null) {
        return null;
      }
      // ⚠️ bgTerminationDate ПЪРВО и endDate само като резерва — както в заварения строител.
      LocalDate end = contract.getBgTerminationDate();
      return end != null ? end : contract.getEndDate();
    }
    LocalDate latestFrom = null;
    LocalDate latestTo = null;
    for (BgSelfInsuredActivityPeriod period : activityPeriods()) {
      LocalDate from = period.getFromDate();
      if (from == null) {
        continue;
      }
      if (latestFrom == null || from.isAfter(latestFrom)) {
        latestFrom = from;
        latestTo = period.getToDate();
      }
    }
    return latestTo;
  }

  /**
   * ПРЕКЪСВАНИЯТА МЕЖДУ ПЕРИОДИТЕ НА ДЕЙНОСТ — пролуките, не свои редове.
   *
   * <p>⚠️ Един ред = един интервал В осигуряване; онова между два реда Е прекъсването. Обратното
   * (ред за прекъсване) би позволило интервал, който е едновременно в и извън осигуряване.
   *
   * <p>Форматът е същият като на {@code BgD1DaySource}: масив {@code [от, до]} включително.
   */
  public List<LocalDate[]> gaps() {
    if (registration == null) {
      return Collections.emptyList();
    }
    List<BgSelfInsuredActivityPeriod> periods = new ArrayList<>(activityPeriods());
    periods.removeIf(p -> p == null || p.getFromDate() == null);
    periods.sort((a, b) -> a.getFromDate().compareTo(b.getFromDate()));

    List<LocalDate[]> out = new ArrayList<>();
    for (int i = 0; i + 1 < periods.size(); i++) {
      LocalDate closes = periods.get(i).getToDate();
      LocalDate opens = periods.get(i + 1).getFromDate();
      if (closes == null) {
        // ⚠️ ОТВОРЕН ПЕРИОД ПРЕДИ СЛЕДВАЩ Е ДЕФЕКТ В ДАННИТЕ, не прекъсване: два интервала се
        // застъпват. Тук той се ПРЕСКАЧА мълчаливо, а обявяването му е на валидатора — този клас е
        // чист прочит и няма къде да сложи находка.
        continue;
      }
      LocalDate gapFrom = closes.plusDays(1);
      LocalDate gapTo = opens.minusDays(1);
      if (!gapFrom.isAfter(gapTo)) {
        out.add(new LocalDate[] {gapFrom, gapTo});
      }
    }
    return out;
  }

  private List<BgSelfInsuredActivityPeriod> activityPeriods() {
    List<BgSelfInsuredActivityPeriod> periods =
        registration == null ? null : registration.getBgActivityPeriodList();
    return periods == null ? Collections.emptyList() : periods;
  }

  /** Дали изобщо има носител — огледално на {@code BgSelfInsuredRules#refuseCarrier}. */
  public boolean isPresent() {
    return contract != null || registration != null;
  }

  /** Кратко име на носителя за съобщенията на находките. */
  public String describe() {
    if (registration != null) {
      return "регистрация на самоосигуряващо се лице";
    }
    return contract != null ? "трудов договор" : "няма носител";
  }
}
