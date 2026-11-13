package com.axelor.apps.bglocalization.service;

/**
 * ОТКАЗ НА ПРЕСМЯТАНЕТО — данни, които човек трябва да поправи, а не дефект.
 *
 * <p>Хвърля се от {@code BgPaySlipService#calculate}, който не обявява проверими изключения и се
 * вика при всяка промяна по формата. Контролерът го показва като отказ с текста, а не като срив.
 * Частният случай „копието няма замразен режим или дни“ е {@link BgRegimeMissingException}.
 */
public class BgCalculationRefusedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public BgCalculationRefusedException(String message) {
    super(message);
  }
}
