package com.axelor.apps.bglocalization.service.etz;

/**
 * ДВЕТЕ СХЕМИ НА НАП — <b>откъде са, коя е коя, и отпечатък срещу подмяна</b>.
 *
 * <p>Огледален клас на {@code BgApp9Xsd}. Във Фаза 1 тук стоеше обявена празнота; схемите вече са
 * набавени и закрепени, а {@code BgEtzXsdPinTest} сверява отпечатъците им.
 *
 * <p><b>⚠️ ДВЕ СХЕМИ, ДВА КОРЕНА, ДВА ТИПА ДОКУМЕНТ.</b> Те НЕ са варианти на едно: {@code
 * etzemploy} носи сключване/изменение/прекратяване и има 33 полета; {@code etz123employer} носи
 * промяната на работодател и има 14. Разликата не е само в обема — при чл. 123 записът има ДВА ЕИК
 * ({@code formereik} и {@code eik}), защото правоотношението се ЗАПАЗВА и се сменя страната по
 * него.
 *
 * <p><b>⚠️ ФАЙЛОВЕТЕ СА В WINDOWS-1251 И С CRLF, И ТОВА ТРЯБВА ДА ОЦЕЛЕЕ В GIT.</b> Кореновият
 * {@code .gitattributes} носи {@code * text=auto eol=lf}; без изключение схемите биха се записали с
 * LF и отпечатъците отдолу биха паднали — но чак в CI, не при този, който ги е сложил. Изключението
 * е {@code schemas/.gitattributes} ({@code *.xsd -text}) и покрива и тази подпапка. Проверено с
 * {@code git check-attr} и {@code git checkout-index}, не по работното копие.
 *
 * <p><b>⚠️ СХЕМАТА НЕ Е ЕДИНСТВЕНИЯТ ПЪРВОИЗТОЧНИК — И ТУК РАЗЛИКАТА Е ПО-ГОЛЯМА ОТКОЛКОТО ПРИ
 * НОИ.</b> Схемата казва, че почти всичко е {@code minOccurs="0"}; истинската задължителност е
 * УСЛОВНА и живее в Приложения № 1 и № 2 към Заповед {@value #ORDER_NUMBER} ({@link
 * BgEtzMandatory}), а НОМЕНКЛАТУРИТЕ на полета 12 и 27 — в самата Наредба ({@link BgEtzGrounds}).
 * Тоест файл, валиден срещу схемата, спокойно може да е непълен или да носи несъществуващ код.
 *
 * <p><b>⚠️ И ЗАТОВА „0 грешки от XSD“ НЕ Е „НАП ЩЕ ГО ПРИЕМЕ“.</b> Редът е: задължителността се
 * пази ПРЕДИ емисията ({@link BgEtzMandatory}), кодовете — при сглобяването ({@link BgEtzGrounds}),
 * а схемата проверява формата НАКРАЯ.
 */
public final class BgEtzXsd {

  private BgEtzXsd() {}

  /** Страницата на НАП, от която са свалени схемите и описанията. */
  public static final String SOURCE_URL =
      "https://nra.bg/wps/portal/nra/Programni-produkti/Specifikaciq-za-podavane-na-danni/"
          + "elektronen%20trudov%20zapis";

  /** Заповедта, към която са Приложения № 1 и № 2 (описанията на структурата). */
  public static final String ORDER_NUMBER = "З-ЦУ-30-1072/22.06.2026";

  /**
   * Наредбата — носителят на номенклатурите за полета 12, 13 и 27. Сверена 02.11.2026 срещу
   * консолидирания текст на НАП към 01.06.2026 (ПМС № 67/11.06.2026, ДВ бр. 54 от 2026 г.).
   */
  public static final String ORDINANCE =
      "Наредба за вписване в регистъра на заетостта (ред. ДВ бр. 54 от 2026 г.)";

  /** Датата, на която схемите са свалени и сверени. */
  public static final String DOWNLOADED_ON = "2026-09-12";

  /**
   * Едната от двете схеми — заедно с корена, пътя и отпечатъка ѝ.
   *
   * <p>Държани в изброяване, а не в две двойки константи, за да не може валидаторът да вземе пътя
   * на едната и корена на другата.
   */
  public enum Schema {

    /** {@code DEC_ETZ62} — сключване, изменение и прекратяване. Приложение № 1 към чл. 8 т. 1. */
    EMPLOY(
        "/schemas/etz/etz_employ_restrict.xsd",
        "etzemploy",
        14769L,
        "2e3a74621be01f909d14535bd3039c34f4940a64d998a132e829019e885f4c80",
        "Приложение № 1"),

    /** {@code DEC_ETZ123} — промяна на работодател по чл. 10 ал. 5. Приложение № 2. */
    EMPLOYER_CHANGE(
        "/schemas/etz/etz_123_employer.xsd",
        "etz123employer",
        7135L,
        "87341522733522032e8bb621f3563dabefad5efa30a20e329316f5bfc42e393c",
        "Приложение № 2");

    private final String resource;
    private final String rootElement;
    private final long byteLength;
    private final String sha256;
    private final String appendix;

    Schema(String resource, String rootElement, long byteLength, String sha256, String appendix) {
      this.resource = resource;
      this.rootElement = rootElement;
      this.byteLength = byteLength;
      this.sha256 = sha256;
      this.appendix = appendix;
    }

    /** Пътят в classpath-а до закрепеното копие. */
    public String getResource() {
      return resource;
    }

    /** Кореновият елемент, който схемата описва. */
    public String getRootElement() {
      return rootElement;
    }

    /** Точният размер в байтове — сверява се от пин-теста. */
    public long getByteLength() {
      return byteLength;
    }

    /** SHA-256 на закрепения файл. */
    public String getSha256() {
      return sha256;
    }

    /** Кое приложение описва задължителността за тази схема. */
    public String getAppendix() {
      return appendix;
    }

    /** Едноредово описание за отчета. */
    public String describe() {
      return rootElement
          + " ("
          + resource.substring(resource.lastIndexOf('/') + 1)
          + ", sha256 "
          + sha256.substring(0, 12)
          + "…, свалена "
          + DOWNLOADED_ON
          + ") · "
          + appendix
          + " към Заповед "
          + ORDER_NUMBER;
    }
  }

  /** Схемата за едно събитие. */
  public static Schema forEvent(BgEtzEventKind kind) {
    if (kind == null) {
      throw new IllegalArgumentException(
          "няма вид събитие — без него не се знае коя схема се ползва");
    }
    return kind == BgEtzEventKind.EMPLOYER_CHANGE ? Schema.EMPLOYER_CHANGE : Schema.EMPLOY;
  }

  /** Описание за отчета — и двете схеми. */
  public static String describe() {
    return Schema.EMPLOY.describe() + " · " + Schema.EMPLOYER_CHANGE.describe();
  }
}
