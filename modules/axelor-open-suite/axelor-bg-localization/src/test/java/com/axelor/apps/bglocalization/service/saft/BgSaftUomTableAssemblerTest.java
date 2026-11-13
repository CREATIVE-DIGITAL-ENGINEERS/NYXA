package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftUomTableAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UOMTable: ползваните кодове по веднъж, наименованието на НАП и кодът извън номенклатурата. */
class BgSaftUomTableAssemblerTest {

  private static Result assemble(String... codes) {
    return BgSaftUomTableAssembler.assemble(
        Arrays.asList(codes), BgNraNomenclatures.unitsOfMeasure());
  }

  private static List<String> units(Result result) {
    return result.getEntries().stream().map(SaftUom::getUnitOfMeasure).collect(Collectors.toList());
  }

  @Test
  @DisplayName("Ползваните кодове — подредени, по веднъж, с българското име на НАП")
  void ползваните_кодове() {
    Result result = assemble("H87", "KGM", "H87", "", null, " E49 ");
    assertEquals(List.of(), result.getFindings());
    assertEquals(List.of("E49", "H87", "KGM"), units(result));
    assertEquals(
        List.of("работен ден", "бройка", "килограм"),
        result.getEntries().stream().map(SaftUom::getDescription).collect(Collectors.toList()));
  }

  @Test
  @DisplayName("Отрицателен свидетел: код извън Unit_Of_Measure е грешка, а редът остава видим")
  void кодът_извън_номенклатурата() {
    Result result = assemble("H87", "БР");
    assertEquals(List.of("H87", "БР"), units(result));
    assertEquals("", result.getEntries().get(1).getDescription());
    assertEquals(1, result.getFindings().size());
    BgDeclarationFileFinding invalid = result.getFindings().get(0);
    assertEquals("SAFT_UOM_CODE_INVALID", invalid.getCode());
    assertTrue(invalid.isBlocking());
    assertTrue(invalid.getMessage().endsWith(": БР"), invalid.getMessage());
  }

  @Test
  @DisplayName("Без нито един код — грешка, схемата иска поне един ред")
  void празната_таблица() {
    Result result = assemble("", null);
    assertTrue(result.getEntries().isEmpty());
    assertEquals("SAFT_UOM_EMPTY", result.getFindings().get(0).getCode());
    assertTrue(result.getFindings().get(0).isBlocking());
  }
}
