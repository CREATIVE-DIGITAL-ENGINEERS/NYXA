/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Отговорът на VIES, сведен до трите неща, които потребителският интерфейс може да ползва.
 */
package com.axelor.apps.bglocalization.service.vies;

/**
 * Присъдата на VIES за един ДДС номер.
 *
 * <p><b>Три състояния, не две.</b> Разделянето на {@link Status#UNAVAILABLE} от {@link
 * Status#INVALID} е цялата защита срещу фалшива тревога: паднала услуга, изтекло време и грешка в
 * мрежата НЕ са присъда за номера. Всичко, което не е категоричен отговор от регистъра, се слива в
 * {@code UNAVAILABLE} и повикващият мълчи или казва „не можах да проверя".
 *
 * <p>⚠️ <b>{@code INVALID} значи „не е намерен като активно регистриран", а НЕ „сгрешен номер".</b>
 * Структурно безупречен номер на дерегистрирала се фирма дава точно {@code INVALID}. Текстът към
 * потребителя трябва да казва това, инак човек ще тръгне да „поправя" верен номер.
 */
public final class BgViesResult {

  public enum Status {
    /** Регистърът потвърди активна регистрация. */
    VALID,
    /** Регистърът отговори категорично: не е намерен като активно регистриран. */
    INVALID,
    /** Услугата прие заявката, но не разпозна входа (VIES {@code INVALID_INPUT}). */
    INVALID_INPUT,
    /** Няма отговор: таймаут, мрежа, не-200, недостъпна държава. */
    UNAVAILABLE,
    /**
     * Проверката е ИЗКЛЮЧЕНА от конфигурацията ({@code bg.vat.vies.enabled=false}).
     *
     * <p>⚠️ Отделено от {@link #UNAVAILABLE} нарочно. „Не можах да проверя" е новина, когато
     * услугата е паднала, но е ШУМ, когато някой съзнателно е изключил проверката — а точно това се
     * прави, ако изходът към {@code ec.europa.eu} е затворен на средата. При изключена проверка
     * потребителят не бива да вижда нищо.
     */
    DISABLED
  }

  private final Status status;
  private final String name;
  private final String address;

  private BgViesResult(Status status, String name, String address) {
    this.status = status;
    this.name = name;
    this.address = address;
  }

  public static BgViesResult valid(String name, String address) {
    return new BgViesResult(Status.VALID, name, address);
  }

  public static BgViesResult of(Status status) {
    return new BgViesResult(status, null, null);
  }

  public Status getStatus() {
    return status;
  }

  /**
   * Името от регистъра, или {@code null}.
   *
   * <p>⚠️ Празно име НЕ е разминаване. Част от държавите (измерено: Германия) не разкриват име и
   * връщат {@code "---"}; затова името само се ПОКАЗВА и никога не се сравнява автоматично с
   * партньора.
   */
  public String getName() {
    return name;
  }

  public String getAddress() {
    return address;
  }

  public boolean isDefinitivelyInvalid() {
    return status == Status.INVALID;
  }
}
