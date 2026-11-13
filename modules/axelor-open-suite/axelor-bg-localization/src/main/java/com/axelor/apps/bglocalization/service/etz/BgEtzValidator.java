package com.axelor.apps.bglocalization.service.etz;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * ОФЛАЙН ПРОВЕРКАТА СРЕЩУ ЗАКРЕПЕНАТА СХЕМА — реална, детерминистична, нула мрежа.
 *
 * <p>Във Фаза 1 тук стоеше стъб, който отказваше. Схемите са набавени и проверката е истинска.
 *
 * <p><b>⚠️ ЧЕТЕ БАЙТОВЕ, А НЕ НИЗ, И ТОВА НЕ Е ДЕТАЙЛ.</b> Файлът е в WINDOWS-1251 и НАП проверява
 * поотделно декларацията ({@code API_XML_ENCODING_HEADER}) и байтовете ({@code
 * API_XML_ENCODING_FILE}). Низ, прекодиран към UTF-8 преди проверката, би скрил точно тази грешка —
 * парсерът чете декларацията от самите байтове и така разминаването пада ТУК, а не при НАП.
 *
 * <p><b>⚠️ ВТВЪРДЯВАНЕТО Е ПРЕЗ ЧЕТЕЦА, НЕ ПРЕЗ {@code setProperty}.</b> Измерено при НОИ и
 * пренесено: {@code Validator} на този билд отхвърля {@code ACCESS_EXTERNAL_DTD} с „Property … is
 * not recognized“, изключението пада В СРЕДАТА на валидацията и напълно валиден файл изглежда като
 * файл с грешка. Забраната върви през {@code disallow-doctype-decl} на {@code SAXParserFactory} —
 * поддържа се навсякъде и е по-силна.
 *
 * <p><b>⚠️ „0 ГРЕШКИ“ ТУК НЕ ЗНАЧИ „НАП ЩЕ ГО ПРИЕМЕ“.</b> В {@code etz_employ_restrict.xsd} 24 от
 * 33 полета са {@code minOccurs="0"} — запис само със структурно задължителните минава схемата.
 * Пълнотата се пази ПРЕДИ емисията, от {@link BgEtzMandatory}; тук се пази ФОРМАТЪТ.
 */
public class BgEtzValidator {

  /** Максималният брой съобщения от схемата, които влизат в отчета. */
  private static final int MAX_SCHEMA_ERRORS = 50;

  /**
   * Проверява подготвените байтове срещу схемата на събитието.
   *
   * @param xml байтовете, както биха били подадени — WINDOWS-1251, не низ
   * @param schema коя от двете схеми
   */
  public List<BgEtzFinding> validate(byte[] xml, BgEtzXsd.Schema schema) {
    List<BgEtzFinding> findings = new ArrayList<>();
    if (schema == null) {
      findings.add(BgEtzFinding.gate("схема", "не е посочено срещу коя схема се проверява"));
      return findings;
    }
    if (xml == null || xml.length == 0) {
      findings.add(BgEtzFinding.schema("файл", "няма съдържание за проверка"));
      return findings;
    }

    try (InputStream xsd = BgEtzValidator.class.getResourceAsStream(schema.getResource())) {
      if (xsd == null) {
        // ⚠️ ОТКАЗ, А НЕ ПРАЗЕН СПИСЪК. Липсваща схема и „няма грешки“ не бива да изглеждат
        // еднакво — това е разликата, заради която този клас съществуваше и във Фаза 1.
        findings.add(
            BgEtzFinding.gate(
                schema.getResource(),
                "схемата не е в classpath-а — без нея проверката е безсмислена и НЕ се приема за"
                    + " минала"));
        return findings;
      }
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Schema compiled = factory.newSchema(new StreamSource(xsd));

      Validator validator = compiled.newValidator();
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

      final List<BgEtzFinding> collected = findings;
      validator.setErrorHandler(
          new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
              add(collected, e, "предупреждение");
            }

            @Override
            public void error(SAXParseException e) {
              add(collected, e, "грешка");
            }

            @Override
            public void fatalError(SAXParseException e) {
              add(collected, e, "фатална грешка");
            }
          });
      validator.validate(hardenedSource(xml));

    } catch (Exception e) {
      findings.add(
          BgEtzFinding.gate(
              "валидация",
              "проверката срещу " + schema.describe() + " не завърши: " + e.getMessage()));
    }
    return findings;
  }

  /** Проверка на цял сглобен файл — схемата се взема от събитието, не се подава наум. */
  public List<BgEtzFinding> validate(byte[] xml, BgEtzFile file) {
    return validate(xml, file == null ? null : file.getSchema());
  }

  /**
   * ВХОДЪТ СЕ ЧЕТЕ ОТ ВТВЪРДЕН ЧЕТЕЦ — без DTD, без външни обекти, без мрежа.
   *
   * <p>⚠️ Подава се {@code InputSource} върху БАЙТОВЕТЕ, без да се назовава кодировка: парсерът я
   * чете от XML декларацията. Точно затова декларация, която не отговаря на байтовете, пада тук.
   */
  private static SAXSource hardenedSource(byte[] xml) throws Exception {
    SAXParserFactory parsers = SAXParserFactory.newInstance();
    parsers.setNamespaceAware(true);
    parsers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    parsers.setFeature("http://xml.org/sax/features/external-general-entities", false);
    parsers.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    XMLReader reader = parsers.newSAXParser().getXMLReader();
    return new SAXSource(reader, new InputSource(new ByteArrayInputStream(xml)));
  }

  private static void add(List<BgEtzFinding> findings, SAXParseException e, String kind) {
    if (findings.size() >= MAX_SCHEMA_ERRORS) {
      return;
    }
    findings.add(BgEtzFinding.schema("ред " + e.getLineNumber(), kind + ": " + e.getMessage()));
  }

  /** Дали резултатът съдържа отказ на гейта — тоест проверката НЕ е извършена. */
  public static boolean isBlocked(List<BgEtzFinding> findings) {
    if (findings == null) {
      return true;
    }
    for (BgEtzFinding finding : findings) {
      if (finding.getSource() == BgEtzFinding.Source.GATE) {
        return true;
      }
    }
    return false;
  }

  /** Дали има каквато и да е констатация — гейт или структурна грешка. */
  public static boolean hasFindings(List<BgEtzFinding> findings) {
    return findings != null && !findings.isEmpty();
  }
}
