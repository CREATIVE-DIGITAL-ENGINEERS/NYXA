package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.service.BgAmendmentSalaryRule;
import com.axelor.apps.bglocalization.service.BgInsuranceCalculationService;
import com.axelor.apps.bglocalization.service.BgInsuredTypeCategoryRule;
import com.axelor.apps.bglocalization.service.BgTerminationCompensationService;
import com.axelor.apps.bglocalization.service.BgWorkTimeModeRule;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractHRRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.util.List;
import java.util.Objects;
import javax.persistence.FlushModeType;
import javax.validation.ValidationException;

/**
 * Proposes the termination compensations whenever a contract is saved as terminated.
 *
 * <p>Sits at the repository layer rather than on a form action, for the same reason {@link
 * BgDeclarantManagementRepository} does: it covers every save path — the contract form, an editable
 * grid, the REST API and batch import — instead of only the one screen someone remembered to wire.
 *
 * <p><b>Extends the HR override, and is bound as a link in its chain.</b> {@code
 * axelor-human-resource} already binds {@code EmploymentContractRepository} to {@link
 * EmploymentContractHRRepository}, which assigns the contract reference from a sequence. Two things
 * follow. Extending it rather than the generated base keeps that behaviour instead of dropping it
 * silently. And the binding in {@code BgLocalizationModule} targets {@code
 * EmploymentContractHRRepository}, not the base key: Guice does not override across modules, so
 * binding the base a second time fails the whole injector with {@code BindingAlreadySet}. The chain
 * is the idiom this codebase already uses — {@code InvoiceRepository → InvoiceManagementRepository}
 * in account, then {@code InvoiceManagementRepository → InvoiceSupplychainRepository} in
 * supplychain.
 *
 * <p><b>Automatic, and manual decisions survive.</b> The pass adds the candidates that are missing,
 * withdraws the proposals the current ground no longer gives rise to, and does not touch confirmed,
 * excluded or manually corrected rows. So a later save never overwrites a decision somebody took:
 * an excluded compensation stays excluded with its reason, a confirmed one stays confirmed, an
 * adjusted amount stays adjusted. Only the pass's own proposals are the pass's to take back — see
 * {@code BgCompensationProposalResolver.plan}.
 *
 * <p>Failures are deliberately not swallowed: if proposing throws, the save fails with it. A
 * half-registered termination is worse than a refused one, and both live in the same transaction.
 *
 * <p><b>⚠️ И ОТКАЗВА НЕВЪЗМОЖНАТА ДВОЙКА (вид осигурен ↔ категория труд) ПРЕДИ ЗАПИСА.</b> Иначе
 * двойка без нито един ред със ставки — например (01, първа категория) — се записва без възражение
 * и гърми чак на фиша, с {@code NO_CONTRIBUTIONS_FOUND}. Правилото не се преписва тук: то е {@link
 * BgInsuredTypeCategoryRule}, същото, което сверява и {@code BgInsuredTypeHomeFileTest}. Тук се
 * решава само КОГА се задава въпросът — на записа, а не два екрана по-късно.
 *
 * <p><b>Отказът е ПРЕДИ {@code super.save}</b>, не след него: половин записан договор с невъзможен
 * ключ за ставките е точно състоянието, което се опитваме да не допуснем. И на ниво репозиторий, а
 * не на действие във формата — по същия довод като предложението на обезщетенията отгоре: покрива
 * формата, редактируемата решетка, REST и импорта наведнъж.
 *
 * <p><b>⚠️ И ОТКАЗВА ЗАБРАНЕНИЯ РЕЖИМ НА ОТЧИТАНЕ (ненормиран работен ден + СИРВ).</b> КТ чл. 142
 * ал. 5: „Не се допуска сумирано изчисляване на работното време за работниците и служителите с
 * ненормиран работен ден.“ Тоест двойката не е рядка и не е спорна — тя е НЕДОПУСТИМА, и точно
 * затова се спира тук, а не се обслужва по-нататък: недопустима конфигурация, която се записва, се
 * оказва сметната на фиш и платена. Правилото е {@link BgWorkTimeModeRule}, същото, което отказва и
 * перата в {@code BgEarningsResolver}.
 *
 * <p>⚠️ ПЪРВИЯТ ОТ ДВАТА ОТКАЗА, И РЕДЪТ НЕ Е СВОБОДЕН: този се произнася по ДВЕ полета на самия
 * договор и не иска нито един прочит от базата, докато двойката (вид осигурен ↔ категория труд)
 * чете каталога със ставките. Питан втори, забраненият режим щеше да плати заявка, за да съобщи
 * нещо, което се вижда от самия запис.
 */
public class BgEmploymentContractManagementRepository extends EmploymentContractHRRepository {

  @Override
  public EmploymentContract save(EmploymentContract employmentContract) {
    refuseForbiddenWorkTimeMode(employmentContract);
    refuseImpossibleInsuredTypeCategory(employmentContract);
    // ⚠️ ТРЕТИЯТ ОТКАЗ, И ТОЙ Е ПОСЛЕДЕН НАРОЧНО: другите два се произнасят по полета на самия
    // запис, а този обхожда допълнителните споразумения. Питан пръв, той щеше да плати обхождане,
    // за да съобщи нещо, което двете полета отгоре вече отричат.
    refuseBaseSalaryRestatingAmendment(employmentContract);
    // ЧЕТВЪРТИЯТ: прекратяването. Чете записа за прекратяване — затова след трите, които
    // се произнасят по полета на самия договор.
    refuseTerminationOutsideItsRecord(employmentContract);
    // Записва се ПЪРВО: обезщетенията сочат договора и се датират от неговите полета, тоест те
    // трябва да са актуални, а новият договор — да има id.
    EmploymentContract saved = super.save(employmentContract);
    Beans.get(BgTerminationCompensationService.class).propose(saved);
    return saved;
  }

  /**
   * ЕДНАТА ДАТА И ОСНОВАНИЕТО ПОД ПОДАДЕН ЕТЗ.
   *
   * <p>Има ли запис за прекратяване, датата в договора е НЕГОВАТА: промяна тук, която не идва от
   * него, отказва ({@link BgEtzTerminationService#DATE_ONLY_VIA_RECORD}) — иначе ЕТЗ, Д1 и вид 27
   * биха чели две дати. Подаден ли е вече ЕТЗ за прекратяването, отказват и датата, и смяната на
   * основанието ({@link BgEtzTerminationService#FILED_LOCKED}): поправката е корекция.
   *
   * <p>Заявките са с {@code COMMIT} — не flush-ват договора, който в момента се записва.
   */
  private static void refuseTerminationOutsideItsRecord(EmploymentContract contract) {
    if (contract == null || contract.getId() == null) {
      return;
    }
    BgContractTermination termination = BgEtzTerminationService.recordOf(contract.getId());
    if (termination == null) {
      return;
    }
    boolean filed = BgEtzTerminationService.isFiled(termination.getId());
    if (!Objects.equals(contract.getBgTerminationDate(), termination.getTerminationDate())) {
      throw new ValidationException(
          filed
              ? BgEtzTerminationService.FILED_LOCKED
              : BgEtzTerminationService.DATE_ONLY_VIA_RECORD);
    }
    if (filed) {
      Long now =
          contract.getEndOfContractReason() == null
              ? null
              : contract.getEndOfContractReason().getId();
      if (!Objects.equals(now, committedReasonId(contract.getId()))) {
        throw new ValidationException(BgEtzTerminationService.FILED_LOCKED);
      }
    }
  }

  private static Long committedReasonId(Long contractId) {
    List<Long> rows =
        JPA.em()
            .createQuery(
                "SELECT r.id FROM EmploymentContract c LEFT JOIN c.endOfContractReason r"
                    + " WHERE c.id = :id",
                Long.class)
            .setParameter("id", contractId)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Отказва записа, когато основната заплата по договора ПОВТАРЯ сума, която анекс вече въвежда.
   *
   * <p>Ловеният навик: ТРЗ въвежда допълнително споразумение за 1200,00 и по стар рефлекс вдига и
   * полето на договора на 1200,00. Анексът тогава не пази нищо — базата е мръднала под него и
   * септемврийският фиш също излиза с 1200,00, макар че за септември се дължат 1000,00.
   *
   * <p><b>⚠️ ОТКАЗЪТ Е ТУК, А НЕ САМО {@code readonlyIf} НА ФОРМАТА.</b> Изгледът не пази пътя през
   * REST — същото, заради което забраната за СИРВ при ненормиран работен ден е отказ на ЗАПИСА, а
   * не скрито поле.
   *
   * <p>Правилото е {@link BgAmendmentSalaryRule#refuseBaseRestatingAmendment} и не се преписва тук
   * — то е същото, което решава и коя заплата влиза във фиша.
   */
  private void refuseBaseSalaryRestatingAmendment(EmploymentContract contract) {
    if (contract == null) {
      return;
    }
    String refusal =
        BgAmendmentSalaryRule.refuseBaseRestatingAmendment(
            contract.getBgBaseSalary(), contract.getBgContractAmendmentList());
    if (refusal != null) {
      // Без I18n.get: съобщението се СГЛОБЯВА от сумата и датите на анекса, тоест няма постоянен
      // ключ, който речникът да превежда.
      throw new ValidationException(refusal);
    }
  }

  /**
   * Отказва записа, когато двойката (вид осигурен, категория труд) няма нито един ред със ставки.
   *
   * <p>Каталогът се чете САМО когато и двете полета са попълнени — празно поле е незададен въпрос,
   * не разминаване, и не бива да струва заявка на всеки запис на договор.
   */
  private void refuseImpossibleInsuredTypeCategory(EmploymentContract contract) {
    if (contract == null) {
      return;
    }
    String code = contract.getBgInsuredTypeCode();
    String laborCategory = laborCategoryOf(contract);
    if (!BgInsuredTypeCategoryRule.isAnswerable(code, laborCategory)) {
      return;
    }
    String refusal =
        BgInsuredTypeCategoryRule.disagreement(
            code,
            laborCategory,
            Beans.get(BgInsuranceCalculationService.class).laborCategoriesWithRates(code));
    if (refusal != null) {
      // Без I18n.get: съобщението се СГЛОБЯВА от кода, категорията и наличните категории, тоест
      // няма постоянен ключ, който речникът да превежда. Празен превод би го изял целия.
      throw new ValidationException(refusal);
    }
  }

  /**
   * Отказва записа на договор с ненормиран работен ден И сумирано изчисляване на работното време.
   *
   * <p>⚠️ Правилото е {@link BgWorkTimeModeRule#forbiddenPairRefusal} и не се преписва тук — то е
   * същото, което отказва и перата на фиша. Тук е само прочитът на двете полета на договора.
   *
   * <p>⚠️ БЕЗ ПРОЧИТ ОТ БАЗАТА, тоест без цена на всеки запис: и двете полета са на самия договор.
   *
   * <p>⚠️ {@code Boolean.TRUE.equals}, А НЕ ПРЯКО РАЗПАКОВАНЕ — същият прочит като в {@code
   * BgPayrollInputBuilder}. Генерираният getter връща {@code Boolean} със стойност по подразбиране
   * {@code FALSE} и заварен ред с {@code NULL} се чете като „не е ненормиран“, което е верният
   * прочит за всеки, когото никой не е питал: ненормираният ден се установява със ЗАПОВЕД по чл.
   * 139а ал. 3.
   *
   * <p>Изнесено като статичен метод по същия довод като {@link #laborCategoryOf}: проверява се без
   * база и без Guice.
   */
  static void refuseForbiddenWorkTimeMode(EmploymentContract contract) {
    if (contract == null) {
      return;
    }
    String refusal =
        BgWorkTimeModeRule.forbiddenPairRefusal(
            Boolean.TRUE.equals(contract.getBgIsUnregulatedWorkDay()),
            contract.getBgWorkTimeMode());
    if (refusal != null) {
      // Без I18n.get: съобщението се СГЛОБЯВА от нормата и от имената на двете полета, тоест няма
      // постоянен ключ, който речникът да превежда. Празен превод би го изял целия.
      throw new ValidationException(refusal);
    }
  }

  /**
   * КАТЕГОРИЯТА ТРУД Е НА ЛИЦЕТО, НЕ НА ДОГОВОРА — същият прочит като в {@code
   * BgPayrollInputBuilder} и в {@code BgInsuredTypeHomeDbTest}.
   *
   * <p>Точно това разминаване между двата екрана прави едно-полевото съобщение подвеждащо:
   * операторът стои на договора, а сгрешеното поле е на служителя.
   *
   * <p>Изнесено като статичен метод, за да се проверява без база — същият шев като {@code
   * BgTerminationCompensationManagementRepository#isMissingProof}.
   */
  static String laborCategoryOf(EmploymentContract contract) {
    Employee employee = contract == null ? null : contract.getEmployee();
    return employee == null ? null : employee.getBgLaborCategory();
  }
}
