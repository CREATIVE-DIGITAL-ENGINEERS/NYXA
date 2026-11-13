package com.axelor.apps.napsubmissions.common.submission;

/**
 * Шумният отказ на {@link NapFilingLock}: документът вече е подаден по другия път. Наследява {@link
 * IllegalStateException}, за да го покажат като грешка и контролерите, които вече ловят отказите на
 * услугите по този тип.
 */
public class NapFilingLockedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public NapFilingLockedException(String message) {
    super(message);
  }
}
