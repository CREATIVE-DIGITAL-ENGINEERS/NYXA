package com.axelor.apps.bglocalization.service.zddfl;

import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import java.time.LocalDate;

/**
 * ОТ ИЗХОДЕН ДОКУМЕНТ ДО РЕД В РЕГИСТЪРА — <b>решенията, които са ПРАВИЛА</b>.
 *
 * <p><b>Чист клас, без база и без Axelor.</b> По образеца на {@link BgIncomeTaxResolver} и {@code
 * BgArt73Para6Aggregator}: правилата се проверяват с дати и числа, а обектите се пълнят от {@code
 * BgIncomePaymentFeedService}. Така „декларация от 15 октомври НЕ отменя удържането по плащане от
 * 10 март“ е твърдение, което не иска нито схема, нито екран.
 *
 * <p><b>═══ ⚠️ КЪДЕ СВЪРШВА ТОЗИ КЛАС И ЗАЩО ТОЧНО ТАМ ═══</b>
 *
 * <p>Тук се случват само двата въпроса, на които изходният документ отговаря, а каталогът — не:
 * <b>годен ли е кандидатът</b> и <b>отпада ли удържането за ТОВА плащане</b>. Аритметиката —
 * разходите, основата и данъкът — е на {@link BgIncomeTaxResolver} и се вика през {@link
 * BgIndividualIncomePaymentService#computeInto}, точно както при ръчния ред. Преписана тук, тя щеше
 * да е ВТОРА сметка по същите членове: ръчно въведеният и авто-захранваният ред за едно и също
 * плащане биха могли да дадат различни числа, а разликата се вижда едва в справката.
 *
 * <p><b>═══ ⚠️ ЧЛ. 43 АЛ. 5 ИСКА ДВЕ УСЛОВИЯ И ЕДНА ДАТА ═══</b>
 *
 * <p>Дословно: „Алинея 4 не се прилага, когато лицето, придобиващо дохода, <b>е самоосигуряващо се
 * лице</b> по смисъла на Кодекса за социално осигуряване <b>и декларира това обстоятелство с
 * писмена декларация</b> пред платеца на дохода.“
 *
 * <ul>
 *   <li><b>Двете условия са слети в ЕДНО поле на лицето</b> — {@code
 *       Partner.bgSelfInsuredDeclaredOn}. Датата съществува само когато декларацията е подадена, а
 *       декларацията твърди самоосигуряването. Изведени поотделно, първото щеше да дойде от {@code
 *       BgSelfInsuredRegistration}, който виси на {@code Employee}, а получателят по фактура е
 *       {@code Partner} — тоест признак, който за половината получатели по устройство е празен.
 *   <li><b>Датата се СРАВНЯВА, а не само се проверява за наличие.</b> Декларация, подадена след
 *       плащането, не заварва плащането: в деня, в който платецът е плащал, той е бил длъжен да
 *       удържи по ал. 4. Прочетена като флаг, октомврийска декларация би отменила удържането и по
 *       мартенското плащане — със задна дата, върху вече внесен данък и вече подадена декларация по
 *       чл. 55 за първото тримесечие.
 *   <li><b>САМО при шаблон A.</b> Ал. 5 отменя ал. 4 на СЪЩИЯ член, тоест обхватът ѝ са доходите по
 *       чл. 29. Чл. 44 (наем) няма съответна алинея — неговата ал. 4 е безусловна, тоест
 *       самоосигуряващ се НАЕМОДАТЕЛ не е изключение и платецът му удържа като на всеки друг. Чл.
 *       44а — също не. Пренесено „по симетрия“, правилото би оставило данък неудържан без норма.
 * </ul>
 *
 * <p><b>═══ ⚠️ ТИПЪТ НА ИДЕНТИФИКАТОРА: ДВЕ ИЗВОДИМИ СТОЙНОСТИ ОТ ЧЕТИРИ ═══</b>
 *
 * <p>{@code identifierType} е {@code required} на регистъра, а авто-захранването няма ръка. ЕГН и
 * ЛНЧ носят контролна цифра и се проверяват ({@link BgIdentifierValidator}) — затова празно поле
 * при проверимо ЕГН се чете „0“, а при проверимо ЛНЧ — „1“. БУЛСТАТ („2“) и чуждестранното лице без
 * идентификатор („3“) нямат такъв признак: първото се различава от ЕГН само по дължина, второто по
 * определение няма какво да се провери. Затова при тях се ОТКАЗВА с назована причина, вместо да се
 * познава: правдоподобна колона 3 върху ред, който НАП отхвърля, е по-скъпа от преброен отказ.
 *
 * <p><b>═══ ⚠️ ГРАНИЦА, КОЯТО ТОЗИ КЛАС НЕ ПРЕМИНАВА ═══</b>
 *
 * <p><b>Чуждестранното лице и държавата (колони 2 и 9) НЕ се извеждат.</b> {@code Partner} няма
 * признак, от който „чуждестранно лице по смисъла на чл. 4 и 5 ЗДДФЛ“ да се прочете: местният адрес
 * не е местно лице, а гражданството не е данъчна принадлежност. Затова захранването не пипа {@code
 * isForeign} и {@code countryCode} — при създаване те остават по подразбиране, а при повторен
 * прогон се ПАЗЯТ такива, каквито човек ги е оставил. Изведени по адрес, те биха дали „0“ на
 * чуждестранно лице — грешка, която образецът приема мълчаливо.
 */
public final class BgIncomePaymentFeedMapper {

  /** Източникът за ред от фиш — {@code bg.income.payment.source.select}. */
  public static final String SOURCE_PAYROLL = "PAYROLL";

  /** Източникът за ред от фактура — {@code bg.income.payment.source.select}. */
  public static final String SOURCE_INVOICE = "INVOICE";

  /** Колона 3, стойност „0“ — лице с ЕГН. */
  public static final String ID_TYPE_EGN = "0";

  /** Колона 3, стойност „1“ — лице с ЛН, ЛНЧ или Сл. № от регистъра на НАП. */
  public static final String ID_TYPE_OTHER = "1";

  /** Моделът в референцията за ред, дошъл от фиш. */
  public static final String MODEL_PAY_SLIP = "BgPaySlip";

  /** Моделът в референцията за ред, дошъл от плащане по фактура. */
  public static final String MODEL_INVOICE_PAYMENT = "InvoicePayment";

  /** Разделителят в референцията. Не се среща в имена на класове и в десетични id-та. */
  private static final String REF_SEPARATOR = "#";

  /** Парите се закръгляват до стотинка — спецификация § 5.3. */
  private static final int MONEY_SCALE = 2;

  /** Делът е МЕЖДИННО число и се пази с повече знаци — виж {@link #share}. */
  private static final int SHARE_SCALE = 10;

  private BgIncomePaymentFeedMapper() {}

  /**
   * ИДЕМПОТЕНТНИЯТ КЛЮЧ на един изходен документ: „{@code BgPaySlip#1234}“, „{@code
   * InvoicePayment#88}“.
   *
   * <p><b>⚠️ МОДЕЛЪТ Е В КЛЮЧА, А НЕ САМО ID-ТО.</b> Три източника пишат в един регистър и техните
   * id-та тръгват от единица поотделно: фиш 7 и плащане 7 са различни документа. Ключиран само по
   * число, вторият прогон би „намерил“ реда на първия и би го пренаписал с чужди суми.
   *
   * <p>Връща {@code null} при липсващо id — преходен обект още няма референция и не бива да получи
   * измислена.
   */
  public static String refOf(String model, Long id) {
    if (model == null || model.trim().isEmpty() || id == null) {
      return null;
    }
    return model.trim() + REF_SEPARATOR + id;
  }

  /**
   * ОТПАДА ЛИ УДЪРЖАНЕТО по чл. 43 ал. 5 за това плащане.
   *
   * @param taxPattern шаблонът от каталога — извън {@link BgIncomeTaxResolver#PATTERN_BUSINESS}
   *     правилото не важи
   * @param declaredOn датата на писмената декларация ({@code Partner.bgSelfInsuredDeclaredOn})
   * @param paymentDate датата на придобиване на дохода по чл. 11
   */
  public static boolean selfInsuredDeclared(
      String taxPattern, LocalDate declaredOn, LocalDate paymentDate) {
    if (!BgIncomeTaxResolver.PATTERN_BUSINESS.equals(trim(taxPattern))) {
      return false;
    }
    if (declaredOn == null || paymentDate == null) {
      return false;
    }
    // „не по-късно от“ — декларация, подадена В ДЕНЯ на плащането, заварва плащането.
    return !declaredOn.isAfter(paymentDate);
  }

  /**
   * ТИПЪТ НА ИДЕНТИФИКАТОРА — обявеният, или изводимият от проверимо ЕГН/ЛНЧ.
   *
   * <p>Връща {@code null}, когато нито едното не е налице; извикващият го превръща в назован отказ.
   */
  public static String identifierType(String declared, String registrationCode) {
    String explicit = trim(declared);
    if (explicit != null && !explicit.isEmpty()) {
      return explicit;
    }
    String code = trim(registrationCode);
    if (code == null || code.isEmpty()) {
      return null;
    }
    if (BgIdentifierValidator.isValidEgn(code)) {
      return ID_TYPE_EGN;
    }
    return BgIdentifierValidator.isValidLnch(code) ? ID_TYPE_OTHER : null;
  }

  /** Годен ли е кандидатът, и с кои две изведени стойности. */
  public static BgIncomePaymentFeedOutcome decide(BgIncomePaymentFeedCandidate in) {
    if (in == null) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_SOURCE_REF, "Кандидатът липсва.");
    }
    if (trim(in.sourceRef) == null || trim(in.sourceRef).isEmpty()) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_SOURCE_REF,
          "Изходният документ няма референция — без нея повторният прогон не може да намери своя"
              + " ред и би създал втори.");
    }
    if (!in.hasPartner) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_PARTNER,
          "Изходният документ не сочи лице (Partner). Справката по чл. 73, ал. 1 назовава"
              + " ПОЛУЧАТЕЛИ в колони 4–8; ред без лице би влязъл в нея празен. За граждански"
              + " договор лицето се чете от служителя (Employee.contactPartner) — попълни го там.");
    }
    if (trim(in.taxPattern) == null) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_INCOME_TYPE,
          "Изходният документ не казва вид доход по ЗДДФЛ. Без него не се знае нито по кой член се"
              + " смята данъкът, нито с кой код се подава редът — а подразбиране няма: 25, 40 и 60"
              + " на сто разходи са три различни числа по чл. 29, ал. 1.");
    }
    if (in.paymentDate == null) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_PAYMENT_DATE,
          "Липсва датата на изплащане. Чл. 11, ал. 1 връзва придобиването с ДЕНЯ на плащането, а той"
              + " определя и годината на справката по чл. 73, и тримесечието на декларацията по"
              + " чл. 55. Изведена от периода, датата би сложила доход в грешно тримесечие.");
    }
    if (in.grossAmount == null || in.grossAmount.signum() < 0) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.BAD_GROSS,
          "Брутният размер е "
              + (in.grossAmount == null ? "празен" : in.grossAmount.toPlainString())
              + " — изплатен доход не може да е под нула. Заличаване се подава с код корекция „8“.");
    }
    if (in.grossAmount.signum() == 0) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.ZERO_GROSS,
          "Брутният размер е нула — доход не е изплатен и ред НЕ се създава. Това е законна нула, а"
              + " не липсващи данни: ред с нула в колона 11 би твърдял изплатен доход.");
    }
    if (!in.hasCompany) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_COMPANY,
          "Изходният документ не сочи платец. Справката по чл. 73, ал. 1 се подава ОТ платеца и в"
              + " Част І носи неговия ЕИК; ред без него остава извън нея.");
    }

    String identifierType = identifierType(in.identifierType, in.registrationCode);
    if (identifierType == null) {
      return BgIncomePaymentFeedOutcome.refuse(
          BgIncomePaymentFeedOutcome.NO_IDENTIFIER_TYPE,
          "Типът на идентификатора не е обявен на лицето и не се извежда от номера му. „0“ (ЕГН) и"
              + " „1“ (ЛН, ЛНЧ, Сл. №) имат контролна цифра и се разпознават; „2“ (БУЛСТАТ) и „3“"
              + " (чуждестранно лице без идентификатор) — не. Попълни „Тип идентификатор“ на"
              + " партньора.");
    }

    return BgIncomePaymentFeedOutcome.accept(
        identifierType,
        selfInsuredDeclared(in.taxPattern, in.selfInsuredDeclaredOn, in.paymentDate));
  }

  // ═══ ЧАСТИЧНОТО ПЛАЩАНЕ ═════════════════════════════════════════════════════

  /**
   * ДЕЛЪТ НА ЕДНО ПЛАЩАНЕ в общата сума на фактурата — {@code null}, когато не се определя.
   *
   * <p><b>⚠️ ПРИДОБИТОТО Е ПЛАТЕНОТО, НЕ НАЧИСЛЕНОТО.</b> Чл. 11 ал. 1 връзва придобиването с
   * плащането. Взето цялото по фактурата, първата вноска по разсрочено плащане би обложила доход,
   * който лицето още не е получило — и то в тримесечие, което не е неговото.
   *
   * <p><b>⚠️ ДЕЛИ СЕ НА СУМАТА С ДАНЪК, А СЕ ПРИЛАГА ВЪРХУ СУМАТА БЕЗ ДАНЪК.</b> Плащането се
   * превежда за фактурираното (с ДДС, ако лицето е регистрирано), а облагаемият доход по ЗДДФЛ е
   * данъчната основа: ДДС-то е задължение към бюджета, не доход. При нерегистрирано лице двете
   * съвпадат и делът излиза точно 1.
   *
   * <p>⚠️ Мащаб 10 знака: делът е МЕЖДИННО число. Закръглен тук до стотинка, той би внесъл грешка
   * от порядъка на левове при голяма фактура; закръглянето е чак след умножението — виж {@link
   * #incomeFor}.
   */
  public static java.math.BigDecimal share(
      java.math.BigDecimal paidAmount, java.math.BigDecimal invoiceTotal) {
    if (paidAmount == null || invoiceTotal == null || invoiceTotal.signum() == 0) {
      return null;
    }
    return paidAmount.divide(invoiceTotal, SHARE_SCALE, java.math.RoundingMode.HALF_UP);
  }

  /**
   * Придобитият доход за един вид доход: делът на плащането върху сумата на редовете, без данък.
   */
  public static java.math.BigDecimal incomeFor(
      java.math.BigDecimal share, java.math.BigDecimal lineTotalWithoutTax) {
    if (share == null || lineTotalWithoutTax == null) {
      return java.math.BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
    return lineTotalWithoutTax
        .multiply(share)
        .setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
  }

  // ═══ ОБХВАТЪТ НА ЕДИН ПРОГОН ════════════════════════════════════════════════

  /**
   * Първият ден на обхвата: 1 януари, или първият ден на тримесечието.
   *
   * <p>⚠️ <b>ОБХВАТЪТ Е ФИЛТЪР, А НЕ КЛЮЧ.</b> Той решава кои изходни документи се четат, и нищо
   * повече: своят ред се намира по референцията ({@link #refOf}). Затова прогон за цялата година и
   * прогон само за второто тримесечие пипат едни и същи редове и не се сблъскват.
   *
   * <p>⚠️ И живее ТУК, при правилата, а не при услугата: чл. 55 е тримесечна, чл. 73 — годишна, и
   * границите на тримесечието се проверяват с две дати, без Postgres.
   *
   * @param quarter {@code null} значи ЦЯЛАТА година
   */
  public static java.time.LocalDate firstDayOf(int taxYear, Integer quarter) {
    return quarter == null
        ? java.time.LocalDate.of(taxYear, 1, 1)
        : java.time.LocalDate.of(taxYear, 1, 1).plusMonths(3L * (quarter - 1));
  }

  /** Последният ден на обхвата: 31 декември, или последният ден на тримесечието. */
  public static java.time.LocalDate lastDayOf(int taxYear, Integer quarter) {
    return quarter == null
        ? java.time.LocalDate.of(taxYear, 12, 31)
        : firstDayOf(taxYear, quarter).plusMonths(3).minusDays(1);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
