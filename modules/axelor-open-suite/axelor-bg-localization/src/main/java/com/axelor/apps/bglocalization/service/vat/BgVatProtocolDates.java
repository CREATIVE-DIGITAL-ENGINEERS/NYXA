package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;
import java.time.LocalDate;

/**
 * ИЗИСКУЕМОСТТА И ДАТАТА НА ПРОТОКОЛА ПО ЧЛ. 117 ЗДДС — чисти функции.
 *
 * <p><b>Изискуемост (D_ch).</b>
 *
 * <ul>
 *   <li>ВОП — ЗДДС чл. 63 ал. 3: „Данъкът при вътреобщностното придобиване става изискуем на 15-ия
 *       ден на месеца, следващ месеца, през който е възникнало данъчното събитие съгласно ал. 1 и
 *       2.“; ал. 4: „Независимо от ал. 3, данъкът става изискуем на датата на издаване на фактурата
 *       … когато са издадени преди 15-ия ден на месеца, следващ месеца, през който е настъпило
 *       данъчното събитие.“ Ал. 5 („…издадени във връзка с направено плащане преди датата на
 *       възникване на данъчното събитие“) — авансовата фактура не стига дотук: тя е ГРЕШКА в
 *       услугата.
 *   <li>Чл. 82 ал. 2 и чл. 163а — чл. 25 ал. 6: „На датата на възникване на данъчното събитие по
 *       ал. 2, 3 и 4: 1. данъкът по този закон става изискуем…“ (за чл. 163а — чрез чл. 163а ал. 3:
 *       „…става изискуем по реда на чл. 25, ал. 6 и 7.“). Авансът по чл. 25 ал. 7 също е ГРЕШКА в
 *       услугата.
 * </ul>
 *
 * <p><b>Срок.</b> ЗДДС чл. 117 ал. 3: „Протоколът се издава не по-късно от 15 дни от датата, на
 * която данъкът е станал изискуем.“ — D_ch + 15 календарни дни, без отместване при празник.
 *
 * <p><b>Дата и период.</b> Протоколът е данъчен документ (чл. 112 ал. 1 т. 3), а данъкът е „дължим
 * от регистрираното лице за данъчния период, през който е издаден данъчният документ, а в случаите,
 * когато не е издаден такъв документ или не е издаден в срока по този закон - за данъчния период,
 * през който данъкът е станал изискуем“ (чл. 86 ал. 2). По подразбиране датата е max(D_ch, датата
 * на фактурата); исканата дата е в [D_ch; D_ch + 15] — тогава статията е с датата на протокола.
 *
 * <p><b>Закъснелият протокол.</b> ППЗДДС чл. 81 ал. 5: „В случаите по чл. 73а от закона, когато
 * данъкът е начислен извън сроковете по чл. 117, ал. 3 от закона, издаденият протокол не се
 * отразява в дневника за продажбите за периода, през който е издаден. Същият следва да се отрази в
 * дневниците за продажби и покупки за данъчния период, през който данъкът е станал изискуем, като
 * се прилага чл. 126, ал. 3, т. 2 от закона.“ (ЗДДС чл. 73а ал. 2 — същото за данъчния кредит.)
 * Затова статията е с датата D_ch (периодът), а протоколът пази датата на издаването си ({@code
 * Move.bgVatProtocolDate}). Закъснял е, когато и датата по подразбиране (фактурата е въведена след
 * срока) е след срока; исканата дата тогава е не по-рано от датата на фактурата.
 */
public final class BgVatProtocolDates {

  /** ЗДДС чл. 117 ал. 3 — „не по-късно от 15 дни“. */
  public static final int ISSUE_DAYS = 15;

  /** ЗДДС чл. 63 ал. 3 — „15-ия ден на месеца, следващ месеца“. */
  public static final int VOP_DAY_OF_NEXT_MONTH = 15;

  private BgVatProtocolDates() {}

  /**
   * D_ch — датата, на която данъкът става изискуем.
   *
   * @param operation операцията на протокола
   * @param taxEventDate датата на данъчното събитие
   * @param supplierInvoiceDate датата на издаване на фактурата от доставчика ({@code
   *     Invoice.originDate})
   */
  public static LocalDate chargeability(
      String operation, LocalDate taxEventDate, LocalDate supplierInvoiceDate) {
    if (taxEventDate == null) {
      return null;
    }
    if (BgVatMoveOperationRouting.OPERATION_VOP.equals(operation)) {
      LocalDate fifteenth = taxEventDate.plusMonths(1).withDayOfMonth(VOP_DAY_OF_NEXT_MONTH);
      return supplierInvoiceDate != null && supplierInvoiceDate.isBefore(fifteenth)
          ? supplierInvoiceDate
          : fifteenth;
    }
    return taxEventDate;
  }

  /** Последният ден за издаване — D_ch + 15 календарни дни (ЗДДС чл. 117 ал. 3). */
  public static LocalDate deadline(LocalDate chargeability) {
    return chargeability == null ? null : chargeability.plusDays(ISSUE_DAYS);
  }

  /** Датата по подразбиране: max(D_ch, датата на фактурата). */
  public static LocalDate defaultDate(LocalDate chargeability, LocalDate invoiceDate) {
    if (invoiceDate == null || invoiceDate.isBefore(chargeability)) {
      return chargeability;
    }
    return invoiceDate;
  }

  /** Къде ляга протоколът: датата на издаване, датата на статията (периодът) — или защо не може. */
  public static final class Placement {
    private final LocalDate protocolDate;
    private final LocalDate moveDate;
    private final boolean late;
    private final String refusal;

    private Placement(LocalDate protocolDate, LocalDate moveDate, boolean late, String refusal) {
      this.protocolDate = protocolDate;
      this.moveDate = moveDate;
      this.late = late;
      this.refusal = refusal;
    }

    private static Placement refused(String refusal) {
      return new Placement(null, null, false, refusal);
    }

    /** Датата на издаване (ЗДДС чл. 117 ал. 2 т. 1). */
    public LocalDate getProtocolDate() {
      return protocolDate;
    }

    /** Датата на статията — тя решава периода на дневниците. */
    public LocalDate getMoveDate() {
      return moveDate;
    }

    /** Издаден след D_ch + 15 — дневниците са за периода на изискуемостта. */
    public boolean isLate() {
      return late;
    }

    /** Защо датата не е допустима; {@code null}, когато е. */
    public String getRefusal() {
      return refusal;
    }
  }

  /**
   * Датата на протокола и периодът му. Исканата дата е в [D_ch; D_ch + 15]: по-ранна — данъкът още
   * не е изискуем; по-късна — отказ, освен когато и датата по подразбиране вече е след срока:
   * тогава протоколът е закъснял и се издава на исканата дата, не по-рано от датата на фактурата.
   */
  public static Placement place(
      LocalDate chargeability, LocalDate invoiceDate, LocalDate requestedDate) {
    LocalDate deadline = deadline(chargeability);
    LocalDate byDefault = defaultDate(chargeability, invoiceDate);
    LocalDate date = requestedDate == null ? byDefault : requestedDate;
    if (date.isBefore(chargeability)) {
      return Placement.refused(
          "датата на протокола "
              + date
              + " е преди изискуемостта "
              + chargeability
              + " — данъкът още не е изискуем");
    }
    if (!date.isAfter(deadline)) {
      return new Placement(date, date, false, null);
    }
    if (!byDefault.isAfter(deadline)) {
      return Placement.refused(
          "датата на протокола "
              + date
              + " е след срока по ЗДДС чл. 117, ал. 3 ("
              + deadline
              + ") — изберете дата от "
              + chargeability
              + " до "
              + deadline);
    }
    if (date.isBefore(byDefault)) {
      return Placement.refused(
          "датата на закъснелия протокол "
              + date
              + " е преди датата на фактурата "
              + byDefault
              + " — изберете дата от "
              + chargeability
              + " до "
              + deadline
              + " или не по-рано от "
              + byDefault);
    }
    return new Placement(date, chargeability, true, null);
  }
}
