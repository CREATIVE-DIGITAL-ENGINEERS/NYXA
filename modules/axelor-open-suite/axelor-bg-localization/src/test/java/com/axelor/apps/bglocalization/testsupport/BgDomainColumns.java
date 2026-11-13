package com.axelor.apps.bglocalization.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.db.hibernate.naming.PhysicalNamingStrategyImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.hibernate.boot.model.naming.Identifier;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Декларираните дължини на низовите колони, прочетени ОТ ДОМЕЙН XML-ИТЕ.
 *
 * <p><b>Защо на едно място.</b> Две различни твърдения стъпват на един и същи прочит: файловото
 * („стойността в seed-а се побира в декларираната колона“ — {@code BgSeedColumnWidthTest}) и
 * базовото („действителната колона отговаря на декларацията“ — {@code BgSchemaColumnWidthTest}).
 * Ако всяко си четеше XML-ите сам, двете можеха да се разминат в правилото и разминаването щеше да
 * изглежда като противоречие между тестовете, а не като дефект в четенето.
 *
 * <p><b>Правилото.</b> Низово поле е {@code varchar(255)}. Изключенията са две: {@code
 * large="true"} прави колоната {@code text} и маха тавана, а изричен {@code max} задава друга
 * дължина (генераторът го изнася като {@code @Size(max=…)}, откъдето Hibernate взема дължината на
 * колоната). Нищо в този модул днес не ползва {@code max} — поддържан е, защото правилото е негово,
 * не на данните.
 */
public final class BgDomainColumns {

  /** Подразбиращата се дължина на низова колона при Hibernate. */
  public static final int DEFAULT_LENGTH = 255;

  private static final String DOMAINS = "src/main/resources/domains";

  private BgDomainColumns() {}

  /** Декларираната ширина на едно низово поле. */
  public static final class Width {

    /** Броят знаци, или {@code null} за {@code text} — тоест без таван. */
    public final Integer chars;

    private Width(Integer chars) {
      this.chars = chars;
    }

    /**
     * Ширина от друг източник — напр. действителната колона, прочетена от {@code
     * information_schema}.
     */
    public static Width of(Integer chars) {
      return new Width(chars);
    }

    public boolean isText() {
      return chars == null;
    }

    @Override
    public String toString() {
      return chars == null ? "text" : "varchar(" + chars + ")";
    }
  }

  /** Коренът на модула, в който тече тестът. */
  public static Path moduleRoot() {
    return Paths.get("").toAbsolutePath();
  }

  /** Коренът {@code modules/}, тоест всички модули наведнъж. */
  public static Path modulesRoot() {
    Path modules = moduleRoot().getParent().getParent();
    if (!Files.isDirectory(modules) || !"modules".equals(modules.getFileName().toString())) {
      fail("очаквах модулът да живее в modules/<група>/<модул>, а съм в " + moduleRoot());
    }
    return modules;
  }

  /**
   * Декларациите от ВСИЧКИ модули, по FQN на entity-то.
   *
   * <p>Едно entity може да се дописва от няколко домейн XML-а и генераторът ги слива; проверка само
   * в собствения модул би пропуснала точно полето, добавено отвън.
   */
  public static Map<String, Map<String, Width>> acrossAllModules() {
    return read(collect(modulesRoot()));
  }

  /** Декларациите САМО на този модул — обхватът, който базовата половина проверява. */
  public static Map<String, Map<String, Width>> inThisModule() {
    return read(collect(moduleRoot().resolve(DOMAINS)));
  }

  /** Едно поле от домейна — какъвто и да е типът му. */
  public static final class FieldSpec {

    /** Името на елемента: {@code string}, {@code decimal}, {@code many-to-one}… */
    public final String tag;

    /**
     * Дали домейнът го обявява {@code required="true"}, тоест генераторът слага {@code @NotNull}.
     */
    public final boolean required;

    /**
     * FQN-ът на сочения entity за връзките ({@code ref="…"}), или {@code null} за проста стойност.
     *
     * <p>⚠️ Пази се, за да може ПЪТ да се разхожда, а не само корен. Заявка като {@code
     * self.employmentContract.payCompany} е вярна само ако и двете стъпки съществуват; проверен
     * само по първата, {@code self.period.fromDatum} щеше да мине за законен. Виж {@code
     * BgPaySlipQueryFieldTest}.
     */
    public final String ref;

    /**
     * Стойността на {@code min="…"} от домейна, или {@code null}, ако няма.
     *
     * <p>⚠️ За ЧИСЛОВИТЕ полета това не е просто долна граница, а КАПАН при seed: генераторът слага
     * {@code @Min(n)} И инициализира полето на {@code 0} (напр. {@code private Integer requiredSize
     * = 0}). Тоест пропусната колона НЕ оставя null — оставя нула, която Bean Validation НЕ
     * прескача, и целият ред пада. За НИЗОВИТЕ ({@code min} = дължина) капан няма: те нямат
     * инициализатор, остават null и валидацията ги подминава. Виж {@code BgSeedMinConstraintTest}.
     */
    public final String min;

    private FieldSpec(String tag, boolean required, String ref, String min) {
      this.tag = tag;
      this.required = required;
      this.ref = ref;
      this.min = min;
    }
  }

  /** Имената на елементите, които домейнът смята за полета. */
  private static final List<String> FIELD_TAGS =
      Arrays.asList(
          "string",
          "integer",
          "long",
          "decimal",
          "boolean",
          "date",
          "datetime",
          "time",
          "binary",
          "enum",
          "many-to-one",
          "one-to-one",
          "many-to-many",
          "one-to-many");

  /**
   * ВСИЧКИ полета на всички entity-та, с признака {@code required} — не само низовите.
   *
   * <p>Ползва се от {@code BgImportBindingTest}: там въпросът не е за ширина, а кое поле е
   * задължително и следователно чие непопълване поваля INSERT.
   */
  public static Map<String, Map<String, FieldSpec>> allFields() {
    Map<String, Map<String, FieldSpec>> out = new TreeMap<>();
    for (Path file : collect(modulesRoot())) {
      Element root = parse(file);
      if (root == null) {
        continue;
      }
      NodeList moduleNodes = root.getElementsByTagNameNS("*", "module");
      if (moduleNodes.getLength() == 0) {
        continue;
      }
      String pkg = ((Element) moduleNodes.item(0)).getAttribute("package");
      if (pkg.isEmpty()) {
        continue;
      }
      NodeList entities = root.getElementsByTagNameNS("*", "entity");
      for (int e = 0; e < entities.getLength(); e++) {
        Element entity = (Element) entities.item(e);
        Map<String, FieldSpec> fields =
            out.computeIfAbsent(pkg + "." + entity.getAttribute("name"), k -> new TreeMap<>());
        for (Node child = entity.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (child.getNodeType() != Node.ELEMENT_NODE) {
            continue;
          }
          String tag = child.getLocalName();
          String name = ((Element) child).getAttribute("name");
          if (FIELD_TAGS.contains(tag) && !name.isEmpty()) {
            String ref = ((Element) child).getAttribute("ref");
            String min = ((Element) child).getAttribute("min");
            fields.put(
                name,
                new FieldSpec(
                    tag,
                    "true".equals(((Element) child).getAttribute("required")),
                    ref.isEmpty() ? null : ref,
                    min.isEmpty() ? null : min));
          }
        }
      }
    }
    return out;
  }

  private static Element parse(Path file) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    } catch (Exception e) {
      fail("не се чете домейн XML " + file + ": " + e.getMessage());
      return null;
    }
  }

  private static List<Path> collect(Path root) {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(p -> p.toString().endsWith(".xml"))
          .filter(p -> p.getParent().endsWith(Paths.get(DOMAINS)))
          .forEach(out::add);
    } catch (Exception e) {
      fail("не се обхождат домейн XML-ите под " + root + ": " + e.getMessage());
    }
    if (out.isEmpty()) {
      fail(
          "под "
              + root
              + " няма нито един домейн XML — ако подредбата се е сменила, тестът мълчи вместо да мери");
    }
    return out;
  }

  private static Map<String, Map<String, Width>> read(List<Path> files) {
    Map<String, Map<String, Width>> out = new TreeMap<>();
    for (Path file : files) {
      Element root = parse(file);
      if (root == null) {
        continue;
      }

      NodeList moduleNodes = root.getElementsByTagNameNS("*", "module");
      if (moduleNodes.getLength() == 0) {
        continue;
      }
      String pkg = ((Element) moduleNodes.item(0)).getAttribute("package");
      if (pkg.isEmpty()) {
        continue;
      }

      NodeList entities = root.getElementsByTagNameNS("*", "entity");
      for (int e = 0; e < entities.getLength(); e++) {
        Element entity = (Element) entities.item(e);
        Map<String, Width> fields =
            out.computeIfAbsent(pkg + "." + entity.getAttribute("name"), k -> new TreeMap<>());

        for (Node child = entity.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (child.getNodeType() != Node.ELEMENT_NODE || !"string".equals(child.getLocalName())) {
            continue;
          }
          Element field = (Element) child;
          String name = field.getAttribute("name");
          if (name.isEmpty()) {
            continue;
          }
          if ("true".equals(field.getAttribute("large"))) {
            fields.put(name, new Width(null));
          } else {
            String max = field.getAttribute("max");
            fields.put(name, new Width(max.isEmpty() ? DEFAULT_LENGTH : Integer.parseInt(max)));
          }
        }
      }
    }
    return out;
  }

  /**
   * Името на колоната за поле — през СЪЩАТА стратегия, която ползва Hibernate.
   *
   * <p><b>Защо не се пише на ръка.</b> Правилото не е „главна буква → долна черта“. По {@link
   * PhysicalNamingStrategyImpl} черта се вмъква само когато главната буква е МЕЖДУ две малки. Тоест
   * {@code bgNote → bg_note}, но {@code d1Code → d1code} и {@code bgD1Field → bgd1field} — след
   * цифра черта НЕ се слага. Ръчно написаното правило даваше {@code d1_code} и обявяваше три
   * съществуващи колони за липсващи, тоест докладваше собствената си грешка като находка.
   *
   * <p>{@code toPhysicalColumnName} не ползва {@code JdbcEnvironment} и приема {@code null}.
   */
  public static String columnName(String field) {
    return PhysicalNamingStrategyImpl.INSTANCE
        .toPhysicalColumnName(Identifier.toIdentifier(field), null)
        .getText();
  }

  /** Името на таблицата през същата стратегия — {@code @Table} го дава с главни букви. */
  public static String tableName(String declared) {
    return PhysicalNamingStrategyImpl.INSTANCE
        .toPhysicalTableName(Identifier.toIdentifier(declared), null)
        .getText();
  }
}
