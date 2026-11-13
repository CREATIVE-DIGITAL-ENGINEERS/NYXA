package com.axelor.apps.bglocalization.service.nsi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ОБРАЗЕЦЪТ НА ГОД КЪМ НСИ — БАЛАНС (таблица 4) И ОПР (таблица 5) — КАТО ДАННИ.
 *
 * <p><b>⚠️ КОДОВЕТЕ НА РЕДОВЕТЕ НЕ СА В КОДА.</b> НСИ ги сменя по години, затова всяка година на
 * формуляра е отделен ресурс {@code nsi/god-<година>-nf2.csv}. За отчетна година без собствен файл
 * се взима последният по-ранен и справката го казва („да се потвърди“). Първият файл е за 2025:
 * Приложение № 2 (2025-НФ2), nsi.bg, PDF с Last-Modified 17.03.2026, прочетен на 27.10.2026.
 *
 * <p><b>Колоните на файла:</b> таблица (BS/PL); код на реда (празен за заглавие и служебните групи
 * IGNORE/EXCL); ниво за отстъпа; надпис от формуляра; вид; правило по подразбиране; сметки;
 * формула; признаци.
 *
 * <ul>
 *   <li><b>Вид:</b> TITLE — заглавие без код; ACC — сума по сметки; SUM — формула над други кодове;
 *       MANUAL — в сметкоплана няма сметка, попълва се ръчно; IGNORE — сметките са известни, но не
 *       дават стойност в таблицата; EXCL — сметки, които не са за нефинансово предприятие (салдо по
 *       тях е грешка); SPLIT — как се разделя салдото на сметка с двойна природа (Д/К) преди
 *       правилата DP/KP: PARTNER — по партньор, без нетиране между партньори; BANK — по банкова
 *       сметка; ACCOUNT — по подсметка. Режимът е в колоната на признаците.
 *   <li><b>Насрещни сметки:</b> {@code 4521@123+69} — оборотът само от статиите, в които има ред по
 *       ДРУГА сметка с префикс 123 или 69 (данъкът за годината, без Дт 122 / Кт 4521 за минали
 *       години).
 *   <li><b>Сметки:</b> префикси, разделени със запетая. {@code 702:TN_STOCK} сменя правилото за
 *       този префикс. {@code 4531+4532} е ГРУПА: сметките се нетират помежду си и правилото се
 *       прилага върху нетото (иначе ДДС за възстановяване и ДДС за внасяне биха излезли и в актива,
 *       и в пасива).
 *   <li><b>Признаци:</b> SUB — „в т.ч.“ (не участва в сборовете и не се състезава за сметката);
 *       CHECK — съответствието е по-широко от реда, справката предупреждава; MSS — „само за
 *       предприятията, прилагащи МСС“; SERVICE — служебен ред, не е от формуляра.
 * </ul>
 */
public final class BgNsiGodForm {

  /** Първата година, за която има образец. */
  public static final int FIRST_VERSION = 2025;

  public enum Table {
    BS,
    PL
  }

  public enum Kind {
    TITLE,
    ACC,
    SUM,
    MANUAL,
    IGNORE,
    EXCL,
    SPLIT
  }

  /** Как се разделя салдото на сметка с двойна природа. */
  public enum Split {
    PARTNER,
    BANK,
    ACCOUNT
  }

  /** Семейство: кои редове се състезават за една сметка. */
  public enum Family {
    BAL,
    TURN,
    DELTA
  }

  /** Правило: от цифрите на една сметка към стойност на реда. */
  public enum Rule {
    /** Салдо (дебит − кредит) към края на годината. */
    D(Family.BAL),
    /** Кредитно салдо, показано положително. */
    K(Family.BAL),
    /** Само дебитното салдо — „Д/К по знака“, страната на актива. */
    DP(Family.BAL),
    /** Само кредитното салдо — страната на пасива. */
    KP(Family.BAL),
    /** Нетен дебитен оборот за годината (разход). */
    TN(Family.TURN),
    /** Нетен кредитен оборот за годината (приход). */
    TNK(Family.TURN),
    /** Кредитен оборот. */
    TC(Family.TURN),
    /** Дебитен оборот. */
    TD(Family.TURN),
    /** Нетен кредитен оборот БЕЗ статиите със сметка 30x — приходите от продажби. */
    TNK_NOSTOCK(Family.TURN),
    /** Нетен дебитен оборот САМО по статиите със сметка 30x — себестойността на продаденото. */
    TN_STOCK(Family.TURN),
    /** Увеличение на салдото спрямо края на предходната година. */
    DELTA_POS(Family.DELTA),
    /** Намаление на салдото спрямо края на предходната година. */
    DELTA_NEG(Family.DELTA);

    public final Family family;

    Rule(Family family) {
      this.family = family;
    }
  }

  /** Един префикс или група от префикси, нетирани заедно. */
  public static final class Token {
    public final List<String> prefixes;

    /** {@code null} за IGNORE/EXCL. */
    public final Rule rule;

    public final Family family;
    public final String text;

    /**
     * Насрещните сметки ({@code 4521@123+69}): оборотът се взима само от статиите, в които има ред
     * по друга сметка с някой от тези префикси. {@code null} — целият оборот.
     */
    public final List<String> against;

    Token(List<String> prefixes, Rule rule, Family family, String text, List<String> against) {
      this.prefixes = prefixes;
      this.rule = rule;
      this.family = family;
      this.text = text;
      this.against = against;
    }

    /** Ключът на насрещните сметки — {@code 123+69}; {@code null} — няма. */
    public String againstKey() {
      return against == null ? null : String.join("+", against);
    }

    public boolean isGroup() {
      return prefixes.size() > 1;
    }

    /** Дължината на най-дългия префикс, с който започва сметката; 0 — не съвпада. */
    public int match(String accountCode) {
      int best = 0;
      for (String prefix : prefixes) {
        if (accountCode.startsWith(prefix) && prefix.length() > best) {
          best = prefix.length();
        }
      }
      return best;
    }
  }

  public static final class Row {
    public final Table table;
    public final String code;
    public final int level;
    public final String label;
    public final Kind kind;
    public final List<Token> tokens;
    public final String formula;
    public final Set<String> flags;

    Row(
        Table table,
        String code,
        int level,
        String label,
        Kind kind,
        List<Token> tokens,
        String formula,
        Set<String> flags) {
      this.table = table;
      this.code = code;
      this.level = level;
      this.label = label;
      this.kind = kind;
      this.tokens = tokens;
      this.formula = formula;
      this.flags = flags;
    }

    public boolean isSub() {
      return flags.contains("SUB");
    }

    public boolean isCheck() {
      return flags.contains("CHECK");
    }

    public boolean isMss() {
      return flags.contains("MSS");
    }

    public boolean isService() {
      return flags.contains("SERVICE");
    }

    /** Ред със стойност във формуляра (не заглавие и не служебна група). */
    public boolean hasValue() {
      return kind == Kind.ACC || kind == Kind.SUM || kind == Kind.MANUAL;
    }
  }

  public final int version;
  public final List<Row> rows;

  private BgNsiGodForm(int version, List<Row> rows) {
    this.version = version;
    this.rows = Collections.unmodifiableList(rows);
  }

  public static String resource(int version) {
    return "nsi/god-" + version + "-nf2.csv";
  }

  /**
   * Образецът за отчетната година: собственият ѝ файл или последният по-ранен. За година преди
   * първия образец се взима първият.
   */
  public static BgNsiGodForm forYear(int reportYear) {
    for (int version = Math.max(reportYear, FIRST_VERSION); version >= FIRST_VERSION; version--) {
      InputStream in = BgNsiGodForm.class.getClassLoader().getResourceAsStream(resource(version));
      if (in != null) {
        return parse(version, in);
      }
    }
    throw new IllegalStateException("Няма образец на ГОД: " + resource(FIRST_VERSION));
  }

  static BgNsiGodForm parse(int version, InputStream in) {
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parse(version, lines);
  }

  static BgNsiGodForm parse(int version, List<String> lines) {
    List<Row> rows = new ArrayList<>();
    Set<String> codes = new HashSet<>();
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      String[] c = line.split(";", -1);
      if (c.length != 9) {
        throw new IllegalStateException(
            resource(version) + ", ред " + (i + 1) + ": очакват се 9 колони, а са " + c.length);
      }
      Table table = Table.valueOf(c[0]);
      String code = c[1].trim();
      Kind kind = Kind.valueOf(c[4]);
      Rule rule = c[5].isEmpty() ? null : Rule.valueOf(c[5]);
      Family rowFamily =
          rule != null ? rule.family : (table == Table.BS ? Family.BAL : Family.TURN);
      List<Token> tokens = new ArrayList<>();
      if (!c[6].isEmpty()) {
        for (String text : c[6].split(",")) {
          String[] parts = text.split(":");
          Rule tokenRule = parts.length > 1 ? Rule.valueOf(parts[1]) : rule;
          Family family = tokenRule != null ? tokenRule.family : rowFamily;
          String[] at = parts[0].split("@");
          List<String> against = at.length > 1 ? Arrays.asList(at[1].split("\\+")) : null;
          if (against != null && family != Family.TURN) {
            throw new IllegalStateException(
                resource(version) + ": " + code + " — насрещни сметки само при оборот: " + text);
          }
          tokens.add(
              new Token(Arrays.asList(at[0].split("\\+")), tokenRule, family, at[0], against));
        }
      }
      if (!code.isEmpty() && !codes.add(code)) {
        throw new IllegalStateException(resource(version) + ": кодът " + code + " е два пъти");
      }
      if ((kind == Kind.ACC) != (rule != null) && kind != Kind.IGNORE && kind != Kind.EXCL) {
        throw new IllegalStateException(resource(version) + ": " + code + " — вид и правило");
      }
      if ((kind == Kind.SUM) == c[7].isEmpty()) {
        throw new IllegalStateException(resource(version) + ": " + code + " — формула");
      }
      if (kind.ordinal() <= Kind.MANUAL.ordinal() && kind != Kind.TITLE && code.isEmpty()) {
        throw new IllegalStateException(resource(version) + ", ред " + (i + 1) + ": без код");
      }
      Set<String> flags =
          c[8].isEmpty()
              ? Collections.emptySet()
              : new LinkedHashSet<>(Arrays.asList(c[8].split("\\|")));
      rows.add(
          new Row(
              table,
              code,
              c[2].isEmpty() ? 0 : Integer.parseInt(c[2]),
              c[3],
              kind,
              Collections.unmodifiableList(tokens),
              c[7],
              Collections.unmodifiableSet(flags)));
    }
    return new BgNsiGodForm(version, rows);
  }

  public Row row(String code) {
    for (Row row : rows) {
      if (row.code.equals(code)) {
        return row;
      }
    }
    return null;
  }

  /**
   * Режимът на разделяне за сметката — най-дългият префикс сред редовете SPLIT; иначе подсметка.
   */
  public Split splitMode(String accountCode) {
    Split mode = Split.ACCOUNT;
    int best = 0;
    for (Row row : rows) {
      if (row.kind != Kind.SPLIT) {
        continue;
      }
      for (Token token : row.tokens) {
        int len = token.match(accountCode);
        if (len > best) {
          best = len;
          mode = Split.valueOf(row.flags.iterator().next());
        }
      }
    }
    return mode;
  }
}
