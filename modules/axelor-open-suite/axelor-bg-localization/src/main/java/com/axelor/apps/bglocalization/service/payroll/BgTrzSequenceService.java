package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.db.Model;
import com.google.inject.Inject;
import java.time.LocalDate;
import javax.persistence.PersistenceException;

/**
 * Номерът на ТРЗ документ — от редицата на ДРУЖЕСТВОТО, по стандартния механизъм на Axelor ({@link
 * SequenceService} със {@code company}). Правилата са в {@link BgTrzSequences}.
 */
public class BgTrzSequenceService {

  protected final SequenceService sequenceService;

  @Inject
  public BgTrzSequenceService(SequenceService sequenceService) {
    this.sequenceService = sequenceService;
  }

  /**
   * Следващият номер за документ на {@code company}.
   *
   * <p>⚠️ {@code getSequence(код, дружество)} търси САМО редицата на това дружество ({@code
   * SequenceRepository#find}) — не пада към реда без дружество. Затова липсата е отказ тук, а не
   * {@code null}, който някой отгоре да запълни.
   *
   * <p>⚠️ И грешката на Axelor е отказ, не следа в дневника: {@code AxelorException} (вкл. „номерът
   * вече съществува“) не се записва в TraceBack и документът не минава на резервен брояч.
   *
   * @param documentLabel как да се назове документът в отказа (обикновено номерът му или „(нов)“)
   */
  public String nextNumber(
      String code,
      Company company,
      LocalDate refDate,
      Class<? extends Model> objectClass,
      String fieldName,
      Model model,
      String documentLabel) {
    Sequence sequence = company == null ? null : sequenceFor(code, company);
    if (sequence == null) {
      throw new PersistenceException(BgTrzSequences.missingRefusal(company, code, documentLabel));
    }
    String number;
    try {
      number = sequenceService.getSequenceNumber(sequence, refDate, objectClass, fieldName, model);
    } catch (AxelorException e) {
      throw new PersistenceException(
          BgTrzSequences.numberingFailed(company, code, documentLabel, e.getMessage()), e);
    }
    if (number == null || number.trim().isEmpty()) {
      throw new PersistenceException(BgTrzSequences.missingRefusal(company, code, documentLabel));
    }
    return number;
  }

  /**
   * Редицата на дружеството — и САМО неговата. Свой шев, за да могат свидетелите да пуснат двете
   * мутации (обща редица; тихо връщане към общата) по истинския път.
   */
  protected Sequence sequenceFor(String code, Company company) {
    return sequenceService.getSequence(code, company);
  }
}
