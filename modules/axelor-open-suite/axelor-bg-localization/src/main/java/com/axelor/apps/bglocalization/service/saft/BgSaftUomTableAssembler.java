package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * UOMTable (MF.UOM.*) ОТ КОДОВЕТЕ, КОИТО ФАЙЛЪТ ПОЛЗВА — чиста функция.
 *
 * <p><b>⚠️ ТАБЛИЦАТА Е НА ФАЙЛА.</b> Влизат кодовете по НАП ({@code Unit.bgUomCode}), които излизат
 * като UOMStandard в Products; редовете на документите (G5) ще добавят своите. Образецът на НАП има
 * три реда, не 2071.
 *
 * <p><b>⚠️ КОДЪТ СЕ СВЕРЯВА ТУК, НЕ В ПРОДУКТИТЕ.</b> MF.UOM.2 и MF.P.10 валидират срещу една и
 * съща номенклатура (Unit_Of_Measure), а всеки код минава оттук — един и същи сгрешен код се казва
 * веднъж, не по веднъж на продукт. Схемата го приема („текст до 9 знака“), отказът е при НАП —
 * затова находката е грешка. Пазачът на seed-а ({@code BgUnitSeedTest}) не стига: кодът се
 * редактира и през изгледа.
 *
 * <p>Description (MF.UOM.3) е българското наименование от номенклатурата, не нашето име на
 * единицата — едно и също H87 стои зад „Брой“ и осемте кутии.
 */
public final class BgSaftUomTableAssembler {

  private BgSaftUomTableAssembler() {}

  /** Редовете на таблицата и находките по пътя. */
  public static final class Result {
    private final List<SaftUom> entries;
    private final List<BgDeclarationFileFinding> findings;

    private Result(List<SaftUom> entries, List<BgDeclarationFileFinding> findings) {
      this.entries = Collections.unmodifiableList(entries);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftUom> getEntries() {
      return entries;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param codes кодовете по НАП, които файлът ползва; празните и повторенията се пропускат
   * @param nomenclature Unit_Of_Measure: код → българско наименование ({@link
   *     BgNraNomenclatures#unitsOfMeasure()})
   */
  public static Result assemble(Collection<String> codes, Map<String, String> nomenclature) {
    TreeSet<String> used = new TreeSet<>();
    for (String code : codes) {
      String c = code == null ? "" : code.trim();
      if (!c.isEmpty()) {
        used.add(c);
      }
    }
    List<String> invalid = new ArrayList<>();
    List<SaftUom> entries = new ArrayList<>();
    for (String code : used) {
      String name = nomenclature.get(code);
      if (name == null) {
        invalid.add(code);
        name = "";
      }
      entries.add(new SaftUom().unitOfMeasure(code).description(name));
    }

    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (entries.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_UOM_EMPTY",
              "Нито един продукт няма мерна единица с код по НАП — схемата иска поне една"
                  + " (UOMTable/UOMTableEntry)."));
    }
    for (String item : invalid) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_UOM_CODE_INVALID",
              "Кодове на мерни единици извън номенклатурата Unit_Of_Measure (MF.UOM.2,"
                  + " MF.P.10): "
                  + item));
    }
    return new Result(entries, findings);
  }
}
