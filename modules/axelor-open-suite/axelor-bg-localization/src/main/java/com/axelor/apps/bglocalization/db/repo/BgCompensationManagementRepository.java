package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSequenceService;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSequences;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import javax.persistence.PersistenceException;

/**
 * Дава номер на документа при първия запис.
 *
 * <p>Идиомът е на {@code EmploymentContractHRRepository}: пита {@code SequenceService} С
 * ДРУЖЕСТВОТО-ПЛАТЕЦ по договора.
 *
 * <p>⚠️ БРОЯЧЪТ Е НА ДРУЖЕСТВОТО ({@link BgTrzSequences}). Една обща редица за всички дружества
 * ({@code getSequence(код, null)}) или резервен брояч OBEZ-RES-… ({@code count + 1}) биха дали
 * номерация, която никое дружество не може да покаже като своя. Затова дружество без редица
 * получава отказ, който го назовава. Заварените OBEZ-RES-… номера остават.
 */
public class BgCompensationManagementRepository extends BgCompensationRepository {

  @Override
  public BgCompensation save(BgCompensation doc) {
    pinCompany(doc);
    if (doc.getReference() == null || doc.getReference().trim().isEmpty()) {
      doc.setReference(nextReference(doc));
    }
    return super.save(doc);
  }

  /**
   * ДРУЖЕСТВОТО НА ДОКУМЕНТА — платецът по договора; записва се при номерирането и после не се
   * сменя ({@link BgTrzSequences#refusePayerMismatch}; по старото състояние — {@code
   * BgTrzNumberingCompanyGuard}).
   */
  protected void pinCompany(BgCompensation doc) {
    EmploymentContract contract = doc.getEmploymentContract();
    Company derived = contract == null ? null : contract.getPayCompany();
    if (derived == null && doc.getCompany() == null) {
      throw new PersistenceException(
          BgTrzSequences.noCompanyRefusal(
              BgTrzSequences.COMPENSATION,
              label(doc),
              "трудовият договор няма дружество-платец. Попълнете платеца на договора и"
                  + " повторете."));
    }
    String refusal =
        BgTrzSequences.refusePayerMismatch(
            BgTrzSequences.COMPENSATION, label(doc), doc.getCompany(), derived);
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
    if (doc.getCompany() == null) {
      doc.setCompany(derived);
    }
  }

  /**
   * Номерът — САМО от редицата на дружеството.
   *
   * <p>⚠️ ДАТАТА, С КОЯТО СЕ ПИТА, Е bgEventDate, А НЕ ДНЕШНИЯТ ДЕН. Редицата е с годишно нулиране
   * и префикс {@code OBEZ-%YYYY-}, тоест годината в номера идва от подадената дата. Документ,
   * издаден през януари за прекратяване от декември, трябва да носи ДЕКЕМВРИЙСКАТА година — инак
   * номерът твърди друга година от събитието, което описва.
   */
  protected String nextReference(BgCompensation doc) {
    LocalDate refDate = doc.getBgEventDate() != null ? doc.getBgEventDate() : LocalDate.now();
    return Beans.get(BgTrzSequenceService.class)
        .nextNumber(
            BgTrzSequences.COMPENSATION,
            doc.getCompany(),
            refDate,
            BgCompensation.class,
            "reference",
            doc,
            label(doc));
  }

  private static String label(BgCompensation doc) {
    String reference = doc.getReference();
    return reference == null || reference.trim().isEmpty() ? "(нов)" : reference;
  }
}
