package com.axelor.apps.bglocalization.service.accounting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * КОЯ СМЕТКА В КОЙ РЕД НА СПРАВКАТА — БЕЗ БАЗА, ЗА ДА СЕ ТЕСТВА ДИРЕКТНО.
 *
 * <p><b>1. Подсметка без свой ред отива в реда на най-близкия изброен предшественик.</b> Редовете
 * на BG_COA изброяват синтетичните сметки с точен код ({@code 504}), а {@code LIKE '504'} не хваща
 * подсметката {@code 5042}, която дружеството си е открило. Тя не влизаше в нито един ред —
 * балансът на „Демо Търговия“ се разминаваше точно с нейното салдо (−4 134,84). Тук всяка сметка,
 * която не съвпада с нито един токен на справката, тръгва нагоре по {@code parentAccount} до
 * първата, която съвпада, и влиза в нейните редове. Сметка със свой ред (пряко съвпадение в която и
 * да е линия на справката) никога не се пренася — така нищо не се брои два пъти. Шаблонните
 * подсметки на BG_COA висят на групови заглавия ({@code 4211} → {@code C010}, не → {@code 421}),
 * тоест правилото не мести нито една шаблонна сметка.
 *
 * <p><b>2. {@code 702@30} — само статиите, в които има ред по сметка 30x.</b> Себестойността на
 * продадените стоки се записва Дт 702 / Кт 304 (изписването на склада). Ред с токен {@code 702@30}
 * взима само тези статии; всеки ДРУГ ред на същата справка, който съдържа 702 без суфикса, ги
 * изключва. Сметката се разделя между двата реда по статии — нищо не се губи и нищо не се брои два
 * пъти. В справка без {@code @30} нищо не се мени. Предишната версия на приложението търси {@code
 * LIKE '702@30'}, не намира сметка и редът е нула — затова ред с {@code @30} е безопасен и за нея.
 *
 * <p>Токенът е {@code LIKE} шаблон ({@code %}, {@code _}), както в AOS.
 */
public final class BgReportAccountMap {

  /** Суфиксът „само статиите със сметка 30x“. */
  public static final String STOCK_SUFFIX = "@30";

  /** Префиксът на сметките, които правят статията „складова“. */
  public static final String STOCK_ACCOUNT_PREFIX = "30";

  private BgReportAccountMap() {}

  /** Сметка: id, код, id на родителя (или null). */
  public static final class Acc {
    final long id;
    final String code;
    final Long parentId;

    public Acc(long id, String code, Long parentId) {
      this.id = id;
      this.code = code;
      this.parentId = parentId;
    }
  }

  /** Ред „сума по сметки“: id и {@code accountCode}. */
  public static final class Line {
    final long id;
    final String accountCode;

    public Line(long id, String accountCode) {
      this.id = id;
      this.accountCode = accountCode;
    }
  }

  /** Резултатът за един ред. */
  public static final class Resolved {
    /** Подсметките, пренесени в реда от изброен предшественик. */
    public final Set<Long> extra = new HashSet<>();

    /** Сметките на реда, които влизат САМО със статиите със 30x. */
    public final Set<Long> withStock = new HashSet<>();

    /** Сметките на реда, които влизат САМО със статиите БЕЗ 30x. */
    public final Set<Long> withoutStock = new HashSet<>();
  }

  /** Токенът без суфикса {@link #STOCK_SUFFIX} — това, което се търси с {@code LIKE}. */
  public static String pattern(String token) {
    String t = token.trim();
    return t.endsWith(STOCK_SUFFIX) ? t.substring(0, t.length() - STOCK_SUFFIX.length()) : t;
  }

  public static boolean isStock(String token) {
    return token.trim().endsWith(STOCK_SUFFIX);
  }

  /** {@code LIKE} на SQL върху кода: {@code %} — произволно, {@code _} — един знак. */
  public static boolean like(String pattern, String code) {
    if (code == null || pattern.isEmpty()) {
      return false;
    }
    StringBuilder rx = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      if (c == '%') {
        rx.append(".*");
      } else if (c == '_') {
        rx.append('.');
      } else {
        rx.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return code.matches(rx.toString());
  }

  public static List<String> tokens(String accountCode) {
    List<String> out = new ArrayList<>();
    if (accountCode == null) {
      return out;
    }
    for (String token : accountCode.split(",")) {
      if (!token.trim().isEmpty()) {
        out.add(token.trim());
      }
    }
    return out;
  }

  /** Пряко съвпадение: ред и дали е през токен {@code @30}. */
  private static final class Hit {
    final long lineId;
    final boolean stock;

    Hit(long lineId, boolean stock) {
      this.lineId = lineId;
      this.stock = stock;
    }
  }

  public static Map<Long, Resolved> resolve(List<Line> lines, List<Acc> accounts) {
    Map<Long, Acc> byId = new HashMap<>();
    for (Acc a : accounts) {
      byId.put(a.id, a);
    }
    // Преките съвпадения на всяка сметка.
    Map<Long, List<Hit>> direct = new LinkedHashMap<>();
    for (Acc a : accounts) {
      List<Hit> hits = new ArrayList<>();
      for (Line line : lines) {
        for (String token : tokens(line.accountCode)) {
          if (like(pattern(token), a.code)) {
            hits.add(new Hit(line.id, isStock(token)));
          }
        }
      }
      if (!hits.isEmpty()) {
        direct.put(a.id, hits);
      }
    }
    // Съвпаденията на всяка сметка — преките или наследените от най-близкия изброен предшественик.
    Map<Long, List<Hit>> reached = new LinkedHashMap<>(direct);
    Set<Long> inherited = new HashSet<>();
    for (Acc a : accounts) {
      if (direct.containsKey(a.id)) {
        continue;
      }
      Set<Long> seen = new HashSet<>();
      Long up = a.parentId;
      while (up != null && seen.add(up)) {
        if (direct.containsKey(up)) {
          reached.put(a.id, direct.get(up));
          inherited.add(a.id);
          break;
        }
        Acc parent = byId.get(up);
        up = parent == null ? null : parent.parentId;
      }
    }
    // Сметките, до които стига поне един токен @30 в справката.
    Set<Long> stocked = new HashSet<>();
    reached.forEach(
        (id, hits) -> {
          if (hits.stream().anyMatch(h -> h.stock)) {
            stocked.add(id);
          }
        });
    Map<Long, Resolved> out = new HashMap<>();
    for (Line line : lines) {
      out.put(line.id, new Resolved());
    }
    reached.forEach(
        (id, hits) -> {
          Map<Long, Set<Boolean>> kinds = new HashMap<>();
          for (Hit h : hits) {
            kinds.computeIfAbsent(h.lineId, k -> new HashSet<>()).add(h.stock);
          }
          kinds.forEach(
              (lineId, k) -> {
                Resolved r = out.get(lineId);
                if (inherited.contains(id)) {
                  r.extra.add(id);
                }
                if (k.size() > 1) {
                  return; // и с, и без суфикса в един ред — целият оборот
                }
                if (k.contains(Boolean.TRUE)) {
                  r.withStock.add(id);
                } else if (stocked.contains(id)) {
                  r.withoutStock.add(id);
                }
              });
        });
    return Collections.unmodifiableMap(out);
  }
}
