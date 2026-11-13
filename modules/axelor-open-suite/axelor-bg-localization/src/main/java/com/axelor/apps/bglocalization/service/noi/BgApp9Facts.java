package com.axelor.apps.bglocalization.service.noi;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * ВХОДЪТ НА {@link BgApp9ObstoyatelstvaResolver} — всичко, което обстоятелствата по Приложение № 9
 * искат, събрано веднъж и в плоски стойности.
 *
 * <p><b>⚠️ ЗАЩО ПЛОСЪК ЗАПИС, А НЕ ENTITY-ТА.</b> Модулът НЯМА mock-подпора в тестовете, тоест
 * правило, което иска свидетел с числа, трябва да живее в чиста функция над стойности — иначе
 * свидетелят му се пише само структурно. Резолверът получава този запис и НЕ пипа нито базата, нито
 * Guice; заявките са в {@link BgApp9DeclarationService}. Оттам всяко правило по-долу е закованимо с
 * тест без среда.
 *
 * <p><b>Кое поле откъде идва.</b> Трите източника са ЛИСТЪТ (Ф0 — BgSickLeave и неговите редове по
 * т. 9), ДОГОВОРЪТ (EmploymentContract) и ЛИЦЕТО (Employee). Четвърти блок — броените при
 * осигурителя — не е поле на нито един запис: той е резултат от заявка и затова стои като готово
 * число, а не като списък от договори, който резолверът пак да обхожда.
 *
 * <p>⚠️ Обвивките са {@code Integer}/{@code Boolean}, а не {@code int}/{@code boolean}, точно
 * където разликата между „нула“ и „не е подадено“ е съществена. Точно това различаване е причината
 * Employee да носи ДВЕ колони за стажа ({@code bgInsuranceServiceMonths} + {@code
 * bgInsuranceServiceDeclared}): генерираният getter за Integer връща 0 и за NULL колона. Тук флагът
 * пристига отделно и стойността се чете само през него.
 */
public final class BgApp9Facts {

  // ═══ ЛИСТЪТ (Ф0) ═════════════════════════════════════════════════════════

  /**
   * ДЕНЯТ НА НАСТЪПВАНЕ на риска — {@code BgSickLeave.bgIncapacityStartDate}. Котвата на т. 1, 2,
   * 3, 4 и 5: и петте питат „към деня на настъпването“.
   *
   * <p><b>⚠️ НЕ Е {@link #sickFrom}, И НЕ СЕ ПОДМЕНЯ С НЕГО.</b> Ф0 въведе двете нарочно разделени
   * и каза защо: „днес двете съвпадат при почти всеки лист — и точно затова изведеното от fromDate
   * щеше да изглежда вярно, докато не срещне първия случай, в който не е“. Празна тук значи ОТКАЗ,
   * а не тихо заемане от fromDate.
   */
  public final LocalDate incapacityStartDate;

  /** Началото на отпуска по листа — {@code BgSickLeave.fromDate}. */
  public final LocalDate sickFrom;

  /** Краят на отпуска по листа — {@code BgSickLeave.toDate}. */
  public final LocalDate sickTo;

  /**
   * Категорията на вида отсъствие — {@code BgAbsenceType.category}, ос {@code
   * bg.absence.category.select}. Разграничителят между т. 2 и т. 3 и гейтът на т. 13–23.
   */
  public final Integer absenceCategory;

  /** Стабилният код на вида отсъствие — влиза в свидетеля, за да се вижда КОЙ вид е решил. */
  public final String absenceTypeCode;

  /** Наименованието на вида отсъствие — за свидетеля. */
  public final String absenceTypeName;

  /** Т. 25 — {@code BgSickLeave.bgEmployerReceivedNo}. НЕ се копира в удостоверението. */
  public final String employerReceivedNo;

  /** Т. 25 — {@code BgSickLeave.bgEmployerReceivedDate}. */
  public final LocalDate employerReceivedDate;

  /**
   * Броят редове по т. 9 върху листа — {@code BgSickLeave.bgPeriodLineList.size()}.
   *
   * <p>⚠️ САМО ЗА СВИДЕТЕЛЯ. Резолверът НЕ преизчислява т. 9 и не я записва никъде: редовете са на
   * Ф0 и се пишат при пресмятането на фиша. Тук числото служи, за да каже присъдата какво е видяла.
   */
  public final int periodLineCount;

  /** Сборът на работните дни по редовете от т. 9. Пак САМО за свидетеля. */
  public final int periodLineWorkDays;

  // ═══ ДОГОВОРЪТ ═══════════════════════════════════════════════════════════

  /**
   * {@code EmploymentContract.bgContractType}, ос {@code bg.employment.contract.type.select}.
   *
   * <p>⚠️ РЕЗОЛВЕРЪТ Е ПЪРВИЯТ МУ ЧИТАТЕЛ В ПРОИЗВОДСТВЕНИЯ КОД, и това е обявено в самото поле:
   * „ОПИСАТЕЛНО ПОЛЕ. Нула реда производствен код го четат.“ Оттам заварените договори може да го
   * носят празен — и тогава т. 1.1 ОТКАЗВА с причина, вместо да отгатне безсрочност.
   */
  public final String contractType;

  /** Началото на договора. Без него т. 1 не може да каже дали лицето е осигурено към деня. */
  public final LocalDate contractStartDate;

  /**
   * УГОВОРЕНИЯТ край — {@code EmploymentContract.endDate}. Пълни датата в т. 1.1.
   *
   * <p>⚠️ Различен от {@link #terminationDate} и това е цялата причина двете да съществуват (виж
   * коментара на {@code bgTerminationDate}): при срочен договор endDate се попълва ПРИ ПОДПИСВАНЕТО
   * и значи изтичане на срока, а при безсрочен се попълва едва при прекратяване.
   */
  public final LocalDate contractEndDate;

  /** ДЕЙСТВИТЕЛНОТО прекратяване — {@code EmploymentContract.bgTerminationDate}. Пълни т. 7. */
  public final LocalDate terminationDate;

  /** {@code EmploymentContract.bgInsuredTypeCode} — стойността на т. 4. */
  public final String insuredTypeCode;

  /** {@code EmploymentContract.bgInsuredOnOtherGround}. ⚠️ НЕ влиза в т. 5 — виж резолвера. */
  public final boolean insuredOnOtherGround;

  // ═══ ЛИЦЕТО ══════════════════════════════════════════════════════════════

  /** {@code Employee.bgIsSelfInsured} — гейтът на т. 1.2 и на потискането на т. 25. */
  public final boolean selfInsured;

  /** {@code Employee.bgInsuranceServiceDeclared} — вратата към {@link #insuranceServiceMonths}. */
  public final boolean insuranceServiceDeclared;

  /** {@code Employee.bgInsuranceServiceMonths}. Чете се САМО когато флагът е вдигнат. */
  public final Integer insuranceServiceMonths;

  /**
   * Датата на раждане — нужна САМО за изключението по чл. 40 ал. 1 изр. 2 КСО.
   *
   * <p><b>⚠️ БЕЗ НЕЯ Т. 2 НЕ Е „НЕ“, А НЕИЗВЕСТНА.</b> Чл. 40 ал. 1 КСО завършва с „Изискването за
   * 6 месеца осигурителен стаж НЕ СЕ ОТНАСЯ за лицата, ненавършили 18-годишна възраст.“ Тоест при
   * стаж под шест месеца отговорът зависи от възрастта, и празна дата на раждане прави точката
   * неопределима, а не отрицателна. Същият избор и по същия довод като в {@code
   * BgSickLeaveResolver#eligibility}, който при стаж под прага и липсваща дата ОТКАЗВА, вместо да
   * върне нула дни.
   */
  public final LocalDate birthDate;

  /**
   * ЗАМРАЗЕНАТА ПРИСЪДА ЗА ПРАВОТО на листа ({@code BgSickLeave.bgBenefitVerdict}): {@code
   * HAS_RIGHT} / {@code NO_RIGHT}, или {@code null}. Когато е подадена, т. 2 и т. 3 я ЧЕТАТ и не
   * смятат нищо от {@link #insuranceServiceMonths} — една присъда за фиша, Д1 и удостоверението.
   */
  public final String benefitVerdict;

  /** Обяснението на присъдата — {@code BgSickLeave.bgBenefitVerdictWhy}. */
  public final String benefitVerdictWhy;

  /**
   * Защо присъда НЯМА — отказът на {@code BgSickBenefitVerdictService}. Тогава т. 2/т. 3 се
   * отказват със същия текст, а не се смятат по друг път.
   */
  public final String benefitVerdictRefusal;

  /** {@code Employee.bgBenefitIban} — стойността на т. 26. */
  public final String benefitIban;

  /** Броят въведени деца на лицето. Гейтът на т. 13: едно дете се извежда, две искат избор. */
  public final int childCount;

  /** Идентификаторът на детето, когато е ТОЧНО ЕДНО. {@code null} при нула и при повече. */
  public final Long soleChildId;

  /** Името на единственото дете — за свидетеля. */
  public final String soleChildName;

  // ═══ БРОЕНИТЕ ПРИ ОСИГУРИТЕЛЯ (същия ЕИК) ════════════════════════════════

  /**
   * Т. 5 — броят активни правоотношения при {@code payCompany} на този договор към деня на
   * настъпването. {@code null} значи „не е броено“ и води до ОТКАЗ, а не до 1.
   */
  public final Integer relationshipCount;

  /**
   * Т. 6 — редът на ТОЗИ договор сред преброените по т. 5, започвайки от 1.
   *
   * <p>⚠️ Не е брояч по лице. Указанието иска номер „в зависимост от броя на
   * правоотношенията/основанията, посочени в т. 5“, тоест стойност в 1..{@link #relationshipCount}.
   */
  public final Integer relationshipOrdinal;

  /**
   * Има ли по-ранно ПОДАДЕНО удостоверение по Приложение № 9 за това лице. Гейтът на т. 26. {@code
   * null} значи „не е проверявано“.
   */
  public final Boolean earlierApp9Submitted;

  // ═══ ПРЕДЛОЖЕНИЯТА ═══════════════════════════════════════════════════════

  /**
   * Валидираните заявки за ПЛАТЕН отпуск, пресичащи периода по листа, като готови за четене
   * интервали. Влизат в ПРЕДЛОЖЕНИЕТО по т. 12 и никога в стойност.
   */
  public final List<String> overlappingPaidLeave;

  /**
   * Валидираните заявки за отпуск в периода, които НЕ СЕ ЗАЧИТАТ за осигурителен стаж. Влизат в
   * предложението по т. 8 — указанието изброява „неплатен отпуск, който не се зачита за
   * осигурителен стаж“ като повод осигуряването да е прекъснато.
   */
  public final List<String> leaveNotCountedAsInsuranceService;

  private BgApp9Facts(Builder b) {
    this.incapacityStartDate = b.incapacityStartDate;
    this.sickFrom = b.sickFrom;
    this.sickTo = b.sickTo;
    this.absenceCategory = b.absenceCategory;
    this.absenceTypeCode = b.absenceTypeCode;
    this.absenceTypeName = b.absenceTypeName;
    this.employerReceivedNo = b.employerReceivedNo;
    this.employerReceivedDate = b.employerReceivedDate;
    this.periodLineCount = b.periodLineCount;
    this.periodLineWorkDays = b.periodLineWorkDays;
    this.contractType = b.contractType;
    this.contractStartDate = b.contractStartDate;
    this.contractEndDate = b.contractEndDate;
    this.terminationDate = b.terminationDate;
    this.insuredTypeCode = b.insuredTypeCode;
    this.insuredOnOtherGround = b.insuredOnOtherGround;
    this.selfInsured = b.selfInsured;
    this.insuranceServiceDeclared = b.insuranceServiceDeclared;
    this.insuranceServiceMonths = b.insuranceServiceMonths;
    this.birthDate = b.birthDate;
    this.benefitVerdict = b.benefitVerdict;
    this.benefitVerdictWhy = b.benefitVerdictWhy;
    this.benefitVerdictRefusal = b.benefitVerdictRefusal;
    this.benefitIban = b.benefitIban;
    this.childCount = b.childCount;
    this.soleChildId = b.soleChildId;
    this.soleChildName = b.soleChildName;
    this.relationshipCount = b.relationshipCount;
    this.relationshipOrdinal = b.relationshipOrdinal;
    this.earlierApp9Submitted = b.earlierApp9Submitted;
    this.overlappingPaidLeave =
        b.overlappingPaidLeave == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(b.overlappingPaidLeave);
    this.leaveNotCountedAsInsuranceService =
        b.leaveNotCountedAsInsuranceService == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(b.leaveNotCountedAsInsuranceService);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Сглобителят. ⚠️ Двайсет и седем стойности НЕ се подават като позиционни аргументи: в
   * конструктор с толкова полета разменени две дати от един тип не пада никъде и свидетелският тест
   * би заковал разменените. Освен това тестът задава по 3–4 стойности на случай, а останалите
   * трябва да са явно празни.
   */
  public static final class Builder {
    private LocalDate incapacityStartDate;
    private LocalDate sickFrom;
    private LocalDate sickTo;
    private Integer absenceCategory;
    private String absenceTypeCode;
    private String absenceTypeName;
    private String employerReceivedNo;
    private LocalDate employerReceivedDate;
    private int periodLineCount;
    private int periodLineWorkDays;
    private String contractType;
    private LocalDate contractStartDate;
    private LocalDate contractEndDate;
    private LocalDate terminationDate;
    private String insuredTypeCode;
    private boolean insuredOnOtherGround;
    private boolean selfInsured;
    private boolean insuranceServiceDeclared;
    private Integer insuranceServiceMonths;
    private LocalDate birthDate;
    private String benefitVerdict;
    private String benefitVerdictWhy;
    private String benefitVerdictRefusal;
    private String benefitIban;
    private int childCount;
    private Long soleChildId;
    private String soleChildName;
    private Integer relationshipCount;
    private Integer relationshipOrdinal;
    private Boolean earlierApp9Submitted;
    private List<String> overlappingPaidLeave;
    private List<String> leaveNotCountedAsInsuranceService;

    public Builder incapacityStartDate(LocalDate v) {
      this.incapacityStartDate = v;
      return this;
    }

    public Builder sickPeriod(LocalDate from, LocalDate to) {
      this.sickFrom = from;
      this.sickTo = to;
      return this;
    }

    public Builder absenceType(Integer category, String code, String name) {
      this.absenceCategory = category;
      this.absenceTypeCode = code;
      this.absenceTypeName = name;
      return this;
    }

    public Builder employerReceived(String no, LocalDate date) {
      this.employerReceivedNo = no;
      this.employerReceivedDate = date;
      return this;
    }

    public Builder periodLines(int count, int workDays) {
      this.periodLineCount = count;
      this.periodLineWorkDays = workDays;
      return this;
    }

    public Builder contractType(String v) {
      this.contractType = v;
      return this;
    }

    public Builder contractDates(LocalDate start, LocalDate end) {
      this.contractStartDate = start;
      this.contractEndDate = end;
      return this;
    }

    public Builder terminationDate(LocalDate v) {
      this.terminationDate = v;
      return this;
    }

    public Builder insuredTypeCode(String v) {
      this.insuredTypeCode = v;
      return this;
    }

    public Builder insuredOnOtherGround(boolean v) {
      this.insuredOnOtherGround = v;
      return this;
    }

    public Builder selfInsured(boolean v) {
      this.selfInsured = v;
      return this;
    }

    /** Стажът ВИНАГИ идва с флага си — оттам един метод за двете, а не две независими врати. */
    public Builder insuranceService(boolean declared, Integer months) {
      this.insuranceServiceDeclared = declared;
      this.insuranceServiceMonths = months;
      return this;
    }

    public Builder birthDate(LocalDate v) {
      this.birthDate = v;
      return this;
    }

    /** Замразената присъда на листа — или отказът ѝ. Едното от двете, не и двете. */
    public Builder benefitVerdict(String verdict, String why, String refusal) {
      this.benefitVerdict = verdict;
      this.benefitVerdictWhy = why;
      this.benefitVerdictRefusal = refusal;
      return this;
    }

    public Builder benefitIban(String v) {
      this.benefitIban = v;
      return this;
    }

    public Builder children(int count, Long soleId, String soleName) {
      this.childCount = count;
      this.soleChildId = soleId;
      this.soleChildName = soleName;
      return this;
    }

    /** Т. 5 и т. 6 се подават заедно: указанието връзва втората за първата. */
    public Builder relationships(Integer count, Integer ordinal) {
      this.relationshipCount = count;
      this.relationshipOrdinal = ordinal;
      return this;
    }

    public Builder earlierApp9Submitted(Boolean v) {
      this.earlierApp9Submitted = v;
      return this;
    }

    public Builder overlappingPaidLeave(List<String> v) {
      this.overlappingPaidLeave = v;
      return this;
    }

    public Builder leaveNotCountedAsInsuranceService(List<String> v) {
      this.leaveNotCountedAsInsuranceService = v;
      return this;
    }

    public BgApp9Facts build() {
      return new BgApp9Facts(this);
    }
  }
}
