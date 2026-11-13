package com.axelor.apps.bglocalization.service.payment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * СВЕРЯВА ГОТОВИЯ ФАЙЛ СРЕЩУ ОФИЦИАЛНАТА СХЕМА — ПРЕДИ ЧОВЕК ДА ГО КАЧИ В БАНКАТА.
 *
 * <p><b>⚠️ JAXB МАРШАЛИРА БЕЗ ДА ВАЛИДИРА, И ТОВА Е ИЗМЕРЕНО, НЕ ПРЕДПОЛОЖЕНО.</b> {@code
 * MarshallingHelper#marshallFile} създава {@code Marshaller} и задава само {@code
 * jaxb.formatted.output} — не му се подава {@code Schema}. Тоест поле, което схемата ограничава по
 * дължина или по образец (името на получателя е {@code Max140Text}), излиза във файла както си е и
 * се отхвърля чак от банката — часове по-късно, от друг човек, без съобщение, което да сочи ред.
 *
 * <p><b>⚠️ СХЕМАТА СЕ ЧЕТЕ ОТ CLASSPATH-А НА AXELOR-BANK-PAYMENT, А НЕ СЕ КОПИРА ТУК.</b> Тя е
 * пакетирана в jar-а на модула на път {@code /xsd/sepa/pain.001.001.03.xsd} и е САМОСТОЯТЕЛНА —
 * нула {@code xs:import} и {@code xs:include}, тоест резолвър за външни ресурси не трябва. Второ
 * копие тук щеше да е второ място за сверяване, което се разминава при първото вдигане на upstream.
 *
 * <p><b>⚠️ „ЧИСТО“ И „НЕ СЪМ ПРОВЕРЯВАЛ“ СА ДВА РАЗЛИЧНИ ОТГОВОРА, И ТОВА Е ПЛАТЕНО С ДЕФЕКТ.</b>
 * Първата версия на този клас връщаше {@code null} и при двете, а при неуспех да построи схемата
 * ГЪЛТАШЕ причината. Ефектът беше точно обратният на целта: {@code SchemaFactory#setProperty} за
 * {@code ACCESS_EXTERNAL_DTD} хвърля {@code SAXNotRecognizedException} при Xerces-а на този
 * classpath — тоест сверяването не се случваше НИКОГА, а докладът пишеше „0 грешки“. Хванато е от
 * {@code BgPayrollSepaFileTest#tooLongNameIsCaughtByTheSchema}: тест, който нарочно подава
 * невалиден файл и иска той да ПАДНЕ. Оттам и {@link Result}: трите състояния не се събират в едно
 * поле.
 */
public final class BgSepaXsdValidator {

  /** Броят показани грешки — достатъчно, за да се разбере дали е една, или е всичко. */
  private static final int MAX_REPORTED = 10;

  private BgSepaXsdValidator() {}

  /**
   * ТРИТЕ ВЪЗМОЖНИ ОТГОВОРА, РАЗДЕЛЕНИ.
   *
   * <p>Чисто (сверено, нула несъответствия) · с несъответствия (сверено, ето кои) · непроверено
   * (схемата не можа да се построи и ето защо). Третото НЕ е отказ — файлът вече е генериран от
   * upstream и спирането на заплати заради липсващ ресурс за проверка е по-лошо от това, което
   * решава, — но и не бива да се чете като първото.
   */
  public static final class Result {

    private final String problems;
    private final String notCheckedReason;

    private Result(String problems, String notCheckedReason) {
      this.problems = problems;
      this.notCheckedReason = notCheckedReason;
    }

    /** Сверено и без нито едно несъответствие. */
    public boolean isClean() {
      return problems == null && notCheckedReason == null;
    }

    /** Текстът на несъответствията, или {@code null}, когато няма такива. */
    public String getProblems() {
      return problems;
    }

    /** Защо сверяване не е имало, или {@code null}, когато е имало. */
    public String getNotCheckedReason() {
      return notCheckedReason;
    }
  }

  public static Result validate(File file) {
    if (file == null || !file.isFile()) {
      return new Result(
          "Файлът не е намерен там, където генераторът твърди, че го е записал.", null);
    }

    Schema schema;
    try (InputStream xsd =
        BgSepaXsdValidator.class.getResourceAsStream(BgPayrollSepaRules.XSD_RESOURCE)) {
      if (xsd == null) {
        return new Result(
            null,
            "схемата не е намерена на classpath-а ("
                + BgPayrollSepaRules.XSD_RESOURCE
                + "). Тя се пакетира от axelor-bank-payment.");
      }
      schema = factory().newSchema(new StreamSource(xsd));
    } catch (SAXException | IOException e) {
      return new Result(null, "схемата не можа да бъде построена: " + e.getMessage());
    }

    Collector collector = new Collector();
    try {
      Validator validator = schema.newValidator();
      validator.setErrorHandler(collector);
      validator.validate(new StreamSource(file));
    } catch (SAXException | IOException e) {
      collector.problems.add(e.getMessage());
    }
    if (collector.problems.isEmpty()) {
      return new Result(null, null);
    }
    return new Result(describe(collector.problems), null);
  }

  /**
   * ЗАТВАРЯНЕТО НА ВЪНШНИЯ ДОСТЪП Е ОПИТ, А НЕ УСЛОВИЕ.
   *
   * <p><b>⚠️ ИЗМЕРЕНО:</b> Xerces-ът на този classpath НЕ разпознава двете свойства и хвърля {@code
   * SAXNotRecognizedException}. Задължителни, те повалят построяването на схемата — тоест
   * сверяването изчезва заради мярка срещу риск, който тук не съществува: схемата е ЛОКАЛЕН ресурс
   * от jar (не потребителски вход) и няма нито един {@code xs:import} или {@code xs:include}, тоест
   * няма какво външно да бъде поискано. Затова се опитват и се подминават.
   */
  private static SchemaFactory factory() {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    harden(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
    harden(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
    return factory;
  }

  private static void harden(SchemaFactory factory, String property) {
    try {
      factory.setProperty(property, "");
    } catch (SAXException e) {
      // Парсерът не познава свойството — виж коментара на factory().
    }
  }

  private static String describe(List<String> problems) {
    StringBuilder text = new StringBuilder();
    text.append("Генерираният файл НЕ отговаря на схемата pain.001.001.03 (")
        .append(problems.size())
        .append(problems.size() == 1 ? " несъответствие" : " несъответствия")
        .append("). Файл в това състояние се отхвърля от банката.\n");
    int shown = 0;
    for (String problem : problems) {
      if (shown++ == MAX_REPORTED) {
        text.append("\n… и още ").append(problems.size() - MAX_REPORTED).append(".");
        break;
      }
      text.append("\n• ").append(problem);
    }
    return text.toString();
  }

  /** Събира ВСИЧКИ несъответствия, а не спира на първото. */
  private static final class Collector implements ErrorHandler {

    private final List<String> problems = new ArrayList<>();

    @Override
    public void warning(SAXParseException exception) {
      // Предупрежденията на схемата не са несъответствия и не спират плащане.
    }

    @Override
    public void error(SAXParseException exception) {
      problems.add(describe(exception));
    }

    @Override
    public void fatalError(SAXParseException exception) {
      problems.add(describe(exception));
    }

    private static String describe(SAXParseException exception) {
      return "ред " + exception.getLineNumber() + ": " + exception.getMessage();
    }
  }
}
