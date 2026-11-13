package com.axelor.apps.bglocalization.service.saft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * НОМЕНКЛАТУРИТЕ НА НАП, ЧЕТЕНИ ОТ CLASSPATH — по веднъж на JVM, при първо поискване.
 *
 * <p><b>⚠️ ОТ ФАЙЛА, НЕ ОТ БАЗАТА.</b> Източникът на истина е {@code nomenclatures/nra/} — дословно
 * извлечен от структурната книга и пазен от {@code BgNraNomenclatureIntegrityTest}. Внасянето им
 * остава забранено (файловете са ИНЕРТНИ към App Install и синка); забраната е за вносителските
 * конфигурации, не за четене. Затова тук няма нито заявка, нито кеш, който да остарее спрямо файла.
 *
 * <p><b>⚠️ ЗАГЛАВНИЯТ РЕД СЕ СВЕРЯВА БУКВАЛНО.</b> Колоните се четат по позиция; разместени колони
 * биха дали правдоподобни, но чужди стойности. Променен заглавен ред спира четенето с името на
 * файла.
 *
 * <p>Разделителят е запетая, кавичките са по RFC 4180 ({@code ""} е кавичка в поле). Редове с нов
 * ред вътре в поле няма — извличането ги е свило — и ако се появят, четенето спира, вместо да ги
 * разцепи.
 */
public final class BgNraNomenclatures {

  static final String DIR = "/nomenclatures/nra/";

  static final String ACCOUNTS = DIR + "nra_nom_accounts.csv";
  static final String TAX_IMP = DIR + "tax_imp.csv";
  static final String UNIT_OF_MEASURE = DIR + "unit_of_measure.csv";
  static final String NC8_TARIC = DIR + "nc8_taric.csv";
  static final String PAYMENT_METHOD = DIR + "nom_paymentmethod.csv";
  static final String INVOICE_TYPES = DIR + "nom_invoice_types.csv";
  static final String STOCK_MOVEMENT = DIR + "stock_movement.csv";
  static final String ASSET_MOVEMENT_TYPES = DIR + "asset_movement_types.csv";
  static final String INVENTORY_TYPES = DIR + "nom_inventory_types.csv";

  private BgNraNomenclatures() {}

  /**
   * Ред от TAX-IMP.
   *
   * <p>⚠️ КОЛОНАТА „rate“ СЕ ПАЗИ ДОСЛОВНО, НЕ КАТО ЧИСЛО. Книгата смесва в нея четири различни
   * неща: дроб ({@code 0.2} = 20 %), фиксирана сума върху хазарта ({@code 500}, {@code 5000},
   * {@code 22000}), „Н/П“ (25 кода, сред тях 100330–100332) и празно. Прочетена като ставка, сумата
   * би излязла като 50 000 % — тълкуването (TaxPercentage или FlatTaxRate) е на TaxTable.
   */
  public static final class TaxCode {
    private final String taxType;
    private final String taxCode;
    private final String taxName;
    private final String description;
    private final String rate;

    TaxCode(String taxType, String taxCode, String taxName, String description, String rate) {
      this.taxType = taxType;
      this.taxCode = taxCode;
      this.taxName = taxName;
      this.description = description;
      this.rate = rate;
    }

    public String taxType() {
      return taxType;
    }

    public String taxCode() {
      return taxCode;
    }

    public String taxName() {
      return taxName;
    }

    public String description() {
      return description;
    }

    /** Дословната стойност от колона „rate“; празен низ, където книгата е празна. */
    public String rate() {
      return rate;
    }
  }

  /** Унифицираният сметкоплан: код → наименование. */
  public static Map<String, String> accounts() {
    return Accounts.MAP;
  }

  /** TAX-IMP: код на данъка → ред. */
  public static Map<String, TaxCode> taxCodes() {
    return TaxCodes.MAP;
  }

  /** Unit_Of_Measure: код → българско наименование. */
  public static Map<String, String> unitsOfMeasure() {
    return Units.MAP;
  }

  /** NC8_TARIC: кодовете, включително {@code 00000000} за услуги. Кодът {@code 0} НЕ е тук. */
  public static Set<String> nc8Codes() {
    return Nc8.SET;
  }

  /**
   * Nom_PaymentMethod: метод (SD.P.10) → механизмите му (SD.P.32).
   *
   * <p>⚠️ КЛЮЧЪТ Е ДВОЙКАТА, НЕ МЕТОДЪТ. Деветте реда носят три метода; механизъм е валиден само
   * заедно със своя метод — 97 е „Прихващане“, не „Безкасово плащане“.
   */
  public static Map<String, Set<String>> paymentMechanisms() {
    return PaymentMethods.MAP;
  }

  /** Nom_Invoice_Types: код на вида документ → наименование (S.I.9). */
  public static Map<String, String> invoiceTypes() {
    return InvoiceTypes.MAP;
  }

  /**
   * Stock movement: вид на движението на запасите → наименование (MF.MT.2, SD.MG.10), в реда на
   * книгата.
   *
   * <p>⚠️ НАИМЕНОВАНИЯТА СА ДОСЛОВНИ, С КРАЙНИТЕ ИНТЕРВАЛИ — „Продажба “ (30) и „…запаси “ (150).
   * Така ги пише и официалният образец в MovementTypeTable/Description; изрязани, те престават да
   * са текстът на НАП.
   */
  public static Map<String, String> movementTypes() {
    return MovementTypes.MAP;
  }

  /** AssetMovementTypes: вид на транзакцията с актив → наименование (SD.AT.5). */
  public static Map<String, String> assetTransactionTypes() {
    return AssetTransactionTypes.MAP;
  }

  /** Nom_Inventory_Types: вид на материалния запас → наименование (MF.PS.6 ProductType). */
  public static Map<String, String> inventoryTypes() {
    return InventoryTypes.MAP;
  }

  // ─────────────── мързеливите притежатели — зареждане при първо поискване ───────────────

  private static final class Accounts {
    static final Map<String, String> MAP = load();

    private static Map<String, String> load() {
      Map<String, String> map = new LinkedHashMap<>();
      for (String[] row : read(ACCOUNTS, "nra_code,nra_name,section,group")) {
        put(map, ACCOUNTS, row[0], row[1]);
      }
      return Collections.unmodifiableMap(map);
    }
  }

  private static final class TaxCodes {
    static final Map<String, TaxCode> MAP = load();

    private static Map<String, TaxCode> load() {
      Map<String, TaxCode> map = new LinkedHashMap<>();
      for (String[] row : read(TAX_IMP, "taxType,taxCode,taxName,description,rate,note")) {
        put(map, TAX_IMP, row[1], new TaxCode(row[0], row[1], row[2], row[3], row[4]));
      }
      return Collections.unmodifiableMap(map);
    }
  }

  private static final class Units {
    static final Map<String, String> MAP = load();

    private static Map<String, String> load() {
      Map<String, String> map = new LinkedHashMap<>();
      for (String[] row : read(UNIT_OF_MEASURE, "code,nameEn,nameBg")) {
        put(map, UNIT_OF_MEASURE, row[0], row[2]);
      }
      return Collections.unmodifiableMap(map);
    }
  }

  private static final class Nc8 {
    static final Set<String> SET = load();

    private static Set<String> load() {
      Set<String> set = new LinkedHashSet<>();
      for (String[] row : read(NC8_TARIC, "nc8_code,description")) {
        if (!set.add(row[0])) {
          throw new IllegalStateException(NC8_TARIC + ": повторен код " + row[0]);
        }
      }
      return Collections.unmodifiableSet(set);
    }
  }

  private static final class PaymentMethods {
    static final Map<String, Set<String>> MAP = load();

    private static Map<String, Set<String>> load() {
      Map<String, Set<String>> map = new LinkedHashMap<>();
      for (String[] row :
          read(PAYMENT_METHOD, "methodCode,methodName,mechanismCode,mechanismName")) {
        if (row[0].isEmpty() || row[2].isEmpty()) {
          throw new IllegalStateException(PAYMENT_METHOD + ": ред без метод или механизъм");
        }
        if (!map.computeIfAbsent(row[0], k -> new LinkedHashSet<>()).add(row[2])) {
          throw new IllegalStateException(
              PAYMENT_METHOD + ": повторена двойка " + row[0] + "/" + row[2]);
        }
      }
      map.replaceAll((method, mechanisms) -> Collections.unmodifiableSet(mechanisms));
      return Collections.unmodifiableMap(map);
    }
  }

  private static final class InvoiceTypes {
    static final Map<String, String> MAP = codeNames(INVOICE_TYPES);
  }

  private static final class MovementTypes {
    static final Map<String, String> MAP = codeNames(STOCK_MOVEMENT);
  }

  private static final class AssetTransactionTypes {
    static final Map<String, String> MAP = codeNames(ASSET_MOVEMENT_TYPES);
  }

  private static final class InventoryTypes {
    static final Map<String, String> MAP = codeNames(INVENTORY_TYPES);
  }

  /** Номенклатура „код, наименование“ — в реда на файла, наименованието дословно. */
  private static Map<String, String> codeNames(String resource) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String[] row : read(resource, "code,name")) {
      put(map, resource, row[0], row[1]);
    }
    return Collections.unmodifiableMap(map);
  }

  // ─────────────── четенето ───────────────

  private static <V> void put(Map<String, V> map, String resource, String key, V value) {
    if (key.isEmpty()) {
      throw new IllegalStateException(resource + ": ред без код");
    }
    if (map.put(key, value) != null) {
      throw new IllegalStateException(resource + ": повторен код " + key);
    }
  }

  /** Редовете без заглавния; всеки с точно толкова полета, колкото заглавният ред. */
  static List<String[]> read(String resource, String header) {
    try (InputStream in = BgNraNomenclatures.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Номенклатурата " + resource + " не е в classpath-а");
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String first = reader.readLine();
      if (!header.equals(first)) {
        throw new IllegalStateException(
            resource + ": заглавният ред е „" + first + "“, очаква се „" + header + "“");
      }
      int columns = header.split(",", -1).length;
      List<String[]> rows = new ArrayList<>();
      String line;
      int number = 1;
      while ((line = reader.readLine()) != null) {
        number++;
        if (line.isEmpty()) {
          continue;
        }
        String[] row = split(line, resource, number);
        if (row.length != columns) {
          throw new IllegalStateException(
              resource + ":" + number + ": " + row.length + " полета, очакват се " + columns);
        }
        rows.add(row);
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException("Номенклатурата " + resource + " не се чете", e);
    }
  }

  /** Един ред по RFC 4180. */
  static String[] split(String line, String resource, int number) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    if (quoted) {
      throw new IllegalStateException(
          resource + ":" + number + ": незатворени кавички — поле с нов ред не се поддържа");
    }
    fields.add(field.toString());
    return fields.toArray(new String[0]);
  }
}
