package com.axelor.apps.bglocalization.service.vat.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Защо статии и документи с ДДС НЕ са в дневниците на периода — за съобщението след „Генерирай“.
 *
 * <p>Първите пет списъка са статии, които събирачът не е взел изобщо ({@code
 * BgVatDocumentCollector#findPostedMoves}); последните три — документи, които е взел, но които не
 * са попаднали в нито един дневник. Всеки списък носи номера на статията или документа; броят е
 * размерът му.
 */
public class BgVatExclusionsDto {

  /** Статии на дружеството в периода в „Дневник“ (DAYBOOK), още не осчетоводени окончателно. */
  private final List<String> daybookMoves = new ArrayList<>();

  /** Статии на дружеството в периода, които са чернови (NEW) или симулирани. */
  private final List<String> draftMoves = new ArrayList<>();

  /**
   * Осчетоводени статии на фактури, чиято дата на данъчното събитие (или на фактурата) е в периода,
   * а датата на статията — не. Дневникът се води по датата на статията.
   */
  private final List<String> outsidePeriodMoves = new ArrayList<>();

  /**
   * Осчетоводени статии на фактури, отразени в ДРУГ данъчен период („Период на отразяване по
   * ЗДДС“), с дата на статията или на данъчното събитие в този — с периода, в който са.
   */
  private final List<String> reportedElsewhereMoves = new ArrayList<>();

  /** Осчетоводени статии в периода на фактури на дружеството, записани в ДРУГО дружество. */
  private final List<String> otherCompanyMoves = new ArrayList<>();

  /** Покупни фактури с нулеви основа и ДДС във валутата на дружеството на всеки ред. */
  private final List<String> zeroAmountDocuments = new ArrayList<>();

  /**
   * Фактури с обратно начисляване, които сами не са в дневник (там е протоколът по чл. 117 ЗДДС).
   */
  private final List<String> reverseChargeDocuments = new ArrayList<>();

  /** Взети документи, които не са в нито един дневник по друга причина. */
  private final List<String> otherDroppedDocuments = new ArrayList<>();

  public List<String> getDaybookMoves() {
    return daybookMoves;
  }

  public List<String> getDraftMoves() {
    return draftMoves;
  }

  public List<String> getOutsidePeriodMoves() {
    return outsidePeriodMoves;
  }

  public List<String> getReportedElsewhereMoves() {
    return reportedElsewhereMoves;
  }

  public List<String> getOtherCompanyMoves() {
    return otherCompanyMoves;
  }

  public List<String> getZeroAmountDocuments() {
    return zeroAmountDocuments;
  }

  public List<String> getReverseChargeDocuments() {
    return reverseChargeDocuments;
  }

  public List<String> getOtherDroppedDocuments() {
    return otherDroppedDocuments;
  }

  public boolean isEmpty() {
    return daybookMoves.isEmpty()
        && draftMoves.isEmpty()
        && outsidePeriodMoves.isEmpty()
        && reportedElsewhereMoves.isEmpty()
        && otherCompanyMoves.isEmpty()
        && zeroAmountDocuments.isEmpty()
        && reverseChargeDocuments.isEmpty()
        && otherDroppedDocuments.isEmpty();
  }
}
