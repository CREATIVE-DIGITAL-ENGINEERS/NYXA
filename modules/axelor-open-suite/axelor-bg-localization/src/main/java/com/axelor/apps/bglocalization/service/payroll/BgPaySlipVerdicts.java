package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ПРИСЪДИТЕ НА ЕДИН ОБХОД НА ГЕНЕРАТОРИТЕ — и ШЕВЪТ, по който излизат на екрана.
 *
 * <p><b>⚠️ ТОЗИ КЛАС Е ЧИТАТЕЛЯТ, КОЙТО ЛИПСВАШЕ.</b> Списък с присъди без читател е мъртъв: точно
 * това беше положението в четирите генератора на фиша ({@code BgPaySlipService} редове 270, 293,
 * 317 и 411), където {@code generate()} се викаше и върнатият списък не се присвояваше на нищо.
 * Огледален е на {@code BgApp9Resolution} от Ф1 и по същия довод — там присъдите влизат в {@code
 * BgApp9Declaration.resolverNotes} и стоят до точката, останала празна заради тях.
 *
 * <p><b>⚠️ ДВА СПИСЪКА, А НЕ ЕДИН, И ТЕ ОТГОВАРЯТ НА РАЗЛИЧНИ ВЪПРОСИ.</b>
 *
 * <ul>
 *   <li>{@link #asNotes()} — ВСИЧКИТЕ присъди. „Класът прослужено време не се дължи по НСОРЗ чл. 12
 *       ал. 1“ е отговорът на въпрос, който операторът ще зададе, като види перото го няма — а
 *       отговор, който го няма, се връща като докладван от потребител дефект.
 *   <li>{@link #asGaps()} — САМО отказите, тоест „какво липсва и защо“. Отделен списък, защото при
 *       шейсет и няколко присъди на фиш пълният доклад е точно мястото, на което три отказа се
 *       губят.
 * </ul>
 *
 * <p><b>⚠️ ПРИСЪДИТЕ ЖИВЕЯТ ВЪРХУ ФИША, А НЕ В УСЛУГАТА.</b> {@code BgPaySlipService} е {@code
 * Singleton} и обслужва фишове успоредно: натрупване в негово поле би слепило присъдите на двама
 * души. Затова обходът пише В ЗАПИСА — {@link #reset(BgPaySlip)} в началото, {@link
 * #appendInto(BgPaySlip)} след всяка стъпка, {@link #seal(BgPaySlip)} накрая — и състоянието е
 * толкова успоредно, колкото са и самите фишове.
 *
 * <p><b>⚠️ И ЗАТОВА ШЕВОВЕТЕ НА {@code prepare()} НЕ СМЕНИХА ПОДПИСА СИ.</b> {@code protected void
 * generateEarnings(BgPaySlip)} остава {@code void}: над дузина заварени свидетеля го подменят и
 * разширен шев би ги повалил наведнъж. Записът е място, до което подписът не се пипа.
 *
 * <p><b>⚠️ НИТО ЕДНО ЧИСЛО НА ПАРИТЕ НЕ МИНАВА ОТТУК.</b> Класът чете вече взети решения и ги
 * превежда в текст. Перата, сумите, дните и сборовете на фиша не се докосват — инвариантът на Ф0
 * (перо 195 = 133,33 и разбивът 2/1) стои непокътнат по устройство, а не по договорка.
 */
public final class BgPaySlipVerdicts {

  // ═══ ИМЕНАТА НА СТЪПКИТЕ ════════════════════════════════════════════════
  // ⚠️ Константи, а не низове на място: името на стъпката излиза в текста, който операторът чете, и
  // в свидетелите на тестовете. Написано два пъти на ръка, то е свободно да се размине.

  /**
   * Извеждането на основната заплата от договора и допълнителните му споразумения — стъпката ПРЕДИ
   * всички генератори, защото те четат точно нея.
   */
  public static final String STEP_BASE_SALARY = "Основна заплата";

  /** Болничният по чл. 40 ал. 5 КСО — първата стъпка на {@code prepare()}. */
  public static final String STEP_SICK_LEAVE = "Болничен";

  /** Начисленията (група 1xx). */
  public static final String STEP_EARNINGS = "Начисления";

  /** Обезщетенията като пера (група 9xx). */
  public static final String STEP_COMPENSATIONS = "Обезщетения";

  /** Осигуровките като пера (група 5xx). */
  public static final String STEP_CONTRIBUTIONS = "Осигуровки";

  /** Здравната вноска при неплатен отпуск — ЗЗО чл. 40 ал. 1 т. 1 б. „б“. */
  public static final String STEP_UNPAID_LEAVE_HEALTH = "Здравна · неплатен отпуск";

  /** Стъпката на възнаграждението за платен отпуск по КТ чл. 177. */
  public static final String STEP_ART177_LEAVE = "Платен отпуск · чл. 177";

  /** Данъкът (група 7xx) — по чл. 42, чл. 43 или чл. 41а ЗДДФЛ. */
  public static final String STEP_INCOME_TAX = "Данък";

  /**
   * Годишното изравняване по чл. 49 ЗДДФЛ (перо 710) — САМО на януарски фиш.
   *
   * <p>⚠️ Отделна стъпка, а не част от „Данък“, макар и двете да пишат в група 7xx. Двете отговарят
   * на различни въпроси пред различни хора: „Данък“ казва колко е удържано ЗА ТОЗИ МЕСЕЦ по чл. 42,
   * а тази стъпка — как е приключила МИНАЛАТА ГОДИНА по чл. 49. Слети, отказът на едната би се
   * четял като провал на другата: липсваща справка по чл. 73 ал. 6 щеше да изглежда като липсваща
   * данъчна ставка, и операторът щеше да отвори каталога с параметри.
   */
  public static final String STEP_ART49_SETTLEMENT = "Годишно изравняване · чл. 49";

  /**
   * Кодът, под който излиза здравната вноска при неплатен отпуск.
   *
   * <p>⚠️ Не е код от речника с типове пера: резолверът ѝ не носи код, а типът се намира по
   * двойката фонд + знак чак при записа. Тук стои името на ХИПОТЕЗАТА, за да има присъдата за какво
   * да е.
   */
  public static final String CODE_UNPAID_LEAVE_HEALTH = "ЗЗО чл. 40 ал. 1 т. 1 б. „б“";

  /**
   * Кодът, под който излиза изведената основна заплата.
   *
   * <p>⚠️ И той не е код от речника с типове пера: заплатата не е перо, а ВХОД, от който перата се
   * смятат. Стои името на въпроса, за да има присъдата за какво да е — „кое число влезе и откъде“.
   */
  public static final String CODE_BASE_SALARY = "КТ чл. 119 ал. 1 · основно възнаграждение";

  private final List<BgPaySlipVerdict> verdicts = new ArrayList<>();

  // ═══ НАТРУПВАНЕТО ═══════════════════════════════════════════════════════

  /** Една присъда. */
  public BgPaySlipVerdicts add(BgPaySlipVerdict verdict) {
    if (verdict != null) {
      verdicts.add(verdict);
    }
    return this;
  }

  /**
   * Присъдите на НАЧИСЛЕНИЯТА.
   *
   * <p>⚠️ {@code null} се приема като празен списък, а не гърми: генераторите връщат {@code
   * Collections.emptyList()} при пропуснат фиш, но подменен четец в тест има право да върне {@code
   * null}, а тест, който пада заради ДОКЛАДА, не казва нищо за сметките.
   */
  public BgPaySlipVerdicts addEarnings(String step, List<BgEarningsOutcome> outcomes) {
    if (outcomes != null) {
      for (BgEarningsOutcome outcome : outcomes) {
        add(BgPaySlipVerdict.of(step, outcome));
      }
    }
    return this;
  }

  /** Присъдите на ОБЕЗЩЕТЕНИЯТА, ОСИГУРОВКИТЕ и ДАНЪКА — те делят един речник на състояния. */
  public BgPaySlipVerdicts addLines(String step, List<BgPaySlipLineOutcome> outcomes) {
    if (outcomes != null) {
      for (BgPaySlipLineOutcome outcome : outcomes) {
        add(BgPaySlipVerdict.of(step, outcome));
      }
    }
    return this;
  }

  /** Присъдите на БОЛНИЧНИЯ. */
  public BgPaySlipVerdicts addSickLeave(String step, List<BgSickLeaveOutcome> outcomes) {
    if (outcomes != null) {
      // ⚠️ ЕДИН ОТКАЗ — ЕДНА ПРИСЪДА. Перото 195 и здравната вноска излизат под ЕДИН код и при
      // отказан разбив носят ЕДНА И СЪЩА причина — без това тя стои два пъти в „Какво липсва“, а
      // броячът на отказите казва 2 за един дефект. Еднаквият отказ се пропуска;
      // различните остават всичките.
      Set<String> refused = new HashSet<>();
      for (BgSickLeaveOutcome outcome : outcomes) {
        BgPaySlipVerdict verdict = BgPaySlipVerdict.of(step, outcome);
        if (verdict.refusal && !refused.add(verdict.asGap())) {
          continue;
        }
        add(verdict);
      }
    }
    return this;
  }

  /** Присъдата на ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК — една, не списък. */
  public BgPaySlipVerdicts addUnpaidLeaveHealth(
      String step, BgUnpaidLeaveHealthResolver.Outcome outcome) {
    if (outcome != null) {
      add(BgPaySlipVerdict.of(step, CODE_UNPAID_LEAVE_HEALTH, outcome));
    }
    return this;
  }

  /** Присъдата на ВЪЗНАГРАЖДЕНИЕТО ЗА ПЛАТЕН ОТПУСК по чл. 177 — една, не списък. */
  public BgPaySlipVerdicts addArt177Leave(String step, BgArt177Outcome outcome) {
    if (outcome != null) {
      add(BgPaySlipVerdict.of(step, outcome));
    }
    return this;
  }

  // ═══ ЧЕТЕНЕТО ═══════════════════════════════════════════════════════════

  /** Присъдите, както са дадени. Неизменяем изглед. */
  public List<BgPaySlipVerdict> all() {
    return Collections.unmodifiableList(verdicts);
  }

  /** Колко присъди има общо. */
  public int size() {
    return verdicts.size();
  }

  /** Колко искат някой да поправи нещо СЕГА. Това е числото, което влиза в предупреждението. */
  public int refusalCount() {
    int count = 0;
    for (BgPaySlipVerdict verdict : verdicts) {
      if (verdict.refusal) {
        count++;
      }
    }
    return count;
  }

  /** Само отказите — за съобщението към оператора и за свидетелите. */
  public List<BgPaySlipVerdict> refusals() {
    List<BgPaySlipVerdict> out = new ArrayList<>();
    for (BgPaySlipVerdict verdict : verdicts) {
      if (verdict.refusal) {
        out.add(verdict);
      }
    }
    return out;
  }

  /** ВСИЧКИТЕ присъди, по ред на даване, слепени в текста на полето. Празно при нула присъди. */
  public String asNotes() {
    StringBuilder sb = new StringBuilder();
    for (BgPaySlipVerdict verdict : verdicts) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(verdict.asNote());
    }
    return sb.toString();
  }

  /** САМО отказите — списъкът „какво липсва и защо“. Празно значи, че нищо не е отказано. */
  public String asGaps() {
    StringBuilder sb = new StringBuilder();
    for (BgPaySlipVerdict verdict : verdicts) {
      if (!verdict.refusal) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(verdict.asGap());
    }
    return sb.toString();
  }

  // ═══ ШЕВЪТ КЪМ ФИША ═════════════════════════════════════════════════════

  /**
   * ИЗТРИВА присъдите от предишния обход. Вика се ВЕДНЪЖ, в началото на {@code prepare()}.
   *
   * <p>⚠️ Задължително, не козметика. Без него второ {@code prepare()} след поправка на договора
   * оставя стария отказ да стои под новите присъди — тоест текст, който днешните данни вече
   * отричат, до число, което вече е вярно. Същият довод, по който {@code
   * BgApp9DeclarationService#resolveInto} презаписва изведените стойности и с {@code null}.
   *
   * <p>⚠️ И ВЪЗРАСТТА СЕ НУЛИРА С ТЯХ. {@code bgGeneratedAt} е онова, което различава „нула откази“
   * от „генераторите не са пускани“ — броячът е {@code Integer} и генерираният му getter връща 0 и
   * за {@code null}, тоест сам по себе си той не може да каже кое от двете е.
   */
  public static void reset(BgPaySlip slip) {
    if (slip == null) {
      return;
    }
    slip.setBgGeneratorNotes(null);
    slip.setBgGeneratorGaps(null);
    slip.setBgGeneratorRefusalCount(0);
    slip.setBgGeneratedAt(null);
  }

  /**
   * ДОЛЕПЯ присъдите на ЕДНА стъпка към записа — тук изхвърлянето спира.
   *
   * <p>⚠️ Долепя, а не презаписва: шестте стъпки пишат една след друга в един обход и всяка вижда
   * само своите присъди. Презапис би оставил на фиша доклада на последната.
   */
  public void appendInto(BgPaySlip slip) {
    if (slip == null || verdicts.isEmpty()) {
      return;
    }
    slip.setBgGeneratorNotes(join(slip.getBgGeneratorNotes(), asNotes()));
    slip.setBgGeneratorGaps(join(slip.getBgGeneratorGaps(), asGaps()));
    slip.setBgGeneratorRefusalCount(slip.getBgGeneratorRefusalCount() + refusalCount());
  }

  /**
   * ЗАТВАРЯ обхода: слага възрастта на присъдите.
   *
   * <p>Вика се и по двата пътя на {@code prepare()} — и при наетия, и при самоосигуряващото се
   * лице. Пропуснат по единия, полето би твърдяло, че генераторите не са пускани точно за фиша, за
   * когото са пуснати.
   */
  public static void seal(BgPaySlip slip) {
    if (slip == null) {
      return;
    }
    slip.setBgGeneratedAt(LocalDateTime.now());
  }

  /** Долепяне с нов ред между двете, без празни редове при празна страна. */
  private static String join(String existing, String addition) {
    if (addition == null || addition.isEmpty()) {
      return existing;
    }
    if (existing == null || existing.isEmpty()) {
      return addition;
    }
    return existing + "\n" + addition;
  }
}
