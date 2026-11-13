package com.axelor.apps.bglocalization.service.nsi;

import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Family;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Kind;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Row;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Rule;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Table;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Token;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ОТ ЦИФРИТЕ ПО СМЕТКИ КЪМ СТОЙНОСТИТЕ НА РЕДОВЕТЕ — БЕЗ БАЗА, ЗА ДА СЕ ТЕСТВА ДИРЕКТНО.
 *
 * <p><b>Коя сметка към кой ред.</b> За всяка сметка и всяко семейство правила (салда, обороти,
 * изменение) печели редът с НАЙ-ДЪЛГИЯ съвпадащ префикс: 6044 „командировки“ отива в „Други
 * разходи“, макар 604 да е „възнаграждения“; 05600 събира целите класове 6 и 7, но 651 остава в
 * 04000. При равенство печелят всички — така една сметка стои и в актива (DP), и в пасива (KP), а
 * 702 дава и приходите (TNK_NOSTOCK), и себестойността (TN_STOCK). Редовете „в т.ч.“ (SUB) не се
 * състезават: те вземат каквото съвпада с техните префикси, без да отнемат от основния ред.
 *
 * <p><b>Сметка без ред е грешка, не тишина.</b> Салдо в баланса или оборот по клас 6/7 в ОПР без
 * ред значи, че сборът на формуляра не е сборът на счетоводството.
 */
public final class BgNsiGodCalculator {

  private BgNsiGodCalculator() {}

  /** Цифрите на една сметка в една колона, във валутата на справката. */
  public static final class Figures {
    /** Салдо (дебит − кредит) към края на годината на колоната. */
    public BigDecimal bal = BigDecimal.ZERO;

    /** Салдо към края на предходната година — за изменението на запасите. */
    public BigDecimal balPrev = BigDecimal.ZERO;

    public BigDecimal td = BigDecimal.ZERO;
    public BigDecimal tc = BigDecimal.ZERO;

    /** Обороти по статиите, в които има сметка 30x. */
    public BigDecimal tdStock = BigDecimal.ZERO;

    public BigDecimal tcStock = BigDecimal.ZERO;

    /**
     * Салдото към края на годината, разделено по партньор, по банкова сметка или по подсметка
     * (BgNsiGodForm.Split). Сборът им е {@link #bal}. Празно — едно цяло. DP/KP се прилагат на
     * всяка част поотделно: два партньора с противоположни салда не се нетират.
     */
    public List<BigDecimal> balParts = new ArrayList<>();

    /**
     * Обороти [дебит, кредит] само по статиите с насрещна сметка — по ключа {@code 123+69} на
     * префикса (BgNsiGodForm.Token#against).
     */
    public Map<String, BigDecimal[]> against = new HashMap<>();

    /** Групата нетира сметките си — частите на отделните сметки не се пренасят. */
    void add(Figures o) {
      bal = bal.add(o.bal);
      balPrev = balPrev.add(o.balPrev);
      td = td.add(o.td);
      tc = tc.add(o.tc);
      tdStock = tdStock.add(o.tdStock);
      tcStock = tcStock.add(o.tcStock);
      o.against.forEach(
          (k, v) ->
              against.merge(
                  k, v.clone(), (a, b) -> new BigDecimal[] {a[0].add(b[0]), a[1].add(b[1])}));
    }

    boolean hasTurnover() {
      return td.signum() != 0 || tc.signum() != 0;
    }
  }

  public static final class Result {
    /** Точната стойност на всеки ред ACC, по код. */
    public final Map<String, BigDecimal> values = new HashMap<>();

    /** Откъде е стойността: „сметка: сума“ — за проверка от счетоводителя. */
    public final Map<String, String> sources = new HashMap<>();

    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
  }

  /** Правилото на префикса — върху оборотите срещу насрещните сметки, ако префиксът ги има. */
  static BigDecimal apply(Token token, Figures f) {
    if (token.against == null) {
      return apply(token.rule, f);
    }
    BigDecimal[] dc = f.against.get(token.againstKey());
    Figures only = new Figures();
    only.td = dc == null ? BigDecimal.ZERO : dc[0];
    only.tc = dc == null ? BigDecimal.ZERO : dc[1];
    return apply(token.rule, only);
  }

  static BigDecimal apply(Rule rule, Figures f) {
    switch (rule) {
      case D:
        return f.bal;
      case K:
        return f.bal.negate();
      case DP:
        return side(f, true);
      case KP:
        return side(f, false);
      case TN:
        return f.td.subtract(f.tc);
      case TNK:
        return f.tc.subtract(f.td);
      case TC:
        return f.tc;
      case TD:
        return f.td;
      case TNK_NOSTOCK:
        return f.tc.subtract(f.tcStock).subtract(f.td.subtract(f.tdStock));
      case TN_STOCK:
        return f.tdStock.subtract(f.tcStock);
      case DELTA_POS:
        return f.bal.subtract(f.balPrev).max(BigDecimal.ZERO);
      case DELTA_NEG:
        return f.balPrev.subtract(f.bal).max(BigDecimal.ZERO);
      default:
        throw new IllegalArgumentException(String.valueOf(rule));
    }
  }

  /** Дебитните (актив) или кредитните (пасив) части на салдото, всяка поотделно. */
  private static BigDecimal side(Figures f, boolean debit) {
    List<BigDecimal> parts = f.balParts.isEmpty() ? List.of(f.bal) : f.balParts;
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal part : parts) {
      sum = sum.add((debit ? part : part.negate()).max(BigDecimal.ZERO));
    }
    return sum;
  }

  /** Сметката отива в реда през този префикс. */
  public static final class Hit {
    public final Row row;
    public final Token token;

    Hit(Row row, Token token) {
      this.row = row;
      this.token = token;
    }
  }

  /** Редовете, в които влиза сметката, за една таблица и едно семейство. */
  public static List<Hit> hits(BgNsiGodForm form, Table table, Family family, String account) {
    int best = 0;
    List<Hit> main = new ArrayList<>();
    List<Hit> sub = new ArrayList<>();
    for (Row row : form.rows) {
      if (row.table != table || row.tokens.isEmpty() || row.kind == Kind.SPLIT) {
        continue;
      }
      Token rowBest = null;
      int rowLen = 0;
      for (Token token : row.tokens) {
        int len = token.family == family ? token.match(account) : 0;
        if (len > rowLen) {
          rowLen = len;
          rowBest = token;
        }
      }
      if (rowBest == null) {
        continue;
      }
      if (row.isSub()) {
        sub.add(new Hit(row, rowBest));
      } else if (rowLen > best) {
        best = rowLen;
        main.clear();
        main.add(new Hit(row, rowBest));
      } else if (rowLen == best) {
        main.add(new Hit(row, rowBest));
      }
    }
    if (main.isEmpty()) {
      return main;
    }
    main.addAll(sub);
    return main;
  }

  public static Result compute(BgNsiGodForm form, Map<String, Figures> byAccount) {
    Result result = new Result();
    Map<String, StringBuilder> sources = new HashMap<>();
    Map<String, Map<Token, Figures>> groups = new LinkedHashMap<>();
    Map<String, List<String>> ignoredWithTurnover = new TreeMap<>();
    for (Map.Entry<String, Figures> e : new TreeMap<>(byAccount).entrySet()) {
      String account = e.getKey();
      Figures f = e.getValue();
      for (Table table : Table.values()) {
        for (Family family : Family.values()) {
          List<Hit> hits = hits(form, table, family, account);
          if (hits.isEmpty()) {
            unmapped(table, family, account, f, result);
            continue;
          }
          for (Hit hit : hits) {
            Row row = hit.row;
            if (row.kind == Kind.EXCL || row.kind == Kind.IGNORE) {
              if (hit.token.isGroup()) {
                groups
                    .computeIfAbsent(row.table + "|" + row.label, k -> new LinkedHashMap<>())
                    .computeIfAbsent(hit.token, k -> new Figures())
                    .add(f);
              } else if (row.kind == Kind.EXCL
                  && (table == Table.BS ? nonZeroBal(f) : f.hasTurnover())) {
                result.errors.add(
                    "Сметка "
                        + account
                        + " не е за нефинансово предприятие („"
                        + row.label
                        + "“), а има "
                        + (table == Table.BS ? "салдо " + f.bal.toPlainString() : "обороти")
                        + ".");
              } else if (row.kind == Kind.IGNORE && row.isCheck() && f.hasTurnover()) {
                ignoredWithTurnover.computeIfAbsent(row.label, k -> new ArrayList<>()).add(account);
              }
              continue;
            }
            if (hit.token.isGroup()) {
              groups
                  .computeIfAbsent(row.code, k -> new LinkedHashMap<>())
                  .computeIfAbsent(hit.token, k -> new Figures())
                  .add(f);
              continue;
            }
            BigDecimal value = apply(hit.token, f);
            if (value.signum() != 0) {
              result.values.merge(row.code, value, BigDecimal::add);
              note(sources, row.code, account, value);
            }
          }
        }
      }
    }
    for (Map.Entry<String, Map<Token, Figures>> g : groups.entrySet()) {
      for (Map.Entry<Token, Figures> t : g.getValue().entrySet()) {
        Token token = t.getKey();
        Figures f = t.getValue();
        if (token.rule == null) {
          if (g.getKey().startsWith("BS|") && nonZeroBal(f)) {
            result.errors.add(
                "Сметките "
                    + token.text
                    + " имат нетно салдо "
                    + f.bal.toPlainString()
                    + " — те трябва да се неутрализират (откриване/закриване на годината).");
          }
          continue;
        }
        BigDecimal value = apply(token, f);
        if (value.signum() != 0) {
          result.values.merge(g.getKey(), value, BigDecimal::add);
          note(sources, g.getKey(), token.text, value);
        }
      }
    }
    ignoredWithTurnover.forEach(
        (label, accounts) ->
            result.warnings.add(
                "Обороти по "
                    + String.join(", ", accounts)
                    + " („"
                    + label
                    + "“) — не влизат в"
                    + " ОПР. Проверете, че разходите не са прехвърлени от 60x към тях."));
    sources.forEach((code, text) -> result.sources.put(code, text.toString()));
    return result;
  }

  private static boolean nonZeroBal(Figures f) {
    return f.bal.signum() != 0;
  }

  private static void unmapped(
      Table table, Family family, String account, Figures f, Result result) {
    if (table == Table.BS && family == Family.BAL && nonZeroBal(f)) {
      result.errors.add(
          "Сметка " + account + " има салдо " + f.bal.toPlainString() + ", но няма ред в баланса.");
    } else if (table == Table.PL
        && family == Family.TURN
        && (account.startsWith("6") || account.startsWith("7"))
        && f.hasTurnover()) {
      result.errors.add("Сметка " + account + " има обороти, но няма ред в ОПР.");
    }
  }

  private static void note(
      Map<String, StringBuilder> sources, String code, String what, BigDecimal v) {
    StringBuilder sb = sources.computeIfAbsent(code, k -> new StringBuilder());
    if (sb.length() > 0) {
      sb.append("; ");
    }
    sb.append(what).append(": ").append(v.toPlainString());
  }

  /**
   * Сборовете над закръглените стойности. {@code leaves} — кодът и стойността на всеки ред със
   * стойност, която не е формула. Формулите могат да сочат и по-долни редове (14000 → 18000).
   */
  public static Map<String, BigDecimal> totals(BgNsiGodForm form, Map<String, BigDecimal> leaves) {
    Map<String, String> formulas = new HashMap<>();
    for (Row row : form.rows) {
      if (row.kind == Kind.SUM) {
        formulas.put(row.code, row.formula);
      }
    }
    Map<String, BigDecimal> out = new HashMap<>(leaves);
    for (String code : formulas.keySet()) {
      value(code, formulas, out, new ArrayList<>());
    }
    return out;
  }

  private static BigDecimal value(
      String code, Map<String, String> formulas, Map<String, BigDecimal> out, List<String> stack) {
    BigDecimal known = out.get(code);
    if (known != null) {
      return known;
    }
    String formula = formulas.get(code);
    if (formula == null) {
      throw new IllegalStateException("Формулата сочи непознат код " + code);
    }
    if (stack.contains(code)) {
      throw new IllegalStateException("Кръгова формула: " + stack + " → " + code);
    }
    stack.add(code);
    BigDecimal v = new Formula(formula, c -> value(c, formulas, out, stack)).parse();
    stack.remove(stack.size() - 1);
    out.put(code, v);
    return v;
  }

  /** expr := term (('+'|'-') term)*; term := POS(expr) | NEG(expr) | код. */
  static final class Formula {
    private final String s;
    private final java.util.function.Function<String, BigDecimal> lookup;
    private int pos;

    Formula(String s, java.util.function.Function<String, BigDecimal> lookup) {
      this.s = s.replace(" ", "");
      this.lookup = lookup;
    }

    BigDecimal parse() {
      BigDecimal v = expr();
      if (pos != s.length()) {
        throw new IllegalStateException("Формула „" + s + "“: излишък от позиция " + pos);
      }
      return v;
    }

    private BigDecimal expr() {
      BigDecimal v = term();
      while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
        char op = s.charAt(pos++);
        BigDecimal t = term();
        v = op == '+' ? v.add(t) : v.subtract(t);
      }
      return v;
    }

    private BigDecimal term() {
      int start = pos;
      while (pos < s.length() && Character.isLetterOrDigit(s.charAt(pos))) {
        pos++;
      }
      String word = s.substring(start, pos);
      if (pos < s.length() && s.charAt(pos) == '(') {
        pos++;
        BigDecimal inner = expr();
        if (pos >= s.length() || s.charAt(pos) != ')') {
          throw new IllegalStateException("Формула „" + s + "“: липсва „)“");
        }
        pos++;
        if ("POS".equals(word)) {
          return inner.max(BigDecimal.ZERO);
        }
        if ("NEG".equals(word)) {
          return inner.negate().max(BigDecimal.ZERO);
        }
        throw new IllegalStateException("Формула „" + s + "“: непозната функция " + word);
      }
      if (word.isEmpty()) {
        throw new IllegalStateException("Формула „" + s + "“: очаква се код на позиция " + start);
      }
      return lookup.apply(word);
    }
  }
}
