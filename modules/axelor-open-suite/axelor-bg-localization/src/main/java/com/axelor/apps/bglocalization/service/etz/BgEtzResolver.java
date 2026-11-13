package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.bglocalization.db.BgContractAmendment;
import com.axelor.apps.bglocalization.db.BgContractTransfer;
import com.axelor.apps.bglocalization.service.BgCompensationProposalResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * СГЛОБЯВАНЕТО НА ЗАПИСА — <b>чиста функция върху фактите, без база и без Guice</b>.
 *
 * <p>Модулът няма mock-подпора: правило, което иска жива Guice или база, се проверява само
 * структурно. Резолверът приема {@link BgEtzFacts} и връща {@link BgEtzFile} — тоест всяко правило
 * тук се сверява с дати и кодове в обикновен JUnit тест.
 *
 * <p><b>⚠️ ДНЕС СЕ СГЛОБЯВАТ ДВЕ ОТ ЧЕТИРИТЕ СЪБИТИЯ, И ТОВА Е ПРЕЦЕНКА, НЕ ПРОПУСК.</b> Сключване
 * (тип 1) и прекратяване (тип 3) се извеждат ЦЯЛОСТНО от текущия договор — той носи последното
 * състояние, а точно то е вярното за тези две събития.
 *
 * <p>Изменение (тип 2) и промяна на работодател НЕ се сглобяват: те искат данни, които договорът не
 * пази. Тип 2 иска т. 16 (дата на допълнителното споразумение) И т. 16А (дата на влизане в сила) —
 * ДВЕ различни дати — плюс кодирано основание за изменението (кодове 018–021); моделът има едно
 * {@code amendmentDate}, което се презаписва. Чл. 123 иска {@code formereik} и {@code
 * correctiontype}, за които няма носител.
 *
 * <p><b>⚠️ ДАТИТЕ ИЗЛИЗАТ ПО ISO, А НЕ ПО „ддммгггг“.</b> Наредбата описва датите като (ддммгггг) —
 * това е ЧОВЕШКОТО описание на реквизита в текста ѝ. Машинният договор е схемата и тя казва {@code
 * xs:date}. Писани като „12092026“, всички дати падат на структурна проверка — измерено срещу
 * закрепената схема, не предположено.
 */
public class BgEtzResolver {

  /** {@code xs:date} — ISO-8601. Виж класовия javadoc защо НЕ „ддммгггг“. */
  static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  /** Код корекция 0 — редовни данни. */
  public static final String CORRECTION_REGULAR = "0";

  /**
   * Сглобява файл с ЕДИН ред за един договор.
   *
   * @param facts фактите на договора
   * @param kind събитието — днес се приемат само {@link BgEtzEventKind#CONCLUSION} и {@link
   *     BgEtzEventKind#TERMINATION}
   * @param gaps списък, в който се дописват пречките; НЕ се хвърля при първата
   */
  public BgEtzFile resolve(BgEtzFacts facts, BgEtzEventKind kind, List<BgEtzGap> gaps) {
    return resolve(facts, kind, gaps, null);
  }

  /**
   * Същото, с изричните реквизити на прекратяването.
   *
   * @param input т. 25, 28–33 — реквизитите, които договорът не пази; {@code null} ги обявява като
   *     пречки вместо да ги подразбира
   */
  public BgEtzFile resolve(
      BgEtzFacts facts, BgEtzEventKind kind, List<BgEtzGap> gaps, BgEtzTerminationInput input) {
    if (facts == null) {
      throw new IllegalArgumentException("няма факти, от които да се сглоби ЕТЗ запис");
    }
    if (kind == null) {
      throw new IllegalArgumentException(
          "видът събитие не е избран — той решава по коя схема върви записът и не се извежда от"
              + " данните");
    }
    if (kind == BgEtzEventKind.AMENDMENT || kind == BgEtzEventKind.EMPLOYER_CHANGE) {
      // ⚠️ ТЕЗИ ДВЕ НЕ СЕ СГЛОБЯВАТ ОТ ДОГОВОРА, И ТОВА ВЕЧЕ НЕ Е ЛИПСА, А МАРШРУТ.
      // Изменението иска КОНКРЕТЕН анекс (BgContractAmendment), прехвърлянето — конкретен
      // трансфер (BgContractTransfer): договорът носи само последното си състояние и не може да
      // каже КОЕ от няколкото изменения се подава.
      throw new BgEtzUnsupportedEventException(
          "събитието „"
              + kind.getLabel()
              + "“ не се сглобява от договора — то иска конкретен"
              + " запис. Ползвай "
              + (kind == BgEtzEventKind.AMENDMENT
                  ? "resolveAmendment(facts, BgContractAmendment, gaps)"
                  : "resolveTransfer(facts, BgContractTransfer, gaps)")
              + ".");
    }

    List<BgEtzGap> out = gaps == null ? new ArrayList<>() : gaps;
    BgEtzFile file = new BgEtzFile(kind);

    // ── заглавната част ─────────────────────────────────────────────────────
    String eik = facts.getEmployerEik();
    if (isEmpty(eik)) {
      out.add(
          BgEtzGap.of(
              BgEtz62Field.EIK.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "дружеството няма ЕИК по БУЛСТАТ (Partner.registrationCode)"));
    }
    file.putHeader(BgEtz62Field.EIK.getTag(), eik);

    BgEtzRecord row = new BgEtzRecord();
    String employType = employTypeOf(facts, out);
    row.put(BgEtz62Field.EMPLOYTYPE, employType);

    // ── код корекция (т. 2) и тип на документа (т. 3) ───────────────────────
    // ⚠️ Код корекция е ВИНАГИ 0 тук. 1 (корекция) и 2 (заличаване) се отнасят до ВЕЧЕ подаден
    // запис и искат неговите ключови полета — тоест следа от подаването, не състояние на договора.
    row.put(BgEtz62Field.CODECORRECTION, CORRECTION_REGULAR);
    row.put(BgEtz62Field.DOCUMENTTYPE, documentTypeOf(kind));

    fillPerson(facts, row, out);
    putDate(
        out,
        row,
        BgEtz62Field.CONTRACTDATE,
        facts.getSignatureDate(),
        "договорът няма дата на сключване");
    putContractGround(facts, row, out, employType);

    if (kind == BgEtzEventKind.CONCLUSION) {
      fillConclusion(facts, row, out);
    } else {
      fillTermination(facts, row, out, input);
    }

    file.add(row);
    out.addAll(BgEtzMandatory.check62(row));
    return file;
  }

  /**
   * ИЗМЕНЕНИЕ (тип на документа 2) — <b>по едно събитие на АНЕКС, не на договор</b>.
   *
   * <p>Това е причината {@link com.axelor.apps.bglocalization.db.BgContractAmendment} да
   * съществува: {@code EmploymentContract.amendmentDate} се презаписва, а регистърът иска всяко
   * изменение поотделно.
   *
   * <p><b>⚠️ ПОЛЕ 12 ИДВА ОТ АНЕКСА, А НЕ ОТ ВИДА ДОГОВОР — И ТОВА Е ОБРАТНОТО НА ТИП 1 И 3.</b>
   * При сключване и прекратяване т. 12 носи основанието, на което договорът Е СКЛЮЧЕН (чл. 67 →
   * 001). При изменение същото поле носи основанието на ИЗМЕНЕНИЕТО (018–021 при КТ, 100–128 при
   * ЗДСл) — Наредбата го нарича „основание за изменение на правоотношението“. Пренесено от
   * договора, то би обявило всяко изменение за ново сключване.
   *
   * <p><b>⚠️ И СТОЙНОСТИТЕ СА НА АНЕКСА, НЕ НА ДОГОВОРА.</b> Приложение № 1 иска при тип 2
   * попълнени т. 18, 19/20, 21, 22, 23, 23А и 24 — състоянието СЛЕД изменението. Договорът носи
   * само последното; за исторически анекс той вече не знае какво е било тогава.
   */
  public BgEtzFile resolveAmendment(
      BgEtzFacts facts, BgContractAmendment amendment, List<BgEtzGap> gaps) {

    if (facts == null) {
      throw new IllegalArgumentException("няма факти, от които да се сглоби ЕТЗ запис");
    }
    if (amendment == null) {
      throw new IllegalArgumentException(
          "няма анекс — тип на документа 2 се подава за КОНКРЕТНО изменение, не за договор");
    }
    List<BgEtzGap> out = gaps == null ? new ArrayList<>() : gaps;
    BgEtzFile file = new BgEtzFile(BgEtzEventKind.AMENDMENT);
    putEmployerHeader(facts, file, out);

    BgEtzRecord row = new BgEtzRecord();
    String employType = employTypeOf(facts, out);
    row.put(BgEtz62Field.EMPLOYTYPE, employType);
    row.put(BgEtz62Field.CODECORRECTION, CORRECTION_REGULAR);
    row.put(BgEtz62Field.DOCUMENTTYPE, BgEtzMandatory.DOC_AMENDMENT);

    fillPerson(facts, row, out);
    putDate(
        out,
        row,
        BgEtz62Field.CONTRACTDATE,
        facts.getSignatureDate(),
        "договорът няма дата на сключване");

    // ── основанието за ИЗМЕНЕНИЕТО (т. 12) ──────────────────────────────────
    String ground = trimOrNull(amendment.getBgChangeGround());
    if (ground == null) {
      out.add(
          BgEtzGap.of(
              BgEtz62Field.CONTRACTTYPE.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "анексът няма основание за изменението (BgContractAmendment.bgChangeGround)"));
    } else if (!BgEtzGrounds.isContractCode(ground, employType)) {
      // ⚠️ Тук пада и служебният маркер „000“, с който миграцията посява заварените анекси:
      // amendmentTypeSelect не носи код по номенклатурата и никой не е избирал основание.
      out.add(
          BgEtzGap.of(
              BgEtz62Field.CONTRACTTYPE.label(),
              BgEtzGap.Kind.UNMAPPED_CODE,
              "основанието „"
                  + ground
                  + "“ не е в номенклатурата по т. 12 за вид правоотношение „"
                  + employType
                  + "“."
                  + ("000".equals(ground)
                      ? " ⚠️ Това е служебният маркер на миграцията: анексът е пренесен от"
                          + " EmploymentContract.amendmentDate, а старото amendmentTypeSelect НЕ"
                          + " носи кодирано основание — изберете го."
                      : " Допустимите кодове са в Наредбата, т. 12.")));
    } else {
      row.put(BgEtz62Field.CONTRACTTYPE, ground);
    }

    // ── датите на изменението (т. 16 и 16А) ─────────────────────────────────
    putDate(
        out,
        row,
        BgEtz62Field.CHANGEDATE,
        amendment.getChangeDate(),
        "анексът няма дата на сключване");
    putDate(
        out,
        row,
        BgEtz62Field.ENTRYDATE,
        amendment.getEntryDate(),
        "анексът няма дата на влизане в сила (т. 16А е РАЗЛИЧНА от т. 16)");
    row.put(BgEtz62Field.CHANGENO, amendment.getChangeNo());

    // ⚠️ т. 14 СЕ ИСКА И ПРИ ИЗМЕНЕНИЕ, не само при сключване — Приложение № 1: „попълва се, когато
    // в т. 1 е попълнен код 01 и тип на документа е 1 ИЛИ 2“. И тя е свойство на ДОГОВОРА (началото
    // на изпълнението), не на анекса: изменението не мести деня, в който човекът е започнал работа.
    putDate(
        out,
        row,
        BgEtz62Field.BEGINDATE,
        facts.getStartDate(),
        "договорът няма начало на изпълнението, а т. 14 се иска и при тип на документа 2");

    // ── състоянието СЛЕД изменението ────────────────────────────────────────
    row.put(BgEtz62Field.SALARY, money(amendment.getNewSalary()));
    put(out, row, BgEtz62Field.CODENKPD, nkpdOf(amendment), "анексът няма код по НКПД");
    put(
        out,
        row,
        BgEtz62Field.CODEKID,
        facts.getKidCode(),
        "дружеството няма код по КИД (Company.bgNkid)");
    put(
        out,
        row,
        BgEtz62Field.CODEEKATTE,
        ekatteOf(amendment, facts),
        "нито анексът, нито договорът носят код по ЕКАТТЕ на мястото на работа");
    if (amendment.getNewHoursCode() != 0) {
      row.put(BgEtz62Field.CODEHOURS, String.valueOf(amendment.getNewHoursCode()));
    }
    if (amendment.getNewWorkingHours() != 0) {
      row.put(BgEtz62Field.WORKINGHOURS, String.valueOf(amendment.getNewWorkingHours()));
    }
    row.put(BgEtz62Field.VACATIONDAYS, positiveInt(amendment.getNewVacationDays()));
    if (amendment.getNewTermDate() != null) {
      row.put(BgEtz62Field.TERMDATE, DATE.format(amendment.getNewTermDate()));
    }

    file.add(row);
    out.addAll(BgEtzMandatory.check62(row));
    return file;
  }

  /**
   * ПРОМЯНА НА РАБОТОДАТЕЛЯ по чл. 123 / чл. 123а — <b>другата схема, другият корен</b>.
   *
   * <p><b>⚠️ ДВА ЕИК В ЗАГЛАВНАТА ЧАСТ, И РЕДЪТ ИМ НОСИ ПОСОКАТА.</b> {@code formereik} е
   * ПРЕДИШНИЯТ работодател (от трансфера), {@code eik} — новият (дружеството по договора).
   * Разменени, файлът остава валиден по схема — двата елемента са от един тип — и вписва
   * прехвърлянето в обратната посока.
   *
   * <p><b>⚠️ ПОЛЕ 12 ТУК Е ОСНОВАНИЕТО НА СЪЩЕСТВУВАЩИЯ ДОГОВОР, НЕ НА ПРОМЯНАТА.</b> Приложение №
   * 2 го нарича „Основание на трудовия договор/основание за възникване на служебното
   * правоотношение“ — тоест същата номенклатура като при сключване. Основанието на ПРОМЯНАТА е
   * отделно поле (т. 3, кодове 1–11).
   */
  public BgEtzFile resolveTransfer(
      BgEtzFacts facts, BgContractTransfer transfer, List<BgEtzGap> gaps) {

    if (facts == null) {
      throw new IllegalArgumentException("няма факти, от които да се сглоби ЕТЗ запис");
    }
    if (transfer == null) {
      throw new IllegalArgumentException(
          "няма трансфер — чл. 123 се подава за КОНКРЕТНА промяна на работодателя");
    }
    List<BgEtzGap> out = gaps == null ? new ArrayList<>() : gaps;
    BgEtzFile file = new BgEtzFile(BgEtzEventKind.EMPLOYER_CHANGE);

    // ── заглавната част: ПРЕДИШЕН, после НОВ ────────────────────────────────
    String formerEik = trimOrNull(transfer.getFormerEik());
    if (formerEik == null) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.FORMEREIK.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "трансферът няма ЕИК на предишния работодател (BgContractTransfer.formerEik)"));
    }
    file.putHeader(BgEtz123Field.FORMEREIK.getTag(), formerEik);

    String newEik = facts.getEmployerEik();
    if (isEmpty(newEik)) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.EIK.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "дружеството по договора няма ЕИК по БУЛСТАТ (Partner.registrationCode)"));
    }
    file.putHeader(BgEtz123Field.EIK.getTag(), newEik);

    // ⚠️ Един и същ ЕИК от двете страни не е прехвърляне. Схемата го приема — и двата елемента са
    // валидни BULSTAT — а регистърът би вписал преминаване към самия себе си.
    if (formerEik != null && formerEik.equals(newEik)) {
      out.add(
          BgEtzGap.general(
              BgEtzGap.Kind.CROSS_CHECK,
              "предишният и новият работодател са с еднакъв ЕИК ("
                  + formerEik
                  + ") — това не е"
                  + " промяна по чл. 123"));
    }

    BgEtzRecord row = new BgEtzRecord();
    String employType = employTypeOf(facts, out);
    row.put(BgEtz123Field.EMPLOYTYPE, employType);
    row.put(BgEtz123Field.CODECORRECTION, CORRECTION_REGULAR);

    // ── основанието на ПРОМЯНАТА (т. 3) ─────────────────────────────────────
    String reason = trimOrNull(transfer.getBgChangeReason());
    if (reason == null) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.CORRECTIONTYPE.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "трансферът няма основание на промяната (BgContractTransfer.bgChangeReason)"));
    } else {
      try {
        row.put(
            BgEtz123Field.CORRECTIONTYPE,
            BgEtzCorrectionType.of(Integer.parseInt(reason)).asValue());
      } catch (IllegalArgumentException e) {
        // ⚠️ Отказ, не подразбиране. „1 — сливане“ е първо в списъка и затова е съблазнително за
        // начална стойност; основанието обаче е правен факт за конкретното прехвърляне.
        out.add(
            BgEtzGap.of(
                BgEtz123Field.CORRECTIONTYPE.label(),
                BgEtzGap.Kind.UNMAPPED_CODE,
                "основанието на промяната „"
                    + reason
                    + "“ не е сред 1–11 по Приложение № 2: "
                    + e.getMessage()));
      }
    }

    // ── лицето (т. 6–10) ────────────────────────────────────────────────────
    put123(
        out,
        row,
        BgEtz123Field.EGN,
        facts.getPersonalIdNumber(),
        "наетото лице няма попълнен ЕГН/ЛНЧ");
    row.put(BgEtz123Field.EGNTYPE, egnTypeOf123(facts.getPersonalIdType(), out));
    put123(out, row, BgEtz123Field.FIRSTNAME, facts.getFirstName(), "лицето няма собствено име");
    row.put(BgEtz123Field.SECONDNAME, facts.getMiddleName());
    put123(out, row, BgEtz123Field.THIRDNAME, facts.getLastName(), "лицето няма фамилия");

    // ── договорът (т. 11, 12) и датата на промяната (т. 13) ─────────────────
    if (facts.getSignatureDate() == null) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.CONTRACTDATE.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "договорът няма дата на сключване"));
    } else {
      row.put(BgEtz123Field.CONTRACTDATE, DATE.format(facts.getSignatureDate()));
    }
    try {
      row.put(
          BgEtz123Field.CONTRACTTYPE,
          BgEtzGrounds.contractGroundFor(facts.getContractType(), employType));
    } catch (BgEtzGrounds.BgEtzUnmappedGroundException e) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.CONTRACTTYPE.label(), BgEtzGap.Kind.UNMAPPED_CODE, e.getMessage()));
    }
    if (transfer.getChangeDate() == null) {
      out.add(
          BgEtzGap.of(
              BgEtz123Field.CHANGEDATE.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "трансферът няма дата на промяната"));
    } else {
      row.put(BgEtz123Field.CHANGEDATE, DATE.format(transfer.getChangeDate()));
    }

    // ── ЕКАТТЕ (т. 14) ──────────────────────────────────────────────────────
    String ekatte = trimOrNull(transfer.getNewWorkplaceEkatte());
    row.put(BgEtz123Field.CODEEKATTE, ekatte != null ? ekatte : facts.getEkatteCode());

    file.add(row);
    out.addAll(BgEtzMandatory.check123(row));
    return file;
  }

  // ── общи помощници, ползвани и от трите пътя ──────────────────────────────

  /** Заглавната част на {@code etzemploy} — един ЕИК. */
  protected void putEmployerHeader(BgEtzFacts facts, BgEtzFile file, List<BgEtzGap> gaps) {
    String eik = facts.getEmployerEik();
    if (isEmpty(eik)) {
      gaps.add(
          BgEtzGap.of(
              BgEtz62Field.EIK.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "дружеството няма ЕИК по БУЛСТАТ (Partner.registrationCode)"));
    }
    file.putHeader(BgEtz62Field.EIK.getTag(), eik);
  }

  /**
   * т. 1 — видът правоотношение.
   *
   * <p>Свойство на АКТА, не на записа: КТ и ЗВО пораждат трудово правоотношение, ЗДСл — служебно.
   * Преизползва правилото, което предлагането на обезщетения вече ползва — второ копие би могло да
   * се размине с него, без нито един тест да падне.
   */
  protected String employTypeOf(BgEtzFacts facts, List<BgEtzGap> gaps) {
    // ⚠️ ПЪРВО ВИДЪТ ДОГОВОР, после основанието за прекратяване. Договор по чл. 67 КТ Е трудово
    // правоотношение — това не е подразбиране, а самото име на члена. Основанието за ПРЕКРАТЯВАНЕ
    // нов договор няма; четено само от него, всяко сключване би паднало на т. 1.
    String family = BgEtzGrounds.legalFamilyOfContractType(facts.getContractType());
    if (family == null) {
      family = facts.getLegalFamily();
    }
    String employType = BgCompensationProposalResolver.relationshipKindOf(family);
    if (isEmpty(employType)) {
      gaps.add(
          BgEtzGap.of(
              BgEtz62Field.EMPLOYTYPE.label(),
              BgEtzGap.Kind.EMPTY_VALUE,
              "видът правоотношение се извежда от вида договор, когато той е член от КТ, или от"
                  + " нормативния акт на основанието за прекратяване — тук няма нито едното"));
    }
    return employType;
  }

  /** т. 5–9 на {@code etzemploy} — идентификаторът и трите имена. */
  protected void fillPerson(BgEtzFacts facts, BgEtzRecord row, List<BgEtzGap> gaps) {
    put(
        gaps,
        row,
        BgEtz62Field.EGN,
        facts.getPersonalIdNumber(),
        "наетото лице няма попълнен ЕГН/ЛНЧ");
    row.put(BgEtz62Field.EGNTYPE, egnTypeOf(facts.getPersonalIdType(), gaps));
    put(gaps, row, BgEtz62Field.FIRSTNAME, facts.getFirstName(), "лицето няма собствено име");
    row.put(BgEtz62Field.SECONDNAME, facts.getMiddleName());
    put(gaps, row, BgEtz62Field.THIRDNAME, facts.getLastName(), "лицето няма фамилия");
  }

  /** т. 12 от ВИДА ДОГОВОР — за сключване и прекратяване (при изменение идва от анекса). */
  protected void putContractGround(
      BgEtzFacts facts, BgEtzRecord row, List<BgEtzGap> gaps, String employType) {
    try {
      row.put(
          BgEtz62Field.CONTRACTTYPE,
          BgEtzGrounds.contractGroundFor(facts.getContractType(), employType));
    } catch (BgEtzGrounds.BgEtzUnmappedGroundException e) {
      gaps.add(
          BgEtzGap.of(
              BgEtz62Field.CONTRACTTYPE.label(), BgEtzGap.Kind.UNMAPPED_CODE, e.getMessage()));
    }
  }

  /** НКПД от анекса, с връщане към договора, когато изменението не го мени. */
  private static String nkpdOf(BgContractAmendment amendment) {
    return amendment.getNewNkpd() == null ? null : amendment.getNewNkpd().getCode();
  }

  /**
   * ЕКАТТЕ от анекса, с връщане към договора.
   *
   * <p>⚠️ Празно на анекса значи „мястото не се мени“, а не „няма място“ — затова се чете от
   * договора. Липсва ли и там, пречката е обявена: т. 22 е задължителна при тип 2.
   */
  private static String ekatteOf(BgContractAmendment amendment, BgEtzFacts facts) {
    String own = trimOrNull(amendment.getNewWorkplaceEkatte());
    return own != null ? own : facts.getEkatteCode();
  }

  private static String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void put123(
      List<BgEtzGap> gaps, BgEtzRecord row, BgEtz123Field field, String value, String detail) {
    if (isEmpty(value)) {
      gaps.add(BgEtzGap.of(field.label(), BgEtzGap.Kind.EMPTY_VALUE, detail));
      return;
    }
    row.put(field, value);
  }

  /** Същият превод като при {@code etzemploy}, но с номерата на Приложение № 2 в отказа. */
  static String egnTypeOf123(String bgPersonalIdType, List<BgEtzGap> gaps) {
    String type = bgPersonalIdType == null ? "" : bgPersonalIdType.trim();
    if ("1".equals(type)) {
      return "0";
    }
    if ("2".equals(type)) {
      return "2";
    }
    gaps.add(
        BgEtzGap.of(
            BgEtz123Field.EGNTYPE.label(),
            BgEtzGap.Kind.EMPTY_VALUE,
            "видът на идентификатора не е избран (Employee.bgPersonalIdType)"));
    return null;
  }

  /** Полетата, които СКЛЮЧВАНЕТО (тип 1) иска. */
  protected void fillConclusion(BgEtzFacts facts, BgEtzRecord row, List<BgEtzGap> gaps) {
    putDate(
        gaps,
        row,
        BgEtz62Field.BEGINDATE,
        facts.getStartDate(),
        "договорът няма начало на изпълнението");
    // Срокът е реквизит само при СРОЧНО правоотношение; празен при безсрочен е правилното
    // състояние, не липса — затова тук няма пречка при null.
    if (facts.getAgreedEndDate() != null) {
      row.put(BgEtz62Field.TERMDATE, DATE.format(facts.getAgreedEndDate()));
    }
    row.put(BgEtz62Field.SALARY, money(facts.getBaseSalary()));
    put(gaps, row, BgEtz62Field.CODENKPD, facts.getNkpdCode(), "договорът няма код по НКПД");
    put(gaps, row, BgEtz62Field.CODEKID, facts.getKidCode(), "дружеството няма код по КИД");
    put(
        gaps,
        row,
        BgEtz62Field.CODEEKATTE,
        facts.getEkatteCode(),
        "договорът няма код по ЕКАТТЕ на мястото на работа");
    fillWorkingTime(facts, row);
    row.put(BgEtz62Field.VACATIONDAYS, positiveInt(facts.getAnnualLeaveDays()));
  }

  /** Полетата, които ПРЕКРАТЯВАНЕТО (тип 3) иска. */
  protected void fillTermination(
      BgEtzFacts facts, BgEtzRecord row, List<BgEtzGap> gaps, BgEtzTerminationInput input) {
    putDate(
        gaps,
        row,
        BgEtz62Field.ENDDATE,
        facts.getTerminationDate(),
        "договорът няма дата на прекратяване");
    String employType = row.get(BgEtz62Field.EMPLOYTYPE);
    try {
      row.put(
          BgEtz62Field.ENDREASON,
          BgEtzGrounds.terminationGroundFor(
              facts.getTerminationGround(), facts.getLegalFamily(), employType));
    } catch (BgEtzGrounds.BgEtzUnmappedGroundException e) {
      gaps.add(
          BgEtzGap.of(BgEtz62Field.ENDREASON.label(), BgEtzGap.Kind.UNMAPPED_CODE, e.getMessage()));
    }
    // ⚠️ т. 25 и т. 30 НЯМАТ НОСИТЕЛ на договора и затова се подават изрично. Липсващ вход се
    // обявява, вместо да се подразбира: нула използван отпуск е ИЗЯВЛЕНИЕ, а стаж, сметнат като
    // разлика между двете дати, мълчаливо приема, че няма непризнато време. Виж
    // BgEtzTerminationInput.
    if (input == null || input.getUsedVacationDays() == null) {
      gaps.add(
          BgEtzGap.of(
              BgEtz62Field.USEDVACATION.label(),
              BgEtzGap.Kind.NO_DATA_SOURCE,
              "използваният платен годишен отпуск за годината на прекратяване не се пази на"
                  + " договора (bgAnnualLeaveDays е ПОЛАГАЕМИЯТ) — подава се през"
                  + " BgEtzTerminationInput"));
    } else {
      row.put(BgEtz62Field.USEDVACATION, String.valueOf(input.getUsedVacationDays().intValue()));
    }
    if (input == null || isEmpty(input.getRecognisedExperience())) {
      gaps.add(
          BgEtzGap.of(
              BgEtz62Field.EXPERIENCE.label(),
              BgEtzGap.Kind.NO_DATA_SOURCE,
              "признатият трудов стаж ПО ТОЗИ ДОГОВОР (ггммдд) не се изчислява от модула;"
                  + " bgPriorExperience* описва стажа ПРЕДИ договора — подава се през"
                  + " BgEtzTerminationInput"));
    } else {
      row.put(BgEtz62Field.EXPERIENCE, input.getRecognisedExperience());
    }
    if (input != null) {
      row.put(BgEtz62Field.NOEXPERIENCE, input.getNotRecognisedExperience());
      row.put(BgEtz62Field.WORKPERIOD, input.getCivilServicePeriod());
      if (input.getCompensation222PaidOn() != null) {
        row.put(BgEtz62Field.COMPENSATION222, DATE.format(input.getCompensation222PaidOn()));
      }
      if (input.getCompensation106PaidOn() != null) {
        row.put(BgEtz62Field.COMPENSATION106, DATE.format(input.getCompensation106PaidOn()));
      }
      if (input.getGarnishment() != null) {
        row.put(BgEtz62Field.COMMCODE, input.getGarnishment() ? "1" : "0");
      }
    }
  }

  /** т. 23 и 23А — кодът и продължителността вървят ЗАЕДНО и се извеждат от един носител. */
  protected void fillWorkingTime(BgEtzFacts facts, BgEtzRecord row) {
    BigDecimal perDay = facts.getHoursPerDay();
    if (perDay != null && perDay.signum() > 0) {
      row.put(BgEtz62Field.CODEHOURS, "1");
      row.put(BgEtz62Field.WORKINGHOURS, perDay.setScale(0, RoundingMode.HALF_UP).toPlainString());
    }
    // ⚠️ Седмичният вариант (код 2) НЕ се предлага като резервен. Ако дневната продължителност е
    // празна, записът остава без т. 23/23А и BgEtzMandatory го отказва с назована причина —
    // по-добре от подразбиране, което би обявило невярно работно време.
  }

  /** Тип на документа по събитието. */
  static String documentTypeOf(BgEtzEventKind kind) {
    switch (kind) {
      case CONCLUSION:
        return BgEtzMandatory.DOC_CONCLUSION;
      case AMENDMENT:
        return BgEtzMandatory.DOC_AMENDMENT;
      case TERMINATION:
        return BgEtzMandatory.DOC_TERMINATION;
      default:
        throw new IllegalStateException(
            "събитието " + kind + " няма тип на документа в схемата etzemploy");
    }
  }

  /**
   * т. 6 — кодът на идентификатора.
   *
   * <p>⚠️ НАШИТЕ СТОЙНОСТИ НЕ СА СТОЙНОСТИТЕ НА НАП. {@code bg.personal.id.type.select} е „1“ ЕГН и
   * „2“ ЛНЧ; регистърът иска „0“ за ЕГН и „2“ за ЛНЧ/ЛН/служебен номер. Съвпадението при ЛНЧ е
   * СЛУЧАЙНО и точно то прави пряката употреба съблазнителна: ЕГН би влязъл като „1“ — стойност,
   * която схемата отхвърля.
   */
  static String egnTypeOf(String bgPersonalIdType, List<BgEtzGap> gaps) {
    String type = bgPersonalIdType == null ? "" : bgPersonalIdType.trim();
    if ("1".equals(type)) {
      return "0";
    }
    if ("2".equals(type)) {
      return "2";
    }
    gaps.add(
        BgEtzGap.of(
            BgEtz62Field.EGNTYPE.label(),
            BgEtzGap.Kind.EMPTY_VALUE,
            "видът на идентификатора не е избран (Employee.bgPersonalIdType)"));
    return null;
  }

  /** {@code xs:float} — без излишни нули, с точка. */
  static String money(BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      return null;
    }
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** {@code xs:positiveInteger} — нулата и отрицателното не са допустими стойности. */
  static String positiveInt(BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      return null;
    }
    return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
  }

  private static void put(
      List<BgEtzGap> gaps, BgEtzRecord row, BgEtz62Field field, String value, String detail) {
    if (isEmpty(value)) {
      gaps.add(BgEtzGap.of(field.label(), BgEtzGap.Kind.EMPTY_VALUE, detail));
      return;
    }
    row.put(field, value);
  }

  private static void putDate(
      List<BgEtzGap> gaps, BgEtzRecord row, BgEtz62Field field, LocalDate date, String detail) {
    if (date == null) {
      gaps.add(BgEtzGap.of(field.label(), BgEtzGap.Kind.EMPTY_VALUE, detail));
      return;
    }
    row.put(field, DATE.format(date));
  }

  private static boolean isEmpty(String value) {
    return value == null || value.trim().isEmpty();
  }

  /** Отказът при събитие, което текущият модел не може да сглоби. */
  public static class BgEtzUnsupportedEventException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public BgEtzUnsupportedEventException(String message) {
      super(message);
    }
  }
}
