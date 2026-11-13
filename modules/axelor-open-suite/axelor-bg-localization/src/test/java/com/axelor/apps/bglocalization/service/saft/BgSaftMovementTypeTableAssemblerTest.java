package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementType;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovement;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MovementTypeTable — само видовете на подадените движения; без движения — всичките 20. */
class BgSaftMovementTypeTableAssemblerTest {

  private static SaftMovementOfGoods moves(String... types) {
    SaftMovementOfGoods goods =
        new SaftMovementOfGoods().numberOfMovementLines((long) types.length);
    for (String type : types) {
      goods.addStockMovement(new SaftStockMovement().movementType(type));
    }
    return goods;
  }

  private static BgSaftMovementTypeTableAssembler.Result assemble(SaftMovementOfGoods goods) {
    return BgSaftMovementTypeTableAssembler.assemble(goods, BgNraNomenclatures.movementTypes());
  }

  private static List<String> codes(BgSaftMovementTypeTableAssembler.Result result) {
    return result.getEntries().stream()
        .map(SaftMovementType::getMovementType)
        .collect(Collectors.toList());
  }

  private static List<String> findings(BgSaftMovementTypeTableAssembler.Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Само ползваните видове, по веднъж, в реда на номенклатурата (Прил. 3 т. I.1.2)")
  void само_ползваните() {
    BgSaftMovementTypeTableAssembler.Result result = assemble(moves("30", "10", "30", " 101 "));
    assertEquals(List.of("10", "30", "101"), codes(result));
    assertEquals(List.of(), result.getFindings());
    assertEquals("Покупка", result.getEntries().get(0).getDescription());
    assertEquals("Продажба ", result.getEntries().get(1).getDescription(), "дословно");
  }

  @Test
  @DisplayName("Нулевата структура: всичките 20 вида, с предупреждение „да се потвърди“")
  void без_движения_всичките() {
    BgSaftMovementTypeTableAssembler.Result result =
        assemble(BgSaftZeroStructures.movementOfGoods());
    assertEquals(20, result.getEntries().size());
    assertEquals("10", result.getEntries().get(0).getMovementType());
    assertFalse(codes(result).contains("0"), "видът „0“ на нулевата структура не е в таблицата");
    assertEquals(List.of("SAFT_MOVEMENT_TYPES_ALL_UNCONFIRMED"), findings(result));
    assertFalse(result.getFindings().get(0).isBlocking());
  }

  @Test
  @DisplayName("Вид извън Stock movement е ГРЕШКА — схемата го приема, НАП не")
  void непознатият_вид() {
    BgSaftMovementTypeTableAssembler.Result result = assemble(moves("30", "35"));
    assertEquals(List.of("30", "35"), codes(result));
    assertEquals(List.of("SAFT_MOVEMENT_TYPE_INVALID"), findings(result));
    assertTrue(result.getFindings().get(0).isBlocking());
    assertTrue(result.getFindings().get(0).getMessage().contains("35"));
  }

  @Test
  @DisplayName("Движения без вид: празна таблица и ГРЕШКА, не тиха липса")
  void без_вид() {
    BgSaftMovementTypeTableAssembler.Result result = assemble(moves(" ", null));
    assertEquals(List.of(), result.getEntries());
    assertEquals(List.of("SAFT_MOVEMENT_TYPES_EMPTY"), findings(result));
  }
}
