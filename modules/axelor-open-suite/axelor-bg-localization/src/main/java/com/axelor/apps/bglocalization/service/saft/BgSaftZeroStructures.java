package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransactions;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovement;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovementLine;
import java.math.BigDecimal;

/**
 * НУЛЕВИТЕ СТРУКТУРИ ЗА ПЕРИОД БЕЗ ДВИЖЕНИЕ — QA IV.2.6 (с. 97), за годишния файл и за файла при
 * поискване.
 *
 * <p><b>⚠️ КНИГАТА И СХЕМАТА СИ ПРОТИВОРЕЧАТ, QA ГИ СВЪРЗВА.</b> SD.AT.2: „AssetTransaction не е
 * задължително, когато нямате никакви активи … NumberOfAssetTransactions = 0“; SD.MG.4: „Не се
 * подава информация, когато полето Брой на записите е равно на 0“. Схемата обаче иска поне един
 * AssetTransaction и поне един StockMovement — без тях файлът пада на XSD-то. Отговорът на QA
 * IV.2.6 е общо правило за „секции/подсекции … за период, в който няма движение“: броят е „0“ и се
 * подава ЕДНА структура с нулеви стойности.
 *
 * <p><b>⚠️ СТОЙНОСТИТЕ СА ТЕЗИ НА ПРИМЕРИТЕ В QA</b> — идентификаторите и кодовете „0“, датата
 * 1900-01-01, сметката 998 (няма я в номенклатурата — следва се QA, както в {@link
 * BgSaftInvoicesAssembler#emptyInvoice()}), ред № 1, коефициент 1, суми 0.00. Двете структури тук
 * нямат елемент с валута; където има, тя е EUR (S.H.9), а не BGN от примерите — те са писани преди
 * еврото.
 */
public final class BgSaftZeroStructures {

  /** Идентификатор и код без стойност — „0“, както PaymentRefNo и PaymentMethod в примера. */
  static final String ZERO = "0";

  /** Номер на ред — „1“, както LineNumber в примерите. */
  static final String FIRST_LINE = "1";

  private BgSaftZeroStructures() {}

  /** AssetTransactions за година без транзакции с активи. */
  public static SaftAssetTransactions assetTransactions() {
    return new SaftAssetTransactions()
        .numberOfAssetTransactions(0L)
        .addAssetTransaction(
            new SaftAssetTransaction()
                .assetTransactionId(ZERO)
                .assetId(ZERO)
                .assetTransactionType(ZERO)
                .assetTransactionDate(BgSaftInvoicesAssembler.EMPTY_DATE)
                .transactionId(ZERO)
                .bookValueOnTransaction(money())
                .assetTransactionAmount(money()));
  }

  /** MovementOfGoods за период без движения на запаси. */
  public static SaftMovementOfGoods movementOfGoods() {
    return new SaftMovementOfGoods()
        .numberOfMovementLines(0L)
        .totalQuantityReceived(BigDecimal.ZERO)
        .totalQuantityIssued(BigDecimal.ZERO)
        .addStockMovement(
            new SaftStockMovement()
                .movementReference(ZERO)
                .movementDate(BgSaftInvoicesAssembler.EMPTY_DATE)
                .movementType(ZERO)
                .sourceId(ZERO)
                .systemId(ZERO)
                .documentType(ZERO)
                .documentNumber(ZERO)
                .addLine(
                    new SaftStockMovementLine()
                        .lineNumber(FIRST_LINE)
                        .accountId(BgSaftInvoicesAssembler.EMPTY_ACCOUNT)
                        .transactionId(ZERO)
                        .customerId(ZERO)
                        .supplierId(ZERO)
                        .productCode(BgSaftInvoicesAssembler.NO_PRODUCT)
                        .quantity(BigDecimal.ZERO)
                        .unitOfMeasure(ZERO)
                        .uomToUomPhysicalStockConversionFactor(BigDecimal.ONE)
                        .bookValue(money())
                        .movementSubType(ZERO)));
  }

  /**
   * Нулевата ли е структурата — броят по QA IV.2.6. Таблиците, които зависят от движенията
   * (MovementTypeTable), я познават по него.
   */
  public static boolean isZero(SaftMovementOfGoods goods) {
    return goods != null
        && goods.getNumberOfMovementLines() != null
        && goods.getNumberOfMovementLines() == 0L;
  }

  private static BigDecimal money() {
    return BigDecimal.ZERO.setScale(2);
  }
}
