package com.axelor.apps.bglocalization.service.saft.adapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ОТКЪДЕ Е СХЕМАТА НА СТАНДАРТНИЯ ОДИТЕН ФАЙЛ (SAF-T), КОЯ Е ТЯ И С КОИ ЗАПОВЕДИ Е УТВЪРДЕНА —
 * заковано в код, не в нечия папка „Изтегляния“.
 *
 * <p><b>⚠️ ЗАДЪЛЖЕНИЕТО И ФОРМАТЪТ СА ДВЕ РАЗЛИЧНИ НОРМИ.</b> Задължението да се подава SAF-T е по
 * <b>ДОПК чл. 71з</b>; форматът и редът се утвърждават със ЗАПОВЕД на изпълнителния директор на НАП
 * <b>на основание чл. 71к ал. 4 ДОПК</b>. Тоест схемата не идва с наредба и не се извежда от закона
 * — тя е приложение към заповед, което се сменя със следваща заповед, без ДОПК да се пипа. Затова
 * веригата на заповедите е ТУК, а не в коментар някъде.
 *
 * <p><b>⚠️ ЗАПОВЕДИТЕ СА ЧЕТИРИ, И ПОСЛЕДНАТА НЕ ОТМЕНЯ ПЪРВАТА.</b> {@link #ORDER_ORIGINAL}
 * утвърждава формата и реда; {@link #ORDER_AMENDMENT_1} вдига схемата на 1.0.1; {@link
 * #ORDER_AMENDMENT_2} — на 1.0.2 „считано от <b>01.04.2026 г.</b>“; {@link #ORDER_AMENDMENT_3} — на
 * 1.0.3, дословно (с. 1): „Стандартните одитни файлове за данъчни цели се подават съгласно новата
 * версия 1.0.3 на XCD [sic] схемата в сроковете по чл. 71к от ДОПК, считано от <b>01.11.2026
 * г.</b>“ Изменителните заповеди само ЗАМЕНЯТ приложенията — основанието остава първата.
 *
 * <p><b>⚠️ КОЯ ВЕРСИЯ ВАЖИ, РЕШАВА ДАТАТА НА ГЕНЕРИРАНЕ — ТАБЛИЦАТА {@link #VERSIONS}.</b> Оттам
 * идват И стойността на S.H.1, И схемата, срещу която файлът се проверява; двете не могат да се
 * разминат. QA II.2.4 (с. 13): коригиращите файлове „следва да съответстват на XSD-схемата, която е
 * актуална към момента на подаване“ — тоест периодът на файла не решава.
 *
 * <p><b>⚠️ СХЕМАТА НЕ Е ЕДИНСТВЕНИЯТ ПЪРВОИЗТОЧНИК, И ТОВА Е ВАЖНО.</b> XSD-то описва СТРУКТУРАТА.
 * Задължителността по поле, режимът на отчитане (месечно / годишно / при поискване) и семантичните
 * контроли са в <b>Приложение №2</b> (структурната таблица) и <b>Приложение №3</b> (форматът и
 * редът). Трето — кодовете са в номенклатурите, които са неразделна част от схемата. Никое от трите
 * не се гади.
 *
 * <p><b>⚠️ ЦЕЛИ СЕКЦИИ СА ОПЦИОНАЛНИ, ТОЕСТ МИНАЛА XSD ПРОВЕРКА НЕ ЗНАЧИ ПРИЕТ ФАЙЛ.</b> Приложение
 * №3, раздел I.1: „Опционална секции/подсекции/елементи, в които няма попълнени данни, НЕ СЕ
 * ПОДАВАТ с XML файла.“ Схемата приема и празната, и пропуснатата секция; НАП приема само
 * пропуснатата. Втората врата е на семантичния валидатор, не тук.
 *
 * <p><b>⚠️ ОТПЕЧАТЪКЪТ Е ТУК, ЗА ДА СЕ ВИДИ ПОДМЯНА.</b> Файлът в {@code resources/schemas/saft/} е
 * копие; замени ли го някой, без да пипне този клас, {@code BgSaftXsdPinTest} пада с назована
 * причина, вместо мълчаливо да валидира срещу друга схема. Пази се БАЙТ ПО БАЙТ — {@code
 * schemas/.gitattributes} носи {@code *.xsd -text} точно затова.
 *
 * <p><b>⚠️ СХЕМАТА СИ ПРОТИВОРЕЧИ ЗА ВЕРСИЯТА — ЗАТОВА ВЕРСИЯТА НЕ СЕ ЧЕТЕ ОТ НЕЯ.</b> В 1.0.2
 * атрибутът е {@code version="1.0.2"}, а {@code <xs:documentation>} казва „версия 1.0.1“; в 1.0.3
 * атрибутът е ОСТАНАЛ {@code version="1.0.2"}. Заповедите назовават точно тези архиви.
 * Разминаването е в официалния текст и НЕ се поправя тук — поправен, файлът престава да е този,
 * който НАП е публикувал. Версията идва от {@link #VERSIONS}.
 *
 * <p><b>⚠️ СХЕМАТА НОСИ НЕВАЛИДНА ДЕКЛАРАЦИЯ, КОЯТО ДНЕС Е БЕЗОБИДНА.</b> {@code
 * xmlns:doc="urn:schemas-OECD:schema-extensions:documentation xml:lang=en"} е URI с интервали и с
 * {@code xml:lang} вътре. Префиксът {@code doc} никъде не се ползва, затова Xerces на JDK 11 я
 * приема и трите официални еталона валидират с НУЛА находки — измерено, не предположено. Строги
 * инструменти и XJC обаче може да я отхвърлят: генерирането на типове ще иска САНИРАНО КОПИЕ, а
 * валидацията остава срещу ТЕЗИ байтове.
 *
 * <p><b>⚠️ НУЛА МРЕЖА ПО ВРЕМЕ НА РАБОТА.</b> Валидацията е ОФЛАЙН, срещу това копие.
 */
public final class BgSaftXsd {

  private BgSaftXsd() {}

  /** Заповедта, с която форматът и редът са утвърдени — основанието. */
  public static final String ORDER_ORIGINAL = "З-ЦУ-30-1085/25.07.2025 г.";

  /** Първото изменение — схемата и структурата стават 1.0.1. */
  public static final String ORDER_AMENDMENT_1 = "З-ЦУ-30-1247/25.08.2025 г.";

  /** Второто изменение — схемата и структурата стават 1.0.2, от 01.04.2026 г. */
  public static final String ORDER_AMENDMENT_2 = "З-ЦУ-30-359/27.02.2026 г.";

  /**
   * Третото изменение — Приложения №1 и №2 стават 1.0.3, от 01.11.2026 г.; Приложение №3 остава
   * същото. Подписана от изпълнителния директор Борис Михайлов.
   */
  public static final String ORDER_AMENDMENT_3 = "З-ЦУ-30-1797/13.10.2026 г.";

  /** Правното основание на самите заповеди. */
  public static final String LEGAL_BASIS = "ДОПК чл. 71з, формат по чл. 71к ал. 4";

  /** Версията на правилата за формата и реда (Приложение №3) — една и съща за 1.0.2 и 1.0.3. */
  public static final String REPORTING_RULES_VERSION = "1.0.1";

  /**
   * ЕДИН РЕД ОТ ТАБЛИЦАТА — версия на схемата, откога важи и всичко, което пинът сверява.
   *
   * <p><b>⚠️ ВЕРСИЯТА И S.H.1 СА ДВЕ ПОЛЕТА.</b> Днес съвпадат по книгата, но стойността на S.H.1
   * се потвърждава от тестовата е-услуга, а не от името на архива.
   */
  public static final class Version {
    private final String schemaVersion;
    private final String auditFileVersion;
    private final List<String> orders;
    private final LocalDate appliesFrom;
    private final String resource;
    private final long byteLength;
    private final String sha256;
    private final String downloadedOn;

    private Version(
        String schemaVersion,
        String auditFileVersion,
        List<String> orders,
        LocalDate appliesFrom,
        String resource,
        long byteLength,
        String sha256,
        String downloadedOn) {
      this.schemaVersion = schemaVersion;
      this.auditFileVersion = auditFileVersion;
      this.orders = List.copyOf(orders);
      this.appliesFrom = appliesFrom;
      this.resource = resource;
      this.byteLength = byteLength;
      this.sha256 = sha256;
      this.downloadedOn = downloadedOn;
    }

    /** Версията на схемата по заповедта — „1.0.3“. */
    public String getSchemaVersion() {
      return schemaVersion;
    }

    /** Стойността на S.H.1 AuditFileVersion във файлите по тази версия. */
    public String getAuditFileVersion() {
      return auditFileVersion;
    }

    /** Заповедите, от първата до тази, която въвежда версията. */
    public List<String> getOrders() {
      return orders;
    }

    /** От коя дата на генериране (подаване) важи — дословно от заповедта. */
    public LocalDate getAppliesFrom() {
      return appliesFrom;
    }

    /** Пътят в classpath-а до копието на схемата. */
    public String getResource() {
      return resource;
    }

    /** Точният размер на закрепения файл в байтове. */
    public long getByteLength() {
      return byteLength;
    }

    /** SHA-256 на закрепения файл — сверява се от {@code BgSaftXsdPinTest}. */
    public String getSha256() {
      return sha256;
    }

    /** Датата, на която пакетът е свален. */
    public String getDownloadedOn() {
      return downloadedOn;
    }

    /** Едноредово описание за доклада и за екрана: срещу КОЯ схема е валидиран файлът. */
    public String describe() {
      return "SAF-T BG v"
          + schemaVersion
          + " (заповед "
          + String.join(", изм. ", orders)
          + ", в сила от "
          + appliesFrom.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
          + " г., свалена "
          + downloadedOn
          + ", sha256 "
          + sha256.substring(0, 12)
          + "…)";
    }

    @Override
    public String toString() {
      return "SAF-T BG v" + schemaVersion;
    }
  }

  /**
   * 1.0.2 — от 01.04.2026 г. (З-ЦУ-30-359) до 31.10.2026 г.
   *
   * <p>⚠️ S.H.1 „1.0.2“ е по книгата (S.H.1: „Попълва се съответната версия на спецификацията на
   * SAF-T за България.“). <b>Потвърдено</b> в тестовата е-услуга на 17.10.2026: протокол
   * 30TST000014745 — „1.0.2“ ПРИЕТ; 30TST000014746 — „1.0.3“ ОТКАЗАН („не отговаря на последната
   * утвърдена версия“), тоест порталът сверява към датата на подаване. „007“ от месечния еталон е
   * отхвърлена с протокол 30TST000014738.
   */
  public static final Version V1_0_2 =
      new Version(
          "1.0.2",
          "1.0.2",
          List.of(ORDER_ORIGINAL, ORDER_AMENDMENT_1, ORDER_AMENDMENT_2),
          LocalDate.of(2026, 4, 1),
          "/schemas/saft/BG_SAFT_Schema_V_1.0.2.xsd",
          251559L,
          "2bd5fb13072c92b221bee2bea46c4f71c1619c38977e300bc7ac1e75c439cfc7",
          "2026-09-20");

  /**
   * 1.0.3 — от 01.11.2026 г. (З-ЦУ-30-1797). Новият годишен еталон носи S.H.1 „1.0.3“; за месечния
   * файл версията само облекчава (InvoiceSettlement 0..n, ContactPerson по избор).
   */
  public static final Version V1_0_3 =
      new Version(
          "1.0.3",
          "1.0.3",
          List.of(ORDER_ORIGINAL, ORDER_AMENDMENT_1, ORDER_AMENDMENT_2, ORDER_AMENDMENT_3),
          LocalDate.of(2026, 11, 1),
          "/schemas/saft/BG_SAFT_Schema_V_1.0.3.xsd",
          232985L,
          "a152c5d02f359d7d1c132121d82db63492eed23cf0475b85f27069a2074d188f",
          "2026-10-17");

  /** Таблицата „версия → от дата“, по възходящ ред на датите. */
  public static final List<Version> VERSIONS = List.of(V1_0_2, V1_0_3);

  /**
   * Версията за файл, генериран на {@code generatedOn}: последният ред, който вече важи.
   *
   * <p>Преди 01.04.2026 г. няма подаване по нито една от закрепените версии — файл с такава дата
   * получава най-ранната, за да се генерира изобщо (тестове, история).
   */
  public static Version forDate(LocalDate generatedOn) {
    if (generatedOn == null) {
      throw new IllegalArgumentException("SAF-T: версията се избира по дата на генериране");
    }
    Version current = VERSIONS.get(0);
    for (Version v : VERSIONS) {
      if (!v.getAppliesFrom().isAfter(generatedOn)) {
        current = v;
      }
    }
    return current;
  }

  /** Кореновият елемент, който схемата описва. */
  public static final String ROOT_ELEMENT = "AuditFile";

  /**
   * Целевото пространство от имена на схемата.
   *
   * <p><b>⚠️ „dxxxx“ Е ДОСЛОВНО, А НЕ ШАБЛОН ЗА ПОПЪЛВАНЕ.</b> Изглежда като запазено място, което
   * някой е забравил да замени, и точно затова тук стои изрично: така е публикувано и така се
   * очаква във файла. „Поправен“ на нещо смислено, документът престава да отговаря на схемата и НАП
   * го отхвърля.
   */
  public static final String NAMESPACE = "mf:nra:dgti:dxxxx:declaration:v1";

  /**
   * Префиксът, с който трите официални еталона пишат елементите — {@code <nsSAFT:AuditFile>}.
   *
   * <p>⚠️ Схемата е {@code elementFormDefault="qualified"}, тоест ВСЕКИ елемент носи
   * пространството. Самият префикс е свободен по XML, но еталоните са единственото, срещу което се
   * сверяваме на око — разминаване тук прави сравнението с тях безполезно за човек.
   */
  public static final String NAMESPACE_PREFIX = "nsSAFT";

  /**
   * Кодировката по Приложение №3, раздел I.2, дословно: „Формат на файла: XML. Данните се
   * предоставят в UTF-8 кодова таблица.“
   *
   * <p><b>⚠️ И ТОВА Е ОБРАТНОТО НА ОСТАНАЛИТЕ ФАЙЛОВЕ КЪМ НАП.</b> ДДС дневниците, справката по чл.
   * 73 и ЕТЗ са CP1251. Заета наум оттам, кодировката тук би дала файл, който изглежда
   * правдоподобен и се разпада на кирилицата чак в приемащата система.
   */
  public static final String ENCODING = "UTF-8";

  /** Префиксът на кодовете за грешка на този формат — виж {@code BgXsdValidator}. */
  public static final String CODE_PREFIX = "SAFT";

  /**
   * Имената на файловете по Приложение №3, раздел I.2.
   *
   * <p>⚠️ Шаблонът в приложението е с ДЕВЕТ позиции за ЕИК ({@code XXXXXXXXX}). 13-цифреният номер
   * по БУЛСТАТ е на клон, а файлът е на търговеца и включва клоновете (QA II.1.10) — {@code
   * BgSaftFileName} го отказва с грешка. Файл с име, което не отговаря на конвенцията, се ОТХВЪРЛЯ
   * (раздел II).
   */
  public static final String FILE_NAME_MONTHLY = "<ЕИК>_MM_YYYY.xml";

  /** Годишният файл — наличности и движение на дълготрайни активи. */
  public static final String FILE_NAME_ANNUAL = "<ЕИК>_YYYY.xml";

  /** Файлът при поискване от орган по приходите — период от / до. */
  public static final String FILE_NAME_ON_DEMAND = "<ЕИК>_DD_MM_YYYY_DD_MM_YYYY.xml";
}
