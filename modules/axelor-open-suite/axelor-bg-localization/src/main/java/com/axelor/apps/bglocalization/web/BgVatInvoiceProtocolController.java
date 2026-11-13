package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Протоколът по чл. 117 ЗДДС от покупна фактура — бутонът на фактурата и двете пакетни действия:
 * избраните фактури (меню „Инструменти“ на покупните фактури) и периодът на ДДС декларацията.
 * Неуспехът е видим (грешките и предупрежденията на {@link BgVatInvoiceProtocolService.Result}), а
 * осчетоводяването на фактурата не зависи от него.
 */
public class BgVatInvoiceProtocolController {

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

  /** „Състави протокол по чл. 117“ — с исканата дата от формата, ако е попълнена. */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      Invoice context = request.getContext().asType(Invoice.class);
      Invoice invoice = Beans.get(InvoiceRepository.class).find(context.getId());
      LocalDate requested = (LocalDate) request.getContext().get("bgVatProtocolRequestedDate");
      BgVatInvoiceProtocolService.Result result =
          Beans.get(BgVatInvoiceProtocolService.class).generate(invoice, requested);
      report(result, response);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Състави протоколите по чл. 117 за избраните“ — от таблицата на покупните фактури. */
  public void generateSelected(ActionRequest request, ActionResponse response) {
    try {
      @SuppressWarnings("unchecked")
      List<Number> selected = (List<Number>) request.getContext().get("_ids");
      if (selected == null || selected.isEmpty()) {
        response.setError(I18n.get("Изберете поне една фактура."));
        return;
      }
      List<Long> ids = new ArrayList<>();
      selected.forEach(id -> ids.add(id.longValue()));
      report(Beans.get(BgVatInvoiceProtocolService.class).generateForIds(ids), response);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Състави протоколите по чл. 117 за периода“ — от ДДС декларацията. */
  public void generateForPeriod(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn context = request.getContext().asType(BgVatReturn.class);
      BgVatReturn vatReturn = Beans.get(BgVatReturnRepository.class).find(context.getId());
      if (vatReturn == null || vatReturn.getCompany() == null || vatReturn.getPeriod() == null) {
        response.setError(I18n.get("Изберете дружество и период и запишете декларацията."));
        return;
      }
      YearMonth month = YearMonth.parse(vatReturn.getPeriod().trim(), PERIOD);
      BgVatInvoiceProtocolService.Result result =
          Beans.get(BgVatInvoiceProtocolService.class)
              .generateForPeriod(vatReturn.getCompany(), month.atDay(1), month.atEndOfMonth());
      report(result, response);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  private static void report(BgVatInvoiceProtocolService.Result result, ActionResponse response) {
    StringBuilder text = new StringBuilder();
    text.append("Съставени протоколи: ").append(result.getCreated().size());
    if (!result.getLinked().isEmpty()) {
      text.append("; свързани заварени: ").append(result.getLinked().size());
    }
    text.append('.');
    for (String error : result.getErrors()) {
      text.append("<br/>❌ ").append(escape(error));
    }
    for (String warning : result.getWarnings()) {
      text.append("<br/>⚠ ").append(escape(warning));
    }
    if (result.getErrors().isEmpty()) {
      response.setInfo(I18n.get(text.toString()));
    } else {
      response.setError(I18n.get(text.toString()));
    }
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
