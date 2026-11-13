package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Products (MF.P.*) ОТ ПРОДУКТИТЕ НА ПЕРИОДА, МЕРНИТЕ ЕДИНИЦИ И КОНВЕРСИИТЕ — чиста функция.
 *
 * <p><b>⚠️ КОД ПО КН (MF.P.6) — ПРАВИЛОТО Е ТУК, НЕ ВЪВ WRITER-А.</b> Услуга → {@code 00000000}
 * (текстът над таблицата NC8_TARIC). Стока → {@code Product.customsCodeNomenclature}, без
 * интервалите и точките („0101 21 00“ на демо данните на Axelor), и само ако остане 8-цифрен код,
 * който го има в NC8_TARIC. Липсващ, глава/позиция (2–6 цифри), TARIC (10 цифри) или непознат код →
 * {@code 0} с предупреждение: книгата казва „в случаите, в които кодът не съществува в
 * номенклатурата — 0“. {@code 00000000} върху стока също е {@code 0} — това е кодът на услугите.
 *
 * <p><b>⚠️ UOMBase/UOMStandard/коефициент (MF.P.9–11).</b> UOMStandard е {@code Unit.bgUomCode}.
 * Няколко наши единици делят един код (H87 е „Брой“ и осемте кутии; E49 — половин ден, ден и
 * седмица) и само една от тях Е стандартната единица — КАНОНИЧНАТА. За нея UOMBase е самият код и
 * коефициентът е 1: така „UOMBase = UOMStandard ⇒ 1“ е буквално вярно, а не се спъва в „Брой“ ≠
 * „H87“. За останалите UOMBase е името на нашата единица, а коефициентът идва от {@code
 * UnitConversion} и не може да е 1.
 *
 * <p><b>Каноничната единица</b> на код е единствената, която е сама с този код, или — когато са
 * няколко — единствената, КЪМ която друга единица от кода се превръща пряко и която сама не се
 * превръща към единица от кода (кутиите → „Брой“, седмицата → „Работен ден“, годината → „Работен
 * месец“). Ако такава няма или са повече от една, коефициентът е неизвестен и находката е грешка.
 *
 * <p><b>⚠️ ПОСОКАТА НА КОЕФИЦИЕНТА — ДА СЕ ПОТВЪРДИ.</b> Книгата (MF.P.11, колона M): „коефициентът
 * на преобразуване на стандартната мерна единица в базовата единица“; схемата: „Conversion factor
 * of the UOM to UOMBase“. Тук тя е буквална: колко базови единици прави една стандартна. Кутия от
 * 12 броя спрямо H87 → 1/12, половин работен ден спрямо E49 → 2. QA на НАП няма пример с коефициент
 * ≠ 1, тоест обратното четене (12) не е изключено — затова посоката е в ЕДИН метод ({@link
 * #factor}).
 */
public final class BgSaftProductsAssembler {

  /** MF.P.3. */
  public static final String GOODS = "01";

  public static final String SERVICES = "02";

  /** MF.P.6 — обща категория за услугите (текстът над таблицата NC8_TARIC). */
  public static final String SERVICES_COMMODITY_CODE = "00000000";

  /** MF.P.6 — стока, чийто код по КН не съществува в номенклатурата. */
  public static final String NO_COMMODITY_CODE = "0";

  /** Знаците след запетаята на коефициент, който не е цяло число (1/12, 1/3). */
  static final int FACTOR_SCALE = 10;

  /** Дължините по схемата: SAFmiddle2textType, SAFlongtextType, SAFmiddle1textType. */
  static final int MAX_CODE = 70;

  static final int MAX_DESCRIPTION = 256;
  static final int MAX_UOM_BASE = 35;

  private static final Pattern NC8 = Pattern.compile("[0-9]{8}");
  private static final Pattern NC8_SEPARATORS = Pattern.compile("[\\s\\u00A0.]");

  private BgSaftProductsAssembler() {}

  /** Продукт от периода. */
  /** Продукт на ред от фактура — посоката и сметката на реда решават дали е запас (G2c). */
  public static final class Use {
    private final ProductRow product;
    private final boolean sales;
    private final Long accountId;

    public Use(ProductRow product, boolean sales, Long accountId) {
      this.product = product;
      this.sales = sales;
      this.accountId = accountId;
    }
  }

  /**
   * Продуктите на файла — по правилото на реда на фактурата: продаденият продукт влиза, а купеният
   * — само по сметка от група 30 на НАП (запаси). Покупка на ДМА, директно разходван артикул или
   * текущ разход НЕ е запас (Прил. 3 т. IV.6): редът ѝ носи ProductCode „0“ и в Products няма какво
   * да сочи (G2c). Стоките с наличност влизат винаги (MF.P.1).
   *
   * @param nraByAccount AccountID по НАП по id на наша сметка
   */
  public static List<ProductRow> select(
      Collection<Use> invoiced, Collection<ProductRow> stocked, Map<Long, String> nraByAccount) {
    Map<Long, ProductRow> products = new LinkedHashMap<>();
    for (Use use : invoiced) {
      String nra = use.accountId == null ? null : nraByAccount.get(use.accountId);
      if (use.sales || (nra != null && nra.startsWith(BgSaftInvoicesAssembler.STOCK_GROUP))) {
        products.putIfAbsent(use.product.id, use.product);
      }
    }
    for (ProductRow product : stocked) {
      products.putIfAbsent(product.id, product);
    }
    return new ArrayList<>(products.values());
  }

  public static final class ProductRow {
    private final Long id;
    private final String code;
    private final String name;
    private final String typeSelect;
    private final Long unitId;
    private final String customsCode;

    /**
     * @param typeSelect {@code Product.productTypeSelect}: {@code storable} или {@code service}
     * @param unitId {@code Product.unit} — основната единица
     * @param customsCode {@code Product.customsCodeNomenclature.code}, суров
     */
    public ProductRow(
        Long id, String code, String name, String typeSelect, Long unitId, String customsCode) {
      this.id = id;
      this.code = code;
      this.name = name;
      this.typeSelect = typeSelect;
      this.unitId = unitId;
      this.customsCode = customsCode;
    }

    public Long getId() {
      return id;
    }
  }

  /** Мерна единица — всички, не само ползваните: каноничната се търси сред целия код. */
  public static final class UnitRow {
    private final Long id;
    private final String name;
    private final String uomCode;

    /**
     * @param uomCode {@code Unit.bgUomCode}
     */
    public UnitRow(Long id, String name, String uomCode) {
      this.id = id;
      this.name = name;
      this.uomCode = uomCode;
    }
  }

  /** {@code UnitConversion} с коефициент: 1 начална = {@code coef} крайни. */
  public static final class ConversionRow {
    private final Long startUnitId;
    private final Long endUnitId;
    private final BigDecimal coef;

    public ConversionRow(Long startUnitId, Long endUnitId, BigDecimal coef) {
      this.startUnitId = startUnitId;
      this.endUnitId = endUnitId;
      this.coef = coef;
    }
  }

  /** Продуктите и находките по пътя. */
  public static final class Result {
    private final List<SaftProduct> products;
    private final List<BgDeclarationFileFinding> findings;

    private Result(List<SaftProduct> products, List<BgDeclarationFileFinding> findings) {
      this.products = Collections.unmodifiableList(products);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftProduct> getProducts() {
      return products;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param products продуктите на периода; повторен id се взема веднъж
   * @param units всички мерни единици
   * @param conversions конверсиите с коефициент (без тези с формула)
   * @param nc8Codes NC8_TARIC ({@link BgNraNomenclatures#nc8Codes()})
   */
  public static Result assemble(
      Collection<ProductRow> products,
      Collection<UnitRow> units,
      Collection<ConversionRow> conversions,
      Set<String> nc8Codes) {
    Units graph = new Units(units, conversions);
    Map<Long, ProductRow> distinct = new LinkedHashMap<>();
    for (ProductRow product : products) {
      distinct.putIfAbsent(product.id, product);
    }
    List<ProductRow> sorted = new ArrayList<>(distinct.values());
    sorted.sort(
        Comparator.comparing((ProductRow p) -> trim(p.code))
            .thenComparing(p -> p.id, Comparator.nullsLast(Comparator.naturalOrder())));

    Findings f = new Findings();
    List<SaftProduct> result = new ArrayList<>();
    for (ProductRow product : sorted) {
      String code = trim(product.code);
      String name = trim(product.name);
      String label = code.isEmpty() ? "#" + product.id : code;
      f.current = BgSaftRef.of(label, BgSaftRef.PRODUCT, product.id);
      if (code.isEmpty() || name.isEmpty()) {
        f.incomplete.add(f.ref(label));
      }
      tooLong(f, label, "ProductCode", code, MAX_CODE);
      tooLong(f, label, "Description", name, MAX_DESCRIPTION);

      String goodsServices = goodsServicesId(product.typeSelect);
      if (goodsServices == null) {
        f.typeUnknown.add(f.ref(label));
      }
      SaftProduct p =
          new SaftProduct()
              .productCode(code)
              .goodsServicesId(goodsServices)
              .description(name)
              .productCommodityCode(
                  commodityCode(product, SERVICES.equals(goodsServices), nc8Codes, label, f));
      uom(p, product, graph, label, f);
      tooLong(f, label, "UOMBase", p.getUomBase(), MAX_UOM_BASE);
      result.add(p);
    }
    return new Result(result, f.toList(result.isEmpty()));
  }

  /** MF.P.3 от {@code productTypeSelect}; {@code null}, когато видът е непознат. */
  static String goodsServicesId(String typeSelect) {
    String type = trim(typeSelect);
    if (ProductRepository.PRODUCT_TYPE_SERVICE.equals(type)) {
      return SERVICES;
    }
    if (ProductRepository.PRODUCT_TYPE_STORABLE.equals(type)) {
      return GOODS;
    }
    return null;
  }

  /** Кодът по КН без разделителите: „0101 21 00“ → „01012100“. */
  static String normalizeNc8(String raw) {
    return NC8_SEPARATORS.matcher(trim(raw)).replaceAll("");
  }

  /**
   * MF.P.11 — колко базови единици прави ЕДНА стандартна; {@code standardInBase} е 1 стандартна,
   * изразена в базови. Посоката е да се потвърди — виж бележката на класа.
   */
  static BigDecimal factor(BigDecimal standardInBase) {
    BigDecimal v = standardInBase.setScale(FACTOR_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    return v.scale() < 0 ? v.setScale(0) : v;
  }

  // ─────────────── правилата ───────────────

  private static String commodityCode(
      ProductRow product, boolean service, Set<String> nc8Codes, String label, Findings f) {
    if (service) {
      return SERVICES_COMMODITY_CODE;
    }
    String raw = trim(product.customsCode);
    if (raw.isEmpty()) {
      f.nc8Missing.add(f.ref(label));
      return NO_COMMODITY_CODE;
    }
    String code = normalizeNc8(raw);
    String reason = null;
    if (!NC8.matcher(code).matches()) {
      reason = "не е 8 цифри";
    } else if (SERVICES_COMMODITY_CODE.equals(code)) {
      reason = "това е кодът на услугите";
    } else if (!nc8Codes.contains(code)) {
      reason = "няма го в NC8_TARIC";
    }
    if (reason != null) {
      f.nc8Invalid.add(f.ref(label + " („" + raw + "“ — " + reason + ")"));
      return NO_COMMODITY_CODE;
    }
    return code;
  }

  private static void uom(
      SaftProduct p, ProductRow product, Units graph, String label, Findings f) {
    UnitRow unit = product.unitId == null ? null : graph.byId.get(product.unitId);
    if (unit == null) {
      f.noUnit.add(f.ref(label));
      p.uomBase("").uomStandard("").uomToUomBaseConversionFactor(BigDecimal.ONE);
      return;
    }
    String code = trim(unit.uomCode);
    String unitName = trim(unit.name);
    if (code.isEmpty()) {
      f.noUomCode.add(f.ref(label + " („" + unitName + "“)"));
      p.uomBase(unitName).uomStandard("").uomToUomBaseConversionFactor(BigDecimal.ONE);
      return;
    }
    p.uomStandard(code);
    Long canonical = graph.canonical(code);
    if (unit.id.equals(canonical)) {
      p.uomBase(code).uomToUomBaseConversionFactor(BigDecimal.ONE);
      return;
    }
    p.uomBase(unitName).uomToUomBaseConversionFactor(BigDecimal.ONE);
    if (canonical == null) {
      f.noStandardUnit.add(f.ref(label + " („" + unitName + "“, " + code + ")"));
      return;
    }
    Path path = graph.path(canonical, unit.id);
    BigDecimal value = path == null ? null : factor(path.value);
    if (value == null || value.signum() == 0) {
      f.noConversion.add(
          f.ref(label + " („" + unitName + "“ → „" + trim(graph.byId.get(canonical).name) + "“)"));
      return;
    }
    if (value.compareTo(BigDecimal.ONE) == 0) {
      // Същата големина като стандартната — тя е, само под друго име.
      p.uomBase(code);
      return;
    }
    p.uomToUomBaseConversionFactor(value);
    if (path.hops > 1) {
      f.indirect.add(
          f.ref(
              label
                  + " („"
                  + unitName
                  + "“ = "
                  + value.toPlainString()
                  + " × „"
                  + trim(graph.byId.get(canonical).name)
                  + "“ през "
                  + path.via
                  + ")"));
    }
  }

  private static void tooLong(Findings f, String label, String element, String value, int max) {
    if (value != null && value.length() > max) {
      f.tooLong.add(f.ref(label + " " + element + " " + value.length() + " > " + max));
    }
  }

  // ─────────────── мерните единици като граф ───────────────

  private static final class Path {
    /** 1 начална = value крайни. */
    private final BigDecimal value;

    private final int hops;
    private final String via;

    private Path(BigDecimal value, int hops, String via) {
      this.value = value;
      this.hops = hops;
      this.via = via;
    }
  }

  private static final class Units {
    private final Map<Long, UnitRow> byId = new HashMap<>();
    private final Map<String, List<Long>> byCode = new HashMap<>();

    /** id → съсед → 1 id = стойност съседи; TreeMap за детерминиран път. */
    private final Map<Long, TreeMap<Long, BigDecimal>> edges = new HashMap<>();

    private final Collection<ConversionRow> conversions;
    private final Map<String, Long> canonical = new HashMap<>();

    private Units(Collection<UnitRow> units, Collection<ConversionRow> conversions) {
      for (UnitRow unit : units) {
        byId.put(unit.id, unit);
        String code = trim(unit.uomCode);
        if (!code.isEmpty()) {
          byCode.computeIfAbsent(code, c -> new ArrayList<>()).add(unit.id);
        }
      }
      this.conversions = new ArrayList<>();
      for (ConversionRow c : conversions) {
        if (c.startUnitId == null
            || c.endUnitId == null
            || c.startUnitId.equals(c.endUnitId)
            || c.coef == null
            || c.coef.signum() <= 0) {
          continue;
        }
        this.conversions.add(c);
        edges.computeIfAbsent(c.startUnitId, k -> new TreeMap<>()).put(c.endUnitId, c.coef);
        edges
            .computeIfAbsent(c.endUnitId, k -> new TreeMap<>())
            .putIfAbsent(c.startUnitId, BigDecimal.ONE.divide(c.coef, MathContext.DECIMAL128));
      }
    }

    /** Виж бележката на класа; {@code null}, когато не е еднозначна. */
    Long canonical(String code) {
      return canonical.computeIfAbsent(code, this::findCanonical);
    }

    private Long findCanonical(String code) {
      List<Long> group = byCode.getOrDefault(code, Collections.emptyList());
      if (group.size() == 1) {
        return group.get(0);
      }
      Set<Long> members = new HashSet<>(group);
      Set<Long> reached = new TreeSet<>();
      Set<Long> leaving = new HashSet<>();
      for (ConversionRow c : conversions) {
        if (members.contains(c.startUnitId) && members.contains(c.endUnitId)) {
          reached.add(c.endUnitId);
          leaving.add(c.startUnitId);
        }
      }
      reached.removeAll(leaving);
      return reached.size() == 1 ? reached.iterator().next() : null;
    }

    /** Най-късият път по конверсиите; {@code null}, когато двете не са свързани. */
    Path path(Long from, Long to) {
      Map<Long, BigDecimal> value = new HashMap<>();
      Map<Long, Long> previous = new HashMap<>();
      Deque<Long> queue = new ArrayDeque<>();
      value.put(from, BigDecimal.ONE);
      queue.add(from);
      while (!queue.isEmpty()) {
        Long current = queue.poll();
        if (current.equals(to)) {
          break;
        }
        for (Map.Entry<Long, BigDecimal> e :
            edges.getOrDefault(current, new TreeMap<>()).entrySet()) {
          if (!value.containsKey(e.getKey())) {
            value.put(
                e.getKey(), value.get(current).multiply(e.getValue(), MathContext.DECIMAL128));
            previous.put(e.getKey(), current);
            queue.add(e.getKey());
          }
        }
      }
      if (!value.containsKey(to)) {
        return null;
      }
      List<String> via = new ArrayList<>();
      int hops = 0;
      for (Long step = previous.get(to); step != null; step = previous.get(step)) {
        hops++;
        if (!step.equals(from)) {
          via.add(0, "„" + trim(byId.containsKey(step) ? byId.get(step).name : "#" + step) + "“");
        }
      }
      return new Path(value.get(to), hops, String.join(", ", via));
    }
  }

  // ─────────────── находките ───────────────

  private static final class Findings {
    private final List<BgSaftRef> incomplete = new ArrayList<>();
    private final List<BgSaftRef> tooLong = new ArrayList<>();
    private final List<BgSaftRef> typeUnknown = new ArrayList<>();
    private final List<BgSaftRef> nc8Missing = new ArrayList<>();
    private final List<BgSaftRef> nc8Invalid = new ArrayList<>();
    private final List<BgSaftRef> noUnit = new ArrayList<>();
    private final List<BgSaftRef> noUomCode = new ArrayList<>();
    private final List<BgSaftRef> noStandardUnit = new ArrayList<>();
    private final List<BgSaftRef> noConversion = new ArrayList<>();
    private final List<BgSaftRef> indirect = new ArrayList<>();

    private List<BgDeclarationFileFinding> toList(boolean empty) {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      if (empty) {
        findings.add(
            BgDeclarationFileFinding.error(
                "SAFT_P_EMPTY",
                "Нито фактуриран продукт в периода, нито стока с наличност — схемата иска поне"
                    + " един (Products/Product)."));
      }
      error(
          findings,
          "SAFT_P_INCOMPLETE",
          "Продукти без код или наименование (MF.P.2/5)",
          incomplete);
      error(findings, "SAFT_P_TOO_LONG", "Стойности над дължината по схемата", tooLong);
      warning(
          findings,
          "SAFT_P_TYPE_UNKNOWN",
          "Продукти без вид стока/услуга — GoodsServicesID не се подава, а кодът по КН се"
              + " търси като за стока (MF.P.3)",
          typeUnknown);
      warning(
          findings,
          "SAFT_P_NC8_MISSING",
          "Стоки без код по КН — подадени с „0“ (MF.P.6)",
          nc8Missing);
      warning(
          findings,
          "SAFT_P_NC8_INVALID",
          "Стоки с код по КН, който не е 8-цифрен код от NC8_TARIC — подадени с „0“ (MF.P.6)",
          nc8Invalid);
      error(findings, "SAFT_P_NO_UNIT", "Продукти без мерна единица (MF.P.9/10)", noUnit);
      warning(
          findings,
          "SAFT_P_UOM_NO_CODE",
          "Мерни единици без код по НАП — UOMStandard е празен (MF.P.10)",
          noUomCode);
      error(
          findings,
          "SAFT_P_UOM_NO_STANDARD_UNIT",
          "Няколко единици делят кода по НАП, а нито една не е явно стандартната — коефициентът"
              + " е неизвестен (MF.P.11)",
          noStandardUnit);
      error(
          findings,
          "SAFT_P_UOM_NO_CONVERSION",
          "Няма конверсия между единицата на продукта и стандартната за кода — коефициентът е"
              + " неизвестен, а различни единици не може да са с коефициент 1 (MF.P.11)",
          noConversion);
      warning(
          findings,
          "SAFT_P_UOM_INDIRECT",
          "Коефициентът е сметнат през друга единица, а не по пряка конверсия — да се потвърди"
              + " (MF.P.11)",
          indirect);
      return findings;
    }

    /** Продуктът, който се сглобява — към него сочат находките. */
    private BgSaftRef current;

    private BgSaftRef ref(String text) {
      return current == null ? BgSaftRef.text(text) : current.withLabel(text);
    }

    private static void error(
        List<BgDeclarationFileFinding> findings, String code, String text, List<BgSaftRef> items) {
      BgSaftRef.error(findings, code, text + ": ", items);
    }

    private static void warning(
        List<BgDeclarationFileFinding> findings, String code, String text, List<BgSaftRef> items) {
      BgSaftRef.warning(findings, code, text + ": ", items);
    }
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
