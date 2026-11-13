package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgContributionPreview;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgInsuranceCalculationService;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgMinInsuranceIncomeService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollWorkTime;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only diagnostic controller: runs МОД lookup → insurable base → contribution calculator
 * against the real data and writes a formatted text summary back into the transient {@link
 * BgContributionPreview}.
 *
 * <p>All inputs the services need beyond the form fields are gathered from the selected employee
 * and company (НКИД, НКПД from the primary contract, born-after-1959, pension choice, overrides).
 * The controller only gathers and formats — no rounding, no row selection, no ceiling arithmetic;
 * every decision lives in the services. Because the form is diagnostic it echoes the inputs it
 * chose itself (which contract, НКПД, НКИД, ТЗПБ rate, cap and its period), so a strange number can
 * be traced to a wrong calculation versus a wrong input. It is the first execution of the JPQL
 * queries, which have no unit coverage.
 *
 * <p><b>⚠️ ПРОПОРЦИЯТА ПО НЕВД ВЛИЗА ОТТУК.</b> Четириаргументният {@code resolve} значи {@code
 * BgWorkTimeFacts.fullMonth()}: прагът се сравнява ЦЯЛ дори при непълно работно време. Този екран е
 * единственият жив път до веригата МОД → база → вноски, тоест без него пропорцията по НЕВД чл. 1
 * ал. 3 и ал. 4 е недостижима от работещото приложение. Затова входът се сглобява от {@link
 * BgPayrollInputBuilder} за МЕСЕЦА на датата на преглед и наборът факти минава нататък.
 *
 * <p>⚠️ Числото се мени САМО когато има валидиран отчет за време: без отчет отработеното е „не
 * знам“ (не нула) и наборът пак е {@code fullMonth}, тоест поведението е дословно предишното.
 * Липсата на отчет не бива да понижава база.
 */
@Singleton
public class BgContributionPreviewController {

  public void calculate(ActionRequest request, ActionResponse response) {
    try {
      BgContributionPreview preview = request.getContext().asType(BgContributionPreview.class);
      CapabilityGate.requireRole("preview contributions", Capabilities.PAYROLL_READ);
      CapabilityGate.requireActiveCompany(preview.getCompany());
      if (preview.getEmployee() != null) {
        CapabilityGate.requireRead(
            com.axelor.apps.hr.db.Employee.class, preview.getEmployee().getId());
      }

      Employee employee = preview.getEmployee();
      Company company = preview.getCompany();
      LocalDate date =
          preview.getPreviewDate() != null ? preview.getPreviewDate() : LocalDate.now();

      BgNkid nkid = company != null ? company.getBgNkid() : null;
      String nkidCode = nkid != null ? nkid.getCode() : null;
      String divisionCode = nkid != null ? nkid.getDivisionCode() : null;
      String companyOverrideRow = company != null ? company.getBgMinIncomeRowCodeOverride() : null;

      EmploymentContract contract = primaryContract(employee);
      String nkpdCode =
          contract != null && contract.getBgNkpd() != null ? contract.getBgNkpd().getCode() : null;

      BigDecimal employeeOverride = employee != null ? employee.getBgMinIncomeOverride() : null;
      boolean bornAfter1959 =
          employee != null && Boolean.TRUE.equals(employee.getBgBornAfter1959());
      String pensionChoice = employee != null ? employee.getBgPensionChoice() : null;
      String insuredTypeCode = preview.getInsuredTypeCode();

      // 1. МОД resolution.
      BgMinInsuranceIncomeService modService = Beans.get(BgMinInsuranceIncomeService.class);
      BgMinIncomeResult mod =
          modService.findMinInsuranceIncome(
              employeeOverride, nkidCode, companyOverrideRow, nkpdCode, insuredTypeCode, date);

      // 2. Работното време за МЕСЕЦА на датата. ⚠️ Месец, а не единичен ден: и двата знаменателя
      // по НЕВД чл. 1 са МЕСЕЧНИ — законоустановените часове ЗА МЕСЕЦА и работните дни В МЕСЕЦА.
      // Ден без месец около себе си няма как да даде пропорция.
      BgPayrollInput payrollInput =
          Beans.get(BgPayrollInputBuilder.class)
              .build(contract, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()));
      BgPayrollWorkTime.Decision workTime = BgPayrollWorkTime.decide(payrollInput);

      // ⚠️ Пропорционира ли се прагът ВЪОБЩЕ за този вид осигурен — от bgZeroWorkTime, тоест от
      // СЪЩИЯ ред, по който резолверът после ще смята. Това УСЛОВЯВА отказа по-долу.
      boolean prorates = proratesWithWorkTime(insuredTypeCode, date);

      // 3. Insurable base: min(max(gross, МОД floor, statutory floor), cap). Both bounds are read
      // from data by the service; the insured type selects which statutory floor applies.
      // The generated decimal getter already returns ZERO for an empty field — the ternary was
      // dead code with two identical branches.
      //
      // ⚠️ ПРИ ОТКАЗ БАЗАТА НЕ СЕ СМЯТА. Отказът значи „не се знае кой ред по НЕВД чл. 1 важи“, а
      // двата реда дават различно число за един и същ месец. Смятане с подразбиране би дало праг
      // наужким — точно тихият резултат, който отказът съществува да замени.
      //
      // ⚠️ НО ОТКАЗЪТ Е УСЛОВЕН. При bgZeroWorkTime прагът се дължи ЦЯЛ и работното време не влиза
      // с нито едно число; безусловен отказ би спрял морско лице (код 92) заради непопълнено
      // bgHoursPerDay — вход, от който сметката НЕ зависи.
      BigDecimal gross = preview.getGrossSalary();
      BgInsurableIncomeResult income =
          workTime.blocks(prorates)
              ? null
              : Beans.get(BgInsurableIncomeService.class)
                  .resolve(gross, mod, insuredTypeCode, date, workTime.factsFor(prorates));

      // 4. ТЗПБ rate for the employer's economic activity (2-digit division), via the service.
      BgInsuranceCalculationService calcService = Beans.get(BgInsuranceCalculationService.class);
      BigDecimal tzpbRate =
          divisionCode != null ? calcService.findTzpbRate(divisionCode, date) : null;

      // 5. Contribution calculation — only when the base resolved.
      BgContributionResult calc = null;
      if (income != null && income.isResolved()) {
        BgContributionRequest calcRequest =
            new BgContributionRequest(
                income.getAmount(),
                insuredTypeCode,
                preview.getLaborCategory(),
                bornAfter1959,
                pensionChoice,
                preview.getSolScope(),
                date,
                tzpbRate,
                preview.getCompensationTypeCode(),
                // Учителството идва от ДОГОВОРА, не от вида осигурен: под код 04 стоят и учители, и
                // неучители. Без договор няма как да се знае, и тогава NON_TEACHER е верният
                // отговор —
                // не подразбиране, а липса на учителска длъжност.
                // ⚠️ Чете се от СГЛОБЕНИЯ вход, а не се извежда втори път тук: две места, които
                // отговарят на един въпрос, са свободни да се разминат.
                payrollInput.teacherScope);
        calc = calcService.calculate(calcRequest);
      }

      preview.setResultText(
          format(
              modService,
              mod,
              gross,
              income,
              tzpbRate,
              nkidCode,
              divisionCode,
              contract,
              nkpdCode,
              bornAfter1959,
              pensionChoice,
              insuredTypeCode,
              preview.getLaborCategory(),
              workTime,
              prorates,
              calc));
      response.setValue("resultText", preview.getResultText());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Пропорционира ли се прагът с работното време за този вид осигурен.
   *
   * <p>Шев към {@link BgInsurableIncomeService#proratesWithWorkTime} — същият прочит на {@code
   * BgInsuredType.bgZeroWorkTime}, с който резолверът после смята. Отделен метод, за да е подменяем
   * без Guice: {@link #format} се проверява от тест, а той не бива да вдига инжектора.
   */
  protected boolean proratesWithWorkTime(String insuredTypeCode, LocalDate date) {
    return Beans.get(BgInsurableIncomeService.class).proratesWithWorkTime(insuredTypeCode, date);
  }

  /** The employee's primary employment contract, or the first one if none is flagged primary. */
  protected EmploymentContract primaryContract(Employee employee) {
    if (employee == null) {
      return null;
    }
    return Beans.get(EmploymentContractRepository.class)
        .all()
        .filter("self.employee = :e")
        .bind("e", employee)
        .order("-bgIsPrimaryContract")
        .fetchOne();
  }

  protected String format(
      BgMinInsuranceIncomeService modService,
      BgMinIncomeResult mod,
      BigDecimal gross,
      BgInsurableIncomeResult income,
      BigDecimal tzpbRate,
      String nkidCode,
      String divisionCode,
      EmploymentContract contract,
      String nkpdCode,
      boolean bornAfter1959,
      String pensionChoice,
      String insuredTypeCode,
      String laborCategory,
      BgPayrollWorkTime.Decision workTime,
      boolean prorates,
      BgContributionResult calc) {

    StringBuilder sb = new StringBuilder();

    sb.append("═══ Избран вход (диагностика) ═══\n");
    sb.append("Вид осигурен (код НАП): ")
        .append(insuredTypeCode != null ? insuredTypeCode : "(няма)")
        .append("\n");
    sb.append("Категория труд: ")
        .append(laborCategory != null ? laborCategory : "(няма)")
        .append("\n");
    if (contract != null) {
      boolean primary = Boolean.TRUE.equals(contract.getBgIsPrimaryContract());
      sb.append("Трудов договор: #")
          .append(contract.getId())
          .append(primary ? " (основен)" : " (НЕ е отбелязан основен — изборът е произволен)")
          .append("\n");
    } else {
      sb.append("Трудов договор: няма намерен за служителя\n");
    }
    sb.append("НКПД: ").append(nkpdCode != null ? nkpdCode : "(няма)").append("\n");
    sb.append("НКИД: ")
        .append(nkidCode != null ? nkidCode : "(няма)")
        .append(", раздел: ")
        .append(divisionCode != null ? divisionCode : "(няма)")
        .append("\n");
    sb.append("Роден след 1959: ")
        .append(bornAfter1959 ? "да" : "не")
        .append(", пенсионен избор: ")
        .append(pensionChoice != null ? pensionChoice : "DEFAULT")
        .append("\n");
    sb.append("ТЗПБ ставка: ")
        .append(tzpbRate != null ? tzpbRate.toPlainString() + "%" : "(няма ред)")
        .append("\n");
    // ⚠️ Пропорцията е ВХОД, не сметка, и без този ред „база 275,33“ не се различава от „база
    // 550,66“ по нищо видимо на екрана.
    sb.append("Режим на работното време: ").append(workTime.regime).append("\n");
    sb.append("Пропорция: ").append(BgPayrollWorkTime.describe(workTime, prorates)).append("\n\n");

    // ⚠️ Отказът спира ТУК, след избрания вход и преди всяка сметка: показаното е какво липсва, а
    // не число, стигнало дотук по подразбиране.
    //
    // ⚠️ И СПИРА САМО КОГАТО ПАЗИ НЕЩО. При bgZeroWorkTime прагът се дължи цял, режимът не участва
    // в сметката и екранът продължава — с бележката за неразпознатия режим, изписана горе от
    // describe(workTime, prorates), тоест липсата в данните остава ВИДИМА, без да блокира.
    if (workTime.blocks(prorates)) {
      sb.append("═══ ОТКАЗ ═══\n");
      sb.append(BgPayrollWorkTime.describe(workTime)).append("\n\n");
      sb.append("(нито МОД, нито база, нито вноски се смятат — виж отказа)\n");
      return sb.toString();
    }

    sb.append("═══ МОД (минимален осигурителен доход) ═══\n");
    sb.append("Статус: ").append(mod.getStatus()).append("\n");
    sb.append(modService.describe(mod)).append("\n");
    if (mod.isResolved() && mod.getAmount() != null) {
      sb.append("МОД праг: ").append(mod.getAmount());
      if (mod.getRowCode() != null) {
        sb.append("  (ред ").append(mod.getRowCode());
        if (mod.getQualificationGroup() != null) {
          sb.append(", група ").append(mod.getQualificationGroup());
        }
        sb.append(")");
      }
      sb.append("\n");
    }
    sb.append("\n");

    sb.append("═══ Осигурителна база ═══\n");
    sb.append("Статус: ").append(income.getStatus()).append("\n");
    sb.append("Брутно възнаграждение: ").append(gross).append("\n");
    if (income.isResolved()) {
      sb.append("Таван: ").append(income.getCapAmount());
      if (income.getCapValidFrom() != null) {
        sb.append("  (в сила от ").append(income.getCapValidFrom()).append(")");
      }
      sb.append("\n");
      sb.append("Използвана осигурителна база: ").append(income.getAmount()).append("\n\n");
    } else {
      sb.append("Детайл: ").append(income.getDetail()).append("\n");
      sb.append("(калкулаторът не е стартиран — базата не е определена)\n");
      return sb.toString();
    }

    sb.append("═══ Осигурителни вноски ═══\n");
    sb.append("Статус: ").append(calc.getStatus()).append("\n");
    if (calc.getDetail() != null && !calc.getDetail().isEmpty()) {
      sb.append("Детайл: ").append(calc.getDetail()).append("\n");
    }
    sb.append("\n");

    if (!calc.getLines().isEmpty()) {
      sb.append("Фонд                | Платец      | Ставка % | Сума\n");
      sb.append("--------------------+-------------+----------+---------\n");
      for (BgContributionLine line : calc.getLines()) {
        sb.append(pad(line.getFundCode(), 20))
            .append("| ")
            .append(pad(payerLabel(line.getPayer()), 12))
            .append("| ")
            .append(pad(line.getRate().toPlainString(), 9))
            .append("| ")
            .append(line.getAmount().toPlainString())
            .append("\n");
      }
      sb.append("\n");
      sb.append("Общо работодател: ").append(calc.totalEmployer()).append("\n");
      sb.append("Общо лице: ").append(calc.totalEmployee()).append("\n");
      sb.append("Общо: ").append(calc.total()).append("\n");
    } else {
      sb.append("(няма изчислени линии)\n");
    }

    return sb.toString();
  }

  private static String payerLabel(BgContributionLine.Payer payer) {
    return payer == BgContributionLine.Payer.EMPLOYER ? "работодател" : "лице";
  }

  private static String pad(String value, int width) {
    String v = value != null ? value : "";
    if (v.length() >= width) {
      return v + " ";
    }
    StringBuilder sb = new StringBuilder(v);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }
}
