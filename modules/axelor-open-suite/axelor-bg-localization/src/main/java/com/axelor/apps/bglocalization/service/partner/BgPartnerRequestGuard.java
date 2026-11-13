package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.event.Observes;
import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.events.qualifiers.EntityType;
import com.axelor.rpc.Request;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Named;

/**
 * КЛИЕНТЪТ НЕ ИЗБИРА ДРУЖЕСТВОТО НА ПАРТНЬОРА — пазачът на {@code /ws/rest} и екрана.
 *
 * <p><b>Създаване ({@code save} без id).</b> Новият партньор е на активното дружество на
 * потребителя. Подадено друго дружество се отказва; подаденото активно (или празно) минава, а
 * празното го попълва {@link BgPartnerCompanyListener}. Без тази проверка заявката би сложила
 * партньора в дружество, в което потребителят не работи — слушателят на Hibernate не различава
 * дружество от клиента от дружество, сложено от услуга.
 *
 * <p><b>Масово обновяване ({@code mass-update}).</b> {@code Resource.updateMass} пише с JPQL {@code
 * UPDATE … WHERE id IN (…)}, без да зарежда редовете, тоест без {@code PreUpdate}: слушателят не го
 * вижда. Затова масова смяна на {@code company} се отказва тук, за всички.
 *
 * <p><b>Системен партньор</b> ({@code nxSystem}) клиентът не създава: той се ражда само с нов
 * потребител. <b>Записът на дружество</b> не носи нов вложен партньор — партньорът на дружеството
 * се създава от сървъра след него.
 *
 * <p>Смяна на дружеството на заварен партньор през {@code save} не се проверява тук — нея я хваща
 * слушателят по старото състояние, по всеки път. Образецът е {@code NapArtifactRequestGuard}.
 */
public class BgPartnerRequestGuard {

  public void onSave(
      @Observes @Named(RequestEvent.SAVE) @EntityType(Partner.class) PreRequest event) {
    List<Map<String, Object>> records = records(event.getRequest());
    Company active = null;
    boolean activeRead = false;
    for (Map<String, Object> record : records) {
      if (idOf(record) != null) {
        continue;
      }
      if (Boolean.TRUE.equals(record.get(BgPartnerCompanyListener.SYSTEM))) {
        throw new BgPartnerCompanyException(BgPartnerCompany.SYSTEM_OUTSIDE_NEW_USER);
      }
      Long requested = idOf(record.get("company"));
      if (requested == null) {
        continue;
      }
      if (!activeRead) {
        active = BgPartnerCompany.activeCompanyOfUser();
        activeRead = true;
      }
      String refusal = refuseClientCompany(requested, active);
      if (refusal != null) {
        throw new BgPartnerCompanyException(refusal);
      }
    }
  }

  /**
   * Нов партньор, вложен в записа на дружество, Hibernate би вмъкнал ПРЕДИ дружеството — тоест в
   * чуждо (активното) дружество. Партньорът на дружеството се създава от сървъра след него ({@code
   * BgCompanyManagementRepository}); вложеният нов се отказва с обяснение.
   */
  public void onCompanySave(
      @Observes @Named(RequestEvent.SAVE) @EntityType(Company.class) PreRequest event) {
    for (Map<String, Object> record : records(event.getRequest())) {
      if (isNewNested(record.get("partner"))) {
        throw new BgPartnerCompanyException(BgPartnerCompany.NESTED_COMPANY_PARTNER);
      }
    }
  }

  /** Вложен запис без id ({@code {name: …}}) — нов; {@code {id: …}} е препратка. */
  @SuppressWarnings("unchecked")
  static boolean isNewNested(Object value) {
    return value instanceof Map && idOf(((Map<String, Object>) value).get("id")) == null;
  }

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(Partner.class) PreRequest event) {
    for (String key : massUpdateValues(event.getRequest()).keySet()) {
      String field = key.replaceFirst("^self\\.", "");
      if (BgPartnerCompanyListener.SYSTEM.equals(field)) {
        throw new BgPartnerCompanyException(
            "Това дали партньорът е системен (на потребител) не се сменя с масово обновяване.");
      }
      if (BgPartnerCompanyListener.COMPANY.equals(field)) {
        throw new BgPartnerCompanyException(
            "Дружеството на партньорите не се сменя с масово обновяване: всеки партньор е на точно"
                + " едно дружество, определено при създаването му.");
      }
    }
  }

  /** Отказът за нов партньор с подадено дружество; {@code null} — позволено. */
  static String refuseClientCompany(Long requestedCompanyId, Company active) {
    if (requestedCompanyId == null) {
      return null;
    }
    if (active != null && requestedCompanyId.equals(active.getId())) {
      return null;
    }
    return BgPartnerCompany.clientCompany(active);
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> records(Request request) {
    if (request == null) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    if (request.getRecords() != null && !request.getRecords().isEmpty()) {
      for (Object record : request.getRecords()) {
        if (record instanceof Map) {
          out.add((Map<String, Object>) record);
        }
      }
    } else if (request.getData() != null) {
      out.add(request.getData());
    }
    return out;
  }

  /** id от {@code {id: …}} или от самия запис; {@code null}, ако няма. */
  @SuppressWarnings("unchecked")
  static Long idOf(Object value) {
    if (value instanceof Map) {
      value = ((Map<String, Object>) value).get("id");
    }
    if (value == null) {
      return null;
    }
    long id = Long.parseLong(value.toString());
    return id <= 0 ? null : id;
  }

  /**
   * Стойностите на масовото обновяване. ⚠️ Те са в {@code records[0]}, НЕ в {@code data}: {@code
   * data} носи филтъра — така ги праща клиентът и така ги чете {@code Resource.updateMass}.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> massUpdateValues(Request request) {
    if (request == null) {
      return Collections.emptyMap();
    }
    List<Object> records = request.getRecords();
    if (records != null && !records.isEmpty() && records.get(0) instanceof Map) {
      return (Map<String, Object>) records.get(0);
    }
    return request.getData() == null ? Collections.emptyMap() : request.getData();
  }
}
