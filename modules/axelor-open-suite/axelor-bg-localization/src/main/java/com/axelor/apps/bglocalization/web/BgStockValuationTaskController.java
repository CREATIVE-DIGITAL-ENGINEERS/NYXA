package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.service.stock.BgStockValuationProcessor;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;

/** „Обработи отново“ в списъка „Неосчетоводени изписвания“ (ИЗП). */
public class BgStockValuationTaskController {

  public void retry(ActionRequest request, ActionResponse response) {
    BgStockValuationTask task = request.getContext().asType(BgStockValuationTask.class);
    if (task.getId() == null) {
      return;
    }
    Beans.get(BgStockValuationProcessor.class).retry(task.getId());
    response.setReload(true);
  }
}
