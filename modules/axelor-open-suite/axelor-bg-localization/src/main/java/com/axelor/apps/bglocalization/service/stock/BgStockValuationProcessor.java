package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.db.repo.BgStockValuationTaskRepository;
import com.axelor.apps.bglocalization.service.accounting.BgAutoPostingIdentity;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработва задачите „чака оценка“ (ИЗП) СЛЕД commit на реализирането — всяка в собствена
 * транзакция, по реда на реализиране (id на задачата).
 *
 * <ul>
 *   <li>Успех — слоевете, редовете за оценка и статията се записват, задачата става DONE.
 *   <li>Отказ или грешка — транзакцията се връща назад (слоевете не се пипат), а в нова транзакция
 *       задачата става ERROR с причината. Реализирането НЕ се пипа (решение 4).
 *   <li>Задача, чийто продукт има по-ранна неоценена задача (ERROR или чакаща), чака — средната се
 *       смята по реда на реализиране и не прескача дупка.
 * </ul>
 */
public class BgStockValuationProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** Колко задачи на един ход — следващите остават за следващата минута. */
  static final int BATCH = 200;

  protected final BgStockValuationService valuationService;

  @Inject
  public BgStockValuationProcessor(BgStockValuationService valuationService) {
    this.valuationService = valuationService;
  }

  /** Обработва чакащите задачи. Връща колко са станали DONE. */
  public int processPending() {
    List<Long> ids =
        JPA.em()
            .createQuery(
                "SELECT self.id FROM BgStockValuationTask self WHERE self.statusSelect = :pending"
                    + " ORDER BY self.id",
                Long.class)
            .setParameter("pending", BgStockValuationTaskRepository.STATUS_PENDING)
            .setMaxResults(BATCH)
            .getResultList();
    int done = 0;
    for (Long id : ids) {
      if (process(id)) {
        done++;
      }
    }
    return done;
  }

  /**
   * Обработва една задача. Връща {@code true}, когато е станала DONE. Грешката не излиза оттук —
   * записва се върху задачата.
   */
  public boolean process(Long taskId) {
    // Задачата работи като потребителя за автоматично осчетоводяване на дружеството си — само
    // за времето на тази задача (BgAutoPostingIdentity, try/finally). Празна настройка — без
    // идентичност; валидирането тогава отказва с причина.
    try {
      return BgAutoPostingIdentity.runAs(postingUserCode(taskId), () -> processAsUser(taskId));
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /** Кодът на потребителя за автоматично осчетоводяване на дружеството на задачата, или null. */
  protected String postingUserCode(Long taskId) {
    List<String> codes =
        JPA.em()
            .createQuery(
                "SELECT ac.bgAutoPostingUser.code FROM BgStockValuationTask t, AccountConfig ac"
                    + " WHERE t.id = :id AND ac.company = t.company",
                String.class)
            .setParameter("id", taskId)
            .getResultList();
    return codes.isEmpty() ? null : codes.get(0);
  }

  protected boolean processAsUser(Long taskId) {
    AtomicReference<String> waitingFor = new AtomicReference<>();
    try {
      JPA.runInTransaction(
          () -> {
            BgStockValuationTask task = JPA.find(BgStockValuationTask.class, taskId);
            if (task == null
                || task.getStatusSelect() != BgStockValuationTaskRepository.STATUS_PENDING) {
              return;
            }
            String earlier = earlierUnvalued(task);
            if (earlier != null) {
              waitingFor.set(earlier);
              task.setErrorMessage(
                  "Чака оценката на по-ранното движение " + earlier + " със същия продукт.");
              JPA.save(task);
              return;
            }
            try {
              Move move = valuationService.value(task);
              task.setMove(move);
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              throw new IllegalStateException(e.getMessage(), e);
            }
            task.setStatusSelect(BgStockValuationTaskRepository.STATUS_DONE);
            task.setErrorMessage(null);
            task.setAttempts(task.getAttempts() + 1);
            task.setProcessedOn(LocalDateTime.now());
            JPA.save(task);
          });
      JPA.clear();
      return waitingFor.get() == null && isDone(taskId);
    } catch (Exception e) {
      JPA.clear();
      String message = message(e);
      if (hasTechnicalCause(e)) {
        LOG.warn("Stock valuation task {} failed: {}", taskId, message, e);
      } else {
        LOG.warn("Stock valuation task {} failed: {}", taskId, message);
      }
      markError(taskId, message);
      return false;
    }
  }

  /** Връща задачата с ERROR за повторна обработка (бутонът в списъка). */
  public void retry(Long taskId) {
    JPA.runInTransaction(
        () -> {
          BgStockValuationTask task = JPA.find(BgStockValuationTask.class, taskId);
          if (task != null
              && task.getStatusSelect() == BgStockValuationTaskRepository.STATUS_ERROR) {
            task.setStatusSelect(BgStockValuationTaskRepository.STATUS_PENDING);
            JPA.save(task);
          }
        });
    JPA.clear();
    process(taskId);
  }

  protected void markError(Long taskId, String message) {
    JPA.runInTransaction(
        () -> {
          BgStockValuationTask task = JPA.find(BgStockValuationTask.class, taskId);
          if (task == null) {
            return;
          }
          task.setStatusSelect(BgStockValuationTaskRepository.STATUS_ERROR);
          task.setErrorMessage(message);
          task.setAttempts(task.getAttempts() + 1);
          task.setProcessedOn(LocalDateTime.now());
          JPA.save(task);
        });
    JPA.clear();
  }

  private boolean isDone(Long taskId) {
    BgStockValuationTask task = JPA.find(BgStockValuationTask.class, taskId);
    return task != null && task.getStatusSelect() == BgStockValuationTaskRepository.STATUS_DONE;
  }

  /**
   * Номерът на по-ранно неоценено движение (ERROR или чакащо) на същото дружество със същия
   * продукт, или {@code null}.
   */
  protected String earlierUnvalued(BgStockValuationTask task) {
    List<Long> products = new ArrayList<>();
    for (StockMoveLine line : task.getStockMove().getStockMoveLineList()) {
      if (line.getProduct() != null) {
        products.add(line.getProduct().getId());
      }
    }
    if (products.isEmpty()) {
      return null;
    }
    List<String> earlier =
        JPA.em()
            .createQuery(
                "SELECT t.stockMove.stockMoveSeq FROM BgStockValuationTask t"
                    + " WHERE t.id < :id AND t.company = :company"
                    + " AND t.statusSelect IN (:pending, :error)"
                    + " AND EXISTS (SELECT l.id FROM StockMoveLine l"
                    + " WHERE l.stockMove = t.stockMove AND l.product.id IN (:products))"
                    + " ORDER BY t.id",
                String.class)
            .setParameter("id", task.getId())
            .setParameter("company", task.getCompany())
            .setParameter("pending", BgStockValuationTaskRepository.STATUS_PENDING)
            .setParameter("error", BgStockValuationTaskRepository.STATUS_ERROR)
            .setParameter("products", products)
            .setMaxResults(1)
            .getResultList();
    return earlier.isEmpty() ? null : String.valueOf(earlier.get(0));
  }

  /** Бизнес отказът без причина е обявен текст; всичко друго иска стек в лога. */
  static boolean hasTechnicalCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BgStockValuationService.Refusal) {
        return t.getCause() != null;
      }
    }
    return true;
  }

  static String message(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null
        && !(t instanceof BgStockValuationService.Refusal)
        && t.getMessage() == null) {
      t = t.getCause();
    }
    String text = t.getMessage();
    return text == null ? t.getClass().getSimpleName() : text;
  }
}
