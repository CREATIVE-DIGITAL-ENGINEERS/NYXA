package com.axelor.apps.bglocalization.service.fixedasset;

import javax.persistence.PersistenceException;

/**
 * Отказ по правилата на {@link BgFixedAssetGuard}: инвентарният номер, сметката и категорията на
 * валидиран актив, връщане в чернова и триене, „отделен от“, видът по рамката.
 *
 * <p>{@link PersistenceException} нарочно — като {@code BgPartnerCompanyException}: хвърля се от
 * слушател на Hibernate и от наблюдател на заявката, които не обявяват {@code AxelorException};
 * излиза от транзакцията като нарушено ограничение и съобщението стига до екрана.
 */
public class BgFixedAssetIdentityException extends PersistenceException {

  private static final long serialVersionUID = 1L;

  public BgFixedAssetIdentityException(String message) {
    super(message);
  }
}
