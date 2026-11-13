package com.axelor.apps.bglocalization.service;

import java.util.Set;
import java.util.TreeSet;

/**
 * ВИД ОСИГУРЕН ↔ КАТЕГОРИЯ ТРУД — ЕДИНСТВЕНИЯТ ИЗТОЧНИК НА ПРАВИЛОТО.
 *
 * <p><b>Правилото е едно изречение и не е ново.</b> Двойката (вид осигурен, категория труд) е
 * валидна ⇔ каталогът {@code bg_insurance_contribution} носи поне един ред със ставки за нея. То
 * съществуваше и досега — измерено от seed-а в {@code BgInsuredTypeHomeFileTest#disagreements} — но
 * живееше САМО в теста. Тоест проверяваше се, след като договорът е записан, и то само когато някой
 * пусне тестовете.
 *
 * <p><b>Какво се счупи (договор #6).</b> Невъзможна двойка се записваше по договора БЕЗ ВЪЗРАЖЕНИЕ.
 * Гърмеше чак на фиша, с {@code NO_CONTRIBUTIONS_FOUND}, а съобщението назоваваше само „вид
 * осигурен: 01“ — тоест пращаше оператора да поправя ПЪРВОТО поле, докато сгрешено можеше да е
 * второто. Един и същ модел на тих провал: полето се приема, разминаването изплува през два екрана
 * и посочва грешното място.
 *
 * <p><b>Затова правилото слиза тук, в главния код, и се ПРЕИЗПОЛЗВА и на трите места</b> — записът
 * на договора ({@code BgEmploymentContractManagementRepository}), отказът на фиша ({@code
 * BgContributionResolver}) и двата пазача ({@code BgInsuredTypeHomeFileTest} и {@code
 * BgInsuredTypeHomeDbTest}). Второ копие би се разминало при първата поправка, а разминаването е
 * тихо: зеленото на едното копие изглежда точно като зеленото на другото.
 *
 * <p><b>⚠️ ИЗТОЧНИКЪТ НА НАБОРА Е НА ВИКАЩИЯ, НЕ ТУК.</b> Класът приема ГОТОВИЯ набор категории и
 * не знае откъде идва: пазачът го чете от {@code bg_insurance_contribution.csv} на диска, записът
 * на договора — от таблицата през {@code BgInsuranceCalculationService#laborCategoriesWithRates}.
 * Един и същи набор дава един и същи отговор — точно това мери сверяващият тест срещу drift.
 *
 * <p><b>⚠️ БЕЗ ФИЛТЪР ПО ПЕРИОД, И ТОВА Е НАРОЧНО.</b> Наборът се строи от ВСИЧКИ редове на кода, а
 * не от валидните на дата. Договорът се записва веднъж и се ползва през месеци: дата днес би
 * отказала договор, чиито ставки влизат в сила другия месец. Периодът е въпрос на ФИША и се решава
 * там — {@code BgInsuranceCalculationService#calculate} филтрира по {@code validFrom}/{@code
 * validTo} както преди.
 *
 * <p><b>Мълчанието на данните не е забрана.</b> Код без нито един ред със ставки НЕ ограничава
 * нищо: липсата на редове е празнота в каталога, не правило. Инак кодовете без редове щяха да
 * отказват всяка категория, тоест правилото щеше да мери липсата на данни вместо самите данни.
 */
public final class BgInsuredTypeCategoryRule {

  /** Каталогът със ставките — назован в съобщенията, за да е проследим до файла. */
  public static final String CATALOGUE = "bg_insurance_contribution";

  private BgInsuredTypeCategoryRule() {}

  /**
   * Има ли изобщо какво да се сверява — ДВЕТЕ полета попълнени.
   *
   * <p>Празно поле е незададен въпрос, не разминаване: липсващият вид осигурен се отказва по
   * собствена причина от {@code BgPayrollInputBuilder}, а липсващата категория — от домейна.
   * Викащият пита това ПРЕДИ да чете каталога, за да не плаща прочит за въпрос без отговор.
   */
  public static boolean isAnswerable(String insuredTypeCode, String laborCategory) {
    return trimmed(insuredTypeCode) != null && trimmed(laborCategory) != null;
  }

  /**
   * ИМЕТО НА ДВОЙКАТА С ЛИПСВАЩИ СТАВКИ — едно изречение, и ДВЕТЕ полета в него.
   *
   * <p>Тук е причината класът да съществува. Отказът на фиша носеше „вид осигурен: 01“ и нищо
   * повече, тоест назоваваше едното от двете полета, които съставят ключа. Операторът поправяше
   * това, което съобщението посочва, а сгрешеното беше другото.
   */
  public static String noRatesFor(String insuredTypeCode, String laborCategory) {
    return "вид осигурен "
        + orUnset(insuredTypeCode)
        + " + категория труд "
        + orUnset(laborCategory)
        + ": няма ставки за тази комбинация";
  }

  /**
   * РАЗМИНАВАНЕТО НА ЕДНА ДВОЙКА, или {@code null}, когато разминаване няма.
   *
   * <p>{@code null} се връща в три случая, и трите са „няма какво да се твърди“: незададен въпрос
   * (празно поле), мълчащ каталог (кодът няма нито един ред) и съгласувана двойка.
   *
   * @param categoriesWithRates категориите, за които каталогът носи ≥1 ред за ТОЗИ код; празен или
   *     {@code null} набор значи „каталогът мълчи за кода“, а не „нищо не е позволено“
   * @return изречение, което назовава ДВЕТЕ полета и къде се води всяко от тях, или {@code null}
   */
  public static String disagreement(
      String insuredTypeCode, String laborCategory, Set<String> categoriesWithRates) {

    String code = trimmed(insuredTypeCode);
    String category = trimmed(laborCategory);
    if (code == null || category == null) {
      return null;
    }
    Set<String> known = sorted(categoriesWithRates);
    if (known.isEmpty() || known.contains(category)) {
      return null;
    }
    return noRatesFor(code, category)
        + ". Каталогът "
        + CATALOGUE
        + " носи редове за вид осигурен "
        + code
        + " САМО за категория(и) "
        + String.join(", ", known)
        + ", тоест наборът ставки за тази двойка е ПРАЗЕН и BgContributionResolver няма да намери"
        + " нито един ред. Или видът осигурен е грешен, или категорията труд —"
        + " ⚠️ категорията се води на СЛУЖИТЕЛЯ (Служител → Категория труд,"
        + " Employee.bgLaborCategory), а видът осигурен на ДОГОВОРА"
        + " (EmploymentContract.bgInsuredTypeCode). Двете полета са на два различни екрана и точно"
        + " затова се назовават и двете.";
  }

  /** Подредено копие без празните стойности — за да е предвидим редът в съобщението. */
  private static Set<String> sorted(Set<String> categories) {
    Set<String> out = new TreeSet<>();
    if (categories != null) {
      for (String category : categories) {
        String c = trimmed(category);
        if (c != null) {
          out.add(c);
        }
      }
    }
    return out;
  }

  private static String orUnset(String value) {
    String v = trimmed(value);
    return v == null ? "(непопълнен)" : v;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
