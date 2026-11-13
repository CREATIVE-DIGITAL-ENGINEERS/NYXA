package com.axelor.apps.napsubmissions.services.saft;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * ПРОТОКОЛЪТ НА НАП ЗА ПОДАДЕН SAF-T ФАЙЛ (errsaft XML) — разчетен до списък грешки.
 *
 * <p>Форматът, както го връща тестовата е-услуга (протокол 30TST000014738, 15.10.2026):
 *
 * <pre>{@code
 * <errsaft>
 *   <error><err_number>1</err_number><err_code>IBANNumber S.BA.1</err_code>
 *          <err_descr>…</err_descr></error>
 * </errsaft>
 * }</pre>
 *
 * <p><b>⚠️ ПРОТОКОЛ БЕЗ ГРЕШКИ НЕ Е „ПРИЕТ“.</b> Изходът (приет/отхвърлен) го отбелязва човекът по
 * това, което порталът казва; разчетените грешки са за поправката, не за статуса.
 *
 * <p>Четенето е без DTD и без външни ентитита — файлът идва от потребителя.
 */
public final class SaftNapProtocol {

  public static final String ROOT = "errsaft";

  private SaftNapProtocol() {}

  /** Една грешка от протокола. */
  public static final class Error {
    private final String number;
    private final String code;
    private final String description;

    Error(String number, String code, String description) {
      this.number = number;
      this.code = code;
      this.description = description;
    }

    public String getNumber() {
      return number;
    }

    /** Елементът по книгата на НАП, напр. „IBANNumber S.BA.1“. */
    public String getCode() {
      return code;
    }

    public String getDescription() {
      return description;
    }

    @Override
    public String toString() {
      return number + ". " + code + " — " + description;
    }
  }

  /**
   * Грешките от протокола, по реда им.
   *
   * @throws IllegalArgumentException файлът не е протокол на НАП за SAF-T (коренът не е {@code
   *     errsaft} или XML-ът е повреден)
   */
  public static List<Error> parse(byte[] xml) {
    if (xml == null || xml.length == 0) {
      throw new IllegalArgumentException("Празен файл — не е протокол на НАП (errsaft).");
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    List<Error> errors = new ArrayList<>();
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        boolean root = false;
        String number = null;
        String code = null;
        String description = null;
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            String name = reader.getLocalName();
            if (!root) {
              if (!ROOT.equals(name)) {
                throw new IllegalArgumentException(
                    "Файлът не е протокол на НАП за SAF-T: коренът е „"
                        + name
                        + "“, а не „"
                        + ROOT
                        + "“.");
              }
              root = true;
            } else if ("error".equals(name)) {
              number = null;
              code = null;
              description = null;
            } else if ("err_number".equals(name)) {
              number = reader.getElementText().trim();
            } else if ("err_code".equals(name)) {
              code = reader.getElementText().trim();
            } else if ("err_descr".equals(name)) {
              description = reader.getElementText().trim();
            }
          } else if (event == XMLStreamConstants.END_ELEMENT
              && "error".equals(reader.getLocalName())) {
            errors.add(new Error(number, code, description));
          }
        }
        if (!root) {
          throw new IllegalArgumentException("Файлът не е протокол на НАП за SAF-T (errsaft).");
        }
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException(
          "Файлът не е четим XML — не е протокол на НАП (errsaft): " + e.getMessage(), e);
    }
    return Collections.unmodifiableList(errors);
  }
}
