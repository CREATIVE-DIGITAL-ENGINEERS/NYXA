package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * A wrongly typed nested argument used to be dropped silently while the rest of the update saved —
 * the name changed, the address never arrived, and the reply could still say "address added". Now
 * the whole call is refused before {@code SupplierMaintenanceService} is even looked up: this test
 * runs with no injector, so reaching {@code Beans.get} would fail it with a different exception.
 */
class ManageSupplierToolArgumentsTest {

  @Test
  void anAddressSentAsAStringRefusesTheWholeCallBeforeAnythingIsSaved() {
    ObjectNode args =
        new ObjectMapper()
            .createObjectNode()
            .put("operation", "update")
            .put("supplierId", 164)
            .put("name", "Нов Доставчик ЕООД")
            .put("address", "ул. Витоша 1, София");

    AiToolException ex =
        assertThrows(AiToolException.class, () -> new ManageSupplierTool().execute(args));

    assertEquals(AiBlockerCode.BAD_ARGUMENTS.wire(), ex.details().get("blocker"));
    assertTrue(ex.getMessage().contains("address must be an object"), ex.getMessage());
    assertTrue(ex.getMessage().contains("Nothing was saved"), ex.getMessage());
  }

  /** Everything a create lacks is named in one refusal, before any service is reached. */
  @Test
  void aCreateMissingNameAndEikNamesBothAtOnce() {
    ObjectNode args = new ObjectMapper().createObjectNode().put("operation", "create");

    AiToolException ex =
        assertThrows(AiToolException.class, () -> new ManageSupplierTool().execute(args));

    assertEquals(AiBlockerCode.MISSING_SUPPLIER_DATA.wire(), ex.details().get("blocker"));
    assertTrue(ex.getMessage().contains("name ("), ex.getMessage());
    assertTrue(ex.getMessage().contains("eik ("), ex.getMessage());
  }

  /** «ИН по ДДС BG130159785» alone is the ЕИК 130159785 for a create. */
  @Test
  void aBgVatNumberStandsInForAMissingEik() {
    ObjectNode args =
        new ObjectMapper()
            .createObjectNode()
            .put("operation", "create")
            .put("name", "SAP BULGARIA")
            .put("vatNumber", "BG130159785");
    assertEquals("130159785", ManageSupplierTool.effectiveEik(args));
  }
}
