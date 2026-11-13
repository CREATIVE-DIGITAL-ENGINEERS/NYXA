package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.Model;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Правилата на обезщетението-документ, БЕЗ база и без Guice.
 *
 * <p>Отделен от {@code BgCompensationService} по същата причина, по която {@code
 * BgContributionResolver} е отделен от изчислителната услуга: правилата тук са прочит на закона и
 * трябва да се четат и проверяват без Hibernate, транзакция и екран. Услугата отгоре чете каталога,
 * пише по документа и вика тези методи.
 *
 * <p><b>Какво НЯМА тук.</b> Няма лихва за забава — чл. 228 ал. 3 я дължи, но база и правило за
 * закръгляне не са установени, а сметната наслуки лихва е по-лоша от липсваща. Няма и проверка за
 * стажа при същия работодател: таблица с история по работодатели не съществува и не се измисля тук.
 */
public final class BgCompensationResolver {

  private BgCompensationResolver() {}

  public static final String DRAFT = "DRAFT";
  public static final String CONFIRMED = "CONFIRMED";
  public static final String PAID = "PAID";
  public static final String CANCELLED = "CANCELLED";

  public static final String BTV_MONTHS = "BTV_MONTHS";
  public static final String UNUSED_LEAVE_DAYS = "UNUSED_LEAVE_DAYS";
  public static final String NOTICE_DAYS = "NOTICE_DAYS";

  /**
   * Видовете, при които законовият размер е МИНИМУМ, тоест корекция НАДОЛУ е недопустима.
   *
   * <p>Чл. 228 ал. 2 КТ: размерите на обезщетенията по чл. 215, 218, 222 и 225 се прилагат,
   * доколкото в акт на Министерския съвет, в колективен трудов договор или в трудовия договор не са
   * предвидени по-големи размери. Чл. 331 ал. 2 казва същото със свои думи — „не по-малко от“
   * четирикратния размер.
   *
   * <p>⚠️ СЪЗНАТЕЛЕН ДЪЛГ: това принадлежи на {@code BgCompensationType} като булево поле, а не на
   * списък в кода — при нов вид обезщетение никой няма да се сети да мине оттук. Не се добавя днес,
   * защото каталогът е мърджнат и полето иска миграция и повторно засяване. Списъкът е малък и
   * нарочно поименен, за да личи, че е временен.
   *
   * <p>Чл. 215 и чл. 218 нямат редове в каталога към днес — затова ги няма и тук.
   */
  private static final Set<String> STATUTORY_MINIMUM =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "comp_kt_222_1", // чл. 222 ал. 1
                  "comp_kt_222_2", // чл. 222 ал. 2
                  "comp_kt_222_3", // чл. 222 ал. 3
                  "comp_kt_225_1", // чл. 225 ал. 1
                  "comp_kt_331"))); // чл. 331 ал. 2 — „не по-малко от“

  /**
   * ВИДОВЕТЕ, ПРИ КОИТО {@code baseMonths} НА КАТАЛОГА Е ТАВАН, А НЕ РАЗМЕР.
   *
   * <p>ЦИТАТ, КТ чл. 225 ал. 1: „При незаконно уволнение работникът или служителят има право на
   * обезщетение от работодателя в размер на брутното му трудово възнаграждение за времето, през
   * което е останал без работа поради това уволнение, <b>но за не повече от 6 месеца</b>.“ Тоест
   * 6.00 е ГОРНА ГРАНИЦА върху ДЕЙСТВИТЕЛНИЯ период, а не брой вноски. Три месеца без работа дават
   * 3 БТВ; осем месеца дават 6.
   *
   * <p><b>⚠️ ЧИСЛОТО НЕ СЕ ПИШЕ ТУК — само знанието, че то е таван.</b> Стойността се чете от
   * {@code BgCompensationType.baseMonths}, тоест остава в данните и се мени с тях. В кода стои
   * единствено класификацията, защото самият модел я няма: {@code baseKind = BTV_MONTHS} значи
   * „baseMonths е РАЗМЕРЪТ“ (виж домейна на {@code BgCompensationType}), а бележката на реда CT-10
   * в {@code bg_compensation_type.csv} казва точно обратното — „6.00 е ТАВАН, не размер —
   * действителният период се въвежда“. Двете твърдения се разминават от деня, в който редът е
   * засят, и до днес НИЩО не ги сверяваше: количеството просто се предлагаше 6.00 и никой не го
   * ограничаваше.
   *
   * <p><b>⚠️ РАЗЛИКАТА „ТАВАН“ / „ФИКСИРАН РАЗМЕР“ Е В САМИЯ ТЕКСТ НА НОРМАТА и се чете
   * буквално:</b>
   *
   * <ul>
   *   <li>„за времето, през което е останал без работа, <b>но за не повече от</b> N месеца“ →
   *       ТАВАН. Така е при чл. 225 ал. 1 (6 месеца) и при чл. 222 ал. 1 (1 месец).
   *   <li>„в размер на брутното му трудово възнаграждение <b>за срок от</b> N месеца“ → ФИКСИРАН
   *       размер. Така е при чл. 222 ал. 2 и ал. 3, тоест техните 2.00 и 6.00 са верни както са.
   * </ul>
   *
   * <p><b>⚠️ ЧЛ. 222 АЛ. 1 НЕ Е В СПИСЪКА, макар текстът му да е от същия вид.</b> Това е ОБХВАТ,
   * не прочит: правилото покрива чл. 213, чл. 214 и чл. 225, а таванът на чл. 222 ал. 1 иска свое
   * измерване — при него законът ИЗРИЧНО допуска по-дълъг срок с КТД („С акт на Министерския съвет,
   * с колективен трудов договор или с трудовия договор може да се предвижда обезщетение за по-дълъг
   * срок“), тоест отказ над 1.00 би бил ГРЕШЕН без прочит на КТД. При чл. 225 ал. 1 такава клауза
   * НЯМА — чл. 228 ал. 2 позволява по-голям РАЗМЕР, но не по-дълъг срок.
   *
   * <p>Огледалният близнак на {@link #STATUTORY_MINIMUM} и се чете заедно с него: там законовият
   * размер е ДОЛНА граница за договаряне, тук броят месеци е ГОРНА граница за факта.
   */
  private static final Set<String> BASE_MONTHS_IS_A_CAP =
      Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("comp_kt_225_1")));

  /**
   * Видовете, при които законът поставя условие за СТАЖ, което системата не може да провери.
   *
   * <p>Няма таблица с историята на стажа по работодатели — известен пропуск, документиран отделно.
   * Оттам следва предупреждение, а НЕ блокировка: числото се въвежда от ТРЗ-специалиста.
   */
  private static final Set<String> SENIORITY_UNCHECKABLE =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("comp_kt_222_2", "comp_kt_222_3")));

  /**
   * Чл. 228 ал. 1 КТ: базата е БТВ за МЕСЕЦА, ПРЕДХОЖДАЩ месеца, в който е възникнало основанието.
   *
   * <p>Връща първото число на този месец — самата стойност е месец, не ден, а първото число е
   * единственият му еднозначен запис в поле от тип дата.
   *
   * <p>⚠️ Втората хипотеза на ал. 1 — „или последното получено месечно брутно трудово
   * възнаграждение“ — НЕ се решава тук: тя иска да се знае за кои месеци изобщо има начислено, а
   * това е заявка към ведомостта. Услугата отгоре я прилага и записва РЕЗУЛТАТА в bgBaseMonth,
   * тоест на документа винаги се вижда кой месец е ползван.
   */
  public static LocalDate baseMonthFor(LocalDate eventDate) {
    return eventDate == null ? null : eventDate.withDayOfMonth(1).minusMonths(1);
  }

  /**
   * Чл. 228 ал. 3 КТ: не по-късно от последния ден на месеца, СЛЕДВАЩ месеца на прекратяването.
   *
   * <p>15.03.2026 → 30.04.2026. Стойността е подразбиране, не таван: КТД може да е договорил друг
   * срок, затова полето на документа остава редактируемо.
   */
  public static LocalDate dueDateFor(LocalDate eventDate) {
    if (eventDate == null) {
      return null;
    }
    LocalDate next = eventDate.withDayOfMonth(1).plusMonths(1);
    return next.withDayOfMonth(next.lengthOfMonth());
  }

  /**
   * Просрочен ли е документът: срокът е минал, а плащане няма.
   *
   * <p>Само маркиране. Лихвата по чл. 228 ал. 3 НЕ се смята — виж коментара на класа.
   */
  public static boolean isOverdue(LocalDate dueDate, LocalDate paidDate, LocalDate today) {
    return dueDate != null && paidDate == null && today != null && today.isAfter(dueDate);
  }

  /**
   * Сумата по вида база. {@code null} значи „не може да се изведе от подадените данни“ — НЕ нула.
   *
   * <p>При базите в дни сумата е среднодневно БТВ × дни, а среднодневното иска делител: броя
   * работни дни в месеца на базата. Без него резултатът е налучкване, затова се връща null, а
   * услугата отказва потвърждаване с ясно съобщение.
   *
   * <p>Останалите видове (действителни вреди, разноски, остатък от срок) НЕ се смятат от системата
   * и също връщат null — сумата им се въвежда. Това е решение на клиента, записано в
   * BgCompensationType.
   */
  public static BigDecimal compute(
      String baseKind, BigDecimal quantity, BigDecimal monthlyGross, Integer baseWorkingDays) {

    if (baseKind == null || quantity == null || monthlyGross == null) {
      return null;
    }
    if (BTV_MONTHS.equals(baseKind)) {
      return monthlyGross.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }
    if (UNUSED_LEAVE_DAYS.equals(baseKind) || NOTICE_DAYS.equals(baseKind)) {
      if (baseWorkingDays == null || baseWorkingDays <= 0) {
        return null;
      }
      BigDecimal daily =
          monthlyGross.divide(BigDecimal.valueOf(baseWorkingDays), 10, RoundingMode.HALF_UP);
      return daily.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }
    return null;
  }

  /**
   * ЗАКОНОВИЯТ ТАВАН В МЕСЕЦИ за този вид, или {@code null}, когато {@code baseMonths} е размер.
   *
   * <p>⚠️ Непопълненият {@code baseMonths} също дава {@code null}: генерираният getter за decimal
   * връща ZERO вместо null, тоест „таван няма обявен“ и „таванът е нула месеца“ се пишат с едно и
   * също число. Нула месеца не е таван, а несмислен ред — виж axelor-decimal-getter-zero.
   */
  public static BigDecimal statutoryMaximumMonths(BgCompensationType type) {
    if (type == null || type.getCode() == null || !BASE_MONTHS_IS_A_CAP.contains(type.getCode())) {
      return null;
    }
    BigDecimal months = type.getBaseMonths();
    return months.signum() > 0 ? months : null;
  }

  /**
   * КОЛИЧЕСТВОТО, КОЕТО КАТАЛОГЪТ ПРЕДЛАГА при създаване на документа, или {@code null} — „не
   * предлагай нищо“.
   *
   * <p><b>⚠️ ТАВАН НЕ СЕ ПРЕДЛАГА КАТО СТОЙНОСТ, И ТОВА Е НАРОЧНО.</b> {@code prepare()} НЕ копира
   * {@code baseMonths} за ВСЕКИ вид с база в месеци. За чл. 222 ал. 2 и ал. 3 това е вярно — там
   * законът казва „за срок от“ 2 месеца и числото Е размерът. За чл. 225 ал. 1 е невярно: 6.00 е
   * таванът, а действителният период е ФАКТ, който системата не знае. Предложен като стойност,
   * таванът е НАЙ-ГОЛЯМОТО възможно число и тръгва към плащане, ако никой не го пипне — тоест
   * подразбирането по устройство надплаща всеки, който е останал без работа за по-малко от 6
   * месеца.
   *
   * <p>⚠️ И такова надплащане не би било видимо: с празно количество сумата е нула и {@link
   * #refuseConfirm} я спира със „сумата за изплащане е празна“, докато с предложените 6.00 излиза
   * правдоподобно число, което нито един пазач не оспорва. Оттам и защо празното е по-безопасно от
   * пълното — то СПИРА, вместо да плати.
   *
   * <p>⚠️ Обратната посока също е затворена: {@link #refuseAboveStatutoryMaximum} не позволява
   * въведеното количество да надхвърли тавана.
   */
  public static BigDecimal prefillQuantity(BgCompensationType type) {
    if (type == null) {
      return null;
    }
    return statutoryMaximumMonths(type) == null ? type.getBaseMonths() : null;
  }

  /**
   * КОЛИЧЕСТВОТО НАД ЗАКОНОВИЯ ТАВАН. Връща причината за отказ, или {@code null}.
   *
   * <p>⚠️ Пита се САМО при база в МЕСЕЦИ. Единиците на {@code bgQuantity} се менят с вида база —
   * работни дни при чл. 220 и чл. 224 — а „20 работни дни > 6 месеца“ би било сравнение на две
   * различни неща, при това с вид на правило.
   */
  public static String refuseAboveStatutoryMaximum(
      BgCompensationType type, String baseKind, BigDecimal quantity) {

    BigDecimal cap = statutoryMaximumMonths(type);
    if (cap == null || !BTV_MONTHS.equals(baseKind) || quantity == null) {
      return null;
    }
    if (quantity.compareTo(cap) <= 0) {
      return null;
    }
    return "Количеството ("
        + quantity.stripTrailingZeros().toPlainString()
        + " месеца) надхвърля законовия таван по "
        + type.getLegalBasis()
        + " — обезщетението е брутното трудово възнаграждение за времето БЕЗ РАБОТА, но за не повече"
        + " от "
        + cap.stripTrailingZeros().toPlainString()
        + " месеца. Въведете действителния период; чл. 228 ал. 2 КТ допуска по-ГОЛЯМ размер по КТД,"
        + " но не и по-ДЪЛЪГ срок.";
  }

  /**
   * ПОТВЪРЖДАВАНЕ БЕЗ ВЪВЕДЕН ДЕЙСТВИТЕЛЕН ПЕРИОД. Връща причината за отказ, или {@code null}.
   *
   * <p>Важи само за видовете с ТАВАН: при тях количеството е ФАКТ („колко месеца е останал без
   * работа“), който никой източник в модула не носи, и затова {@link #prefillQuantity} нарочно не
   * го подразбира. Отказът назовава какво се иска — иначе операторът получава само „сумата е
   * празна“ и най-естественото, което ще направи, е да върне тавана обратно в полето.
   *
   * <p>⚠️ signum() &lt;= 0, не {@code == null}: генерираният getter за decimal връща ZERO.
   */
  public static String refuseMissingActualPeriod(BgCompensationType type, BigDecimal quantity) {
    BigDecimal cap = statutoryMaximumMonths(type);
    if (cap == null || (quantity != null && quantity.signum() > 0)) {
      return null;
    }
    return "Количеството е празно. По "
        + type.getLegalBasis()
        + " обезщетението се дължи за ДЕЙСТВИТЕЛНОТО време без работа, а "
        + cap.stripTrailingZeros().toPlainString()
        + " месеца е само таванът — затова каталогът НЕ го предлага като стойност. Въведете колко"
        + " месеца лицето е останало без работа (най-много "
        + cap.stripTrailingZeros().toPlainString()
        + ").";
  }

  /**
   * Работните дни, попадащи в календарен срок — вторият етап на чл. 220.
   *
   * <p>ЦИТАТ, КТ чл. 326 ал. 2: срокът на предизвестието при безсрочен трудов договор е 30 дни,
   * доколкото страните не са уговорили по-дълъг, но не повече от 3 месеца. Ал. 4: срокът тече от
   * следващия ден на получаването. Тоест срокът е КАЛЕНДАРЕН.
   *
   * <p>ЦИТАТ, НСОРЗ чл. 19 ал. 1: обезщетението се получава, като среднодневното брутно трудово
   * възнаграждение се умножи по броя на РАБОТНИТЕ дни за периода. Заедно с чл. 18 ал. 1 (делене на
   * ОТРАБОТЕНИТЕ дни) това прави правилото двустъпково: календарен срок → работни дни в него.
   *
   * <p>Оттам следва, че един и същ срок струва различно според това кога тече: 30 календарни дни от
   * 1 април 2026 г. съдържат 20 работни дни, същите 30 дни от 1 декември — 19.
   *
   * <p>⚠️ ОФИЦИАЛНИТЕ ПРАЗНИЦИ СЕ ПРИСПАДАТ САМО ДОКОЛКОТО СА ПОДАДЕНИ. Методът не знае календар —
   * получава множеството дати отвън. Ако за дадена година в календара няма редове, резултатът е
   * само понеделник–петък и е ЗАВИШЕН. Празна година не се различава от година без празници, и това
   * не може да се поправи тук: услугата отгоре трябва да каже, че за периода няма календар.
   *
   * @param holidays официалните празници, които попадат в срока; {@code null} значи „никакви“
   */
  public static int workingDaysIn(LocalDate start, Integer calendarDays, Set<LocalDate> holidays) {
    if (start == null || calendarDays == null || calendarDays <= 0) {
      return 0;
    }
    int days = 0;
    for (int i = 0; i < calendarDays; i++) {
      LocalDate day = start.plusDays(i);
      if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
        continue;
      }
      if (holidays != null && holidays.contains(day)) {
        continue;
      }
      days++;
    }
    return days;
  }

  /**
   * Предупреждение, когато въведеното количество се различава от изведеното от срока.
   *
   * <p>⚠️ Предупреждение, НЕ блокировка: ТРЗ вижда случаи, които календарът не знае — например че
   * лицето е било в неплатен отпуск част от срока. Мълчаливото приемане обаче е по-лошо от двете:
   * тогава числото няма произход.
   */
  public static String noticeQuantityWarning(BigDecimal entered, int derived) {
    if (entered == null || entered.compareTo(BigDecimal.valueOf(derived)) == 0) {
      return null;
    }
    return "Въведени са "
        + entered.stripTrailingZeros().toPlainString()
        + " работни дни, а от срока на предизвестието излизат "
        + derived
        + ". Числото остава вашето — но ако е вярно, опишете защо в забележката.";
  }

  /**
   * Проверката на корекцията. Връща причината за отказ, или {@code null}, когато всичко е наред.
   *
   * <p>Три правила, всяко от различен източник: каталогът казва дали сумата изобщо се коригира, чл.
   * 228 ал. 2 КТ забранява корекция НАДОЛУ при част от видовете, а проследимостта иска причина за
   * всяко разминаване с изчисленото.
   */
  public static String refuseAdjustment(
      BgCompensationType type, BigDecimal computed, BigDecimal finalAmount, String reason) {

    if (finalAmount == null || computed == null || finalAmount.compareTo(computed) == 0) {
      return null;
    }
    if (type != null && Boolean.FALSE.equals(type.getIsAmountAdjustable())) {
      return "Сумата по „"
          + type.getName()
          + "“ не подлежи на ръчна корекция — така е отбелязано в каталога на обезщетенията.";
    }
    if (finalAmount.compareTo(computed) < 0 && isStatutoryMinimum(type)) {
      return "Корекция НАДОЛУ не се допуска за "
          + (type == null ? "този вид" : type.getLegalBasis())
          + ": по чл. 228 ал. 2 КТ законовият размер е МИНИМУМ и може да се увеличава, но не и да се"
          + " намалява. Изчислено: "
          + computed
          + ", въведено: "
          + finalAmount
          + ".";
    }
    if (reason == null || reason.trim().isEmpty()) {
      return "Сумата за изплащане се различава от изчислената ("
          + computed
          + " → "
          + finalAmount
          + "), затова причината за корекцията е задължителна.";
    }
    return null;
  }

  /** Законовият размер минимум ли е за този вид — виж {@link #STATUTORY_MINIMUM}. */
  public static boolean isStatutoryMinimum(BgCompensationType type) {
    return type != null && type.getCode() != null && STATUTORY_MINIMUM.contains(type.getCode());
  }

  /**
   * Условие за стаж, което системата не може да провери — предупреждение за формата.
   *
   * <p>Връща текста, или {@code null}, когато няма такова условие. ⚠️ Предупреждение, НЕ
   * блокировка: числото се въвежда от човек, който има достъп до трудовата книжка.
   */
  public static String seniorityWarning(BgCompensationType type) {
    if (type == null || type.getCode() == null || !SENIORITY_UNCHECKABLE.contains(type.getCode())) {
      return null;
    }
    if ("comp_kt_222_3".equals(type.getCode())) {
      return "Чл. 222 ал. 3 КТ: обезщетението е 2 брутни заплати, но става 6, ако лицето е работило"
          + " при СЪЩИЯ работодател през последните 10 години от трудовия си стаж. Системата НЕ може"
          + " да провери условието — няма история на стажа по работодатели. Проверете и въведете"
          + " количеството ръчно.";
    }
    return "Чл. 222 ал. 2 КТ: обезщетението се дължи при поне 5 години трудов стаж И само ако през"
        + " последните 5 години лицето не е получавало обезщетение на същото основание. Второто"
        + " условие иска история и при ДРУГИ работодатели, която системата няма. Проверете и"
        + " въведете количеството ръчно.";
  }

  /**
   * Позволен ли е преходът. Връща причината за отказ, или {@code null}.
   *
   * <p>⚠️ ОТ CONFIRMED НЕ СЕ ВРЪЩА В DRAFT. Потвърждаването замразява сумата и режима; връщане
   * назад значи подписан документ да се препише. Грешният документ се ОТКАЗВА и се прави нов.
   */
  public static String refuseTransition(String from, String to) {
    // ⚠️ ВТОРИ ОТКАЗ НА ОТКАЗАН ДОКУМЕНТ — ОТКАЗ: щеше да подмени причината на първия. Същото
    // правило като при фиша (BgPaySlipResolver#refuseTransition).
    if (CANCELLED.equals(from) && CANCELLED.equals(to)) {
      return "Документът вече е отказан — втори отказ би подменил причината на първия.";
    }
    if (from == null || to == null || from.equals(to)) {
      return null;
    }
    if (CANCELLED.equals(to)) {
      return null; // отказ е възможен от всяко състояние — виж refuseCancel за условието
    }
    if (CANCELLED.equals(from)) {
      return "Отказан документ не се връща в работа. Направете нов.";
    }
    if (DRAFT.equals(to)) {
      return "Връщане в чернова не се допуска: потвърждаването замразява сумата, базата и режима."
          + " Ако документът е грешен, откажете го и направете нов.";
    }
    if (CONFIRMED.equals(to) && !DRAFT.equals(from)) {
      return "Потвърждава се само чернова.";
    }
    if (PAID.equals(to) && !CONFIRMED.equals(from)) {
      return "Изплаща се само потвърден документ.";
    }
    return null;
  }

  /**
   * Условията за потвърждаване. Връща причината за отказ, или {@code null}.
   *
   * <p>{@code requiresProof} на каталога значи, че правото не е знайно към датата на прекратяване и
   * се доказва след това — чл. 222 ал. 1 се доказва с документ за оставане без работа. Доказването
   * е или прикачен файл, или изрична бележка: и двете оставят следа, а празният документ не.
   */
  public static String refuseConfirm(
      BgCompensationType type, BigDecimal finalAmount, boolean hasAttachment, String note) {

    if (finalAmount == null || finalAmount.signum() == 0) {
      return "Сумата за изплащане е празна — няма какво да се потвърди.";
    }
    if (type != null
        && Boolean.TRUE.equals(type.getRequiresProof())
        && !hasAttachment
        && (note == null || note.trim().isEmpty())) {
      return "Обезщетението по „"
          + type.getLegalBasis()
          + "“ иска доказване след прекратяването. Прикачете документа или опишете доказването в"
          + " забележката.";
    }
    return null;
  }

  /**
   * Сумата на акта срещу сбора на плащанията по правото. Връща причината за отказ, или {@code
   * null}.
   *
   * <p>Проверява се САМО когато документът сочи към право И то вече носи плащания. Право без
   * плащания не казва нищо за размера — то е „полага се“, а не „платено е“.
   *
   * <p>⚠️ Това е ЕДИНСТВЕНАТА връзка между двата записа днес. Пълната реконсилиация — кой ги пълни,
   * какво става при частично плащане и при надплащане — е дълг за стъпка 4 и нарочно не се прави
   * тук. Дотогава разминаването СПИРА потвърждаването, вместо да се изглажда мълчаливо: два записа
   * за едни и същи пари, които твърдят различни числа, са по-лоши от блокиран бутон.
   *
   * @param paid сборът на плащанията по правото, или {@code null}, когато право/плащания няма
   */
  public static String refuseAgainstEntitlement(BigDecimal finalAmount, BigDecimal paid) {
    if (paid == null || finalAmount == null || finalAmount.compareTo(paid) == 0) {
      return null;
    }
    return "Сумата на документа ("
        + finalAmount
        + ") не съвпада със сбора на плащанията по правото ("
        + paid
        + "). Едните пари са записани два пъти с различни числа — поправете едното, преди да"
        + " потвърдите.";
  }

  /**
   * Потвърждаване при НЕПОКРИТА от календара година. Връща причината за отказ, или {@code null}.
   *
   * <p>⚠️ БЛОКИРА САМО КОГАТО ЧИСЛОТО Е НА СИСТЕМАТА. Ако количеството е още изведеното,
   * единственият му източник е броене без празници — тоест завишено, и никой не го знае. Ако
   * ТРЗ-специалистът го е надписал, числото е нечие решение: то може да е взето от лист хартия, от
   * НОИ или от календар, какъвто системата няма. Тогава блокировката би спряла човек, който знае
   * повече от нея.
   *
   * <p>Оттам и формата на съобщението: коя година липсва и кой файл се допълва. „Грешка в
   * календара“ не казва нито едното.
   *
   * <p><b>⚠️ „НАДПИСАНО“ ЗНАЧИ ПОЛОЖИТЕЛНО, НЕ „НЕ Е null“.</b> Викащият подава {@code
   * doc.getBgQuantity()}, а генерираният getter за decimal връща ZERO вместо null — тоест празното
   * количество идва тук като нула; ако нулата се брои за надписване, тя не съвпада с изведеното и
   * отказът се ПРЕСКАЧА точно когато трябва да се задейства. Същото важи за работните дни на фиша.
   * Нулата не е валидно количество (обезщетение за нула дни не е обезщетение и {@link
   * #refuseConfirm} го спира заради нулевата сума), затова тук тя се брои за непопълнено, а не за
   * нечие решение.
   *
   * @param derivedQuantity количеството, което системата извежда от срока
   * @param enteredQuantity количеството, което стои на документа; нула или {@code null} значи
   *     непопълнено
   * @param uncoveredYears годините без календар, като текст; празно значи пълно покритие
   */
  public static String refuseOnUncoveredCalendar(
      int derivedQuantity, BigDecimal enteredQuantity, String uncoveredYears) {

    if (uncoveredYears == null || uncoveredYears.isEmpty()) {
      return null;
    }
    if (enteredQuantity != null
        && enteredQuantity.signum() > 0
        && enteredQuantity.compareTo(BigDecimal.valueOf(derivedQuantity)) != 0) {
      return null; // надписано на ръка — решението е нечие и е проверимо
    }
    return "Срокът на предизвестието навлиза в "
        + uncoveredYears
        + " г., а производствен календар за нея НЯМА. Работните дни са преброени само по"
        + " понеделник–петък, БЕЗ официалните празници, тоест числото ("
        + derivedQuantity
        + ") е ЗАВИШЕНО. Допълнете"
        + " apps/init-data/en/bg_eventsPlanningLine.csv и fr/ със същата година и заредете наново,"
        + " или въведете количеството ръчно, ако го знаете от друг източник.";
  }

  /** Отказът иска причина — след шест месеца никой няма да помни защо документът е отменен. */
  public static String refuseCancel(String cancelReason) {
    return cancelReason == null || cancelReason.trim().isEmpty() ? "Отказът иска причина." : null;
  }

  // ═══ ПРАВО ПРЕДИ ПАРИ ═══════════════════════════════════════════════════════════════════════

  /** {@code bg.compensation.status.select} — потвърденото право. */
  public static final String ENTITLEMENT_CONFIRMED = "CONFIRMED";

  /** Чл. 220 — фактът е срокът на неспазеното предизвестие. */
  static final Set<String> NEEDS_NOTICE =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("comp_kt_220_1", "comp_kt_220_2")));

  /** Чл. 221 — фактът е основанието за прекратяване. */
  static final Set<String> NEEDS_GROUND =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("comp_kt_221_1", "comp_kt_221_2")));

  /** Чл. 225 ал. 1 — фактът е влязлото в сила съдебно решение. */
  static final String NEEDS_COURT_DECISION = "comp_kt_225_1";

  /** Чл. 226 ал. 2 — фактът е вписването на прекратяването в регистъра (ЕТЗ). */
  static final String NEEDS_ETZ_REGISTRATION = "comp_kt_226_2";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * ПРАВО ПРЕДИ ПАРИ: обезщетение, което възниква при прекратяване, се признава САМО срещу
   * потвърдено право. Връща причината за отказ, или {@code null}.
   *
   * <p>Важи само за видовете с {@code arisesOnTermination}. Обезщетенията ПО ВРЕМЕ на договора (чл.
   * 200, 213, 214, 216, 217, 232) нямат право и не се питат за него — „прекратен договор“ за тях е
   * юридически грешно.
   *
   * <p>Проверява: документът сочи право · то е ПОТВЪРДЕНО · за същия договор и вид · договорът е
   * ОСНОВЕН · договорът е прекратен · датата на прекратяване е една и съща в договора, в правото и
   * в документа. ⚠️ Датата на прекратяване НЕ пада към днешния ден ({@link #terminationDateOf}):
   * документ по договор, който още не е прекратен, не се потвърждава „днес“.
   */
  public static String refuseWithoutEntitlement(BgCompensation doc) {
    BgCompensationType type = doc == null ? null : doc.getBgCompensationType();
    if (type == null || !Boolean.TRUE.equals(type.getArisesOnTermination())) {
      return null;
    }
    String basis = basisOf(type);
    BgTerminationCompensation right = doc.getBgTerminationCompensation();
    if (right == null) {
      return "Обезщетението по „"
          + basis
          + "“ възниква при прекратяване и се признава САМО срещу потвърдено право. Документът не е"
          + " свързан с право — направете го от правото: Трудов договор › Обезщетения при"
          + " прекратяване › потвърденият ред › „Създай документ“. За чл. 220, чл. 225 ал. 1 и"
          + " чл. 226 ал. 2 правото се добавя ръчно там.";
    }
    if (!ENTITLEMENT_CONFIRMED.equals(right.getStatus())) {
      return "Правото по „"
          + basis
          + "“ не е потвърдено (състояние: "
          + right.getStatus()
          + ") — по непотвърдено право документ не се потвърждава.";
    }
    EmploymentContract contract = doc.getEmploymentContract();
    if (!sameId(right.getEmploymentContract(), contract)) {
      return "Правото е по друг трудов договор — документът и правото трябва да са за един и същ"
          + " договор.";
    }
    if (!sameId(right.getBgCompensationType(), type)) {
      return "Правото е за друг вид обезщетение — документът и правото трябва да са за един и същ"
          + " вид.";
    }
    if (!Boolean.TRUE.equals(contract.getBgIsPrimaryContract())) {
      return "По допълнителен трудов договор обезщетение при прекратяване не се поражда"
          + " — документът не се потвърждава.";
    }
    LocalDate terminated = terminationDateOf(contract);
    if (terminated == null) {
      return "Трудовият договор не е прекратен — няма нито дата на прекратяване, нито уговорен"
          + " край. Обезщетението по „"
          + basis
          + "“ се признава след прекратяването.";
    }
    if (!terminated.equals(right.getTerminationDate())) {
      return "Датата на прекратяване в договора ("
          + day(terminated)
          + ") не съвпада с датата в правото ("
          + day(right.getTerminationDate())
          + "). Поправете едното, преди да потвърдите.";
    }
    if (!terminated.equals(doc.getBgEventDate())) {
      return "Датата на основанието в документа ("
          + day(doc.getBgEventDate())
          + ") не съвпада с датата на прекратяване ("
          + day(terminated)
          + ").";
    }
    return null;
  }

  /**
   * Датата на прекратяване от самия договор: изричната, иначе уговореният край. ⚠️ БЕЗ днешния ден
   * — за разлика от {@code BgCompensationProposalResolver#effectiveTerminationDate}, която решава
   * дали да ПРЕДЛОЖИ право. Тук правото вече е налице и се сверява датата, не се гадае.
   */
  public static LocalDate terminationDateOf(EmploymentContract contract) {
    if (contract == null) {
      return null;
    }
    return contract.getBgTerminationDate() != null
        ? contract.getBgTerminationDate()
        : contract.getEndDate();
  }

  /**
   * Фактическите полета, без които обезщетението няма основание. Връща причината за отказ, или
   * {@code null}. Важи независимо от правото — и за документ, направен от право.
   */
  public static String refuseMissingFacts(BgCompensation doc) {
    BgCompensationType type = doc == null ? null : doc.getBgCompensationType();
    String code = type == null ? null : type.getCode();
    if (code == null) {
      return null;
    }
    String basis = basisOf(type);
    if (NEEDS_NOTICE.contains(code)
        && (doc.getBgNoticeStartDate() == null || doc.getBgNoticeCalendarDays() <= 0)) {
      return "Обезщетението по „"
          + basis
          + "“ е за НЕСПАЗЕНИЯ срок на предизвестието — попълнете началото и срока в календарни"
          + " дни.";
    }
    if (NEEDS_GROUND.contains(code)) {
      String ground = doc.getBgTerminationGround();
      if (isBlank(ground)) {
        return "Обезщетението по „" + basis + "“ иска основанието за прекратяване.";
      }
      if (!BgCompensationProposalResolver.matches(type, ground)) {
        return "Основанието за прекратяване ("
            + ground
            + ") не поражда обезщетение по „"
            + basis
            + "“.";
      }
    }
    if (NEEDS_COURT_DECISION.equals(code)
        && (isBlank(doc.getBgCourtDecisionNumber())
            || doc.getBgCourtDecisionEffectiveDate() == null)) {
      return "Обезщетението по „"
          + basis
          + "“ се дължи по ВЛЯЗЛО В СИЛА съдебно решение, с което уволнението е признато за"
          + " незаконно — попълнете номера на решението и датата на влизането му в сила.";
    }
    if (NEEDS_ETZ_REGISTRATION.equals(code) && doc.getBgEtzRegistrationDate() == null) {
      return "Обезщетението по „"
          + basis
          + "“ е за времето до вписването на прекратяването в регистъра — попълнете датата на"
          + " вписване в ЕТЗ.";
    }
    return null;
  }

  /**
   * Може ли от правото да се направи документ. Връща причината за отказ, или {@code null}.
   *
   * @param openDocument номерът на документ по това право, който още е в работа (чернова, потвърден
   *     или изплатен); {@code null} — няма такъв
   */
  public static String refuseDocumentFromEntitlement(
      BgTerminationCompensation right, String openDocument) {
    if (right == null) {
      return "Няма право, от което да се направи документ.";
    }
    if (!ENTITLEMENT_CONFIRMED.equals(right.getStatus())) {
      return "Документ се прави само от ПОТВЪРДЕНО право (сега: " + right.getStatus() + ").";
    }
    BgCompensationType type = right.getBgCompensationType();
    if (type == null || !Boolean.TRUE.equals(type.getArisesOnTermination())) {
      return "Видът обезщетение не е отбелязан като възникващ при прекратяване — документ от"
          + " право не се прави. Проверете „Възниква при прекратяване“ във вида.";
    }
    if (openDocument != null) {
      return "По това право вече има документ в работа ("
          + openDocument
          + "). Откажете го, преди да правите нов.";
    }
    return null;
  }

  private static boolean sameId(Model a, Model b) {
    return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
  }

  private static String basisOf(BgCompensationType type) {
    return type.getLegalBasis() != null ? type.getLegalBasis() : String.valueOf(type.getName());
  }

  private static String day(LocalDate date) {
    return date == null ? "—" : date.format(DAY);
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
