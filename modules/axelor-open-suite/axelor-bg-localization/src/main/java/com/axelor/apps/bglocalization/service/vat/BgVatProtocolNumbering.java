package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;

/**
 * НОМЕРЪТ НА ДДС ПРОТОКОЛА — кога се дава и как изглежда. Чисти функции; самото взимане от
 * поредицата е в {@code BgMoveRepository}.
 *
 * <p><b>Кой.</b> Всеки ДДС протокол по ръчна статия: самоначисляване (ВОП, чл. 82, чл. 163а),
 * вносът по чл. 167а и корекциите по чл. 79/79а ({@link BgVatMoveOperationRouting#isProtocol}).
 * ЗДДС чл. 117 ал. 2 т. 1 и ППЗДДС чл. 66 ал. 3–6 т. 1 искат „номер и дата“. Номерът е СОБСТВЕН, а
 * не номерът на фактурата на доставчика.
 *
 * <p><b>Кога.</b> При осчетоводяване — {@code MoveValidateServiceImpl.updateValidateStatus} е
 * единственото място, което слага {@link MoveRepository#STATUS_ACCOUNTED}, и след него статията се
 * записва. Веднъж даден, номерът не се мени: „Поправки и добавки в протоколите не се разрешават.
 * Погрешно съставени или поправени протоколи се анулират и се издават нови.“ (ЗДДС чл. 117а ал. 1).
 * Анулираният протокол пази номера си.
 *
 * <p><b>Откъде.</b> От поредицата на дружеството с код {@link #SEQUENCE_CODE}. Тя не е засята: иска
 * дружество и се настройва при внедряване. Без нея номер няма, а дневникът предупреждава.
 *
 * <p><b>Какъв.</b> 10 арабски цифри с водещи нули, без префикс и без годишно нулиране. ⚠️ Да се
 * потвърди: ЗДДС чл. 117 ал. 2 т. 1 не предписва формат. 10-те цифри са по аналогия с фактурата —
 * чл. 114 ал. 1 т. 2 „пореден десетразряден номер, съдържащ само арабски цифри, базиран на една или
 * повече серии…“.
 */
public final class BgVatProtocolNumbering {

  /** Кодът на поредицата ({@code sequence.generic.code.select}). */
  public static final String SEQUENCE_CODE = "bgVatProtocol";

  private BgVatProtocolNumbering() {}

  /** Дължи ли статията номер сега: осчетоводена, ДДС протокол и още без номер. */
  public static boolean isDue(Move move) {
    return move != null
        && Integer.valueOf(MoveRepository.STATUS_ACCOUNTED).equals(move.getStatusSelect())
        && BgVatMoveOperationRouting.isProtocol(move.getBgVatMoveVatOperation())
        && isBlank(move.getBgVatProtocolNumber());
  }

  /** Ширината на „Номер на документ“ (02-05 / 03-06) — символно поле от 20 знака (Прил. № 12). */
  public static final int MAX_LENGTH = 20;

  /**
   * Защо номерът на РЕАЛНО ИЗДАДЕН протокол не може да се въведе ръчно сега; {@code null}, когато
   * може. Това е тясното действие за заварените протоколи без номер: само осчетоводен ДДС протокол,
   * само докато номер няма — даден веднъж, той не се мени (ЗДДС чл. 117а ал. 1).
   */
  public static String entryRefusal(Move move, String number) {
    if (move == null) {
      return "Няма статия.";
    }
    if (!Integer.valueOf(MoveRepository.STATUS_ACCOUNTED).equals(move.getStatusSelect())) {
      return "Номерът на протокола се въвежда само на осчетоводена статия.";
    }
    if (!BgVatMoveOperationRouting.isProtocol(move.getBgVatMoveVatOperation())) {
      return "Статията не е ДДС протокол.";
    }
    if (!isBlank(move.getBgVatProtocolNumber())) {
      return "Протоколът вече има номер „"
          + move.getBgVatProtocolNumber().trim()
          + "“ — номерът не се мени (ЗДДС чл. 117а ал. 1).";
    }
    if (isBlank(number)) {
      return "Въведете номера на издадения протокол.";
    }
    if (number.trim().length() > MAX_LENGTH) {
      return "Номерът е по-дълъг от "
          + MAX_LENGTH
          + " знака — „Номер на документ“ (02-05 / 03-06) е символно поле от "
          + MAX_LENGTH
          + " знака.";
    }
    return null;
  }

  /** 10 арабски цифри — формата, който поредицата трябва да дава (да се потвърди). */
  public static boolean isWellFormed(String number) {
    return number != null && number.matches("\\d{10}");
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
