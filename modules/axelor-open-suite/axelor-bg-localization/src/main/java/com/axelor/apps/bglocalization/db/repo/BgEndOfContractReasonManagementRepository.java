package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationService;
import com.axelor.apps.hr.db.EndOfContractReason;
import com.axelor.apps.hr.db.repo.EndOfContractReasonRepository;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.db.JPA;
import java.util.List;
import java.util.Objects;
import javax.persistence.FlushModeType;
import javax.validation.ValidationException;

/**
 * ОСНОВАНИЕТО ЗА ПРЕКРАТЯВАНЕ В РЕЧНИКА — не се сменя под подаден ЕТЗ.
 *
 * <p>Основанието (т. 27) живее в {@code EndOfContractReason.bgTerminationGround} + {@code
 * bgLegalFamily}, сочено от договора; оттам го чете и обезщетението. Редакция на реда в речника
 * сменя основанието на ВСИЧКИ договори, които го сочат — включително на онези, чийто ЕТЗ за
 * прекратяване вече е вписан. Затова: подаден ЕТЗ по договор с това основание → отказ; поправката е
 * корекция.
 */
public class BgEndOfContractReasonManagementRepository extends EndOfContractReasonRepository {

  @Override
  public EndOfContractReason save(EndOfContractReason reason) {
    if (reason.getId() != null && groundChanged(reason) && usedByFiledTermination(reason.getId())) {
      throw new ValidationException(BgEtzTerminationService.FILED_LOCKED);
    }
    return super.save(reason);
  }

  private static boolean groundChanged(EndOfContractReason reason) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT r.bgTerminationGround, r.bgLegalFamily FROM EndOfContractReason r"
                    + " WHERE r.id = :id",
                Object[].class)
            .setParameter("id", reason.getId())
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return false;
    }
    Object[] committed = rows.get(0);
    return !Objects.equals(committed[0], reason.getBgTerminationGround())
        || !Objects.equals(committed[1], reason.getBgLegalFamily());
  }

  private static boolean usedByFiledTermination(Long reasonId) {
    Long count =
        JPA.em()
            .createQuery(
                "SELECT COUNT(s) FROM NapSubmission s, BgContractTermination t"
                    + " WHERE s.relatedModel = :m AND s.relatedRecordId = t.id"
                    + " AND t.employmentContract.endOfContractReason.id = :r"
                    + " AND s.submissionStatusSelect IN :filed",
                Long.class)
            .setParameter("m", BgContractTermination.class.getName())
            .setParameter("r", reasonId)
            .setParameter("filed", NapFilingLock.FILED_STATUSES)
            .setFlushMode(FlushModeType.COMMIT)
            .getSingleResult();
    return count != null && count > 0;
  }
}
