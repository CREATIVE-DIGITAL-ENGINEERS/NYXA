package com.axelor.apps.bglocalization.service.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.csv.script.ImportAccountingConfigTemplate;
import com.axelor.data.csv.CSVConfig;
import com.axelor.data.csv.CSVInput;
import com.axelor.meta.db.MetaFile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BgCoaTemplateRefresh}: зипът на BG_COA се прави наново само когато СЪДЪРЖАНИЕТО на
 * CSV-тата се различава. Без база — отпечатъците и пакетирането се подменят с наследник.
 *
 * <p>Последните тестове пазят връзката със seed-а и самия сметкоплан: конфигът вика колбека, а
 * днешният BG_COA носи 40901, 60212 под 602 и 602 като групова сметка. Пълният път (зип → ново
 * дружество) е в {@code BgCoaTemplateFreshCompanyIntegrationTest}.
 */
class BgCoaTemplateRefreshTest {

  private static final Path MODULE_RESOURCES =
      BgDomainColumns.moduleRoot().resolve("src/main/resources");

  private static final Path BG_COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Map<String, Object> NO_VALUES = Collections.emptyMap();

  /** Пакетирането, както го прави data-init — без диск: само слага нов MetaFile. */
  private static class FakePackager extends ImportAccountingConfigTemplate {
    int calls;
    boolean failUpload;

    @Override
    public Object importAccountingConfigTemplate(Object bean, Map<String, Object> values) {
      calls++;
      if (!failUpload) {
        MetaFile fresh = new MetaFile();
        fresh.setFileName("BG_COA.zip");
        ((AccountingConfigTemplate) bean).setMetaFile(fresh);
      }
      return bean;
    }
  }

  private static class FakeRefresh extends BgCoaTemplateRefresh {
    final SortedMap<String, String> shipped;
    final SortedMap<String, String> stored;

    FakeRefresh(FakePackager packager, Map<String, String> shipped, Map<String, String> stored) {
      super(packager);
      this.shipped = new TreeMap<>(shipped);
      this.stored = new TreeMap<>(stored);
    }

    @Override
    protected SortedMap<String, String> shippedFingerprint(AccountingConfigTemplate template) {
      return shipped;
    }

    @Override
    protected SortedMap<String, String> storedFingerprint(MetaFile metaFile) {
      return stored;
    }
  }

  private static AccountingConfigTemplate stored(Long id) {
    AccountingConfigTemplate template = new AccountingConfigTemplate();
    template.setId(id);
    template.setCode("BG_COA");
    template.setCountryCode("bgr");
    MetaFile old = new MetaFile();
    old.setFileName("BG_COA.zip");
    template.setMetaFile(old);
    return template;
  }

  private static final Map<String, String> CURRENT =
      Map.of("account_account.csv", "a2", "account_journal.csv", "j1");

  @Test
  @DisplayName("Същото съдържание (втори старт, нова база) → нищо, нов MetaFile не се качва")
  void sameContentIsANoOp() throws Exception {
    FakePackager packager = new FakePackager();
    AccountingConfigTemplate template = stored(1L);
    MetaFile before = template.getMetaFile();

    Object out = new FakeRefresh(packager, CURRENT, CURRENT).refresh(template, NO_VALUES);

    assertSame(template, out);
    assertSame(before, template.getMetaFile());
    assertEquals(0, packager.calls);
  }

  @Test
  @DisplayName("Заварен стар зип (без 40901) → пакетира се наново, шаблонът сочи новия")
  void staleZipIsRepackaged() throws Exception {
    FakePackager packager = new FakePackager();
    AccountingConfigTemplate template = stored(1L);
    MetaFile before = template.getMetaFile();
    Map<String, String> old = Map.of("account_account.csv", "a1", "account_journal.csv", "j1");

    new FakeRefresh(packager, CURRENT, old).refresh(template, NO_VALUES);

    assertEquals(1, packager.calls);
    assertNotNull(template.getMetaFile());
    assertNotEquals(before, template.getMetaFile());
  }

  @Test
  @DisplayName("Шаблон без зип (качването при data-init е паднало) → пакетира се")
  void missingZipIsPackaged() throws Exception {
    FakePackager packager = new FakePackager();
    AccountingConfigTemplate template = stored(1L);
    template.setMetaFile(null);

    new FakeRefresh(packager, CURRENT, Map.of()).refresh(template, NO_VALUES);

    assertEquals(1, packager.calls);
    assertNotNull(template.getMetaFile());
  }

  @Test
  @DisplayName("Няма запис BG_COA (data-init на axelor-account не е минал) → редът се пропуска")
  void noTemplateRowIsSkipped() throws Exception {
    FakePackager packager = new FakePackager();
    AccountingConfigTemplate fresh = new AccountingConfigTemplate();
    fresh.setCode("BG_COA");

    assertNull(new FakeRefresh(packager, CURRENT, Map.of()).refresh(fresh, NO_VALUES));
    assertEquals(0, packager.calls);
  }

  @Test
  @DisplayName("Без CSV на classpath-а завареният зип НЕ се подменя с празен")
  void emptyClasspathLeavesTheZip() throws Exception {
    FakePackager packager = new FakePackager();
    AccountingConfigTemplate template = stored(1L);
    MetaFile before = template.getMetaFile();

    new FakeRefresh(packager, Map.of(), CURRENT).refresh(template, NO_VALUES);

    assertSame(before, template.getMetaFile());
    assertEquals(0, packager.calls);
  }

  @Test
  @DisplayName("Паднало качване → шаблонът остава със стария зип, вносът не спира")
  void failedUploadKeepsTheOldZip() throws Exception {
    FakePackager packager = new FakePackager();
    packager.failUpload = true;
    AccountingConfigTemplate template = stored(1L);
    MetaFile before = template.getMetaFile();

    Object out = new FakeRefresh(packager, CURRENT, Map.of()).refresh(template, NO_VALUES);

    assertSame(template, out);
    assertSame(before, template.getMetaFile());
  }

  @Test
  @DisplayName("Чужд бийн минава непокътнат")
  void otherBeansPassThrough() throws Exception {
    FakePackager packager = new FakePackager();
    MetaFile other = new MetaFile();

    assertSame(other, new FakeRefresh(packager, CURRENT, Map.of()).refresh(other, NO_VALUES));
    assertEquals(0, packager.calls);
  }

  // ---------------------------------------------------------- отпечатъкът на зипа

  private static void zip(Path target, List<String[]> entries, long time) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
      for (String[] e : entries) {
        ZipEntry entry = new ZipEntry(e[0]);
        entry.setLastModifiedTime(FileTime.fromMillis(time));
        out.putNextEntry(entry);
        out.write(e[1].getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
  }

  @Test
  @DisplayName("Отпечатъкът е по съдържание: часът и редът на записите не участват")
  void fingerprintIgnoresTimeAndOrder(@TempDir Path dir) throws Exception {
    Path first = dir.resolve("a.zip");
    Path second = dir.resolve("b.zip");
    String[] x = {"x.csv", "1"};
    String[] y = {"y.csv", "2"};
    zip(first, Arrays.asList(x, y), 1_000L);
    zip(second, Arrays.asList(y, x), 9_000_000L);

    assertEquals(
        BgCoaTemplateRefresh.zipFingerprint(first), BgCoaTemplateRefresh.zipFingerprint(second));
  }

  @Test
  @DisplayName("Един сменен байт в един CSV → отпечатъкът се различава точно по него")
  void fingerprintSeesAContentChange(@TempDir Path dir) throws Exception {
    Path first = dir.resolve("a.zip");
    Path second = dir.resolve("b.zip");
    zip(first, Arrays.asList(new String[] {"x.csv", "1"}, new String[] {"y.csv", "2"}), 1_000L);
    zip(second, Arrays.asList(new String[] {"x.csv", "1"}, new String[] {"y.csv", "3"}), 1_000L);

    Set<String> differing =
        BgCoaTemplateRefresh.differingFiles(
            BgCoaTemplateRefresh.zipFingerprint(first),
            BgCoaTemplateRefresh.zipFingerprint(second));

    assertEquals(Set.of("y.csv"), differing);
  }

  @Test
  @DisplayName("Добавен или махнат CSV също е разлика")
  void addedOrRemovedFileIsADifference() {
    Set<String> differing =
        BgCoaTemplateRefresh.differingFiles(
            Map.of("x.csv", "1"), Map.of("x.csv", "1", "base_sequenceVersion.csv", "s"));

    assertEquals(Set.of("base_sequenceVersion.csv"), differing);
  }

  // ---------------------------------------------------------- връзката със seed-а

  @Test
  @DisplayName("Конфигът вика колбека на входа account_accountingConfigTemplate.csv, по code")
  void configCallsTheRefresh() throws Exception {
    CSVConfig config =
        CSVConfig.parse(
            MODULE_RESOURCES.resolve("apps/init-data/bg-localization-config.xml").toFile());
    CSVInput input =
        config.getInputs().stream()
            .filter(i -> "account_accountingConfigTemplate.csv".equals(i.getFileName()))
            .findFirst()
            .orElse(null);

    assertNotNull(input, "входът за шаблона липсва в bg-localization-config.xml");
    assertEquals(AccountingConfigTemplate.class.getName(), input.getTypeName());
    assertEquals("self.code = :code", input.getSearch());
    assertEquals(BgCoaTemplateRefresh.class.getName() + ":refresh", input.getCallable());
    // CSVInput#call търси точно (Object, Map) — друга сигнатура пада чак при вноса.
    Method method = BgCoaTemplateRefresh.class.getMethod("refresh", Object.class, Map.class);
    assertNotNull(method);
  }

  @Test
  @DisplayName("CSV-то носи САМО code = BG_COA — входът не пише име, държава или зип")
  void csvCarriesOnlyTheCode() throws Exception {
    for (String lang : new String[] {"en", "fr"}) {
      List<String> lines =
          Files.readAllLines(
              MODULE_RESOURCES.resolve(
                  "apps/init-data/" + lang + "/account_accountingConfigTemplate.csv"),
              StandardCharsets.UTF_8);
      assertEquals(List.of("\"code\"", "\"BG_COA\""), lines, lang);
    }
  }

  // ---------------------------------------------------------- днешният BG_COA

  private static Map<String, String[]> accounts() throws IOException {
    List<String> lines =
        Files.readAllLines(BG_COA.resolve("account_account.csv"), StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(lines.get(0).replace("\"", "").split(";", -1));
    int code = header.indexOf("code");
    int parent = header.indexOf("parent_code");
    int type = header.indexOf("accountType.importId");
    Map<String, String[]> out = new TreeMap<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] cells = line.replace("\"", "").split(";", -1);
      out.put(cells[code], new String[] {cells[parent], cells[type]});
    }
    return out;
  }

  private static Map<String, String> technicalTypes() throws IOException {
    List<String> lines =
        Files.readAllLines(BG_COA.resolve("account_accountType.csv"), StandardCharsets.UTF_8);
    Map<String, String> out = new TreeMap<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] cells = line.replace("\"", "").split(";", -1);
      out.put(cells[0], cells[1]);
    }
    return out;
  }

  @Test
  @DisplayName("BG_COA в кода: 40901 е разчет, 60212 е под 602, 602 е групова (view)")
  void theShippedChartIsTheNraChart() throws Exception {
    Map<String, String[]> accounts = accounts();
    Map<String, String> types = technicalTypes();

    assertTrue(accounts.containsKey("40901"), "40901 „Ваучери за храна“ липсва");
    assertEquals("payable", types.get(accounts.get("40901")[1]));
    assertTrue(accounts.containsKey("60212"), "60212 липсва");
    assertEquals("602", accounts.get("60212")[0]);
    assertEquals("view", types.get(accounts.get("602")[1]));
  }
}
