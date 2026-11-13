package com.axelor.apps.bglocalization.service.setup;

import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.csv.script.ImportAccountingConfigTemplate;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.MetaScanner;
import com.axelor.meta.db.MetaFile;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Опреснява зипа на шаблона BG_COA ({@code AccountingConfigTemplate.metaFile}), когато CSV-тата на
 * сметкоплана в кода са се сменили.
 *
 * <p><b>Защо изобщо.</b> Ново дружество получава сметкоплана от ЗИПА, записан в базата, а не от
 * classpath-а ({@code AccountingConfigTemplateService#installProcess} чете {@code
 * act.getMetaFile()}). Зипът се прави от {@code ImportAccountingConfigTemplate} при data-init на
 * axelor-account, а data-init върви САМО при първата инсталация на модула: {@code AppStartup} вика
 * {@code ModuleManager.initialize(false, …)}, тоест модул, който базата вече познава, не е
 * „pending“ и data-init не се пуска. Следствие: зипът замръзва във версията от първия старт на
 * базата и никой деплой не го опреснява — ново дружество получава сметкоплана такъв, какъвто е бил
 * при първия старт.
 *
 * <p><b>Откъде се вика.</b> {@code call} на входа {@code account_accountingConfigTemplate.csv} в
 * {@code apps/init-data/bg-localization-config.xml}: при App Install и при всеки старт през {@code
 * ModuleResourceSyncService} ({@code datainit.sync.modules}). Без SQL и без DDL.
 *
 * <p><b>Сравнението е по СЪДЪРЖАНИЕ, не по байтове на зипа.</b> Отпечатъкът е множеството (име на
 * CSV → SHA-256 на съдържанието). Зипът носи и часа на пакетиране, тоест два зипа с едни и същи
 * CSV-та се различават байт по байт. Еднакъв отпечатък → нищо (нов MetaFile не се качва).
 *
 * <p><b>Пакетира същият код като data-init</b> ({@code ImportAccountingConfigTemplate}), тоест
 * опресненият зип е байт по байт това, което би дала нова база със същия build. Старият MetaFile
 * остава (одит); шаблонът сочи новия.
 *
 * <p><b>Заварените дружества не се пипат.</b> Зипът се чете само при вноса на счетоводната
 * конфигурация на НОВО дружество; прилаганото досега не се повтаря. Шаблоните FRA_PCG и GBR_COA не
 * се пипат.
 *
 * <p>⚠️ Няма ли записа BG_COA (data-init на axelor-account още не е минал), входът НЕ създава
 * шаблон — колбекът връща {@code null} и редът се пропуска; следващият старт опитва пак.
 */
public class BgCoaTemplateRefresh {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String CODE = "BG_COA";

  protected final ImportAccountingConfigTemplate packager;

  @Inject
  public BgCoaTemplateRefresh(ImportAccountingConfigTemplate packager) {
    this.packager = packager;
  }

  /** Колбекът на CSV вноса. */
  public Object refresh(Object bean, Map<String, Object> values) throws IOException {
    if (!(bean instanceof AccountingConfigTemplate)) {
      return bean;
    }
    AccountingConfigTemplate template = (AccountingConfigTemplate) bean;
    if (template.getId() == null) {
      LOG.warn(
          "BG Localization: chart template {} not found — axelor-account data-init has not created"
              + " it yet; nothing to refresh.",
          CODE);
      return null;
    }
    SortedMap<String, String> shipped = shippedFingerprint(template);
    if (shipped.isEmpty()) {
      LOG.warn(
          "BG Localization: no CSV under {} on the classpath — the {} template is left as it is.",
          chartPath(template),
          CODE);
      return bean;
    }
    MetaFile before = template.getMetaFile();
    SortedMap<String, String> stored = storedFingerprint(before);
    if (shipped.equals(stored)) {
      return bean;
    }
    Set<String> differing = differingFiles(stored, shipped);
    packager.importAccountingConfigTemplate(template, values);
    MetaFile after = template.getMetaFile();
    if (after == null || after == before) {
      LOG.warn(
          "BG Localization: the {} template differs from the shipped chart ({}), but the new ZIP"
              + " could not be stored — new companies still get the old chart.",
          CODE,
          differing);
      return bean;
    }
    LOG.info(
        "BG Localization: {} template refreshed — {} file(s) differ from the stored ZIP {}: {}.",
        CODE,
        differing.size(),
        before == null ? "(none)" : before.getId(),
        differing);
    return bean;
  }

  /** Пътят, от който пакетира и data-init: {@code l10n/l10n_<държава>/<код>}. */
  protected static String chartPath(AccountingConfigTemplate template) {
    return "l10n/l10n_" + template.getCountryCode() + "/" + template.getCode();
  }

  /** CSV-тата на сметкоплана в този build — същото сканиране като {@code getDataFile}. */
  protected SortedMap<String, String> shippedFingerprint(AccountingConfigTemplate template)
      throws IOException {
    List<URL> resources = MetaScanner.findAll(chartPath(template) + "/[^/]+\\.csv$");
    SortedMap<String, String> out = new TreeMap<>();
    for (URL resource : resources) {
      String path = resource.getPath();
      try (InputStream in = resource.openStream()) {
        out.put(path.substring(path.lastIndexOf('/') + 1), sha256(ByteStreams.toByteArray(in)));
      }
    }
    return out;
  }

  /** Записаният зип; няма MetaFile или файлът липсва на диска → празен отпечатък. */
  protected SortedMap<String, String> storedFingerprint(MetaFile metaFile) throws IOException {
    if (metaFile == null || metaFile.getFilePath() == null) {
      return new TreeMap<>();
    }
    Path zip = MetaFiles.getPath(metaFile);
    if (!Files.isRegularFile(zip)) {
      return new TreeMap<>();
    }
    return zipFingerprint(zip);
  }

  /** Име на запис → SHA-256 на съдържанието му; часът и редът на записите не участват. */
  public static SortedMap<String, String> zipFingerprint(Path zip) throws IOException {
    SortedMap<String, String> out = new TreeMap<>();
    try (ZipFile file = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = file.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        try (InputStream in = file.getInputStream(entry)) {
          String name = entry.getName();
          out.put(name.substring(name.lastIndexOf('/') + 1), sha256(ByteStreams.toByteArray(in)));
        }
      }
    }
    return out;
  }

  /** Имената, които липсват от едната страна или са с различно съдържание. */
  public static Set<String> differingFiles(Map<String, String> a, Map<String, String> b) {
    Set<String> names = new TreeSet<>(a.keySet());
    names.addAll(b.keySet());
    names.removeIf(n -> a.containsKey(n) && a.get(n).equals(b.get(n)));
    return Collections.unmodifiableSet(names);
  }

  protected static String sha256(byte[] bytes) {
    return Hashing.sha256().hashBytes(bytes).toString();
  }
}
