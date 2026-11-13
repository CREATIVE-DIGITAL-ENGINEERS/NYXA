package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * СХЕМАТА, СРЕЩУ КОЯТО Е ВАЛИДИРАН ФАЙЛЪТ, СЕ ЗАПИСВА КАТО АРТЕФАКТ — за да може месеци по-късно да
 * се докаже СРЕЩУ КОЕ е минал един подаден одитен файл.
 *
 * <p><b>⚠️ ЗАЩО СХЕМАТА Е АРТЕФАКТ, А НЕ САМО НИЗ В {@code SaftReport.schemaVersion}.</b> „1.0.2“ е
 * ИМЕ на версия и то е сменяно с изменителна заповед два пъти. Отпечатък е ТЪЖДЕСТВО. Корекция се
 * подава до шест месеца по-късно (ДОПК чл. 71к ал. 6), а НАП дава 7 дни за замяна на отхвърлен файл
 * — тоест въпросът „срещу тези ли байтове е минал“ се задава СЛЕД като схемата може вече да е
 * сменена. Записан артефакт с SHA-256 отговаря; име на версия — не.
 *
 * <p><b>⚠️ ПРЕЗ {@code RegulatoryFile}, А НЕ ПРЕЗ {@code MetaFiles.upload} — същата врата, през
 * която минават Д1/Д6, чл. 73 и Приложение № 9.</b> Вратата взима отпечатъка на ТОЧНО тези байтове
 * в конструктора, преди нещо да ги е записало, така че съхраненото и хешираното не могат да се
 * разминат.
 *
 * <p><b>⚠️ ПИНЪТ СЕ СВЕРЯВА ПРЕДИ ЗАПИС, А НЕ СЛЕД НЕГО.</b> Регистриран без проверка, подменен
 * файл би получил собствен, съвършено правдоподобен отпечатък — и одитната следа щеше да сочи
 * грешната схема с пълна увереност. {@link #schemaFile()} отказва вместо това.
 */
@Singleton
public class BgSaftSchemaRegistrar {

  /** Типът съдържание на съхранения {@code MetaFile}. */
  private static final String MIME_TYPE = "application/xml";

  protected final NapArtifactService artifactService;
  protected final NapArtifactRepository artifactRepo;

  @Inject
  public BgSaftSchemaRegistrar(
      NapArtifactService artifactService, NapArtifactRepository artifactRepo) {
    this.artifactService = artifactService;
    this.artifactRepo = artifactRepo;
  }

  /**
   * Закованата схема на {@code version} като готов за записване файл — ЧИСТА функция, без Guice и
   * без база, точно както {@code BgD1SubmissionPlan} е тестваем без Postgres.
   *
   * @throws IllegalStateException когато схемата липсва в classpath-а или байтовете ѝ не отговарят
   *     на реда от {@link BgSaftXsd#VERSIONS} — непроверена схема НЕ се записва
   */
  public static RegulatoryFile schemaFile(BgSaftXsd.Version version) {
    byte[] bytes = readClasspath(version.getResource());
    if (bytes.length != version.getByteLength()) {
      throw new IllegalStateException(
          "Схемата "
              + version.getResource()
              + " е "
              + bytes.length
              + " байта, а закрепката твърди "
              + version.getByteLength()
              + " — подменен файл не се записва като одитна следа.");
    }
    RegulatoryFile file = new RegulatoryFile(Institution.NAP, fileName(version), MIME_TYPE, bytes);
    if (!version.getSha256().equals(file.getSha256())) {
      throw new IllegalStateException(
          "Схемата "
              + version.getResource()
              + " има отпечатък "
              + file.getSha256()
              + ", а закрепката твърди "
              + version.getSha256()
              + " — подменен файл не се записва като одитна следа.");
    }
    return file;
  }

  /**
   * Артефактът на закованата схема — СЪЩЕСТВУВАЩИЯТ, ако вече е записан, инак нов.
   *
   * <p><b>⚠️ ЕДИН РЕД НА СХЕМА, А НЕ ЕДИН НА ДРУЖЕСТВО ИЛИ НА ОТЧЕТ.</b> Схемата е една и съща за
   * всички; ключът е отпечатъкът. Нов ред при всяко извикване щеше да напълни регистъра с копия на
   * едни и същи байтове и да направи въпроса „коя схема“ по-труден, а не по-лесен. Версията се
   * подава — тази, по която е проверен файлът ({@link BgSaftXsd#forDate}).
   */
  public NapArtifact register(BgSaftXsd.Version version) throws IOException {
    RegulatoryFile file = schemaFile(version);
    NapArtifact existing =
        artifactRepo
            .all()
            .filter("self.sha256 = :sha AND self.artifactTypeSelect = :type")
            .bind("sha", file.getSha256())
            .bind("type", NapArtifactRepository.TYPE_SCHEMA)
            .fetchOne();
    if (existing != null) {
      return existing;
    }
    // ⚠️ БЕЗ ДРУЖЕСТВО, НАРОЧНО: схемата е обща за всички (виж горе), не файл на едно дружество.
    // Каталогът я пуска само на admin — решение от 17.10.2026.
    return artifactService.createFromFile(file, NapArtifactRepository.TYPE_SCHEMA, null);
  }

  /**
   * Името, под което схемата се съхранява — точно както е публикувана от НАП.
   *
   * <p>⚠️ Изведено от ресурса на версията, а не написано втори път: два низа за едно име се
   * разминават при първата смяна на версията, и то мълчаливо.
   */
  public static String fileName(BgSaftXsd.Version version) {
    String resource = version.getResource();
    return resource.substring(resource.lastIndexOf('/') + 1);
  }

  private static byte[] readClasspath(String resource) {
    try (InputStream in = BgSaftSchemaRegistrar.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "Схемата " + resource + " не е в classpath-а — няма какво да се регистрира.");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Схемата " + resource + " не се прочете: " + e.getMessage(), e);
    }
  }
}
