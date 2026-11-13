package com.axelor.apps.bglocalization.service.art55;

import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * РЕГИСТЪРЪТ НА ИЗПЛАТЕНИТЕ ДОХОДИ ЗА ЕДНО ТРИМЕСЕЧИЕ → ТРИТЕ РЕДА НА ЧАСТ ІV, КОЛОНА 3 ОТ ОБРАЗЕЦ
 * 4001 („Декларация по чл. 55, ал. 1 от ЗДДФЛ и чл. 201, ал. 1 от ЗКПО за дължими данъци“).
 *
 * <p>Чиста функция: без база и без Guice — по образеца на {@code BgArt73Para1Aggregator}. Прочитът
 * от базата, заглавната част и закачането към транспорта живеят в {@link
 * BgArt55DeclarationService}; тук се случва само разпределянето по редове и сумирането, за да
 * остане проверимо без Postgres.
 *
 * <p><b>═══ ПО КОЕ СЕ ГРУПИРА ═══</b>
 *
 * <p>По <b>ТРИМЕСЕЧИЕ + ВИД ДАНЪК</b>, и двете задължителни:
 *
 * <ul>
 *   <li><b>ТРИМЕСЕЧИЕ</b> — Част І, т. 3.2 на образеца иска едно от четирите тримесечия, а чл. 56
 *       ал. 1 връзва подаването със срока за внасяне. Едно (година, тримесечие, платец) е ЕДНА
 *       декларация — оттам и уникалността на заглавната част.
 *   <li><b>ВИД ДАНЪК</b> — Част ІV има отделен ред за авансовия и за окончателния данък и те се
 *       внасят по различни разпоредби. Слети в едно число, редовете на образеца не могат да бъдат
 *       възстановени обратно.
 * </ul>
 *
 * <p><b>⚠️ ЛИЦЕТО НЕ Е В КЛЮЧА, И ТОВА Е РАЗЛИКАТА СРЕЩУ СПРАВКАТА ПО ЧЛ. 73.</b> Част ІV е
 * ОБОБЩЕНА: тя не назовава нито един получател, а само дължимия данък по вид. Затова изходът е три
 * числа, а не списък от редове. Справката по чл. 73 ал. 1 назовава лицата — тя е другият образец и
 * другият срок.
 *
 * <p><b>═══ КОЛОНА 3, НЕ КОЛОНА 4 ═══</b>
 *
 * <p>Част ІV има две колони: к. 3 „от ПЛАТЕЦ на дохода“ и к. 4 „от ПОЛУЧАТЕЛ на дохода“. Този
 * строител пълни САМО к. 3, защото нашето дружество е платецът. Указание т. 5 към образеца,
 * ДОСЛОВНО: „Част ІV, ред 1 в колона 3 се попълва от предприятия и самоосигуряващи се лица,
 * задължени да УДЪРЖАТ и внасят авансов данък по чл. 43, ал. 4, чл. 44, ал. 4 и/или чл. 44а от
 * ЗДДФЛ“. Указание т. 8 дава к. 4 на физическите лица, които внасят данъка САМИ — а те подават своя
 * декларация, отбелязана в Част І, т. 2, не тази.
 *
 * <p><b>═══ КОЕ НЕ ВЛИЗА ═══</b>
 *
 * <p><b>1. ТРУДОВИТЕ ДОХОДИ.</b> Чл. 55 ал. 3, ДОСЛОВНО: „В декларацията по ал. 1 НЕ се посочва
 * дължимият от работодателите данък за доходи от трудови правоотношения, както и дължимият
 * алтернативен данък по чл. 41а, за които се подава информация по ред и по начин, определени в
 * наредба, издадена от министъра на финансите.“ ⚠️ Отделянето става по {@code
 * BgIncomeType.declarations}, а НЕ по списък „101–111“ в Java — по същия довод като при справката
 * по чл. 73: коя номенклатура кой код обслужва се сменя със заповед на изпълнителния директор на
 * НАП и списък в код би оцелял следващата заповед мълчаливо.
 *
 * <p><b>2. РЕДОВЕТЕ С {@code taxKind = NONE}.</b> Данък не е удържан — няма какво да се декларира в
 * к. 3. Три различни причини стоят зад нулата (необлагаем доход по шаблон F, доход по чл. 33 по
 * шаблон D, и декларирало СОЛ по чл. 43 ал. 5) и трите се броят поотделно, за да не изчезнат тихо.
 *
 * <p><b>3. ДЕКЛАРИРАЛОТО СОЛ.</b> Чл. 43 ал. 5 и 6 — платецът не удържа, лицето внася само. ⚠️
 * Проверява се ИЗРИЧНО, а не само през {@code taxKind}: {@code BgIncomeTaxResolver} наистина стъпва
 * на {@code NONE} за такъв ред, но регистърът приема и РЪЧНО въведени редове, при които двете
 * полета могат да си противоречат. Данък, удържан от платеца, и „лицето го внася само“ не могат да
 * са верни едновременно; при спор побеждава признакът на ЛИЦЕТО, защото той е този, за който има
 * писмена декларация по чл. 43 ал. 5.
 *
 * <p><b>4. ПЛАЩАНИЯТА ИЗВЪН ТРИМЕСЕЧИЕТО</b> — по {@code paymentDate}, датата на ПРИДОБИВАНЕ на
 * дохода по чл. 11. Плащане от април е във ВТОРОТО тримесечие и има своя декларация, не се долепя
 * към първото.
 *
 * <p><b>═══ ЧЕТВЪРТОТО ТРИМЕСЕЧИЕ ═══</b>
 *
 * <p>Виж {@link #suppressedInFourthQuarter} — чл. 65 <b>ал. 13</b> (не ал. 12) изключва авансовия
 * данък от удържане за Q4.
 */
public final class BgArt55Aggregator {

  /**
   * Стойността на {@code BgIncomeType.declarations} за извънтрудовите доходи.
   *
   * <p>⚠️ Признакът се чете „НЕ е на справката по ал. 6“, а не „е на образец 4001“: каталогът
   * описва двете справки по чл. 73, не тази декларация. Чл. 55 ал. 3 обаче изважда точно трудовите
   * доходи, а те са именно съдържанието на ал. 6 — тоест двете множества съвпадат и признакът е
   * годен. При появата на трети образец това съвпадение спира да важи и разделянето иска свое поле.
   */
  public static final String DECLARATION_EMPLOYMENT = "73-6";

  /** {@code BgIncomeTaxResolver.TAX_ADVANCE} — авансов данък по чл. 43, чл. 44 и чл. 44а. */
  public static final String TAX_ADVANCE = "ADVANCE";

  /** {@code BgIncomeTaxResolver.TAX_FINAL} — окончателен данък по глава шеста. */
  public static final String TAX_FINAL = "FINAL";

  /**
   * ЧЕТВЪРТОТО тримесечие — единственото, в което ред 1 не се попълва. Виж {@link
   * #suppressedInFourthQuarter}.
   */
  public static final int FOURTH_QUARTER = 4;

  /**
   * РЕД 2 се разпознава по РАЗПОРЕДБАТА, а не по кода на дохода.
   *
   * <p>⚠️ И ТОВА Е ОБМИСЛЕНО РАЗЛИЧИЕ СПРЯМО {@code BgArt73Para1ReportService}, който изброява
   * кодовете 814 и 8141. Причината е чия е номенклатурата: кодовете са на СПРАВКАТА по чл. 73 и се
   * утвърждават с нейната заповед, докато образец 4001 не ги споменава нито веднъж — ред 2 е
   * определен с думите „по чл. 38, ал. 1 от ЗДДФЛ“. Ключирано по код, разпределянето би се счупило
   * при следващата преномерация на ЧУЖДА номенклатура; ключирано по член, то следва това, което
   * образецът действително пише.
   */
  private static final String ARTICLE_DIVIDENDS = "чл.38,ал.1";

  /**
   * Разпоредбите, при които окончателният данък се внася ОТ ПОЛУЧАТЕЛЯ, а не се удържа от платеца.
   *
   * <p>Чл. 65 ал. 6, ДОСЛОВНО: „Данъкът по чл. 46 за доходите по чл. 37, ал. 1, т. 10 - 12 и чл.
   * 38, ал. 5 се внася по реда на чл. 67 от лицето, придобило дохода.“ Указание т. 8 към образеца
   * ги праща в колона 4.
   *
   * <p>⚠️ СЛУЖИ САМО ЗА БРОЕНЕ, НЕ ЗА ИЗКЛЮЧВАНЕ. Каталогът носи свой признак за това ({@code
   * withheldByPayer}) и той е този, на който стъпва {@code BgIncomeTaxResolver}. Дублирано тук като
   * правило, разпределянето би имало два източника на истина. Броят се, защото признакът, който
   * работи, е в БАЗАТА, а тя се обновява от App Install, не от комита: seed-ът вече ги носи с
   * {@code withheldByPayer = false}, но до пускането му редът пак стига дотук с {@code FINAL} — виж
   * {@link BgArt55Boundaries}.
   */
  private static final List<String> ARTICLES_PAID_BY_RECIPIENT =
      Collections.unmodifiableList(
          Arrays.asList("чл.37,ал.1,т.10", "чл.37,ал.1,т.11", "чл.37,ал.1,т.12", "чл.38,ал.5"));

  private BgArt55Aggregator() {}

  /**
   * ТРИМЕСЕЧИЕТО НА ДАТАТА — Част І, т. 3.2.
   *
   * <p>I-во: януари–март, II-ро: април–юни, III-то: юли–септември, IV-то: октомври–декември.
   */
  public static int quarterOf(LocalDate date) {
    return date == null ? 0 : ((date.getMonthValue() - 1) / 3) + 1;
  }

  /**
   * ПОТИСКА ЛИ СЕ АВАНСОВИЯТ ДАНЪК ЗА ТОВА ТРИМЕСЕЧИЕ.
   *
   * <p>Чл. 65 <b>ал. 13</b>, ДОСЛОВНО: „Не се удържа и внася авансов данък по чл. 43, ал. 4, чл.
   * 44, ал. 4 и чл. 44а, ал. 1 за доходи, придобити през ЧЕТВЪРТОТО ТРИМЕСЕЧИЕ на данъчната година,
   * освен когато лицето, придобиващо дохода, писмено декларира пред предприятието или
   * самоосигуряващото се лице - платец на дохода, че желае удържане на данък. В тези случаи
   * удържаният данък се внася от платеца на дохода в срок до 31 януари на следващата година.“
   *
   * <p><b>⚠️ АЛ. 13, А НЕ АЛ. 12 — сверено срещу ЗДДФЛ (ред. ДВ бр. 30 от 2026 г.).</b> Ал. 12 е
   * СРОКЪТ за внасяне на същия този авансов данък („до края на месеца, следващ тримесечието на
   * придобиване на дохода“), а изключението за Q4 е следващата алинея. Двете се бъркат лесно,
   * защото изброяват едни и същи три разпоредби.
   *
   * <p><b>⚠️ И ЧЛ. 44а Е ВЪТРЕ.</b> Ал. 13 назовава и трите — чл. 43 ал. 4, чл. 44 ал. 4 И чл. 44а
   * ал. 1 — тоест ред 1 за Q4 е нула ИЗЦЯЛО, а не само по частта за стопанска дейност и наем.
   *
   * <p><b>⚠️ ИЗКЛЮЧЕНИЕТО НА ИЗКЛЮЧЕНИЕТО НЕ Е ПРЕДСТАВИМО ДНЕС</b> и затова не се гадае:
   * регистърът няма поле „лицето е поискало писмено удържане“. Потиснатите редове се БРОЯТ и сумата
   * им се назовава в границите, за да може човек да я върне ръчно — виж {@link
   * Result#getQ4SuppressedAmount}. Мълчаливо занулена, тя би изчезнала без следа.
   */
  public static boolean suppressedInFourthQuarter(int quarter) {
    return quarter == FOURTH_QUARTER;
  }

  /**
   * ТРИТЕ РЕДА НА ЧАСТ ІV, КОЛОНА 3 за подадените плащания, данъчната година и тримесечието.
   *
   * <p>⚠️ Сумира {@code taxAmount} — УДЪРЖАНИЯ данък, не основата и не брутото. Част ІV е за
   * „размер на дължимия данък“ и нищо друго от реда на регистъра не влиза в нея.
   */
  public static Result aggregate(
      List<BgIndividualIncomePayment> payments, int taxYear, int quarter) {
    Result out = new Result();
    if (payments == null) {
      return out;
    }

    for (BgIndividualIncomePayment payment : payments) {
      if (payment == null) {
        continue;
      }
      BgIncomeType type = payment.getIncomeType();
      if (type == null || payment.getPaymentDate() == null) {
        // ⚠️ Непълен ред: без вид доход не се знае кой ред на Част ІV го чака, без дата — кое
        // тримесечие. Не се приписва по подразбиране, а се брои.
        out.unusable++;
        continue;
      }
      if (DECLARATION_EMPLOYMENT.equals(type.getDeclarations())) {
        // Чл. 55 ал. 3 — трудовите доходи не се посочват в тази декларация.
        out.employment++;
        continue;
      }
      if (!inPeriod(payment, taxYear, quarter)) {
        continue;
      }

      // ⚠️ Редът се брои за периода ПРЕДИ да бъде разпределен: числата в границите описват
      // тримесечието, а не само това, което е влязло в трите реда.
      out.periodRows++;

      if (Boolean.TRUE.equals(payment.getSelfInsuredDeclared())) {
        // Чл. 43 ал. 5 и 6 — колона 4, чужда декларация. Виж класовия коментар, т. 3.
        out.selfInsured++;
        continue;
      }

      // ⚠️ БЕЗ проверка за null: taxAmount е decimal с default="0" и генерираният getter връща
      // ZERO за непопълнено поле — виж BgDefaultedGetterTest.
      BigDecimal tax = payment.getTaxAmount();
      String kind = payment.getTaxKind();

      if (TAX_ADVANCE.equals(kind)) {
        if (suppressedInFourthQuarter(quarter)) {
          out.q4SuppressedRows++;
          out.q4SuppressedAmount = out.q4SuppressedAmount.add(tax);
          continue;
        }
        out.row1 = out.row1.add(tax);
        out.row1Rows++;
        continue;
      }

      if (TAX_FINAL.equals(kind)) {
        if (articleIs(type.getArticle(), ARTICLE_DIVIDENDS)) {
          out.row2 = out.row2.add(tax);
          out.row2Rows++;
        } else {
          out.row3 = out.row3.add(tax);
          out.row3Rows++;
          if (paidByRecipient(type.getArticle())) {
            out.recipientPaidFinal++;
          }
        }
        continue;
      }

      // taxKind = NONE — необлагаем доход, чл. 33 или код, който платецът не облага.
      out.notWithheld++;
    }
    return out;
  }

  /** Попада ли плащането в данъчната година И в тримесечието — по {@code paymentDate}. */
  public static boolean inPeriod(BgIndividualIncomePayment payment, int taxYear, int quarter) {
    if (payment == null || payment.getPaymentDate() == null) {
      return false;
    }
    LocalDate date = payment.getPaymentDate();
    return date.getYear() == taxYear && quarterOf(date) == quarter;
  }

  /**
   * СРАВНЯВА РАЗПОРЕДБАТА нечувствително към разредката.
   *
   * <p><b>⚠️ СЛЕД ПРЕФИКСА СЕ ИСКА НЕ-ЦИФРА, И ТОВА НЕ Е ПРЕДПАЗЛИВОСТ.</b> „чл. 38, ал. 12“ (код
   * 820, наем при етажна собственост) започва със същите знаци като „чл. 38, ал. 1“ (дивидентите).
   * Сравнени като чист префикс, наемът по ал. 12 би влязъл в РЕД 2 при дивидентите — с 10 на сто
   * ставка срещу 5 на сто и с изрично забранена колона 4 на образеца.
   */
  static boolean articleIs(String article, String normalizedPrefix) {
    String normalized = normalize(article);
    if (normalized == null || !normalized.startsWith(normalizedPrefix)) {
      return false;
    }
    if (normalized.length() == normalizedPrefix.length()) {
      return true;
    }
    return !Character.isDigit(normalized.charAt(normalizedPrefix.length()));
  }

  /** Внася ли се окончателният данък от получателя — чл. 65 ал. 6. Само за броене. */
  static boolean paidByRecipient(String article) {
    for (String prefix : ARTICLES_PAID_BY_RECIPIENT) {
      if (articleIs(article, prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Маха разредката и уеднаквява регистъра, за да не решава форматирането коя разпоредба е коя. */
  private static String normalize(String article) {
    if (article == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(article.length());
    for (char symbol : article.toCharArray()) {
      if (!Character.isWhitespace(symbol)) {
        out.append(Character.toLowerCase(symbol));
      }
    }
    return out.length() == 0 ? null : out.toString();
  }

  /**
   * ИЗХОДЪТ — трите реда на колона 3 плюс това, което НЕ е влязло в тях.
   *
   * <p><b>⚠️ ЧИСЛАТА ЗА НЕВЛЯЗЛОТО СА ЧАСТ ОТ ОТГОВОРА, А НЕ СТАТИСТИКА.</b> Декларация с нула на
   * ред 1 изглежда ЕДНАКВО, когато авансов данък не е удържан и когато е удържан, но е бил потиснат
   * заради Q4 или отклонен като чужда колона. Първото е вярна декларация; второто е сума, която
   * човек трябва да види, преди да поеме отговорността по чл. 82 ЗДДФЛ.
   */
  public static final class Result {

    private BigDecimal row1 = BigDecimal.ZERO;
    private BigDecimal row2 = BigDecimal.ZERO;
    private BigDecimal row3 = BigDecimal.ZERO;
    private BigDecimal q4SuppressedAmount = BigDecimal.ZERO;

    private int row1Rows;
    private int row2Rows;
    private int row3Rows;
    private int periodRows;
    private int unusable;
    private int employment;
    private int selfInsured;
    private int notWithheld;
    private int q4SuppressedRows;
    private int recipientPaidFinal;

    Result() {}

    /**
     * РЕД 1, К. 3 — авансовият данък по чл. 43, чл. 44 и чл. 44а, трите на един ред.
     *
     * <p>⚠️ Нула за четвъртото тримесечие по чл. 65 ал. 13, дори когато регистърът носи удържан
     * данък — тогава {@link #getQ4SuppressedAmount} казва колко е потиснато.
     */
    public BigDecimal getRow1() {
      return row1;
    }

    /** РЕД 2, К. 3 — окончателният данък за дивиденти и ликвидационни дялове по чл. 38 ал. 1. */
    public BigDecimal getRow2() {
      return row2;
    }

    /**
     * РЕД 3, К. 3 — окончателният данък по чл. 37 и чл. 38 БЕЗ дивидентите от ред 2.
     *
     * <p>⚠️ Образецът изключва от този ред и „дължимия данък по чл. 67, ал. 4“ — доходите от
     * ИЗТОЧНИК В ЧУЖБИНА, придобити от местно физическо лице, които се внасят до 30 април и се
     * декларират в годишната данъчна декларация по чл. 50. Регистърът няма признак за източника на
     * дохода и това изключване НЕ е направено — виж {@link BgArt55Boundaries}.
     */
    public BigDecimal getRow3() {
      return row3;
    }

    /** Редове на регистъра, влезли в ред 1. */
    public int getRow1Rows() {
      return row1Rows;
    }

    /** Редове на регистъра, влезли в ред 2. */
    public int getRow2Rows() {
      return row2Rows;
    }

    /** Редове на регистъра, влезли в ред 3. */
    public int getRow3Rows() {
      return row3Rows;
    }

    /** Всички редове на регистъра, попаднали в тримесечието — включително неразпределените. */
    public int getPeriodRows() {
      return periodRows;
    }

    /**
     * Редове без вид доход или без дата — не могат да бъдат поставени нито в ред, нито в период.
     */
    public int getUnusableCount() {
      return unusable;
    }

    /** Редове с трудови доходи — чл. 55 ал. 3 ги изважда от тази декларация. */
    public int getEmploymentCount() {
      return employment;
    }

    /** Редове на декларирало СОЛ по чл. 43 ал. 5 — колона 4, не колона 3. */
    public int getSelfInsuredCount() {
      return selfInsured;
    }

    /** Редове с {@code taxKind = NONE} — данък не е удържан и няма какво да се декларира. */
    public int getNotWithheldCount() {
      return notWithheld;
    }

    /** Редове с авансов данък, потиснати заради четвъртото тримесечие (чл. 65 ал. 13). */
    public int getQ4SuppressedRows() {
      return q4SuppressedRows;
    }

    /**
     * СУМАТА, ПОТИСНАТА ПО ЧЛ. 65 АЛ. 13.
     *
     * <p>⚠️ Тя е ОТГОВОРЪТ на въпроса „защо ред 1 е нула, като регистърът носи данък“. Ако лицето е
     * поискало писмено удържане, точно това число се връща на ред 1 ръчно — и се внася до 31 януари
     * на следващата година, не до края на януари по общия срок на ал. 12.
     */
    public BigDecimal getQ4SuppressedAmount() {
      return q4SuppressedAmount;
    }

    /**
     * Редове на ред 3, чиято разпоредба е сред тези по чл. 65 ал. 6 — данъкът се внася от
     * ПОЛУЧАТЕЛЯ.
     */
    public int getRecipientPaidFinalCount() {
      return recipientPaidFinal;
    }

    /** Има ли изобщо какво да се декларира в Част ІV — трите реда заедно. */
    public boolean isEmpty() {
      return row1.signum() == 0 && row2.signum() == 0 && row3.signum() == 0;
    }
  }
}
