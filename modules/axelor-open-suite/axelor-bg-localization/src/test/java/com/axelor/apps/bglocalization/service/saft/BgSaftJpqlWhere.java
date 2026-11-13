package com.axelor.apps.bglocalization.service.saft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHERE ФРАГМЕНТ НА JPQL, ОЦЕНЕН ВЪРХУ РЕД В ПАМЕТТА — само за тестовете.
 *
 * <p><b>⚠️ ЗАЩО.</b> Общата база няма нито един {@code MoveLine}, а модулът няма mock-подпора за
 * JPA. Без този оценител реконсилиацията „салда (G2a) срещу дневник (G3)“ би сравнявала два
 * assembler-а върху един и същ ръчно подаден набор — тоест не би доказала, че двете заявки избират
 * едни и същи статии. Тук се оценява ИСТИНСКИЯТ текст на заявките, тоест различен предикат в G3
 * чупи реконсилиацията.
 *
 * <p><b>⚠️ ТРИСТОЙНОСТНА ЛОГИКА, КАТО В SQL.</b> Сравнение с NULL е „неизвестно“, а WHERE пропуска
 * само „истина“ — точно капанът {@code functionalOriginSelect <> 1} без {@code IS NULL}.
 *
 * <p>Поддържа подмножеството, което ползват заявките на SAF-T: AND, OR, скоби, {@code = <> < <= >
 * >=}, {@code IS [NOT] NULL}, {@code IN (:списък)}, параметри и литералите true/false/цели числа.
 * Всичко друго гърми, вместо да се оцени тихо.
 */
final class BgSaftJpqlWhere {

  private static final Pattern TOKEN = Pattern.compile("\\(|\\)|<>|<=|>=|=|<|>|:\\w+|[\\w.]+|\\S");

  private final List<String> tokens;
  private final Map<String, Object> parameters;
  private final Function<String, Object> paths;
  private int pos;

  private BgSaftJpqlWhere(
      List<String> tokens, Map<String, Object> parameters, Function<String, Object> paths) {
    this.tokens = tokens;
    this.parameters = parameters;
    this.paths = paths;
  }

  /** Условието между WHERE и GROUP BY / ORDER BY (или края) на цялата заявка. */
  static String where(String jpql) {
    int from = jpql.indexOf(" WHERE ");
    if (from < 0) {
      throw new IllegalArgumentException("заявката няма WHERE: " + jpql);
    }
    int end = jpql.length();
    for (String tail : List.of(" GROUP BY ", " ORDER BY ")) {
      int i = jpql.indexOf(tail, from);
      if (i >= 0) {
        end = Math.min(end, i);
      }
    }
    return jpql.substring(from + " WHERE ".length(), end);
  }

  /**
   * @param where условието
   * @param parameters стойностите на именуваните параметри
   * @param paths стойността на път като {@code self.move.date} за реда
   * @return дали WHERE пропуска реда — само при „истина“
   */
  static boolean matches(
      String where, Map<String, Object> parameters, Function<String, Object> paths) {
    List<String> tokens = new ArrayList<>();
    Matcher m = TOKEN.matcher(where);
    while (m.find()) {
      tokens.add(m.group());
    }
    BgSaftJpqlWhere parser = new BgSaftJpqlWhere(tokens, parameters, paths);
    Boolean result = parser.or();
    if (parser.pos != tokens.size()) {
      throw new IllegalStateException(
          "неразбран остатък: " + tokens.subList(parser.pos, tokens.size()));
    }
    return Boolean.TRUE.equals(result);
  }

  private Boolean or() {
    Boolean value = and();
    while (accept("OR")) {
      Boolean right = and();
      value =
          Boolean.TRUE.equals(value) || Boolean.TRUE.equals(right)
              ? Boolean.TRUE
              : value == null || right == null ? null : Boolean.FALSE;
    }
    return value;
  }

  private Boolean and() {
    Boolean value = unary();
    while (accept("AND")) {
      Boolean right = unary();
      value =
          Boolean.FALSE.equals(value) || Boolean.FALSE.equals(right)
              ? Boolean.FALSE
              : value == null || right == null ? null : Boolean.TRUE;
    }
    return value;
  }

  private Boolean unary() {
    if (accept("(")) {
      Boolean value = or();
      expect(")");
      return value;
    }
    return comparison();
  }

  private Boolean comparison() {
    Object left = operand(next());
    if (accept("IS")) {
      boolean not = accept("NOT");
      expect("NULL");
      return not ? left != null : left == null;
    }
    if (accept("IN")) {
      expect("(");
      Object list = operand(next());
      expect(")");
      if (!(list instanceof Collection)) {
        throw new IllegalStateException("IN иска списък, а получи " + list);
      }
      return left == null ? null : ((Collection<?>) list).contains(left);
    }
    String op = next();
    Object right = operand(next());
    if (left == null || right == null) {
      return null;
    }
    int cmp = compare(left, right);
    switch (op) {
      case "=":
        return cmp == 0;
      case "<>":
        return cmp != 0;
      case "<":
        return cmp < 0;
      case "<=":
        return cmp <= 0;
      case ">":
        return cmp > 0;
      case ">=":
        return cmp >= 0;
      default:
        throw new IllegalStateException("непознат оператор: " + op);
    }
  }

  private Object operand(String token) {
    if (token.startsWith(":")) {
      String name = token.substring(1);
      if (!parameters.containsKey(name)) {
        throw new IllegalStateException("параметър без стойност: " + token);
      }
      return parameters.get(name);
    }
    if ("true".equals(token) || "false".equals(token)) {
      return Boolean.valueOf(token);
    }
    if (token.matches("-?\\d+")) {
      return Long.valueOf(token);
    }
    if (token.startsWith("self.")) {
      return paths.apply(token);
    }
    throw new IllegalStateException("непознат операнд: " + token);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compare(Object left, Object right) {
    if (left instanceof Number && right instanceof Number) {
      return Long.compare(((Number) left).longValue(), ((Number) right).longValue());
    }
    if (left instanceof Boolean || right instanceof Boolean) {
      return left.equals(right) ? 0 : 1;
    }
    if (left.getClass() != right.getClass()) {
      throw new IllegalStateException("несравними: " + left + " и " + right);
    }
    return ((Comparable) left).compareTo(right);
  }

  private boolean accept(String token) {
    if (pos < tokens.size() && tokens.get(pos).equals(token)) {
      pos++;
      return true;
    }
    return false;
  }

  private void expect(String token) {
    if (!accept(token)) {
      throw new IllegalStateException(
          "очаква се „" + token + "“ на позиция " + pos + ": " + tokens);
    }
  }

  private String next() {
    if (pos >= tokens.size()) {
      throw new IllegalStateException("неочакван край: " + tokens);
    }
    return tokens.get(pos++);
  }
}
