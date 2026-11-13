package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.i18n.I18n;
import java.time.LocalDate;
import javax.validation.ValidationException;

/**
 * Enforces the two conditional invariants of a termination-compensation row at the repository
 * layer: an excluded row carries a written reason, and a confirmed row carries its proof when the
 * compensation is one that has to be proved.
 *
 * <p><b>Why here and not {@code required} in the domain.</b> Both rules are CONDITIONAL: the reason
 * is demanded only when the status is {@code EXCLUDED}, and the proof only when the status is
 * {@code CONFIRMED} and the type demands it. A {@code required} field would be NOT NULL and would
 * demand a reason from proposed rows too, which have nothing to explain — the pass creates them by
 * itself. The view already asks for it, but a view constraint is a courtesy to whoever is looking
 * at the screen: REST, an import and a batch all walk past it. Without this check a row can be
 * saved through REST as EXCLUDED with an empty reason and nothing objects.
 *
 * <p><b>Convention, so the next such question already has an answer: conditional business rules
 * live in {@code save()}, not in the view.</b> The view may repeat the rule for a better
 * experience, but the repository is where it is true — that is the only layer every write path
 * passes through.
 *
 * <p>The reason matters because an exclusion is a decision. Six months later nobody remembers why a
 * termination compensation was not paid, and a termination compensation is precisely the thing that
 * gets litigated.
 *
 * <p><b>═══ ЗАЩО И ДОКАЗВАНЕТО Е ТУК ═══</b>
 *
 * <p>Без проверка на {@code proofDate} правото по чл. 222 ал. 1 КТ би минало PROPOSED → CONFIRMED
 * само със смяна на падащото меню, на самия ден на прекратяването, когато отговорът още НЕ
 * СЪЩЕСТВУВА: обезщетението се дължи само ако лицето действително остане без работа, и това се
 * доказва ПОСЛЕ. Полето пази точно този отговор.
 *
 * <p><b>⚠️ ИНВАРИАНТ НА СЪСТОЯНИЕТО, НЕ ПАЗАЧ НА ПРЕХОДА, и разликата е измерима.</b> {@code
 * save()} вижда САМО входящия запис — предишното състояние не му е известно. Затова правилото е
 * „ред, ЗАПИСАН като CONFIRMED, носи дата на доказване“, а не „преходът PROPOSED → CONFIRMED иска
 * дата“. Второто би пропуснало ред, който пристига CONFIRMED през REST или импорт без никога да е
 * бил PROPOSED — тоест точно пътищата, заради които този пазач живее тук, а не в изгледа.
 * Формулиран като инвариант, той пази и заварените редове при всяко следващо записване.
 *
 * <p><b>═══ ⚠️ ДВА ОТДЕЛНИ ПЪТЯ ЗА ДОКАЗВАНЕ, И ТЕ НАРОЧНО НЕ СЕ СВЪРЗВАТ ═══</b>
 *
 * <p>{@code BgCompensationResolver#refuseConfirm} иска доказване и то при потвърждаване на
 * ДОКУМЕНТА ({@code BgCompensation}) — прикачен файл или изрична бележка. Този пазач иска ДАТА на
 * ПРАВОТО. Изкушението е да се слеят; не се сливат, по четири причини:
 *
 * <ol>
 *   <li><b>Документ може още да няма.</b> Правото се създава от автоматичния пасаж при записа на
 *       прекратения договор, а {@code BgCompensation.bgTerminationCompensation} е nullable — актът
 *       се съставя после и не за всяко право. Пазач, който иска документ, би блокирал нормалния
 *       поток.
 *   <li><b>Двата записа НЕ СЕ ПЪЛНЯТ ОТ ЕДИН ПРОЦЕС</b> и това е записано решение в домейна на
 *       {@code BgTerminationCompensation}: пълната реконсилиация е дълг за стъпка 4. Пазач тук,
 *       който чете документа, въвежда точно свързването, което е отложено — и то отзад, през
 *       валидация.
 *   <li><b>Двата въпроса са различни.</b> Правото пита „дължи ли се“ и доказателството му е ФАКТ с
 *       дата (остана ли лицето без работа ≥ 30 дни). Актът пита „може ли да се изплати“ и
 *       доказателството му е ХАРТИЯ в досието. Дата и прикачен файл не са взаимозаменяеми.
 *   <li><b>Датата е и входът, който подаването на Д1 иска.</b> Д1 се подава СЛЕД изплащането, а
 *       осигурителният доход се отнася към месеца, за който се дължи — сметка, която тръгва от
 *       дата, не от файл.
 * </ol>
 *
 * <p>Оттам следва и обхватът: тук се проверява {@code proofDate} и НИЩО друго. Документният пазач
 * остава непокътнат.
 */
public class BgTerminationCompensationManagementRepository
    extends BgTerminationCompensationRepository {

  /** {@code bg.compensation.status.select} — the status that demands a reason. */
  static final String STATUS_EXCLUDED = "EXCLUDED";

  /** {@code bg.compensation.status.select} — the status that demands the proof it was owed. */
  static final String STATUS_CONFIRMED = "CONFIRMED";

  /**
   * Whether this pair of status and reason is refused.
   *
   * <p>Static and free of the entity so the rule itself can be unit tested without a database — the
   * same split the module uses between its resolvers and its services.
   */
  static boolean isMissingExclusionReason(String status, String exclusionReason) {
    return STATUS_EXCLUDED.equals(status)
        && (exclusionReason == null || exclusionReason.trim().isEmpty());
  }

  /**
   * Whether this row is confirmed without the proof its compensation type demands.
   *
   * <p>Static and free of the entity for the same reason as {@link #isMissingExclusionReason} — the
   * rule is testable without a database.
   *
   * <p>⚠️ {@code requiresProof} се подава като {@code Boolean}, а не се чете тук от типа, и е
   * {@code Boolean.TRUE.equals(...)}, а не {@code == true}: генерираният getter връща {@code FALSE}
   * и за непопълнено поле, тоест „не изисква доказване“ и „никой не е казал“ са едно и също нещо —
   * и това е верният прочит. Липсващ тип също не изисква доказване: ред без вид обезщетение има
   * по-голям проблем от този и той е {@code required} в домейна.
   *
   * @param status {@code bg.compensation.status.select}
   * @param requiresProof {@code BgCompensationType.requiresProof}; {@code null} значи „не изисква“
   * @param proofDate датата, на която е доказано оставането без работа
   */
  static boolean isMissingProof(String status, Boolean requiresProof, LocalDate proofDate) {
    return STATUS_CONFIRMED.equals(status)
        && Boolean.TRUE.equals(requiresProof)
        && proofDate == null;
  }

  @Override
  public BgTerminationCompensation save(BgTerminationCompensation entity) {
    if (entity == null) {
      return super.save(entity);
    }
    if (isMissingExclusionReason(entity.getStatus(), entity.getExclusionReason())) {
      throw new ValidationException(
          I18n.get("Изключено обезщетение изисква причина за изключването."));
    }
    BgCompensationType type = entity.getBgCompensationType();
    if (isMissingProof(
        entity.getStatus(), type == null ? null : type.getRequiresProof(), entity.getProofDate())) {
      throw new ValidationException(
          I18n.get(
              "Обезщетението се потвърждава само след доказване: попълнете датата на доказване."
                  + " Правото по чл. 222 ал. 1 КТ се дължи единствено ако лицето е останало без"
                  + " работа, а това не е знайно към датата на прекратяването."));
    }
    return super.save(entity);
  }
}
