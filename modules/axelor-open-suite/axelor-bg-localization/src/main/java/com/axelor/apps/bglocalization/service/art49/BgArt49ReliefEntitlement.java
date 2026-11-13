package com.axelor.apps.bglocalization.service.art49;

import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * КОИ ОБЛЕКЧЕНИЯ СЕ ИЗВЕЖДАТ ОТ КАРТОНА НА ЛИЦЕТО — чл. 18, чл. 22в и чл. 22г.
 *
 * <p>Чиста функция: от служителя, децата му и сумите за годината до колони 8, 15 и 16. Без база,
 * без Guice — сумите идват отвън, защото са ДАННИ ({@code BgPayrollParameter}), не константи в
 * Java.
 *
 * <p><b>═══ ⚠️ ЗАЩО САМО ТРИ, А НЕ ДЕВЕТ ═══</b>
 *
 * <p>Останалите шест колони (9, 10, 11, 12, 13, 14) са суми, които НЕ съществуват в модела: лични
 * вноски за доброволно осигуряване, вноски по чл. 9а КСО и дарения. Те не се извеждат от фиш по
 * устройство — чл. 49 ал. 3 т. 1 ги признава само „когато сумите са УДЪРЖАНИ ОТ РАБОТОДАТЕЛЯ при
 * изплащане на дохода“, тоест иска перо на фиша, каквото няма (виж и празната група {@code
 * SOCIAL_EXPENSES} в речника с типове пера и обявената липса на чл. 19 ал. 2 в манифеста на
 * параметрите). Тези колони са РЪЧЕН вход и се назовават в границите на справката, а не се пълнят
 * мълчаливо с нула.
 *
 * <p><b>═══ ⚠️ СУМИТЕ ЗА ГОДИНАТА — КОЕ Е СВЕРЕНО И КОЕ НЕ ═══</b>
 *
 * <ul>
 *   <li><b>Чл. 18 ал. 1 — 7920 лв.</b> Стои в самия ЗДДФЛ и справката НЕ препраща другаде за него.
 *       По фиксирания курс 1,95583 това е 4049,43 EUR. ⚠️ Числото се превалутира от ГОДИШНАТА сума
 *       в закона, а не като 12 × месечното: 12 × 337,45 = 4049,40, тоест трите стотинки разлика са
 *       от закръгляне на месечното, а чл. 18 ал. 1 казва своя си 7920.
 *   <li><b>Чл. 22в и чл. 22г — НЕ СЕ ЧЕТАТ ОТ ЧЛ. 22в АЛ. 1.</b> Указание т. 7 към образеца,
 *       дословно: „в колони 15 и 16 се посочва само годишният размер на съответното данъчно
 *       облекчение ПО § 3, АЛ. 1 И 2 ОТ ПЗР НА ЗДБРБ за 2025 г.“ Тоест размерът се определя от
 *       ЗАКОНА ЗА ДЪРЖАВНИЯ БЮДЖЕТ за съответната година, а не от стоящите 200/400/600 лв. в чл.
 *       22в ал. 1 и 2000 лв. в чл. 22г ал. 1. Този закон НЕ е наличен в тази среда — затова
 *       съответните параметри се оставят НЕЗАРЕДЕНИ и облекчението не се прилага, вместо да се
 *       приложи правдоподобно, но почти сигурно грешно число.
 * </ul>
 */
public final class BgArt49ReliefEntitlement {

  /** Пълнолетието по ЗЛС — същата граница, която ползва и {@code BgGarnishmentService}. */
  private static final int MAJORITY_AGE = 18;

  /** ГОДИШНОТО облекчение по чл. 18 ал. 1 в {@code BgPayrollParameter} — 7920 лв. = 4049,43 EUR. */
  public static final String PARAM_DISABILITY_ANNUAL = "DISABILITY_RELIEF_ANNUAL";

  /** Чл. 22в ал. 1 т. 1 — при ЕДНО ненавършило пълнолетие дете. */
  public static final String PARAM_CHILD_ONE = "CHILD_RELIEF_ONE";

  /** Чл. 22в ал. 1 т. 2 — при ДВЕ ненавършили пълнолетие деца. */
  public static final String PARAM_CHILD_TWO = "CHILD_RELIEF_TWO";

  /** Чл. 22в ал. 1 т. 3 — при ТРИ И ПОВЕЧЕ ненавършили пълнолетие деца. */
  public static final String PARAM_CHILD_THREE_PLUS = "CHILD_RELIEF_THREE_PLUS";

  /** Чл. 22г ал. 1 — за отглеждане на дете с 50 и над 50 на сто увреждане. */
  public static final String PARAM_CHILD_DISABILITY = "CHILD_DISABILITY_RELIEF";

  private BgArt49ReliefEntitlement() {}

  /** Сумите за годината, прочетени от каталога с параметри. {@code null} значи „не е зареден“. */
  public static class Amounts {

    /** Чл. 18 ал. 1 — годишният размер. */
    public BigDecimal disabilityAnnual;

    /** Чл. 22в ал. 1 т. 1. */
    public BigDecimal childOne;

    /** Чл. 22в ал. 1 т. 2. */
    public BigDecimal childTwo;

    /** Чл. 22в ал. 1 т. 3. */
    public BigDecimal childThreePlus;

    /** Чл. 22г ал. 1 — за ЕДНО дете с увреждане. */
    public BigDecimal childDisability;

    /** Има ли поне една от трите суми по чл. 22в. */
    public boolean hasChildAmounts() {
      return childOne != null || childTwo != null || childThreePlus != null;
    }
  }

  /**
   * ИЗВЕЖДА КОЛОНИ 8, 15 И 16 от картона на лицето.
   *
   * <p>Колони 9–14 остават нула — те са ръчен вход и обявена граница, а не пропуск на този метод.
   *
   * <p><b>⚠️ {@code null} В ЕДНА ОТ ТРИТЕ ЗНАЧИ „НЕ МОЖЕ ДА СЕ ИЗВЕДЕ“, А НЕ „НУЛА“.</b> Разликата
   * е цялата причина методът да не връща само числа. Нула е ОТГОВОР: лицето няма решение на ТЕЛК,
   * няма ненавършили пълнолетие деца. {@code null} е ЛИПСА НА ВЪПРОС: сумата за годината не е
   * заредена в каталога с параметри, тоест не се знае С КОЛКО се намалява основата. Слети в едно,
   * незаредена сума би изглеждала като „лицето няма деца“ — и повторното генериране би затрило
   * число, което човек е въвел на ръка. Повикващият пази старата стойност точно при {@code null}.
   */
  public static BgArt49Reliefs derive(Employee employee, Amounts amounts, int taxYear) {
    BgArt49Reliefs reliefs = new BgArt49Reliefs();
    Amounts a = amounts == null ? new Amounts() : amounts;
    List<BgEmployeeChild> children = childrenOf(employee);

    reliefs.disability =
        a.disabilityAnnual == null
            ? null
            : (hasDisabilityDecision(employee, taxYear) ? a.disabilityAnnual : BigDecimal.ZERO);

    int minors = minorChildren(children, taxYear);
    if (!a.hasChildAmounts()) {
      reliefs.children = null;
    } else {
      // ⚠️ Липсва САМО стъпалото на този брой деца — например заредени са сумите за едно и за две,
      // а лицето има три. Тогава пак не се знае с колко, значи пак е null, а не нула.
      reliefs.children = minors == 0 ? BigDecimal.ZERO : childAmountFor(minors, a);
    }

    int disabled = disabledChildren(children, taxYear);
    reliefs.disabledChildren =
        a.childDisability == null ? null : a.childDisability.multiply(BigDecimal.valueOf(disabled));
    return reliefs;
  }

  /**
   * ЧЛ. 18 АЛ. 1 — има ли лицето валидно решение за 50 и над 50 на сто намалена работоспособност.
   *
   * <p><b>⚠️ ГОДИНАТА НА ИЗТИЧАНЕ БРОИ ЦЯЛА.</b> Чл. 18 ал. 1, дословно: сумата се намалява със
   * 7920 лв. „ВКЛЮЧИТЕЛНО за годината на настъпване на неработоспособността и за годината на
   * изтичане срока на валидност на решението“. Тоест решение, изтекло на 3 февруари, дава ПЪЛНОТО
   * годишно облекчение за тази година — не две дванайсети. Мерено с {@code isBefore(31 декември)},
   * то би дало нула точно за случая, който нормата назовава изрично.
   *
   * <p>⚠️ Липсваща крайна дата се чете като „валидно“ — същият прочит, който месечното облекчение
   * по чл. 18 ал. 2 вече прави в {@code BgPaySlipService#disabilityRelief}. Две различни четения на
   * ЕДНО И СЪЩО поле биха дали лице с месечно облекчение, но без годишно, и изравняването щеше да
   * поиска от него данък заради непопълнена дата.
   */
  public static boolean hasDisabilityDecision(Employee employee, int taxYear) {
    if (employee == null || !employee.getBgDisabilityRelief()) {
      return false;
    }
    LocalDate validUntil = employee.getBgDisabilityValidUntil();
    return validUntil == null || validUntil.getYear() >= taxYear;
  }

  /**
   * ЧЛ. 22в — БРОЯТ на ненавършилите пълнолетие деца за данъчната година.
   *
   * <p><b>⚠️ ГОДИНАТА НА РАЖДАНЕ И ГОДИНАТА НА ПЪЛНОЛЕТИЕ БРОЯТ И ДВЕТЕ ЦЯЛА.</b> Чл. 22в ал. 7:
   * „Данъчното облекчение се ползва ВКЛЮЧИТЕЛНО за годината, в която детето е родено, навършило е
   * пълнолетие или е починало.“ Тоест дете, родено на 28 декември, брои за цялата тази година, и
   * дете, навършило 18 на 3 януари, също. Мерено „към 31 декември“, второто щеше да отпадне — точно
   * случаят, който ал. 7 назовава.
   *
   * <p>⚠️ Дете без дата на раждане НЕ се брои. Възрастта му не може да се пресметне, а по чл. 22в
   * ал. 9 ползване на облекчение в нарушение прави лицето задължено за съответната част от данъка;
   * непопълнено поле не е основание да се поеме такъв риск от негово име. Същият прочит като в
   * {@code BgGarnishmentService.isUndeterminable}.
   */
  public static int minorChildren(List<BgEmployeeChild> children, int taxYear) {
    int count = 0;
    for (BgEmployeeChild child : childrenOrEmpty(children)) {
      LocalDate born = child == null ? null : child.getBirthDate();
      if (born != null && born.getYear() <= taxYear && born.getYear() + MAJORITY_AGE >= taxYear) {
        count++;
      }
    }
    return count;
  }

  /**
   * ЧЛ. 22г — БРОЯТ на децата с 50 и над 50 на сто увреждане за данъчната година.
   *
   * <p><b>⚠️ ГРАНИЦАТА ЗА ВЪЗРАСТ Е ДРУГА, И ТОВА НЕ Е НЕДОГЛЕЖДАНЕ НА ЗАКОНОДАТЕЛЯ.</b> Чл. 22г
   * ал. 2 т. 2 иска детето да не е навършило пълнолетие „към 1 ЯНУАРИ на данъчната година“, докато
   * чл. 22в ал. 2 т. 2 просто иска да не е пълнолетно и ал. 7 му дава цялата година на
   * пълнолетието. Двете се разминават на ЕДИН ден в годината — за дете с рожден ден на 1 януари: то
   * е навършило 18 точно на измервателната дата на 22г, но годината на пълнолетието му е тази,
   * тоест 22в го брои. Копирано условие между двата члена изглежда вярно при всяка друга дата и
   * пада само тук.
   *
   * <p>⚠️ Решението трябва да е ВАЛИДНО и срокът му да е попълнен. Чл. 22г ал. 2 дава годината на
   * изтичане цяла, затова се сравняват ГОДИНИ. Липсващ срок при вдигнат признак „дете с увреждане“
   * НЕ се чете като пожизнено: „пожизнено решение“ и „никой не е попълнил докога“ изглеждат еднакво
   * в базата — същият довод, с който {@code BgGarnishmentService} обявява случая за неустановим.
   * Прочетено като валидно, то би поискало облекчение, за което лицето отговаря по ал. 8.
   */
  public static int disabledChildren(List<BgEmployeeChild> children, int taxYear) {
    LocalDate firstOfJanuary = LocalDate.of(taxYear, 1, 1);
    int count = 0;
    for (BgEmployeeChild child : childrenOrEmpty(children)) {
      if (child == null || !child.getIsDisabled()) {
        continue;
      }
      LocalDate born = child.getBirthDate();
      LocalDate until = child.getDisabilityValidTo();
      if (born != null
          && born.plusYears(MAJORITY_AGE).isAfter(firstOfJanuary)
          && until != null
          && until.getYear() >= taxYear) {
        count++;
      }
    }
    return count;
  }

  /**
   * СУМАТА ПО ЧЛ. 22в ЗА ТОЗИ БРОЙ ДЕЦА — стъпало, а не произведение.
   *
   * <p><b>⚠️ ЧЛ. 22в НЕ Е „ПО ТОЛКОВА НА ДЕТЕ“.</b> Ал. 1 изброява три отделни суми: „двеста лв. —
   * при ЕДНО … четиристотин — при ДВЕ … шестстотин — при ТРИ И ПОВЕЧЕ“. При четири деца сумата
   * остава тази за три и повече. Умножено по броя, семейство с пет деца би получило облекчение,
   * каквото нормата не дава.
   *
   * <p>⚠️ И точно този контраст показва, че чл. 22г Е на дете: там ал. 1 не изброява брой, а казва
   * едно число „за отглеждане на дете с … увреждане“. Законодателят е знаел как се пише стъпало и
   * го е написал в 22в; в 22г не го е написал.
   *
   * <p>⚠️ Липсваща сума за съответното стъпало връща {@code null} — облекчението НЕ се прилага и се
   * назовава в границите. Върнато като нула, то щеше да изглежда като „лицето няма деца“.
   */
  public static BigDecimal childAmountFor(int minorCount, Amounts amounts) {
    if (minorCount <= 0 || amounts == null) {
      return null;
    }
    if (minorCount == 1) {
      return amounts.childOne;
    }
    return minorCount == 2 ? amounts.childTwo : amounts.childThreePlus;
  }

  private static List<BgEmployeeChild> childrenOf(Employee employee) {
    return employee == null ? Collections.emptyList() : childrenOrEmpty(employee.getBgChildren());
  }

  private static List<BgEmployeeChild> childrenOrEmpty(List<BgEmployeeChild> children) {
    return children == null ? Collections.emptyList() : children;
  }
}
