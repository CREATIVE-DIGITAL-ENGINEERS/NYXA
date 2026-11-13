package com.axelor.apps.bglocalization.service.fatca.adapter;

import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent.Account;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * ПИСАЧЪТ НА FATCA XML v2.0.1 — по реда на елементите в схемата, без JAXB (както SAF-T: ADR {@code
 * the SAF-T generator design
 *
 * <p>Структурата (IRS Pub 5124): {@code FATCA_OECD} → {@code MessageSpec} + {@code FATCA} → {@code
 * ReportingFI} + {@code ReportingGroup} → {@code NilReport} ИЛИ {@code AccountReport*}.
 *
 * <p><b>⚠️ ДВЕ ПРОСТРАНСТВА ОТ ИМЕНА В ЕДИН ЕЛЕМЕНТ.</b> Наследените от {@code stffatcatypes}
 * полета (име, адрес, TIN) са в {@code sfa}, а добавените от FATCA схемата ({@code DocSpec}, {@code
 * AcctHolderType}, плащанията) — в {@code ftc}. Сбъркан префикс схемата хваща; затова всеки елемент
 * тук назовава своя.
 *
 * <p><b>⚠️ ПРОВЕРКИТЕ СА ПРЕДИ ПИСАЧА.</b> Тук задължителното се пише, без да се тълкува; празно
 * задължително поле стига до схемата и тя отказва. {@code BgFatcaFileValidator} отказва по-рано и с
 * по-ясна причина.
 */
public final class BgFatcaXmlWriter {

  /** Pub 5124, т. 3: YYYY-MM-DDThh:mm:ss, без части от секундата. */
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /** Model 1 IGA: файлът тръгва от България към САЩ. */
  static final String TRANSMITTING_COUNTRY = "BG";

  static final String RECEIVING_COUNTRY = "US";
  static final String MESSAGE_TYPE = "FATCA";
  static final String US_TIN_ISSUER = "US";

  private BgFatcaXmlWriter() {}

  public static byte[] toBytes(BgFatcaFileContent content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      XMLStreamWriter xml =
          XMLOutputFactory.newInstance().createXMLStreamWriter(out, BgFatcaXsd.ENCODING);
      new Doc(xml).write(content);
      xml.flush();
      xml.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("FATCA: XML потокът се провали: " + e.getMessage(), e);
    }
    return out.toByteArray();
  }

  /** DocTypeIndic: FATCA1 (нови данни) или FATCA11 (тестови). */
  public static String docTypeIndic(boolean testFile) {
    return testFile ? "FATCA11" : "FATCA1";
  }

  private static final class Doc {

    private final XMLStreamWriter xml;
    private int depth;

    private Doc(XMLStreamWriter xml) {
      this.xml = xml;
    }

    void write(BgFatcaFileContent c) throws XMLStreamException {
      xml.writeStartDocument(BgFatcaXsd.ENCODING, "1.0");
      newLine();
      xml.setPrefix("ftc", BgFatcaXsd.NS_FTC);
      xml.setPrefix("sfa", BgFatcaXsd.NS_SFA);
      xml.writeStartElement("ftc", BgFatcaXsd.ROOT_ELEMENT, BgFatcaXsd.NS_FTC);
      xml.writeNamespace("ftc", BgFatcaXsd.NS_FTC);
      xml.writeNamespace("sfa", BgFatcaXsd.NS_SFA);
      xml.writeAttribute("version", BgFatcaXsd.VERSION);
      depth++;

      messageSpec(c);
      ftc("FATCA");
      reportingFi(c);
      ftc("ReportingGroup");
      if (c.isNil()) {
        ftc("NilReport");
        docSpec(c, c.getNilDocRefId());
        ftcText("NoAccountToReport", "yes");
        end();
      } else {
        for (Account a : c.getAccounts()) {
          accountReport(c, a);
        }
      }
      end(); // ReportingGroup
      end(); // FATCA

      depth--;
      newLine();
      xml.writeEndElement();
      xml.writeEndDocument();
    }

    private void messageSpec(BgFatcaFileContent c) throws XMLStreamException {
      ftc("MessageSpec");
      sfaText("SendingCompanyIN", c.getGiin());
      sfaText("TransmittingCountry", TRANSMITTING_COUNTRY);
      sfaText("ReceivingCountry", RECEIVING_COUNTRY);
      sfaText("MessageType", MESSAGE_TYPE);
      sfaText("MessageRefId", c.getMessageRefId());
      sfaText("ReportingPeriod", LocalDate.of(c.getReportYear(), 12, 31).toString());
      sfaText("Timestamp", TIMESTAMP.format(c.getTimestamp().truncatedTo(ChronoUnit.SECONDS)));
      end();
    }

    private void reportingFi(BgFatcaFileContent c) throws XMLStreamException {
      ftc("ReportingFI");
      sfaText("ResCountryCode", c.getFiCountryCode());
      sfaText("TIN", c.getGiin());
      sfaText("Name", c.getFiName());
      sfa("Address");
      sfaText("CountryCode", c.getFiCountryCode());
      if (c.getFiCity() != null) {
        sfa("AddressFix");
        optSfa("Street", c.getFiStreet());
        optSfa("PostCode", c.getFiPostCode());
        sfaText("City", c.getFiCity());
        end();
      } else {
        sfaText("AddressFree", join(c.getFiStreet(), c.getFiPostCode()));
      }
      end(); // Address
      docSpec(c, c.getFiDocRefId());
      end();
    }

    private void accountReport(BgFatcaFileContent c, Account a) throws XMLStreamException {
      ftc("AccountReport");
      docSpec(c, a.getDocRefId());
      ftcText("AccountNumber", a.getAccountNumber().trim());
      if (a.isClosed()) {
        ftcText("AccountClosed", "true");
      }
      ftc("AccountHolder");
      if (a.isIndividual()) {
        ftc("Individual");
        tin(a);
        sfa("Name");
        sfaText("FirstName", a.getFirstName().trim());
        sfaText("LastName", a.getLastName().trim());
        end();
        address(a);
        if (a.getBirthDate() != null) {
          sfa("BirthInfo");
          sfaText("BirthDate", a.getBirthDate().toString());
          end();
        }
        end();
      } else {
        ftc("Organisation");
        tin(a);
        sfaText("Name", a.getOrganisationName().trim());
        address(a);
        end();
        ftcText("AcctHolderType", a.getAcctHolderType());
      }
      end(); // AccountHolder
      amount("AccountBalance", a.getBalance(), a.getCurrencyCode());
      payment("FATCA501", a.getDividends(), a.getCurrencyCode());
      payment("FATCA502", a.getInterest(), a.getCurrencyCode());
      payment("FATCA503", a.getGrossProceeds(), a.getCurrencyCode());
      payment("FATCA504", a.getOther(), a.getCurrencyCode());
      end();
    }

    private void tin(Account a) throws XMLStreamException {
      if (a.getUsTin() == null || a.getUsTin().trim().isEmpty()) {
        return;
      }
      newLine();
      xml.writeStartElement("sfa", "TIN", BgFatcaXsd.NS_SFA);
      xml.writeAttribute("issuedBy", US_TIN_ISSUER);
      xml.writeCharacters(a.getUsTin().replaceAll("[\\s-]", ""));
      xml.writeEndElement();
    }

    private void address(Account a) throws XMLStreamException {
      sfa("Address");
      sfaText("CountryCode", a.getCountryCode());
      String city = trim(a.getCity());
      String free = trim(a.getAddressFree());
      if (city != null) {
        sfa("AddressFix");
        sfaText("City", city);
        end();
        optSfa("AddressFree", free);
      } else {
        sfaText("AddressFree", free);
      }
      end();
    }

    /** Плащане само при ненулева сума — нулите не се пишат. */
    private void payment(String type, BigDecimal value, String currency) throws XMLStreamException {
      if (value == null || value.signum() == 0) {
        return;
      }
      ftc("Payment");
      ftcText("Type", type);
      amount("PaymentAmnt", value, currency);
      end();
    }

    private void amount(String name, BigDecimal value, String currency) throws XMLStreamException {
      newLine();
      xml.writeStartElement("ftc", name, BgFatcaXsd.NS_FTC);
      xml.writeAttribute("currCode", currency);
      xml.writeCharacters(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
      xml.writeEndElement();
    }

    private void docSpec(BgFatcaFileContent c, String docRefId) throws XMLStreamException {
      ftc("DocSpec");
      ftcText("DocTypeIndic", docTypeIndic(c.isTestFile()));
      ftcText("DocRefId", docRefId);
      end();
    }

    // ─────────────── елементи ───────────────

    private void ftc(String name) throws XMLStreamException {
      start(BgFatcaXsd.NS_FTC, "ftc", name);
    }

    private void sfa(String name) throws XMLStreamException {
      start(BgFatcaXsd.NS_SFA, "sfa", name);
    }

    private void start(String ns, String prefix, String name) throws XMLStreamException {
      newLine();
      xml.writeStartElement(prefix, name, ns);
      depth++;
    }

    private void end() throws XMLStreamException {
      depth--;
      newLine();
      xml.writeEndElement();
    }

    private void ftcText(String name, String value) throws XMLStreamException {
      text(BgFatcaXsd.NS_FTC, "ftc", name, value);
    }

    private void sfaText(String name, String value) throws XMLStreamException {
      text(BgFatcaXsd.NS_SFA, "sfa", name, value);
    }

    private void optSfa(String name, String value) throws XMLStreamException {
      if (trim(value) != null) {
        sfaText(name, value.trim());
      }
    }

    private void text(String ns, String prefix, String name, String value)
        throws XMLStreamException {
      newLine();
      xml.writeStartElement(prefix, name, ns);
      xml.writeCharacters(value == null ? "" : value);
      xml.writeEndElement();
    }

    private void newLine() throws XMLStreamException {
      StringBuilder indent = new StringBuilder("\n");
      for (int i = 0; i < depth; i++) {
        indent.append("  ");
      }
      xml.writeCharacters(indent.toString());
    }
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static String join(String street, String postCode) {
    StringBuilder b = new StringBuilder();
    if (trim(postCode) != null) {
      b.append(postCode.trim());
    }
    if (trim(street) != null) {
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(street.trim());
    }
    return b.toString();
  }
}
