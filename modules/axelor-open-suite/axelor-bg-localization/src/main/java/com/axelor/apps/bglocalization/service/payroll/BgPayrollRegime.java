package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * РЕЖИМЪТ НА ОСИГУРЯВАНЕ НА ЕДИН ФИШ — всичко, което вноските и данъкът четат за ЛИЦЕТО и
 * ПРАВООТНОШЕНИЕТО, а не за месеца.
 *
 * <p><b>⚠️ ЕДИН ОБЕКТ, ДВА ИЗТОЧНИКА, ЕДИН ПЪТ.</b> {@link BgPayrollInputBuilder} пълни входа на
 * вноските ВИНАГИ от такъв обект: при пресмятане на редовен фиш в чернова той се извлича живо от
 * договора, служителя, фирмата и регистрацията ({@code BgPayrollInputBuilder#liveRegime}) и се
 * замразява на фиша; навсякъде другаде — потвърждаване, копие, декларации — се чете ОТ ФИША ({@link
 * #of}). Така живото и замразеното не могат да се разминат по това КАК се тълкуват.
 *
 * <p><b>⚠️ МОРСКОТО ЛИЦЕ И ЧЛ. 41а НЕ СА ТУК</b> — те са свойства на вида осигурен в каталога за
 * датата на периода и се извеждат от {@link #insuredTypeCode}. Отделно поле щеше да може да се
 * размине с вида, особено в копие, където видът е редактируем.
 */
public final class BgPayrollRegime {

  /** Произходът на замразения режим — {@code bg.payslip.regime.source.select}. */
  public static final String CALCULATED = "CALCULATED";

  public static final String COPIED = "COPIED";
  public static final String FILLED = "FILLED";

  public final String insuredTypeCode;
  public final String laborCategory;
  public final boolean bornAfter1959;
  public final String pensionChoice;
  public final String solScope;
  public final String teacherScope;
  public final String nkpdCode;
  public final String nkidCode;
  public final String tzpbDivision;
  public final String workTimeMode;
  public final BigDecimal hoursPerDay;
  public final BigDecimal daysPerWeek;
  public final BigDecimal expenseRate;
  public final boolean insuredOnOtherGround;
  public final BigDecimal otherPayerIncome;
  public final BigDecimal selfInsuredIncome;
  public final boolean disabilityRelief;
  public final LocalDate disabilityValidUntil;
  public final String minIncomeRow;
  public final BigDecimal minIncomeOverride;

  @SuppressWarnings("java:S107")
  public BgPayrollRegime(
      String insuredTypeCode,
      String laborCategory,
      boolean bornAfter1959,
      String pensionChoice,
      String solScope,
      String teacherScope,
      String nkpdCode,
      String nkidCode,
      String tzpbDivision,
      String workTimeMode,
      BigDecimal hoursPerDay,
      BigDecimal daysPerWeek,
      BigDecimal expenseRate,
      boolean insuredOnOtherGround,
      BigDecimal otherPayerIncome,
      BigDecimal selfInsuredIncome,
      boolean disabilityRelief,
      LocalDate disabilityValidUntil,
      String minIncomeRow,
      BigDecimal minIncomeOverride) {
    this.insuredTypeCode = blankToNull(insuredTypeCode);
    this.laborCategory = blankToNull(laborCategory);
    this.bornAfter1959 = bornAfter1959;
    this.pensionChoice = blankToNull(pensionChoice);
    this.solScope = blankToNull(solScope);
    this.teacherScope = blankToNull(teacherScope);
    this.nkpdCode = blankToNull(nkpdCode);
    this.nkidCode = blankToNull(nkidCode);
    this.tzpbDivision = blankToNull(tzpbDivision);
    this.workTimeMode = blankToNull(workTimeMode);
    this.hoursPerDay = hoursPerDay;
    this.daysPerWeek = daysPerWeek;
    this.expenseRate = expenseRate;
    this.insuredOnOtherGround = insuredOnOtherGround;
    this.otherPayerIncome = otherPayerIncome;
    this.selfInsuredIncome = selfInsuredIncome;
    this.disabilityRelief = disabilityRelief;
    this.disabilityValidUntil = disabilityValidUntil;
    this.minIncomeRow = blankToNull(minIncomeRow);
    this.minIncomeOverride = minIncomeOverride;
  }

  /** Замразен ли е режимът на този фиш. Празната дата значи „фиш отпреди замразяването“. */
  public static boolean isFrozen(BgPaySlip slip) {
    return slip != null && slip.getBgRegimeFrozenAt() != null;
  }

  /** Замразеният режим на фиша, или {@code null}, ако фишът е отпреди замразяването. */
  public static BgPayrollRegime of(BgPaySlip slip) {
    if (!isFrozen(slip)) {
      return null;
    }
    return new BgPayrollRegime(
        slip.getBgRegimeInsuredTypeCode(),
        slip.getBgRegimeLaborCategory(),
        Boolean.TRUE.equals(slip.getBgRegimeBornAfter1959()),
        slip.getBgRegimePensionChoice(),
        slip.getBgRegimeSolScope(),
        slip.getBgRegimeTeacherScope(),
        slip.getBgRegimeNkpdCode(),
        slip.getBgRegimeNkidCode(),
        slip.getBgRegimeTzpbDivision(),
        slip.getBgRegimeWorkTimeMode(),
        positiveOrNull(slip.getBgRegimeHoursPerDay()),
        positiveOrNull(slip.getBgRegimeDaysPerWeek()),
        decimalOrNull(slip.getBgRegimeExpenseRate()),
        Boolean.TRUE.equals(slip.getBgRegimeInsuredOnOtherGround()),
        positiveOrNull(slip.getBgRegimeOtherPayerIncome()),
        // ⚠️ НЕ positiveOrNull: живият прочит подава getter-а на регистрацията такъв, какъвто е
        // (0 при празно), и замразеният трябва да е същото число, не друго твърдение.
        slip.getBgRegimeSelfInsuredIncome(),
        Boolean.TRUE.equals(slip.getBgRegimeDisabilityRelief()),
        slip.getBgRegimeDisabilityValidUntil(),
        slip.getBgRegimeMinIncomeRow(),
        // ⚠️ НЕ positiveOrNull, по довода при СОЛ: живото подава getter-а на служителя такъв,
        // какъвто е (0 при празно), а BgMinInsuranceIncomeService прилага само положителен МОД.
        slip.getBgRegimeMinIncomeOverride());
  }

  /** Записва режима върху фиша и го обявява за замразен. */
  public void writeTo(BgPaySlip slip, String source, LocalDateTime at) {
    slip.setBgRegimeInsuredTypeCode(insuredTypeCode);
    slip.setBgRegimeLaborCategory(laborCategory);
    slip.setBgRegimeBornAfter1959(bornAfter1959);
    slip.setBgRegimePensionChoice(pensionChoice);
    slip.setBgRegimeSolScope(solScope);
    slip.setBgRegimeTeacherScope(teacherScope);
    slip.setBgRegimeNkpdCode(nkpdCode);
    slip.setBgRegimeNkidCode(nkidCode);
    slip.setBgRegimeTzpbDivision(tzpbDivision);
    slip.setBgRegimeWorkTimeMode(workTimeMode);
    slip.setBgRegimeHoursPerDay(hoursPerDay);
    slip.setBgRegimeDaysPerWeek(daysPerWeek);
    slip.setBgRegimeExpenseRate(expenseRate == null ? null : expenseRate.toPlainString());
    slip.setBgRegimeInsuredOnOtherGround(insuredOnOtherGround);
    slip.setBgRegimeOtherPayerIncome(otherPayerIncome);
    slip.setBgRegimeSelfInsuredIncome(selfInsuredIncome);
    slip.setBgRegimeDisabilityRelief(disabilityRelief);
    slip.setBgRegimeDisabilityValidUntil(disabilityValidUntil);
    slip.setBgRegimeMinIncomeRow(minIncomeRow);
    slip.setBgRegimeMinIncomeOverride(minIncomeOverride);
    slip.setBgRegimeSource(source);
    slip.setBgRegimeFrozenAt(at);
  }

  /** Пренася замразения режим от оригинала в копието — без нито едно живо четене. */
  public static void copy(BgPaySlip from, BgPaySlip to) {
    for (Field f : FIELDS) {
      f.copy.accept(from, to);
    }
    to.setBgRegimeFrozenAt(from.getBgRegimeFrozenAt());
    to.setBgRegimeSource(isFrozen(from) ? COPIED : from.getBgRegimeSource());
  }

  // ═══ КАТАЛОГЪТ НА ПОЛЕТАТА — за одита, обобщението и изброяването на липсващото ════════════

  /** Едно поле на режима: името в домейна, думите в отказа и одита, стойността като текст. */
  public static final class Field {
    public final String name;
    public final String title;
    public final boolean forEmployee;
    public final boolean forSelfInsured;
    final Function<BgPaySlip, String> text;
    final java.util.function.BiConsumer<BgPaySlip, BgPaySlip> copy;

    Field(
        String name,
        String title,
        boolean forEmployee,
        boolean forSelfInsured,
        Function<BgPaySlip, String> text,
        java.util.function.BiConsumer<BgPaySlip, BgPaySlip> copy) {
      this.name = name;
      this.title = title;
      this.forEmployee = forEmployee;
      this.forSelfInsured = forSelfInsured;
      this.text = text;
      this.copy = copy;
    }

    /** Стойността на полето във фиша, така както я чете човек. */
    public String textOf(BgPaySlip slip) {
      return text.apply(slip);
    }
  }

  public static final List<Field> FIELDS =
      Collections.unmodifiableList(
          Arrays.asList(
              new Field(
                  "bgRegimeInsuredTypeCode",
                  "вид осигурен",
                  true,
                  true,
                  s -> str(s.getBgRegimeInsuredTypeCode()),
                  (a, b) -> b.setBgRegimeInsuredTypeCode(a.getBgRegimeInsuredTypeCode())),
              new Field(
                  "bgRegimeLaborCategory",
                  "категория труд",
                  true,
                  false,
                  s -> str(s.getBgRegimeLaborCategory()),
                  (a, b) -> b.setBgRegimeLaborCategory(a.getBgRegimeLaborCategory())),
              new Field(
                  "bgRegimeBornAfter1959",
                  "роден след 1959",
                  true,
                  true,
                  s -> yesNo(s.getBgRegimeBornAfter1959()),
                  (a, b) -> b.setBgRegimeBornAfter1959(a.getBgRegimeBornAfter1959())),
              new Field(
                  "bgRegimePensionChoice",
                  "избор за ДЗПО",
                  true,
                  true,
                  s -> str(s.getBgRegimePensionChoice()),
                  (a, b) -> b.setBgRegimePensionChoice(a.getBgRegimePensionChoice())),
              new Field(
                  "bgRegimeSolScope",
                  "обхват на осигуряването",
                  true,
                  true,
                  s -> str(s.getBgRegimeSolScope()),
                  (a, b) -> b.setBgRegimeSolScope(a.getBgRegimeSolScope())),
              new Field(
                  "bgRegimeTeacherScope",
                  "учителство",
                  true,
                  false,
                  s -> str(s.getBgRegimeTeacherScope()),
                  (a, b) -> b.setBgRegimeTeacherScope(a.getBgRegimeTeacherScope())),
              new Field(
                  "bgRegimeNkpdCode",
                  "НКПД",
                  true,
                  false,
                  s -> str(s.getBgRegimeNkpdCode()),
                  (a, b) -> b.setBgRegimeNkpdCode(a.getBgRegimeNkpdCode())),
              new Field(
                  "bgRegimeNkidCode",
                  "НКИД",
                  true,
                  false,
                  s -> str(s.getBgRegimeNkidCode()),
                  (a, b) -> b.setBgRegimeNkidCode(a.getBgRegimeNkidCode())),
              new Field(
                  "bgRegimeTzpbDivision",
                  "раздел по НКИД за ТЗПБ",
                  true,
                  false,
                  s -> str(s.getBgRegimeTzpbDivision()),
                  (a, b) -> b.setBgRegimeTzpbDivision(a.getBgRegimeTzpbDivision())),
              new Field(
                  "bgRegimeMinIncomeRow",
                  "МОД ред на фирмата",
                  true,
                  false,
                  s -> str(s.getBgRegimeMinIncomeRow()),
                  (a, b) -> b.setBgRegimeMinIncomeRow(a.getBgRegimeMinIncomeRow())),
              new Field(
                  "bgRegimeWorkTimeMode",
                  "режим на работното време",
                  true,
                  false,
                  s -> str(s.getBgRegimeWorkTimeMode()),
                  (a, b) -> b.setBgRegimeWorkTimeMode(a.getBgRegimeWorkTimeMode())),
              new Field(
                  "bgRegimeHoursPerDay",
                  "часове на ден",
                  true,
                  false,
                  s -> num(s.getBgRegimeHoursPerDay()),
                  (a, b) -> b.setBgRegimeHoursPerDay(a.getBgRegimeHoursPerDay())),
              new Field(
                  "bgRegimeDaysPerWeek",
                  "дни в седмицата",
                  true,
                  false,
                  s -> num(s.getBgRegimeDaysPerWeek()),
                  (a, b) -> b.setBgRegimeDaysPerWeek(a.getBgRegimeDaysPerWeek())),
              new Field(
                  "bgRegimeExpenseRate",
                  "нормативни разходи",
                  true,
                  false,
                  s -> str(s.getBgRegimeExpenseRate()),
                  (a, b) -> b.setBgRegimeExpenseRate(a.getBgRegimeExpenseRate())),
              new Field(
                  "bgRegimeInsuredOnOtherGround",
                  "осигурен на друго основание",
                  true,
                  false,
                  s -> yesNo(s.getBgRegimeInsuredOnOtherGround()),
                  (a, b) -> b.setBgRegimeInsuredOnOtherGround(a.getBgRegimeInsuredOnOtherGround())),
              new Field(
                  "bgRegimeOtherPayerIncome",
                  "стоящ доход при друг платец",
                  true,
                  false,
                  s -> num(s.getBgRegimeOtherPayerIncome()),
                  (a, b) -> b.setBgRegimeOtherPayerIncome(a.getBgRegimeOtherPayerIncome())),
              new Field(
                  "bgRegimeMinIncomeOverride",
                  "ръчен МОД на лицето",
                  true,
                  true,
                  s -> num(s.getBgRegimeMinIncomeOverride()),
                  (a, b) -> b.setBgRegimeMinIncomeOverride(a.getBgRegimeMinIncomeOverride())),
              new Field(
                  "bgRegimeSelfInsuredIncome",
                  "избран осигурителен доход (СОЛ)",
                  false,
                  true,
                  s -> num(s.getBgRegimeSelfInsuredIncome()),
                  (a, b) -> b.setBgRegimeSelfInsuredIncome(a.getBgRegimeSelfInsuredIncome())),
              new Field(
                  "bgRegimeDisabilityRelief",
                  "облекчение по ТЕЛК",
                  true,
                  true,
                  s -> yesNo(s.getBgRegimeDisabilityRelief()),
                  (a, b) -> b.setBgRegimeDisabilityRelief(a.getBgRegimeDisabilityRelief())),
              new Field(
                  "bgRegimeDisabilityValidUntil",
                  "ТЕЛК валидно до",
                  true,
                  true,
                  s -> date(s.getBgRegimeDisabilityValidUntil()),
                  (a, b) ->
                      b.setBgRegimeDisabilityValidUntil(a.getBgRegimeDisabilityValidUntil()))));

  /** Полетата, които се отнасят за този вид фиш — договор или регистрация на СОЛ. */
  public static List<Field> fieldsFor(BgPaySlip slip) {
    boolean selfInsured = slip != null && slip.getBgSelfInsuredRegistration() != null;
    List<Field> out = new ArrayList<>();
    for (Field f : FIELDS) {
      if (selfInsured ? f.forSelfInsured : f.forEmployee) {
        out.add(f);
      }
    }
    return out;
  }

  /**
   * ОТКАЗЪТ ЗА ЛИПСВАЩ РЕЖИМ, или {@code null}, ако режимът е замразен. Изброява полетата поименно
   * — точно тези, които операторът трябва да провери след „Попълни от текущите данни“.
   */
  public static String missingRefusal(BgPaySlip slip, String what) {
    if (isFrozen(slip)) {
      return null;
    }
    List<String> titles = new ArrayList<>();
    for (Field f : fieldsFor(slip)) {
      titles.add(f.title);
    }
    return "Режимът на осигуряване на "
        + what
        + " не е замразен — фишът е отпреди замразяването, а живите данни на договора и"
        + " служителя не се четат тихо за минал месец. Липсват: "
        + String.join(", ", titles)
        + ". Натиснете „Попълни от текущите данни“, проверете стойностите и пресметнете отново.";
  }

  /**
   * ОТКАЗЪТ ЗА РЕДОВЕН ФИШ БЕЗ РЕЖИМ, или {@code null}. Такъв фиш е или непресмятан, или пресметнат
   * преди замразяването — и в двата случая потвърждаването щеше да чете режима от днешния договор,
   * а не от пресмятането, с което операторът е видял сумите.
   *
   * <p>⚠️ Фиш без носител (нито договор, нито регистрация) мълчи тук: режим няма откъде да се
   * замрази, вход не се сглобява, тоест и живо четене няма — а отказът „няма носител“ при вноските
   * назовава истинската причина.
   */
  public static String unfrozenRefusal(BgPaySlip slip) {
    if (isFrozen(slip)
        || slip.getEmploymentContract() == null && slip.getBgSelfInsuredRegistration() == null) {
      return null;
    }
    return "Режимът на осигуряване на фиша не е замразен — фишът не е пресметнат след"
        + " замразяването. Натиснете „Преизчисли“: пресмятането замразява режима от данните на"
        + " договора и служителя, а потвърждаването чете само замразеното.";
  }

  /**
   * ОТКАЗЪТ ЗА РЕЖИМ, СМЕНЕН СЛЕД ПРЕСМЯТАНЕТО — за РЕДОВНА чернова, или {@code null}.
   *
   * <p>Замразеният режим се сравнява поле по поле с прочетения сега ({@code live}). Различат ли се,
   * сумите на фиша са сметнати по данни, които договорът или служителят вече не твърдят, и тихото
   * потвърждаване със стария режим би декларирало месеца по отменено твърдение. Отказът назовава
   * всяко сменено поле — старо → ново, — а „Преизчисли“ замразява новото.
   *
   * <p><b>⚠️ САМО ЗА РЕДОВЕН ФИШ.</b> В копието режимът е замразен НАРОЧНО — коригира се ОНЗИ месец
   * — и се поправя в самото копие, с одит. Сравнен с днешния договор, той би отказвал точно
   * корекциите, заради които съществува.
   *
   * <p>⚠️ Сравнява се ТЕКСТЪТ на полето ({@link Field#textOf}), тоест същото, което отказът
   * показва: „8,00“ и „8“ часа не са промяна, а празно и „—“ са едно и също.
   */
  public static String driftRefusal(BgPaySlip slip, BgPayrollRegime live) {
    if (!isFrozen(slip) || live == null) {
      return null;
    }
    BgPaySlip now = new BgPaySlip();
    live.writeTo(now, CALCULATED, null);
    List<String> changes = new ArrayList<>();
    for (Field f : fieldsFor(slip)) {
      String was = f.textOf(slip);
      String is = f.textOf(now);
      if (!was.equals(is)) {
        changes.add(f.title + " " + was + " → " + is);
      }
    }
    if (changes.isEmpty()) {
      return null;
    }
    return "Режимът е сменен след пресмятането: "
        + String.join("; ", changes)
        + ". Натиснете „Преизчисли“ и потвърдете отново.";
  }

  // ═══ ДРЕБНИТЕ ПРЕВОДИ ════════════════════════════════════════════════════════════════════

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  static String str(String value) {
    return value == null || value.trim().isEmpty() ? "—" : value.trim();
  }

  static String yesNo(Boolean value) {
    return Boolean.TRUE.equals(value) ? "да" : "не";
  }

  static String num(BigDecimal value) {
    if (value == null || value.signum() == 0) {
      return "—";
    }
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static String date(LocalDate value) {
    return value == null ? "—" : value.format(DATE);
  }

  private static String blankToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static BigDecimal decimalOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
  }

  private static BigDecimal positiveOrNull(BigDecimal value) {
    return value == null || value.signum() <= 0 ? null : value;
  }
}
