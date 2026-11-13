package com.axelor.apps.bglocalization.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.batch.BgBatchRealizeFixedAssetLine.TaxPlanSelection;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Регресионни тестове за {@code LazyInitializationException: could not initialize proxy
 * [AccountingBatch#N] - no Session}, което спираше цялото месечно начисляване.
 *
 * <p>Базовият {@code realizeFixedAssetLineList} вика {@code JPA.clear()} след всеки реализиран ред.
 * Тогавашният код четеше {@code batch.getAccountingBatch()} СЛЕД базовата обработка — вече откачено
 * прокси без сесия. Грешката излиташе до {@code AbstractBatch.run} и батчът спираше преди {@code
 * stop()}: без крайна дата, без коментар, без броячи. Отвън: „нищо не се случва".
 *
 * <p>Тестовете са само върху статична логика и върху структурата на снимката. Самият батч НЕ се
 * инстанцира: конструкторът на {@code AbstractBatch} вика {@code JPA.runInTransaction}, тъй че
 * всеки негов наследник изисква жив JPA контекст, какъвто чистите тестове на този модул нямат.
 */
class BgBatchRealizeFixedAssetLineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);
  private static final long COMPANY_ID = 7L;

  /**
   * Същината на поправката: снимката пази САМО откъснати от сесията стойности. Сложи ли някой пак
   * {@code Company} (или което и да е entity) в нея, то ще е Hibernate прокси, {@code JPA.clear()}
   * ще го откачи и батчът ще гръмне отново по същия начин. Затова проверката е върху типовете, а не
   * върху стойностите.
   */
  @Test
  void theSnapshotHoldsNoEntityReferencesSoJpaClearCannotBreakItAgain() {
    Set<Class<?>> detachedSafe =
        Set.of(LocalDate.class, Long.class, String.class, Integer.class, boolean.class);

    for (Field field : TaxPlanSelection.class.getDeclaredFields()) {
      if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      assertTrue(
          detachedSafe.contains(field.getType()),
          "TaxPlanSelection."
              + field.getName()
              + " е от тип "
              + field.getType().getName()
              + " — само стойности оцеляват след JPA.clear(), entity-та стават мъртви проксита");
    }
  }

  /**
   * Догонването на данъчния план подбира по {@code fiscalFixedAsset} — икономическите редове вече
   * са минали през базовия пас. С {@code updateAll} подборът е „всичко с минала дата", тоест ВСИЧКИ
   * просрочени месеци наведнъж, а не само последният период.
   */
  @Test
  void everyOverdueTaxLineIsSelectedByTheFiscalPlanForTheBatchCompany() {
    TaxPlanSelection selection = new TaxPlanSelection(null, null, true, COMPANY_ID, TODAY);

    assertEquals(
        "self.statusSelect = :statusSelect AND self.fiscalFixedAsset.company.id = :companyId"
            + " AND self.depreciationDate < :dateNow",
        BgBatchRealizeFixedAssetLine.buildTaxLineQuery(selection));

    Map<String, Object> params = BgBatchRealizeFixedAssetLine.buildTaxLineParams(selection);
    assertEquals(COMPANY_ID, params.get("companyId"));
    assertEquals(TODAY, params.get("dateNow"));
  }

  /** Зададен период стеснява подбора до него — поведението на базовия батч, запазено. */
  @Test
  void anExplicitPeriodNarrowsTheSelectionToThatRange() {
    TaxPlanSelection selection =
        new TaxPlanSelection(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), false, COMPANY_ID, TODAY);

    assertEquals(
        "self.statusSelect = :statusSelect AND self.fiscalFixedAsset.company.id = :companyId"
            + " AND self.depreciationDate <= :endDate AND self.depreciationDate >= :startDate",
        BgBatchRealizeFixedAssetLine.buildTaxLineQuery(selection));
  }
}
