package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * МОЖЕ ЛИ РЕДОВНАТА ДЕКЛАРАЦИЯ ДА СЕ ГЕНЕРИРА НАНОВО — чиста функция върху статуса на ПОСЛЕДНОТО
 * подаване.
 *
 * <p><b>⚠️ СПИСЪК НА ПОЗВОЛЕНОТО, НЕ НА ЗАБРАНЕНОТО.</b> Генерирането е ЗАМЯНА: то изтрива записите
 * и ги прави наново от живите фишове. Изброени ли бяха забранените статуси, ВСЕКИ нов статус в
 * {@code nap.submission.status.select} щеше да се окаже позволен по подразбиране — тоест списъкът
 * щеше да се разширява сам, в посоката, в която греши по-скъпо. Затова тук стоят ДВАТА, при които
 * НАП не пази нищо: отхвърлено и техническа грешка. Всичко друго — отказ, който НАЗОВАВА статуса.
 *
 * <p><b>⚠️ ДВА ВЪПРОСА, А НЕ ЕДИН.</b> Първо ИСТОРИЯТА: ако за този период някога е имало ПРИЕТО
 * или ИЗИСКВАЩО КОРЕКЦИЯ подаване, НАП пази данните за месеца — и ги пази независимо от това какво
 * е станало с по-късните опити. Второ ПОСЛЕДНОТО подаване: то отговаря за текущото състояние.
 * Историята бие последното, защото твърди по-силното: „там има нещо“ не се отменя от „последният
 * опит се върна“. Поправката на такъв месец минава през КОРИГИРАЩА декларация, не през пренаписване
 * на редовната.
 *
 * <p>⚠️ САМО последното подаване не стига: „прието → отхвърлено“ би разрешило точно случая, в който
 * НАП държи приет файл за месеца.
 *
 * <p>⚠️ И НЕ Е КЛЮЧ (Б). {@link BgD1PeriodLockRules#LOCKING_STATUSES} отговаря на „може ли месецът
 * да се редактира редовно“ и заключва при четири статуса. Тук въпросът е „ще презапишем ли нещо,
 * което НАП държи“ — друг въпрос, друг списък, и двата се четат от своето място.
 */
public final class BgD1RegenerationRules {

  /**
   * Статусите в ИСТОРИЯТА, които отказват независимо от последното подаване.
   *
   * <p>⚠️ И двата значат „НАП има данните за този месец“: приетото е прието, а „изисква корекция“ е
   * произнасяне ВЪРХУ получено подаване — тоест и то е стигнало дотам. Отхвърленото и техническата
   * грешка не влизат: при тях НАП не пази нищо, колкото и пъти да се е повторило.
   */
  public static final List<String> HISTORY_BLOCKING_STATUSES =
      Collections.unmodifiableList(
          Arrays.asList(
              NapSubmissionRepository.STATUS_ACCEPTED,
              NapSubmissionRepository.STATUS_CORRECTION_REQUIRED));

  /**
   * Статусите, при които повторното генериране е РАЗРЕШЕНО — гледано само на ПОСЛЕДНОТО подаване.
   *
   * <p>⚠️ Липсата на подаване (null) също разрешава и НЕ е в списъка: тя не е статус, а отсъствие
   * на ред. Вписана като стойност, щеше да покани проверка „съдържа ли списъкът null“ — тоест да
   * слее „нищо не е тръгвало“ с „тръгна и се върна“.
   */
  public static final List<String> ALLOWING_STATUSES =
      Collections.unmodifiableList(
          Arrays.asList(
              NapSubmissionRepository.STATUS_REJECTED,
              NapSubmissionRepository.STATUS_TECHNICAL_ERROR));

  /** Българските имена на статусите — отказът назовава статуса на езика на екрана И суров. */
  private static final Map<String, String> LABELS = new LinkedHashMap<>();

  static {
    LABELS.put(NapSubmissionRepository.STATUS_DRAFT, "чернова");
    LABELS.put(NapSubmissionRepository.STATUS_SUBMITTING, "подава се в момента");
    LABELS.put(NapSubmissionRepository.STATUS_SUBMITTED, "подадена");
    LABELS.put(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, "неизвестен изход");
    LABELS.put(NapSubmissionRepository.STATUS_ACCEPTED, "приета");
    LABELS.put(NapSubmissionRepository.STATUS_REJECTED, "отхвърлена");
    LABELS.put(NapSubmissionRepository.STATUS_CORRECTION_REQUIRED, "изисква корекция");
    LABELS.put(NapSubmissionRepository.STATUS_TECHNICAL_ERROR, "техническа грешка");
  }

  private BgD1RegenerationRules() {}

  /** Разрешава ли този статус повторното генериране. {@code null} (няма подаване) — да. */
  public static boolean allows(String submissionStatus) {
    return submissionStatus == null
        || submissionStatus.trim().isEmpty()
        || ALLOWING_STATUSES.contains(submissionStatus.trim());
  }

  /** Името на статуса на български, или самата стойност, ако е непозната. */
  public static String label(String submissionStatus) {
    String known = submissionStatus == null ? null : LABELS.get(submissionStatus.trim());
    return known == null ? "непознат статус" : known;
  }

  /**
   * Отказът по ИСТОРИЯ — „някога прието“ бие всичко по-късно.
   *
   * <p>⚠️ Назовава СТАТУСА ОТ ИСТОРИЯТА, а не последния. Причина, която казва „последното подаване
   * е отхвърлено“, праща човека да гледа точно реда, който НЕ го спира.
   */
  public static String everSubmittedRefusal(String periodName, String historyStatus) {
    return "За период „"
        + (periodName == null || periodName.trim().isEmpty() ? "(без име)" : periodName.trim())
        + "“ в историята на подаванията има „"
        + label(historyStatus)
        + "“ ("
        + historyStatus
        + "). НАП ПАЗИ данните за този месец — и ги пази независимо от това какво е станало с"
        + " по-късните опити, тоест по-късно отхвърляне не ги изтрива оттам. Генерирането е ЗАМЯНА:"
        + " то изтрива записите и ги прави наново от живите фишове, а подаденото остава без следа"
        + " САМО в системата. Поправката минава през КОРИГИРАЩА декларация (вид „Коригираща“),"
        + " която носи само ретро-фишовете и сочи тази редовна.";
  }

  /**
   * Причината за отказ, или {@code null}, ако генерирането е разрешено.
   *
   * <p>⚠️ При НЕИЗВЕСТЕН ИЗХОД причината сочи ПЪРВАТА стъпка, а не крайния изход. Подаване, чийто
   * резултат не се знае, може да е прието — сверяването е евтино, а повторното генериране преди
   * него е необратимо за системата.
   */
  public static String refuse(String periodName, String submissionStatus, String historyStatus) {
    // ⚠️ ЧЛЕНСТВОТО СЕ ПРОВЕРЯВА ТУК, а не само в заявката. Заявката филтрира по същия списък, но
    // правилото трябва да е ПЪЛНО: подаден отхвърлен статус като „история“ не бива да отказва,
    // защото при него НАП не пази нищо. Инак списъкът живее на две места и едното мълчи.
    if (historyStatus != null && HISTORY_BLOCKING_STATUSES.contains(historyStatus.trim())) {
      return everSubmittedRefusal(periodName, historyStatus.trim());
    }
    if (allows(submissionStatus)) {
      return null;
    }
    String status = submissionStatus.trim();
    StringBuilder reason = new StringBuilder();
    reason
        .append("За период „")
        .append(periodName == null || periodName.trim().isEmpty() ? "(без име)" : periodName.trim())
        .append("“ последното подаване към НАП е със статус „")
        .append(label(status))
        .append("“ (")
        .append(status)
        .append(
            "). Генерирането е ЗАМЯНА: то изтрива записите и ги прави наново от живите фишове,"
                + " тоест подаденото остава без следа в системата, а в НАП — ако е стигнало дотам — си"
                + " стои. Разрешено е само когато НЯМА подаване или последното е ОТХВЪРЛЕНО или с"
                + " ТЕХНИЧЕСКА ГРЕШКА — там НАП не пази нищо.");

    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(status)) {
      reason.append(
          " ⚠️ ИЗХОДЪТ НЕ СЕ ЗНАЕ: първо сверете резултата по documentId/входящ номер. Подаването"
              + " може да е ПРИЕТО — повторното подаване „на сляпо“ е точно случаят, срещу който"
              + " този статус съществува.");
    } else if (NapSubmissionRepository.STATUS_ACCEPTED.equals(status)) {
      reason.append(
          " Поправката на приет период минава през КОРИГИРАЩА декларация (вид „Коригираща“), която"
              + " носи само ретро-фишовете и сочи тази редовна.");
    } else if (NapSubmissionRepository.STATUS_CORRECTION_REQUIRED.equals(status)) {
      reason.append(
          " НАП иска КОРЕКЦИЯ на подаденото, тоест то съществува там. Поправката минава през"
              + " коригираща декларация, не през пренаписване на редовната.");
    } else if (!LABELS.containsKey(status)) {
      reason.append(
          " ⚠️ Статусът не е познат на това правило. Позволените се изброяват ПОИМЕННО, тоест нов"
              + " статус се отказва по подразбиране — добави го в ALLOWING_STATUSES само ако НАП"
              + " наистина не пази нищо при него.");
    }
    return reason.toString();
  }
}
