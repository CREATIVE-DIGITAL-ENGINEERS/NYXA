package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ТРИТЕ ПОЛЕТА, БЕЗ КОИТО ФИШЪТ НЕ СЕ СМЯТА — вид осигурен, основна заплата и НКПД.
 *
 * <p>Внесен договор с празни {@code bgInsuredTypeCode}, {@code bgBaseSalary} и {@code bgNkpd} дава
 * фиш, на който генераторите отказват пет пера, между които ЦЕЛИЯТ блок 5xx — {@code
 * INPUT_NOT_ASSEMBLED: видът осигурен не е попълнен по договора}.
 *
 * <p><b>И ТРИТЕ ИМАТ АВТОРИТЕТЕН ИЗТОЧНИК В MICROINVEST</b>, сверен срещу самата база:
 *
 * <ul>
 *   <li><b>Вид осигурен</b> — {@code Dekl1Value} с {@code FormRefNo = "12"}. Стойността е била
 *       ПОДАДЕНА пред НАП за съответния месец. Всеки payroll с Д1 носи ТОЧНО ЕДИН отделен код;
 *       двусмислие няма.
 *   <li><b>Основна заплата</b> — {@code VariablesHistory} → {@code FormulaSalaryHistory} за {@code
 *       FormulaSalaryID = 65} („Основно възнаграждение“). ⚠️ ТОВА Е ВХОД НА ФОРМУЛНИЯ ЕНДЖИН, а не
 *       перо: {@code ValueSalary} носи ПЛАТЕНОТО за месеца (непълен месец, неплатен отпуск,
 *       болничен), а договорната заплата е това число.
 *   <li><b>НКПД</b> — {@code PayrollKPDs} → {@code KPDs}. ⚠️ {@code KPDs.Code} САМОСТОЯТЕЛНО
 *       („2001“/„0004“) не се застъпва с 8-цифрения {@code BgNkpd}. Нужна е и колоната {@code
 *       KPDs.Section}: схемата е {@code nvarchar(4) Section + nvarchar(4) Code}, а двете половини
 *       СА официалният код — 5120+2001 = 51202001 „Готвач“. И петте кода на дружеството се намират
 *       ТОЧНО в {@code bg_nkpd}, заедно с наименованията.
 * </ul>
 *
 * <p><b>⚠️ КОНКАТЕНАЦИЯТА НЕ Е ИЗМИСЛЯНЕ НА КОД.</b> Тя възстановява как Microinvest пази ЕДИН код
 * в две колони, а резултатът се сверява с речника {@code BgNkpd} и се ОТХВЪРЛЯ, ако го няма там.
 * Никакво допълване с нули, никакво сравнение по прилика на текст.
 *
 * <p><b>⚠️ ЗАЛИЧЕНИТЕ ИЗТОЧНИЦИ ОТПАДАТ САМИ.</b> Класът не чете {@code Payrolls.IsDeleted} —
 * получава {@code contractByPayrollId}, а {@link MicroinvestPilotImportService} слага там САМО
 * незаличени редове. Тоест заличен {@code PayrollID} не намира договор и не може нито да създаде,
 * нито да обнови такъв.
 */
public final class MicroinvestContractPayrollFields {

  /** Точката на Декларация обр. 1, която носи вида осигурен. */
  static final String D1_INSURED_TYPE_FORM_REF = "12";

  public enum Outcome {
    /** Полето беше празно и изворът го попълни. */
    UPDATED,
    /** Договорът вече носи точно тази стойност — нищо не се записва. */
    UNCHANGED,
    /** Изворът не носи стойност. */
    SKIPPED,
    /** Договорът носи РЪЧНА стойност, различна от изворовата — не се презаписва. */
    CONFLICT,
    /** Изворът носи противоречива или невалидна стойност — иска човек. */
    FAILED
  }

  /** Присъда за един договор по едно поле — с цялата следа към извора. */
  public static final class FieldResult {
    public final Outcome outcome;
    public final String existingValue;
    public final String proposedValue;
    public final LocalDate effectiveDate;
    public final String sourceRecord;
    public final String detail;

    FieldResult(
        Outcome outcome,
        String existingValue,
        String proposedValue,
        LocalDate effectiveDate,
        String sourceRecord,
        String detail) {
      this.outcome = outcome;
      this.existingValue = existingValue;
      this.proposedValue = proposedValue;
      this.effectiveDate = effectiveDate;
      this.sourceRecord = sourceRecord;
      this.detail = detail;
    }

    static FieldResult of(Outcome o, String existing, String proposed, String src, String detail) {
      return new FieldResult(o, existing, proposed, null, src, detail);
    }
  }

  /** Присъдите за един договор по трите полета. */
  public static final class ContractResult {
    public final EmploymentContract contract;

    /** Източниковите {@code PayrollID}-та, довели до този договор — следата към Microinvest. */
    public final Set<Integer> sourcePayrollIds = new LinkedHashSet<>();

    public FieldResult insuredType;
    public FieldResult baseSalary;
    public FieldResult nkpd;

    /** Договорени часове на ден — без тях нито едно перо от 5xx не се ражда. */
    public FieldResult hoursPerDay;

    /** Законоустановени часове на ден за длъжността (Д1 т. 16 поз. 3). */
    public FieldResult legalHoursPerDay;

    /** Категория труд — пише се върху СЛУЖИТЕЛЯ, не върху договора. */
    public FieldResult laborCategory;

    BigDecimal resolvedHoursPerDay;
    BigDecimal resolvedLegalHoursPerDay;
    String resolvedLaborCategory;

    /** NKPD-то, което {@link #apply} трябва да запише — резолвнато от речника. */
    BgNkpd resolvedNkpd;

    /** Заплатата, която {@link #apply} трябва да запише, с точната десетична точност от извора. */
    BigDecimal resolvedSalary;

    ContractResult(EmploymentContract contract) {
      this.contract = contract;
    }
  }

  private MicroinvestContractPayrollFields() {}

  /**
   * Изчислява присъдите БЕЗ да записва каквото и да било.
   *
   * @param contractByPayrollId само НЕзаличени Payroll редове → договор
   * @param payrollsById източниковите Payroll редове (носят заплатата и НКПД)
   * @param d1Values редовете от {@code Dekl1Value} за тази компания
   * @param knownInsuredTypeCodes кодовете от речника {@code BgInsuredType}
   * @param nkpdByCode речникът {@code BgNkpd}, по код
   * @param operativeDate краят на оперативния период, за който се търси валидната заплата
   */
  public static List<ContractResult> resolve(
      Map<Integer, EmploymentContract> contractByPayrollId,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      Collection<MicroinvestSourceModels.Dekl1FieldValue> d1Values,
      Set<String> knownInsuredTypeCodes,
      Map<String, BgNkpd> nkpdByCode,
      LocalDate operativeDate) {
    return resolve(
        contractByPayrollId,
        payrollsById,
        d1Values,
        knownInsuredTypeCodes,
        java.util.Collections.emptySet(),
        nkpdByCode,
        operativeDate);
  }

  /**
   * @param selfInsuredOnlyCodes кодове, които по СОБСТВЕНИЯ си речник означават самоосигуряващо се
   *     лице без работодател ({@code BgInsuredType.bgFloorType = MIN_INCOME_SOL}) — те нямат какво
   *     да търсят на трудов договор
   */
  public static List<ContractResult> resolve(
      Map<Integer, EmploymentContract> contractByPayrollId,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      Collection<MicroinvestSourceModels.Dekl1FieldValue> d1Values,
      Set<String> knownInsuredTypeCodes,
      Set<String> selfInsuredOnlyCodes,
      Map<String, BgNkpd> nkpdByCode,
      LocalDate operativeDate) {

    Map<EmploymentContract, TreeMap<LocalDate, Set<String>>> insuredByContract =
        new LinkedHashMap<>();
    Map<EmploymentContract, ContractResult> results = new LinkedHashMap<>();

    for (Map.Entry<Integer, EmploymentContract> e : contractByPayrollId.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      results.computeIfAbsent(e.getValue(), ContractResult::new).sourcePayrollIds.add(e.getKey());
      insuredByContract.computeIfAbsent(e.getValue(), k -> new TreeMap<>());
    }

    if (d1Values != null) {
      for (MicroinvestSourceModels.Dekl1FieldValue v : d1Values) {
        if (v == null || !D1_INSURED_TYPE_FORM_REF.equals(trim(v.formRefNo))) {
          continue;
        }
        EmploymentContract contract = contractByPayrollId.get(v.payrollId);
        if (contract == null) {
          continue;
        }
        String code = normalizeInsuredTypeCode(v.value);
        if (code == null) {
          continue;
        }
        insuredByContract
            .computeIfAbsent(contract, k -> new TreeMap<>())
            .computeIfAbsent(v.date, k -> new LinkedHashSet<>())
            .add(code);
      }
    }

    for (Map.Entry<EmploymentContract, ContractResult> entry : results.entrySet()) {
      EmploymentContract contract = entry.getKey();
      ContractResult r = entry.getValue();

      r.insuredType =
          resolveInsuredType(
              contract,
              insuredByContract.get(contract),
              knownInsuredTypeCodes,
              selfInsuredOnlyCodes);
      resolveBaseSalary(r, payrollsById, operativeDate);
      resolveNkpd(r, payrollsById, nkpdByCode);
      resolveWorkingTime(r, payrollsById, operativeDate);
    }

    return new ArrayList<>(results.values());
  }

  // ═══════════════════════════ вид осигурен ═══════════════════════════

  private static FieldResult resolveInsuredType(
      EmploymentContract contract,
      TreeMap<LocalDate, Set<String>> perDate,
      Set<String> knownInsuredTypeCodes,
      Set<String> selfInsuredOnlyCodes) {

    String existing = emptyToNull(contract.getBgInsuredTypeCode());

    if (perDate == null || perDate.isEmpty()) {
      return FieldResult.of(
          Outcome.SKIPPED,
          existing,
          null,
          "Dekl1Value/FormRefNo=12",
          "изворът няма точка 12 на Декларация обр. 1 за нито един период на този договор."
              + " ⚠️ НЕ се подразбира 01: за учител (08), държавен служител (05), допълнителен"
              + " договор (04) и морско лице (92) той избира ДРУГ набор ставки, тоест грешна"
              + " вноска, която никъде не изглежда като грешка.");
    }

    Set<String> distinct = new LinkedHashSet<>();
    for (Set<String> codes : perDate.values()) {
      distinct.addAll(codes);
    }

    // ⚠️ ПРОТИВОРЕЧИЕТО НЕ СЕ РАЗРЕШАВА ОТ НАС. „Последната дата печели“ би избрало мълчаливо и би
    // изглеждало като извод от данните. Договор, подаван ту под един, ту под друг вид осигурен, е
    // или същинска смяна (иска анекс и дата), или грешка в подаденото — и двете са въпрос към ТРЗ.
    if (distinct.size() > 1) {
      StringBuilder history = new StringBuilder();
      for (Map.Entry<LocalDate, Set<String>> e : perDate.entrySet()) {
        if (history.length() > 0) {
          history.append("; ");
        }
        history.append(e.getKey()).append("=").append(String.join("/", e.getValue()));
      }
      return FieldResult.of(
          Outcome.FAILED,
          existing,
          null,
          "Dekl1Value/FormRefNo=12",
          "изворът подава РАЗЛИЧНИ видове осигурен за този договор (" + history + ")");
    }

    String code = distinct.iterator().next();
    LocalDate lastDate = perDate.lastKey();

    if (!knownInsuredTypeCodes.contains(code)) {
      return new FieldResult(
          Outcome.FAILED,
          existing,
          code,
          lastDate,
          "Dekl1Value/FormRefNo=12",
          "код „" + code + "“ го няма в речника BgInsuredType");
    }

    // ⚠️ САМООСИГУРЯВАЩ СЕ КОД НЯМА КАКВО ДА ТЪРСИ НА ТРУДОВ ДОГОВОР, и това го казват САМИТЕ
    // данни: речникът обявява bgFloorType = MIN_INCOME_SOL, а всичките редове със ставки за такъв
    // код носят нула за осигурителя. Изворът все пак подава такова лице под точка 12 (напр. код
    // 12 — управител по ДУК), защото в Microinvest то виси на payroll запис.
    // Записан върху договора, кодът произвежда фиш без вноска за сметка на работодателя и минава
    // за редовен — затова се ОТКАЗВА и се назовава правилният път: регистрация на самоосигуряващо
    // се лице (BgSelfInsuredRegistration), а не трудов договор. Пазено и от
    // BgInsuredTypeHomeDbTest.
    if (selfInsuredOnlyCodes.contains(code)) {
      return new FieldResult(
          Outcome.FAILED,
          existing,
          code,
          lastDate,
          "Dekl1Value/FormRefNo=12",
          "код „"
              + code
              + "“ е САМООСИГУРЯВАЩО СЕ ЛИЦЕ БЕЗ РАБОТОДАТЕЛ (bgFloorType = MIN_INCOME_SOL) и не"
              + " се записва върху трудов договор — лицето се въвежда като регистрация на"
              + " самоосигуряващо се лице");
    }
    if (code.equals(existing)) {
      return new FieldResult(
          Outcome.UNCHANGED, existing, code, lastDate, "Dekl1Value/FormRefNo=12", "вече попълнен");
    }
    if (existing != null) {
      return new FieldResult(
          Outcome.CONFLICT,
          existing,
          code,
          lastDate,
          "Dekl1Value/FormRefNo=12",
          "ръчната стойност се ЗАПАЗВА; изчистете полето и пуснете вноса наново, ако изворът е"
              + " верният");
    }
    return new FieldResult(
        Outcome.UPDATED, null, code, lastDate, "Dekl1Value/FormRefNo=12", "от точка 12 на Д1");
  }

  // ═══════════════════════════ основна заплата ═══════════════════════════

  /**
   * ⚠️ ИЗБОРЪТ Е ПО ДАТА, А НЕ „НАЙ-НОВИЯТ РЕД“. Microinvest пренася реда напред за ВСЕКИ месец и
   * след като договорът е приключил — PayrollID 1 е спрял на 26.07.2022 г., но носи ред и за
   * 01.07.2026 г. със старата, предевровата стойност. Затова се взима последната стойност, валидна
   * към по-ранното от: края на оперативния период и края на САМИЯ ИЗВОРОВ РЕД (виж {@link
   * #pickLatest}) — НЕ края на договора в Nyxa, който Фаза 1 е попълнила от корена на веригата.
   *
   * <p>⚠️ Нулата НЕ е заплата. Заличеният PayrollID 1019 носи точно 0,00 — ако нулата се приемеше,
   * тя щеше да влезе като договорна заплата и фишът щеше да излезе „сметнат“ с нула бруто.
   */
  private static void resolveBaseSalary(
      ContractResult r,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      LocalDate operativeDate) {

    // ⚠️ БЕЗ проверка за null: генерираният getter връща BigDecimal.ZERO и никога null (виж
    // BgDefaultedGetterTest). „Няма стойност“ се изразява със ЗНАКА, не с препратката.
    BigDecimal existing = r.contract.getBgBaseSalary();
    String existingText = existing.signum() == 0 ? null : existing.toPlainString();

    LocalDate cap = operativeDate;

    Point best = pickLatest(r, payrollsById, cap, p -> p.baseSalaryHistory);
    LocalDate bestDate = best.date;
    BigDecimal bestValue = best.value;
    int bestPayroll = best.payrollId;

    String src = "VariablesHistory/FormulaSalaryID=65";

    if (bestValue == null) {
      r.baseSalary =
          FieldResult.of(
              Outcome.SKIPPED,
              existingText,
              null,
              src,
              "изворът няма договорна основна заплата, валидна към " + cap);
      return;
    }
    if (bestValue.signum() <= 0) {
      r.baseSalary =
          new FieldResult(
              Outcome.SKIPPED,
              existingText,
              bestValue.toPlainString(),
              bestDate,
              src + " (PayrollID " + bestPayroll + ")",
              "изворът дава нула — нулата не е договорна заплата и не се записва");
      return;
    }

    String proposed = bestValue.toPlainString();

    if (existing.signum() != 0) {
      if (existing.compareTo(bestValue) == 0) {
        r.baseSalary =
            new FieldResult(
                Outcome.UNCHANGED,
                existingText,
                proposed,
                bestDate,
                src + " (PayrollID " + bestPayroll + ")",
                "вече попълнена със същата стойност");
        return;
      }
      r.baseSalary =
          new FieldResult(
              Outcome.CONFLICT,
              existingText,
              proposed,
              bestDate,
              src + " (PayrollID " + bestPayroll + ")",
              "ръчната стойност се ЗАПАЗВА");
      return;
    }

    r.resolvedSalary = bestValue;
    r.baseSalary =
        new FieldResult(
            Outcome.UPDATED,
            null,
            proposed,
            bestDate,
            src + " (PayrollID " + bestPayroll + ")",
            "договорна основна заплата, валидна към " + bestDate);
  }

  // ═══════════════════════════ работно време ═══════════════════════════

  /**
   * Договорените и законоустановените часове на ден — по СЪЩОТО правило по дата като заплатата.
   *
   * <p><b>⚠️ БЕЗ ТЯХ ФИШЪТ ИЗЛИЗА БЕЗ ВНОСКИ.</b> Фиш с вид осигурен, заплата и НКПД пак произвежда
   * нула пера от 5xx, а отказът назовава {@code EmploymentContract.bgHoursPerDay}. Причината е НЕВД
   * чл. 1 — ал. 3 разпределя осигурителния праг по ДНИ (пълно работно време), ал. 4 по ЧАСОВЕ
   * (непълно), а двете дават различно число за един и същ месец. Затова резолверът отказва, вместо
   * да избере праг наужким.
   */
  private static void resolveWorkingTime(
      ContractResult r,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      LocalDate operativeDate) {

    LocalDate cap = operativeDate;

    Point hours = pickLatest(r, payrollsById, cap, p -> p.hoursPerDayHistory);
    Point legal = pickLatest(r, payrollsById, cap, p -> p.legalHoursPerDayHistory);
    Point category = pickLatest(r, payrollsById, cap, p -> p.laborCategoryHistory);

    r.hoursPerDay =
        decimalOutcome(
            r.contract.getBgHoursPerDay(),
            hours,
            "VariablesHistory/„Работно време в часове на ден“",
            cap);
    if (r.hoursPerDay.outcome == Outcome.UPDATED) {
      r.resolvedHoursPerDay = hours.value;
    }

    r.legalHoursPerDay =
        decimalOutcome(
            r.contract.getBgLegalHoursPerDay(),
            legal,
            "VariablesHistory/„Законоустановено работно време в часове на ден“",
            cap);
    if (r.legalHoursPerDay.outcome == Outcome.UPDATED) {
      r.resolvedLegalHoursPerDay = legal.value;
    }

    r.laborCategory = laborCategoryOutcome(r, category, cap);
    if (r.laborCategory.outcome == Outcome.UPDATED) {
      r.resolvedLaborCategory = r.laborCategory.proposedValue;
    }
  }

  /**
   * Категория труд — НИЗ „1“/„2“/„3“, не число.
   *
   * <p>⚠️ Стойността живее върху СЛУЖИТЕЛЯ ({@code Employee.bgLaborCategory}), а не върху договора:
   * категорията е свойство на труда на лицето. Изворът я пази на ниво payroll, затова се резолвва
   * тук и се записва зад договора.
   *
   * <p>⚠️ Приемат се само трите законови категории по КСО. Всичко друго е отказ, не закръгляне —
   * ставките се търсят по двойката (вид осигурен, категория) и невалидна категория дава празен
   * набор, тоест фиш без вноски.
   */
  private static FieldResult laborCategoryOutcome(ContractResult r, Point found, LocalDate cap) {
    String source = "VariablesHistory/„Категория труд“";
    com.axelor.apps.hr.db.Employee employee = r.contract.getEmployee();
    String existing = employee == null ? null : emptyToNull(employee.getBgLaborCategory());

    if (found.value == null) {
      return FieldResult.of(
          Outcome.SKIPPED,
          existing,
          null,
          source,
          "изворът няма категория труд, валидна към " + cap);
    }
    String code = found.value.stripTrailingZeros().toPlainString();
    String src = source + " (PayrollID " + found.payrollId + ")";

    if (!"1".equals(code) && !"2".equals(code) && !"3".equals(code)) {
      return new FieldResult(
          Outcome.FAILED,
          existing,
          code,
          found.date,
          src,
          "категория „" + code + "“ не е нито I, нито II, нито III по КСО");
    }
    if (employee == null) {
      return new FieldResult(
          Outcome.FAILED, null, code, found.date, src, "договорът няма служител зад себе си");
    }
    if (code.equals(existing)) {
      return new FieldResult(Outcome.UNCHANGED, existing, code, found.date, src, "вече попълнена");
    }
    if (existing != null) {
      return new FieldResult(
          Outcome.CONFLICT, existing, code, found.date, src, "ръчната стойност се ЗАПАЗВА");
    }
    return new FieldResult(
        Outcome.UPDATED, null, code, found.date, src, "валидна към " + found.date);
  }

  /** Един избран източников отчет: стойност, дата и кой Payroll я е дал. */
  private static final class Point {
    LocalDate date;
    BigDecimal value;
    int payrollId;
  }

  /**
   * Последната стойност, ВАЛИДНА към {@code cap}, по всички payroll-и на договора. Равни дати се
   * разрешават по по-голям {@code PayrollID} — по-късният анекс на веригата, а не редът на четене.
   *
   * <p><b>⚠️ ВСЕКИ РЕД СЕ КАПВА ПО СОБСТВЕНИЯ СИ КРАЙ, а не по края на договора в Nyxa.</b>
   * Microinvest пренася стойността на всеки {@code Payroll} ред напред за ВСЕКИ месец и след като
   * този ред е приключил: PayrollID 18 спира на 28.02.2025 г. със 700,00 лв., но носи ред и за
   * 01.07.2026 г. с нея — в един и същи месец с 465,15 лв. от действащия анекс 1022. Без капването
   * изборът между двете пада на случайността (по-голям PayrollID), а срещу истинска верига това не
   * е избор, а съвпадение.
   *
   * <p>⚠️ И НЕ ПО {@code contract.getEndDate()}: точно тя беше грешната — Фаза 1 я е попълнила от
   * КОРЕНА на веригата (виж {@link MicroinvestPayrollChainResolver}), тоест капваше живия договор
   * към отдавна отминала дата и връщаше старата заплата. Собственият край на реда идва направо от
   * извора и не зависи от това какво Nyxa вече е записала.
   */
  private static Point pickLatest(
      ContractResult r,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      LocalDate cap,
      java.util.function.Function<
              MicroinvestSourceModels.Payroll, List<MicroinvestSourceModels.BaseSalaryPoint>>
          selector) {

    Point best = new Point();
    for (Integer payrollId : r.sourcePayrollIds) {
      MicroinvestSourceModels.Payroll p = payrollsById == null ? null : payrollsById.get(payrollId);
      if (p == null) {
        continue;
      }
      List<MicroinvestSourceModels.BaseSalaryPoint> points = selector.apply(p);
      if (points == null) {
        continue;
      }
      // Собственият край на реда, но никога след оперативната дата.
      LocalDate rowCap = p.toDate != null && p.toDate.isBefore(cap) ? p.toDate : cap;
      for (MicroinvestSourceModels.BaseSalaryPoint point : points) {
        if (point == null
            || point.date == null
            || point.value == null
            || point.date.isAfter(rowCap)) {
          continue;
        }
        if (best.date == null
            || point.date.isAfter(best.date)
            || (point.date.equals(best.date) && payrollId > best.payrollId)) {
          best.date = point.date;
          best.value = point.value;
          best.payrollId = payrollId;
        }
      }
    }
    return best;
  }

  /**
   * Общата присъда за десетично поле, чийто getter НИКОГА не връща null (виж {@code
   * BgDefaultedGetterTest}) — „няма стойност“ се изразява със знака.
   */
  private static FieldResult decimalOutcome(
      BigDecimal existing, Point found, String source, LocalDate cap) {

    String existingText = existing.signum() == 0 ? null : existing.toPlainString();

    if (found.value == null) {
      return FieldResult.of(
          Outcome.SKIPPED, existingText, null, source, "изворът няма стойност, валидна към " + cap);
    }
    if (found.value.signum() <= 0) {
      return new FieldResult(
          Outcome.SKIPPED,
          existingText,
          found.value.toPlainString(),
          found.date,
          source + " (PayrollID " + found.payrollId + ")",
          "изворът дава нула — не се записва");
    }

    String proposed = found.value.toPlainString();
    String src = source + " (PayrollID " + found.payrollId + ")";

    if (existing.signum() != 0) {
      return existing.compareTo(found.value) == 0
          ? new FieldResult(
              Outcome.UNCHANGED, existingText, proposed, found.date, src, "вече попълнено")
          : new FieldResult(
              Outcome.CONFLICT,
              existingText,
              proposed,
              found.date,
              src,
              "ръчната стойност се ЗАПАЗВА");
    }
    return new FieldResult(
        Outcome.UPDATED, null, proposed, found.date, src, "валидно към " + found.date);
  }

  // ═══════════════════════════ НКПД ═══════════════════════════

  /**
   * ⚠️ НАЙ-НОВАТА ВЕРСИЯ НА НОМЕНКЛАТУРАТА, а не „първият намерен ред“. Една и съща длъжност стои
   * по веднъж за всяка {@code KPDVersionID}. Изборът по най-голям номер е детерминиран; две
   * РАЗЛИЧНИ длъжности в една и съща версия са противоречие и искат човек.
   */
  private static void resolveNkpd(
      ContractResult r,
      Map<Integer, MicroinvestSourceModels.Payroll> payrollsById,
      Map<String, BgNkpd> nkpdByCode) {

    BgNkpd existing = r.contract.getBgNkpd();
    String existingText = existing == null ? null : existing.getCode();

    int bestVersion = Integer.MIN_VALUE;
    Set<String> codesAtBestVersion = new LinkedHashSet<>();
    String sampleDescription = null;
    int samplePayrollKpdId = 0;

    for (Integer payrollId : r.sourcePayrollIds) {
      MicroinvestSourceModels.Payroll p = payrollsById == null ? null : payrollsById.get(payrollId);
      if (p == null || p.nkpdCodes == null) {
        continue;
      }
      for (MicroinvestSourceModels.NkpdAssignment a : p.nkpdCodes) {
        String full = normalizeNkpd(a);
        if (full == null) {
          continue;
        }
        if (a.kpdVersionId > bestVersion) {
          bestVersion = a.kpdVersionId;
          codesAtBestVersion.clear();
          sampleDescription = a.description;
          samplePayrollKpdId = a.payrollKpdId;
        }
        if (a.kpdVersionId == bestVersion) {
          codesAtBestVersion.add(full);
        }
      }
    }

    String src = "PayrollKPDs+KPDs(Section||Code)";

    if (codesAtBestVersion.isEmpty()) {
      r.nkpd =
          FieldResult.of(
              Outcome.SKIPPED, existingText, null, src, "изворът няма НКПД за този договор");
      return;
    }
    if (codesAtBestVersion.size() > 1) {
      r.nkpd =
          FieldResult.of(
              Outcome.FAILED,
              existingText,
              null,
              src,
              "изворът дава РАЗЛИЧНИ НКПД кодове в една и съща версия "
                  + bestVersion
                  + " ("
                  + String.join("/", codesAtBestVersion)
                  + ")");
      return;
    }

    String code = codesAtBestVersion.iterator().next();
    String srcFull = src + " v" + bestVersion + " #" + samplePayrollKpdId;

    // ⚠️ РЕЧНИКЪТ Е ПОСЛЕДНАТА ДУМА. Кодът е възстановен от две колони на извора; ако точно този
    // осемцифрен код го няма в BgNkpd, той НЕ се записва — нито се допълва, нито се приближава.
    BgNkpd dictionary = nkpdByCode == null ? null : nkpdByCode.get(code);
    if (dictionary == null) {
      r.nkpd =
          FieldResult.of(
              Outcome.FAILED,
              existingText,
              code,
              srcFull,
              "код „"
                  + code
                  + "“ ("
                  + sampleDescription
                  + ") го няма в речника BgNkpd — НЕ се налучква приблизителен");
      return;
    }
    if (existing != null && code.equals(existingText)) {
      r.nkpd = FieldResult.of(Outcome.UNCHANGED, existingText, code, srcFull, "вече попълнен");
      return;
    }
    if (existing != null) {
      r.nkpd =
          FieldResult.of(
              Outcome.CONFLICT, existingText, code, srcFull, "ръчната стойност се ЗАПАЗВА");
      return;
    }

    r.resolvedNkpd = dictionary;
    r.nkpd =
        FieldResult.of(
            Outcome.UPDATED, null, code, srcFull, "Section+Code от НКПД версия " + bestVersion);
  }

  /** {@code Section}+{@code Code}, само ако и двете са цифри и дават точно осем знака. */
  static String normalizeNkpd(MicroinvestSourceModels.NkpdAssignment a) {
    if (a == null) {
      return null;
    }
    String section = trim(a.section);
    String code = trim(a.code);
    if (section == null || code == null || section.isEmpty() || code.isEmpty()) {
      return null; // йерархично ниво, не длъжност
    }
    String full = section + code;
    if (full.length() != 8 || !full.chars().allMatch(Character::isDigit)) {
      return null;
    }
    return full;
  }

  // ═══════════════════════════ запис ═══════════════════════════

  /**
   * Записва САМО присъдите {@link Outcome#UPDATED}. Връща броя докоснати договори.
   *
   * <p>⚠️ Не докосва нито едно друго поле — длъжност, дати, работно време и всичко ръчно поддържано
   * остават каквито са.
   */
  public static int apply(List<ContractResult> results) {
    int touched = 0;
    for (ContractResult r : results) {
      boolean changed = false;
      if (r.insuredType != null && r.insuredType.outcome == Outcome.UPDATED) {
        r.contract.setBgInsuredTypeCode(r.insuredType.proposedValue);
        changed = true;
      }
      if (r.baseSalary != null
          && r.baseSalary.outcome == Outcome.UPDATED
          && r.resolvedSalary != null) {
        r.contract.setBgBaseSalary(twoDecimals(r.resolvedSalary));
        changed = true;
      }
      if (r.nkpd != null && r.nkpd.outcome == Outcome.UPDATED && r.resolvedNkpd != null) {
        r.contract.setBgNkpd(r.resolvedNkpd);
        changed = true;
      }
      if (r.resolvedHoursPerDay != null) {
        r.contract.setBgHoursPerDay(twoDecimals(r.resolvedHoursPerDay));
        changed = true;
      }
      if (r.resolvedLegalHoursPerDay != null) {
        r.contract.setBgLegalHoursPerDay(twoDecimals(r.resolvedLegalHoursPerDay));
        changed = true;
      }
      if (r.resolvedLaborCategory != null && r.contract.getEmployee() != null) {
        r.contract.getEmployee().setBgLaborCategory(r.resolvedLaborCategory);
        changed = true;
      }
      if (changed) {
        touched++;
      }
    }
    return touched;
  }

  /**
   * ДВА ЗНАКА СЛЕД ЗАПЕТАЯТА — толкова, колкото носи колоната.
   *
   * <p><b>⚠️ Без това вносът пада ЦЯЛ, и то тихо.</b> Изворът е {@code numeric(9,6)} (8.000000), а
   * трите целеви полета са {@code scale="2"}, тоест генерираният клас носи {@code @Digits(...,
   * fraction = 2)}. Bean Validation отказва при flush с „Validation failed … during update time“ —
   * едно поле със седем нули след запетаята отменя ЦЕЛИЯ пакет записи, а докладът вече е преброил
   * обновените полета.
   *
   * <p>⚠️ {@code RoundingMode.HALF_UP} е безопасно ТУК и само тук: и трите стойности са парични или
   * часови величини, които изворът пази с нули след втория знак (620.200000, 8.000000, 6.000000).
   * Закръгляне няма какво да промени; стойност с истински трети знак би била друг вид данни и би се
   * видяла веднага в доклада, защото предложената стойност се изписва ТОЧНО както е в извора.
   */
  static BigDecimal twoDecimals(BigDecimal v) {
    return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /**
   * „1“ → „01“. Номенклатурата на НАП е двузначна с водещи нули, а Microinvest пази точка 12 като
   * число без водещата нула. Връща {@code null} за празно или негодно.
   */
  static String normalizeInsuredTypeCode(String raw) {
    String v = trim(raw);
    if (v == null || v.isEmpty() || !v.chars().allMatch(Character::isDigit)) {
      return null;
    }
    String stripped = v.replaceFirst("^0+(?=\\d)", "");
    return stripped.length() == 1 ? "0" + stripped : stripped;
  }

  private static String trim(String s) {
    return s == null ? null : s.trim();
  }

  private static String emptyToNull(String s) {
    String t = trim(s);
    return t == null || t.isEmpty() ? null : t;
  }
}
