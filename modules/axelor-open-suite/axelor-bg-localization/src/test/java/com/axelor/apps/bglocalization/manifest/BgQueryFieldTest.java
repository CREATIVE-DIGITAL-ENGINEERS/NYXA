package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ВСЕКИ {@code .all().filter("self.…")} В МОДУЛА СЕ СВЕРЯВА СРЕЩУ ДОМЕЙНА — <b>БЕЗ БАЗА, НА
 * ВСЯКА МАШИНА</b>.
 *
 * <p><b>КАПАНЪТ Е В ПРОДУКЦИОНЕН КОД.</b> Филтър по поле, каквото обектът НЯМА — например {@code
 * self.company} върху {@code BgPaySlip}, чийто домейн обявява точно шест връзки (договор,
 * регистрация СОЛ, период, пера, „коригира“, „заличава“), без дружество; {@code AuditableModel}
 * също не го носи — прави справката невъзможна за генериране ИЗОБЩО. А билдът мълчи, защото JPQL е
 * НИЗ и се разпада едва при викане, на машина с база, при натиснат бутон.
 *
 * <p><b>⚠️ ЦЕЛИЯТ ПЪТ, А НЕ САМО КОРЕНЪТ.</b> {@code self.employmentContract.payCompany} е вярно
 * само ако съществуват и ДВЕТЕ стъпки. Проверен само по първата, {@code self.period.fromDatum} би
 * минал за законен — същата грешка, една стъпка по-навътре. Затова обходът се разхожда по {@code
 * ref="…"} на връзките ({@link BgDomainColumns.FieldSpec#ref}).
 *
 * <p><b>⚠️ ЗАЯВКАТА СЕ ВРЪЗВА КЪМ СВОЯТА РЕПОЗИТОРИЯ, А НЕ КЪМ ФАЙЛА.</b> Един файл държи по
 * няколко репозитории и техните {@code self.} значат РАЗЛИЧНИ entity-та: {@code
 * BgArt73Para6ReportService} пита и фишове, и договори, и видове доход, и своя отчет. Сверени срещу
 * едно entity, четири от пет заявки биха били лъжливо червени. Затова се разпознава ВЕРИГАТА —
 * {@code <носител>.all().filter(…)} — и типът на носителя се чете от декларацията му в същия файл.
 *
 * <p><b>⚠️ ЧЕТЕ ДОМЕЙНИТЕ НА ВСИЧКИ МОДУЛИ.</b> {@code EmploymentContract} се дописва и от {@code
 * axelor-human-resource}, и от нас; {@code payCompany} е ТЯХНО поле. Прочетено само от нашите
 * домейни, то би изглеждало несъществуващо и тестът щеше да е червен върху вярна заявка.
 *
 * <p><b>ГРАНИЦИТЕ, ОБЯВЕНИ.</b> Пропускат се мълчаливо: (1) стъпка към entity, чийто домейн не се
 * чете от {@code modules/} (например {@code com.axelor.auth.db.User}) — отказ там би бил червено за
 * модул, който тестът просто не покрива; (2) репозитория, чието име не се разрешава до едно
 * единствено entity; (3) заявки, сглобени от променлива вместо от литерал — те не се четат от текст
 * и никой тест не бива да се преструва, че ги чете.
 */
class BgQueryFieldTest {

  private static final String PAY_SLIP = "com.axelor.apps.bglocalization.db.BgPaySlip";

  private static final String CONTRACT = "com.axelor.apps.hr.db.EmploymentContract";

  private static final String COMPENSATION = "com.axelor.apps.bglocalization.db.BgCompensation";

  /** Документите с номер от брояча на дружеството — {@code company} им е само за ключа. */
  private static final Set<String> NUMBERED = new TreeSet<>(Arrays.asList(PAY_SLIP, COMPENSATION));

  /** Единствените, които пишат и пазят {@code company} на номерираните документи. */
  private static final Set<String> WRITERS =
      new TreeSet<>(
          Arrays.asList(
              "BgPaySlipManagementRepository.java",
              "BgCompensationManagementRepository.java",
              "BgTrzNumberingCompanyGuard.java"));

  /** {@code slip.getCompany()}, {@code paySlip.getCompany()}, {@code compensation.getCompany()}… */
  private static final Pattern NUMBERED_GETTER =
      Pattern.compile(
          "\\b\\w*(?:[Ss]lip|[Cc]ompensation)\\w*\\s*(?:\\(\\s*\\))?\\s*\\.\\s*getCompany\\(\\)");

  /** {@code FROM BgPaySlip p}, {@code JOIN BgCompensation d} — и псевдонимът. */
  private static final Pattern NUMBERED_ALIAS =
      Pattern.compile("\\b(BgPaySlip|BgCompensation)\\s+(?:AS\\s+)?([a-z][A-Za-z0-9_]*)\\b");

  /** Изглед на фиш или обезщетение. */
  private static final Pattern VIEW_OF_NUMBERED =
      Pattern.compile(
          "model=\"com\\.axelor\\.apps\\.bglocalization\\.db\\.Bg(?:PaySlip|Compensation)\"");

  /** Полето или филтър по него в такъв изглед. */
  private static final Pattern VIEW_COMPANY =
      Pattern.compile("<field\\s+name=\"company\"[^>]*>|\\bself\\.company\\b");

  /** Ред от каталога на правата за фиша/обезщетението, чието условие пита {@code self.company}. */
  private static final Pattern PERMISSION_ROW =
      Pattern.compile(
          "\"com\\.axelor\\.apps\\.bglocalization\\.db\\.Bg(?:PaySlip|Compensation)\";[^\\n]*self\\.company\\b[^\\n]*");

  private static final String SOURCES = "src/main/java";

  /** Полетата, които всеки entity наследява и домейнът не ги обявява. */
  private static final Set<String> INHERITED =
      new TreeSet<>(
          Arrays.asList(
              "id",
              "version",
              "archived",
              "createdOn",
              "updatedOn",
              "createdBy",
              "updatedBy",
              "importId",
              "importOrigin",
              "attrs",
              "selected"));

  /** {@code <носител>.all().filter("…" + "…")} — носителят и слепените литерали. */
  private static final Pattern CHAIN =
      Pattern.compile(
          "(?:Beans\\.get\\(\\s*([A-Za-z_$][\\w$]*)\\.class\\s*\\)|([A-Za-z_$][\\w$]*))"
              + "\\s*\\.all\\(\\)\\s*\\.filter\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)");

  /** Декларация на носител: {@code XRepository name} — поле, параметър или локална. */
  private static final Pattern REPO_DECL =
      Pattern.compile("\\b([A-Za-z_$][\\w$]*Repository)\\s+([A-Za-z_$][\\w$]*)\\b");

  private static final Pattern SELF_PATH = Pattern.compile("\\bself\\.([A-Za-z][A-Za-z0-9_.]*)");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Литерал в цял файл — притежателно, без рекурсия и без нов ред (виж {@link #literals}). */
  private static final Pattern FILE_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]++|\\\\.)*+)\"");

  @Test
  @DisplayName("всеки self.<път> във филтрите на модула сочи поле, което домейнът обявява")
  void filterPathsResolve() throws IOException {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> domain = BgDomainColumns.allFields();
    assertNotNull(
        domain.get(PAY_SLIP), "домейните не се четат — тестът няма срещу какво да сверява");

    Map<String, String> entityBySimpleName = entitiesBySimpleName(domain);
    List<String> broken = new ArrayList<>();
    int checked = 0;

    for (Path file : javaSources()) {
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      Map<String, String> repoTypes = repoVariables(text);

      Matcher chain = CHAIN.matcher(text);
      while (chain.find()) {
        String repoType = chain.group(1) != null ? chain.group(1) : repoTypes.get(chain.group(2));
        String entity = repoType == null ? null : entityBySimpleName.get(strip(repoType));
        if (entity == null || !domain.containsKey(entity)) {
          continue;
        }
        checked++;
        String jpql = unquote(chain.group(3));
        Matcher path = SELF_PATH.matcher(jpql);
        while (path.find()) {
          String problem = resolve(domain, entity, path.group(1));
          if (problem != null) {
            broken.add(
                file.getFileName()
                    + " ["
                    + simple(entity)
                    + "]: self."
                    + path.group(1)
                    + " — "
                    + problem);
          }
        }
      }
    }

    assertTrue(checked > 0, "нито една заявка не се разпозна — обходът е останал без обхват");
    assertTrue(
        broken.isEmpty(),
        "заявка сочи поле, каквото домейнът не обявява. JPQL е низ и се разпада едва при викане,"
            + " тоест билдът мълчи, а екранът отказва:\n  "
            + String.join("\n  ", broken));
  }

  /**
   * ⚠️ ВТОРАТА ПОЛОВИНА НА СЪЩОТО ТВЪРДЕНИЕ, И ТЯ Е ОТДЕЛНА НАРОЧНО.
   *
   * <p>Горният тест казва „пътят се разхожда“. Този казва КОЙ е носителят на дружеството за фиша —
   * а той е на ДОГОВОРА, не на фиша. Слети в едно, при изчезване на {@code payCompany} съобщението
   * щеше да говори за „счупена заявка“, вместо за изчезнал носител.
   *
   * <p>⚠️ ЕДИНСТВЕНОТО ИЗКЛЮЧЕНИЕ. BgPaySlip носи {@code company}, но САМО за уникалността
   * (дружество, номер): копие на платеца, записано веднъж при номерирането. Платецът има ЕДИН
   * източник — договорът. Затова тук полето е позволено само като Company и само readonly, а {@link
   * #nobodyReadsTheNumberingCompany} пази никой да не го чете.
   */
  @Test
  @DisplayName(
      "платецът на фиша е EmploymentContract.payCompany; BgPaySlip.company е само за ключа")
  void payerLivesOnTheContract() throws IOException {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> domain = BgDomainColumns.allFields();

    Map<String, BgDomainColumns.FieldSpec> slip = domain.get(PAY_SLIP);
    assertNotNull(slip, "домейнът на BgPaySlip не се чете");
    for (String entity : NUMBERED) {
      BgDomainColumns.FieldSpec numberingCompany = domain.get(entity).get("company");
      if (numberingCompany == null) {
        continue;
      }
      assertTrue(
          "com.axelor.apps.base.db.Company".equals(numberingCompany.ref),
          simple(entity) + ".company сочи " + numberingCompany.ref + ", а не Company");
      assertTrue(
          domainDeclaresReadonlyCompany(entity),
          simple(entity)
              + ".company не е readonly — колоната е само за уникалността (дружество, номер) и се"
              + " записва веднъж, при номерирането.");
    }

    Map<String, BgDomainColumns.FieldSpec> contract = domain.get(CONTRACT);
    assertNotNull(contract, "домейнът на EmploymentContract не се чете от modules/");
    BgDomainColumns.FieldSpec payCompany = contract.get("payCompany");
    assertNotNull(
        payCompany,
        "EmploymentContract вече няма payCompany — заявките по платец върху фиша остават без"
            + " носител и трябва да намерят друг, а не да се преправят наслуки.");
    assertTrue(
        "com.axelor.apps.base.db.Company".equals(payCompany.ref),
        "payCompany сочи " + payCompany.ref + ", а не Company");
  }

  /**
   * НИКОЙ НЕ ЧЕТЕ {@code BgPaySlip.company} И {@code BgCompensation.company} — ИЗВЪН КЛЮЧА.
   *
   * <p>Колоната съществува САМО за уникалността (дружество, номер). Прочетена от заявка, филтър,
   * изглед, правило за достъп или справка, тя става ВТОРИ източник на платеца до договора — и при
   * първото им разминаване (заварен ред без нея, сменен основен договор) двата отговора се
   * разделят.
   *
   * <p>Позволени са само местата, които я ПИШАТ и пазят: двете хранилища (копието при номерирането
   * и сверката с платеца), пазачът по старото състояние ({@code BgTrzNumberingCompanyGuard}) и
   * миграцията ({@code db/migration}, извън обхода). Axelor я ползва сам при проверката за повтарящ
   * се номер ({@code SequenceService#computeCompanyQuery}) — точно уникалността.
   *
   * <p><b>ГРАНИЦИТЕ, ОБЯВЕНИ.</b> Текстов обход: филтрите се разхождат през домейна (и през връзки
   * — {@code self.paySlip.company}); JPQL с псевдоним ({@code FROM BgPaySlip p … p.company}) се
   * хваща в литералите на Java, в изгледите и в {@code nx_permission.csv}; {@code getCompany()} се
   * хваща, когато приемникът е назован като фиш или обезщетение ({@code slip}, {@code paySlip},
   * {@code compensation}…). Заявка от променлива и приемник с друго име не се четат от текст.
   */
  @Test
  @DisplayName(
      "BgPaySlip.company и BgCompensation.company се ползват само за ключа (company, reference)")
  void nobodyReadsTheNumberingCompany() throws IOException {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> domain = BgDomainColumns.allFields();
    Map<String, String> entityBySimpleName = entitiesBySimpleName(domain);
    List<String> readers = new ArrayList<>();

    for (Path file : javaSources()) {
      String name = file.getFileName().toString();
      if (WRITERS.contains(name)) {
        continue;
      }
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

      // (1) self.<път> във филтрите, разходен през домейна.
      Map<String, String> repoTypes = repoVariables(text);
      Matcher chain = CHAIN.matcher(text);
      while (chain.find()) {
        String repoType = chain.group(1) != null ? chain.group(1) : repoTypes.get(chain.group(2));
        String entity = repoType == null ? null : entityBySimpleName.get(strip(repoType));
        if (entity == null || !domain.containsKey(entity)) {
          continue;
        }
        Matcher path = SELF_PATH.matcher(unquote(chain.group(3)));
        while (path.find()) {
          if (readsNumberingCompany(domain, entity, path.group(1))) {
            readers.add(name + " [" + simple(entity) + "]: self." + path.group(1));
          }
        }
      }

      // (2) JPQL с псевдоним в литералите.
      readers.addAll(aliasReads(name, literals(text)));

      // (3) getCompany() върху фиш/обезщетение.
      Matcher getter = NUMBERED_GETTER.matcher(text);
      while (getter.find()) {
        readers.add(name + ": " + getter.group());
      }
    }

    for (Path file : textResources()) {
      String name = BgDomainColumns.moduleRoot().relativize(file).toString();
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      readers.addAll(aliasReads(name, text));
      if (VIEW_OF_NUMBERED.matcher(text).find()) {
        Matcher field = VIEW_COMPANY.matcher(text);
        while (field.find()) {
          readers.add(name + ": " + field.group());
        }
      }
      Matcher permission = PERMISSION_ROW.matcher(text);
      while (permission.find()) {
        readers.add(name + ": " + permission.group().trim());
      }
      if (file.getFileName().toString().equals("nx_permission.csv")) {
        readers.addAll(permissionPathReads(domain, name, text));
      }
    }

    assertTrue(
        readers.isEmpty(),
        "BgPaySlip.company / BgCompensation.company се четат извън уникалността — платецът има"
            + " ЕДИН източник, договорът (EmploymentContract.payCompany; за самоосигуряващия се —"
            + " основният договор от регистрацията):\n  "
            + String.join("\n  ", readers));
  }

  // ═══ ПОМОЩНИ ════════════════════════════════════════════════════════════════

  private static boolean domainDeclaresReadonlyCompany(String entity) throws IOException {
    Path file =
        BgDomainColumns.moduleRoot()
            .resolve("src/main/resources/domains/" + simple(entity) + ".xml");
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    return Pattern.compile("<many-to-one\\s+name=\"company\"[^>]*readonly=\"true\"")
        .matcher(text)
        .find();
  }

  /** Пътят стъпва на {@code company} на фиш или обезщетение — на корена или през връзка. */
  private static boolean readsNumberingCompany(
      Map<String, Map<String, BgDomainColumns.FieldSpec>> domain, String entity, String path) {
    String current = entity;
    for (String step : path.split("\\.")) {
      if (current == null) {
        return false;
      }
      if (NUMBERED.contains(current) && "company".equals(step)) {
        return true;
      }
      Map<String, BgDomainColumns.FieldSpec> fields = domain.get(current);
      BgDomainColumns.FieldSpec spec = fields == null ? null : fields.get(step);
      current = spec == null ? null : spec.ref;
    }
    return false;
  }

  /**
   * Условията на каталога на правата, разходени през домейна — като филтрите горе: {@code
   * self.paySlip.company} в правото на ДРУГ обект също чете колоната на фиша.
   *
   * <p>⚠️ Генератор на каталога, който вижда прякото {@code BgPaySlip.company}, дава на {@code
   * BgPaySlipRegimeChange} условие {@code self.paySlip.company = ?} — права по колона, празна на
   * заварените фишове. Проверката по ред на самия фиш не хваща това.
   */
  private static List<String> permissionPathReads(
      Map<String, Map<String, BgDomainColumns.FieldSpec>> domain, String name, String text) {
    List<String> out = new ArrayList<>();
    for (String line : text.split("\n")) {
      String[] cols = line.split(";", -1);
      if (cols.length < 8) {
        continue;
      }
      String entity = cols[1].replace("\"", "").trim();
      String condition = cols[7];
      if (!domain.containsKey(entity) || condition.isEmpty()) {
        continue;
      }
      Matcher path = SELF_PATH.matcher(condition);
      while (path.find()) {
        if (readsNumberingCompany(domain, entity, path.group(1))) {
          out.add(name + " [" + simple(entity) + "]: self." + path.group(1));
        }
      }
    }
    return out;
  }

  /** {@code BgPaySlip p … p.company} — псевдонимът на фиша/обезщетението, последван от company. */
  private static List<String> aliasReads(String name, String text) {
    List<String> out = new ArrayList<>();
    Matcher alias = NUMBERED_ALIAS.matcher(text);
    Set<String> aliases = new LinkedHashSet<>();
    while (alias.find()) {
      aliases.add(alias.group(2));
    }
    for (String a : aliases) {
      Matcher read = Pattern.compile("\\b" + Pattern.quote(a) + "\\.company\\b").matcher(text);
      while (read.find()) {
        out.add(name + ": " + read.group());
      }
    }
    return out;
  }

  /**
   * Всички низови литерали на файла, слепени.
   *
   * <p>⚠️ НЕ {@link #LITERAL}: неговата алтернация {@code (?:[^"\\]|\\.)*} е рекурсивна в {@code
   * java.util.regex} и върху цял файл с дълъг литерал изчерпва стека (StackOverflowError в гейта).
   * Тук — притежателни квантори и без нов ред: литерал на Java не пресича ред.
   */
  private static String literals(String text) {
    StringBuilder out = new StringBuilder();
    Matcher piece = FILE_LITERAL.matcher(text);
    while (piece.find()) {
      out.append(piece.group(1)).append(' ');
    }
    return out.toString();
  }

  /** Изгледите, ресурсите и каталогът на правата (nyxa-security) — без миграциите. */
  private static List<Path> textResources() throws IOException {
    List<Path> out = new ArrayList<>();
    Path resources = BgDomainColumns.moduleRoot().resolve("src/main/resources");
    Path permissions =
        BgDomainColumns.modulesRoot()
            .resolve("axelor-open-suite/nyxa-security/src/main/resources/nyxa-security");
    for (Path root : Arrays.asList(resources, permissions)) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        for (Path file :
            (Iterable<Path>)
                walk.filter(
                        p -> {
                          String s = p.toString().replace('\\', '/');
                          return (s.endsWith(".xml") || s.endsWith(".csv"))
                              && !s.contains("/db/migration/")
                              && !s.contains("/domains/");
                        })
                    ::iterator) {
          out.add(file);
        }
      }
    }
    assertTrue(!out.isEmpty(), "не се намериха ресурси за обход");
    return out;
  }

  /**
   * Разхожда пътя през домейна. Връща причината за отказ или {@code null}, когато пътят е годен.
   */
  private static String resolve(
      Map<String, Map<String, BgDomainColumns.FieldSpec>> domain, String root, String dotted) {
    String entity = root;
    String[] steps = dotted.split("\\.");
    for (int i = 0; i < steps.length; i++) {
      Map<String, BgDomainColumns.FieldSpec> fields = domain.get(entity);
      if (fields == null) {
        return null; // entity извън прочетените домейни — граница, не отказ
      }
      String step = steps[i];
      if (INHERITED.contains(step)) {
        return null;
      }
      BgDomainColumns.FieldSpec spec = fields.get(step);
      if (spec == null) {
        return "„" + step + "“ не е поле на " + simple(entity);
      }
      if (i + 1 < steps.length) {
        if (spec.ref == null) {
          return "„" + step + "“ на " + simple(entity) + " е стойност, а пътят продължава след нея";
        }
        entity = spec.ref;
      }
    }
    return null;
  }

  /**
   * Просто име на entity → FQN. Двусмислените имена ОТПАДАТ, вместо да се познава: две entity-та с
   * едно име в различни пакети биха дали свереност срещу грешния домейн.
   */
  private static Map<String, String> entitiesBySimpleName(
      Map<String, Map<String, BgDomainColumns.FieldSpec>> domain) {
    Map<String, String> out = new TreeMap<>();
    Set<String> ambiguous = new LinkedHashSet<>();
    for (String fqn : domain.keySet()) {
      String simple = simple(fqn);
      String seen = out.put(simple, fqn);
      if (seen != null && !seen.equals(fqn)) {
        ambiguous.add(simple);
      }
    }
    ambiguous.forEach(out::remove);
    return out;
  }

  /** Носителите, декларирани в този файл: име на променлива → име на класа ѝ. */
  private static Map<String, String> repoVariables(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher decl = REPO_DECL.matcher(text);
    while (decl.find()) {
      out.put(decl.group(2), decl.group(1));
    }
    return out;
  }

  /** {@code BgPaySlipRepository} → {@code BgPaySlip}; {@code …ManagementRepository} също. */
  private static String strip(String repository) {
    String name = repository;
    if (name.endsWith("ManagementRepository")) {
      return name.substring(0, name.length() - "ManagementRepository".length());
    }
    return name.endsWith("Repository")
        ? name.substring(0, name.length() - "Repository".length())
        : name;
  }

  private static String simple(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  private static List<Path> javaSources() throws IOException {
    List<Path> out = new ArrayList<>();
    Path root = BgDomainColumns.moduleRoot().resolve(SOURCES);
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file :
          (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
        out.add(file);
      }
    }
    assertTrue(!out.isEmpty(), "не се намери нито един Java файл в " + root);
    return out;
  }

  /** Слепва конкатенираните низови части и маха кавичките. */
  private static String unquote(String literals) {
    StringBuilder out = new StringBuilder();
    Matcher piece = LITERAL.matcher(literals);
    while (piece.find()) {
      out.append(piece.group(1));
    }
    return out.toString();
  }
}
