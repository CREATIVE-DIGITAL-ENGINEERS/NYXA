package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.service.stock.BgStockVatSignalList;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.Collections;
import java.util.List;

/** Складовите движения с ДДС сигнал (S0, т. D) — виж {@link BgStockVatSignalList}. */
public class BgStockMoveController {

  public void openVatSignalList(ActionRequest request, ActionResponse response) {
    List<Long> ids = Beans.get(BgStockVatSignalList.class).ids();
    response.setView(
        ActionView.define("Складови документи за ДДС протокол")
            .model(StockMove.class.getName())
            .add("grid", "stock-move-grid")
            .add("form", "stock-move-form")
            .domain("self.id IN (:_bgIds)")
            // Празен IN е невалиден JPQL — несъществуващ id дава празен списък.
            .context("_bgIds", ids.isEmpty() ? Collections.singletonList(0L) : ids)
            .map());
  }
}
