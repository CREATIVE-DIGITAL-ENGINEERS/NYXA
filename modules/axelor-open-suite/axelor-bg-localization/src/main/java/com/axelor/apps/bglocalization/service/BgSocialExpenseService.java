package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipCapUsage;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSocialExpenseCap;
import com.axelor.apps.bglocalization.db.BgSocialExpenseException;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * СОЦИАЛНИТЕ РАЗХОДИ НА ЕДИН ФИШ — базовата половина: КОИ са условията и КОЛКО е месечната ЗКПО
 * основа.
 *
 * <p><b>Границата спрямо чистия резолвер.</b> {@link BgSocialExpenseCapResolver} знае как се дели
 * едно начисление и не знае нито за база, нито за дати. Тази услуга прави обратното: чете реда с
 * изключението, смята средната договорена заплата за три месеца, събира вече използваното от
 * капачката — и ПОДАВА трите отговора. Тя самата не дели нищо.
 *
 * <p><b>⚠️ ЛИПСВАЩИЯТ РЕД В {@code BgSocialExpenseException} НЕ Е ОТКАЗ, А РАЗРЕШЕНИЕ.</b> Това е
 * обратното на {@code BgGarnishmentService}, където неустановимият признак е {@code UNDETERMINABLE}
 * и води до отказ. Причината е цената на грешката: там подразбирането взима пари от длъжник, чиито
 * данни не са попълнени, а тук подразбирането „фирмата има публични задължения“ би обложило целия
 * размер на всяка фирма, която не води списък с лоши месеци. Двете подразбирания са противоположни,
 * защото рисковете им са противоположни.
 *
 * <p><b>⚠️ И ЕДНО, КОЕТО НЕ Е ПОДРАЗБИРАНЕ, А ИЗЧИСЛЕНИЕ.</b> Третото условие (чл. 209а ал. 1 т. 1)
 * НЕ се отмята — то се смята от предходните фишове. Отметка за него би била втори източник на
 * истина до самите фишове и щеше да се разминава с тях мълчаливо.
 */
public class BgSocialExpenseService {

  /**
   * Колко предходни месеца влизат в средното по чл. 209а ал. 1 т. 1 — три, по текста на нормата.
   */
  static final int MONTHS_IN_AVERAGE = 3;

  /**
   * МЕСЕЧНАТА ЗКПО ОСНОВА за този фиш — числото, което сяда на {@code
   * BgPaySlip.bgSocialExpenseTaxBase}.
   *
   * <p><b>⚠️ ИЗЛИЗА НА ПЪРВИЯ РЕД, КОГАТО ФИШЪТ НЯМА НИТО ЕДНО 2xx ПЕРО — И ТОВА НЕ Е
   * ОПТИМИЗАЦИЯ.</b> Проверката е ЧИСТА ({@link BgSocialExpenseCapResolver#isSocialExpenseLine}
   * чете номера, копиран на самото перо) и стои ПРЕДИ всяка заявка. Оттам следва, че всеки заварен
   * фиш минава през този шев, без да е поискал каквото и да е от Guice или от Hibernate — тоест
   * стотиците тестове, които викат {@code recompute()} без контейнер, продължават да текат.
   *
   * <p><b>⚠️ ОТКАЗЪТ НЕ СЕ ПРЕВРЪЩА В НУЛА.</b> Група с отказ (незаредена капачка, недовършен ред)
   * НЕ добавя нищо към основата и се назовава в {@link #refusals(BgPaySlip)}. Прочетен като нула,
   * отказът щеше да даде на фиша ЗКПО основа, по-малка от вярната, с вид на пресметната — точно
   * грешката, която {@code BgIncomeTaxService} избягва при незареден параметър.
   */
  public BigDecimal monthlyTaxBase(BgPaySlip slip) {
    BigDecimal total = BigDecimal.ZERO.setScale(2);
    for (BgSocialExpenseOutcome outcome : assess(slip)) {
      if (!outcome.hasRefusal()) {
        total = total.add(outcome.companyTaxBase);
      }
    }
    for (BgPaySlipLine line : socialExpenseLines(slip)) {
      // Редовете БЕЗ капачка, за които ЗКПО не дава освобождаване — целият размер (чл. 210 ал. 2 и
      // придобивките в натура по ред 270). Капачка тук НЯМА и точно затова признакът е на перото.
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null
          && type.getBgSocialExpenseCap() == null
          && type.getBgIsCompanyExpenseTaxed()) {
        total = total.add(line.getBgAmount());
      }
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Назованите откази по групи, или празен списък. Питат се ПРЕДИ числото да се приеме за вярно.
   */
  public List<String> refusals(BgPaySlip slip) {
    List<String> out = new ArrayList<>();
    for (BgSocialExpenseOutcome outcome : assess(slip)) {
      if (outcome.hasRefusal()) {
        out.add(outcome.refusal);
      }
    }
    return out;
  }

  /**
   * ОЦЕНКАТА ПО ГРУПИ — по един изход за всяка капачка, към която сочи поне едно перо на фиша.
   *
   * <p>⚠️ ГРУПИРА СЕ ПО КАПАЧКА, А НЕ ПО ПЕРО, защото капачката Е ОБЩА: шест реда сочат към {@code
   * DDO_HEALTH_LIFE} и делят едните 30,68 EUR (ЗКПО чл. 213 ал. 2 — „превишението на ТЕЗИ
   * разходи“). Оценени поотделно, трите позиции щяха да получат по цяла капачка.
   *
   * <p>⚠️ РЕДЪТ ВЪТРЕ В ГРУПАТА Е ПО НОМЕР НА ПЕРОТО, и това е нужно за повторяемост: остатъкът от
   * закръглянето при пропорционалното разпределение отива по подредбата на входа. Подадени в реда,
   * в който Hibernate е върнал списъка, същите три суми биха дали различни стотинки при всяко
   * преизчисляване.
   */
  public List<BgSocialExpenseOutcome> assess(BgPaySlip slip) {
    List<BgSocialExpenseOutcome> out = new ArrayList<>();
    for (Group group : groups(slip)) {
      out.add(group.outcome);
    }
    return out;
  }

  /** Една капачка на фиша: позициите ѝ, използваното преди фиша и делението. */
  protected static final class Group {
    final BgSocialExpenseCap cap;
    final BigDecimal usedBefore;
    final BgSocialExpenseOutcome outcome;

    /** Перата „в рамките“ — в реда на {@code outcome.shares}, по едно на дял. */
    final List<BgPaySlipLine> lines;

    Group(
        BgSocialExpenseCap cap,
        BigDecimal usedBefore,
        BgSocialExpenseOutcome outcome,
        List<BgPaySlipLine> lines) {
      this.cap = cap;
      this.lines = lines;
      this.usedBefore = usedBefore;
      this.outcome = outcome;
    }
  }

  /**
   * ГРУПИТЕ ПО КАПАЧКА — с използваното преди фиша и с делението.
   *
   * <p><b>⚠️ ИЗПОЛЗВАНОТО ИДВА ОТ ДВЕ МЕСТА, И ТО НАРОЧНО.</b> Редовният фиш го чете ЖИВО ({@link
   * #usedBefore}) — той е за текущия месец и точно днешното състояние на по-ранните фишове е
   * истината за него. Ретро-копието го чете ЗАМРАЗЕНО от реда си в {@code bgCapUsageList}, наследен
   * от оригинала: коригира се ОНЗИ месец, а корекция на по-ранен месец, потвърдена оттогава, иначе
   * щеше да мести тихо освобождаването на месец, който никой не е пипал. Същият довод като режимът
   * на осигуряване ({@code BgPayrollRegime}).
   *
   * <p>⚠️ ЛИПСВАЩ ЗАМРАЗЕН РЕД НА КОПИЕТО Е ОТКАЗ И ЗА МЕСЕЧНА КАПАЧКА. Резолверът чете {@code
   * null} като нула при месечна — тоест без изричния отказ тук копие на фиш отпреди замразяването
   * щеше да получи цялата капачка за втори път в същия месец.
   */
  protected List<Group> groups(BgPaySlip slip) {
    List<Group> out = new ArrayList<>();
    List<BgPaySlipLine> lines = socialExpenseLines(slip);
    if (lines.isEmpty()) {
      return out;
    }

    Map<BgSocialExpenseCap, List<BgSocialExpenseCapResolver.Item>> byCap = new LinkedHashMap<>();
    Map<BgSocialExpenseCap, List<BgPaySlipLine>> linesByCap = new LinkedHashMap<>();
    for (BgPaySlipLine line : BgPaySlipResolver.ordered(lines)) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type == null || type.getBgSocialExpenseCap() == null) {
        continue;
      }
      // ⚠️ ПОДАВА СЕ САМО РЕДЪТ „В РАМКИТЕ“, НЕ И „ПРЕВИШЕНИЕ“. Операторът въвежда цялото начислено
      // на реда „в рамките“ (MANUAL_AMOUNT) и резолверът връща разделянето; редът „превишение“ е
      // ИЗХОД (DERIVED). Подадени и двата, вече изнесеното превишение щеше да се брои втори път и
      // основата щеше да е двойна.
      // ⚠️ ПО bgIsCapExcess, НЕ ПО bgIsTaxable. Познато по облагаемостта, превишението по ЗКПО
      // чл. 213 ал. 2 не може да стане необлагаемо за лицето (ЗДДФЛ чл. 24 ал. 2 т. 9): свален флаг
      // тук би го вкарал като вход.
      if (Boolean.TRUE.equals(type.getBgIsCapExcess())) {
        continue;
      }
      byCap
          .computeIfAbsent(type.getBgSocialExpenseCap(), k -> new ArrayList<>())
          .add(new BgSocialExpenseCapResolver.Item(type.getCode(), enteredAmountOf(line)));
      linesByCap.computeIfAbsent(type.getBgSocialExpenseCap(), k -> new ArrayList<>()).add(line);
    }

    BgSocialExpenseConditions conditions = conditionsFor(slip);
    boolean retro = BgPaySlipResolver.isRetro(slip);
    for (Map.Entry<BgSocialExpenseCap, List<BgSocialExpenseCapResolver.Item>> e :
        byCap.entrySet()) {
      BgSocialExpenseCap cap = e.getKey();
      String missing = null;
      BigDecimal used = null;
      if (retro) {
        BgPaySlipCapUsage row = frozenRow(slip, cap);
        if (row == null || row.getBgUsedBefore() == null) {
          missing = missingFrozenRefusal(slip, cap);
        } else {
          used = row.getBgUsedBefore();
        }
      } else if (slip.getEmploymentContract() == null) {
        // Без договор няма работодател, по когото да се брои — нищо не е използвано преди. Същото
        // като досега; и пази тестовете без база, които сглобяват фиш само от пера.
        used = BigDecimal.ZERO.setScale(2);
      } else {
        used = usedBefore(slip, cap);
      }
      out.add(
          new Group(
              cap,
              used,
              missing != null
                  ? BgSocialExpenseOutcome.refused(missing)
                  : BgSocialExpenseCapResolver.apply(e.getValue(), cap, conditions, used),
              linesByCap.get(cap)));
    }
    return out;
  }

  /**
   * ВЪВЕДЕНАТА СУМА на перо „в рамките“ — {@code bgEnteredAmount}, а при празно {@code bgAmount}.
   *
   * <p>⚠️ ДЕЛИ СЕ ВЪВЕДЕНОТО, НЕ ПЕРОТО. Перото на еднократната помощ носи след „Преизчисли“ само
   * освободената част (виж {@link #splitPersonalExcess}); второ „Преизчисли“, четящо {@code
   * bgAmount}, щеше да дели вече намалената сума и превишението да изчезне. Прецедентът и
   * семантиката са на ръчните постоянни пера ({@code BgEarningsService#enteredAmountOf}): getter-ът
   * за decimal връща нула, не {@code null}, а помощ от 0,00 не се въвежда.
   */
  static BigDecimal enteredAmountOf(BgPaySlipLine line) {
    BigDecimal entered = line.getBgEnteredAmount();
    return entered.signum() == 0 ? line.getBgAmount() : entered;
  }

  /** Превишението на едно перо „в рамките“. */
  public static final class Excess {
    public final BgPaySlipLine within;
    public final BigDecimal excess;

    Excess(BgPaySlipLine within, BigDecimal excess) {
      this.within = within;
      this.excess = excess;
    }
  }

  /**
   * ДЕЛИ ЕДНОКРАТНИТЕ ПОМОЩИ НА ПЕРАТА: перото „в рамките“ получава освободената част, а
   * превишенията се връщат на генератора ({@code BgPaySlipService#generateSocialExpenseExcess}),
   * който пише 221/223/225.
   *
   * <p><b>⚠️ САМО КАПАЧКИТЕ БЕЗ ФИРМЕНА ОС ({@code bgCompanyTaxApplies = false}).</b> Там
   * превишението е ДОХОД НА ЛИЦЕТО (ЗКПО чл. 205 → ЗДДФЛ чл. 24 ал. 1) и трябва да е перо, за да
   * влезе в основата по чл. 42 ал. 2 и в т. 31 на Д1. При капачките с фирмена ос (ДДО/ДЗО/„Живот“,
   * ваучери) превишението е основа на данъка върху разходите и остава число на фиша; дали и там да
   * стане перо е отделно решение (211 чака счетоводителя). Признакът е на реда на капачката, не код
   * в Java.
   *
   * <p><b>⚠️ ВЪВЕДЕНОТО СЕ УЛАВЯ ТУК, ПРЕДИ ДЕЛЕНИЕТО</b> — по довода при {@code
   * BgEarningsService#carveManualPermanentLines}: формата, REST и вносът минават през едно място.
   *
   * <p><b>⚠️ ЛИПСВАЩ ВХОД — ШУМЕН ОТКАЗ.</b> Перо от 2xx без вид: събитието (раждане, брак, смърт)
   * и таванът са неизвестни, а перото е необлагаемо по флаговете си. Фиш с договор и без период:
   * календарната година на тавана е неизвестна.
   *
   * @return превишенията, по едно на перо „в рамките“ (и нулевите — генераторът маха реда си)
   */
  public List<Excess> splitPersonalExcess(BgPaySlip slip) {
    List<Excess> out = new ArrayList<>();
    boolean any = false;
    for (BgPaySlipLine line : socialExpenseLines(slip)) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type == null) {
        throw new BgCalculationRefusedException(
            "Перото № "
                + line.getBgLineNumber()
                + " „"
                + line.getName()
                + "“ е социален разход, но няма вид — събитието и таванът му са неизвестни, а"
                + " необлагаемостта му идва от флаговете. Изберете вида перо и пресметнете отново.");
      }
      if (isPersonalOnly(type) && !Boolean.TRUE.equals(type.getBgIsCapExcess())) {
        any = true;
        if (line.getBgEnteredAmount().signum() == 0) {
          line.setBgEnteredAmount(line.getBgAmount());
        }
      }
    }
    if (!any) {
      return out;
    }
    if (slip.getEmploymentContract() != null && monthOf(slip) == null) {
      throw new BgCalculationRefusedException(
          "Фишът носи еднократна помощ, но няма период — календарната година на тавана по ЗДДФЛ"
              + " чл. 24 ал. 2 т. 11 е неизвестна. Изберете периода и пресметнете отново.");
    }
    for (Group group : groups(slip)) {
      if (group.cap.getBgCompanyTaxApplies()) {
        continue;
      }
      if (group.outcome.hasRefusal()) {
        throw new BgCalculationRefusedException(
            "Еднократната помощ не може да се раздели на освободена част и превишение: "
                + group.outcome.refusal
                + ".");
      }
      for (int i = 0; i < group.lines.size(); i++) {
        BgSocialExpenseOutcome.Share share = group.outcome.shares.get(i);
        BgPaySlipLine within = group.lines.get(i);
        within.setBgAmount(share.exempt);
        out.add(new Excess(within, share.excess));
      }
    }
    return out;
  }

  /** Перо под капачка без фирмена ос — превишението е доход на лицето. */
  static boolean isPersonalOnly(BgPaySlipLineType type) {
    BgSocialExpenseCap cap = type == null ? null : type.getBgSocialExpenseCap();
    return cap != null && !cap.getBgCompanyTaxApplies();
  }

  // ── използваното от тавана ──────────────────────────────────────────────

  /**
   * ЗАМРАЗЯВА ИЗПОЛЗВАНОТО на фиша — по един ред за капачка, — и сваля редовете на капачки, които
   * фишът вече няма. Вика се от „Преизчисли“ ({@code BgPaySlipService#calculate}).
   *
   * <p>Редовен фиш: и двете числа наново — използваното живо, освободеното от делението.
   * Ретро-копие: използваното СТОИ (наследено от оригинала), а освободеното се смята наново по
   * него, защото копието може да е сменило сумите. Липсващ ред на копието НЕ се допълва тук — виж
   * {@link #fillMissing}.
   *
   * <p>⚠️ РЕДЪТ СЕ ОБНОВЯВА НА МЯСТО, НЕ СЕ ДОБАВЯ. Второ „Преизчисли“ трябва да остави същия фиш;
   * добавен, вторият ред щеше да удвои освободеното в заявката на следващите месеци.
   *
   * <p>⚠️ ОТКАЗАНА ГРУПА ОСТАВЯ {@code bgExempt} ПРАЗНО, не нула: нула значи „нищо не е
   * освободено“. Отказаният фиш не се потвърждава ({@code socialExpenseRefusal}), тоест празното не
   * стига до заявката.
   */
  public void freeze(BgPaySlip slip, LocalDateTime at) {
    boolean retro = BgPaySlipResolver.isRetro(slip);
    List<Group> groups = groups(slip);
    if (slip.getBgCapUsageList() == null) {
      slip.setBgCapUsageList(new ArrayList<>());
    }
    List<String> present = new ArrayList<>();
    for (Group group : groups) {
      present.add(group.cap.getCode());
      BgPaySlipCapUsage row = frozenRow(slip, group.cap);
      if (row == null) {
        if (retro) {
          continue;
        }
        row = new BgPaySlipCapUsage();
        row.setBgSocialExpenseCap(group.cap);
        slip.addBgCapUsageListItem(row);
      }
      if (!retro) {
        row.setBgSocialExpenseCap(group.cap);
        row.setBgUsedBefore(group.usedBefore);
      }
      row.setBgExempt(group.outcome.hasRefusal() ? null : group.outcome.exempt);
    }
    slip.getBgCapUsageList()
        .removeIf(
            row ->
                row.getBgSocialExpenseCap() == null
                    || !present.contains(row.getBgSocialExpenseCap().getCode()));
    if (!retro) {
      slip.setBgCapUsageFrozenAt(at);
    }
  }

  /**
   * ⚠️ ПАЗАЧЪТ ПРИ ПОТВЪРЖДАВАНЕ — замразеното отговаря ли на прясната сметка. Причина за отказ,
   * или {@code null}.
   *
   * <p>Три проверки, всяка със своя довод:
   *
   * <ul>
   *   <li><b>липсващ ред</b> — капачка на фиша без замразено използвано. Фишът не е пресмятан след
   *       замразяването, а копието — от оригинал отпреди него;
   *   <li><b>използваното се е сменило</b> (само редовен фиш) — между „Преизчисли“ и „Потвърди“ е
   *       потвърден друг фиш на лицето в дружеството (втори договор в същия месец, корекция на
   *       по-ранен). Без тази проверка ДВА фиша, пресметнати преди който и да е от тях да е
   *       потвърден, получават цялата капачка ВСЕКИ — двойно освобождаване с вид на пресметнато;
   *   <li><b>освободеното на реда не е делението</b> — редът е пипан покрай „Преизчисли“ (REST,
   *       внос). Той е ИЗТОЧНИКЪТ за следващите месеци и грешен там се размножава.
   * </ul>
   */
  public String usageRefusal(BgPaySlip slip) {
    boolean retro = BgPaySlipResolver.isRetro(slip);
    List<String> problems = new ArrayList<>();
    for (Group group : groups(slip)) {
      BgPaySlipCapUsage row = frozenRow(slip, group.cap);
      String code = "„" + group.cap.getCode() + "“";
      if (row == null || row.getBgUsedBefore() == null) {
        problems.add(code + ": използваното не е замразено");
        continue;
      }
      if (!retro
          && group.usedBefore != null
          && row.getBgUsedBefore().compareTo(group.usedBefore) != 0) {
        problems.add(
            code
                + ": използваното преди фиша е "
                + amount(group.usedBefore)
                + ", а фишът е пресметнат с "
                + amount(row.getBgUsedBefore()));
        continue;
      }
      if (!group.outcome.hasRefusal()
          && (row.getBgExempt() == null
              || row.getBgExempt().compareTo(group.outcome.exempt) != 0)) {
        problems.add(
            code
                + ": освободеното на реда е "
                + amount(row.getBgExempt())
                + ", а делението дава "
                + amount(group.outcome.exempt));
      }
    }
    if (problems.isEmpty()) {
      return null;
    }
    return "Използваното от таваните на социалните разходи не отговаря на фиша: "
        + String.join("; ", problems)
        + ". Натиснете „Преизчисли“"
        + (retro
            ? " (а ако използваното липсва — „Попълни от текущите данни“)"
            : " — другият фиш вече е потвърден и този дели остатъка")
        + ".";
  }

  /**
   * ДОПЪЛВА ЛИПСВАЩИТЕ РЕДОВЕ НА РЕТРО-КОПИЕ от днешните потвърдени фишове. Връща текста за одита,
   * или {@code null}, ако нищо не липсва.
   *
   * <p>Два повода: изричното „Попълни от текущите данни“ за копие на фиш отпреди замразяването
   * ({@code at} — моментът, който става дата на замразяване), и „Преизчисли“ на копие, чийто
   * оригинал е пресметнат след замразяването, но няма перо по капачката ({@code at = null} — датата
   * на оригинала остава).
   *
   * <p>⚠️ ВЕРИГАТА НА КОПИЕТО СЕ ИЗКЛЮЧВА: фишовете на СЪЩИЯ договор за СЪЩИЯ период — оригиналът,
   * предходните му корекции. Иначе копието щеше да брои като „използвано преди“ точно месеца, който
   * коригира.
   */
  public String fillMissing(BgPaySlip copy, LocalDateTime at) {
    if (!BgPaySlipResolver.isRetro(copy) || copy.getEmploymentContract() == null) {
      return null;
    }
    if (copy.getBgCapUsageList() == null) {
      copy.setBgCapUsageList(new ArrayList<>());
    }
    List<String> filled = new ArrayList<>();
    for (BgSocialExpenseCap cap : capsOn(copy)) {
      BgPaySlipCapUsage row = frozenRow(copy, cap);
      if (row != null && row.getBgUsedBefore() != null) {
        continue;
      }
      if (row == null) {
        row = new BgPaySlipCapUsage();
        row.setBgSocialExpenseCap(cap);
        copy.addBgCapUsageListItem(row);
      }
      row.setBgUsedBefore(query(copy, cap, true));
      filled.add(cap.getCode() + " " + amount(row.getBgUsedBefore()));
    }
    if (at != null) {
      copy.setBgCapUsageFrozenAt(at);
    }
    return filled.isEmpty() ? null : String.join(", ", filled);
  }

  /**
   * Отказът за ретро-копие без замразено използвано за някоя от капачките си, или {@code null}. Без
   * заявка.
   */
  public String missingOnRetro(BgPaySlip slip) {
    if (!BgPaySlipResolver.isRetro(slip)) {
      return null;
    }
    for (BgSocialExpenseCap cap : capsOn(slip)) {
      BgPaySlipCapUsage row = frozenRow(slip, cap);
      if (row == null || row.getBgUsedBefore() == null) {
        String text = missingFrozenRefusal(slip, cap);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
      }
    }
    return null;
  }

  /**
   * ВЕЧЕ ИЗПОЛЗВАНОТО ОТ КАПАЧКАТА ПРЕДИ ТОЗИ (РЕДОВЕН) ФИШ — живо.
   *
   * <p>⚠️ ЕДНА ЗАЯВКА ЗА ВСИЧКИ КАПАЧКИ. Годишната и месечната се различават само по прозореца —
   * календарната година или месецът на фиша. ЗДДФЛ чл. 24 ал. 2 т. 11 („общо до 2400 лв.“, прочит:
   * календарна година при един работодател) и ЗКПО чл. 213 ал. 2 / чл. 214 ал. 2 („месечно за всяко
   * наето лице“) отговарят на един и същ въпрос с различен период.
   */
  protected BigDecimal usedBefore(BgPaySlip slip, BgSocialExpenseCap cap) {
    return query(slip, cap, false);
  }

  /**
   * ЗАЯВКАТА: сборът на освободените части ({@code BgPaySlipCapUsage.bgExempt}) на ДРУГИТЕ
   * действащи фишове на лицето в дружеството, в прозореца на капачката до периода на фиша
   * включително.
   *
   * <ul>
   *   <li><b>По СЛУЖИТЕЛ и ДРУЖЕСТВО, не по договор.</b> Таванът е на лицето при един работодател:
   *       два договора в едно дружество делят едната капачка, а дружество Б е друг работодател и
   *       друга изолирана среда — неговите фишове не местят тавана на А.
   *   <li><b>Само ПОТВЪРДЕНИ (или изплатени).</b> Черновата още не е твърдение — може да бъде
   *       отказана или преизчислена.
   *   <li><b>Само ДЕЙСТВАЩОТО.</b> Коригиращото копие носи ПЪЛНИТЕ верни числа за месеца и ЗАМЕНЯ
   *       оригинала, тоест от двата се брои едното: оригинал с потвърдена корекция или заличаване
   *       не се брои; корекция, коригирана наново или чийто оригинал е заличен — също; от две
   *       корекции на един оригинал — по-късната. Заличаващото копие не се брои никога.
   *   <li><b>До периода на фиша ВКЛЮЧИТЕЛНО.</b> Друг договор в същия месец е друг фиш в същия
   *       период; без него месечната капачка щеше да се дава цяла на всеки договор.
   *   <li><b>Освободеното, не въведеното.</b> Броено по пълната сума, превишението щеше да изчерпва
   *       тавана и следващата помощ щеше да излезе облагаема, макар таванът да не е стигнат.
   * </ul>
   *
   * @param chain {@code true} за ретро-копие: изключва фишовете на същия договор за същия период
   *     (веригата, която копието коригира). {@code false} за редовен фиш: изключва само него.
   */
  protected BigDecimal query(BgPaySlip slip, BgSocialExpenseCap cap, boolean chain) {
    LocalDate month = monthOf(slip);
    EmploymentContract contract = slip == null ? null : slip.getEmploymentContract();
    if (month == null || contract == null) {
      return BigDecimal.ZERO.setScale(2);
    }
    LocalDate windowFrom =
        BgSocialExpenseCapResolver.ANNUAL.equals(cap.getBgPeriodicity())
            ? LocalDate.of(month.getYear(), 1, 1)
            : month.withDayOfMonth(1);
    TypedQuery<BigDecimal> q =
        JPA.em()
            .createQuery(
                "SELECT COALESCE(SUM(u.bgExempt), 0) FROM BgPaySlipCapUsage u"
                    + " JOIN u.paySlip s JOIN s.employmentContract c JOIN s.period p"
                    + " WHERE u.bgSocialExpenseCap.code = :code"
                    + " AND c.employee = :employee"
                    + " AND c.payCompany = :company"
                    + " AND p.fromDate >= :windowFrom AND p.fromDate <= :from"
                    // ⚠️ „Действащо“ е ЕДНО правило за капачките, Д1 и Д6
                    // (BgEffectivePaySlips): потвърден, не заличаващ, неизместен.
                    + " AND "
                    + BgEffectivePaySlips.effective("s")
                    + " AND s.id <> :self"
                    + (chain ? " AND NOT (c = :contract AND p.fromDate = :from)" : ""),
                BigDecimal.class)
            .setParameter("code", cap.getCode())
            .setParameter("employee", contract.getEmployee())
            .setParameter("company", contract.getPayCompany())
            .setParameter("windowFrom", windowFrom)
            .setParameter("from", month)
            .setParameter(BgEffectivePaySlips.STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
            .setParameter("self", slip.getId() == null ? -1L : slip.getId());
    if (chain) {
      q.setParameter("contract", contract);
    }
    List<BigDecimal> found = q.getResultList();
    return found.isEmpty() || found.get(0) == null
        ? BigDecimal.ZERO.setScale(2)
        : found.get(0).setScale(2, RoundingMode.HALF_UP);
  }

  /** Замразеният ред на фиша за капачката — по кода ѝ, защото редът на капачката е по период. */
  protected static BgPaySlipCapUsage frozenRow(BgPaySlip slip, BgSocialExpenseCap cap) {
    if (slip == null || slip.getBgCapUsageList() == null || cap == null) {
      return null;
    }
    for (BgPaySlipCapUsage row : slip.getBgCapUsageList()) {
      if (row.getBgSocialExpenseCap() != null
          && cap.getCode() != null
          && cap.getCode().equals(row.getBgSocialExpenseCap().getCode())) {
        return row;
      }
    }
    return null;
  }

  /** Капачките, към които сочи поне едно перо „в рамките“ на фиша. Без заявка. */
  protected List<BgSocialExpenseCap> capsOn(BgPaySlip slip) {
    List<BgSocialExpenseCap> out = new ArrayList<>();
    for (BgPaySlipLine line : socialExpenseLines(slip)) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type == null
          || type.getBgSocialExpenseCap() == null
          || Boolean.TRUE.equals(type.getBgIsCapExcess())
          || out.contains(type.getBgSocialExpenseCap())) {
        continue;
      }
      out.add(type.getBgSocialExpenseCap());
    }
    return out;
  }

  private static String missingFrozenRefusal(BgPaySlip slip, BgSocialExpenseCap cap) {
    return "използваното от капачката „"
        + cap.getCode()
        + "“ преди периода не е замразено на "
        + (Boolean.TRUE.equals(slip.getBgIsDeletion()) ? "заличаващото" : "коригиращото")
        + " копие — оригиналът е пресметнат преди замразяването. Прочетено живо, то щеше да следва"
        + " корекции, потвърдени оттогава. Натиснете „Попълни от текущите данни“ и проверете"
        + " числото";
  }

  private static String amount(BigDecimal value) {
    return value == null ? "празно" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * СЪСТОЯНИЕТО НА ТРИТЕ УСЛОВИЯ за периода на този фиш.
   *
   * <p>Двете фирмени идват от {@code BgSocialExpenseException} — липсващ ред значи изпълнени.
   * Третото се смята от предходните фишове.
   */
  public BgSocialExpenseConditions conditionsFor(BgPaySlip slip) {
    LocalDate month = monthOf(slip);
    BgSocialExpenseException flagged =
        month == null
            ? null
            : exceptionFor(companyOf(slip), month.getYear(), month.getMonthValue());
    // ⚠️ ФЛАГОВЕТЕ, НЕ СЪЩЕСТВУВАНЕТО НА РЕДА. Ред със свалени отметки значи „наред“ точно колкото
    // липсващият: той може да съществува заради бележка или защото отметката е била махната.
    // Прочетено „има ли ред → лош месец“, изтритата отметка щеше да продължи да облага.
    boolean debts = flagged != null && flagged.getBgPublicDebts();
    boolean unlicensed = flagged != null && flagged.getBgOperatorUnlicensed();
    return new BgSocialExpenseConditions(debts, salaryBelowAverage(slip), unlicensed);
  }

  /**
   * ЧЛ. 209а АЛ. 1 Т. 1 — под средната ли е ДОГОВОРЕНАТА основна заплата за месеца.
   *
   * <p><b>⚠️ МЕРИ СЕ ВЪРХУ ДОГОВОРЕНОТО, НЕ ВЪРХУ НАЧИСЛЕНОТО, и това е в текста на нормата:</b>
   * „ДОГОВОРЕНОТО основно месечно възнаграждение на лицето в месеца на предоставяне на ваучерите е
   * не по-малко от СРЕДНОМЕСЕЧНОТО ДОГОВОРЕНО основно възнаграждение на лицето за предходните три
   * месеца“. {@code BgPaySlip.bgBaseSalary} е точно това — копие на договорената основна заплата,
   * замразено на фиша, а не пропорционалната част за отработените дни. Сметнато върху начисленото,
   * всеки болничен или неплатен отпуск щеше да свали заплатата под средната и да обложи ваучерите
   * на лицето — с вид на вярна сметка.
   *
   * <p><b>⚠️ ПО-МАЛКО ОТ ТРИ ПРЕДХОДНИ ФИША ЗНАЧИ „УСЛОВИЕТО Е ИЗПЪЛНЕНО“.</b> Ново назначено лице
   * няма три предходни месеца и нормата не му дава средна, с която да се сравни. Прочетено обратно,
   * всеки нов служител щеше да получава обложени ваучери в първите си три месеца — резултат, който
   * текстът не дава. Същото оптимистично подразбиране като при липсващия ред с изключението.
   */
  protected boolean salaryBelowAverage(BgPaySlip slip) {
    if (slip == null || slip.getBgBaseSalary().signum() <= 0) {
      return false;
    }
    List<BigDecimal> previous = previousContractedSalaries(slip);
    if (previous.size() < MONTHS_IN_AVERAGE) {
      return false;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal one : previous) {
      sum = sum.add(one);
    }
    BigDecimal average =
        sum.divide(
            BigDecimal.valueOf(MONTHS_IN_AVERAGE),
            BgSocialExpenseCapResolver.WORKING_SCALE,
            RoundingMode.HALF_UP);
    return slip.getBgBaseSalary().compareTo(average) < 0;
  }

  /**
   * ДОГОВОРЕНИТЕ основни заплати от ПРЕДХОДНИТЕ ТРИ фиша по СЪЩИЯ трудов договор, най-новите първи.
   *
   * <p>⚠️ По ДОГОВОРА, не по лицето: второ правоотношение има своя договорена заплата и своя
   * средна. Смесени, ваучерите по единия договор щяха да се преценяват по заплатата на другия.
   *
   * <p>⚠️ Заличените фишове са извън списъка: заличаването значи „този фиш не е бил“, тоест месецът
   * му няма договорена заплата, с която да се смята средно. Отказаните също — те не са издавани.
   */
  protected List<BigDecimal> previousContractedSalaries(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    if (contract == null || slip.getPeriod() == null || slip.getPeriod().getFromDate() == null) {
      return new ArrayList<>();
    }
    return JPA.em()
        .createQuery(
            "SELECT s.bgBaseSalary FROM BgPaySlip s"
                + " WHERE s.employmentContract = :contract"
                + " AND s.period.fromDate < :from"
                // ⚠️ „IS NULL OR = FALSE“, НЕ голото „IS FALSE“. Генерираният getter за Boolean
                // връща FALSE и за NULL, а заварените фишове носят NULL: Property#set елидира
                // false, когато старата стойност вече е false (измереният капан на
                // bgIsSeafarer). Голото „IS FALSE“ би изхвърлило точно фишовете, които СА редовни.
                + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                + " AND (s.statusSelect IS NULL OR s.statusSelect <> :cancelled)"
                + " AND s.bgBaseSalary > 0"
                + " ORDER BY s.period.fromDate DESC",
            BigDecimal.class)
        .setParameter("contract", contract)
        .setParameter("from", slip.getPeriod().getFromDate())
        .setParameter("cancelled", BgPaySlipResolver.CANCELLED)
        .setMaxResults(MONTHS_IN_AVERAGE)
        .getResultList();
  }

  /** Редът с изключението за (фирма, година, месец), или {@code null} — тоест „всичко е наред“. */
  protected BgSocialExpenseException exceptionFor(Company company, int year, int month) {
    if (company == null) {
      return null;
    }
    List<BgSocialExpenseException> found =
        JPA.em()
            .createQuery(
                "SELECT e FROM BgSocialExpenseException e"
                    + " WHERE e.company = :company AND e.bgYear = :year AND e.bgMonth = :month",
                BgSocialExpenseException.class)
            .setParameter("company", company)
            .setParameter("year", year)
            .setParameter("month", month)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Перата от 2xx на този фиш. ЧИСТО — без нито една заявка. */
  protected List<BgPaySlipLine> socialExpenseLines(BgPaySlip slip) {
    List<BgPaySlipLine> out = new ArrayList<>();
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return out;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgSocialExpenseCapResolver.isSocialExpenseLine(line)) {
        out.add(line);
      }
    }
    return out;
  }

  /**
   * КАЛЕНДАРНИЯТ МЕСЕЦ на фиша — от началото на периода.
   *
   * <p>⚠️ От периода, НЕ от {@code LocalDate.now()}. Условията на чл. 208 и чл. 209а са „към края
   * на месеца, през който са начислени разходите“; питани за днешния месец, коригиращ фиш за минал
   * период щеше да получи чуждото състояние.
   */
  private static LocalDate monthOf(BgPaySlip slip) {
    return slip == null || slip.getPeriod() == null ? null : slip.getPeriod().getFromDate();
  }

  /**
   * ФИРМАТА, чиито публични задължения важат — {@code payCompany} на договора.
   *
   * <p>⚠️ Същият прочит като {@code BgPaySlipService#companyOf}, и нарочно същият: данъчно
   * задълженото лице по чл. 207 ал. 2 ЗКПО е РАБОТОДАТЕЛЯТ, тоест тази, която плаща — не тази, в
   * чиито регистри лицето стои. Два различни прочита на едно и също понятие биха дали два отговора
   * за един месец.
   */
  private static Company companyOf(BgPaySlip slip) {
    return slip == null || slip.getEmploymentContract() == null
        ? null
        : slip.getEmploymentContract().getPayCompany();
  }
}
