package com.axelor.apps.bglocalization.service.fatca.adapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ОТКЪДЕ Е СХЕМАТА НА FATCA ФАЙЛА, КОЯ Е ТЯ И КАКВО ОЩЕ НЕ Е ПОТВЪРДЕНО — заковано в код.
 *
 * <p><b>⚠️ СХЕМАТА Е НА IRS, НЕ НА НАП.</b> Споразумението между компетентните органи на България и
 * САЩ (т. 3.2.1) предвижда обмен по IRS Publication 5124 — FATCA XML. НАП приема файла с формата,
 * утвърден със заповед {@link #NAP_ORDER}; с нея през 2016 г. е публикувана v1.1, която IRS не
 * поддържа от януари 2017 г. Тук е текущата v2.0.1 от irs.gov. Коя версия валидира порталът днес —
 * ДА СЕ ПОТВЪРДИ.
 *
 * <p><b>⚠️ ВЕРСИЯТА Е НА ЕДНО МЯСТО.</b> {@link #VERSION} е атрибутът {@code version} на {@code
 * FATCA_OECD} (Pub 5124, т. 2.1: версията на схемата) и папката в classpath-а. {@code
 * BgFatcaXsdPinTest} сверява, че основната схема декларира същата версия.
 *
 * <p><b>⚠️ ОТПЕЧАТЪЦИТЕ СА ТУК, ЗА ДА СЕ ВИДИ ПОДМЯНА.</b> Четирите файла в {@code
 * resources/schemas/fatca/v2.0.1/} са копие байт по байт от пакета на IRS (LF, {@code *.xsd
 * -text}).
 *
 * <p><b>⚠️ НУЛА МРЕЖА ПО ВРЕМЕ НА РАБОТА.</b> Валидацията е офлайн, срещу това копие.
 */
public final class BgFatcaXsd {

  private BgFatcaXsd() {}

  /** Версията на схемата — и атрибутът {@code version} във файла. */
  public static final String VERSION = "2.0.1";

  /** Заповедта на изпълнителния директор на НАП за реда и формата. */
  public static final String NAP_ORDER = "ЗЦУ-599/25.05.2016 г.";

  /** Пакетът на IRS, от който са файловете. */
  public static final String SOURCE_URL = "https://www.irs.gov/pub/fatca/fatcaxml-v2-0-1.zip";

  /** Датата, на която пакетът е свален. */
  public static final String DOWNLOADED_ON = "2026-10-27";

  /** Папката в classpath-а. */
  public static final String DIR = "/schemas/fatca/v" + VERSION + "/";

  /** Основната схема; останалите три се внасят от нея. */
  public static final String RESOURCE = DIR + "FatcaXML_v" + VERSION + ".xsd";

  /** Префиксът на кодовете за грешка — стига до екрана. */
  public static final String CODE_PREFIX = "FATCA";

  /** Pub 5124: файлът е UTF-8. */
  public static final String ENCODING = "UTF-8";

  public static final String NS_FTC = "urn:oecd:ties:fatca:v2";
  public static final String NS_SFA = "urn:oecd:ties:stffatcatypes:v2";
  public static final String ROOT_ELEMENT = "FATCA_OECD";

  /** Един закрепен файл: име, дължина, SHA-256. */
  public static final class Pinned {
    public final String file;
    public final long byteLength;
    public final String sha256;

    Pinned(String file, long byteLength, String sha256) {
      this.file = file;
      this.byteLength = byteLength;
      this.sha256 = sha256;
    }

    public String resource() {
      return DIR + file;
    }
  }

  /** Четирите файла от пакета на IRS — сверяват се от {@code BgFatcaXsdPinTest}. */
  public static final List<Pinned> FILES =
      Collections.unmodifiableList(
          Arrays.asList(
              new Pinned(
                  "FatcaXML_v" + VERSION + ".xsd",
                  26342L,
                  "bfab1daf0473aca250bf35831d58db3a16efb3804777cc8bb5313908ee5ba3d6"),
              new Pinned(
                  "stffatcatypes_v2.0.xsd",
                  24675L,
                  "3c2cebf3f19414d6e5998d4e09e59db4627f2d4ac074ec068557a76d5010521c"),
              new Pinned(
                  "isofatcatypes_v1.2.xsd",
                  73242L,
                  "8d3fd18488acac640d9bc86f643c935bf6e2a7ef7aab19e4e4f7b9c26c58c86a"),
              new Pinned(
                  "oecdtypes_v4.2.xsd",
                  3702L,
                  "9a93ec6136c2904dc331003b91944d2d711440079b7867975c02aa45d4a5225d")));

  /**
   * Името на файла. ⚠️ Правилата на НАП за името не са намерени — ДА СЕ ПОТВЪРДИ; до тогава името
   * само назовава годината и институцията.
   */
  public static String fileName(int reportYear, String giin) {
    return "FATCA_" + reportYear + "_" + giin + ".xml";
  }

  /** Едноредово описание: срещу коя схема е валидиран файлът. */
  public static String describe() {
    return "FATCA XML v"
        + VERSION
        + " (IRS, свалена "
        + DOWNLOADED_ON
        + ", sha256 "
        + FILES.get(0).sha256.substring(0, 12)
        + "…)";
  }
}
