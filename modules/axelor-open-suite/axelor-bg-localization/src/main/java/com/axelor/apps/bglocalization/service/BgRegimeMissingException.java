package com.axelor.apps.bglocalization.service;

/**
 * ОТКАЗ: КОПИЕТО НЯМА ЗАМРАЗЕН РЕЖИМ НА ОСИГУРЯВАНЕ — или дните на карва, когато ги чете.
 *
 * <p>Хвърля се от {@code BgPaySlipService#calculate}, който не обявява проверими изключения и се
 * вика при всяка промяна по формата. Контролерът го показва като отказ с текста, а не като срив:
 * причината е данни, които човек трябва да попълни, а не дефект.
 */
public class BgRegimeMissingException extends BgCalculationRefusedException {

  private static final long serialVersionUID = 1L;

  public BgRegimeMissingException(String message) {
    super(message);
  }
}
