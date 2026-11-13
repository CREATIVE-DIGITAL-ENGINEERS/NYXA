package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.views.MenuItem;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Скриването на чуждо меню още СТИГА до нещо, и още е построено така, че да сработи.
 *
 * <p><b>Защо изобщо.</b> Файловете {@code Menu-*-hide.xml} не носят свои екрани — всеки ред в тях е
 * преопределяне на чуждо меню, вързано ЕДИНСТВЕНО за чуждото {@code name}. Такава връзка се къса
 * тихо в двете посоки:
 *
 * <ol>
 *   <li><b>Отвън.</b> Ъпгрейд на чуждия модул преименува или маха менюто. Нашият ред остава валиден
 *       XML, зарежда се без грешка и създава СИРАК в {@code meta_menu} — ред, който не покрива
 *       нищо. Междувременно преименуваното чуждо меню изплува видимо, защото вече никой не го
 *       покрива.
 *   <li><b>Отвътре.</b> Ред без {@code id} изобщо не се прилага: {@code AbstractLoader#isVisited}
 *       ключува по {@code xmlId}, а без него — по {@code name}, тоест вторият {@code menuitem} със
 *       същото име се брои за ПОВТОРЕНИЕ и {@code ViewLoader#importMenu} излиза веднага. Следата е
 *       един ред в лога — „Duplicate MenuItem found without 'id'“ — и нищо повече: boot-ът минава,
 *       приложението работи, а френското меню си стои на екрана.
 * </ol>
 *
 * <p>И двата провала се виждат само с очи, върху вдигнато приложение, от човек, който знае какво да
 * търси. Затова проверката е тук.
 *
 * <p><b>Обхватът е ЦЕЛИЯТ classpath.</b> Файловете със скривания са в {@link #HIDE_FILES} — {@code
 * Menu-hr-hide.xml}, {@code Menu-account-hide.xml}, {@code Menu-nap-hide.xml}, {@code
 * Menu-base-hide.xml}, {@code Menu-stock-hide.xml} и {@code Menu-project-hide.xml} — но филтър по
 * име на модул би направил теста мълчалив в деня, в който някой скрие меню на трети. Затова се
 * четат {@code views/*.xml} от всеки JAR — цената е изброяване на записи, защото само пътищата под
 * {@code views/} изобщо се разпарсват.
 *
 * <p><b>И затова е ЕДИН тест, а не по един на файл.</b> Инвариантът е общ: повторен {@code id} или
 * второ скриване на едно и също меню губи преопределяне независимо дали двата реда са в същия файл,
 * или в съседния. Разцепен на тест за файл, точно този случай би останал непроверен.
 */
class BgHiddenMenuTest {

  /**
   * Всеки файл със скривания на чужди менюта. Инвариантът е ЕДИН и се проверява наведнъж: разцепен
   * на по един тест за файл, той би мълчал за съседа — а точно съседът е този, който се добавя, без
   * някой да се сети за пазача.
   */
  private static final List<String> HIDE_FILES =
      List.of(
          "src/main/resources/views/Menu-hr-hide.xml",
          "src/main/resources/views/Menu-account-hide.xml",
          "src/main/resources/views/Menu-nap-hide.xml",
          // „Import demo data“ — без файла тук пазачът мълчи за него.
          "src/main/resources/views/Menu-base-hide.xml",
          "src/main/resources/views/Menu-stock-hide.xml",
          "src/main/resources/views/Menu-project-hide.xml");

  /**
   * РЕШЕНИЯТА: менюто, модулът, чийто JAR носи чуждия ред, и родителят му. Общите проверки по-горе
   * казват, че всяко скриване е годно; тази — че ТОВА е скрито и още покрива точно своя чужд ред.
   */
  private static final List<String[]> DECIDED =
      List.of(
          // „Администрация › Поддръжка › Import demo data“ (Menu-base-hide.xml)
          new String[] {"admin-root-import-demo-data", "axelor-base", "admin-root-maintenance"},
          // Френските чекове, LCR, FEC и логистичните формуляри
          new String[] {
            "account-root-payments-cheque-deposit-slip-all",
            "axelor-account",
            "account-root-payments"
          },
          new String[] {
            "account-root-periodic-processing-cheque-reject",
            "axelor-account",
            "account-root-periodic-processing"
          },
          new String[] {
            "account-root-bank-order-lcr", "axelor-bank-payment", "account-root-bank-orders"
          },
          new String[] {"fec-import", "axelor-account", "account-conf"},
          new String[] {"stock-root-logistical-forms", "axelor-stock", "stock-root-deliveries"},
          // Главните менюта (родител няма) — скриване на ръка пада при деплой
          new String[] {"menu-project-root", "axelor-project", ""},
          new String[] {"menu-team", "axelor-base", ""},
          new String[] {"menu-calendar", "axelor-base", ""},
          new String[] {"menu-dms", "axelor-base", ""});

  private static Document parse(InputStream in) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(in);
  }

  private static List<Element> menuItems(Document doc) {
    List<Element> items = new ArrayList<>();
    NodeList all = doc.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if ("menuitem".equals(e.getLocalName())) {
        items.add(e);
      }
    }
    return items;
  }

  /** Разпарсва един файл със скривания и пада, ако го няма. */
  private static Document hideDocument(String path) throws Exception {
    Path file = Paths.get(path);
    assertTrue(Files.isRegularFile(file), "не намирам " + file.toAbsolutePath());
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in);
    }
  }

  /** Скритите менюта от ВСИЧКИ файлове: име → заглавието, с което е записано. */
  private static Map<String, String> hiddenMenus() throws Exception {
    Map<String, String> found = new LinkedHashMap<>();
    for (String path : HIDE_FILES) {
      for (Element item : menuItems(hideDocument(path))) {
        found.put(item.getAttribute("name"), item.getAttribute("title"));
      }
    }
    assertFalse(
        found.isEmpty(), String.join(", ", HIDE_FILES) + " не съдържат нито един <menuitem>");
    return found;
  }

  @Test
  @DisplayName("Всяко скриване носи id, hidden=\"true\" и непразно име")
  void everyOverrideIsBuiltSoThatItApplies() throws Exception {
    List<String> broken = new ArrayList<>();
    // Наборите са ОБЩИ за всички файлове: повторен id — или второ скриване на същото меню — в
    // съседния файл губи едното преопределяне точно както и в същия файл.
    Set<String> ids = new LinkedHashSet<>();
    Set<String> names = new LinkedHashSet<>();
    for (String path : HIDE_FILES) {
      for (Element item : menuItems(hideDocument(path))) {
        String where = path + ": ";
        String name = item.getAttribute("name");
        String id = item.getAttribute("id");
        if (name.isBlank()) {
          broken.add(where + "<menuitem> без name");
          continue;
        }
        if (!names.add(name)) {
          broken.add(
              where + name + " → същото меню се скрива два пъти; едното преопределяне се губи");
        }
        if (id.isBlank()) {
          broken.add(
              where
                  + name
                  + " → без id. ⚠️ Преопределяне без id се брои за ПОВТОРЕНИЕ по name и се"
                  + " изхвърля МЪЛЧАЛИВО (ViewLoader#importMenu излиза на isVisited); чуждото меню"
                  + " остава видимо.");
        } else if (!ids.add(id)) {
          broken.add(where + name + " → id=" + id + " се повтаря, тоест едното скриване се губи");
        }
        if (!"true".equals(item.getAttribute("hidden"))) {
          broken.add(where + name + " → hidden не е \"true\", тоест редът не скрива нищо");
        }
        if (item.getAttribute("title").isBlank()) {
          broken.add(where + name + " → без title (задължителен по object-views.xsd)");
        }
      }
    }

    if (!broken.isEmpty()) {
      fail("негодни скривания (" + broken.size() + "):\n  " + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Всяко скрито меню още съществува в чуждия модул")
  void everyHiddenMenuStillExistsUpstream() throws Exception {
    Map<String, String> hidden = hiddenMenus();

    Set<String> upstream = new LinkedHashSet<>();
    int scannedJars = 0;
    for (File jar : jarsOnClasspath()) {
      try (JarFile zip = new JarFile(jar)) {
        scannedJars++;
        Enumeration<JarEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().startsWith("views/") || !entry.getName().endsWith(".xml")) {
            continue;
          }
          Document doc;
          try (InputStream in = zip.getInputStream(entry)) {
            doc = parse(in);
          } catch (Exception ignored) {
            continue;
          }
          for (Element item : menuItems(doc)) {
            upstream.add(item.getAttribute("name"));
          }
        }
      }
    }

    if (upstream.isEmpty()) {
      fail(
          "не намирам НИТО ЕДНО меню в JAR-овете от classpath-а (прегледани "
              + scannedJars
              + "). ⚠️ Без чуждите менюта проверката е безсмислена — тя щеше да мълчи, каквото и да"
              + " пише в "
              + String.join(", ", HIDE_FILES)
              + ".");
    }

    List<String> orphans = new ArrayList<>();
    for (Map.Entry<String, String> entry : hidden.entrySet()) {
      // Нашият собствен ред идва от src/, не от JAR — затова се търси ЧУЖДОТО меню със същото име.
      if (!upstream.contains(entry.getKey())) {
        orphans.add(entry.getKey() + " (\"" + entry.getValue() + "\")");
      }
    }

    if (!orphans.isEmpty()) {
      fail(
          "скриваме менюта, които вече ги няма нагоре по веригата ("
              + orphans.size()
              + " от "
              + hidden.size()
              + "):\n  "
              + String.join("\n  ", orphans)
              + "\n⚠️ Всяко от тях е СИРАК: редът ни се зарежда без грешка и не покрива нищо, а"
              + " преименуваното чуждо меню изплува видимо. Проверете как се казва днес и поправете"
              + " "
              + String.join(", ", HIDE_FILES)
              + " — или махнете реда, ако менюто е премахнато.");
    }
  }

  /**
   * Решените скривания, прочетени така, както ги чете зареждането: {@link XMLViews#unmarshal} е
   * същият JAXB с object-views.xsd, през който {@code ViewLoader} внася файла, а {@code
   * ViewLoader#importMenu} записва в {@code meta_menu} точно {@code getXmlId()}, {@code
   * getHidden()} и {@code getParent()} на получения {@link MenuItem}. Оттам: id → втори ред с
   * приоритет на чуждия + 1 (чуждият вече е в базата — модулът му е зависимост на bg-localization,
   * затова и JAR-ът му е на този classpath), hidden → {@code MenuService} не показва възела и
   * поддървото му.
   */
  @Test
  @DisplayName("Решените менюта излизат скрити при зареждането и покриват своя чужд ред")
  void decidedMenusAreHiddenAsTheLoaderReadsThem() throws Exception {
    Map<String, MenuItem> ours = new LinkedHashMap<>();
    for (String path : HIDE_FILES) {
      ObjectViews views;
      try (InputStream in = Files.newInputStream(Paths.get(path))) {
        views = XMLViews.unmarshal(in);
      }
      for (MenuItem item : views.getMenus()) {
        ours.put(item.getName(), item);
      }
    }
    Map<String, List<String>> upstream = upstreamMenus("parent");
    Map<String, List<String>> upstreamIds = upstreamMenus("id");

    List<String> broken = new ArrayList<>();
    for (String[] decided : DECIDED) {
      String name = decided[0];
      String module = decided[1];
      String parent = decided[2];
      MenuItem item = ours.get(name);
      if (item == null) {
        broken.add(name + " → не е скрито в нито един от " + HIDE_FILES);
        continue;
      }
      if (!Boolean.TRUE.equals(item.getHidden())) {
        broken.add(name + " → след зареждането hidden=" + item.getHidden() + ", не true");
      }
      if (item.getXmlId() == null || item.getXmlId().isBlank()) {
        broken.add(name + " → без id; ViewLoader го изхвърля като повторение");
      }
      if (!parent.equals(item.getParent() == null ? "" : item.getParent())) {
        broken.add(name + " → родител " + item.getParent() + ", а чуждият е " + parent);
      }
      String expected = module + "|" + parent;
      List<String> found = upstream.getOrDefault(name, List.of());
      if (!found.contains(expected)) {
        broken.add(
            name
                + " → чуждият ред не е в "
                + module
                + " под "
                + parent
                + " (на classpath-а: "
                + found
                + ")");
      }
    }
    // menu-team и menu-dms: ДВА чужди реда — axelor-core без id (приоритет 0) и axelor-base с id
    // (приоритет 1). Нашият взима приоритета на MetaMenuRepository#findByName (подреден по
    // -priority, тоест на base) + 1 = 2, а MenuUtils#fetchMetaMenu оставя най-високия. Проверява
    // се, че картината, на която стъпва това, не се е сменила под нас.
    for (String twice : List.of("menu-team", "menu-dms")) {
      List<String> rows = upstream.getOrDefault(twice, List.of());
      if (!rows.contains("axelor-core|") || !rows.contains("axelor-base|")) {
        broken.add(twice + " → очаквам чужд ред в axelor-core и в axelor-base, а са " + rows);
      }
      if (!upstreamIds.getOrDefault(twice, List.of()).contains("axelor-base|abs-" + twice)) {
        broken.add(twice + " → редът на axelor-base вече не е с id abs-" + twice);
      }
    }
    // LCR се скрива като група: детето пада заедно с нея само докато още е под нея.
    if (!upstream
        .getOrDefault("account-root-bank-order-lcr-bank-orders", List.of())
        .contains("axelor-bank-payment|account-root-bank-order-lcr")) {
      broken.add(
          "account-root-bank-order-lcr-bank-orders вече не е под account-root-bank-order-lcr —"
              + " скриването на групата не го покрива");
    }

    if (!broken.isEmpty()) {
      fail(
          "решените скривания не държат ("
              + broken.size()
              + "):\n  "
              + String.join("\n  ", broken));
    }
  }

  /** Чуждите менюта: име → „модул|атрибут“ (родител или id) за всеки JAR, който го носи. */
  private static Map<String, List<String>> upstreamMenus(String attribute) throws Exception {
    Map<String, List<String>> found = new LinkedHashMap<>();
    for (File jar : jarsOnClasspath()) {
      Matcher m = JAR_MODULE.matcher(jar.getName());
      if (!m.matches()) {
        continue;
      }
      String module = m.group(1);
      try (JarFile zip = new JarFile(jar)) {
        Enumeration<JarEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().startsWith("views/") || !entry.getName().endsWith(".xml")) {
            continue;
          }
          Document doc;
          try (InputStream in = zip.getInputStream(entry)) {
            doc = parse(in);
          } catch (Exception ignored) {
            continue;
          }
          for (Element item : menuItems(doc)) {
            found
                .computeIfAbsent(item.getAttribute("name"), k -> new ArrayList<>())
                .add(module + "|" + item.getAttribute(attribute));
          }
        }
      }
    }
    return found;
  }

  /** „axelor-bank-payment-8.2.0.jar“ → „axelor-bank-payment“. */
  private static final Pattern JAR_MODULE = Pattern.compile("(.+?)-\\d[^/]*\\.jar");

  private static List<File> jarsOnClasspath() {
    List<File> jars = new ArrayList<>();
    for (String part : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (part.endsWith(".jar")) {
        File jar = new File(part);
        if (jar.isFile()) {
          jars.add(jar);
        }
      }
    }
    return jars;
  }
}
