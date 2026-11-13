package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6Service;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.Map;

/**
 * „БНБ — СПБ-6А/6Б“: преходна форма, един бутон, файл за сваляне.
 *
 * <p>Роля {@code accounting.read} (или по-широка) и активното дружество — справката чете
 * счетоводството, без да го променя. Платформата не пази action-ите.
 */
@Singleton
public class BgBnbSpb6Controller {

  private static final String EXPORT = "export the БНБ СПБ-6А/6Б workbook";

  /** Новата форма: миналото тримесечие. */
  public void defaults(ActionRequest request, ActionResponse response) {
    LocalDate previous = LocalDate.now().minusMonths(3);
    response.setValue("$year", previous.getYear());
    response.setValue("$quarter", (previous.getMonthValue() - 1) / 3 + 1);
  }

  /**
   * Стойността на преходно поле {@code $<name>} от формата. Front-ът я праща БЕЗ „$“: {@code
   * processContextValues} преименува {@code $year} на {@code year} (getBaseDummy), а сериализаторът
   * на заявките изхвърля всеки ключ с „$“ (axelor-front services/client/client.ts). Прочетен като
   * {@code "$year"}, ключът е винаги null, и „Свали Excel“ би отговорил „Изберете година и период.“
   * при попълнена форма. „$“-ключът остава само като резерва (REST, бъдещ front).
   */
  static Integer wizardInt(Map<String, Object> context, String name) {
    Object value = context.get(name);
    if (value == null) {
      value = context.get("$" + name);
    }
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    return value instanceof Number
        ? Integer.valueOf(((Number) value).intValue())
        : Integer.valueOf(value.toString().trim());
  }

  public void export(ActionRequest request, ActionResponse response) {
    try {
      CapabilityGate.requireRole(EXPORT, Capabilities.ACCOUNTING_READ);
      User user = AuthUtils.getUser();
      Company company = user == null ? null : user.getActiveCompany();
      if (company == null) {
        response.setError("Изберете активно дружество.");
        return;
      }
      CapabilityGate.requireActiveCompany(company);
      CapabilityGate.requireRead(Company.class, company.getId());
      Integer year = wizardInt(request.getContext(), "year");
      Integer quarter = wizardInt(request.getContext(), "quarter");
      if (year == null || quarter == null) {
        response.setError("Изберете година и период.");
        return;
      }
      MetaFile file = Beans.get(BgBnbSpb6Service.class).export(company, year, quarter);
      response.setView(
          ActionView.define(file.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + file.getId()
                      + "/content/download?v="
                      + file.getVersion())
              .param("download", "true")
              .map());
    } catch (IllegalArgumentException e) {
      response.setError(e.getMessage());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
