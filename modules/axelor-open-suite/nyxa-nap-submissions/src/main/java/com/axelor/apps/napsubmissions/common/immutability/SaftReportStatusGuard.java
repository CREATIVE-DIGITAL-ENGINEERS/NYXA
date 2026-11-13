package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.db.Model;
import java.util.List;
import java.util.Objects;

/**
 * СТАТУСЪТ НА SAF-T ОТЧЕТА СЕ СМЕНЯ САМО ОТ ПРОВЕРКАТА И ГЕНЕРИРАНЕТО (G6b, риск 6 от одита).
 *
 * <p>Полетата {@link #WORKFLOW_FIELDS} пише само {@code SaftReportWorkflowService}, в обхвата
 * {@link #allow}. Всеки друг път — форма, {@code /ws/rest}, масово обновяване, услуга, admin — е
 * отказ: READY никога пряко, генериран файл никога подменен.
 *
 * <p>Още две правила, по всеки път: дружеството на отчета не се сменя след създаването; видът и
 * периодът му (месец, година, дати и искане от НАП) — след първото генериране (файлът е за тях).
 *
 * <p>Пазачите: {@link SaftReportStatusListener} (Hibernate PRE_INSERT/PRE_UPDATE, по старото
 * състояние) и {@link SaftReportRequestGuard} (масовото обновяване минава покрай Hibernate).
 */
public final class SaftReportStatusGuard {

  /** Само работният поток ги пише. */
  public static final List<String> WORKFLOW_FIELDS =
      List.of(
          "lifecycleStatusSelect", "readinessStatusSelect", "generatedArtifact", "schemaVersion");

  /** Никога след създаването. */
  public static final String COMPANY = "company";

  /** Не след първото генериране — всичко, за което е файлът (вид, месец, година, дати, искане). */
  public static final List<String> FIXED_AFTER_GENERATION =
      List.of(
          "period",
          "reportTypeSelect",
          "reportYear",
          "fromDate",
          "toDate",
          "napRequestNumber",
          "napRequestDate");

  public static final String REFUSAL =
      "Полето „%s“ на SAF-T отчета се сменя само от „Провери“ и „Генерирай“.";

  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private SaftReportStatusGuard() {}

  /** Тяло, което може да хвърли проверимо изключение. */
  public interface Body<T> {
    T run() throws Exception;
  }

  /**
   * Изпълнява тялото в обхвата на работния поток. ⚠️ Тялото само трябва да флъшне преди края си —
   * пазачът е на flush-а, а транзакцията се затваря след обхвата.
   */
  public static <T> T allow(Body<T> body) throws Exception {
    DEPTH.set(DEPTH.get() + 1);
    try {
      return body.run();
    } finally {
      int depth = DEPTH.get() - 1;
      if (depth == 0) {
        DEPTH.remove();
      } else {
        DEPTH.set(depth);
      }
    }
  }

  public static boolean allowed() {
    return DEPTH.get() > 0;
  }

  /** Нов отчет: празен статус, без файл — освен от работния поток (коригиращият отчет). */
  public static void checkInsert(String[] names, Object[] state, boolean allowed) {
    if (allowed) {
      return;
    }
    Object lifecycle = value(names, state, "lifecycleStatusSelect");
    if (lifecycle != null && !SaftReportRepository.LIFECYCLE_DRAFT.equals(lifecycle)) {
      throw refusal("lifecycleStatusSelect");
    }
    Object readiness = value(names, state, "readinessStatusSelect");
    if (readiness != null && !SaftReportRepository.READINESS_NOT_CHECKED.equals(readiness)) {
      throw refusal("readinessStatusSelect");
    }
    if (value(names, state, "generatedArtifact") != null) {
      throw refusal("generatedArtifact");
    }
  }

  /** Промяна по старото и новото състояние. */
  public static void checkUpdate(
      String[] names, Object[] oldState, Object[] state, boolean allowed) {
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      if (same(oldState[i], state[i])) {
        continue;
      }
      if (COMPANY.equals(name) && oldState[i] != null) {
        throw new RegulatoryImmutabilityException(
            "Дружеството на SAF-T отчета не се сменя след създаването му.");
      }
      if (!allowed && WORKFLOW_FIELDS.contains(name)) {
        throw refusal(name);
      }
      if (FIXED_AFTER_GENERATION.contains(name)
          && value(names, oldState, "generatedArtifact") != null) {
        throw new RegulatoryImmutabilityException(
            "Полето „"
                + name
                + "“ на SAF-T отчета не се сменя след генериране — файлът е за него. Създайте нов"
                + " отчет.");
      }
    }
  }

  static RegulatoryImmutabilityException refusal(String field) {
    return new RegulatoryImmutabilityException(String.format(REFUSAL, field));
  }

  static Object value(String[] names, Object[] state, String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return state[i];
      }
    }
    return null;
  }

  static boolean same(Object before, Object after) {
    if (before instanceof Model || after instanceof Model) {
      return Objects.equals(idOf(before), idOf(after));
    }
    return Objects.equals(before, after);
  }

  private static Long idOf(Object value) {
    return value == null ? null : ((Model) value).getId();
  }
}
