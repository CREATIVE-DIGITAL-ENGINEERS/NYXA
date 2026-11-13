package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipRegimeChange;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime;
import com.axelor.auth.db.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ОДИТЪТ НА РЕЖИМА В КОРИГИРАЩО ИЛИ ЗАЛИЧАВАЩО КОПИЕ — поле, старо, ново, кой, кога.
 *
 * <p>В копието замразеният режим е РЕДАКТИРУЕМ: така се поправя грешен режим на оригинала
 * (неотбелязан ТЕЛК, грешен вид осигурен). Всяка такава промяна е причина коригиращата декларация
 * да осигурява по друг режим, тоест следата ѝ е задължителна.
 *
 * <p><b>⚠️ САМО ДОБАВЯНЕ, СПРЯМО ПОСЛЕДНОТО ЗАПИСАНО.</b> {@link #sync} сравнява всяко поле с
 * последния ред за него в одита, а без ред — с оригинала. Различно ли е, добавя нов ред; нищо не се
 * редактира и не се трие. Така двукратна промяна дава два реда, а връщане към оригинала — трети.
 *
 * <p>⚠️ ЧИСТИ ФУНКЦИИ: без база и без Guice. Вика се от {@code BgPaySlipManagementRepository#save}
 * (всеки път на запис — форма, REST, внос) и от „Попълни от текущите данни“.
 */
public final class BgPaySlipRegimeAudit {

  public static final String MANUAL = "MANUAL";
  public static final String FILLED = "FILLED";

  /** Поводът на бутона „Попълни от текущите данни“ — думите в следата и в обобщението. */
  public static final String FROM_CURRENT = "от текущите данни";

  /** Дните на карва в следата — не са поле на режима, но се попълват по същия път. */
  public static final String ART177_DAYS = "дни платен отпуск по чл. 177";

  public static final String UNPAID_D1_DAYS = "неплатени дни, които Д1 вади от т. 16.1";

  /**
   * „САМО ЗДРАВНО“ (ЗЗО чл. 40 ал. 1 т. 1 б. „б“) — по ред на платец: знакът на перото и думите в
   * следата. Перото не се смята наново в копието, тоест всяка разлика от оригинала е ръчна поправка
   * — и тя иска същата следа като полето на режима: какво, старо, ново, кой, кога.
   */
  private static final String[][] HEALTH_ONLY = {
    {BgPaySlipResolver.CREDIT, "за сметка на осигурителя"},
    {BgPaySlipResolver.DEBIT, "за сметка на лицето"}
  };

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgPaySlipRegimeAudit() {}

  /**
   * Добавя ред за всяко поле на режима — и за всяка ръчна поправка на „само здравно“, — чиято
   * стойност се различава от последната записана, и пише видимото обобщение. Нищо не прави за фиш,
   * който не е копие; полетата на режима — само когато режимът вече е замразен.
   */
  public static void sync(BgPaySlip copy, User user, LocalDateTime at) {
    if (copy == null || !BgPaySlipResolver.isRetro(copy)) {
      return;
    }
    BgPaySlip original = originalOf(copy);
    if (BgPayrollRegime.isFrozen(copy)) {
      for (BgPayrollRegime.Field f : BgPayrollRegime.fieldsFor(copy)) {
        String last = lastRecorded(copy, f.name);
        String baseline =
            last != null ? last : BgPayrollRegime.isFrozen(original) ? f.textOf(original) : null;
        String now = f.textOf(copy);
        if (baseline != null && !baseline.equals(now)) {
          add(copy, f.name, f.title, baseline, now, user, at, MANUAL);
        }
      }
    }
    syncHealthOnly(copy, original, user, at);
    copy.setBgRegimeChangeSummary(summary(copy));
  }

  /**
   * РЪЧНАТА ПОПРАВКА НА „САМО ЗДРАВНО“ — по ред за сумата и за базата на всеки платец.
   *
   * <p>⚠️ Изходната точка е последният записан ред за полето, а без такъв — перото на ОРИГИНАЛА.
   * Изтрито перо се записва като „—“, ново (каквото оригиналът няма) — от „—“. Същото правило „само
   * добавяне“ като при режима: две поправки дават два реда, връщане към оригинала — трети.
   */
  private static void syncHealthOnly(
      BgPaySlip copy, BgPaySlip original, User user, LocalDateTime at) {
    for (String[] payer : HEALTH_ONLY) {
      BgPaySlipLine now = healthOnlyLine(copy, payer[0]);
      BgPaySlipLine was = healthOnlyLine(original, payer[0]);
      healthOnlyField(copy, payer, "amount", "сума", amountOf(was), amountOf(now), user, at);
      healthOnlyField(copy, payer, "base", "база", baseOf(was), baseOf(now), user, at);
    }
  }

  private static void healthOnlyField(
      BgPaySlip copy,
      String[] payer,
      String part,
      String partTitle,
      String original,
      String now,
      User user,
      LocalDateTime at) {
    String name = "healthOnly." + payer[0] + "." + part;
    String last = lastRecorded(copy, name);
    String baseline = last != null ? last : original;
    if (!baseline.equals(now)) {
      add(copy, name, healthOnlyTitle(payer, partTitle), baseline, now, user, at, MANUAL);
    }
  }

  private static String healthOnlyTitle(String[] payer, String partTitle) {
    return "„само здравно“ (" + payer[1] + ") — " + partTitle;
  }

  /** Перото „само здравно“ на фиша за платеца, или {@code null}. */
  private static BgPaySlipLine healthOnlyLine(BgPaySlip slip, String sign) {
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return null;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.isOwnPeriod(line)
          && sign.equals(line.getBgSign())
          && BgD1ContributionAggregator.BASE_HEALTH_ONLY.equals(
              BgD1ContributionAggregator.healthBaseOf(line))) {
        return line;
      }
    }
    return null;
  }

  private static String amountOf(BgPaySlipLine line) {
    return line == null ? "—" : days(line.getBgAmount());
  }

  private static String baseOf(BgPaySlipLine line) {
    return line == null ? "—" : days(line.getBgQuantity());
  }

  /**
   * Следата на „Попълни от текущите данни“: по един ред на поле, от празно към попълненото. Пише се
   * В МОМЕНТА на действието — тоест „кой“ и „кога“ са на човека, натиснал бутона.
   */
  public static void recordFilled(BgPaySlip copy, User user, LocalDateTime at) {
    recordFilled(copy, user, at, FROM_CURRENT);
  }

  /**
   * Както горното, с ПОВОДА на попълването — „от текущите данни“ или „при отразяване на болничен
   * лист № …“ (3А). Поводът отива на всеки ред и в обобщението.
   */
  public static void recordFilled(BgPaySlip copy, User user, LocalDateTime at, String how) {
    for (BgPayrollRegime.Field f : BgPayrollRegime.fieldsFor(copy)) {
      add(copy, f.name, f.title, "—", f.textOf(copy), user, at, FILLED).setNote(how);
    }
    copy.setBgRegimeChangeSummary(summary(copy));
  }

  /** Следата на попълнените дни на карва — по ред на число, от празно към попълненото. */
  public static void recordFilledDays(BgPaySlip copy, User user, LocalDateTime at, String how) {
    String art177 = days(copy.getBgArt177LeaveDays());
    String unpaid = days(copy.getBgUnpaidLeaveDaysInD1());
    add(copy, "bgArt177LeaveDays", ART177_DAYS, "—", art177, user, at, FILLED).setNote(how);
    add(copy, "bgUnpaidLeaveDaysInD1", UNPAID_D1_DAYS, "—", unpaid, user, at, FILLED).setNote(how);
    copy.setBgRegimeChangeSummary(summary(copy));
  }

  /**
   * Следата на попълненото използвано от таваните на социалните разходи — един ред, с капачките и
   * числата (виж {@code BgSocialExpenseService#fillMissing}).
   */
  public static void recordFilledCapUsage(
      BgPaySlip copy, User user, LocalDateTime at, String how, String filled) {
    add(copy, "bgCapUsageList", CAP_USAGE, "—", filled, user, at, FILLED).setNote(how);
    copy.setBgRegimeChangeSummary(summary(copy));
  }

  /** Заглавието на реда в одита за попълненото използвано от таваните. */
  public static final String CAP_USAGE = "използвано от таваните на социалните разходи";

  /** Дни като текст — „2,00“, както ги показва формата. */
  public static String days(BigDecimal value) {
    return value == null
        ? "—"
        : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  /**
   * ВИДИМОТО ИЗРЕЧЕНИЕ: какво в режима на копието се различава от оригинала, или — при заварен
   * оригинал — откога е попълнен и какво е сменено след това. {@code null}, ако няма какво да се
   * каже.
   */
  public static String summary(BgPaySlip copy) {
    List<String> parts = new ArrayList<>();
    List<String> all =
        Arrays.asList(regimeSummary(copy), daysSummary(copy), healthOnlySummary(copy));
    for (String part : all) {
      if (part != null) {
        parts.add(part);
      }
    }
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  /** „„Само здравно“ е поправено на ръка: …“ — разликите на копието спрямо оригинала. */
  private static String healthOnlySummary(BgPaySlip copy) {
    BgPaySlip original = originalOf(copy);
    List<String> changes = new ArrayList<>();
    for (String[] payer : HEALTH_ONLY) {
      BgPaySlipLine now = healthOnlyLine(copy, payer[0]);
      BgPaySlipLine was = healthOnlyLine(original, payer[0]);
      if (!amountOf(was).equals(amountOf(now))) {
        changes.add(healthOnlyTitle(payer, "сума") + " " + amountOf(was) + " → " + amountOf(now));
      }
      if (!baseOf(was).equals(baseOf(now))) {
        changes.add(healthOnlyTitle(payer, "база") + " " + baseOf(was) + " → " + baseOf(now));
      }
    }
    return changes.isEmpty()
        ? null
        : "„Само здравно“ е поправено на ръка: " + String.join("; ", changes) + ".";
  }

  private static String regimeSummary(BgPaySlip copy) {
    BgPaySlip original = originalOf(copy);
    List<String> changes = new ArrayList<>();
    String prefix;
    if (BgPayrollRegime.isFrozen(original)) {
      prefix = "Режимът е променен спрямо оригинала: ";
      for (BgPayrollRegime.Field f : BgPayrollRegime.fieldsFor(copy)) {
        String was = f.textOf(original);
        String is = f.textOf(copy);
        if (!was.equals(is)) {
          changes.add(f.title + " " + was + " → " + is);
        }
      }
      return changes.isEmpty() ? null : prefix + String.join("; ", changes) + ".";
    }
    BgPaySlipRegimeChange filled = firstFilled(copy, false);
    if (filled == null) {
      return null;
    }
    prefix =
        "Режимът е попълнен "
            + howOf(filled)
            + (filled.getChangedOn() == null ? "" : " на " + filled.getChangedOn().format(DAY))
            + " — оригиналът е отпреди замразяването.";
    for (BgPayrollRegime.Field f : BgPayrollRegime.fieldsFor(copy)) {
      String atFill = filledValue(copy, f.name);
      String is = f.textOf(copy);
      if (atFill != null && !atFill.equals(is)) {
        changes.add(f.title + " " + atFill + " → " + is);
      }
    }
    return changes.isEmpty() ? prefix : prefix + " След това: " + String.join("; ", changes) + ".";
  }

  /** „Дните на карва са попълнени …“ — когато копието ги е получило с попълване, не наследени. */
  private static String daysSummary(BgPaySlip copy) {
    BgPaySlipRegimeChange filled = firstFilled(copy, true);
    if (filled == null) {
      return null;
    }
    return "Дните на карва са попълнени "
        + howOf(filled)
        + (filled.getChangedOn() == null ? "" : " на " + filled.getChangedOn().format(DAY))
        + ": "
        + ART177_DAYS
        + " "
        + days(copy.getBgArt177LeaveDays())
        + ", "
        + UNPAID_D1_DAYS
        + " "
        + days(copy.getBgUnpaidLeaveDaysInD1())
        + ".";
  }

  private static String howOf(BgPaySlipRegimeChange row) {
    String note = row.getNote();
    return note == null || note.trim().isEmpty() ? FROM_CURRENT : note.trim();
  }

  private static boolean isDaysField(String fieldName) {
    return "bgArt177LeaveDays".equals(fieldName) || "bgUnpaidLeaveDaysInD1".equals(fieldName);
  }

  // ═══ ВЪТРЕШНОСТТА ════════════════════════════════════════════════════════════════════════

  private static BgPaySlip originalOf(BgPaySlip copy) {
    return Boolean.TRUE.equals(copy.getBgIsDeletion())
        ? copy.getBgDeletionOf()
        : copy.getBgCorrectionOf();
  }

  private static String lastRecorded(BgPaySlip copy, String field) {
    String last = null;
    if (copy.getBgRegimeChangeList() == null) {
      return null;
    }
    for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
      if (field.equals(c.getFieldName())) {
        last = c.getNewValue();
      }
    }
    return last;
  }

  private static BgPaySlipRegimeChange firstFilled(BgPaySlip copy, boolean daysField) {
    if (copy.getBgRegimeChangeList() == null) {
      return null;
    }
    for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
      if (FILLED.equals(c.getSource()) && isDaysField(c.getFieldName()) == daysField) {
        return c;
      }
    }
    return null;
  }

  private static String filledValue(BgPaySlip copy, String field) {
    String value = null;
    for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
      if (FILLED.equals(c.getSource()) && field.equals(c.getFieldName())) {
        value = c.getNewValue();
      }
    }
    return value;
  }

  private static BgPaySlipRegimeChange add(
      BgPaySlip copy,
      String name,
      String title,
      String was,
      String is,
      User user,
      LocalDateTime at,
      String source) {
    BgPaySlipRegimeChange c = new BgPaySlipRegimeChange();
    c.setFieldName(name);
    c.setFieldTitle(title);
    c.setOldValue(was);
    c.setNewValue(is);
    c.setChangedBy(user);
    c.setChangedOn(at);
    c.setSource(source);
    c.setPaySlip(copy);
    if (copy.getBgRegimeChangeList() == null) {
      copy.setBgRegimeChangeList(new ArrayList<>());
    }
    copy.getBgRegimeChangeList().add(c);
    return c;
  }
}
