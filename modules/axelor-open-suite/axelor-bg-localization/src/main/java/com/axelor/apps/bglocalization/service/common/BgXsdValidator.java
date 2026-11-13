package com.axelor.apps.bglocalization.service.common;

import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * СТРУКТУРНАТА ПРОВЕРКА СРЕЩУ ЗАКОВАНА СХЕМА — офлайн, за всеки формат на НАП/НОИ.
 *
 * <p><b>⚠️ ЕДНА РЕАЛИЗАЦИЯ ЗА ВСИЧКИ СХЕМИ, А НЕ ПО ЕДИН КЛАС НА ЗАПОВЕД.</b> {@code dec731},
 * {@code dec736} и SAF-T са различни схеми с различни заповеди, но проверката е ЕДНА И СЪЩА: същото
 * втвърдяване, същият събирач на грешки, същият таван. Копирана за всеки формат, тя щеше да се
 * разминава при първата поправка на сигурността — и то МЪЛЧАЛИВО, защото всички копия връщат празен
 * списък, когато всичко е наред.
 *
 * <p><b>⚠️ КОДЪТ НА ГРЕШКАТА НОСИ ПРЕФИКС НА ФОРМАТА, ЗАЩОТО СТИГА ДО ЕКРАНА.</b> Находките се
 * записват като {@code SaftValidationIssue} / ред в доклада и човекът ги чете. Закован „ART73“ в
 * доклад за SAF-T не е козметичен проблем — той твърди грешен произход на грешката и праща
 * търсенето в грешната заповед.
 *
 * <p><b>⚠️ ВАЛИДИРА БАЙТОВЕТЕ, А НЕ НИЗА.</b> Декларацията на файла сама казва как се чете ({@code
 * WINDOWS-1251} за чл. 73 и ЕТЗ, {@code UTF-8} за SAF-T). Подаден като {@code String} и прекодиран
 * наум, документът щеше да се чете с грешна кодировка и кирилицата да се разпадне — тоест
 * проверката щеше да пада по причина, която файлът няма. Подават се ТОЧНО байтовете, които отиват
 * към приемащата система.
 *
 * <p><b>⚠️ СХЕМАТА Е СЛАБ ПАЗАЧ — И ТОВА НЕ Е НЕЙН ДЕФЕКТ.</b> В {@code dec731} почти всеки елемент
 * на реда е {@code minOccurs="0"}; в SAF-T цели секции са опционални. Файл, който минава
 * структурно, приемащата система пак може да отхвърли. Задължителността по поле и кръстосаните
 * контроли са в ЗАПОВЕДТА и се пазят от отделен валидатор. Затова минала XSD проверка НЕ значи
 * приет файл, и двете врати се минават една след друга.
 *
 * <p><b>⚠️ ФАБРИКИТЕ СА ВГРАДЕНИТЕ В JDK, НЕ ТЕЗИ ОТ CLASSPATH-А.</b> На classpath-а има Apache
 * Xerces и {@code newInstance()} връща него — а той НЕ разпознава {@code ACCESS_EXTERNAL_DTD} /
 * {@code ACCESS_EXTERNAL_SCHEMA} (виж {@code BgSepaXsdValidator}) и при {@code xsd:import} към
 * {@code http:}/{@code file:} би изтеглил ресурса. {@code newDefaultInstance()} дава реализацията
 * на JDK, която ги поддържа: тук и двете са празни, т.е. външен достъп няма; {@code
 * FEATURE_SECURE_PROCESSING} е включен. Съседните схеми идват само от {@link #siblings}.
 *
 * <p><b>⚠️ ДОКУМЕНТ С DOCTYPE СЕ ОТКАЗВА</b> — и вътрешни, и външни обекти. Днес се проверяват само
 * наши, генерирани байтове (SAF-T, чл. 73, FATCA); отговорите на НАП не минават оттук. Отказът е за
 * случая, в който някой ден минат.
 *
 * <p><b>⚠️ НУЛА МРЕЖА ПО ВРЕМЕ НА РАБОТА.</b> Валидацията е ОФЛАЙН, срещу закованото копие в
 * classpath-а.
 */
public final class BgXsdValidator {

  /** Схемата я няма в classpath-а — проверката НЕ се е случила. */
  public static final String SUFFIX_SCHEMA_MISSING = "_SCHEMA_MISSING";

  /** Схемата е намерена и документът не ѝ отговаря. */
  public static final String SUFFIX_SCHEMA_ERROR = "_SCHEMA_ERROR";

  /** Проверката не е завършила — това НЕ е „чисто“. */
  public static final String SUFFIX_SCHEMA_CHECK_FAILED = "_SCHEMA_CHECK_FAILED";

  /** Повече от толкова находки не носят информация — първите сочат причината. */
  private static final int MAX_ERRORS = 20;

  /** Съседна схема: голо име на файл {@code .xsd} — без схема, път и обратни наклонени черти. */
  static final Pattern SIBLING = Pattern.compile("[A-Za-z0-9._-]+\\.xsd");

  private BgXsdValidator() {}

  /** Кодът за „схемата липсва“ за даден формат, напр. {@code SAFT_SCHEMA_MISSING}. */
  public static String schemaMissingCode(String codePrefix) {
    return codePrefix + SUFFIX_SCHEMA_MISSING;
  }

  /** Кодът за „документът не отговаря на схемата“ за даден формат. */
  public static String schemaErrorCode(String codePrefix) {
    return codePrefix + SUFFIX_SCHEMA_ERROR;
  }

  /** Кодът за „проверката не завърши“ за даден формат. */
  public static String schemaCheckFailedCode(String codePrefix) {
    return codePrefix + SUFFIX_SCHEMA_CHECK_FAILED;
  }

  /**
   * Структурните находки за подадените байтове срещу посочената схема; празен списък значи „минава
   * схемата“ — и НИЩО повече, виж класовия javadoc.
   *
   * @param xml байтовете, които тръгват към приемащата система — НЕ низ
   * @param resource пътят в classpath-а до схемата (напр. {@code BgSaftXsd.Version#getResource})
   * @param describe едноредовото описание на схемата за съобщенията при отказ
   * @param codePrefix префиксът на кодовете за грешка за този формат, напр. {@code SAFT}
   */
  public static List<BgDeclarationFileFinding> validate(
      byte[] xml, String resource, String describe, String codePrefix) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (xml == null || xml.length == 0) {
      findings.add(
          BgDeclarationFileFinding.error(
              schemaCheckFailedCode(codePrefix), "Няма съдържание за проверка."));
      return findings;
    }
    try (InputStream xsd = BgXsdValidator.class.getResourceAsStream(resource)) {
      if (xsd == null) {
        findings.add(
            BgDeclarationFileFinding.error(
                schemaMissingCode(codePrefix),
                "Схемата "
                    + resource
                    + " не е в classpath-а — без нея проверката е безсмислена и НЕ се приема за"
                    + " минала."));
        return findings;
      }
      SchemaFactory factory = schemaFactory();
      factory.setResourceResolver(siblings(resource));
      Schema schema = factory.newSchema(new StreamSource(xsd, fileName(resource)));

      Validator validator = schema.newValidator();
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.setErrorHandler(collector(findings, codePrefix));
      validator.validate(hardenedSource(xml));

    } catch (Exception e) {
      findings.add(
          BgDeclarationFileFinding.error(
              schemaCheckFailedCode(codePrefix),
              "Проверката срещу " + describe + " не завърши: " + e.getMessage()));
    }
    return findings;
  }

  /**
   * ⚠️ И предупрежденията се събират като ГРЕШКИ. Файл, който „не отговаря на файловия формат“, се
   * отхвърля ЦЕЛИЯТ — заповедта по чл. 73, т. 3, и Приложение №3 към заповедите за SAF-T, раздел
   * II. Разделение на тежести тук би подсказало, че част от структурните забележки са поносими.
   */
  private static ErrorHandler collector(
      List<BgDeclarationFileFinding> findings, String codePrefix) {
    return new ErrorHandler() {
      @Override
      public void warning(SAXParseException e) {
        add(findings, e, "предупреждение", codePrefix);
      }

      @Override
      public void error(SAXParseException e) {
        add(findings, e, "грешка", codePrefix);
      }

      @Override
      public void fatalError(SAXParseException e) {
        add(findings, e, "фатална грешка", codePrefix);
      }
    };
  }

  /**
   * ФАБРИКАТА НА СХЕМИТЕ: реализацията на JDK, {@code FEATURE_SECURE_PROCESSING}, без външен достъп
   * до DTD и до схеми. Отделена, за да се провери с тест (без резолвъра).
   */
  static SchemaFactory schemaFactory() throws SAXException {
    SchemaFactory factory = SchemaFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  /**
   * СЪСЕДНИТЕ СХЕМИ ОТ CLASSPATH-А — за {@code xsd:import} на схема, разделена на няколко файла
   * (FATCA: основната схема внася {@code stffatcatypes}, {@code isofatcatypes} и {@code
   * oecdtypes}).
   *
   * <p><b>⚠️ САМО ГОЛО ИМЕ НА {@code .xsd}, САМО ОТ СЪЩАТА ПАПКА.</b> {@code schemaLocation} трябва
   * да е точно име на файл ({@link #SIBLING}) — без схема ({@code http:}, {@code https:}, {@code
   * file:}), без път и без {@code ../}. Всичко друго, както и липсващ файл, е ОТКАЗ с изключение —
   * не {@code null}, за да не опита парсерът сам.
   */
  static LSResourceResolver siblings(String resource) {
    String dir = resource.substring(0, resource.lastIndexOf('/') + 1);
    DOMImplementationLS ls = domLs();
    return (type, namespace, publicId, systemId, baseUri) -> {
      if (systemId == null || !SIBLING.matcher(systemId).matches() || systemId.contains("..")) {
        throw new IllegalStateException(
            "Схемата иска ресурс извън своята папка — отказано: " + systemId);
      }
      InputStream in = BgXsdValidator.class.getResourceAsStream(dir + systemId);
      if (in == null) {
        throw new IllegalStateException(
            "Съседната схема " + systemId + " липсва до " + resource + " — отказано.");
      }
      LSInput input = ls.createLSInput();
      input.setByteStream(in);
      input.setSystemId(systemId);
      return input;
    };
  }

  /** Стандартната DOM реализация на JDK — от нея идва {@code LSInput} за резолвъра. */
  private static DOMImplementationLS domLs() {
    try {
      return (DOMImplementationLS)
          DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().getDOMImplementation();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("Няма DOM реализация за схемите: " + e.getMessage(), e);
    }
  }

  /** Последният сегмент на път или URI. */
  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  /** Четец без DTD, без външни обекти и без мрежа — виж класовия javadoc. */
  private static SAXSource hardenedSource(byte[] xml) throws Exception {
    SAXParserFactory parsers = SAXParserFactory.newDefaultInstance();
    parsers.setNamespaceAware(true);
    parsers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    parsers.setFeature("http://xml.org/sax/features/external-general-entities", false);
    parsers.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    XMLReader reader = parsers.newSAXParser().getXMLReader();
    return new SAXSource(reader, new InputSource(new ByteArrayInputStream(xml)));
  }

  private static void add(
      List<BgDeclarationFileFinding> findings,
      SAXParseException e,
      String kind,
      String codePrefix) {
    if (findings.size() >= MAX_ERRORS) {
      return;
    }
    findings.add(
        BgDeclarationFileFinding.error(
            schemaErrorCode(codePrefix),
            "Схема (" + kind + "), ред " + e.getLineNumber() + ": " + e.getMessage()));
  }
}
