package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.stock.db.StockMove;
import com.axelor.db.JPA;
import java.util.ArrayList;
import java.util.List;

/**
 * Реализираните складови движения с ДДС сигнал — кандидатите за протокол. Само списък.
 *
 * <p>Базата прави само груб предварителен филтър (реализирано и има с какво да се класифицира:
 * ръчен код, причина или инвентаризация). Самото правило е {@link BgStockVatSignals}, същото като
 * предупреждението на формата — JPQL препис би се разминал с него.
 */
public class BgStockVatSignalList {

  /** {@code StockMove.statusSelect} = 3 — реализирано. */
  static final int STATUS_REALIZED = 3;

  public List<Long> ids() {
    List<StockMove> candidates =
        JPA.all(StockMove.class)
            .filter(
                "self.statusSelect = ?1 AND ((self.bgSaftMovementCode IS NOT NULL"
                    + " AND self.bgSaftMovementCode <> '') OR self.stockCorrectionReason IS NOT NULL"
                    + " OR self.inventory IS NOT NULL)",
                STATUS_REALIZED)
            .order("-realDate")
            .order("-id")
            .fetch();
    return withSignal(candidates);
  }

  /** Идентификаторите на движенията, които имат сигнал, в подадения ред. */
  static List<Long> withSignal(List<StockMove> moves) {
    List<Long> out = new ArrayList<>();
    for (StockMove move : moves) {
      if (BgStockVatSignals.signalOf(move) != null) {
        out.add(move.getId());
      }
    }
    return out;
  }
}
