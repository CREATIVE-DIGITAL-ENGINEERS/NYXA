package com.axelor.apps.bglocalization.service.partner;

import javax.persistence.PersistenceException;

/**
 * Отказ по правилото „всеки партньор е на точно едно дружество“: нов партньор без дружество, смяна
 * на дружеството след създаването, подадено от клиента чуждо дружество.
 *
 * <p>{@link PersistenceException} нарочно — хвърля се от слушател на Hibernate и от наблюдател на
 * заявката, тоест от места, които не обявяват {@code AxelorException}; излиза от транзакцията като
 * нарушено ограничение и съобщението стига до екрана.
 */
public class BgPartnerCompanyException extends PersistenceException {

  private static final long serialVersionUID = 1L;

  public BgPartnerCompanyException(String message) {
    super(message);
  }
}
