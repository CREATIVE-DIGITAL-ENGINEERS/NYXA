package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.db.repo.BgStockValuationTaskRepository;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.db.JPA;

/**
 * Обработчикът след реализиране (S0) на ИЗП: САМО insert на задача „чака оценка“ (решение от
 * 07.10.2026 — outbox).
 *
 * <p>Тук няма изчисления и няма статия. Задачата се записва в транзакцията на реализирането, тъй че
 * rollback на реализирането я маха заедно с него, а оценката идва след commit, в собствена
 * транзакция ({@link BgStockValuationProcessor}).
 *
 * <p>⚠️ Без отделна транзакция (REQUIRES_NEW): тя би оценила движение, което после се връща назад,
 * и би заключила редовете на наличностите. А грешка от JPA тук би маркирала външната транзакция за
 * отмяна — затова обработчикът не пише нищо, освен ако дружеството има активна политика, и не
 * хвърля сам.
 */
public class BgStockValuationOutbox implements BgStockMoveRealizedHandler {

  @Override
  public void afterRealize(StockMove move) {
    Company company = move.getCompany();
    if (company == null || company.getId() == null || !hasActivePolicy(company)) {
      return;
    }
    BgStockValuationTask task = new BgStockValuationTask();
    task.setStockMove(move);
    task.setCompany(company);
    task.setDocumentDate(
        move.getBgDocumentDate() != null ? move.getBgDocumentDate() : move.getRealDate());
    task.setStatusSelect(BgStockValuationTaskRepository.STATUS_PENDING);
    task.setAttempts(0);
    JPA.save(task);
  }

  /** Изписването е включено за дружеството, щом има поне една активна политика. */
  protected boolean hasActivePolicy(Company company) {
    return JPA.all(BgStockCostingPolicy.class)
            .filter(
                "self.company.id = ?1 AND self.statusSelect = ?2",
                company.getId(),
                BgStockCostingPolicyRepository.STATUS_ACTIVE)
            .count()
        > 0;
  }
}
