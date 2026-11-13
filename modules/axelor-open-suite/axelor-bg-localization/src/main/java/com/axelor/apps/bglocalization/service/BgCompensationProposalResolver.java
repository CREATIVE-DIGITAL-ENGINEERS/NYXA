package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure logic for "which compensations does this termination ground give rise to".
 *
 * <p>Contains no database access and no Guice injection, so every branch is unit testable by
 * constructing entities in memory. Fetching and persisting lives in {@link
 * BgTerminationCompensationService}. Same split as {@link BgMinIncomeResolver} / {@link
 * BgMinInsuranceIncomeService}.
 *
 * <p>Two ways a type can be proposed, and they are not the same question:
 *
 * <ul>
 *   <li>{@code appliesToAnyGround} — due on every termination whatever the ground. Unused paid
 *       leave under art. 224 par. 1 КТ is the case: it is owed because employment ended, not
 *       because of how it ended. Art. 222 par. 3 КТ too, whose text says "regardless of the ground
 *       for the termination"; the pension right it also requires is a fact about the person that
 *       this model does not hold, so the row stays a proposal.
 *   <li>{@code triggerGrounds} — a comma-separated list of grounds, each of which gives rise to
 *       this compensation. Art. 222 par. 1 КТ is owed on dismissal under art. 328 par. 1 items 1,
 *       2, 3, 4, 7 and 8, and nowhere else.
 * </ul>
 *
 * <p>⚠️ ЧЕТЕНО ОТ SEED-А, не по спомен: {@code comp_kt_222_1} носи точно {@code
 * 328_1_1,328_1_2,328_1_3,328_1_4,328_1_7,328_1_8}. Числата тук се сверяват срещу CSV-то, не се
 * преписват.
 *
 * <p>A type with neither is never proposed by ground, and that is deliberate for 15 of the 25
 * seeded rows. Two reasons, and they are different:
 *
 * <ul>
 *   <li>art. 220 par. 1 and par. 2 arise from notice that was not served — a separate fact, not a
 *       ground, so no ground can ever propose them;
 *   <li>the rest arise from an event DURING the employment, not from its end at all — art. 200
 *       (occupational accident), art. 213, 214, 216, 225, 226 and 232 КТ. They are registered when
 *       the event happens; a termination does not give rise to them.
 * </ul>
 *
 * <p>⚠️ И ЕДИН ДОВОД, КОЙТО ОТПАДНА, а текстът му стоеше: по-ранната редакция обясняваше, че
 * обезщетението по ЗДСл няма как да се предложи, защото {@code bg.termination.ground.select} „носи
 * само КТ“. Селекцията вече носи ТРИ АКТА и 26 стойности — 23 по КТ, 2 по ЗДСл (чл. 106 ал. 1 т. 1
 * и 2) и 1 по ЗВО (чл. 58 ал. 1 т. 3) — а {@code comp_zdsl_106_2} се предлага по {@code
 * 106_1_1,106_1_2}, тоест точно онова, което текстът обявяваше за невъзможно. Актът е ОТДЕЛНО поле
 * ({@code EndOfContractReason.bgLegalFamily}) именно защото номерата се повтарят между актовете.
 */
public class BgCompensationProposalResolver {

  private BgCompensationProposalResolver() {}

  /** Separator of {@code BgCompensationType.triggerGrounds}, the Axelor multi-select convention. */
  private static final String GROUND_SEPARATOR = ",";

  /** {@code bg.compensation.status.select} — the only status the pass may withdraw. */
  static final String STATUS_PROPOSED = "PROPOSED";

  /**
   * {@code bg.relationship.kind.select} — Appendix 1 row 1 of the employment-register regulation.
   */
  public static final String RELATIONSHIP_EMPLOYMENT = "01";

  /** {@code bg.relationship.kind.select} — a civil-service relationship, not an employment one. */
  public static final String RELATIONSHIP_CIVIL_SERVICE = "02";

  /**
   * The kind of relationship an act gives rise to — a property of the act itself.
   *
   * <p><b>КТ and ЗВО both produce an employment relationship; ЗДСл produces a civil-service
   * one.</b> For higher education this is not an analogy: art. 59 ЗВО refers everything Chapter VI
   * leaves unregulated back to the Labour Code, and ВКС Decision № 142/01.07.2019 on civil case №
   * 3934/2018, III civil division, describes the lecturer as a person "working under an employment
   * contract". So a compensation the Labour Code owes on any termination is owed on a ЗВО
   * termination too — the acts differ, the relationship does not.
   *
   * <p>The codes are those of Appendix 1 row 1 of the employment-register regulation (ПМС №
   * 267/2024) rather than a private enumeration, because the register needs the same value and one
   * source is better than two that can drift.
   *
   * @return the relationship kind, or null for an act this module does not know
   */
  public static String relationshipKindOf(String legalFamily) {
    if (legalFamily == null) {
      return null;
    }
    switch (legalFamily.trim()) {
      case "KT":
      case "ZVO":
        // Закон за администрацията и Закон за съдебната власт: кодове 058–063 на т. 27 —
        // НВРЗ ги изброява под „При прекратяване на трудово правоотношение“.
      case "ZA":
      case "ZSV":
        return RELATIONSHIP_EMPLOYMENT;
      case "ZDSL":
        return RELATIONSHIP_CIVIL_SERVICE;
      default:
        return null;
    }
  }

  /**
   * The date a termination actually took effect, or null when the contract has not ended yet.
   *
   * <p><b>Two different dates, and collapsing them loses the case they exist for.</b> {@code
   * endDate} on a fixed-term contract is filled in when it is signed and means the <em>agreed</em>
   * end; {@code bgTerminationDate} is the day it really ended. They coincide when the term simply
   * ran out and they differ when the contract was cut short — and that difference is exactly what
   * decides whether severance under art. 222 par. 1 КТ is owed (it is not owed when the term
   * expired, it is when the employer terminated early).
   *
   * <ul>
   *   <li>an actual termination date wins outright — it is the fact, whatever was agreed
   *   <li>otherwise an agreed end that has arrived counts as a termination: a fixed-term contract
   *       that ran out ends without anyone typing a second date, and its unused leave is still owed
   *   <li>an agreed end still in the future is not a termination
   * </ul>
   *
   * <p>{@code today} is a parameter rather than {@code LocalDate.now()} so the rule stays pure and
   * the boundary is testable on both sides.
   */
  public static LocalDate effectiveTerminationDate(
      LocalDate bgTerminationDate, LocalDate endDate, LocalDate today) {
    if (bgTerminationDate != null) {
      return bgTerminationDate;
    }
    if (endDate != null && today != null && !endDate.isAfter(today)) {
      return endDate;
    }
    return null;
  }

  /**
   * Whether this compensation type is proposed for the given termination ground.
   *
   * <p><b>Grounds are compared whole, never by substring.</b> The selection contains both {@code
   * 328_1_1} and {@code 328_1_10}, so {@code triggerGrounds.contains(ground)} would propose a
   * compensation listed under {@code 328_1_10} on a plain redundancy dismissal under {@code
   * 328_1_1}. The list is split and each entry compared for equality.
   *
   * @param type the compensation type; null or inactive is never proposed
   * @param groundCode the ground from {@code EndOfContractReason.bgTerminationGround}, may be blank
   *     when the reason carries no ground yet
   */
  public static boolean matches(BgCompensationType type, String groundCode) {
    return matches(type, groundCode, null);
  }

  /**
   * Whether this compensation type is proposed for the given act and article.
   *
   * <p><b>The ground is two parts, and the article alone identifies nothing.</b> Article numbers
   * repeat across acts — КТ art. 71 and ЗДСл art. 71 are different grounds carrying the same digits
   * — so a match requires the act to agree as well. A collision between acts is then impossible by
   * construction rather than by care.
   *
   * <p>A null or blank {@code legalFamily} matches any type, so a caller that has no act to offer
   * behaves exactly as before this axis existed. That is for callers, not for data: a persisted
   * reason without an act is a migration gap, not permission to match everything.
   *
   * @param legalFamily {@code EndOfContractReason.bgLegalFamily}; blank means "do not narrow"
   */
  public static boolean matches(BgCompensationType type, String groundCode, String legalFamily) {
    if (type == null || !Boolean.TRUE.equals(type.getActive())) {
      return false;
    }
    boolean narrowing = legalFamily != null && !legalFamily.trim().isEmpty();

    if (Boolean.TRUE.equals(type.getAppliesToAnyGround())) {
      // „При всяко основание“ значи при всяко основание, а не „при всяко основание от моя акт“:
      // обезщетението се дължи, защото правоотношението свърши, и въпросът е какъв вид е било то.
      // Затова тук се сравнява ВИДЪТ ПРАВООТНОШЕНИЕ, не актът — чл. 224 КТ се дължи и при
      // прекратяване по ЗВО, но не и при служебно правоотношение по ЗДСл.
      //
      // Blank ground on purpose as well: the employment ended either way, and a proposed row is
      // reversible by excluding it while a missing row is silent.
      if (!narrowing) {
        return true;
      }
      String typeKind = relationshipKindOf(type.getLegalFamily());
      String groundKind = relationshipKindOf(legalFamily);
      return typeKind != null && typeKind.equals(groundKind);
    }

    // Конкретният член принадлежи на конкретен акт и тук съвпадението е точно — това е защитата
    // срещу сблъсък между актове, при която КТ чл. 71 и ЗДСл чл. 71 са различни двойки.
    if (narrowing) {
      String typeFamily = type.getLegalFamily();
      if (typeFamily == null || !legalFamily.trim().equals(typeFamily.trim())) {
        return false;
      }
    }
    String ground = groundCode == null ? "" : groundCode.trim();
    if (ground.isEmpty()) {
      return false;
    }
    String grounds = type.getTriggerGrounds();
    if (grounds == null || grounds.trim().isEmpty()) {
      return false;
    }
    for (String candidate : grounds.split(GROUND_SEPARATOR)) {
      if (ground.equals(candidate.trim())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The compensation types proposed for a ground, in the order they were supplied.
   *
   * <p>Order is preserved rather than re-sorted so the caller keeps whatever ordering its query
   * asked for — {@code sequence} in practice.
   *
   * @param types the candidate types; null is treated as empty
   * @param groundCode the termination ground, may be blank
   * @return a new list, never null
   */
  public static List<BgCompensationType> proposedFor(
      List<BgCompensationType> types, String groundCode) {
    return proposedFor(types, groundCode, null);
  }

  /** As {@link #proposedFor(List, String)}, narrowed to one act. */
  public static List<BgCompensationType> proposedFor(
      List<BgCompensationType> types, String groundCode, String legalFamily) {
    if (types == null || types.isEmpty()) {
      return Collections.emptyList();
    }
    List<BgCompensationType> proposed = new ArrayList<>();
    for (BgCompensationType type : types) {
      if (matches(type, groundCode, legalFamily)) {
        proposed.add(type);
      }
    }
    return proposed;
  }

  /**
   * What the pass should do to this contract's rows: what to create, and what to withdraw.
   *
   * <p><b>The rule this method implements, in full:</b> add the candidates that are missing ·
   * withdraw the proposals that the current ground no longer gives rise to · never touch confirmed,
   * excluded or manually corrected rows.
   *
   * <p>Withdrawal exists because the ground can be corrected. A termination first registered under
   * art. 331 and then fixed to art. 328 par. 1 item 2 would otherwise keep the art. 331
   * compensation for ever — proposed on a premise that no longer holds, and indistinguishable from
   * one that was meant. Only {@code PROPOSED} rows are withdrawn: they are the only ones the pass
   * itself authored, and everything else is somebody's decision.
   *
   * @param existing the rows already on the contract; null is treated as none
   * @param isPrimaryContract anything other than TRUE yields an empty plan — on an additional
   *     contract the right does not arise, and see {@code BgTerminationCompensationService} for why
   *     nothing is withdrawn there either
   */
  public static BgCompensationProposalPlan plan(
      List<BgCompensationType> types,
      String groundCode,
      String legalFamily,
      List<BgTerminationCompensation> existing,
      Boolean isPrimaryContract) {
    if (!Boolean.TRUE.equals(isPrimaryContract)) {
      return new BgCompensationProposalPlan(Collections.emptyList(), Collections.emptyList());
    }
    List<BgCompensationType> candidates = proposedFor(types, groundCode, legalFamily);

    List<BgCompensationType> missing = new ArrayList<>();
    for (BgCompensationType type : candidates) {
      if (existing == null || !isAlreadyPresent(type, existing)) {
        missing.add(type);
      }
    }

    List<BgTerminationCompensation> stale = new ArrayList<>();
    if (existing != null) {
      for (BgTerminationCompensation row : existing) {
        if (row == null || !STATUS_PROPOSED.equals(row.getStatus())) {
          continue;
        }
        if (!isCandidate(row, candidates)) {
          stale.add(row);
        }
      }
    }
    return new BgCompensationProposalPlan(missing, stale);
  }

  /**
   * Whether this row's type is among the candidates, compared the same way as {@link #toCreate}.
   */
  private static boolean isCandidate(
      BgTerminationCompensation row, List<BgCompensationType> candidates) {
    BgCompensationType present = row.getBgCompensationType();
    if (present == null) {
      return false;
    }
    for (BgCompensationType candidate : candidates) {
      if (candidate.getId() != null && candidate.getId().equals(present.getId())) {
        return true;
      }
      if (candidate.getCode() != null && candidate.getCode().equals(present.getCode())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The types to create: proposed by the ground, minus the ones this contract already carries.
   *
   * <p><b>This is where re-proposing stays harmless.</b> Registering a termination twice must not
   * produce a second row for the same compensation — the unique key on (contract, type) would
   * refuse it at the database, but only after the transaction has done its work, and the user would
   * see a constraint error instead of "nothing to add". The subtraction happens here, in memory, so
   * it can be tested by calling twice without a database.
   *
   * <p><b>An existing row blocks re-creation whatever its status</b> — including {@code EXCLUDED}.
   * Excluding a compensation is a decision someone took and wrote a reason for; a later re-proposal
   * that resurrected it as {@code PROPOSED} would erase that decision silently, which is the whole
   * failure this method exists to prevent. Confirmed and proposed rows are equally left alone: this
   * method never touches what it finds, it only declines to add.
   *
   * <p>Withdrawal of stale proposals is a separate question and lives in {@link #plan} — this
   * method answers only "what is missing".
   *
   * @param types the candidate types; null is treated as empty
   * @param groundCode the termination ground, may be blank
   * @param existing the rows already on the contract; null is treated as none
   * @return the types that still need a row, in input order; never null
   */
  public static List<BgCompensationType> toCreate(
      List<BgCompensationType> types, String groundCode, List<BgTerminationCompensation> existing) {
    List<BgCompensationType> proposed = proposedFor(types, groundCode);
    if (proposed.isEmpty() || existing == null || existing.isEmpty()) {
      return proposed;
    }
    List<BgCompensationType> missing = new ArrayList<>();
    for (BgCompensationType type : proposed) {
      if (!isAlreadyPresent(type, existing)) {
        missing.add(type);
      }
    }
    return missing;
  }

  /**
   * As {@link #toCreate(List, String, List)}, refusing outright for a contract that is not the
   * employee's main one.
   *
   * <p><b>On an additional contract the right does not arise at all.</b> This is not a reporting
   * rule that could be applied later at declaration time: there is nothing to propose, so no row is
   * created and the ground is never even consulted.
   *
   * @param isPrimaryContract {@code EmploymentContract.bgIsPrimaryContract}; anything other than
   *     TRUE refuses, so a legacy row that predates the flag refuses too rather than guessing
   */
  public static List<BgCompensationType> toCreate(
      List<BgCompensationType> types,
      String groundCode,
      List<BgTerminationCompensation> existing,
      Boolean isPrimaryContract) {
    if (!Boolean.TRUE.equals(isPrimaryContract)) {
      return Collections.emptyList();
    }
    return toCreate(types, groundCode, existing);
  }

  /**
   * Whether the contract already carries a row for this type.
   *
   * <p>Compared by id when both sides have one and by code otherwise, so the same logic serves both
   * persisted entities and the in-memory ones a test builds.
   */
  private static boolean isAlreadyPresent(
      BgCompensationType type, List<BgTerminationCompensation> existing) {
    for (BgTerminationCompensation row : existing) {
      BgCompensationType present = row == null ? null : row.getBgCompensationType();
      if (present == null) {
        continue;
      }
      if (type.getId() != null && type.getId().equals(present.getId())) {
        return true;
      }
      if (type.getCode() != null && type.getCode().equals(present.getCode())) {
        return true;
      }
    }
    return false;
  }
}
