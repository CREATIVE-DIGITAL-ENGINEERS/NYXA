package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.persistence.FlushModeType;
import javax.validation.ValidationException;

/**
 * ЕДНАТА ДАТА НА ПРЕКРАТЯВАНЕ — записът е източникът, договорът я носи.
 *
 * <p>При всеки запис датата се пренася в {@code EmploymentContract.bgTerminationDate}, за да четат
 * Д1, обезщетението по вид 27 и ЕТЗ една стойност. Обратният път — промяна на датата в договора —
 * отказва {@code BgEmploymentContractManagementRepository}.
 *
 * <p><b>⚠️ СЛЕД ПОДАДЕН ЕТЗ ДАТАТА НЕ СЕ ПИПА</b> — нито тук, нито в договора: вписаният запис се
 * поправя с корекция (код корекция 1), не с редакция под него.
 *
 * <p>На ниво хранилище, а не на действие във формата — покрива формата, REST и импорта наведнъж.
 */
public class BgContractTerminationManagementRepository extends BgContractTerminationRepository {

  @Override
  public BgContractTermination save(BgContractTermination termination) {
    if (termination.getId() != null) {
      LocalDate committed = committedDate(termination.getId());
      if (!Objects.equals(committed, termination.getTerminationDate())
          && BgEtzTerminationService.isFiled(termination.getId())) {
        throw new ValidationException(BgEtzTerminationService.FILED_LOCKED);
      }
    }
    BgContractTermination saved = super.save(termination);
    EmploymentContract contract = saved.getEmploymentContract();
    if (contract != null
        && !Objects.equals(contract.getBgTerminationDate(), saved.getTerminationDate())) {
      contract.setBgTerminationDate(saved.getTerminationDate());
      Beans.get(EmploymentContractRepository.class).save(contract);
    }
    return saved;
  }

  @Override
  public void remove(BgContractTermination termination) {
    if (termination != null && BgEtzTerminationService.isFiled(termination.getId())) {
      throw new ValidationException(BgEtzTerminationService.FILED_LOCKED);
    }
    super.remove(termination);
  }

  private static LocalDate committedDate(Long id) {
    List<LocalDate> rows =
        JPA.em()
            .createQuery(
                "SELECT t.terminationDate FROM BgContractTermination t WHERE t.id = :id",
                LocalDate.class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }
}
