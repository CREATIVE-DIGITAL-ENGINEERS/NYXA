package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.bglocalization.db.repo.BgCompensationTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgTerminationCompensationRepository;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Database layer for proposing termination compensations.
 *
 * <p>Fetches the dictionary and the rows the contract already carries, delegates every decision to
 * the pure {@link BgCompensationProposalResolver}, and persists what is missing. Same split as
 * {@link BgMinInsuranceIncomeService} / {@link BgMinIncomeResolver}: no rule lives here, so no rule
 * needs a database to be tested.
 *
 * <p>It is a POJO with no injection; repositories come through {@link Beans}, matching the rest of
 * the module.
 *
 * <p><b>What the pass does, exactly:</b> it adds the candidates that are missing · it withdraws the
 * proposals that the current ground no longer gives rise to · it does not touch confirmed, excluded
 * or manually corrected rows. Running it a second time on unchanged input therefore produces
 * nothing. The unique key on (contract, type) is the backstop, not the mechanism: relying on it
 * would surface a constraint violation where the honest answer is "nothing to add". See {@link
 * BgCompensationProposalResolver#plan}.
 *
 * <p>Withdrawal is deliberately narrow. Only {@code PROPOSED} rows are withdrawable, because a
 * proposal is the only thing the pass itself authored; every other status is somebody's decision. A
 * ground corrected from art. 331 to art. 328 would otherwise leave the art. 331 compensation
 * standing for ever, proposed on a premise that no longer holds and indistinguishable from one that
 * was meant.
 *
 * <p><b>An additional contract is not withdrawn from.</b> The guard returns before any query, so a
 * contract flipped from primary to additional keeps whatever was already proposed on it. That is on
 * purpose: it costs nothing per save for the common case, and the established way to retire such a
 * row is to exclude it with a written reason, not to have it vanish.
 *
 * <p><b>Proposed, not confirmed.</b> New rows get {@code PROPOSED}, the domain default and the
 * first of the three states in the design: proposed → confirmed or excluded. For art. 222 par. 1 КТ
 * that state also carries "waiting for proof" — the compensation is owed only if the person really
 * stayed unemployed, which is shown afterwards with the employment record book. Creating rows as
 * confirmed would decide that question on the day of termination, which is precisely when it cannot
 * be answered.
 */
public class BgTerminationCompensationService {

  /** {@code bg.compensation.status.select} — the state a freshly proposed row starts in. */
  static final String STATUS_PROPOSED = "PROPOSED";

  /**
   * Proposes the compensations this contract's termination gives rise to.
   *
   * <p>Does nothing and says why when the contract has not ended: an agreed end still in the future
   * is not a termination, and a contract with no dates at all is running.
   *
   * @param contract the terminated contract; null is answered, not thrown
   * @return what was created and what was left alone
   */
  @Transactional
  public BgCompensationProposalResult propose(EmploymentContract contract) {
    return propose(contract, LocalDate.now());
  }

  /**
   * As {@link #propose(EmploymentContract)}, with the day used to decide whether an agreed end has
   * arrived supplied explicitly.
   */
  @Transactional
  public BgCompensationProposalResult propose(EmploymentContract contract, LocalDate today) {
    if (contract == null) {
      return BgCompensationProposalResult.notRun("няма трудов договор");
    }

    // Преди всичко останало, и преди която и да е заявка: по допълнителен трудов договор правото
    // изобщо не възниква. Не е въпрос на отчитане, тоест няма
    // какво да се предложи и после да се изключва.
    if (!Boolean.TRUE.equals(contract.getBgIsPrimaryContract())) {
      return BgCompensationProposalResult.notRun(
          "по допълнителен трудов договор обезщетение не се поражда");
    }

    LocalDate terminationDate =
        BgCompensationProposalResolver.effectiveTerminationDate(
            contract.getBgTerminationDate(), contract.getEndDate(), today);
    if (terminationDate == null) {
      return BgCompensationProposalResult.notRun(
          "договорът не е прекратен — няма нито дата на прекратяване, нито настъпил край на срока");
    }

    EndOfContractReason reason = contract.getEndOfContractReason();
    String ground = reason == null ? null : reason.getBgTerminationGround();
    String legalFamily = reason == null ? null : reason.getBgLegalFamily();

    List<BgTerminationCompensation> existing =
        Beans.get(BgTerminationCompensationRepository.class)
            .all()
            .filter("self.employmentContract = :contract")
            .bind("contract", contract)
            .fetch();

    List<BgCompensationType> types =
        Beans.get(BgCompensationTypeRepository.class)
            .all()
            .filter("self.active = true")
            .order("sequence")
            .fetch();

    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(
            types, ground, legalFamily, existing, contract.getBgIsPrimaryContract());

    BgTerminationCompensationRepository repo = Beans.get(BgTerminationCompensationRepository.class);
    List<BgTerminationCompensation> created = new ArrayList<>();
    for (BgCompensationType type : plan.getToCreate()) {
      BgTerminationCompensation row = new BgTerminationCompensation();
      row.setEmploymentContract(contract);
      row.setBgCompensationType(type);
      row.setTerminationDate(terminationDate);
      row.setStatus(STATUS_PROPOSED);
      created.add(repo.save(row));
    }

    // Оттеглянето е ИЗТРИВАНЕ, не смяна на статус: предложението никога не е било решение, а
    // изключването е — ред с EXCLUDED и празна причина би изглеждал като нечие решение и би
    // замърсил тъкмо това, за което exclusionReason съществува.
    //
    // ⚠️ Редът се маха И ОТ КОЛЕКЦИЯТА НА ДОГОВОРА, не само през репозитория. Обратната връзка
    // bgTerminationCompensationList се генерира с cascade = ALL и orphanRemoval = true, тоест
    // докато
    // редът стои в колекцията на току-що записания договор, flush-ът го каскадира обратно и
    // изтриването се губи — мълчаливо, защото услугата вече е преброила оттеглените: пасажът
    // докладва едно оттегляне, а редът остава в базата.
    List<BgTerminationCompensation> attached = contract.getBgTerminationCompensationList();
    for (BgTerminationCompensation row : plan.getToRetract()) {
      if (attached != null) {
        attached.remove(row);
      }
      repo.remove(row);
    }

    // Броят на заварените, не на пропуснатите: заварен ред за вид, който това основание не поражда,
    // също не е пипнат, и потребителят трябва да види, че е останал.
    return BgCompensationProposalResult.of(created, existing.size(), plan.getToRetract().size());
  }
}
