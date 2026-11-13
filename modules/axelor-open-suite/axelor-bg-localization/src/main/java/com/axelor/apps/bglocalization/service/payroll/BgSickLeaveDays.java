package com.axelor.apps.bglocalization.service.payroll;

import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * РАЗДЕЛЯНЕТО НА ДНИТЕ — колко са за сметка на осигурителя, колко за сметка на ДОО, и защо.
 *
 * <p><b>Защо обект, а не две числа.</b> Разделянето може да СЕ ПРОВАЛИ: без осигурителен стаж не се
 * знае дали осигурителят дължи първите дни, а „не знам“ и „нула дни“ водят до различен фиш — отказ
 * срещу законно нула. Върнати като {@code int}, двете стават едно и също нула. Оттам правилото на
 * модула: {@code 0} НЕ Е РЕШЕНИЕ.
 *
 * <p><b>Двата броя мерят РАЗЛИЧНИ неща и не се сливат.</b> {@link #employerDays} са дните по чл. 40
 * ал. 5 КСО — те носят осигурителен доход и се плащат от работодателя. {@link #dooDays} са
 * останалите работни дни на неработоспособността в периода — те НЯМАТ осигурителен доход (НОИ плаща
 * обезщетението) и точно затова са базата на здравната вноска по ЗЗО чл. 40 ал. 1 т. 5.
 *
 * <p><b>⚠️ ДНИТЕ ЗА ДОО СА САМО ЗА ПОКАЗ И ЗА ЗДРАВНАТА ВНОСКА.</b> Сумата, която НОИ плаща
 * (Приложение 9, 80 или 90 на сто от осигурителния доход за 18 месеца), НЕ се смята тук и не е перо
 * на фиша — тя е WS-7. Тук се знае само КОЛКО ДНИ са.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и се произвежда от {@link BgSickLeaveResolver#split}.
 */
public final class BgSickLeaveDays {

  /**
   * РАБОТНИ дни за сметка на осигурителя, ПАДАЩИ В ПЕРИОДА. Единица: РАБОТНИ ДНИ.
   *
   * <p>{@code null} само когато разделянето е отказано — тогава {@link #problem} казва защо.
   */
  public final Integer employerDays;

  /**
   * РАБОТНИ дни на неработоспособността в периода БЕЗ осигурителен доход. Единица: РАБОТНИ ДНИ.
   *
   * <p>Тоест всички работни дни, покрити от епизоди и падащи в периода, МИНУС {@link
   * #employerDays}. {@code null} при отказ.
   */
  public final Integer dooDays;

  /**
   * От {@link #dooDays} — колко са БЕЗ ПРАВО на обезщетение (под прага на стажа по чл. 40 ал. 1
   * КСО). Единица: РАБОТНИ ДНИ.
   *
   * <p><b>⚠️ ПОДМНОЖЕСТВО, НЕ ТРЕТИ ПЛАТЕЦ.</b> Дните остават в {@link #dooDays}: здравната вноска
   * по ЗЗО чл. 40 ал. 1 т. 5 е за сметка на работодателя и при тях, а т. 9 на Приложение № 9 също
   * ги чете оттам. Различно е само мястото в Д1 — т. 16.4 вместо т. 16.2. {@code null} при отказ.
   */
  public final Integer withoutBenefitDays;

  /**
   * ЦЯЛО ИЗРЕЧЕНИЕ за човек — какво липсва и къде се поправя. {@code null}, когато разделянето е
   * станало.
   */
  public final String problem;

  /**
   * ОБЯСНЕНИЕТО на разделянето, епизод по епизод — за {@code detail} на изхода и за екрана.
   *
   * <p>Не е отпадък: „нула дни за сметка на осигурителя“ има поне четири различни причини
   * (майчинство, стаж под шест месеца, вид без обезщетение, епизод извън периода) и без изречение
   * те са неразличими на фиша.
   */
  public final List<String> witness;

  /**
   * СЪЩИТЕ ДНИ, В РАЗРЕЗА НА Т. 9 — по лист, по месец, по платец.
   *
   * <p><b>⚠️ ВТОРИ ИЗГЛЕД, НЕ ВТОРА СМЕТКА.</b> {@link #employerDays} и {@link #dooDays} НЕ се
   * изчисляват от този списък и не се менят от него — те си остават сметнати точно както преди
   * срезовете да съществуват. Списъкът се строи в СЪЩИЯ обход и от същите примитиви, тоест не може
   * да отговаря на друг въпрос. Че двата изгледа съвпадат, се пита от {@link #linesReconcile()}.
   *
   * <p>Празен при отказ и при „няма епизоди“ — там няма какво да се среже.
   */
  public final List<BgSickLeavePeriodSlice> periodLines;

  /**
   * Работни дни ЗА СМЕТКА НА ОСИГУРИТЕЛЯ, паднали в периода, които НИТО ЕДИН лист не покрива.
   * Единица: РАБОТНИ ДНИ.
   *
   * <p><b>⚠️ ЗАЩО ТОВА ИЗОБЩО Е ВЪЗМОЖНО.</b> {@code BgSickLeaveResolver#spellsOf} слепва в един
   * непрекъснат период и лист, обявен за ПРОДЪЛЖЕНИЕ, дори когато между двата има календарна
   * пролука. Дните в пролуката влизат в броенето, но не принадлежат на никой документ. Ред от т. 9
   * за тях не може да се създаде: Приложение № 9 се подава ЗА ЛИСТ, а тези дни лист нямат.
   *
   * <p>Почти винаги нула. Различно от нула значи, че някой лист е отметнат като продължение с
   * пролука — данни, които Ф1 трябва да види, преди да подаде удостоверение.
   */
  public final int uncoveredEmployerDays;

  /** Същото за дните без осигурителен доход. Единица: РАБОТНИ ДНИ. */
  public final int uncoveredDooDays;

  /**
   * {@link #employerDays}, РАЗПРЕДЕЛЕНИ ПО МЕСЕЦ НА НАСТЪПВАНЕ на неработоспособността — началото
   * на непрекъснатия период, не месецът на самия ден. Единица: РАБОТНИ ДНИ.
   *
   * <p><b>Защо.</b> Чл. 40 ал. 5 КСО плаща двата дни по „среднодневното брутно възнаграждение за
   * МЕСЕЦА, В КОЙТО Е НАСТЪПИЛА неработоспособността“. Лист от сряда 30.09 дава ден 01.10 на
   * октомврийския фиш, а базата му е септемврийската. Без месеца на настъпване до сумата стигаше
   * само броят и тя се смяташе по базата на фиша — тоест по друг месец.
   *
   * <p>Сборът е точно {@link #employerDays}. Празно при отказ, при „няма епизоди“ и при обект от
   * седемаргументния {@link #of} — той не знае месеца на настъпване и перо 195 от него НЕ се смята
   * (виж {@code BgSickLeaveResolver#sickPayLine}).
   */
  public final Map<YearMonth, Integer> employerDaysByOnset;

  private BgSickLeaveDays(
      Integer employerDays,
      Integer dooDays,
      Integer withoutBenefitDays,
      String problem,
      List<String> witness,
      List<BgSickLeavePeriodSlice> periodLines,
      int uncoveredEmployerDays,
      int uncoveredDooDays,
      Map<YearMonth, Integer> employerDaysByOnset) {
    this.employerDays = employerDays;
    this.dooDays = dooDays;
    this.withoutBenefitDays = withoutBenefitDays;
    this.problem = problem;
    this.witness = witness == null ? Collections.<String>emptyList() : witness;
    this.periodLines =
        periodLines == null
            ? Collections.<BgSickLeavePeriodSlice>emptyList()
            : Collections.unmodifiableList(periodLines);
    this.uncoveredEmployerDays = uncoveredEmployerDays;
    this.uncoveredDooDays = uncoveredDooDays;
    this.employerDaysByOnset =
        employerDaysByOnset == null
            ? Collections.<YearMonth, Integer>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(employerDaysByOnset));
  }

  /**
   * Разделянето е станало, заедно с разреза по т. 9 на Приложение № 9.
   *
   * <p>⚠️ НЯМА ПРЕТОВАРВАНЕ БЕЗ РАЗРЕЗ, и това е нарочно. Втори конструктор, който пълни само двата
   * броя, щеше да е тихият път към състояние, в което сметката съществува, а разрезът ѝ — не; а
   * точно това състояние е дефект. Единственият вход иска и двата изгледа заедно.
   */
  public static BgSickLeaveDays of(
      int employerDays,
      int dooDays,
      int withoutBenefitDays,
      List<String> witness,
      List<BgSickLeavePeriodSlice> periodLines,
      int uncoveredEmployerDays,
      int uncoveredDooDays) {
    return of(
        employerDays,
        dooDays,
        withoutBenefitDays,
        witness,
        periodLines,
        uncoveredEmployerDays,
        uncoveredDooDays,
        null);
  }

  /**
   * Същото, с дните на осигурителя по месец на настъпване — така го произвежда {@link
   * BgSickLeaveResolver#split}. Перо 195 се смята само от такъв обект.
   */
  public static BgSickLeaveDays of(
      int employerDays,
      int dooDays,
      int withoutBenefitDays,
      List<String> witness,
      List<BgSickLeavePeriodSlice> periodLines,
      int uncoveredEmployerDays,
      int uncoveredDooDays,
      Map<YearMonth, Integer> employerDaysByOnset) {
    return new BgSickLeaveDays(
        employerDays,
        dooDays,
        withoutBenefitDays,
        null,
        witness,
        periodLines,
        uncoveredEmployerDays,
        uncoveredDooDays,
        employerDaysByOnset);
  }

  /** Няма подадени епизоди — законен случай, не отказ: този месец няма неработоспособност. */
  public static BgSickLeaveDays none(String witness) {
    return new BgSickLeaveDays(0, 0, 0, null, Collections.singletonList(witness), null, 0, 0, null);
  }

  /** Разделянето НЕ можа да се направи. {@code problem} казва какво липсва. */
  public static BgSickLeaveDays blocked(String problem) {
    return new BgSickLeaveDays(
        null, null, null, problem, Collections.<String>emptyList(), null, 0, 0, null);
  }

  /** Дните по срезовете за сметка на осигурителя. Единица: РАБОТНИ ДНИ. */
  public int employerLineDays() {
    int sum = 0;
    for (BgSickLeavePeriodSlice line : periodLines) {
      if (line.isEmployerPaid()) {
        sum += line.workDays;
      }
    }
    return sum;
  }

  /** Дните по срезовете без осигурителен доход. Единица: РАБОТНИ ДНИ. */
  public int dooLineDays() {
    int sum = 0;
    for (BgSickLeavePeriodSlice line : periodLines) {
      if (!line.isEmployerPaid()) {
        sum += line.workDays;
      }
    }
    return sum;
  }

  /**
   * <b>ИНВАРИАНТЪТ НА ПРЕКРОЯВАНЕТО: двата изгледа към едни и същи дни се събират до едно и
   * също.</b>
   *
   * <p>{@code Σ(срезове на осигурителя) + непокрити == employerDays}, и същото за ДОО. Разминаване
   * значи, че срезовете отговарят на друг въпрос от агрегатите — тоест че разрезът е започнал да е
   * втора сметка. Точно това е дефектът, който прекрояването може да внесе, и затова се пита
   * изрично, а не се подразбира от зелени тестове върху сумите.
   *
   * <p><b>⚠️ ЛЪЖА ТУК НЕ БЛОКИРА РАЗДЕЛЯНЕТО, И ТОВА Е РЕШЕНИЕ.</b> Отказ би превърнал сгрешен
   * РАЗРЕЗ в липсващо ПЕРО — тоест дефект в изглед без читатели би спрял пари, които се дължат.
   * Затова разминаването се пише в обяснението ({@code BgSickLeaveResolver} добавя изречение) и се
   * заковава от свидетел, а сметката върви. Когато Ф1 му даде читател, гейтът е негов, не на фиша.
   */
  public boolean linesReconcile() {
    return isResolved()
        && employerLineDays() + uncoveredEmployerDays == employerDays
        && dooLineDays() + uncoveredDooDays == dooDays;
  }

  /** Станало ли е разделянето. */
  public boolean isResolved() {
    return problem == null;
  }

  /** Има ли изобщо дни неработоспособност в периода — за сметка на който и да е. */
  public boolean hasAnyDays() {
    return isResolved() && (employerDays > 0 || dooDays > 0);
  }

  /** Всички работни дни на неработоспособността в периода. Единица: РАБОТНИ ДНИ. */
  public int totalDays() {
    return isResolved() ? employerDays + dooDays : 0;
  }

  /** Обяснението, слепено в едно изречение. */
  public String witnessAsText() {
    return String.join(" ", witness);
  }

  @Override
  public String toString() {
    return isResolved()
        ? "BgSickLeaveDays[осигурител=" + employerDays + ", ДОО=" + dooDays + "]"
        : "BgSickLeaveDays[отказ: " + problem + "]";
  }
}
