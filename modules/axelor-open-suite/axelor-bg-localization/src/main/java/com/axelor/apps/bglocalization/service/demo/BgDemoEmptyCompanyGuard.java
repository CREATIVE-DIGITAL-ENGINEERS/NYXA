package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Company;
import com.axelor.db.JPA;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ДЕМО ДАННИ САМО В ПРАЗНО ДРУЖЕСТВО — иначе ясен отказ.
 *
 * <p>„Празно“ значи: дружеството има сметкоплана BG_COA (сметките, дневниците, данъците и
 * поредиците от шаблона са настройка, не данни) и НЯМА нито един документ или запис, който демото
 * би смесило с чужди: счетоводна статия, фактура, партньор (освен партньора на самото дружество),
 * трудов договор, дълготраен актив, складово движение, фиш, ДДС справка.
 *
 * <p>Пазачът не пипа нищо — само брои. Други дружества не се гледат.
 */
public final class BgDemoEmptyCompanyGuard {

  /** Какво се брои: заглавие за отказа → JPQL с параметър {@code :c} (дружеството). */
  static final Map<String, String> CHECKS = new LinkedHashMap<>();

  static {
    CHECKS.put("счетоводни статии", "SELECT COUNT(m) FROM Move m WHERE m.company = :c");
    CHECKS.put("фактури", "SELECT COUNT(i) FROM Invoice i WHERE i.company = :c");
    CHECKS.put(
        "партньори",
        "SELECT COUNT(p) FROM Partner p WHERE p.company = :c" + " AND (:own IS NULL OR p <> :own)");
    CHECKS.put(
        "трудови договори", "SELECT COUNT(e) FROM EmploymentContract e WHERE e.payCompany = :c");
    CHECKS.put("дълготрайни активи", "SELECT COUNT(f) FROM FixedAsset f WHERE f.company = :c");
    CHECKS.put("складови движения", "SELECT COUNT(sm) FROM StockMove sm WHERE sm.company = :c");
    CHECKS.put(
        "фишове", "SELECT COUNT(ps) FROM BgPaySlip ps WHERE ps.employmentContract.payCompany = :c");
    CHECKS.put("ДДС справки", "SELECT COUNT(v) FROM BgVatReturn v WHERE v.company = :c");
  }

  private BgDemoEmptyCompanyGuard() {}

  /** Причините за отказ; празен списък значи „може“. */
  public static List<String> refusals(Company company) {
    List<String> out = new ArrayList<>();
    if (company == null || company.getId() == null) {
      out.add("Няма избрано дружество.");
      return out;
    }
    Long accounts =
        JPA.em()
            .createQuery(
                "SELECT COUNT(a) FROM Account a WHERE a.company = :c AND a.code = '411'",
                Long.class)
            .setParameter("c", company)
            .getSingleResult();
    if (accounts == 0) {
      out.add(
          "Дружеството няма сметкоплан BG_COA — приложете първо шаблона"
              + " (Счетоводна конфигурация › Импорт на счетоводни конфигурации).");
    }
    for (Map.Entry<String, String> e : CHECKS.entrySet()) {
      long n = count(e.getValue(), company);
      if (n > 0) {
        out.add("Дружеството не е празно: " + e.getKey() + " — " + n + ".");
      }
    }
    return out;
  }

  private static long count(String jpql, Company company) {
    javax.persistence.TypedQuery<Long> q =
        JPA.em().createQuery(jpql, Long.class).setParameter("c", company);
    if (jpql.contains(":own")) {
      q.setParameter("own", company.getPartner());
    }
    return q.getSingleResult();
  }
}
