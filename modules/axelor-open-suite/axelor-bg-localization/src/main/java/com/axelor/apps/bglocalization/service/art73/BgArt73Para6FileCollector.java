package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileAnnual;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ЗАПИСАНАТА СПРАВКА ПО АЛ. 6 → СЪДЪРЖАНИЕ ЗА ФАЙЛА, С ГРУПИРАНЕ ПО ЛИЦЕ.
 *
 * <p><b>⚠️ ЧЕТЕ ЗАПИСАНОТО, НЕ АГРЕГИРА НАНОВО — инвариантът „екран == файл“.</b> Същият довод като
 * при {@link BgArt73Para1FileCollector}: {@link BgArt73Para6Aggregator} вече е слял регистъра и
 * {@code BgArt73Para6ReportService#build} е записал резултата. Оттам нататък човек може да е
 * поправил ред на ръка — втора обиколка през агрегатора би дала друго съдържание.
 *
 * <p><b>⚠️ ТУК ОБАЧЕ ИМА И ПРЕСТРУКТУРИРАНЕ, КОЕТО АЛ. 1 НЯМА.</b> В базата редът е на (лице, код);
 * във файла записът е на ЛИЦЕ, а плащанията му са вложени. Това е ПРЕПОДРЕЖДАНЕ, не преизчисляване
 * — нито едно число не се променя, само се премества под своя собственик.
 *
 * <p><b>⚠️ КЛЮЧЪТ НА ВЛОЖЕНИЯ РЕД Е ТРОЕН, А НЕ ДВОЕН.</b> „Ред и формат“ (заповед №
 * З-ЦУ-1877/05.12.2019 г.):
 *
 * <ul>
 *   <li><b>т. 15</b> — един ред на (лице, код, работодател); същият работодател със същия код отива
 *       на ЕДИН ред;
 *   <li><b>т. 16</b> — доходите от ПРЕОБРАЗУВАЩОТО СЕ дружество отиват на ОТДЕЛЕН ред със запълнена
 *       колона 23. Платил ли е и правоприемникът по същия код, редовете стават ДВА с един и същ код
 *       — различени точно по това поле.
 * </ul>
 *
 * Затова ключът е (код, работодател, <b>има ли</b> ЕИК на преобразуващо се), а не (код,
 * работодател). Двоен, той щеше да слее точно двата реда, които т. 16 изрично разделя.
 *
 * <p>Чист клас: без Guice и без хранилища, тоест проверим без Postgres.
 */
public final class BgArt73Para6FileCollector {

  private BgArt73Para6FileCollector() {}

  /**
   * Събира Част І и ЛИЦАТА на Част ІІ от ЗАПИСАНАТА справка.
   *
   * @param report справката; {@code null} дава празно съдържание, а не изключение — празнотата се
   *     назовава от валидатора
   */
  public static BgArt73Para6FileContent collect(BgArt73Para6Report report) {
    BgArt73Para6FileContent content = new BgArt73Para6FileContent();
    if (report == null) {
      return content;
    }
    content.setTaxYear(report.getTaxYear());
    content.setPayerIdentifier(trim(report.getEmployerIdentifier()));
    content.setPayerName(trim(report.getEmployerName()));
    content.setContactPhone(trim(report.getContactPhone()));
    content.setContactEmail(trim(report.getContactEmail()));
    content.setRepresentativeName(trim(report.getRepresentativeName()));
    content.setRepresentativeIdentifier(trim(report.getRepresentativeIdentifier()));

    Map<String, BgArt73Para6Annual> annuals = annualsByEmployee(report.getAnnualList());

    // ⚠️ LinkedHashMap: подредбата на ЛИЦАТА е тази на записаните редове (по id), за да дава един и
    // същ файл при два пробега — o2m колекцията на JPA няма гарантиран ред.
    Map<String, BgArt73Para6FilePersonRow> persons = new LinkedHashMap<>();

    for (BgArt73Para6Record record : ordered(report.getRecordList())) {
      String key = personKey(record);
      BgArt73Para6FilePersonRow person = persons.get(key);
      if (person == null) {
        person = person(record);
        persons.put(key, person);
        attachAnnual(person, annuals.get(key));
      } else {
        // ⚠️ ПРОТИВОРЕЧИЕТО СЕ ЗАПОМНЯ, А НЕ СЕ РАЗРЕШАВА. Виж javadoc-а на полето.
        if (person.isMainEmployer() != isMainEmployer(record)) {
          person.setMainEmployerConflict(true);
        }
      }
      merge(person, record);
    }

    // ⚠️ ПО КЛЮЧА НА ЗАПИСА, А НЕ по идентификатора: лицата се ключуват по СЛУЖИТЕЛ, когато има
    // такъв, и втора сметка по идентификатора би подминала точно тях.
    for (Map.Entry<String, BgArt73Para6FilePersonRow> entry : persons.entrySet()) {
      BgArt73Para6FilePersonRow person = entry.getValue();
      person.setTotalTaxWithheld(totalTaxWithheld(person, annuals.get(entry.getKey())));
      content.getPersons().add(person);
    }
    return content;
  }

  /** Част ІІ, ред на лицето — без вложените доходи. */
  private static BgArt73Para6FilePersonRow person(BgArt73Para6Record record) {
    BgArt73Para6FilePersonRow person = new BgArt73Para6FilePersonRow();
    person.setCorrectionCode(trim(record.getCorrectionCode()));
    person.setFirstName(trim(record.getFirstName()));
    person.setMiddleName(trim(record.getMiddleName()));
    person.setLastName(trim(record.getLastName()));
    person.setIdentifierType(trim(record.getIdentifierType()));
    person.setIdentifier(trim(record.getIdentifier()));
    person.setMainEmployer(isMainEmployer(record));
    return person;
  }

  /**
   * Добавя записания ред като ВЛОЖЕН ред доход — или го слива с вече наличния по т. 15.
   *
   * <p>⚠️ Сливането СЪБИРА сумите, вместо да отхвърли втория ред. Заповедта иска един ред на
   * тройката; два реда в един файл биха отнесли ЦЕЛИЯ файл (т. 3).
   */
  private static void merge(BgArt73Para6FilePersonRow person, BgArt73Para6Record record) {
    String code = codeOf(record.getIncomeType());
    String employer = trim(record.getPayerIdentifier());
    String predecessor = trim(record.getPredecessorIdentifier());

    for (BgArt73Para6FileIncomeRow existing : person.getIncomeRows()) {
      if (sameKey(existing, code, employer, predecessor)) {
        existing.setIncome(sum(existing.getIncome(), record.getIncome()));
        existing.setInsuranceBg(sum(existing.getInsuranceBg(), record.getInsurance()));
        existing.setInsuranceForeign(
            sum(existing.getInsuranceForeign(), record.getInsuranceAbroad()));
        existing.setAdvanceTax(sum(existing.getAdvanceTax(), record.getAdvanceTax()));
        return;
      }
    }
    BgArt73Para6FileIncomeRow row = new BgArt73Para6FileIncomeRow();
    row.setIncomeCode(code);
    row.setEmployerIdentifier(employer);
    row.setEmployerName(trim(record.getPayerName()));
    row.setIncome(record.getIncome());
    row.setInsuranceBg(record.getInsurance());
    row.setInsuranceForeign(record.getInsuranceAbroad());
    row.setAdvanceTax(record.getAdvanceTax());
    row.setPredecessorIdentifier(predecessor);
    person.getIncomeRows().add(row);
  }

  /**
   * Тройката по т. 15/16 — код, работодател и НАЛИЧИЕТО на ЕИК на преобразуващо се предприятие.
   *
   * <p>⚠️ Сравнява се НАЛИЧИЕ, не стойност: т. 16 разделя „платеното от правоприемника“ от
   * „платеното от преобразуващото се“, а два различни преобразуващи се по същия код при същия
   * работодател не са случай, който заповедта описва.
   */
  private static boolean sameKey(
      BgArt73Para6FileIncomeRow row, String code, String employer, String predecessor) {
    return eq(row.getIncomeCode(), code)
        && eq(row.getEmployerIdentifier(), employer)
        && (row.getPredecessorIdentifier() != null) == (predecessor != null);
  }

  /** {@code taxbase251}/{@code taxbase253} и блокът по чл. 49 от записания годишен ред. */
  private static void attachAnnual(BgArt73Para6FilePersonRow person, BgArt73Para6Annual source) {
    if (source == null) {
      return;
    }
    person.setTaxBaseArt25Para1(source.getBaseArt25Para1());
    person.setTaxBaseArt25Para3(source.getBaseArt25Para3());

    BgArt73Para6FileAnnual annual = new BgArt73Para6FileAnnual();
    annual.setTaxBase(source.getBaseArt49());
    annual.setReliefDisability(source.getReliefDisability());
    annual.setReliefVoluntaryPension(source.getReliefVoluntaryPension());
    annual.setReliefVoluntaryHealthLife(source.getReliefVoluntaryHealthLife());
    annual.setReliefServiceYears(source.getReliefServiceYears());
    annual.setReliefDonation01(source.getReliefDonationsCode01());
    annual.setReliefDonation02(source.getReliefDonationsCode02());
    annual.setReliefDonation03(source.getReliefDonationsCode03());
    annual.setReliefChildren(source.getReliefChildren());
    annual.setReliefDisabledChildren(source.getReliefDisabledChildren());
    annual.setTaxBaseAfterReliefs(source.getTaxableBase());
    annual.setTax(source.getAnnualTax());
    annual.setSettlement(source.getSettlement());
    annual.setWithheldByJanuary(source.getToWithhold());
    annual.setRefundedByJanuary(source.getToRefund());
    person.setAnnual(annual);
  }

  /**
   * {@code sumtaxdeducted} — ЗАПИСАНОТО число, а изчисление само когато годишен ред НЯМА.
   *
   * <p><b>⚠️ ЗАПИСАНОТО ПОБЕЖДАВА, ЗАЩОТО ЕКРАНЪТ ПОКАЗВА НЕГО.</b> Колона 22 е на екрана на
   * годишния ред; сметната наново тук, тя щеше да сложи във файла друго число от одобреното.
   *
   * <p><b>⚠️ ПРИСЪСТВИЕТО НА РЕДА Е ВЪПРОСЪТ, А НЕ СТОЙНОСТТА МУ.</b> Първата редакция питаше дали
   * записаното число липсва и падаше обратно към сметка. Такъв въпрос е безсмислен: генераторът на
   * Axelor дава на {@code decimal} стойност по подразбиране {@code ZERO}, тоест резервната сметка
   * беше мъртъв код, а нула от още непопълнен годишен ред е неразличима от истинска нула. Виж
   * {@code BgDefaultedGetterTest}. Затова правилото е просто: има ли годишен ред, неговото число е
   * отговорът — и нулата е число.
   *
   * <p><b>⚠️ БЕЗ ГОДИШЕН РЕД СМЕТКАТА Е САМО СБОРЪТ НА АВАНСОВИЯ ДАНЪК.</b> Работодател, който НЕ е
   * по основно правоотношение, няма годишно изравняване — тоест колони 20 и 21 не съществуват за
   * него. Полето обаче остава задължително за прием, щом има {@code incomedata}; пропуснато, то
   * вали ЦЕЛИЯ файл (заповедта, т. 3).
   */
  private static BigDecimal totalTaxWithheld(
      BgArt73Para6FilePersonRow person, BgArt73Para6Annual source) {
    if (!person.hasIncomeData()) {
      return null;
    }
    if (source != null) {
      return source.getTotalWithheld();
    }
    BigDecimal total = BigDecimal.ZERO;
    for (BgArt73Para6FileIncomeRow row : person.getIncomeRows()) {
      total = sum(total, row.getAdvanceTax());
    }
    return total;
  }

  /** Годишните редове по същия ключ като лицата — служителят, с връщане към идентификатора. */
  private static Map<String, BgArt73Para6Annual> annualsByEmployee(
      List<BgArt73Para6Annual> annuals) {
    Map<String, BgArt73Para6Annual> byKey = new LinkedHashMap<>();
    if (annuals == null) {
      return byKey;
    }
    for (BgArt73Para6Annual annual : annuals) {
      // ⚠️ Годишен ред без служител НЕ се подрежда по подразбиращ се ключ: той щеше да се залепи за
      // първото лице без идентификатор и да му припише чуждо изравняване.
      if (annual.getEmployee() != null && annual.getEmployee().getId() != null) {
        byKey.put(employeeKey(annual.getEmployee(), null), annual);
      }
    }
    return byKey;
  }

  /**
   * КОЙ Е ТОВА ЛИЦЕ — по служител, а при липса на служител по идентификатор.
   *
   * <p>⚠️ Ключ само по идентификатор би слял две лица с празен идентификатор в едно; ключ само по
   * служител би оставил ръчно добавен ред без служител без дом.
   */
  private static String personKey(BgArt73Para6Record record) {
    return employeeKey(record.getEmployee(), trim(record.getIdentifier()));
  }

  private static String employeeKey(Employee employee, String identifier) {
    if (employee != null && employee.getId() != null) {
      return "E#" + employee.getId();
    }
    return "I#" + (identifier == null ? "" : identifier);
  }

  private static boolean isMainEmployer(BgArt73Para6Record record) {
    // ⚠️ Axelor НИКОГА не връща null за Boolean — виж BgDefaultedGetterTest.
    return Boolean.TRUE.equals(record.getIsMainEmployer());
  }

  private static String codeOf(BgIncomeType incomeType) {
    return incomeType == null ? null : trim(incomeType.getCode());
  }

  /** Подредбата е по {@code id} — детерминирана, за да са възпроизводими байтовете. */
  private static List<BgArt73Para6Record> ordered(List<BgArt73Para6Record> records) {
    List<BgArt73Para6Record> copy = new ArrayList<>();
    if (records != null) {
      copy.addAll(records);
    }
    copy.sort(Comparator.comparing(r -> r.getId() == null ? Long.MAX_VALUE : r.getId()));
    return copy;
  }

  private static BigDecimal sum(BigDecimal a, BigDecimal b) {
    if (a == null) {
      return b;
    }
    return b == null ? a : a.add(b);
  }

  private static boolean eq(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
