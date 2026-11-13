package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.d1.BgCompensationParts;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.persistence.FlushModeType;
import javax.persistence.TypedQuery;

/**
 * ДЕЙСТВАЩИЯТ ФИШ ЗА (ДОГОВОР, ПЕРИОД) — едно правило, един източник.
 *
 * <p><b>ЗАЩО СЪЩЕСТВУВА.</b> Коригиращото копие ({@code BgPaySlipService#retroCopyOf}) носи СЪЩИЯ
 * договор и СЪЩИЯ период като оригинала и ПЪЛНИТЕ верни числа за месеца — тоест го ЗАМЕНЯ. Ако Д1 и
 * Д6 питат периода всяка със своя заявка, едната взема оригинала (1700), а другата — оригинала И
 * корекцията (1700 + 1840), и двете декларации за един месец се разминават. Тук „кой фиш важи“ се
 * казва ВЕДНЪЖ и двете декларации четат през {@link #effectiveFor}.
 *
 * <p><b>ПРАВИЛОТО.</b> Фишът е ИЗМЕСТЕН, ако съществува потвърден (или изплатен) фиш, който:
 *
 * <ul>
 *   <li>го коригира или заличава;
 *   <li>заличава НЕГОВИЯ оригинал (корекция на заличен фиш не важи);
 *   <li>е по-късна корекция на същия оригинал (от две корекции важи последната).
 * </ul>
 *
 * Действащ е потвърденият (или изплатен), НЕ заличаващ и НЕ изместен фиш. Заличаващото копие не
 * действа никога: то казва „този запис не бива да съществува“.
 *
 * <p>⚠️ Заявката на капачките за социални разходи ({@code BgSocialExpenseService#query}) също чете
 * оттук; написано на две места, правилото би се разминало със себе си при първата промяна.
 *
 * <p>⚠️ {@code BgPaySlipLine.bgCorrectsPeriod} НЕ участва: той е на делта-перото в ТЕКУЩИЯ месец и
 * сочи назад. Делтата пада извън Д1/Д6 по {@link BgPaySlipResolver#isOwnPeriod}.
 */
public final class BgEffectivePaySlips {

  /** Името на параметъра със статусите, които „важат“ — потвърден и изплатен. */
  public static final String STATUSES = "effectiveStatuses";

  private BgEffectivePaySlips() {}

  /**
   * JPQL: фишът с псевдоним {@code s} НЕ е изместен. Иска параметъра {@link #STATUSES} = {@link
   * BgPaySlipResolver#DECLARABLE_STATUSES}.
   *
   * <p>⚠️ Наследникът се брои само ПОТВЪРДЕН: черновата на корекция още не е твърдение, а
   * отказаната е отхвърлена — докато копието не е потвърдено, важи оригиналът.
   */
  public static String notSuperseded(String s) {
    return "NOT EXISTS (SELECT sup.id FROM BgPaySlip sup"
        + " WHERE sup.statusSelect IN :"
        + STATUSES
        + " AND (sup.bgCorrectionOf = "
        + s
        + " OR sup.bgDeletionOf = "
        + s
        + " OR sup.bgDeletionOf = "
        + s
        + ".bgCorrectionOf"
        + " OR (sup.bgCorrectionOf = "
        + s
        + ".bgCorrectionOf AND sup.id > "
        + s
        + ".id)))";
  }

  /** JPQL: фишът с псевдоним {@code alias} е ДЕЙСТВАЩ. Иска параметъра {@link #STATUSES}. */
  public static String effective(String alias) {
    // ⚠️ „IS NULL OR = FALSE“: заварените фишове носят NULL в признака.
    return alias
        + ".statusSelect IN :"
        + STATUSES
        + " AND ("
        + alias
        + ".bgIsDeletion IS NULL OR "
        + alias
        + ".bgIsDeletion = FALSE) AND "
        + notSuperseded(alias);
  }

  /**
   * JPQL: фишът е на НОСИТЕЛЯ на декларацията — дружество (през договора) или регистрация на СОЛ.
   * Иска {@code :period}, {@code :company} и {@code :registration}.
   *
   * <p>⚠️ INNER JOIN-ът е капанът, който прави наивния OR безполезен: {@code
   * self.employmentContract.payCompany = :company} сам по себе си изхвърля всеки ред с празен
   * договор, независимо какво стои от другата страна. Затова всяка страна носи изрична проверка за
   * наличие, преди да стъпи на връзката.
   */
  static final String CARRIER =
      "self.period = :period"
          + " AND ((:registration IS NULL"
          + "        AND self.employmentContract IS NOT NULL"
          + "        AND self.employmentContract.payCompany = :company)"
          + "     OR (:registration IS NOT NULL"
          + "        AND self.bgSelfInsuredRegistration = :registration))";

  /**
   * ДЕЙСТВАЩИТЕ ФИШОВЕ на носителя за периода — ЕДИНСТВЕНИЯТ избор за редовната Д1 и за Д6.
   *
   * <p>⚠️ Двете декларации викат ТОЗИ метод, не свои копия на заявката: две копия се разминават.
   */
  public static List<BgPaySlip> effectiveFor(
      Period period, Company company, BgSelfInsuredRegistration registration) {
    return Beans.get(BgPaySlipRepository.class)
        .all()
        .filter(CARRIER + " AND " + effective("self"))
        .bind("period", period)
        .bind("company", company)
        .bind("registration", registration)
        .bind(STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
        .order("id")
        .fetch();
  }

  /**
   * ДЕЙСТВАЩИТЕ ФИШОВЕ ОТ ДРУГИ МЕСЕЦИ, които носят ЧАСТ от обезщетение по вид 27 за този период.
   *
   * <p><b>⚠️ ТОВА Е „ДЕЙСТВАЩ ФИШ“ ЗА ПЕРИОДА, БЕЗ ДА Е ФИШ НА ПЕРИОДА.</b> Обезщетението по чл.
   * 222 ал. 1 КТ се декларира в Д1 на МЕСЕЦА БЕЗ РАБОТА, по части ({@code BgCompensationParts}), а
   * се начислява на ЕДИН фиш — обикновено този на месеца на прекратяването. За следващия месец
   * лицето фиш няма: редът 27 идва от документа през фиша, който го носи. Тоест редовната Д1, Д6 и
   * сверката им трябва да броят ТОЗИ фиш и за следващия месец — инак записът на Д1 би сочил фиш,
   * „който вече не е действащ“, и Д6 би отказала.
   *
   * <p>⚠️ Заявката е широка по дата (прозорец от месец и няколко дни), а точното „докосва ли
   * периода“ се решава в паметта по {@code BgCompensationParts#periodStart}/{@code periodEnd} —
   * {@code plusMonths} няма обратна функция в JPQL.
   *
   * <p>⚠️ Самоосигуряващото се лице обезщетение по КТ няма — празен списък.
   */
  public static List<BgPaySlip> compensationCarriersFor(
      Period period, Company company, BgSelfInsuredRegistration registration) {
    if (registration != null
        || period == null
        || period.getFromDate() == null
        || period.getToDate() == null
        || company == null) {
      return Collections.emptyList();
    }
    LocalDate from = period.getFromDate();
    LocalDate to = period.getToDate();
    List<BgPaySlip> found =
        JPA.em()
            .createQuery(
                "SELECT DISTINCT l.paySlip FROM BgPaySlipLine l"
                    + " WHERE l.bgCompensation.bgCompensationType.d1Code = :code"
                    + " AND l.bgCompensation.bgEventDate BETWEEN :lo AND :hi"
                    + " AND l.paySlip.period <> :period"
                    + " AND l.paySlip.employmentContract.payCompany = :company"
                    + " AND "
                    + effective("l.paySlip"),
                BgPaySlip.class)
            .setParameter("code", BgCompensationParts.INSURED_TYPE)
            .setParameter("lo", from.minusMonths(1).minusDays(3))
            .setParameter("hi", to)
            .setParameter("period", period)
            .setParameter("company", company)
            .setParameter(STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
            .getResultList();
    List<BgPaySlip> out = new ArrayList<>();
    for (BgPaySlip slip : found) {
      if (touches(slip, from, to) && !out.contains(slip)) {
        out.add(slip);
      }
    }
    out.sort((a, b) -> Long.compare(idOf(a), idOf(b)));
    return out;
  }

  /** Носи ли фишът обезщетение по вид 27, чийто период докосва [from, to]. */
  static boolean touches(BgPaySlip slip, LocalDate from, LocalDate to) {
    for (BgCompensationParts.Carried c : BgCompensationParts.carried(slip)) {
      LocalDate start = BgCompensationParts.periodStart(c.getDoc().getBgEventDate());
      LocalDate end = BgCompensationParts.periodEnd(c.getDoc().getBgEventDate());
      if (start != null && !start.isAfter(to) && !end.isBefore(from)) {
        return true;
      }
    }
    return false;
  }

  private static long idOf(BgPaySlip slip) {
    return slip.getId() == null ? Long.MAX_VALUE : slip.getId();
  }

  /**
   * РЕТРО-КОПИЯТА ЗА КОРИГИРАЩАТА Д1: потвърдени, неизместени, и НЕ вече носени от редовна.
   *
   * <p>⚠️ ПОСЛЕДНОТО УСЛОВИЕ Е ЦЕНАТА НА СЛУЧАЙ (а). Корекция, направена преди редовната Д1 да е
   * подадена, влиза в нея като ДЕЙСТВАЩ фиш — запис без код. Взета и тук, тя би стигнала до НАП
   * втори път, като „К“ на запис със същите числа. Запис без код живее само в редовна декларация,
   * затова признакът е самият запис.
   */
  public static List<BgPaySlip> correctingFor(
      Period period, Company company, BgSelfInsuredRegistration registration) {
    return Beans.get(BgPaySlipRepository.class)
        .all()
        .filter(
            CARRIER
                + " AND self.statusSelect IN :"
                + STATUSES
                + " AND (self.bgIsCorrection = TRUE OR self.bgIsDeletion = TRUE)"
                + " AND "
                + notSuperseded("self")
                + " AND NOT EXISTS (SELECT r.id FROM BgD1Record r WHERE r.paySlip = self"
                + " AND (r.t1CorrectionCode IS NULL OR r.t1CorrectionCode = ''))")
        .bind("period", period)
        .bind("company", company)
        .bind("registration", registration)
        .bind(STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
        .order("id")
        .fetch();
  }

  /**
   * ДЕЙСТВАЩИЯТ ФИШ НА ДОГОВОРА за месеца, който съдържа {@code day}, или {@code null}.
   *
   * <p>⚠️ БАЗАТА ПО ЧЛ. 40 АЛ. 5 КСО ЗА МЕСЕЦА НА НАСТЪПВАНЕ идва оттук, не от потвърдения РЕДОВЕН
   * фиш: при коригиран месец на настъпване това би бил оригиналът, чиито числа корекцията е
   * заменила. Коригиращото копие носи ПЪЛНИТЕ верни числа за онзи месец и е действащият фиш;
   * делтата седи в текущия месец (перо 620) и тук не участва.
   *
   * <p>⚠️ При няколко действащи (заварен дубликат) — последният по {@code id}.
   */
  public static BgPaySlip effectiveForContract(EmploymentContract contract, LocalDate day) {
    if (contract == null || day == null) {
      return null;
    }
    List<BgPaySlip> found =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE s.employmentContract = :contract"
                    + " AND s.period.fromDate <= :day AND s.period.toDate >= :day"
                    + " AND "
                    + effective("s")
                    + " ORDER BY s.id DESC",
                BgPaySlip.class)
            .setParameter("contract", contract)
            .setParameter("day", day)
            .setParameter(STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * ПО-СТАРИЯТ РЕДОВЕН ФИШ за същия договор (или регистрация) и период — неотказан, или {@code
   * null}. „Втори“ е по-новият: за нов фиш (без идентификатор) всеки съществуващ е по-стар.
   *
   * <p>⚠️ БРОИ И ЧЕРНОВАТА. Втори редовен фиш до чернова е същата грешка, само по-рано открита:
   * потвърдени и двата, те влизат в Д1/Д6 едновременно.
   *
   * <p>⚠️ Без идентификатор на периода (или на носителя) заявка НЕ се прави — същият шев като
   * {@code BgPaySlipService#declaredToNap}: фиш, построен в паметта на unit-тест, не стига до
   * базата.
   */
  public static BgPaySlip otherRegular(BgPaySlip slip) {
    if (slip == null
        || BgPaySlipResolver.isRetro(slip)
        || BgPaySlipResolver.CANCELLED.equals(slip.getStatusSelect())) {
      return null;
    }
    Period period = slip.getPeriod();
    EmploymentContract contract = slip.getEmploymentContract();
    BgSelfInsuredRegistration registration = slip.getBgSelfInsuredRegistration();
    boolean byContract = contract != null && contract.getId() != null;
    boolean byRegistration = !byContract && registration != null && registration.getId() != null;
    if (period == null || period.getId() == null || (!byContract && !byRegistration)) {
      return null;
    }
    TypedQuery<BgPaySlip> query =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE s.period = :period"
                    + (byContract
                        ? " AND s.employmentContract = :carrier"
                        : " AND s.bgSelfInsuredRegistration = :carrier")
                    + " AND s.statusSelect <> :cancelled"
                    + " AND (s.bgIsCorrection IS NULL OR s.bgIsCorrection = FALSE)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                    // ⚠️ САМО ПО-СТАРИТЕ. Resource#save флъшва ПРЕДИ repository.save, тоест новият
                    // ред може вече да е в базата с идентификатор — без себе си. И при ЗАВАРЕНИ
                    // дубликати вторият е по-новият:
                    // спира се той, а първият остава свободен да се плати или откаже.
                    + (slip.getId() == null ? "" : " AND s.id < :self")
                    + " ORDER BY s.id",
                BgPaySlip.class)
            .setParameter("period", period)
            .setParameter("carrier", byContract ? contract : registration)
            .setParameter("cancelled", BgPaySlipResolver.CANCELLED)
            // ⚠️ Без flush: заявката пита ДРУГИТЕ редове, а флъш на мръсния фиш от средата на
            // repository.save би го записал преди пазачите си.
            .setFlushMode(FlushModeType.COMMIT)
            .setMaxResults(1);
    if (slip.getId() != null) {
      query.setParameter("self", slip.getId());
    }
    List<BgPaySlip> found = query.getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * ОТКАЗЪТ ЗА ВТОРИ РЕДОВЕН ФИШ — причина, или {@code null}. Назовава съществуващия фиш с номер и
   * статус, защото поправката е в НЕГО: да се откаже или да се работи в него.
   */
  public static String refuseSecondRegular(BgPaySlip slip, BgPaySlip existing) {
    if (slip == null || existing == null) {
      return null;
    }
    Period period = slip.getPeriod();
    return "Фиш "
        + BgPaySlipWriteGuard.label(slip)
        + " не се записва: за "
        + (slip.getEmploymentContract() != null ? "същия договор" : "същата регистрация (СОЛ)")
        + " и период „"
        + (period == null || period.getName() == null ? "(без име)" : period.getName())
        + "“ вече има редовен фиш "
        + BgPaySlipWriteGuard.label(existing)
        + " (статус: "
        + statusLabel(existing.getStatusSelect())
        + "). Един договор има ЕДИН редовен фиш за месец — два влизат в Д1 и Д6 едновременно."
        + " Работете в съществуващия или го откажете първо. Поправка на ПОТВЪРДЕН фиш в заключен"
        + " период се прави с „Коригирай“, не с втори фиш.";
  }

  /** Статусът на фиша на езика на екрана — и суров, ако е непознат. */
  static String statusLabel(String status) {
    if (BgPaySlipResolver.DRAFT.equals(status)) {
      return "чернова";
    }
    if (BgPaySlipResolver.CONFIRMED.equals(status)) {
      return "потвърден";
    }
    if (BgPaySlipResolver.PAID.equals(status)) {
      return "изплатен";
    }
    return status == null ? "(без статус)" : status;
  }
}
