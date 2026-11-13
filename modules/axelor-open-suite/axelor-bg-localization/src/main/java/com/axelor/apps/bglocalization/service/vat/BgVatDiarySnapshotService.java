package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.db.BgVatPurchaseLine;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.BgVatSalesLine;
import com.axelor.apps.bglocalization.db.repo.BgVatPurchaseLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatSalesLineRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Freezes the sales/purchase diary rows produced by a generate() run onto the {@link BgVatReturn}
 * as persisted {@link BgVatSalesLine} / {@link BgVatPurchaseLine} records. These rows are the
 * single source the BIRT дневник reports read (report == TXT): they come from the SAME collector
 * pass / adapter output that produced PRODAGBI.TXT / POKUPKI.TXT (see {@code BgVatExportService}).
 *
 * <p><b>Regenerate = replace.</b> {@link #snapshot} first bulk-deletes every existing line of the
 * return by parent id (deterministic, does not rely on the form collection state), then inserts the
 * fresh rows. {@code orphanRemoval=true} on the O2M is the domain-level backstop.
 */
@Singleton
public class BgVatDiarySnapshotService {

  protected final BgVatReturnRepository returnRepo;
  protected final BgVatSalesLineRepository salesLineRepo;
  protected final BgVatPurchaseLineRepository purchaseLineRepo;

  @Inject
  public BgVatDiarySnapshotService(
      BgVatReturnRepository returnRepo,
      BgVatSalesLineRepository salesLineRepo,
      BgVatPurchaseLineRepository purchaseLineRepo) {
    this.returnRepo = returnRepo;
    this.salesLineRepo = salesLineRepo;
    this.purchaseLineRepo = purchaseLineRepo;
  }

  /**
   * Replace the persisted diary lines of the given return with the supplied rows. No-op on a null
   * id (record must be saved first — the generate button saves before invoking the action).
   */
  @Transactional
  public void snapshot(
      Long bgVatReturnId,
      List<BgVatSalesRowDto> salesRows,
      List<BgVatPurchaseRowDto> purchaseRows) {
    if (bgVatReturnId == null) {
      return;
    }
    // Преди триенето: ред, който колоните не могат да поберат, спира всичко с причина на български,
    // вместо ConstraintViolationException при запис, която формата показваше като английски текст.
    List<String> problems = amountProblems(salesRows, purchaseRows);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Дневниците не могат да се запишат — сумите не се побират в колоните (до "
              + INTEGER_DIGITS
              + " цифри и "
              + FRACTION_DIGITS
              + " знака след десетичната запетая): "
              + String.join("; ", problems));
    }
    BgVatReturn ret = returnRepo.find(bgVatReturnId);
    if (ret == null) {
      return;
    }

    // Invariant 2: bulk-delete existing lines by parent, then insert fresh (no duplicates on
    // regenerate; independent of any in-form collection state).
    salesLineRepo.all().filter("self.bgVatReturn.id = :id").bind("id", bgVatReturnId).delete();
    purchaseLineRepo.all().filter("self.bgVatReturn.id = :id").bind("id", bgVatReturnId).delete();

    if (salesRows != null) {
      for (BgVatSalesRowDto row : salesRows) {
        salesLineRepo.save(toSalesLine(ret, row));
      }
    }
    if (purchaseRows != null) {
      for (BgVatPurchaseRowDto row : purchaseRows) {
        purchaseLineRepo.save(toPurchaseLine(ret, row));
      }
    }
  }

  /**
   * Колоните на BgVatSalesLine/BgVatPurchaseLine са {@code scale="2" precision="18"}, тоест
   * {@code @Digits(integer = 16, fraction = 2)}. Нулите отзад не се броят — както при валидацията.
   */
  static final int FRACTION_DIGITS = 2;

  static final int INTEGER_DIGITS = 16;

  /** Всяка сума, която колоната не побира: дневник, документ, колона, стойност и защо. */
  public static List<String> amountProblems(
      List<BgVatSalesRowDto> salesRows, List<BgVatPurchaseRowDto> purchaseRows) {
    List<String> problems = new ArrayList<>();
    if (salesRows != null) {
      for (BgVatSalesRowDto row : salesRows) {
        row.getAmounts()
            .forEach(
                (column, value) ->
                    check(
                        problems, "продажби", row.getDocumentNumber(), column.getNapCode(), value));
      }
    }
    if (purchaseRows != null) {
      for (BgVatPurchaseRowDto row : purchaseRows) {
        row.getAmounts()
            .forEach(
                (column, value) ->
                    check(
                        problems, "покупки", row.getDocumentNumber(), column.getNapCode(), value));
      }
    }
    return problems;
  }

  private static void check(
      List<String> problems, String diary, String document, String column, BigDecimal value) {
    if (value == null || value.signum() == 0) {
      return;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    int fraction = Math.max(stripped.scale(), 0);
    int integer = stripped.precision() - stripped.scale();
    String why;
    if (fraction > FRACTION_DIGITS) {
      why = "повече от " + FRACTION_DIGITS + " знака след десетичната запетая";
    } else if (integer > INTEGER_DIGITS) {
      why = "повече от " + INTEGER_DIGITS + " цифри преди десетичната запетая";
    } else {
      return;
    }
    problems.add(
        "дневник за "
            + diary
            + ", документ № "
            + (document == null || document.trim().isEmpty() ? "без номер" : document.trim())
            + ", кл."
            + column
            + " = "
            + value.toPlainString()
            + " — "
            + why);
  }

  protected BgVatSalesLine toSalesLine(BgVatReturn ret, BgVatSalesRowDto row) {
    BgVatSalesLine line = new BgVatSalesLine();
    line.setBgVatReturn(ret);
    line.setCompanyVatNumber(row.getCompanyVatNumber());
    line.setPeriodCode(row.getPeriodCode());
    line.setBranch(row.getBranch());
    line.setSequence(row.getSequence());
    line.setNapDocumentType(row.getNapDocumentType());
    line.setDocumentNumber(row.getDocumentNumber());
    line.setDocumentDate(row.getDocumentDate());
    line.setCounterpartyVatNumber(row.getCounterpartyVatNumber());
    line.setCounterpartyName(row.getCounterpartyName());
    line.setSubject(row.getSubject());
    line.setSupplyTypeArt163a(row.getSupplyTypeArt163a());
    line.setCol09(row.getAmount(BgVatSalesColumn.K09_TOTAL_BASE));
    line.setCol10(row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT));
    line.setCol11(row.getAmount(BgVatSalesColumn.K11_BASE_20));
    line.setCol12(row.getAmount(BgVatSalesColumn.K12_VAT_20));
    line.setCol13(row.getAmount(BgVatSalesColumn.K13_BASE_ICA));
    line.setCol14(row.getAmount(BgVatSalesColumn.K14_BASE_RC));
    line.setCol15(row.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    line.setCol16(row.getAmount(BgVatSalesColumn.K16_VAT_SELF_SUPPLY));
    line.setCol17(row.getAmount(BgVatSalesColumn.K17_BASE_9));
    line.setCol18(row.getAmount(BgVatSalesColumn.K18_VAT_9));
    line.setCol19(row.getAmount(BgVatSalesColumn.K19_BASE_ZERO_CH3));
    line.setCol20(row.getAmount(BgVatSalesColumn.K20_BASE_ICS));
    line.setCol21(row.getAmount(BgVatSalesColumn.K21_BASE_ART140_146_173));
    line.setCol22(row.getAmount(BgVatSalesColumn.K22_BASE_EU_SERVICES));
    line.setCol23(row.getAmount(BgVatSalesColumn.K23_BASE_ART69_2));
    line.setCol24(row.getAmount(BgVatSalesColumn.K24_BASE_EXEMPT));
    line.setCol25(row.getAmount(BgVatSalesColumn.K25_BASE_TRIANGULAR));
    return line;
  }

  protected BgVatPurchaseLine toPurchaseLine(BgVatReturn ret, BgVatPurchaseRowDto row) {
    BgVatPurchaseLine line = new BgVatPurchaseLine();
    line.setBgVatReturn(ret);
    line.setCompanyVatNumber(row.getCompanyVatNumber());
    line.setPeriodCode(row.getPeriodCode());
    line.setBranch(row.getBranch());
    line.setSequence(row.getSequence());
    line.setNapDocumentType(row.getNapDocumentType());
    line.setDocumentNumber(row.getDocumentNumber());
    line.setDocumentDate(row.getDocumentDate());
    line.setCounterpartyVatNumber(row.getCounterpartyVatNumber());
    line.setCounterpartyName(row.getCounterpartyName());
    line.setSubject(row.getSubject());
    line.setSupplyTypeArt163a(row.getSupplyTypeArt163a());
    line.setCol09(row.getAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
    line.setCol10(row.getAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    line.setCol11(row.getAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    line.setCol12(row.getAmount(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT));
    line.setCol13(row.getAmount(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));
    line.setCol14(row.getAmount(BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT));
    line.setCol15(row.getAmount(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ));
    return line;
  }
}
