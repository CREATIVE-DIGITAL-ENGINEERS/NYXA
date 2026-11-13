package com.axelor.apps.bglocalization.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

/**
 * {@code xsd:import} НА СЪСЕДНА СХЕМА — само от classpath-а, само от същата папка. Липсващата
 * съседна схема е отказ, не тиха празна проверка.
 */
class BgXsdValidatorImportTest {

  private static final byte[] DOC =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><m:root xmlns:m=\"urn:test:main\">7</m:root>"
          .getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("Съседната схема се внася и типът ѝ се прилага")
  void съседната_схема_се_внася() {
    assertTrue(
        BgXsdValidator.validate(DOC, "/xsd-import/main.xsd", "test", "TEST").isEmpty(),
        "валиден документ");
    byte[] bad =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><m:root xmlns:m=\"urn:test:main\">x</m:root>"
            .getBytes(StandardCharsets.UTF_8);
    List<BgDeclarationFileFinding> findings =
        BgXsdValidator.validate(bad, "/xsd-import/main.xsd", "test", "TEST");
    assertFalse(findings.isEmpty(), "типът от съседната схема трябва да се прилага");
    assertEquals("TEST_SCHEMA_ERROR", findings.get(0).getCode());
  }

  @Test
  @DisplayName("Липсваща съседна схема — отказ, не „минава“")
  void липсващата_схема_е_отказ() {
    List<BgDeclarationFileFinding> findings =
        BgXsdValidator.validate(DOC, "/xsd-import/main-missing.xsd", "test", "TEST");
    assertFalse(findings.isEmpty());
    assertTrue(findings.get(0).isBlocking());
  }

  @Test
  @DisplayName("(а) ../, http:, https: и file: в schemaLocation — отказ от резолвъра, без мрежа")
  void резолверът_отказва_всичко_освен_голо_име() {
    for (String[] c :
        new String[][] {
          {"/xsd-import/main-traversal.xsd", "../xsd-import-other/othertypes.xsd"},
          {"/xsd-import/main-http.xsd", "http://127.0.0.1:9/types.xsd"},
          {"/xsd-import/main-https.xsd", "https://127.0.0.1:9/types.xsd"},
          {"/xsd-import/main-file.xsd", "file:///C:/xsd-import/types.xsd"}
        }) {
      List<BgDeclarationFileFinding> findings = BgXsdValidator.validate(DOC, c[0], "test", "TEST");
      assertEquals(1, findings.size(), c[0] + ": " + findings);
      BgDeclarationFileFinding f = findings.get(0);
      assertEquals("TEST_SCHEMA_CHECK_FAILED", f.getCode(), c[0]);
      assertTrue(f.getMessage().contains("отказано: " + c[1]), f.getMessage());
    }
  }

  @Test
  @DisplayName("(а) резолверът приема само голо име на .xsd")
  void голо_име() {
    assertTrue(BgXsdValidator.SIBLING.matcher("isofatcatypes_v1.2.xsd").matches());
    for (String bad :
        new String[] {
          "../types.xsd",
          "sub/types.xsd",
          "http://x/types.xsd",
          "file:types.xsd",
          "a\\types.xsd",
          "types.dtd",
          ""
        }) {
      assertFalse(BgXsdValidator.SIBLING.matcher(bad).matches(), bad);
    }
  }

  @Test
  @DisplayName("(б) фабриката: FEATURE_SECURE_PROCESSING, без външни схеми и DTD")
  void фабриката_е_втвърдена() throws Exception {
    SchemaFactory factory = BgXsdValidator.schemaFactory();
    assertTrue(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING));

    // Без резолвъра: съществуващ локален файл по file: — спира ACCESS_EXTERNAL_SCHEMA = "".
    String types = BgXsdValidatorImportTest.class.getResource("/xsd-import/types.xsd").toString();
    String importing =
        "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:t\">"
            + "<xsd:import namespace=\"urn:test:types\" schemaLocation=\""
            + types
            + "\"/></xsd:schema>";
    SAXException schema =
        assertThrows(
            SAXException.class,
            () -> factory.newSchema(new StreamSource(new StringReader(importing))));
    assertTrue(schema.getMessage().contains("accessExternalSchema"), schema.getMessage());

    String withDtd =
        "<!DOCTYPE xsd:schema SYSTEM \""
            + types
            + "\"><xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " targetNamespace=\"urn:t\"/>";
    SAXException dtd =
        assertThrows(
            SAXException.class,
            () -> factory.newSchema(new StreamSource(new StringReader(withDtd))));
    assertTrue(dtd.getMessage().contains("accessExternalDTD"), dtd.getMessage());
  }

  @Test
  @DisplayName("(в) документ с DOCTYPE или външен обект — отказ, съдържанието не изтича")
  void doctype_и_външен_обект() {
    String file = BgXsdValidatorImportTest.class.getResource("/xsd-import/types.xsd").toString();
    for (String doc :
        new String[] {
          "<?xml version=\"1.0\"?><!DOCTYPE m:root [<!ENTITY x SYSTEM \""
              + file
              + "\">]><m:root xmlns:m=\"urn:test:main\">&x;</m:root>",
          "<?xml version=\"1.0\"?><!DOCTYPE m:root [<!ENTITY x \"7\">]>"
              + "<m:root xmlns:m=\"urn:test:main\">&x;</m:root>",
          "<?xml version=\"1.0\"?><!DOCTYPE m:root SYSTEM \""
              + file
              + "\"><m:root xmlns:m=\"urn:test:main\">7</m:root>"
        }) {
      List<BgDeclarationFileFinding> findings =
          BgXsdValidator.validate(
              doc.getBytes(StandardCharsets.UTF_8), "/xsd-import/main.xsd", "test", "TEST");
      assertFalse(findings.isEmpty(), doc);
      assertTrue(findings.stream().allMatch(BgDeclarationFileFinding::isBlocking), "" + findings);
      assertTrue(
          findings.stream().noneMatch(f -> f.getMessage().contains("maxInclusive")),
          "съдържанието на външния файл не бива да стига до находките: " + findings);
      assertTrue(
          findings.stream().anyMatch(f -> f.getMessage().contains("DOCTYPE")), "" + findings);
    }
  }
}
