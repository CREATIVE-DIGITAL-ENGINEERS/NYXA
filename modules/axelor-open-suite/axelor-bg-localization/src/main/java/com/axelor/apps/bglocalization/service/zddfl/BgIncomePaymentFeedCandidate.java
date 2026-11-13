package com.axelor.apps.bglocalization.service.zddfl;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ЕДИН КАНДИДАТ ЗА РЕД В РЕГИСТЪРА — ПЛОСЪК, без нито един Axelor тип.
 *
 * <p><b>Защо не се подава самият изходен документ.</b> Кандидатите идват от ТРИ различни носителя —
 * фиш по граждански договор, плащане по фактура за услуга, плащане по фактура за наем — и трите
 * отговарят на едни и същи въпроси: кой е получателят, кой е кодът, колко е брутото, кога е
 * придобит доходът. Подадени като документи, правилото щеше да иска и трите класа, тоест и базата,
 * и Guice, и Hibernate — а въпросът „декларация от 15 октомври отменя ли удържането по плащане от
 * 10 март“ се проверява с две дати и нищо друго.
 *
 * <p>⚠️ <b>СТАВКИТЕ ГИ НЯМА ТУК, И ТОВА Е НАРОЧНО.</b> Аритметиката е на {@link
 * BgIncomeTaxResolver} и се захранва от {@link BgIncomeTaxRequest} — той вече носи разходната
 * ставка, авансовата и окончателната. Второ им копие тук би поканило втора сметка. От каталога този
 * клас взима САМО шаблона, защото шаблонът решава ПРАВНИ въпроси (действа ли чл. 43 ал. 5), а не
 * аритметични.
 */
public final class BgIncomePaymentFeedCandidate {

  /** {@code PAYROLL} или {@code INVOICE} — стойност на {@code bg.income.payment.source.select}. */
  public final String source;

  /** „{@code <Модел>#<id>}“ — идемпотентният ключ. Виж {@link BgIncomePaymentFeedMapper#refOf}. */
  public final String sourceRef;

  /** Датата на ПРИДОБИВАНЕ по чл. 11 — на плащането, не на документа. */
  public final LocalDate paymentDate;

  /** Колона 11 — брутният размер, преди разходи и преди удръжки. */
  public final BigDecimal grossAmount;

  /** Колона 12 — удържаните задължителни осигурителни вноски. */
  public final BigDecimal insuranceAmount;

  /** Има ли получател. Самият {@code Partner} остава при услугата — тук е нужен само фактът. */
  public final boolean hasPartner;

  /** Има ли платец. Ред без платец не влиза в справката — виж {@code BgArt73Para1Boundaries}. */
  public final boolean hasCompany;

  /** Шаблонът от каталога, или {@code null}, когато вид доход изобщо не е посочен. */
  public final String taxPattern;

  /** {@code Partner.bgIdentifierType} — изричната стойност, или {@code null}. */
  public final String identifierType;

  /** {@code Partner.registrationCode} — само за ПОДРАЗБИРАНЕТО на типа, не за самата справка. */
  public final String registrationCode;

  /** {@code Partner.bgSelfInsuredDeclaredOn} — датата на писмената декларация по чл. 43 ал. 5. */
  public final LocalDate selfInsuredDeclaredOn;

  public BgIncomePaymentFeedCandidate(
      String source,
      String sourceRef,
      LocalDate paymentDate,
      BigDecimal grossAmount,
      BigDecimal insuranceAmount,
      boolean hasPartner,
      boolean hasCompany,
      String taxPattern,
      String identifierType,
      String registrationCode,
      LocalDate selfInsuredDeclaredOn) {
    this.source = source;
    this.sourceRef = sourceRef;
    this.paymentDate = paymentDate;
    this.grossAmount = grossAmount;
    this.insuranceAmount = insuranceAmount;
    this.hasPartner = hasPartner;
    this.hasCompany = hasCompany;
    this.taxPattern = taxPattern;
    this.identifierType = identifierType;
    this.registrationCode = registrationCode;
    this.selfInsuredDeclaredOn = selfInsuredDeclaredOn;
  }
}
