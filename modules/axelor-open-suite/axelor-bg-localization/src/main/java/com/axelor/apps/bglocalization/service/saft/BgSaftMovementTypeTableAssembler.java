package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementType;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovement;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MovementTypeTable (MF.MT.*) ОТ ДВИЖЕНИЯТА, КОИТО ФАЙЛЪТ ПОДАВА — чиста функция.
 *
 * <p><b>⚠️ ТАБЛИЦАТА Е НА ФАЙЛА, НЕ НА НОМЕНКЛАТУРАТА.</b> Прил. 3 т. I.1.2: MovementTypeTable
 * „съдържа вида на стоковото движение, свързано с движенията на запасите, за които се подават данни
 * в останалите секции/подсекции на файла“ — същото правило като за TaxTable и UOMTable (QA с. 38).
 * Влизат видовете от SD.MG.10, в реда на номенклатурата.
 *
 * <p><b>⚠️ БЕЗ ДВИЖЕНИЯ — ВСИЧКИТЕ 20, ДА СЕ ПОТВЪРДИ.</b> Нулевата структура по QA IV.2.6 носи вид
 * „0“, който не е в Stock movement, а схемата иска поне един ред в таблицата. Официалният образец
 * при поискване изброява всичките 20 вида; тук — същото, с предупреждение, докато НАП не каже
 * друго.
 *
 * <p>Видът се сверява ТУК, както мерната единица в {@link BgSaftUomTableAssembler}: схемата приема
 * всеки текст, отказът е при НАП — затова вид извън номенклатурата е грешка.
 */
public final class BgSaftMovementTypeTableAssembler {

  private BgSaftMovementTypeTableAssembler() {}

  /** Редовете на таблицата и находките по пътя. */
  public static final class Result {
    private final List<SaftMovementType> entries;
    private final List<BgDeclarationFileFinding> findings;

    private Result(List<SaftMovementType> entries, List<BgDeclarationFileFinding> findings) {
      this.entries = Collections.unmodifiableList(entries);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftMovementType> getEntries() {
      return entries;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param goods движенията на файла — истинските или нулевата структура
   * @param nomenclature Stock movement: вид → наименование, в реда на книгата ({@link
   *     BgNraNomenclatures#movementTypes()})
   */
  public static Result assemble(SaftMovementOfGoods goods, Map<String, String> nomenclature) {
    List<SaftMovementType> entries = new ArrayList<>();
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (goods == null || BgSaftZeroStructures.isZero(goods)) {
      nomenclature.forEach((code, name) -> entries.add(entry(code, name)));
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_MOVEMENT_TYPES_ALL_UNCONFIRMED",
              "Няма движения на запаси — MovementTypeTable изброява всичките "
                  + nomenclature.size()
                  + " вида от Stock movement, както официалният образец. Да се потвърди: Прил. 3"
                  + " т. I.1.2 иска видовете на подадените движения, а такива няма."));
      return new Result(entries, findings);
    }

    Set<String> used = new LinkedHashSet<>();
    for (SaftStockMovement m : goods.getStockMovements()) {
      String code = m.getMovementType() == null ? "" : m.getMovementType().trim();
      if (!code.isEmpty()) {
        used.add(code);
      }
    }
    for (Map.Entry<String, String> e : nomenclature.entrySet()) {
      if (used.remove(e.getKey())) {
        entries.add(entry(e.getKey(), e.getValue()));
      }
    }
    if (!used.isEmpty()) {
      for (String code : used) {
        entries.add(entry(code, ""));
      }
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_MOVEMENT_TYPE_INVALID",
              "Видове движения извън номенклатурата Stock movement (MF.MT.2, SD.MG.10): "
                  + String.join(", ", used)));
    }
    if (entries.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_MOVEMENT_TYPES_EMPTY",
              "Нито едно движение няма вид — схемата иска поне един ред"
                  + " (MovementTypeTable/MovementTypeTableEntry)."));
    }
    return new Result(entries, findings);
  }

  private static SaftMovementType entry(String code, String name) {
    return new SaftMovementType().movementType(code).description(name);
  }
}
