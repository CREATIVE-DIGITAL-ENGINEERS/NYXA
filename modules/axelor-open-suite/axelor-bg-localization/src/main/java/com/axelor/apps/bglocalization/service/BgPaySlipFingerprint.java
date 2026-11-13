package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.db.Model;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ОТПЕЧАТЪКЪТ НА ПОСЛЕДНОТО ПРЕСМЯТАНЕ — каноничният текст на перата и замразените входове.
 *
 * <p>{@code BgPaySlipService#calculate} го записва в края си; {@code confirm()} го сравнява с
 * текущото състояние на фиша. Различен значи, че нещо е пипано след пресмятането — ново перо,
 * сменена сума, сменени дни — и фишът не се потвърждава, докато не се пресметне наново.
 *
 * <p><b>⚠️ ЗАЩО ТЕКСТ, А НЕ ХЕШ.</b> Сравнението е едно и също, но текстът се ЧЕТЕ: отказът казва
 * „перо 180 „Постоянен бонус“ — въведени 200,00, сума 0,00“ вместо „нещо е променено“. Операторът
 * получава причината, а не задачата да я търси.
 *
 * <p><b>⚠️ ЧИСТИ ФУНКЦИИ.</b> Нито Guice, нито заявка: пазачът се вика от {@code confirm()} и
 * трябва да работи и в тестовете без контейнер, а сравнението трябва да се доказва с числа.
 *
 * <p><b>Какво влиза.</b> За всяко перо — видът (кодът на типа и номерът), базата на здравната
 * вноска, коригираният период, въведената сума и сумата. Наименованието се носи само за текста на
 * отказа и НЕ участва в сравнението. Перата са подредени, тоест редът на въвеждане не е разлика.
 * Входовете са замразените полета от формата — точно тези, от които генераторите четат.
 */
public final class BgPaySlipFingerprint {

  static final String HEADER = "bg-payslip-fingerprint v1";

  private static final String INPUT = "I";
  private static final String LINE = "L";
  private static final String SEP = "|";

  /** Замразените входове: името на полето → думите, с които отказът го назовава. */
  private static final Map<String, String> INPUT_LABELS = new LinkedHashMap<>();

  static {
    INPUT_LABELS.put("period", "период");
    INPUT_LABELS.put("employmentContract", "трудов договор");
    INPUT_LABELS.put("bgSelfInsuredRegistration", "регистрация (СОЛ)");
    INPUT_LABELS.put("bgWorkingDaysInPeriod", "работни дни в периода");
    INPUT_LABELS.put("bgWorkedDays", "отработени дни");
    INPUT_LABELS.put("bgBaseSalary", "основна заплата");
    INPUT_LABELS.put("bgAutoFillBaseSalary", "авто-попълване на основната заплата");
    INPUT_LABELS.put("bgSickDaysEmployer", "дни болничен от работодател");
    INPUT_LABELS.put("bgSickDaysDoo", "дни болничен от ДОО");
    INPUT_LABELS.put("bgSickDaysNoBenefit", "дни болничен без право на обезщетение");
    INPUT_LABELS.put("bgArt177Refusal", "отказ по чл. 177");
    INPUT_LABELS.put("bgOtherPayerInsurableIncome", "осигурителен доход при друг платец");
    INPUT_LABELS.put("bgNoOtherPayerIncome", "няма доход при друг платец");
  }

  private BgPaySlipFingerprint() {}

  // ═══ ЗАПИСЪТ ═════════════════════════════════════════════════════════════

  /** Каноничният текст на фиша такъв, какъвто е сега. */
  public static String of(BgPaySlip slip) {
    StringBuilder sb = new StringBuilder(HEADER).append('\n');
    for (Map.Entry<String, String> e : inputs(slip).entrySet()) {
      sb.append(INPUT).append(SEP).append(e.getKey()).append(SEP).append(e.getValue()).append('\n');
    }
    List<String> encoded = new ArrayList<>();
    for (Line line : linesOf(slip)) {
      encoded.add(line.encode());
    }
    Collections.sort(encoded);
    for (String e : encoded) {
      sb.append(e).append('\n');
    }
    return sb.toString();
  }

  /**
   * Замразените входове в каноничен вид. Ползва се и от контролера, за да сравни ЕКРАНА със
   * записаното преди потвърждаване.
   */
  public static Map<String, String> inputs(BgPaySlip slip) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("period", idOf(slip.getPeriod()));
    m.put("employmentContract", idOf(slip.getEmploymentContract()));
    m.put("bgSelfInsuredRegistration", idOf(slip.getBgSelfInsuredRegistration()));
    m.put("bgWorkingDaysInPeriod", String.valueOf(slip.getBgWorkingDaysInPeriod()));
    m.put("bgWorkedDays", String.valueOf(slip.getBgWorkedDays()));
    m.put("bgBaseSalary", money(slip.getBgBaseSalary()));
    m.put("bgAutoFillBaseSalary", flag(slip.getBgAutoFillBaseSalary()));
    m.put("bgSickDaysEmployer", String.valueOf(slip.getBgSickDaysEmployer()));
    m.put("bgSickDaysDoo", String.valueOf(slip.getBgSickDaysDoo()));
    // ⚠️ НОВ КЛЮЧ: отпечатък отпреди него няма този ред, тоест сравнението дава „— → N“ и черновата
    // иска „Преизчисли“. Нарочно — иначе фиш, пресметнат преди полето, се потвърждава с 0 и Д1
    // обявява дните без право на обезщетение в т. 16.2.
    m.put("bgSickDaysNoBenefit", String.valueOf(slip.getBgSickDaysNoBenefit()));
    // ⚠️ ОТКАЗЪТ ПО ЧЛ. 177 — нов ключ по същия довод: пресметнатата преди него чернова иска
    // „Преизчисли“, а той записва отказа, който спира „Потвърди“. Редовете се сливат: отпечатъкът е
    // ред по ред.
    m.put("bgArt177Refusal", oneLine(slip.getBgArt177Refusal()));
    m.put("bgOtherPayerInsurableIncome", money(slip.getBgOtherPayerInsurableIncome()));
    m.put("bgNoOtherPayerIncome", flag(slip.getBgNoOtherPayerIncome()));
    return m;
  }

  // ═══ СРАВНЕНИЕТО ═════════════════════════════════════════════════════════

  /**
   * Какво е различно днес спрямо записания отпечатък — по едно изречение на разлика. Празен списък
   * значи „нищо не е пипано“.
   */
  public static List<String> changes(String stored, BgPaySlip slip) {
    Parsed before = parse(stored);
    if (!before.known) {
      return Collections.singletonList("отпечатъкът на пресмятането е в непознат формат");
    }
    List<String> out = new ArrayList<>();
    Map<String, String> now = inputs(slip);
    for (Map.Entry<String, String> label : INPUT_LABELS.entrySet()) {
      String key = label.getKey();
      String was = before.inputs.get(key);
      String is = now.get(key);
      if (!Objects.equals(was, is)) {
        out.add(label.getValue() + " " + display(key, was) + " → " + display(key, is));
      }
    }

    List<Line> removed = new ArrayList<>(before.lines);
    List<Line> added = new ArrayList<>();
    for (Line line : linesOf(slip)) {
      if (takeFirst(removed, x -> x.key().equals(line.key())) == null) {
        added.add(line);
      }
    }
    for (Line a : added) {
      Line r = takeFirst(removed, x -> x.identity().equals(a.identity()));
      out.add(r == null ? a.describe() : a.describeChangeFrom(r));
    }
    for (Line r : removed) {
      out.add(r.label() + " — махнато (беше сума " + amountText(r.amount) + ")");
    }
    return out;
  }

  /**
   * Входовете, по които ЕКРАНЪТ се различава от ЗАПИСАНОТО — за контролера преди потвърждаване.
   * Празен списък значи „същото е“.
   */
  public static List<String> screenDiffers(BgPaySlip saved, BgPaySlip screen) {
    List<String> out = new ArrayList<>();
    Map<String, String> was = inputs(saved);
    Map<String, String> is = inputs(screen);
    for (Map.Entry<String, String> label : INPUT_LABELS.entrySet()) {
      String key = label.getKey();
      if (!Objects.equals(was.get(key), is.get(key))) {
        out.add(
            label.getValue()
                + " — записано "
                + display(key, was.get(key))
                + ", на екрана "
                + display(key, is.get(key)));
      }
    }
    return out;
  }

  /** Сумата така, както я показва фишът: две цифри след запетаята. */
  public static String amountText(String canonical) {
    return canonical == null ? "—" : canonical.replace('.', ',');
  }

  /** Сумата така, както я показва фишът: две цифри след запетаята. */
  public static String amountText(BigDecimal value) {
    return amountText(money(value));
  }

  // ═══ ВЪТРЕШНОСТТА ════════════════════════════════════════════════════════

  private static List<Line> linesOf(BgPaySlip slip) {
    List<Line> out = new ArrayList<>();
    if (slip.getBgPaySlipLineList() == null) {
      return out;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line != null) {
        out.add(Line.of(line));
      }
    }
    return out;
  }

  private static Line takeFirst(List<Line> lines, Predicate<Line> match) {
    Iterator<Line> it = lines.iterator();
    while (it.hasNext()) {
      Line l = it.next();
      if (match.test(l)) {
        it.remove();
        return l;
      }
    }
    return null;
  }

  private static Parsed parse(String text) {
    Parsed p = new Parsed();
    if (text == null) {
      return p;
    }
    String[] rows = text.split("\n");
    if (rows.length == 0 || !HEADER.equals(rows[0])) {
      return p;
    }
    p.known = true;
    for (int i = 1; i < rows.length; i++) {
      String row = rows[i];
      if (row.startsWith(INPUT + SEP)) {
        String[] kv = row.split("\\|", 3);
        p.inputs.put(kv[1], kv.length > 2 ? kv[2] : "");
      } else if (row.startsWith(LINE + SEP)) {
        Line l = Line.decode(row);
        if (l != null) {
          p.lines.add(l);
        }
      }
    }
    return p;
  }

  private static String display(String key, String value) {
    if (value == null) {
      return "—";
    }
    switch (key) {
      case "bgBaseSalary":
      case "bgOtherPayerInsurableIncome":
        return amountText(value);
      case "bgAutoFillBaseSalary":
      case "bgNoOtherPayerIncome":
        return "true".equals(value) ? "да" : "не";
      case "bgArt177Refusal":
        return value.isEmpty() ? "няма" : value;
      case "period":
      case "employmentContract":
      case "bgSelfInsuredRegistration":
        return value.isEmpty() ? "—" : "№ " + value;
      default:
        return value;
    }
  }

  /** Текст на един ред — отпечатъкът е по ред на вход. Празно за липсващ. */
  static String oneLine(String text) {
    return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
  }

  static String money(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String flag(Boolean value) {
    return String.valueOf(Boolean.TRUE.equals(value));
  }

  private static String idOf(Model model) {
    return model == null || model.getId() == null ? "" : String.valueOf(model.getId());
  }

  private static String clean(String text) {
    return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
  }

  private static final class Parsed {
    boolean known;
    final Map<String, String> inputs = new LinkedHashMap<>();
    final List<Line> lines = new ArrayList<>();
  }

  /** Едно перо в отпечатъка. Наименованието е само за текста — {@link #key()} не го съдържа. */
  private static final class Line {
    final String code;
    final String number;
    final String baseKind;
    final String corrects;
    final String entered;
    final String amount;
    final String name;

    private Line(
        String code,
        String number,
        String baseKind,
        String corrects,
        String entered,
        String amount,
        String name) {
      this.code = code;
      this.number = number;
      this.baseKind = baseKind;
      this.corrects = corrects;
      this.entered = entered;
      this.amount = amount;
      this.name = name;
    }

    static Line of(BgPaySlipLine line) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      String base = line.getBgHealthBaseKind();
      return new Line(
          clean(type == null ? null : type.getCode()),
          String.valueOf(line.getBgLineNumber()),
          clean(base == null ? null : base.trim()),
          idOf(line.getBgCorrectsPeriod()),
          money(line.getBgEnteredAmount()),
          money(line.getBgAmount()),
          clean(line.getName()));
    }

    static Line decode(String row) {
      String[] f = row.split("\\|", 8);
      if (f.length < 8) {
        return null;
      }
      return new Line(f[1], f[2], f[3], f[4], f[5], f[6], f[7]);
    }

    String identity() {
      return code + SEP + number + SEP + baseKind + SEP + corrects;
    }

    String key() {
      return identity() + SEP + entered + SEP + amount;
    }

    String encode() {
      return LINE + SEP + key() + SEP + name;
    }

    String label() {
      return "перо " + number + " „" + name + "“";
    }

    boolean hasEntered() {
      return new BigDecimal(entered).signum() != 0;
    }

    String describe() {
      return label()
          + " — "
          + (hasEntered() ? "въведени " + amountText(entered) + ", " : "")
          + "сума "
          + amountText(amount);
    }

    String describeChangeFrom(Line before) {
      List<String> parts = new ArrayList<>();
      if (!entered.equals(before.entered)) {
        parts.add("въведени " + amountText(before.entered) + " → " + amountText(entered));
      }
      if (!amount.equals(before.amount)) {
        parts.add("сума " + amountText(before.amount) + " → " + amountText(amount));
      }
      return label() + " — " + String.join(", ", parts);
    }
  }
}
