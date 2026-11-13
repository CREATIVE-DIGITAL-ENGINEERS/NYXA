package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * РЕГИСТЪРЪТ НА ИЗПЛАТЕНИТЕ ДОХОДИ ЗА ЕДНА ГОДИНА → РЕДОВЕТЕ НА ЧАСТ ІІ ОТ ОБРАЗЕЦ 5-2001.
 *
 * <p>Чиста функция: без база и без Guice — по образеца на {@link BgArt73Para6Aggregator}. Прочитът
 * от базата, идентификацията на лицето (колони 4–8) и закачането към транспорта живеят в {@code
 * BgArt73Para1ReportService}; тук се случва само сливането и сумирането, за да остане проверимо без
 * Postgres.
 *
 * <p><b>═══ ПО КОЕ СЕ ГРУПИРА ═══</b>
 *
 * <p>По <b>ЛИЦЕ + КОД НА ДОХОДА + КОД КОРЕКЦИЯ</b>, и трите са задължителни:
 *
 * <ul>
 *   <li><b>ЛИЦЕ</b> — Част ІІ описва доходите на конкретно физическо лице (колони 4–8);
 *   <li><b>КОД</b> — указание т. 7, дословно: „Ако на едно физическо лице са изплатени доходи с
 *       различен код, данните се записват на отделни редове.“;
 *   <li><b>КОД КОРЕКЦИЯ</b> — указание т. 2 дава на колона 1 три стойности („0“ основни, „1“
 *       коригиращи, „8“ заличаващи), които съжителстват В ЕДНА справка. Слети в един ред, основните
 *       и коригиращите данни за едно лице по един код биха дали сума, която не е нито едното, нито
 *       другото; а заличаващият ред изобщо няма суми и добавен към сбор би ги обезсмислил.
 * </ul>
 *
 * <p><b>⚠️ И ДАТАТА НЕ Е В КЛЮЧА — ТОВА Е СМИСЪЛЪТ НА СПРАВКАТА.</b> Тя е ГОДИШНА: две плащания по
 * един код към едно лице през март и през ноември са ЕДИН ред с годишната сума, а не два реда.
 * Датата решава само в КОЯ година попада плащането — виж {@link #inTaxYear}.
 *
 * <p><b>═══ КОЕ НЕ ВЛИЗА ═══</b>
 *
 * <p><b>1. ТРУДОВИТЕ ДОХОДИ (КОДОВЕ 101–111).</b> Те са в справката по чл. 73 ал. 6 и се събират
 * ПРЯКО от фишовете ({@link BgArt73Para6Aggregator}). ⚠️ Отделянето им става по {@code
 * BgIncomeType.declarations}, а НЕ по списък „101–111“ в Java: коя справка кой код обслужва е
 * номенклатура, утвърдена със заповед на изпълнителния директор на НАП и сменяна с всеки образец.
 * Списък в код би оцелял следващата заповед мълчаливо — тоест би вкарал в справката код, който НАП
 * е преместил, и никой тест не би паднал. Признакът е ДАННИ, на реда на кода.
 *
 * <p><b>2. ПЛАЩАНИЯТА ИЗВЪН ДАНЪЧНАТА ГОДИНА</b> — по {@code paymentDate}, датата на ПРИДОБИВАНЕ на
 * дохода по чл. 11. ⚠️ Указания т. 4 и т. 5 обявяват ДВЕ изключения от това правило (дивидентите по
 * чл. 38 ал. 1 и прехвърлянията по чл. 33 ал. 3), които регистърът НЕ може да изпълни днес — вход
 * за тях няма. Те са описани в {@link BgArt73Para1Boundaries}, а не изведени тук по догадка.
 *
 * <p><b>3. РЕД БЕЗ ВИД ДОХОД ИЛИ БЕЗ ДАТА</b> — той не може да бъде поставен нито в колона 10, нито
 * в година. Броят му се връща отделно (виж {@link Result#getUnusableCount}), за да не изчезне тихо.
 *
 * <p><b>═══ СБОРНИТЕ КОДОВЕ ═══</b>
 *
 * <p>Указание т. 8, дословно: „За доходи с кодове 5031, 8141 и 820 брутният размер на доходите за
 * ВСИЧКИ физически лица се вписва на ЕДИН ред като обща сума и колона 15 „ЕИК на преобразуващото се
 * предприятие“ не се попълва.“ Указание т. 5: „Колони от 2 до 9, Част ІІ не се попълват за
 * изплатени доходи с кодове 5031, 8141 и 820. Колона 14 „Брой лица“ се попълва само за доходи с
 * кодове 5031 и 8141.“
 *
 * <p><b>⚠️ ТОЕСТ ДВА РАЗЛИЧНИ ПРИЗНАКА, А НЕ ЕДИН.</b> {@code isAggregate} маха лицето от ключа и
 * оставя колони 2–9 празни — за трите кода. {@code reportsPersonCount} пълни колона 14 — само за
 * ДВА от тях. Код 820 (наем в режим на етажна собственост по чл. 38 ал. 12) е сборен ред БЕЗ брой
 * лица. Изведена от единия флаг, колона 14 би получила число и там.
 */
public final class BgArt73Para1Aggregator {

  /** Стойността на {@code BgIncomeType.declarations} за справката по ал. 1. */
  public static final String DECLARATION_PARA1 = "73-1";

  /** Указание т. 2 — колона 1: основни данни. */
  public static final String CORRECTION_MAIN = "0";

  /** Указание т. 2 — колона 1: коригиращи данни. */
  public static final String CORRECTION_AMEND = "1";

  /**
   * Указание т. 2 — колона 1: ЗАЛИЧАВАЩИ данни.
   *
   * <p>⚠️ При него се попълват само колони 1, 2, 3, 4 или 5, 6, 7, 8 и 10 — нито една сума.
   */
  public static final String CORRECTION_DELETE = "8";

  private BgArt73Para1Aggregator() {}

  /**
   * РЕДОВЕТЕ НА ЧАСТ ІІ за подадените плащания и данъчната година.
   *
   * <p>Редът на изхода следва реда на входа — първото плащане от групата определя мястото на реда.
   * Така справката се чете в същия ред, в който е въвеждан регистърът.
   */
  public static Result aggregate(List<BgIndividualIncomePayment> payments, int taxYear) {
    if (payments == null) {
      return new Result(Collections.emptyList(), 0, 0);
    }
    Map<String, Bucket> buckets = new LinkedHashMap<>();
    int unusable = 0;
    int otherForm = 0;

    for (BgIndividualIncomePayment payment : payments) {
      if (payment == null) {
        continue;
      }
      BgIncomeType type = payment.getIncomeType();
      if (type == null || payment.getPaymentDate() == null) {
        // ⚠️ Не се брои като „чужда справка“: това е НЕПЪЛЕН ред, а не ред на друг образец.
        unusable++;
        continue;
      }
      if (!DECLARATION_PARA1.equals(type.getDeclarations())) {
        // Трудовите доходи и всичко останало, което образецът по ал. 6 обслужва.
        otherForm++;
        continue;
      }
      if (!inTaxYear(payment, taxYear)) {
        continue;
      }
      buckets.computeIfAbsent(key(payment, type), k -> new Bucket(type)).add(payment);
    }

    List<BgArt73Para1Line> lines = new ArrayList<>();
    for (Bucket bucket : buckets.values()) {
      lines.add(bucket.line());
    }
    return new Result(lines, unusable, otherForm);
  }

  /**
   * Попада ли плащането в данъчната година.
   *
   * <p>⚠️ ПО {@code paymentDate} — датата на ПРИДОБИВАНЕ на дохода по чл. 11, а не по тримесечния
   * период за чл. 55. Периодът служи на ДРУГА декларация и плащане в края на декември може да
   * принадлежи на период, който вече е от следващата година.
   */
  public static boolean inTaxYear(BgIndividualIncomePayment payment, int taxYear) {
    return payment != null
        && payment.getPaymentDate() != null
        && payment.getPaymentDate().getYear() == taxYear;
  }

  /**
   * КЛЮЧЪТ НА РЕДА.
   *
   * <p>За сборните кодове лицето ОТПАДА от ключа — целият код е един ред. За всички останали ключът
   * е „лице + код + код корекция“.
   *
   * <p>⚠️ Кодът в ключа е {@code code + validFrom}, а не само {@code code}: каталогът е ТЕМПОРАЛЕН
   * и уникалността в него е по двойката. Ключирано само по код, коригираща справка за минала година
   * би слята с редовете по днешната редакция на същия код — тоест два различни реда на образеца,
   * подадени като един.
   */
  private static String key(BgIndividualIncomePayment payment, BgIncomeType type) {
    String typeKey = type.getCode() + "@" + type.getValidFrom();
    String correction = correctionOf(payment);
    if (Boolean.TRUE.equals(type.getIsAggregate())) {
      return "AGG|" + typeKey + "|" + correction;
    }
    return partnerKey(payment.getPartner()) + "|" + typeKey + "|" + correction;
  }

  /**
   * Идентичността на лицето за ключа.
   *
   * <p>⚠️ По {@code id}, когато го има. Записан ред винаги го има; преходен обект (в тест или преди
   * запис) — не, и там пада на идентичността на обекта. Сравнени по {@code equals}, два преходни
   * Partner-а без id биха се слели в един ред.
   */
  private static String partnerKey(Partner partner) {
    if (partner == null) {
      // ⚠️ Не се слива с чуждите: ред без лице е СВОЙ ред, който справката ще покаже празен, а
      // човек ще види, че е празен. Слят с друг, той би добавил сумата си към чуждо име.
      return "NOPARTNER";
    }
    return partner.getId() == null
        ? "OBJ:" + System.identityHashCode(partner)
        : "ID:" + partner.getId();
  }

  /** Код корекция на реда; празното се чете като „основни данни“ — стойността по подразбиране. */
  private static String correctionOf(BgIndividualIncomePayment payment) {
    String code = payment.getCorrectionCode();
    return code == null || code.trim().isEmpty() ? CORRECTION_MAIN : code.trim();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** Натрупващата се група — едно лице (или целият сборен код), един код, един код корекция. */
  private static final class Bucket {

    private final BgIncomeType type;
    private final Set<String> persons = new LinkedHashSet<>();
    private BgIndividualIncomePayment first;
    private BigDecimal gross = BigDecimal.ZERO;
    private BigDecimal insurance = BigDecimal.ZERO;
    private BigDecimal tax = BigDecimal.ZERO;
    private int payments;
    private boolean disagreement;

    Bucket(BgIncomeType type) {
      this.type = type;
    }

    void add(BgIndividualIncomePayment payment) {
      if (first == null) {
        first = payment;
      } else {
        disagreement = disagreement || differsInIdentity(first, payment);
      }
      payments++;
      persons.add(partnerKey(payment.getPartner()));
      gross = gross.add(nz(payment.getGrossAmount()));
      insurance = insurance.add(nz(payment.getInsuranceAmount()));
      tax = tax.add(nz(payment.getTaxAmount()));
    }

    /**
     * ⚠️ Сравняват се САМО признаците на ЛИЦЕТО (колони 2, 3 и 9). Сумите се сумират и различието
     * им е очакваното; кодът и корекцията са в ключа и не могат да се различават.
     */
    private static boolean differsInIdentity(
        BgIndividualIncomePayment a, BgIndividualIncomePayment b) {
      return !Objects.equals(
              Boolean.TRUE.equals(a.getIsForeign()), Boolean.TRUE.equals(b.getIsForeign()))
          || !Objects.equals(a.getIdentifierType(), b.getIdentifierType())
          || !Objects.equals(a.getCountryCode(), b.getCountryCode());
    }

    BgArt73Para1Line line() {
      boolean aggregate = Boolean.TRUE.equals(type.getIsAggregate());
      boolean delete = CORRECTION_DELETE.equals(correctionOf(first));

      // ⚠️ Колона 14 се пълни от СВОЯ флаг, не от „сборен ред“: 820 е сборен и брой лица НЯМА.
      Integer personCount =
          Boolean.TRUE.equals(type.getReportsPersonCount()) && !delete ? persons.size() : null;

      return new BgArt73Para1Line(
          correctionOf(first),
          !aggregate && Boolean.TRUE.equals(first.getIsForeign()),
          aggregate ? null : first.getIdentifierType(),
          aggregate ? null : first.getCountryCode(),
          // ⚠️ Сборният ред НЕ сочи лице — указание т. 5 оставя колони 2–9 празни за него.
          aggregate ? null : first.getPartner(),
          type,
          // ⚠️ Заличаващият ред е БЕЗ суми, а не с нулеви — указание т. 2.
          delete ? null : gross,
          delete ? null : insurance,
          delete ? null : tax,
          personCount,
          payments,
          disagreement && !aggregate);
    }
  }

  /**
   * ИЗХОДЪТ — редовете плюс това, което НЕ е влязло.
   *
   * <p><b>⚠️ ДВЕТЕ ЧИСЛА СА ЧАСТ ОТ ОТГОВОРА, А НЕ СТАТИСТИКА.</b> Справка с 12 реда изглежда
   * еднакво, когато регистърът е имал 12 годни реда и когато е имал 15, от които 3 са без вид
   * доход. Първото е пълна справка; второто е непълна — а указание т. 6 казва, че справка, която не
   * съдържа всички изискуеми данни, „не се счита за достоверна и не се приема“. Върнати като число,
   * те се назовават в границите и човек ги вижда ПРЕДИ да подаде.
   */
  public static final class Result {

    private final List<BgArt73Para1Line> lines;
    private final int unusableCount;
    private final int otherFormCount;

    Result(List<BgArt73Para1Line> lines, int unusableCount, int otherFormCount) {
      this.lines = lines;
      this.unusableCount = unusableCount;
      this.otherFormCount = otherFormCount;
    }

    /** Редовете на Част ІІ, в реда, в който са се появили в регистъра. */
    public List<BgArt73Para1Line> getLines() {
      return Collections.unmodifiableList(lines);
    }

    /** Редове без вид доход или без дата на изплащане — не могат да бъдат поставени в справката. */
    public int getUnusableCount() {
      return unusableCount;
    }

    /**
     * Редове, чийто код НЕ е на тази справка.
     *
     * <p>⚠️ Това НЕ е дефект: кодовете 101–111 са на справката по чл. 73 ал. 6 и се събират оттам,
     * от фишовете. Числото се съобщава, за да се различи „регистърът беше празен“ от „регистърът
     * имаше редове, но не за тази справка“.
     */
    public int getOtherFormCount() {
      return otherFormCount;
    }
  }
}
