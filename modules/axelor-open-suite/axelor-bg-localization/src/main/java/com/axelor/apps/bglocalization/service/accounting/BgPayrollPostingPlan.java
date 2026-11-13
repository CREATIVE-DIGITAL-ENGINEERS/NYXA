package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgCivilContractResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ПОСТИНГ МОДЕЛЪТ: перата на фиша → двойки сметки, БЕЗ база и без Guice.
 *
 * <p><b>⚠️ ЧИСТА ФУНКЦИЯ, И ТОВА Е ИЗИСКВАНЕ, А НЕ СТИЛ.</b> Модулът няма mock-подпора: правило,
 * което иска свидетел с ЧИСЛА, отива в чиста функция, инак се сверява само структурно. Тук числата
 * са целият въпрос — статия, която не балансира до стотинка, не е статия, — затова целият модел се
 * пуска в тест без Postgres и без Hibernate. Услугата отгоре ({@code BgPaySlipMoveService}) само
 * разрешава кодовете до редове от сметкоплана, пита периода и записва.
 *
 * <p><b>⚠️ ЧЕТЕ НОМЕРА НА ПЕРОТО, А НЕ КОДА ОТ РЕЧНИКА.</b> {@code BgPaySlipService#lineFrom}
 * копира върху перото номера, името, знака и флаговете — НО НЕ и кода, и не и правното основание.
 * Тоест по времето, когато статията се строи, единственият стабилен ключ е {@code bgLineNumber}.
 * Същият прочит прави и {@code BgPaySlipResolver} за чл. 446 ГПК. Написан върху {@code
 * bgPaySlipLineType.getCode()}, моделът щеше да чете ЖИВИЯ речник — и издадена преди месеци статия
 * щеше да смени сметка, когато някой преименува перо.
 *
 * <p>═══ ЦЯЛО ИЛИ НИЩО ═══
 *
 * <p><b>⚠️ ЕДНО НЕРАЗПОЗНАТО ПЕРО ОТМЕНЯ ЦЯЛАТА СТАТИЯ.</b> Пропуснатото перо НЕ разваля баланса —
 * всяко перо внася двойка, тоест остатъкът пак е равен, — и точно затова мълчаливото пропускане е
 * опасно: излиза документ, който изглежда напълно сметнат и е по-малък от истината. Такъв дефект не
 * се вижда при проверка на статията; вижда се чак когато салдото на 4211 не се сведе с ведомостта,
 * месеци по-късно. Затова присъдата е една за целия фиш: или всяко перо има сметка, или статия няма
 * и причината се чете поименно. Същият довод като при празната група 3xx в речника — „празната
 * група, която знае защо е празна, е по-честна от заредената наполовина“.
 *
 * <p>═══ ДВА ПРОХОДА, ЗАЩОТО 630 ЗАВИСИ ОТ СЪСЕДИТЕ СИ ═══
 *
 * <p><b>⚠️ ЕДИНСТВЕНОТО ПЕРО, КОЕТО НЕ СЕ ПРЕВЕЖДА САМО.</b> Всяко друго перо дава двойката си от
 * себе си — номер, знак, флаг, фонд. Перо 630 („предадено в натура“) дава само дебита си (4211);
 * кредитът му е НАСРЕЩНИКЪТ НА НАТУРАТА и зависи от ВИДА на придобивката от клас 2xx, която то
 * приключва: ваучер → 409, стока → 304, придобивка от трето лице → 401. Затова 630 се събира в
 * първия проход и се решава във втория, срещу целия фиш ({@link #settleInKind}). Класификацията по
 * вид живее в {@link BgPayrollInKind} и шест пера от класа са ОБЯВЕНО некласифицирани — те отказват
 * цялата статия, вместо да получат насрещник по догадка.
 *
 * <p>═══ ТРИ КОША, А НЕ ДВА ═══
 *
 * <p><b>⚠️ ОТЛОЖЕНОТО ПЕРО НЕ Е НИТО ДВОЙКА, НИТО ОТКАЗ.</b> Ред с попълнен {@code
 * bgCorrectsPeriod} (перо 620 и всеки друг, който го носи) е нетната делта от вече ЗАТВОРЕН минал
 * период. Той е ПАРИ в текущия плик, но НЕ Е СЧЕТОВОДЕН ФАКТ на текущия месец: разходът и
 * задълженията за онзи месец се осчетоводяват в статията на КОРИГИРАЩИЯ фиш, чийто период е самият
 * коригиран месец. Затова тук той се отделя в трети кош — и {@code BgPaySlipMoveService} проверява,
 * че ретро-статията НАИСТИНА съществува, преди да пропусне реда. Изключен, без тази проверка,
 * корекцията не се осчетоводява НИКЪДЕ.
 */
public final class BgPayrollPostingPlan {

  /** Перо 100 — единственото „основно“ възнаграждение. Останалите 1xx са допълнителни. */
  public static final int LINE_BASIC_WAGE = 100;

  /** Перо 600 — авансът, приключван срещу 4212. */
  public static final int LINE_ADVANCE = 600;

  /** Перо 610 — частично плащане по банков път, приключвано срещу 4212 като аванса. */
  public static final int LINE_PARTIAL_PAYMENT = 610;

  /** Перо 620 — нетната делта от коригиран минал период. Отлага се, не се отказва. */
  public static final int LINE_RETRO_DELTA = 620;

  /** Перо 630 — контрата на предаденото в натура. Насрещната ѝ сметка НЕ е еднозначна. */
  public static final int LINE_IN_KIND = 630;

  /** Перо 700 — данъкът. Сметката се избира по вида осигурен, не по перото. */
  public static final int LINE_TAX = 700;

  /**
   * ВИДЪТ УДРЪЖКА ПО НОМЕР НА ПЕРО — аналитичността на сметка 4214.
   *
   * <p><b>⚠️ ЦЯЛАТА ГРУПА 8xx ВЛИЗА В 4214, НО НЕ В ЕДНО САЛДО.</b> Първият прочит пускаше само
   * четири пера (810, 811, 820, 821), защото името на сметката изброява „запорни, заемни“, и
   * отказваше останалите пет. Счетоводното сверяване даде другото: сметката е ЕДНА за всички
   * удръжки, а разграничението е АНАЛИТИЧНО. Тоест разликата между получателите не изчезва — тя
   * става разрез ВЪТРЕ в 4214 и всеки вид получава свой ред в статията.
   *
   * <p>⚠️ 840 и 850 получиха СВОИ аналитичности, а не най-близката от четирите. Потвърдените
   * наименования са четири (запор, заем, издръжка, глоба) и не покриват членския внос и
   * работническия влог. Слети в чужда аналитичност, двете щяха да развалят точно онова, за което
   * аналитичността съществува: синдикатът и самото лице са РАЗЛИЧНИ получатели от взискателя.
   */
  private static final Map<Integer, String> WITHHOLDING_ANALYTIC = new LinkedHashMap<>();

  static {
    WITHHOLDING_ANALYTIC.put(800, "глоба");
    WITHHOLDING_ANALYTIC.put(810, "заем");
    WITHHOLDING_ANALYTIC.put(811, "заем");
    WITHHOLDING_ANALYTIC.put(820, "запор");
    WITHHOLDING_ANALYTIC.put(821, "запор");
    WITHHOLDING_ANALYTIC.put(830, "издръжка");
    WITHHOLDING_ANALYTIC.put(831, "издръжка");
    WITHHOLDING_ANALYTIC.put(840, "членски внос");
    WITHHOLDING_ANALYTIC.put(850, "работнически влог");
  }

  /**
   * Обезщетенията ПО КОДЕКСА НА ТРУДА — единствените, покрити от 6043 „Разходи за обезщетения по
   * КТ“.
   *
   * <p><b>⚠️ ИЗБРОЕНИ, А НЕ „всичко от 9xx“.</b> Групата не е еднородна: 922, 923, 925, 926 и 927
   * са по Закона за държавния служител, а 924 — по Закона за висшето образование. Името на сметката
   * казва „по КТ“, тоест вкарани там, петте плюс едно щяха да са в сметка, която не ги описва. И за
   * шестте отговорът е ОТКАЗ до бъдещия бюджетен/МСС сметкоплан — те не се гадаят.
   *
   * <p>⚠️ Списъкът е ЗАКОВАН нарочно. Нов ред в каталога на обезщетенията ще се появи като отказ, а
   * не като тихо попадение в 6043 — точно както {@code BgPaySlipLineTypeCatalogueTest} пази
   * съответствието едно към едно между каталога и речника.
   */
  private static final Set<Integer> KT_COMPENSATION_LINES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  900, 901, 902, 903, 904, 905, 906, 907, 908, 910, 911, 912, 913, 914, 915, 916,
                  917, 918, 919, 920, 928)));

  /**
   * Обезщетенията ОТ ЛИЦЕТО към работодателя — вземане, тоест ПРИХОД по 709.
   *
   * <p>⚠️ ДВЕ ПЕРА ПОИМЕННО, а не „всяко 9xx с знак DEBIT“. Днес двете съвпадат — 909 и 921 са
   * единствените DEBIT редове в каталога на обезщетенията, — но правилото, написано върху знака, би
   * посрещнало ново перо с тихо попадение в 709. Написано върху номерата, то го посреща с отказ.
   */
  private static final Set<Integer> EMPLOYEE_OWED_COMPENSATION_LINES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(909, 921)));

  private BgPayrollPostingPlan() {}

  /** Видът удръжка за това перо, или {@code null} за перо извън 8xx. */
  public static String withholdingAnalytic(int lineNumber) {
    return WITHHOLDING_ANALYTIC.get(lineNumber);
  }

  /**
   * Строи плана за този фиш.
   *
   * @param lines перата на фиша — замразените копия, не речникът
   * @param insuredTypeCode видът осигурен, от договора или от регистрацията на СОЛ; решава САМО
   *     сметката на данъка
   */
  public static Plan of(List<BgPaySlipLine> lines, String insuredTypeCode) {
    List<BgPayrollPostingEntry> entries = new ArrayList<>();
    List<String> refusals = new ArrayList<>();
    List<Deferred> deferred = new ArrayList<>();
    // ⚠️ ДВА ПРОХОДА, И ВТОРИЯТ НЕ Е ОПТИМИЗАЦИЯ. Насрещната сметка на перо 630 се решава от ВИДА
    // на придобивката, която то offset-ва — тоест от ДРУГИ пера на същия фиш. Питано в реда, в
    // който перата идват, то би зависело от подредбата им; а подредбата на фиша не е правило.
    List<BgPaySlipLine> naturaLines = new ArrayList<>();
    List<BgPaySlipLine> socialLines = new ArrayList<>();

    for (BgPaySlipLine line : lines == null ? Collections.<BgPaySlipLine>emptyList() : lines) {
      if (line == null) {
        continue;
      }
      // ⚠️ НУЛАТА СЕ ПРОПУСКА ПРЕДИ ДА СЕ ТЪРСИ СМЕТКА, И ТОВА НЕ Е СНИЗХОЖДЕНИЕ. Перо с нула не
      // внася нищо в статията — Axelor дори отказва ред с нулев дебит И нулев кредит. Питано за
      // сметка, то щеше да откаже цял фиш заради перо, което не мести пари: перото 515 (УчПФ за
      // сметка на лицето) съществува в речника със ставка 0,00 точно за да покаже, че лицето не
      // дължи, и се генерира с нула.
      if (line.getBgAmount().signum() == 0) {
        continue;
      }
      // ⚠️ МАРКЕРЪТ СЕ ПИТА ПРЕДИ ВСИЧКО ОСТАНАЛО, включително преди номера на перото. Ред за
      // ЧУЖД период не е счетоводен факт на този месец, каквото и да е перото — точно както
      // BgPaySlipResolver#isOwnPeriod го изключва от агрегатите на Д1/Д6 преди да пита за група.
      if (!BgPaySlipResolver.isOwnPeriod(line)) {
        deferred.add(new Deferred(line));
        continue;
      }
      // ⚠️ ПЕРО 630 НЕ СЕ ПРЕВЕЖДА ТУК, а се събира за втория проход. Дебитът му е ясен (4211),
      // кредитът — не: той е насрещникът на натурата и зависи от вида на 2xx придобивката.
      if (line.getBgLineNumber() == LINE_IN_KIND
          && !BgPaySlipResolver.CREDIT.equals(line.getBgSign())) {
        naturaLines.add(line);
        continue;
      }
      if (BgPaySlipResolver.groupOf(line) == GROUP_SOCIAL_EXPENSES
          && BgPaySlipResolver.CREDIT.equals(line.getBgSign())) {
        socialLines.add(line);
      }
      String refusal = append(entries, line, insuredTypeCode);
      if (refusal != null) {
        refusals.add(refusal);
      }
    }
    refusals.addAll(settleInKind(entries, naturaLines, socialLines));
    return new Plan(entries, refusals, deferred);
  }

  /**
   * ВТОРИЯТ ПРОХОД: перо 630 срещу видовете придобивки на същия фиш.
   *
   * <p><b>⚠️ АЛОКАЦИЯТА Е АРИТМЕТИЧНА, НЕ ЕВРИСТИЧНА.</b> Перото 630 е DERIVED — бележката му го
   * казва дословно: „сумата е стойността на самата придобивка и се сглобява от перата на 2xx“.
   * Тоест нормалният случай е ТОЧНО СЪВПАДЕНИЕ и тогава всяка позиция получава своята сума, без
   * нито едно закръгляне и без пропорции. Останалите два случая са изброени поименно:
   *
   * <ul>
   *   <li>ЕДНА позиция в натура — цялото 630 отива при нея, дори да е частично (частично предаване
   *       в натура е нормално: част ваучер, част пари) — има само един кандидат;
   *   <li>НЯКОЛКО позиции и сума, която НЕ съвпада със сбора им — отказ. Кое от двете е предадено
   *       частично не се знае, а пропорционалното разпределение би било измислено число върху
   *       разчет.
   * </ul>
   *
   * @return поименните откази; празен списък значи, че натурата е приключена
   */
  private static List<String> settleInKind(
      List<BgPayrollPostingEntry> entries,
      List<BgPaySlipLine> naturaLines,
      List<BgPaySlipLine> socialLines) {
    List<String> refusals = new ArrayList<>();
    if (naturaLines.isEmpty()) {
      return refusals;
    }
    if (naturaLines.size() > 1) {
      // ⚠️ Второто 630 не се разпределя „по равно“ — то е признак, че фишът е сглобен на ръка, а
      // разпределянето между две контра-пера е точно догадката, която този клас отказва да прави.
      refusals.add(
          refusal(
              naturaLines.get(1),
              "фишът носи "
                  + naturaLines.size()
                  + " пера 630, а перото е DERIVED и се сглобява ЕДНО от целия клас 2xx. Кое от"
                  + " двете offset-ва коя придобивка не се знае"));
      return refusals;
    }
    BgPaySlipLine natura = naturaLines.get(0);
    BigDecimal amount = natura.getBgAmount().setScale(2, RoundingMode.HALF_UP);
    if (socialLines.isEmpty()) {
      refusals.add(
          refusal(
              natura,
              "перото приключва натура, а на фиша НЯМА нито едно перо от класа 2xx. Насрещната"
                  + " сметка се избира по ВИДА на придобивката (ваучер → 409, стока → 304,"
                  + " придобивка от трето лице → 401), тоест без придобивка няма и насрещник"));
      return refusals;
    }

    // ⚠️ НЕКЛАСИФИЦИРАНОТО СЕ ОТКАЗВА ПРЕДИ ВСЯКО СМЯТАНЕ, и то ПОИМЕННО ЗА ВСЯКО ПЕРО. Достатъчно
    // е ЕДНА придобивка да няма решен вид, за да не се знае целият насрещник — а причината е
    // различна за всяко перо и се чете от него.
    List<BgPaySlipLine> positions = new ArrayList<>();
    boolean unclassified = false;
    for (BgPaySlipLine social : socialLines) {
      BgPayrollInKind.Kind kind = BgPayrollInKind.kindOf(social.getBgLineNumber());
      if (kind == null) {
        unclassified = true;
        refusals.add(
            refusal(
                social,
                "фишът носи перо 630 (предадено в натура), тоест насрещната сметка на придобивката"
                    + " трябва да се избере — а "
                    + BgPayrollInKind.refusalFor(social.getBgLineNumber())
                    + ". ⚠️ НЕ СЕ ГАДАЕ: избран наум, насрещникът праща материален запас там,"
                    + " където стои задължение, или обратно"));
        continue;
      }
      if (kind != BgPayrollInKind.Kind.CASH) {
        positions.add(social);
      }
    }
    if (unclassified) {
      return refusals;
    }
    if (positions.isEmpty()) {
      refusals.add(
          refusal(
              natura,
              "перото приключва натура, а всички придобивки на фиша са ИЗРИЧНО В ПАРИ (перата"
                  + " 220–225 и 271 казват точно това в определенията си — затова 271 съществува"
                  + " срещу 270). Тоест фишът твърди две несъвместими неща за една и същата сума"));
      return refusals;
    }

    BigDecimal declared = BigDecimal.ZERO;
    for (BgPaySlipLine position : positions) {
      declared = declared.add(position.getBgAmount().setScale(2, RoundingMode.HALF_UP));
    }
    if (positions.size() == 1) {
      addInKindEntry(entries, natura, positions.get(0), amount);
      return refusals;
    }
    if (declared.compareTo(amount) != 0) {
      refusals.add(
          refusal(
              natura,
              "фишът носи "
                  + positions.size()
                  + " придобивки в натура за общо "
                  + declared
                  + ", а предаденото е "
                  + amount
                  + ". При няколко вида коя от тях е предадена частично НЕ СЕ ЗНАЕ, а"
                  + " пропорционалното разпределяне би било измислено число върху разчет"));
      return refusals;
    }
    for (BgPaySlipLine position : positions) {
      addInKindEntry(
          entries, natura, position, position.getBgAmount().setScale(2, RoundingMode.HALF_UP));
    }
    return refusals;
  }

  /** Един ред от приключването на натурата: Dr 4211 / Cr насрещника на ВИДА. */
  private static void addInKindEntry(
      List<BgPayrollPostingEntry> entries,
      BgPaySlipLine natura,
      BgPaySlipLine position,
      BigDecimal amount) {
    BgPayrollInKind.Kind kind = BgPayrollInKind.kindOf(position.getBgLineNumber());
    entries.add(
        new BgPayrollPostingEntry(
            natura.getBgLineNumber(),
            natura.getName(),
            BgPayrollAccount.PERSONNEL_NET_WAGE,
            kind.getAccount(),
            amount,
            // ⚠️ Аналитичността е ИМЕТО НА ПРИДОБИВКАТА, не на перото 630: разрезът по 401 служи на
            // счетоводителя, който после сочи КОЙ доставчик стои срещу нея. Виж BgPayrollInKind.
            kind.isAnalytic() ? position.getName() : null));
  }

  /**
   * Превежда едно перо. Връща {@code null} при успех, или причината за отказ.
   *
   * <p>⚠️ Редът на въпросите е от НАЙ-ТЕСНИЯ към най-широкия: номер, после група. Обърнат, групата
   * 1xx щеше да погълне перо 100 и основното възнаграждение щеше да иде в „допълнителни“.
   */
  private static String append(
      List<BgPayrollPostingEntry> entries, BgPaySlipLine line, String insuredTypeCode) {
    int number = line.getBgLineNumber();
    int group = BgPaySlipResolver.groupOf(line);
    boolean credit = BgPaySlipResolver.CREDIT.equals(line.getBgSign());
    BigDecimal amount = line.getBgAmount().setScale(2, RoundingMode.HALF_UP);

    // ── 1xx · заплата, добавки и разходите, които не са възнаграждение ────
    if (group == BgPaySlipResolver.GROUP_SALARY && credit) {
      // ⚠️ ФЛАГЪТ РЕШАВА, НЕ НОМЕРЪТ, и това е единственото място, където моделът НЕ брои номера.
      // „Формира БРЗ по НСОРЗ чл. 16 ал. 2“ е точно въпросът „това работна заплата ли е“, а
      // сметките 6041 и 6042 са двете половини на работната заплата. Двете пера от 1xx с свален
      // флаг — 175 (компенсация по чл. 138а КТ) и 195 (болничен от осигурител по чл. 40 ал. 5
      // КСО) — НЕ са възнаграждение, но СА разход по ЗКПО и отиват в синтетичната 604.
      String expense;
      if (line.getBgFormsGrossWage()) {
        expense =
            number == LINE_BASIC_WAGE
                ? BgPayrollAccount.EXPENSE_BASIC_WAGE
                : BgPayrollAccount.EXPENSE_ADDITIONAL_WAGE;
      } else {
        expense = BgPayrollAccount.EXPENSE_WAGE;
      }
      entries.add(entry(line, expense, BgPayrollAccount.PERSONNEL_NET_WAGE, amount));
      return null;
    }

    // ── 2xx · социални разходи (СБКО) ────────────────────────────────────
    if (group == GROUP_SOCIAL_EXPENSES && credit) {
      // ⚠️ 6094 ЗА ЦЕЛИЯ КЛАС, И ТО ЕДНА СМЕТКА. Придобивката по КТ чл. 294 не е възнаграждение:
      // тя има своя данъчна съдба по ЗКПО чл. 204 т. 2 и своя граница по чл. 210. Слята в 6042
      // „допълнителни и за СБКО“, тя щеше да влезе в числото, по което се четат възнагражденията.
      // ⚠️ РАЗХОДЪТ Е ЕДИН ЗА КЛАСА, НО НАСРЕЩНИКЪТ НА ПРЕДАВАНЕТО — НЕ. Тук се пише само
      // начисляването; с какво е платено казва перо 630 и то има своя проход.
      // ⚠️ Насрещната страна е 4211 И ЗА НАТУРАТА. Придобивката се НАЧИСЛЯВА на лицето и после се
      // приключва — с пари (превод) или с перо 630 (предадено в натура). Двойката 2xx + 630 нето
      // дава Dr 609 / Cr <натура>, а 4211 се занулява.
      entries.add(
          entry(
              line, BgPayrollAccount.EXPENSE_SOCIAL, BgPayrollAccount.PERSONNEL_NET_WAGE, amount));
      return null;
    }

    // ── 5xx · осигуровки ──────────────────────────────────────────────────
    if (group == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
      BgContributionFund fund = line.getBgContributionFund();
      String fundCode = fund == null ? null : fund.getCode();
      String liability = BgPayrollAccount.liabilityForFund(fundCode);
      if (liability == null) {
        return refusal(
            line,
            "осигурителен фонд „"
                + (fundCode == null ? "(липсва)" : fundCode)
                + "“ няма сметка в BG_COA");
      }
      if (credit) {
        // Вноската ЗА СМЕТКА НА РАБОТОДАТЕЛЯ: разход на предприятието срещу задължение към фонда.
        String expense = BgPayrollAccount.employerExpenseForFund(fundCode);
        if (expense == null) {
          return refusal(
              line, "осигурителен фонд „" + fundCode + "“ няма РАЗХОДНА сметка в BG_COA");
        }
        entries.add(entry(line, expense, liability, amount));
        return null;
      }
      // Вноската ЗА СМЕТКА НА ЛИЦЕТО: не е разход, а прехвърляне на вече начисленото задължение от
      // персонала към фонда.
      entries.add(entry(line, BgPayrollAccount.PERSONNEL_NET_WAGE, liability, amount));
      return null;
    }

    // ── 600 и 610 · изплатено ПРЕДИ ведомостта ────────────────────────────
    if ((number == LINE_ADVANCE || number == LINE_PARTIAL_PAYMENT) && !credit) {
      // Двете са една и същата операция: сумата вече е ИЗЛЯЗЛА и стои като вземане в 4212, а
      // заплатата я приключва. Преводът си има своя статия по 503 — вкарано директно там, перото
      // щеше да отчете плащането втори път.
      entries.add(
          entry(
              line,
              BgPayrollAccount.PERSONNEL_NET_WAGE,
              BgPayrollAccount.PERSONNEL_ADVANCE,
              amount));
      return null;
    }

    // ── 700 · данък ───────────────────────────────────────────────────────
    if (number == LINE_TAX && !credit) {
      // ⚠️ ТРИТЕ ВРАТИ НА ДАНЪКА СА ТРИ РАЗЛИЧНИ НОРМИ, А ПЕРОТО Е ЕДНО. Разклонението е същото,
      // което прави BgPaySlipService#generateIncomeTax, и се чете от същия вход — вида осигурен.
      // Питано на перото, то нямаше как да се отговори: 700 носи и трите данъка.
      if (BgSelfInsuredRules.alternativeTaxApplies(insuredTypeCode)) {
        return refusal(
            line,
            "алтернативният данък по ЗДДФЛ чл. 41а не е нито „трудови“ (4541), нито „извънтрудови“"
                + " (4542) правоотношения; 4543 „окончателни данъци“ е близка, но чл. 41а е"
                + " АЛТЕРНАТИВЕН, не окончателен по чл. 37–38. Сметка за него ще дойде с"
                + " бюджетния/МСС сметкоплан — дотогава се ОТКАЗВА, не се гади");
      }
      String taxAccount =
          BgCivilContractResolver.isCivilContract(insuredTypeCode)
              ? BgPayrollAccount.TAX_NON_EMPLOYMENT
              : BgPayrollAccount.TAX_EMPLOYMENT;
      entries.add(entry(line, BgPayrollAccount.PERSONNEL_NET_WAGE, taxAccount, amount));
      return null;
    }

    // ── 8xx · удръжки, с аналитичност по вид ──────────────────────────────
    String analytic = WITHHOLDING_ANALYTIC.get(number);
    if (analytic != null && !credit) {
      entries.add(
          entry(
              line,
              BgPayrollAccount.PERSONNEL_NET_WAGE,
              BgPayrollAccount.PERSONNEL_WITHHOLDING,
              amount,
              analytic));
      return null;
    }

    // ── 9xx · обезщетения ─────────────────────────────────────────────────
    if (KT_COMPENSATION_LINES.contains(number) && credit) {
      entries.add(
          entry(
              line,
              BgPayrollAccount.EXPENSE_KT_COMPENSATION,
              BgPayrollAccount.PERSONNEL_COMPENSATION,
              amount));
      return null;
    }
    if (EMPLOYEE_OWED_COMPENSATION_LINES.contains(number) && !credit) {
      // Обезщетение ОТ лицето (КТ чл. 221 ал. 2 и чл. 232 ал. 3): вземане на работодателя, тоест
      // ПРИХОД. Кредит на 6041 щеше да намали отчетения разход за заплати вместо да отчете приход.
      entries.add(
          entry(
              line,
              BgPayrollAccount.PERSONNEL_NET_WAGE,
              BgPayrollAccount.REVENUE_FROM_EMPLOYEE,
              amount));
      return null;
    }

    return refusal(line, reasonFor(number, group, credit));
  }

  /** Групата 2xx — {@code BgPaySlipResolver} няма име за нея, защото не я сумира никъде. */
  private static final int GROUP_SOCIAL_EXPENSES = 2;

  /** Групата 3xx — декларирана и празна, виж речника. */
  private static final int GROUP_SICK_AND_LEAVE = 3;

  /** Причината за отказ — казва КАКВО липсва, а не „непознато перо“. */
  private static String reasonFor(int number, int group, boolean credit) {
    if (number == LINE_IN_KIND) {
      // ⚠️ ДОТУК СТИГА САМО 630 С ГРЕШЕН ЗНАК. Перото е контра на натурата, тоест DEBIT — с
      // CREDIT то би НАЧИСЛЯВАЛО придобивката втори път вместо да я приключва, и нетото щеше да
      // порасне точно с нея. Верният 630 се обработва във втория проход (settleInKind).
      return "перото е контра на натурата и посоката му е DEBIT — то ПРИКЛЮЧВА начисленото по 2xx."
          + " С CREDIT то начислява същата придобивка втори път и нетото расте с нея";
    }
    if (number == LINE_RETRO_DELTA) {
      return "перо 620 БЕЗ попълнен bgCorrectsPeriod. Маркерът е самото определение на перото —"
          + " той го изключва и от Д1/Д6 на текущия месец, и от текущата статия, и сочи периода,"
          + " чиято ретро-статия носи разхода. Без него редът твърди, че е корекция за минал"
          + " период, и не казва за КОЙ";
    }
    switch (group) {
      case GROUP_SICK_AND_LEAVE:
        return "болничните и отпуските (3xx) нямат нито един ред в речника и съответно нито едно"
            + " присвояване";
      case BgPaySlipResolver.GROUP_PAYMENTS:
        return "плащане от група 6xx извън 600/610/620/630 — няма присвояване";
      case BgPaySlipResolver.GROUP_DEDUCTIONS:
        return "удръжка извън деветте пера с аналитичност в 4214 (800, 810, 811, 820, 821, 830,"
            + " 831, 840, 850) — получателят е неизвестен, тоест и аналитичността";
      case BgPaySlipResolver.GROUP_COMPENSATIONS:
        return credit
            ? "обезщетение, което НЕ е по Кодекса на труда (ЗДСл чл. 106 или ЗВО чл. 58), а 6043 се"
                + " казва „по КТ“. Тези шест пера (922–927) чакат бюджетния/МСС сметкоплан и се"
                + " ОТКАЗВАТ дотогава — не се сливат с обезщетенията по КТ"
            : "обезщетение ОТ лицето КЪМ работодателя извън 909 и 921 — приходната сметка 709 е"
                + " окабелена само за тези две пера";
      default:
        return "перо извън описаните групи";
    }
  }

  private static BgPayrollPostingEntry entry(
      BgPaySlipLine line, String debitAccount, String creditAccount, BigDecimal amount) {
    return entry(line, debitAccount, creditAccount, amount, null);
  }

  private static BgPayrollPostingEntry entry(
      BgPaySlipLine line,
      String debitAccount,
      String creditAccount,
      BigDecimal amount,
      String analytic) {
    return new BgPayrollPostingEntry(
        line.getBgLineNumber(), line.getName(), debitAccount, creditAccount, amount, analytic);
  }

  private static String refusal(BgPaySlipLine line, String reason) {
    return "перо "
        + line.getBgLineNumber()
        + " „"
        + line.getName()
        + "“ ("
        + line.getBgAmount().setScale(2, RoundingMode.HALF_UP)
        + ") — "
        + reason;
  }

  /**
   * ОТЛОЖЕНО ПЕРО — ред за чужд, вече затворен период.
   *
   * <p>⚠️ Носи самото перо, а не копие на числата: услугата отгоре има нужда и от периода ({@code
   * bgCorrectsPeriod}), за да намери ретро-статията, и от знака, за да свери моста през 4211.
   */
  public static final class Deferred {

    private final BgPaySlipLine line;

    private Deferred(BgPaySlipLine line) {
      this.line = line;
    }

    public BgPaySlipLine getLine() {
      return line;
    }

    public int getLineNumber() {
      return line.getBgLineNumber();
    }

    public Period getPeriod() {
      return line.getBgCorrectsPeriod();
    }

    /** Сумата СЪС ЗНАК: доплащането е плюс, удръжката — минус. */
    public BigDecimal getSignedAmount() {
      BigDecimal amount = line.getBgAmount().setScale(2, RoundingMode.HALF_UP);
      return BgPaySlipResolver.CREDIT.equals(line.getBgSign()) ? amount : amount.negate();
    }
  }

  /** Планът: двойките, сборовете по сметка, отказите и отложените пера. */
  public static final class Plan {

    private final List<BgPayrollPostingEntry> entries;
    private final List<String> refusals;
    private final List<Deferred> deferred;

    private Plan(
        List<BgPayrollPostingEntry> entries, List<String> refusals, List<Deferred> deferred) {
      this.entries = Collections.unmodifiableList(entries);
      this.refusals = Collections.unmodifiableList(refusals);
      this.deferred = Collections.unmodifiableList(deferred);
    }

    /** Двойките, по едно на перо — свидетелят „кое перо в коя сметка“. */
    public List<BgPayrollPostingEntry> getEntries() {
      return entries;
    }

    /** Поименните откази. Празен списък значи, че всяко перо има сметка. */
    public List<String> getRefusals() {
      return refusals;
    }

    /** Перата за ЧУЖД период — вън от тази статия, но не забравени. */
    public List<Deferred> getDeferred() {
      return deferred;
    }

    /** Сборът на отложените СЪС ЗНАК — точно колкото 4211 на тази статия НЕ носи. */
    public BigDecimal deferredNet() {
      BigDecimal sum = BigDecimal.ZERO;
      for (Deferred d : deferred) {
        sum = sum.add(d.getSignedAmount());
      }
      return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Може ли да се строи статия: всяко перо е разпознато И има какво да се запише. */
    public boolean isPostable() {
      return refusals.isEmpty() && !entries.isEmpty();
    }

    /**
     * Редовете на статията: сборовете по сметка, посока и аналитичност, подредени по код.
     *
     * <p>⚠️ ПОДРЕДБАТА Е ЗАКОВАНА (по код, дебитът преди кредита при равен код, после по
     * аналитичност), защото статията се сравнява в тест число по число. Оставена на реда на перата,
     * тя щеше да се мени при всяка промяна в генераторите и тестът щеше да пази реда на
     * генерирането вместо сметките.
     */
    public List<BgPayrollPosting> getPostings() {
      Map<String, BigDecimal> sums = new LinkedHashMap<>();
      Map<String, BgPayrollPosting> shapes = new LinkedHashMap<>();
      for (BgPayrollPostingEntry e : entries) {
        merge(sums, shapes, e.getDebitAccount(), true, e.getAnalytic(), e.getAmount());
        merge(sums, shapes, e.getCreditAccount(), false, e.getAnalytic(), e.getAmount());
      }
      List<String> keys = new ArrayList<>(shapes.keySet());
      // ⚠️ Подредбата НЕ Е азбучна по ключа: ключът носи посоката като „|C|“/„|D|“ и азбучно
      // кредитът щеше да излиза пръв. Сортира се по (код, дебит преди кредит, аналитичност) —
      // редът, с който статия се пише на ръка и с който се четат редовете в документа.
      Collections.sort(keys, (a, b) -> sortKey(shapes.get(a)).compareTo(sortKey(shapes.get(b))));
      List<BgPayrollPosting> out = new ArrayList<>();
      for (String key : keys) {
        BgPayrollPosting shape = shapes.get(key);
        out.add(
            new BgPayrollPosting(
                shape.getAccountCode(), shape.isDebit(), sums.get(key), shape.getAnalytic()));
      }
      return out;
    }

    private static String sortKey(BgPayrollPosting posting) {
      return posting.getAccountCode()
          + (posting.isDebit() ? "|0|" : "|1|")
          + (posting.getAnalytic() == null ? "" : posting.getAnalytic());
    }

    private static void merge(
        Map<String, BigDecimal> sums,
        Map<String, BgPayrollPosting> shapes,
        String code,
        boolean debit,
        String analytic,
        BigDecimal amount) {
      // ⚠️ Аналитичността влиза в ключа САМО за сметките, които я носят (4214 по вид удръжка, 401
      // по придобивка). Дебитът на 4211 срещу запор и срещу заем е един и същ ред — от страната на
      // персонала няма разрез по получател.
      String effective = BgPayrollAccount.carriesAnalytic(code) ? analytic : null;
      BgPayrollPosting shape = new BgPayrollPosting(code, debit, amount, effective);
      sums.merge(shape.key(), amount, BigDecimal::add);
      shapes.putIfAbsent(shape.key(), shape);
    }

    public BigDecimal totalDebit() {
      return total(true);
    }

    public BigDecimal totalCredit() {
      return total(false);
    }

    private BigDecimal total(boolean debit) {
      BigDecimal sum = BigDecimal.ZERO;
      for (BgPayrollPosting p : getPostings()) {
        if (p.isDebit() == debit) {
          sum = sum.add(p.getAmount());
        }
      }
      return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Балансът е ПО УСТРОЙСТВО — проверката тук е свидетел, не изчисление. */
    public boolean isBalanced() {
      return totalDebit().compareTo(totalCredit()) == 0;
    }
  }
}
