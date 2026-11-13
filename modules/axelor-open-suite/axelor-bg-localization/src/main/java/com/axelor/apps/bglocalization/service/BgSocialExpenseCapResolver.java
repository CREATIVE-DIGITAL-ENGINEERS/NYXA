package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSocialExpenseCap;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * КАПАЧКИТЕ НА СОЦИАЛНИТЕ РАЗХОДИ — колко от начисленото е освободено и каква е месечната ЗКПО
 * основа.
 *
 * <p><b>Чист. Без база, без Guice, без Hibernate.</b> Капачката се ПОДАВА — редът идва от {@code
 * BgSocialExpenseCap} за периода, точно както {@code BgGarnishmentResolver} получава скалата си, а
 * {@code BgContributionResolver} — ставките си. Оттам следва, че всяко число тук е проверимо с един
 * assert, а не с вдигнато приложение.
 *
 * <p><b>═══ АЛГОРИТЪМЪТ ═══</b> При подадени позиции {@code a₁…aₙ} с общ размер {@code T}, лична
 * капачка {@code P}, фирмена {@code C} и вече използвано в периода {@code U}:
 *
 * <pre>
 *   паднало условие + bgWholeAmountOnConditionFail  →  освободено 0, превишение T,
 *                                                      ЗКПО основа = T          (чл. 213 ал. 3 / чл. 214 ал. 3)
 *   иначе          освободено = min(T, P − U)                                   (чл. 24 ал. 2 ЗДДФЛ)
 *                  превишение = T − освободено
 *                  ЗКПО основа = max(0, T − (C − U)), или 0 без фирмена ос      (чл. 213 ал. 2 / чл. 214 ал. 2)
 * </pre>
 *
 * <p><b>═══ ⚠️ ДВЕ ОСИ, ДВЕ СМЕТКИ, И ТЕ НЕ СЕ ИЗВЕЖДАТ ЕДНА ОТ ДРУГА ═══</b> Личната капачка казва
 * какво НЕ се облага при лицето (ЗДДФЛ), фирмената — каква е данъчната основа за данъка върху
 * разходите (ЗКПО). Днес двете суми съвпадат за двете капачки, които имат и двете оси, и въпреки
 * това се смятат поотделно: сметнати като едно, разминаването щеше да е НЕПРЕДСТАВИМО, а не
 * невъзможно. Еднократната помощ вече е доказателството, че двете оси не са една — тя има лична
 * капачка (2400 лв. по чл. 24 ал. 2 т. 11 ЗДДФЛ) и НЯМА фирмена: не е предоставена в натура, тоест
 * попада в чл. 205, не в чл. 204 ал. 1 т. 2, и данък върху разходите за нея не се дължи изобщо.
 *
 * <p><b>═══ ⚠️ ЕДНА КАПАЧКА, НЯКОЛКО ПОЗИЦИИ — И ЗАТОВА ВХОДЪТ Е СПИСЪК ═══</b> ЗКПО чл. 213 ал. 2,
 * ДОСЛОВНО: „Данъчната основа за определяне на данъка върху разходите за календарния месец по чл.
 * 204, ал. 1, т. 2, буква „а“ е ПРЕВИШЕНИЕТО НА ТЕЗИ РАЗХОДИ над 60 лв. месечно за всяко наето
 * лице.“ „Тези разходи“ са трите на буква „а“ — допълнително доброволно осигуряване, доброволно
 * здравно осигуряване и застраховки „Живот“ — взети ЗАЕДНО. Същото и в ЗДДФЛ чл. 24 ал. 2 т. 11:
 * „при: раждане или осиновяване на дете, сключване на граждански брак или смърт на член от
 * семейството — ОБЩО до 2400 лв.“ Думата „общо“ е в закона. Резолвер, който гледа една позиция, би
 * дал на всяка от трите цялата капачка.
 *
 * <p><b>═══ ⚠️ ГОДИШНАТА КАПАЧКА ИСКА {@code usedEarlierInPeriod}, ИНАК СЕ ПОДНОВЯВА ВСЕКИ МЕСЕЦ
 * ═══ </b> Затова {@code null} за годишна капачка е ОТКАЗ, а не нула: „не е питано“ и „нищо не е
 * използвано“ дават едно и също число и второто раждане в една календарна година щеше да получи
 * ново облекчение от 1227,10 EUR. За месечна капачка периодът Е месецът и {@code null} се чете като
 * нула.
 *
 * <p><b>═══ НОРМИТЕ, ДОСЛОВНО ═══</b> Сверено срещу ЗКПО и ЗДДФЛ (редакции 2026 г.).
 *
 * <p><b>ЗКПО чл. 213 ал. 3:</b> „Когато не е изпълнено условието за освобождаване от данък по чл.
 * 208, данъчната основа за определяне на данъка върху разходите е ЦЕЛИЯТ РАЗМЕР на начислените
 * разходи за календарния месец.“ <b>Чл. 214 ал. 3:</b> „Когато не са изпълнени условията за
 * освобождаване от данък по чл. 209а, данъчната основа за определяне на данъка върху разходите е
 * ЦЕЛИЯТ РАЗМЕР на начислените разходи за календарния месец.“
 *
 * <p>⚠️ Тоест паднало условие не НАМАЛЯВА облекчението, а го ЗАЛИЧАВА. Това е и причината изходът
 * да носи {@code companyTaxBase} отделно от {@code excess}: в този случай двете се разминават, и то
 * трикратно.
 *
 * <p><b>Границата на този резолвер.</b> Той отговаря на ЕДИН въпрос — как се дели начисленото. Дали
 * позицията е социален разход, дали е в натура по § 1 т. 34 ДР ЗКПО, дали е осигурителен доход по
 * НЕВД и кой ред на фиша носи кое — това са ПРИЗНАЦИ НА ПЕРОТО, декларирани в речника, и този
 * резолвер не ги пипа. Той не знае и нито един код на капачка: кои условия обвързват реда се чете
 * от самия ред.
 */
public final class BgSocialExpenseCapResolver {

  /**
   * ⚠️ РЕШЕНИЕ — НАИМЕНУВАН ШЕВ: КАК СЕ ДЕЛИ ПРЕВИШЕНИЕТО МЕЖДУ ПОЗИЦИИТЕ ПОД ОБЩА КАПАЧКА.
   * ПРОПОРЦИОНАЛНО НА РАЗМЕРА ИМ.
   *
   * <p><b>Защо това е РЕШЕНИЕ, а не норма.</b> Законът НЕ казва как се дели. ЗКПО чл. 213 ал. 2
   * дава превишението върху СБОРА („превишението на тези разходи над 60 лв.“) и с това приключва:
   * за данъка върху разходите пер-позиция разбивка не съществува и не е нужна. Разбивката
   * съществува САМО защото фишът печата отделен ред за всяка позиция — тоест тя е изискване на
   * документа, не на данъка.
   *
   * <p><b>Какво щеше да е другото.</b> Изчерпване по ред („първата позиция изяжда капачката, после
   * следващата“). То дава СЪЩИТЕ сборове и различни числа по ред, тоест видът на фиша щеше да
   * зависи от подредбата на перата — и две фирми с еднакви разходи щяха да получат различни фишове.
   * При пропорцията позиция с 20,00 и позиция с 40,00 носят превишение 1:2 независимо от
   * подредбата.
   *
   * <p><b>⚠️ И ТОВА Е ПРИЧИНАТА КОНСТАНТАТА ДА Е НАИМЕНУВАНА, а не пропорция, размазана във
   * формулата</b> — по същия довод като {@code BgEarningsResolver#SENIORITY_FOLLOWS_WORKED_TIME}:
   * обръщането ѝ на {@code false} поваля свидетеля с трите пера в {@code
   * BgSocialExpenseCapResolverTest}, тоест правилото е проверимо. Смени ли се някога решението, се
   * сменя един ред — не се търси къде е скрито делението.
   *
   * <p>⚠️ {@code false} НЕ Е РЕАЛИЗИРАН. Константата е наименувана, за да е ВИДИМО, че
   * разпределението е избор — не за да предложи втори режим, който никой не е поръчал. Свален, тя
   * вали {@link #apply} с назована причина, вместо да смята по неутвърдено правило.
   */
  public static final boolean EXCESS_SPLIT_FOLLOWS_SHARE = true;

  /**
   * Групата на социалните разходи — първата цифра на номера на перото.
   *
   * <p>⚠️ ЖИВЕЕ ТУК, А НЕ В {@code BgPaySlipResolver}. Онзи резолвер брои брутото, осигурителния
   * доход, данъчната основа и нетото, и нито един от тези сборове не се мени от 2xx: перата от 2xx
   * влизат в тях по ФЛАГОВЕТЕ си като всяко друго перо. Добавена там, константата щеше да покани
   * следващия читател да мисли, че групата е получила собствено място в агрегата на Д1/Д6 — а тя не
   * е и не бива.
   */
  public static final int GROUP_SOCIAL_EXPENSES = 2;

  /**
   * Работната точност на деленето при пропорцията. Десет знака държат 1/3 далеч от втория знак;
   * закръглянето, което има значение, става веднъж — виж {@link #split}.
   */
  static final int WORKING_SCALE = 10;

  private static final BigDecimal CENT = new BigDecimal("0.01");

  /** MONTHLY / ANNUAL — стойностите на bg.social.expense.periodicity.select. */
  public static final String MONTHLY = "MONTHLY";

  public static final String ANNUAL = "ANNUAL";

  private BgSocialExpenseCapResolver() {}

  /** Едно перо-позиция на входа: кодът му и начислената сума. */
  public static final class Item {

    public final String code;
    public final BigDecimal amount;

    public Item(String code, BigDecimal amount) {
      this.code = code;
      this.amount = amount;
    }
  }

  /**
   * ⚠️ ЧИСТ ТЕСТ „ТОВА ПЕРО СОЦИАЛЕН РАЗХОД ЛИ Е“ — без база и без прочит на речника.
   *
   * <p>Номерът е КОПИРАН на самото перо ({@code bgLineNumber}), а не се чете през типа, тоест
   * отговорът е наличен и за фиш, чиито типове не са заредени. Точно това позволява на {@code
   * BgPaySlipService} да излезе от сметката, ПРЕДИ да е поискал каквото и да е от базата — и затова
   * всеки заварен фиш без 2xx перо минава през новия шев, без да пипне Guice.
   *
   * <p>⚠️ Без проверка за null върху номера: генерираният getter за Integer връща 0, тоест
   * непопълнен номер дава група 0 — „никоя“, което е и правилният отговор.
   */
  public static boolean isSocialExpenseLine(BgPaySlipLine line) {
    return line != null && line.getBgLineNumber() / 100 == GROUP_SOCIAL_EXPENSES;
  }

  /**
   * ⚠️ ПЕРО ОТ 2xx, КОЕТО СИСТЕМАТА СМЯТА — DERIVED по речника. Днес това са точно седемте реда
   * „превишение над капачката“ (201, 203, 205, 211, 221, 223, 225); инвариантът „DERIVED 2xx ⟺
   * bgIsCapExcess“ се пази от {@code BgSocialExpenseSeedFileTest}.
   *
   * <p>Чете се ТИПЪТ, не копие на реда: изчислителният начин не се копира на перото, а речникът е
   * мястото, където „кой го смята“ е решено.
   */
  public static boolean isDerivedSocialExpenseLine(BgPaySlipLine line) {
    BgPaySlipLineType type = line == null ? null : line.getBgPaySlipLineType();
    return isSocialExpenseLine(line)
        && type != null
        && BgEarningsResolver.CALC_DERIVED.equals(type.getBgCalculationBase());
  }

  /**
   * ⚠️ РЪЧНО ВЪВЕДЕНО „ПРЕВИШЕНИЕ НАД КАПАЧКАТА“ — причина за отказ, или {@code null}.
   *
   * <p><b>Защо отказ, а не тихо триене.</b> Превишението е ИЗХОД на {@link #apply}, а не вход:
   * резолверът чете само реда „в рамките“ и дели ЦЯЛОТО начислено на него. Ръчен ред „превишение“
   * следователно е или ВТОРО броене на същите пари (цялата сума е и на „в рамките“), или разделяне,
   * при което основата по ЗКПО излиза НУЛА (на „в рамките“ е само прагът). И двата фиша изглеждат
   * пресметнати. Изтрит мълчаливо, редът щеше да смени нетото на човек, който не е разбрал защо —
   * затова се назовава и се оставя на оператора.
   *
   * <p>⚠️ ГЕНЕРИРАНИЯТ РЕД НЕ Е РЪЧЕН. Превишенията над тавана на еднократните помощи (221/223/225)
   * ги пише {@code BgPaySlipService#generateSocialExpenseExcess} с {@code bgIsGenerated = true} —
   * тук те се пропускат. Ред, вдигнал признака покрай генератора (REST), не печели нищо: при всяко
   * „Преизчисли“ генераторът пренаписва или маха своите редове, а „Потвърди“ иска отпечатъка.
   */
  public static String refuseManualDerivedSocialExpense(List<BgPaySlipLine> lines) {
    if (lines == null) {
      return null;
    }
    List<String> found = new ArrayList<>();
    for (BgPaySlipLine line : lines) {
      if (isDerivedSocialExpenseLine(line) && !Boolean.TRUE.equals(line.getBgIsGenerated())) {
        found.add(line.getBgLineNumber() + " „" + line.getName() + "“");
      }
    }
    if (found.isEmpty()) {
      return null;
    }
    return "Перото "
        + String.join(", ", found)
        + " е превишение над капачката на социалния разход и се смята от системата — не се въвежда"
        + " ръчно. Въведете ЦЯЛАТА начислена сума на съответното перо „… — в рамките на капачката“"
        + " и махнете този ред: системата сама дели освободената част от превишението и смята"
        + " основата за данъка върху разходите по ЗКПО чл. 213 ал. 2 / чл. 214 ал. 2.";
  }

  /**
   * РАЗДЕЛЯ НАЧИСЛЕНОТО на освободена част и превишение и дава месечната ЗКПО основа.
   *
   * @param items позициите под ЕДНА капачка, в реда, в който ще се отпечатат. Празен списък дава
   *     нули без отказ — месец без такъв разход е законно състояние
   * @param cap редът на капачката, ВАЛИДЕН ЗА ПЕРИОДА. {@code null} е ОТКАЗ: без него капачката е
   *     неизвестна, а нула тук би значела „нищо не е освободено“ и би обложила цялата сума
   * @param conditions състоянието на трите условия. {@code null} се чете като {@link
   *     BgSocialExpenseConditions#allMet()} — оптимистичното подразбиране, същото като липсващ ред
   *     в {@code BgSocialExpenseException}
   * @param usedEarlierInPeriod вече използвано от капачката в СЪЩИЯ период. Задължително за годишна
   *     капачка ({@code null} там е ОТКАЗ); за месечна {@code null} се чете като нула
   */
  public static BgSocialExpenseOutcome apply(
      List<Item> items,
      BgSocialExpenseCap cap,
      BgSocialExpenseConditions conditions,
      BigDecimal usedEarlierInPeriod) {

    if (!EXCESS_SPLIT_FOLLOWS_SHARE) {
      return BgSocialExpenseOutcome.refused(
          "EXCESS_SPLIT_FOLLOWS_SHARE е свален, а второ правило за разпределение НЕ е реализирано —"
              + " сметка по неутвърдено правило не се прави");
    }
    if (cap == null) {
      return BgSocialExpenseOutcome.refused(
          "капачката за периода не е заредена — без нея освободената част е неизвестна. Нула тук би"
              + " значела „нищо не е освободено“ и би обложила цялата сума, а това не е отговор на"
              + " липсващ ред. Пусни App Install за bg_social_expense_cap.csv");
    }
    List<Item> list = items == null ? new ArrayList<>() : items;
    for (Item item : list) {
      if (item == null || item.amount == null || item.amount.signum() < 0) {
        return BgSocialExpenseOutcome.refused(
            "позиция с празна или отрицателна сума — социален разход е разход, а знакът е на типа"
                + " перо, не на сумата");
      }
    }

    boolean annual = ANNUAL.equals(cap.getBgPeriodicity());
    if (annual && usedEarlierInPeriod == null) {
      return BgSocialExpenseOutcome.refused(
          "капачката е ГОДИШНА, а вече използваното за годината не е подадено — прочетено като нула,"
              + " тя се подновява всеки месец и второто събитие в една календарна година получава"
              + " ново облекчение");
    }
    BigDecimal used =
        usedEarlierInPeriod == null ? BigDecimal.ZERO : usedEarlierInPeriod.max(BigDecimal.ZERO);

    // ⚠️ signum(), НЕ == null: празната клетка стига до базата като нула и генерираният getter за
    // decimal не връща null. Проверка за null тук би била мъртъв код — виж BgDefaultedGetterTest.
    if (cap.getBgPersonalCapAmount().signum() <= 0) {
      return BgSocialExpenseOutcome.refused(
          "редът „"
              + cap.getCode()
              + "“ носи празна лична капачка — празна клетка и нула са неразличими в базата, тоест"
              + " това е недовършен ред, а не капачка от нула лева");
    }

    BigDecimal total = BigDecimal.ZERO.setScale(2);
    for (Item item : list) {
      total = total.add(item.amount);
    }

    String failed = failedConditions(cap, conditions);
    if (failed != null && !cap.getBgWholeAmountOnConditionFail()) {
      return BgSocialExpenseOutcome.refused(
          "редът „"
              + cap.getCode()
              + "“ обявява условие, но не обявява последствие: bgWholeAmountOnConditionFail е свален,"
              + " а чл. 213 ал. 3 и чл. 214 ал. 3 не дават друго последствие освен целия размер."
              + " Паднало условие ("
              + failed
              + ") няма къде да отиде");
    }

    if (failed != null) {
      // ЧЛ. 213 АЛ. 3 / ЧЛ. 214 АЛ. 3 — ЦЕЛИЯТ РАЗМЕР. Освободено НЯМА: облекчението е заличено, не
      // намалено. Оттам и че тук пропорция не се смята — всяка позиция влиза цяла.
      List<BgSocialExpenseOutcome.Share> shares = new ArrayList<>();
      for (Item item : list) {
        shares.add(
            new BgSocialExpenseOutcome.Share(
                item.code, item.amount, BigDecimal.ZERO.setScale(2), item.amount));
      }
      return BgSocialExpenseOutcome.computed(
          shares, BigDecimal.ZERO.setScale(2), total, companyBase(cap, total, total), failed);
    }

    BigDecimal personalRoom = cap.getBgPersonalCapAmount().subtract(used).max(BigDecimal.ZERO);
    BigDecimal exemptTotal = total.min(personalRoom).setScale(2, RoundingMode.HALF_UP);

    List<BigDecimal> exemptByItem = split(list, total, exemptTotal);
    List<BgSocialExpenseOutcome.Share> shares = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      BigDecimal exempt = exemptByItem.get(i);
      shares.add(
          new BgSocialExpenseOutcome.Share(
              list.get(i).code, list.get(i).amount, exempt, list.get(i).amount.subtract(exempt)));
    }

    BigDecimal excessTotal = total.subtract(exemptTotal);
    BigDecimal companyRoom = cap.getBgCompanyCapAmount().subtract(used).max(BigDecimal.ZERO);
    return BgSocialExpenseOutcome.computed(
        shares,
        exemptTotal,
        excessTotal,
        companyBase(cap, total, total.subtract(companyRoom).max(BigDecimal.ZERO)),
        null);
  }

  /**
   * МЕСЕЧНАТА ЗКПО ОСНОВА, или нула когато данък върху разходите не се дължи изобщо.
   *
   * <p>⚠️ {@code bgCompanyTaxApplies} се пита ПРЕДИ сумите, а не се извежда от празна фирмена
   * капачка. ЗКПО дели социалните разходи на две: предоставените в натура се облагат с данък върху
   * разходите (чл. 204 ал. 1 т. 2), а тези, които НЕ са в натура и представляват доход на физическо
   * лице — по реда на ЗДДФЛ (чл. 205). Еднократната помощ при раждане е във втория случай. Изведено
   * от празна клетка, тя щеше да влезе ЦЯЛА в данъчната основа на предприятието, защото празно и
   * нула са едно и също число в тази база.
   */
  private static BigDecimal companyBase(BgSocialExpenseCap cap, BigDecimal total, BigDecimal base) {
    if (!cap.getBgCompanyTaxApplies()) {
      return BigDecimal.ZERO.setScale(2);
    }
    if (cap.getBgCompanyCapAmount().signum() <= 0) {
      // Обявена фирмена ос с празна капачка значи „всичко влиза в основата“ — и това е ВЕРНИЯТ
      // прочит, а не пропуск: норма без праг облага целия начислен разход (напр. чл. 210 ал. 2 при
      // транспорт с лек автомобил). Затова тук няма отказ, а сборът.
      return total.setScale(2, RoundingMode.HALF_UP);
    }
    // ⚠️ setScale(2) НЕ Е КОЗМЕТИКА. BigDecimal.max връща операнда КАКТО Е, тоест при отрицателна
    // разлика оттам излиза BigDecimal.ZERO със скала 0, а „0“ и „0.00“ НЕ са equals. Изпуснато,
    // сравнението на две нули пада — и то само в случая, в който всичко е под капачката.
    return base.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * ПРОПОРЦИОНАЛНОТО РАЗПРЕДЕЛЕНИЕ НА ОСВОБОДЕНАТА ЧАСТ, със сбор ТОЧНО {@code exemptTotal}.
   *
   * <p><b>⚠️ ДЕЛИ СЕ ОСВОБОДЕНОТО, А НЕ ПРЕВИШЕНИЕТО, И РАЗЛИКАТА НЕ Е КОЗМЕТИКА.</b> Превишението
   * се получава като {@code сума − освободено} за всяка позиция, тоест БЕЗ второ деление и без
   * второ закръгляне. Разпределени поотделно, двете биха имали два независими остатъка и сборът им
   * нямаше да е сумата на перото — на фиша това изглежда като изчезнала стотинка.
   *
   * <p><b>⚠️ ОСТАТЪКЪТ ОТ ЗАКРЪГЛЯНЕТО НЕ СЕ ИЗПУСКА.</b> Три равни позиции по 20,00 EUR под
   * капачка 30,68 дават точен дял 10,2266… — закръглен нагоре, сборът става 30,69 (над капачката),
   * закръглен надолу — 30,66 (две стотинки под нея). Затова: всяка позиция се отсича НАДОЛУ, а
   * липсващите стотинки се разпределят по НАЙ-ГОЛЯМ ОСТАТЪК, при равенство — по реда на подаване.
   * Резултатът е 10,23 / 10,23 / 10,22, тоест превишение 9,77 / 9,77 / 9,78 и сбор точно 29,32.
   *
   * <p>⚠️ Оттам следва, че „по 9,77 на перо“ е с една стотинка по-малко от истината: 3 × 9,77 =
   * 29,31, а превишението е 29,32. Стотинката се вижда, вместо да се изгуби — точно това пази
   * свидетелят в {@code BgSocialExpenseCapResolverTest}.
   */
  private static List<BigDecimal> split(
      List<Item> items, BigDecimal total, BigDecimal exemptTotal) {
    List<BigDecimal> out = new ArrayList<>();
    if (items.isEmpty()) {
      return out;
    }
    if (total.signum() == 0 || exemptTotal.signum() == 0) {
      for (int i = 0; i < items.size(); i++) {
        out.add(BigDecimal.ZERO.setScale(2));
      }
      return out;
    }

    List<BigDecimal> remainder = new ArrayList<>();
    BigDecimal assigned = BigDecimal.ZERO.setScale(2);
    for (Item item : items) {
      BigDecimal raw =
          item.amount.multiply(exemptTotal).divide(total, WORKING_SCALE, RoundingMode.HALF_UP);
      BigDecimal floor = raw.setScale(2, RoundingMode.DOWN);
      out.add(floor);
      remainder.add(raw.subtract(floor));
      assigned = assigned.add(floor);
    }

    // Липсващите стотинки — по най-голям остатък, при равенство по реда на подаване.
    int cents =
        exemptTotal
            .subtract(assigned)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      order.add(i);
    }
    final List<BigDecimal> fractions = remainder;
    order.sort(Comparator.comparing((Integer i) -> fractions.get(i)).reversed());
    for (int k = 0; k < cents && k < order.size(); k++) {
      int i = order.get(k);
      out.set(i, out.get(i).add(CENT));
    }
    return out;
  }

  /**
   * ИМЕНАТА на условията, които са паднали за ТАЗИ капачка, или {@code null}.
   *
   * <p><b>⚠️ КОИ УСЛОВИЯ ОБВЪРЗВАТ РЕДА СЕ ЧЕТЕ ОТ РЕДА, А НЕ ОТ КОДА МУ.</b> Този резолвер не
   * познава нито един код на капачка. Написано с {@code if ("FOOD_VOUCHERS".equals(...))}, четвърта
   * капачка щеше да иска комит в Java, а нова редакция на чл. 209а — да се търси къде е скрит
   * кодът.
   *
   * <p><b>⚠️ И СЕ НАЗОВАВАТ ВСИЧКИТЕ ПАДНАЛИ, НЕ ПЪРВОТО.</b> Трите условия водят до един и същ
   * резултат — цялата сума в основата — тоест поправянето на едното не вдига облекчението, ако
   * другото също е паднало. Съобщение с едно име щеше да прати счетоводителя да търси лиценз на
   * оператор и да се върне със същата данъчна основа.
   */
  private static String failedConditions(
      BgSocialExpenseCap cap, BgSocialExpenseConditions conditions) {
    BgSocialExpenseConditions c =
        conditions == null ? BgSocialExpenseConditions.allMet() : conditions;
    List<String> failed = new ArrayList<>();
    if (cap.getBgRequiresNoPublicDebts() && c.publicDebts) {
      failed.add(
          "публични задължения към края на месеца (ЗКПО чл. 208 / чл. 209а ал. 1 т. 2) — ФИРМЕНО");
    }
    if (cap.getBgRequiresSalaryNotBelowAverage() && c.salaryBelowThreeMonthAverage) {
      failed.add(
          "договорената основна заплата за месеца е ПОД средномесечната договорена за предходните три"
              + " (ЗКПО чл. 209а ал. 1 т. 1) — ПЕР-ЛИЦЕ");
    }
    if (cap.getBgRequiresLicensedOperator() && c.operatorUnlicensed) {
      failed.add("операторът не е лицензиран (ЗКПО чл. 209а ал. 1 т. 3) — ФИРМЕНО");
    }
    return failed.isEmpty() ? null : String.join("; ", failed);
  }
}
