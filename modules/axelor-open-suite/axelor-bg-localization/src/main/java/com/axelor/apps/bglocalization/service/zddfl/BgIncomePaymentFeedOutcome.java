package com.axelor.apps.bglocalization.service.zddfl;

/**
 * ИЗХОДЪТ НА ЕДИН КАНДИДАТ — приет с двете си изведени стойности, или отказан с НАЗОВАНА причина.
 *
 * <p><b>⚠️ ПРИЧИНАТА ИДВА В ДВА ВИДА, И ТОВА НЕ Е ДУБЛИКАТ.</b> {@link #reason} е изречение за
 * човека, който чете границите на прогона; {@link #reasonCode} е стойност, по която прогонът БРОИ.
 * Броене по текста би се счупило при първата поправка на запетая, а показване на кода би оставило
 * оператора да гадае какво значи {@code NO_IDENTIFIER_TYPE}.
 */
public final class BgIncomePaymentFeedOutcome {

  // ═══ ПРИЧИНИТЕ ЗА ОТКАЗ ═════════════════════════════════════════════════════
  /** Кандидатът дойде без референция — програмна грешка, не състояние на данните. */
  public static final String NO_SOURCE_REF = "NO_SOURCE_REF";

  /** Няма получател: справката по чл. 73 ал. 1 назовава ЛИЦА и ред без лице е празен ред. */
  public static final String NO_PARTNER = "NO_PARTNER";

  /** Изходният документ не казва по кой код се подава доходът. */
  public static final String NO_INCOME_TYPE = "NO_INCOME_TYPE";

  /** Липсва датата на придобиване по чл. 11 — без нея не се знае в коя справка влиза редът. */
  public static final String NO_PAYMENT_DATE = "NO_PAYMENT_DATE";

  /** Брутото липсва или е отрицателно. */
  public static final String BAD_GROSS = "BAD_GROSS";

  /** Брутото е нула — доход не е изплатен, ред не се прави. ЗАКОННА нула, не дефект. */
  public static final String ZERO_GROSS = "ZERO_GROSS";

  /** Няма платец: ред без дружество остава извън справката и се брои там също. */
  public static final String NO_COMPANY = "NO_COMPANY";

  /** Типът на идентификатора не е нито обявен, нито изводим от проверимо ЕГН/ЛНЧ. */
  public static final String NO_IDENTIFIER_TYPE = "NO_IDENTIFIER_TYPE";

  private final boolean accepted;
  private final String reason;
  private final String reasonCode;
  private final String identifierType;
  private final boolean selfInsuredDeclared;

  private BgIncomePaymentFeedOutcome(
      boolean accepted,
      String reason,
      String reasonCode,
      String identifierType,
      boolean selfInsuredDeclared) {
    this.accepted = accepted;
    this.reason = reason;
    this.reasonCode = reasonCode;
    this.identifierType = identifierType;
    this.selfInsuredDeclared = selfInsuredDeclared;
  }

  static BgIncomePaymentFeedOutcome accept(String identifierType, boolean selfInsuredDeclared) {
    return new BgIncomePaymentFeedOutcome(true, null, null, identifierType, selfInsuredDeclared);
  }

  static BgIncomePaymentFeedOutcome refuse(String reasonCode, String reason) {
    return new BgIncomePaymentFeedOutcome(false, reason, reasonCode, null, false);
  }

  public boolean isAccepted() {
    return accepted;
  }

  /** Пълно изречение за човека, или {@code null} при приет кандидат. */
  public String getReason() {
    return reason;
  }

  /** Стойност за броене, или {@code null} при приет кандидат. */
  public String getReasonCode() {
    return reasonCode;
  }

  /** Колона 3 — изведена от лицето. {@code null} при отказ. */
  public String getIdentifierType() {
    return identifierType;
  }

  /**
   * Чл. 43 ал. 5 — отпада ли удържането за ТОВА плащане.
   *
   * <p>⚠️ Изведена стойност, не пренесена: при повторен прогон се смята наново, защото декларацията
   * може да е подадена, поправена или заличена между двата прогона.
   */
  public boolean isSelfInsuredDeclared() {
    return selfInsuredDeclared;
  }
}
