package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КРЪГОВРАТЪТ: пълна декларация, XML, и ЗЕЛЕНО срещу схемата на НОИ — офлайн.
 *
 * <p><b>⚠️ КАКВО ДОКАЗВАТ ТЕЗИ СВИДЕТЕЛИ И КАКВО НЕ.</b> Те доказват, че произведеният файл е
 * СТРУКТУРНО годен: всички тагове, в реда на схемата, със стойности в допустимите изброявания и
 * диапазони. Те НЕ доказват, че НОИ ще приеме подаването — за това трябват КЕП, регистриран
 * потребител и портал, и трите човешки. И не доказват, че удостоверението е ПЪЛНО: схемата приема
 * файл, в който всяко „да/не“ е 9 „не е избрано“. Пълнотата е работа на {@link
 * BgApp9MandatoryPoints} и има свои свидетели.
 */
class BgApp9EmitterRoundTripTest {

  private static final BgApp9Validator VALIDATOR = new BgApp9Validator();

  @Test
  @DisplayName("Пълна декларация дава файл, който минава Pril9.xsd без нито една грешка")
  void fullDeclarationValidatesAgainstTheSchema() {
    BgApp9Declaration d = BgApp9Fixture.declaration();

    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);

    assertFalse(emission.isRefused(), "емисията отказа: " + emission.gapReport());

    List<BgApp9Finding> schema = VALIDATOR.validateAgainstSchema(emission.getXml());
    assertTrue(schema.isEmpty(), "схемата намери: " + BgApp9Validator.report(schema));
  }

  @Test
  @DisplayName("Файлът носи името по описанието: Pril9_ddmmyyyy_n.xml")
  void fileNameFollowsTheSpecification() {
    BgApp9Emission emission =
        BgApp9Emitter.emit(
            BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 3);
    // Датата на износа във фикстурата е 12.06.2026 — ddmmyyyy, не yyyymmdd.
    assertEquals("Pril9_12062026_3.xml", emission.getFileName());
  }

  @Test
  @DisplayName("Заглавната част носи ТП на НОИ, ЕИК и начина на подаване")
  void headerCarriesTheInstitutionFacts() {
    String xml =
        BgApp9Emitter.emit(
                BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 1)
            .getXml();

    assertTrue(xml.contains("<NSSI_RO>22</NSSI_RO>"), "ТП на НОИ липсва");
    assertTrue(xml.contains("<BULSTATKasa>" + BgApp9Fixture.COMPANY_BULSTAT + "</BULSTATKasa>"));
    assertTrue(xml.contains("<TypeOfDocument>0</TypeOfDocument>"), "тип на документите");
    assertTrue(xml.contains("<Exported>1</Exported>"), "подаване чрез файл");
    assertTrue(xml.contains("<FlagCertificate>1</FlagCertificate>"), "само раздел I");
  }

  @Test
  @DisplayName("№ на листа е дванайсетте знака: серия + година + седем цифри с водещи нули")
  void chartNumberIsComposedFromTheThreeFields() {
    String xml =
        BgApp9Emitter.emit(
                BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 1)
            .getXml();
    assertTrue(
        xml.contains("<PatientsChartNumber>E20260001234</PatientsChartNumber>"),
        "номерът не е сглобен по образеца");
  }

  @Test
  @DisplayName("⚠️ Т. 2 излиза ОБЪРНАТА: съхраненото „стажът Е налице“ става отметка 1")
  void pointTwoIsInvertedIntoTheFormsCheckbox() {
    BgApp9Declaration met = BgApp9Fixture.declaration();
    String xmlMet = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(met), 1).getXml();
    // Образецът гласи „лицето НЯМА 6 месеца стаж“ и се отмята САМО когато стажът липсва.
    // Тоест изведеното MET дава 1 = „не е избрано, приема се, че ГО ИМА“.
    assertTrue(xmlMet.contains("<SixMonthsInsurance>1</SixMonthsInsurance>"));

    BgApp9Declaration notMet = BgApp9Fixture.declaration();
    notMet.setBgP2SixMonthStaySelect("NOT_MET");
    String xmlNotMet =
        BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(notMet), 1).getXml();
    assertTrue(xmlNotMet.contains("<SixMonthsInsurance>0</SixMonthsInsurance>"));

    // А неотносимата т. 3 дава 9 — това е ДЕКЛАРИРАНАТА неотносимост, не липсващ отговор.
    assertTrue(xmlMet.contains("<InsuranceRigthsOZM>9</InsuranceRigthsOZM>"));
  }

  @Test
  @DisplayName("⚠️ Раздел II присъства ПРАЗЕН — изискванията за прием го налагат")
  void sectionTwoIsPresentButEmpty() {
    String xml =
        BgApp9Emitter.emit(
                BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 1)
            .getXml();
    assertTrue(xml.contains("<CertificateChanges>"), "разделът трябва да ПРИСЪСТВА");
    assertTrue(xml.contains("<InsuredIsDead>0</InsuredIsDead>"), "с непопълнени стойности");
    assertTrue(xml.contains("<A2FlagOtherCircumstances>0</A2FlagOtherCircumstances>"));
  }

  @Test
  @DisplayName("Два документа в един файл се номерират 1 и 2 и файлът пак е валиден")
  void twoDocumentsInOneFile() {
    BgApp9Declaration first = BgApp9Fixture.declaration();

    BgSickLeave second =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0009999", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 25));
    BgApp9Fixture.standardLines(second);
    BgApp9Declaration secondDeclaration = BgApp9Fixture.declarationFor(second);

    List<BgApp9Declaration> both = BgApp9Fixture.one(first);
    both.add(secondDeclaration);

    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), both, 1);
    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(2, emission.getDocumentCount());

    String xml = emission.getXml();
    assertTrue(xml.contains("<Number>1</Number>"));
    assertTrue(xml.contains("<Number>2</Number>"));

    List<BgApp9Finding> schema = VALIDATOR.validateAgainstSchema(xml);
    assertTrue(schema.isEmpty(), BgApp9Validator.report(schema));
  }

  @Test
  @DisplayName("Файлът е UTF-8 и кирилицата в него е цяла")
  void cyrillicSurvives() {
    String xml =
        BgApp9Emitter.emit(
                BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 1)
            .getXml();
    assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"));
    assertTrue(xml.contains("<FamilyName>Стоянова</FamilyName>"));
    assertTrue(xml.contains("<BulName>Никса ЕООД</BulName>"));
  }

  @Test
  @DisplayName("⚠️ Опасните знаци се екранират, а управляващите се МАХАТ")
  void dangerousCharactersAreEscaped() {
    // Управляващият знак се сглобява, а не се пише в изходния текст: в редактор той е невидим и
    // първото копиране на този ред би го загубило мълчаливо, с което свидетелят би спрял да мери.
    char control = (char) 1;

    BgApp9Declaration d = BgApp9Fixture.declaration();
    // ⚠️ Такъв знак НЕ е екранируем: „&#x1;“ е точно толкова невалиден в XML 1.0, колкото голият
    // байт. Изискванията за прием отхвърлят ЦЕЛИЯ файл заради недопустим символ в текстово поле.
    d.setBgP24OtherCircumstances("Лицето & \"частично\" <работи> по" + control + "график");

    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);
    String xml = emission.getXml();

    assertTrue(xml.contains("&amp;"), "амперсандът не е екраниран");
    assertTrue(xml.contains("&lt;работи&gt;"), "ъгловите скоби не са екранирани");
    assertEquals(-1, xml.indexOf(control), "управляващият знак остана — той връща ЦЕЛИЯ файл");

    List<BgApp9Finding> schema = VALIDATOR.validateAgainstSchema(xml);
    assertTrue(schema.isEmpty(), BgApp9Validator.report(schema));
  }
}
