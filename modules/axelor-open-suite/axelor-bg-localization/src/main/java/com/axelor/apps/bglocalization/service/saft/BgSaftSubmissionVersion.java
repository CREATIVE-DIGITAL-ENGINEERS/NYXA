package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.i18n.I18n;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * ВЕРСИЯТА НА SAF-T ВЪВ ФАЙЛА СРЕЩУ ВЕРСИЯТА ЗА ДНЕС — при изтегляне и при входящия номер. Чиста
 * функция.
 *
 * <p><b>Защо.</b> Версията е по момента на ПОДАВАНЕ: QA „Въпроси и отговори“ на НАП (март 2026 г.,
 * версия 2), II.2.4, с. 13 — файловете „следва да съответстват на XSD-схемата, която е актуална към
 * момента на подаване“; Заповед № З-ЦУ-30-1797/13.10.2026 — подаванията от 01.11.2026 са по 1.0.3.
 * {@link BgSaftXsd#VERSIONS} избира по датата на генериране, а Nyxa не знае кога файлът ще бъде
 * качен — файл от 31.10, изтеглен на 01.11, вече е по старата версия.
 *
 * <p><b>Как.</b> S.H.1 AuditFileVersion от файла срещу {@code forDate(днес)} от същата таблица.
 * Различни, липсващ S.H.1 или нечетим файл — отказ: генерирайте файла отново.
 */
public final class BgSaftSubmissionVersion {

  /** Ключът за превод — българският текст; английският е в {@code messages_en.csv}. */
  static final String MESSAGE = "Версията на SAF-T от %s е %s. Генерирайте файла отново.";

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgSaftSubmissionVersion() {}

  /** Защо файлът не бива да се подава на {@code today}; {@code null}, когато може. */
  public static String refusal(byte[] xml, LocalDate today) {
    BgSaftXsd.Version current = BgSaftXsd.forDate(today);
    if (current.getAuditFileVersion().equals(auditFileVersion(xml))) {
      return null;
    }
    return String.format(
        I18n.get(MESSAGE), current.getAppliesFrom().format(DATE), current.getSchemaVersion());
  }

  /** S.H.1 AuditFileVersion от файла; {@code null}, когато липсва или файлът не се чете. */
  public static String auditFileVersion(byte[] xml) {
    if (xml == null) {
      return null;
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        while (reader.hasNext()) {
          if (reader.next() == XMLStreamConstants.START_ELEMENT
              && "AuditFileVersion".equals(reader.getLocalName())
              && BgSaftXsd.NAMESPACE.equals(reader.getNamespaceURI())) {
            return reader.getElementText().trim();
          }
        }
        return null;
      } finally {
        reader.close();
      }
    } catch (Exception e) {
      return null;
    }
  }
}
