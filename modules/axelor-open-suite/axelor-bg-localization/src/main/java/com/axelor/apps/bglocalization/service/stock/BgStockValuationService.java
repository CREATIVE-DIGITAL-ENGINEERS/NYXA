package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.bglocalization.db.BgStockCostLayer;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.BgStockMoveLineValuation;
import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.service.accounting.BgAutoPostingUserService;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementClassifier.Role;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.TrackingNumber;
import com.axelor.auth.AuthUtils;
import com.axelor.db.EntityHelper;
import com.axelor.db.JPA;
import com.axelor.db.mapper.Mapper;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Оценката на една задача „чака оценка“ (ИЗП): кодът, слоят, сметките и статията на едно
 * реализирано движение. Вика се от {@link BgStockValuationProcessor} в СОБСТВЕНА транзакция, след
 * commit на реализирането.
 *
 * <p>Всичко или нищо за движението: отказ на кой да е ред хвърля {@link Refusal}, процесорът връща
 * транзакцията назад (слоевете остават непипнати) и записва задачата като ERROR с причината.
 *
 * <p>Числата са в {@link BgStockCostEngine} („стойност, не цена“), сметките — в {@link
 * BgStockPostingRules}, кодът — в {@link BgStockMovementClassifier}. Тук е само четенето и записът.
 */
public class BgStockValuationService {

  /** Бизнес отказ: задачата става ERROR с това съобщение. */
  public static class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Refusal(String message) {
      super(message);
    }

    /** Отказ с техническа причина — стекът ѝ отива в лога, съобщението — върху задачата. */
    public Refusal(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Полетата на StockConfig по роля — по име, защото production не се вижда оттук. */
  static final Map<String, Role> ROLE_FIELDS;

  static {
    Map<String, Role> m = new LinkedHashMap<>();
    m.put("customerVirtualStockLocation", Role.CUSTOMER);
    m.put("supplierVirtualStockLocation", Role.SUPPLIER);
    m.put("inventoryVirtualStockLocation", Role.INVENTORY);
    m.put("productionVirtualStockLocation", Role.PRODUCTION);
    m.put("wasteStockLocation", Role.WASTE);
    m.put("virtualOutsourcingStockLocation", Role.OUTSOURCING);
    ROLE_FIELDS = m;
  }

  /** Произходът на слоя: начален (заварени наличности) или постъпление. */
  public static final String ORIGIN_OPENING = "OPENING";

  public static final String ORIGIN_IN = "IN";

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveRepository moveRepository;
  protected final UnitConversionService unitConversionService;
  protected final MoveValidateService moveValidateService;

  @Inject
  public BgStockValuationService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveRepository moveRepository,
      UnitConversionService unitConversionService,
      MoveValidateService moveValidateService) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveRepository = moveRepository;
    this.unitConversionService = unitConversionService;
    this.moveValidateService = moveValidateService;
  }

  /** Една оценена линия, преди записа: редът, слоят, сумата и сметките. */
  static final class Planned {
    StockMoveLine line;
    Product product;
    StockLocation warehouse;
    String code;
    String inventoryType;
    String direction;
    BigDecimal qty;
    BigDecimal amount;
    String method;
    BgStockPostingRules.Posting posting;
    Partner partner;
  }

  /**
   * Оценява движението на задачата: слоевете се променят, статията се съставя, редовете за оценка
   * се записват. Хвърля {@link Refusal} при бизнес отказ.
   */
  public Move value(BgStockValuationTask task) throws AxelorException {
    StockMove move = task.getStockMove();
    Company company = move.getCompany();
    LocalDate date = task.getDocumentDate();
    BgStockCostingPolicy policy = activePolicy(company, date);
    if (policy == null) {
      throw new Refusal(
          String.format(
              "Няма активна политика за изписване на „%s“ за финансовата година на %s.",
              company.getName(), date));
    }
    if (!BgStockCostingRules.POSTING_CURRENT.equals(policy.getPostingModeSelect())) {
      throw new Refusal(
          "Обобщеното осчетоводяване в края на месеца още не е включено. Изберете текущо"
              + " осчетоводяване в политиката за следващата година или изчакайте включването му.");
    }

    Map<String, Pile> rows = new LinkedHashMap<>();
    List<Planned> planned = new ArrayList<>();
    StockConfig config = stockConfig(company);
    for (StockMoveLine line : nonNull(move.getStockMoveLineList())) {
      Planned p = plan(move, line, policy, config, rows, date);
      if (p != null) {
        planned.add(p);
      }
    }

    Move accountingMove = post(task, move, company, date, planned);
    if (accountingMove != null && autoValidation(policy)) {
      validate(accountingMove);
    }

    for (Pile pile : rows.values()) {
      for (int i = 0; i < pile.layers.size(); i++) {
        BgStockCostEngine.Layer layer = pile.layers.get(i);
        BgStockCostLayer row = pile.rows.get(i);
        if (row.getCompany() == null) {
          row.setCompany(company);
        }
        row.setQty(layer.qty());
        row.setStockValue(layer.value());
        row.setUnitCost(layer.unitCost());
        JPA.save(row);
      }
    }
    for (Planned p : planned) {
      BgStockMoveLineValuation v = new BgStockMoveLineValuation();
      v.setStockMoveLine(p.line);
      v.setStockMove(move);
      v.setTask(task);
      v.setCompany(company);
      v.setProduct(p.product);
      v.setWarehouse(p.warehouse);
      v.setMovementCode(p.code);
      v.setInventoryType(p.inventoryType);
      v.setDirectionSelect(p.direction);
      v.setCostingMethodSelect(p.method);
      v.setQty(p.qty);
      v.setAmount(p.amount);
      v.setMove(accountingMove);
      JPA.save(v);
    }
    return accountingMove;
  }

  /** Един ред: {@code null}, когато не е стоково-материален запас или няма количество. */
  protected Planned plan(
      StockMove move,
      StockMoveLine line,
      BgStockCostingPolicy policy,
      StockConfig config,
      Map<String, Pile> rows,
      LocalDate date)
      throws AxelorException {
    Product product = line.getProduct();
    if (product == null
        || !ProductRepository.PRODUCT_TYPE_STORABLE.equals(product.getProductTypeSelect())
        || line.getRealQty().signum() <= 0) {
      return null;
    }
    String inventoryType = BgSaftInventoryTypes.typeOf(product);
    if (inventoryType == null) {
      return null;
    }
    String method = BgStockCostingRules.methodFor(policy, product.getProductFamily());
    if (!ENABLED_METHODS.contains(method)) {
      throw new Refusal(
          String.format(
              "Методът „%s“ за „%s“ още не е включен — засега средна претеглена след всяко"
                  + " постъпление, FIFO и LIFO. Изписването се обработва отново, щом методът бъде"
                  + " включен.",
              method, product.getCode()));
    }

    TrackingNumber lot = line.getTrackingNumber();
    if (BgStockCostingRules.SPECIFIC.equals(method)) {
      // Конкретно определена стойност (СС 2 т. 9.1) различава ПАРТИДИТЕ: без партида на реда
      // няма какво да се определи.
      if (lot == null) {
        throw new Refusal(
            String.format(
                "„%s“ се изписва по конкретно определена стойност, а редът на %s няма партида или"
                    + " сериен номер. Посочете партидата на реда и обработете изписването отново.",
                product.getCode(), move.getStockMoveSeq()));
      }
    }

    StockLocation from = line.getFromStockLocation();
    StockLocation to = line.getToStockLocation();
    Direction direction = BgStockMovementCodes.directionOf(from, to);
    if (direction == null) {
      return null;
    }
    Role counterpart =
        direction == Direction.IN
            ? roleOf(from, config)
            : direction == Direction.OUT ? roleOf(to, config) : null;
    String code =
        BgStockMovementClassifier.classify(
            move.getBgSaftMovementCode(),
            move.getStockCorrectionReason() == null
                ? null
                : move.getStockCorrectionReason().getBgStockMovementCode(),
            direction,
            counterpart);

    BigDecimal qty = toProductUnit(line, line.getRealQty());
    Planned p = new Planned();
    p.line = line;
    p.product = product;
    p.code = code;
    p.inventoryType = inventoryType;
    p.qty = qty;
    p.method = method;
    boolean scopeByWarehouse =
        BgStockCostingRules.SCOPE_WAREHOUSE.equals(policy.getAverageScopeSelect());
    StockLocation fromWarehouse = scopeByWarehouse ? BgSaftWarehouses.of(from) : null;
    StockLocation toWarehouse = scopeByWarehouse ? BgSaftWarehouses.of(to) : null;

    if (code == null) {
      throw new Refusal(
          String.format(
              "Кодът по „Stock movement“ на %s не се определя. Посочете ръчния код в движението"
                  + " (раздел SAF-T).",
              move.getStockMoveSeq()));
    }

    Company company = move.getCompany();
    if ("80".equals(code) || direction == Direction.TRANSFER) {
      // Вътрешен трансфер — и към подизпълнителя (S1: стоките остават на дружеството, а
      // локацията му е склад за SAF-T по флага). Без статия; стойността минава от слоя на единия
      // склад в слоя на другия по себестойност.
      if (scopeByWarehouse && (fromWarehouse == null || toWarehouse == null)) {
        throw new Refusal(
            String.format(
                "Трансферът %s минава през локация, която не е склад за SAF-T (виртуална без флаг"
                    + " „Склад за SAF-T“). Вдигнете флага на локацията или посочете друг код.",
                move.getStockMoveSeq()));
      }
      p.amount =
          transfer(
              company, product, method, fromWarehouse, toWarehouse, qty, rows, date, line, lot);
      p.warehouse = fromWarehouse;
      p.direction = "TRANSFER";
      p.posting =
          BgStockPostingRules.plan(code, Direction.TRANSFER, inventoryType, false, null, null);
      return p;
    }
    switch (direction) {
      case IN:
        {
          Pile pile = pile(company, product, toWarehouse, rows);
          p.amount = incomingValue(move, line, code, qty, pile.average(method));
          pile.receive(method, qty, p.amount, date, line, company, product, toWarehouse, lot);
          p.warehouse = toWarehouse;
          p.direction = "IN";
          break;
        }
      case OUT:
        {
          BgStockCostEngine.Issue issue =
              pile(company, product, fromWarehouse, rows).issue(method, qty, lot);
          if (issue.isShort()) {
            throw shortage(product, fromWarehouse, issue.shortage());
          }
          p.amount = issue.amount();
          p.warehouse = fromWarehouse;
          p.direction = "OUT";
          break;
        }
      default:
        return null;
    }

    boolean withPartner =
        "120".equals(code)
            && move.getStockCorrectionReason() != null
            && Boolean.TRUE.equals(move.getStockCorrectionReason().getBgRequiresPartner())
            && move.getPartner() != null;
    p.partner = withPartner ? move.getPartner() : null;
    p.posting =
        BgStockPostingRules.plan(
            code,
            direction,
            inventoryType,
            withPartner,
            stockAccount(company, product),
            policy.getAccountRuleList());
    if (p.posting.refusal != null) {
      throw new Refusal(product.getCode() + ": " + p.posting.refusal);
    }
    return p;
  }

  /**
   * Трансфер: стойността минава от слоя на изходния склад в слоя на целевия. В същия обхват (един
   * склад или средна за дружеството) слоят не се мени — стойността е само за SAF-T BookValue.
   */
  protected BigDecimal transfer(
      Company company,
      Product product,
      String method,
      StockLocation fromWarehouse,
      StockLocation toWarehouse,
      BigDecimal qty,
      Map<String, Pile> rows,
      LocalDate date,
      StockMoveLine line,
      TrackingNumber lot) {
    Pile source = pile(company, product, fromWarehouse, rows);
    if (Objects.equals(id(fromWarehouse), id(toWarehouse))) {
      // Същият обхват: слоевете не се местят — стойността е само за SAF-T BookValue, сметната
      // върху копие по метода.
      BgStockCostEngine.Issue dry = source.copy().issue(method, qty, lot);
      if (dry.isShort()) {
        throw shortage(product, fromWarehouse, dry.shortage());
      }
      return dry.amount();
    }
    BgStockCostEngine.Issue issue = source.issue(method, qty, lot);
    if (issue.isShort()) {
      throw shortage(product, fromWarehouse, issue.shortage());
    }
    // Слоевете минават с партидата на реда — и при конкретната стойност, и за да е известна цената
    // на партидата при смяна към нея (правило 4). FIFO/LIFO: всеки взет слой става слой в целевия
    // склад със своята стойност — редът на постъпване се пази. При средната — един вход.
    Pile target = pile(company, product, toWarehouse, rows);
    for (BgStockCostEngine.Layer taken : issue.taken()) {
      target.receive(
          method, taken.qty(), taken.value(), date, line, company, product, toWarehouse, lot);
    }
    return issue.amount();
  }

  protected Refusal shortage(Product product, StockLocation warehouse, BigDecimal missing) {
    return new Refusal(
        String.format(
            "Недостатъчна наличност в слоя на „%s“%s: липсват %s. Проверете дали постъплението е"
                + " оценено и началните наличности са заредени.",
            product.getCode(),
            warehouse == null ? "" : " (склад „" + warehouse.getName() + "“)",
            missing.stripTrailingZeros().toPlainString()));
  }

  /**
   * Стойността на постъплението.
   *
   * <ul>
   *   <li>10 покупка, 20 продукция, 60 отстъпки, 180 други — цената на реда (модел A: при приемане
   *       — цената от поръчката; фактурата я коригира в „стойностните корекции“);
   *   <li>40 връщане от клиент — първоначалната себестойност от оценката на изписването, което се
   *       връща; без нея — отказ, а не цената на продажбата;
   *   <li>110 положителна корекция — текущата средна на слоя (празен слой — цената на реда).
   * </ul>
   */
  protected BigDecimal incomingValue(
      StockMove move,
      StockMoveLine line,
      String code,
      BigDecimal qty,
      BgStockCostEngine.Layer layer)
      throws AxelorException {
    // layer: средната на целия обхват (при FIFO/LIFO — слетите слоеве), само за 110.
    if ("40".equals(code)) {
      BigDecimal[] original = originalIssue(move, line.getProduct());
      if (original == null) {
        throw new Refusal(
            String.format(
                "Връщането %s няма оценено първоначално изписване на „%s“ (полето „Първоначално"
                    + " движение“) — себестойността му не се определя.",
                move.getStockMoveSeq(), line.getProduct().getCode()));
      }
      return BgStockCostEngine.share(original[1], qty.min(original[0]), original[0]);
    }
    if ("110".equals(code) && layer.qty().signum() > 0) {
      return BgStockCostEngine.share(layer.value(), qty, layer.qty());
    }
    BigDecimal unitPrice = toProductUnitPrice(line, line.getCompanyUnitPriceUntaxed());
    return BgStockCostEngine.valueOf(qty, unitPrice);
  }

  /** Количество и сума на изписването, което връщането връща (за продукта), или {@code null}. */
  protected BigDecimal[] originalIssue(StockMove move, Product product) {
    StockMove origin = move.getReversionOriginStockMove();
    if (origin == null) {
      return null;
    }
    List<BgStockMoveLineValuation> rows =
        JPA.all(BgStockMoveLineValuation.class)
            .filter(
                "self.stockMove = ?1 AND self.product = ?2 AND self.directionSelect = 'OUT'",
                origin,
                product)
            .fetch();
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal amount = BigDecimal.ZERO;
    for (BgStockMoveLineValuation v : rows) {
      qty = qty.add(v.getQty());
      amount = amount.add(v.getAmount());
    }
    return qty.signum() == 0 ? null : new BigDecimal[] {qty, amount};
  }

  /** Статията за изписване — една на движение, само ако някой ред има статия. */
  protected Move post(
      BgStockValuationTask task,
      StockMove stockMove,
      Company company,
      LocalDate date,
      List<Planned> planned)
      throws AxelorException {
    List<Planned> posted = new ArrayList<>();
    for (Planned p : planned) {
      if (!p.posting.none && p.amount.signum() != 0) {
        posted.add(p);
      }
    }
    if (posted.isEmpty()) {
      return null;
    }
    AccountConfig accountConfig = company.getAccountConfig();
    Journal journal = accountConfig == null ? null : accountConfig.getBgStockIssueJournal();
    if (journal == null) {
      throw new Refusal(
          "Няма журнал за изписване на МЗ: попълнете „Журнал за изписване на МЗ (BG)“ в"
              + " счетоводната конфигурация на дружеството.");
    }
    String origin = stockMove.getStockMoveSeq();
    String description = "Изписване на МЗ — " + origin;
    Move move =
        moveCreateService.createMove(
            journal,
            company,
            company.getCurrency(),
            null,
            date,
            date,
            null,
            null,
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            0,
            origin,
            description,
            null);
    // Връзката на AOS „статията е от това складово движение“ (supplychain Move.stockMove). По нея
    // G13 в nyxa-security пуска nyxa-auto-posting да осчетоводи само изписването си.
    move.setStockMove(stockMove);
    int counter = 1;
    for (Planned p : posted) {
      String text = description + " — " + p.product.getCode() + " (код " + p.code + ")";
      Partner debitPartner = p.partner != null && p.posting.debit != null ? p.partner : null;
      // ⚠️ ДОБАВЯ се към списъка на статията, не го подменя — createMove вече я е записала
      // (виж BgArt79ProtocolService).
      move.addMoveLineListItem(
          moveLineCreateService.createMoveLine(
              move, debitPartner, p.posting.debit, p.amount, true, date, counter++, origin, text));
      move.addMoveLineListItem(
          moveLineCreateService.createMoveLine(
              move, null, p.posting.credit, p.amount, false, date, counter++, origin, text));
    }
    return moveRepository.save(move);
  }

  /**
   * Решение от 08.10.2026: по подразбиране статията се валидира автоматично — черновите не влизат в
   * салдата и в SAF-T. Празно (заварена политика отпреди полето) значи „автоматично“.
   */
  static boolean autoValidation(BgStockCostingPolicy policy) {
    return !BgStockCostingRules.VALIDATION_MANUAL.equals(policy.getMoveValidationSelect());
  }

  /**
   * Валидира статията. Отказът на Axelor става {@link Refusal} с причината: задачата остава в
   * „Неосчетоводени изписвания“, а транзакцията на оценката се връща назад цялата.
   */
  protected void validate(Move move) {
    // Потребителят за автоматично осчетоводяване (09.10.2026): празен, неактивен, без права или
    // задачата не е под неговата идентичност → отказ с причина; никога тих преход към друг.
    Company company = move.getCompany();
    AccountConfig config = company == null ? null : company.getAccountConfig();
    String refusal =
        BgAutoPostingUserService.refusal(
            company, config == null ? null : config.getBgAutoPostingUser(), AuthUtils.getUser());
    if (refusal != null) {
      throw new Refusal(refusal);
    }
    try {
      moveValidateService.accounting(move);
    } catch (Exception e) {
      throw new Refusal(
          "Статията за изписване не се валидира: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          e);
    }
  }

  /** Активната политика, чиято финансова година съдържа датата. */
  protected BgStockCostingPolicy activePolicy(Company company, LocalDate date) {
    return JPA.all(BgStockCostingPolicy.class)
        .filter(
            "self.company = ?1 AND self.statusSelect = ?2 AND self.year.typeSelect = ?3"
                + " AND self.year.fromDate <= ?4 AND self.year.toDate >= ?4",
            company,
            BgStockCostingPolicyRepository.STATUS_ACTIVE,
            YearRepository.TYPE_FISCAL,
            date)
        .fetchOne();
  }

  protected StockConfig stockConfig(Company company) {
    return JPA.all(StockConfig.class).filter("self.company = ?1", company).fetchOne();
  }

  /** Ролята на локацията (или на неин предшественик) в StockConfig. */
  static Role roleOf(StockLocation location, StockConfig config) {
    if (location == null || config == null) {
      return null;
    }
    Mapper mapper = Mapper.of(EntityHelper.getEntityClass(config));
    for (StockLocation l = location; l != null; l = l.getParentStockLocation()) {
      for (Map.Entry<String, Role> e : ROLE_FIELDS.entrySet()) {
        if (mapper.getProperty(e.getKey()) == null) {
          continue;
        }
        Object configured = mapper.get(config, e.getKey());
        if (configured instanceof StockLocation
            && Objects.equals(((StockLocation) configured).getId(), l.getId())) {
          return e.getValue();
        }
      }
    }
    return Role.OTHER;
  }

  /** Методите, които двигателят вече оценява. */
  static final Set<String> ENABLED_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  BgStockCostingRules.WAP_PERPETUAL,
                  BgStockCostingRules.FIFO,
                  BgStockCostingRules.LIFO,
                  BgStockCostingRules.SPECIFIC)));

  /**
   * Слоевете на един обхват (дружество, продукт, склад) по реда на постъпване, заедно с редовете им
   * в базата, партидата и датата им. Средната държи един слой; FIFO/LIFO — по един на постъпление;
   * конкретната стойност работи само върху слоевете на своята партида.
   */
  static final class Pile {
    final List<BgStockCostEngine.Layer> layers = new ArrayList<>();
    final List<BgStockCostLayer> rows = new ArrayList<>();
    final List<TrackingNumber> lots = new ArrayList<>();
    final List<LocalDate> dates = new ArrayList<>();

    /** Копие само на числата, партидите и датите — за изчисление без промяна. */
    Pile copy() {
      Pile copy = new Pile();
      for (int i = 0; i < layers.size(); i++) {
        BgStockCostEngine.Layer l = layers.get(i);
        copy.layers.add(new BgStockCostEngine.Layer(l.qty(), l.value()));
        copy.rows.add(null);
        copy.lots.add(lots.get(i));
        copy.dates.add(dates.get(i));
      }
      return copy;
    }

    /** Средната на обхвата като един слой — без промяна на слоевете. */
    BgStockCostEngine.Layer average(String method) {
      return BgStockCostEngine.merge(layers);
    }

    /**
     * Средна: слоевете се сливат в първия (решение 5 — смяна FIFO/LIFO → средна от новата година е
     * един слой със същата стойност; останалите редове остават с нула). Слятият слой е без партида.
     */
    void normalizeForAverage() {
      if (layers.size() <= 1) {
        return;
      }
      BgStockCostEngine.Layer merged = BgStockCostEngine.merge(layers);
      for (int i = 0; i < layers.size(); i++) {
        layers.set(i, i == 0 ? merged : BgStockCostEngine.Layer.empty());
      }
      setLot(0, null);
    }

    void setLot(int i, TrackingNumber lot) {
      lots.set(i, lot);
      if (rows.get(i) != null) {
        rows.get(i).setTrackingNumber(lot);
      }
    }

    BgStockCostEngine.Issue issue(String method, BigDecimal qty, TrackingNumber lot) {
      if (BgStockCostingRules.SPECIFIC.equals(method)) {
        return issueLot(qty, lot);
      }
      if (BgStockCostingRules.WAP_PERPETUAL.equals(method)) {
        normalizeForAverage();
      }
      return BgStockCostEngine.issue(layers, qty, BgStockCostingRules.LIFO.equals(method));
    }

    /**
     * Конкретна стойност: само слоевете на партидата, вътре в нея — FIFO по датата на постъплението
     * (единиците в партидата са взаимозаменяеми). Не стигат ли — недостигът се отрязва от слоевете
     * БЕЗ партида по средната им (решение 6: партида без собствена цена); слоевете на чужди партиди
     * не се пипат.
     */
    BgStockCostEngine.Issue issueLot(BigDecimal qty, TrackingNumber lot) {
      BigDecimal own = BigDecimal.ZERO;
      for (int i : indicesOf(lot)) {
        own = own.add(layers.get(i).qty());
      }
      BigDecimal missing = qty.subtract(own);
      if (missing.signum() > 0) {
        BgStockCostEngine.Issue carved = carve(missing, lot);
        if (carved.isShort()) {
          return carved;
        }
      }
      List<BgStockCostEngine.Layer> sub = new ArrayList<>();
      for (int i : indicesOf(lot)) {
        sub.add(layers.get(i));
      }
      return BgStockCostEngine.issue(sub, qty, false);
    }

    /** Отрязва количество от слоевете без партида, по средната им, като слой на партидата. */
    BgStockCostEngine.Issue carve(BigDecimal qty, TrackingNumber lot) {
      List<Integer> free = indicesOf(null);
      List<BgStockCostEngine.Layer> sub = new ArrayList<>();
      for (int i : free) {
        sub.add(layers.get(i));
      }
      BgStockCostEngine.Layer average = BgStockCostEngine.merge(sub);
      if (average.qty().compareTo(qty) < 0) {
        return BgStockCostEngine.issue(sub, qty, false); // недостиг — нищо не се пипа
      }
      for (int k = 0; k < free.size(); k++) {
        layers.set(free.get(k), k == 0 ? average : BgStockCostEngine.Layer.empty());
      }
      BgStockCostEngine.Issue taken = BgStockCostEngine.issue(average, qty);
      int first = free.get(0);
      BgStockCostLayer row = new BgStockCostLayer();
      BgStockCostLayer source = rows.get(first);
      if (source != null) {
        row.setCompany(source.getCompany());
        row.setProduct(source.getProduct());
        row.setWarehouse(source.getWarehouse());
      }
      row.setOriginSelect(ORIGIN_OPENING);
      row.setLayerDate(dates.get(first));
      row.setTrackingNumber(lot);
      BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
      BgStockCostEngine.receive(layer, qty, taken.amount());
      layers.add(layer);
      rows.add(row);
      lots.add(lot);
      dates.add(dates.get(first));
      return taken;
    }

    /** Индексите на слоевете на партидата (null — без партида), по дата, после по ред. */
    List<Integer> indicesOf(TrackingNumber lot) {
      List<Integer> out = new ArrayList<>();
      for (int i = 0; i < layers.size(); i++) {
        if (sameLot(lots.get(i), lot)) {
          out.add(i);
        }
      }
      out.sort(
          (a, b) -> {
            LocalDate da = dates.get(a);
            LocalDate db = dates.get(b);
            if (da != null && db != null && !da.equals(db)) {
              return da.compareTo(db);
            }
            return Integer.compare(a, b);
          });
      return out;
    }

    static boolean sameLot(TrackingNumber a, TrackingNumber b) {
      if (a == null || b == null) {
        return a == b;
      }
      return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    void receive(
        String method,
        BigDecimal qty,
        BigDecimal value,
        LocalDate date,
        StockMoveLine line,
        Company company,
        Product product,
        StockLocation warehouse,
        TrackingNumber lot) {
      if (BgStockCostingRules.WAP_PERPETUAL.equals(method) && !layers.isEmpty()) {
        normalizeForAverage();
        BgStockCostEngine.receive(layers.get(0), qty, value);
        return;
      }
      BgStockCostLayer row = new BgStockCostLayer();
      row.setCompany(company);
      row.setProduct(product);
      row.setWarehouse(warehouse);
      row.setOriginSelect(ORIGIN_IN);
      row.setLayerDate(date);
      row.setStockMoveLine(line);
      // Слоят на средната е общ за всички партиди — без партида, иначе първата би „присвоила“
      // сборния слой.
      TrackingNumber tag = BgStockCostingRules.WAP_PERPETUAL.equals(method) ? null : lot;
      row.setTrackingNumber(tag);
      BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
      BgStockCostEngine.receive(layer, qty, value);
      layers.add(layer);
      rows.add(row);
      lots.add(tag);
      dates.add(date);
    }
  }

  /** Слоевете на обхвата — заредени веднъж на оценка, после се ползват от паметта. */
  protected Pile pile(
      Company company, Product product, StockLocation warehouse, Map<String, Pile> piles) {
    String key = product.getId() + "|" + id(warehouse);
    Pile pile = piles.get(key);
    if (pile != null) {
      return pile;
    }
    pile = new Pile();
    List<BgStockCostLayer> found =
        warehouse == null
            ? JPA.all(BgStockCostLayer.class)
                .filter(
                    "self.company = ?1 AND self.product = ?2 AND self.warehouse IS NULL"
                        + " AND self.qty > 0",
                    company,
                    product)
                .order("id")
                .fetch()
            : JPA.all(BgStockCostLayer.class)
                .filter(
                    "self.company = ?1 AND self.product = ?2 AND self.warehouse = ?3"
                        + " AND self.qty > 0",
                    company,
                    product,
                    warehouse)
                .order("id")
                .fetch();
    for (BgStockCostLayer row : found) {
      pile.layers.add(new BgStockCostEngine.Layer(row.getQty(), row.getStockValue()));
      pile.rows.add(row);
      pile.lots.add(row.getTrackingNumber());
      pile.dates.add(row.getLayerDate());
    }
    piles.put(key, pile);
    return pile;
  }

  /** Складовата сметка 30x: редът за продукта бие реда за семейството (S0). */
  protected Account stockAccount(Company company, Product product) {
    AccountManagement own =
        JPA.all(AccountManagement.class)
            .filter(
                "self.typeSelect = 1 AND self.company = ?1 AND self.product = ?2", company, product)
            .fetchOne();
    if (own != null && own.getBgStockAccount() != null) {
      return own.getBgStockAccount();
    }
    if (product.getProductFamily() == null) {
      return null;
    }
    AccountManagement family =
        JPA.all(AccountManagement.class)
            .filter(
                "self.typeSelect = 1 AND self.company = ?1 AND self.product IS NULL"
                    + " AND self.productFamily = ?2",
                company,
                product.getProductFamily())
            .fetchOne();
    return family == null ? null : family.getBgStockAccount();
  }

  protected BigDecimal toProductUnit(StockMoveLine line, BigDecimal qty) throws AxelorException {
    Product product = line.getProduct();
    if (line.getUnit() == null
        || product.getUnit() == null
        || line.getUnit().equals(product.getUnit())) {
      return qty;
    }
    return unitConversionService.convert(
        line.getUnit(), product.getUnit(), qty, qty.scale(), product);
  }

  /** Цената на единица от реда, преведена в мерната единица на продукта. */
  protected BigDecimal toProductUnitPrice(StockMoveLine line, BigDecimal price)
      throws AxelorException {
    Product product = line.getProduct();
    if (price == null) {
      return BigDecimal.ZERO;
    }
    if (line.getUnit() == null
        || product.getUnit() == null
        || line.getUnit().equals(product.getUnit())) {
      return price;
    }
    // Цената на единица върви обратно на количеството: от единицата на продукта към тази на реда.
    return unitConversionService.convert(
        product.getUnit(), line.getUnit(), price, Math.max(price.scale(), 6), product);
  }

  private static Long id(StockLocation location) {
    return location == null ? null : location.getId();
  }

  private static <T> List<T> nonNull(List<T> list) {
    return list == null ? new ArrayList<>() : list;
  }
}
