package com.axelor.apps.bglocalization.service;

import com.axelor.db.JPA;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ЕДИНСТВЕНОТО място, където се броят работни дни, и единственото, което знае докъде стига
 * производственият календар.
 *
 * <p><b>Защо е отделен сервиз.</b> Броенето на дни ще потрябва на всяко следващо перо, което мери
 * период — отпуски, болнични, престой. Ако всяко си го препише, проверката за покритие ще се
 * препише НЕПЪЛНО поне веднъж, и точно там ще излезе тихият надплатен резултат. Оттам и правилото:
 * който брои дни, минава оттук.
 *
 * <p><b>Кое е същинското.</b> „Няма празници в този интервал“ и „не знам какви са празниците“ дават
 * един и същ празен резултат от заявката, но са различни отговори: първото е факт, второто е
 * незнание. {@link Count#isReliable()} ги различава.
 */
public class BgWorkingDayService {

  /**
   * Резултатът от броенето: числото И доколко му се вярва.
   *
   * <p>Числото само по себе си лъже — то изглежда еднакво добре и когато празниците са приспаднати,
   * и когато просто ги няма в базата.
   */
  public static class Count {

    private final int workingDays;
    private final List<Integer> uncoveredYears;

    Count(int workingDays, List<Integer> uncoveredYears) {
      this.workingDays = workingDays;
      this.uncoveredYears = Collections.unmodifiableList(uncoveredYears);
    }

    public int getWorkingDays() {
      return workingDays;
    }

    /** Годините в интервала, за които календар НЯМА. Празен списък значи пълно покритие. */
    public List<Integer> getUncoveredYears() {
      return uncoveredYears;
    }

    /** Приспаднати ли са наистина празниците за целия интервал. */
    public boolean isReliable() {
      return uncoveredYears.isEmpty();
    }

    /** Годините като текст за съобщение — „2028“ или „2028 и 2029“. */
    public String uncoveredYearsAsText() {
      List<String> parts = new ArrayList<>();
      for (Integer y : uncoveredYears) {
        parts.add(String.valueOf(y));
      }
      if (parts.isEmpty()) {
        return "";
      }
      if (parts.size() == 1) {
        return parts.get(0);
      }
      return String.join(", ", parts.subList(0, parts.size() - 1))
          + " и "
          + parts.get(parts.size() - 1);
    }
  }

  /**
   * Работните дни в календарен срок, заедно с това дали числото е надеждно.
   *
   * @param start първият ден на срока
   * @param calendarDays дължината му в КАЛЕНДАРНИ дни
   */
  public Count count(LocalDate start, Integer calendarDays) {
    if (start == null || calendarDays == null || calendarDays <= 0) {
      return new Count(0, Collections.emptyList());
    }
    LocalDate end = start.plusDays(calendarDays - 1L);
    int days = BgCompensationResolver.workingDaysIn(start, calendarDays, holidaysIn(start, end));
    return new Count(days, uncoveredYearsIn(start, end));
  }

  /**
   * Годините, които интервалът докосва и за които календар няма.
   *
   * <p>⚠️ ПОКРИТИЕТО СЕ ЧЕТЕ ОТ {@code EventsPlanningLine.year}, а НЕ от името на плана.
   * Стандартният {@code EventsPlanning} няма нито код, нито период — единственото му поле е {@code
   * name}, а име е низ, който всеки може да смени от формата. {@code year} е задължително
   * типизирано поле и е единствената здрава дръжка в модела. Не се добавя ново поле заради това.
   *
   * <p>⚠️ Оттам следва и границата на понятието: „покрита“ значи „има поне един ред за тази
   * година“. Година с НУЛА неприсъствени дни би изглеждала непокрита — за България такава няма, но
   * допускането е записано, вместо да се крие. {@code BgHolidayManifestTest} пази и обратното: че
   * {@code year} съвпада с годината на датата, тоест ключът не се разминава с данните.
   */
  public List<Integer> uncoveredYearsIn(LocalDate from, LocalDate to) {
    List<Integer> missing = new ArrayList<>();
    if (from == null || to == null) {
      return missing;
    }
    Set<Integer> covered = coveredYears();
    for (int year = from.getYear(); year <= to.getYear(); year++) {
      if (!covered.contains(year)) {
        missing.add(year);
      }
    }
    return missing;
  }

  /** Годините, за които изобщо има зареден календар. */
  public Set<Integer> coveredYears() {
    return new HashSet<>(
        JPA.em()
            .createQuery("SELECT DISTINCT l.year FROM EventsPlanningLine l", Integer.class)
            .getResultList());
  }

  /**
   * ОФИЦИАЛНИТЕ ПРАЗНИЦИ В ИНТЕРВАЛА — ПУБЛИЧНАТА ВРАТА към същия шев.
   *
   * <p>⚠️ Публична, защото енджинът за болнични ({@code BgSickLeaveService}) му трябва СЪЩИЯТ
   * списък: той брои първите два работни дни на епизода и работните дни без осигурителен доход, а
   * „работен ден“ трябва да значи едно и също нещо на целия фиш. Втора заявка към {@code
   * EventsPlanningLine} в друг клас е свободна да се размине с тази, а разминаването се мери в дни
   * болничен.
   *
   * <p><b>⚠️ ЗАЩО НОВА ВРАТА, А НЕ {@code protected} → {@code public} НА {@link #holidaysIn}.</b>
   * Java не позволява стесняване на видимостта при override, а ДЕВЕТ теста вече подменят {@code
   * holidaysIn} като {@code protected} — разширена, тя ги поваля всичките с грешка в компилация.
   * Делегирането пази ЕДИН източник: тест, който подмени {@code holidaysIn}, сменя и каквото този
   * метод връща.
   */
  public Set<LocalDate> officialHolidaysIn(LocalDate from, LocalDate to) {
    return holidaysIn(from, to);
  }

  /** Официалните празници в интервала. Отделен метод, за да може да се подменя в тест. */
  protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      return Collections.emptySet();
    }
    return new HashSet<>(
        JPA.em()
            .createQuery(
                "SELECT l.date FROM EventsPlanningLine l WHERE l.date BETWEEN :from AND :to",
                LocalDate.class)
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList());
  }
}
