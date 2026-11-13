package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сглобява листа на протокола по чл. 117 ЗДДС ({@link BgVatProtocolPrint}) от ОСЧЕТОВОДЕНАТА
 * статия.
 *
 * <p><b>⚠️ ЧИСЛАТА И НОМЕРЪТ СА НА ДНЕВНИЦИТЕ, НЕ НА СТАТИЯТА.</b> Номерът, датата, основата и
 * данъкът се четат от реда, който PRODAGBI/POKUPKI пишат за същата статия ({@link
 * BgVatMoveDiaryRows#rows}): к.09/к.10 на продажбите, закръглени по документ. Тоест протоколът на
 * хартия и редът в декларацията не могат да се разминат с една стотинка — и при сума с трета цифра,
 * и при закъснял протокол (ППЗДДС чл. 81 ал. 5: датата на издаване, а периодът — на изискуемостта).
 *
 * <p><b>Реквизитите</b> — ЗДДС чл. 117 ал. 2 т. 1–10 (консолидиран текст на lex.bg, последно изм.
 * ДВ бр. 69/2026, сверено 27.10.2026); ППЗДДС чл. 81 ал. 2–3 ги потвърждава за ВОП/чл. 82
 * (консолидиран текст, последно изм. ДВ бр. 54/2024). Останалото е от свой източник:
 *
 * <ul>
 *   <li>т. 3 и т. 4 — от фактурата на протокола ({@code Move.bgVatSourceInvoice}); ръчният протокол
 *       ги няма → ПРЕДУПРЕЖДЕНИЕ върху листа, не измислена стойност.
 *   <li>т. 7 — {@code Move.bgVatProtocolBasis}; празно (ръчен протокол) → изведено от операцията с
 *       ПРЕДУПРЕЖДЕНИЕ.
 *   <li>т. 9 (ВОП и услуги от ЕС) и т. 10 (чл. 163а, Прил. № 2 ч. II) — номерът на доставчика от
 *       дневника, номерът и датата на фактурата му ({@code Move.origin}/{@code originDate}).
 *   <li>Внос по чл. 167а — митническият документ (ЗДДС чл. 57 ал. 6 т. 2): {@code Move.origin} е
 *       MRN.
 * </ul>
 *
 * <p>Протоколите по чл. 79/79а не са тук: реквизитите им са по ППЗДДС чл. 66, не по чл. 117.
 */
@Singleton
public class BgVatProtocolPrintBuilder {

  /** чл. 117 ал. 1 т. 2 — „в случаите по чл. 57 — от регистрираното лице — вносител“. */
  static final String ISSUED_UNDER_117_1_2 = "чл. 117, ал. 1, т. 2 ЗДДС";

  /** т. 7 при внос по чл. 167а — протоколът е по чл. 57 ал. 6 ЗДДС. */
  static final String BASIS_IMPORT_167A = "чл. 57, ал. 6 ЗДДС";

  static final String OPERATION_IMPORT_167A = "IMPORT_167A";

  private static final BigDecimal HUNDRED = new BigDecimal(100);

  protected final BgVatMoveDiaryRows diaryRows;

  @Inject
  public BgVatProtocolPrintBuilder(BgVatMoveDiaryRows diaryRows) {
    this.diaryRows = diaryRows;
  }

  /**
   * Листът на протокола.
   *
   * @throws AxelorException статията не е протокол по чл. 117 в дневниците или още няма номер
   */
  public BgVatProtocolPrint build(Move move) throws AxelorException {
    String operation = BgVatProtocolPrint.nz(move.getBgVatMoveVatOperation());
    if (!isPrinted(operation)) {
      throw refusal(
          "Статията не е протокол по чл. 117 ЗДДС (ВОП, услуга по чл. 82, чл. 163а или внос по"
              + " чл. 167а). Протоколите по чл. 79/79а имат реквизити по ППЗДДС чл. 66 и не се"
              + " печатат оттук.");
    }
    BgVatMoveDiaryRows.Row row =
        diaryRows.rows(move).stream()
            .filter(each -> !each.getDocument().isIdentificationOnly() && each.isSales())
            .findFirst()
            .orElseThrow(
                () ->
                    refusal(
                        "Протоколът не влиза в дневника за продажбите — проверете статуса на"
                            + " статията и ДДС операцията ѝ."));
    BgVatDocumentDto document = row.getDocument();
    if (BgVatProtocolPrint.nz(move.getBgVatProtocolNumber()).isEmpty()) {
      throw refusal(
          "Протоколът още няма номер (ЗДДС чл. 117, ал. 2, т. 1) — номерът се дава при"
              + " осчетоводяване, а на заварен протокол се въвежда с „Въведи номера на издадения"
              + " протокол“.");
    }

    BgVatProtocolPrint print = new BgVatProtocolPrint();
    Invoice invoice = move.getBgVatSourceInvoice();

    // т. 1, т. 5, т. 8 — от реда в дневниците.
    print.number = BgVatProtocolPrint.nz(document.getDocumentNumber());
    print.date = document.getDocumentDate();
    print.base = row.getSalesTotalBase();
    print.tax = row.getSalesTotalVat();
    print.supplyType = BgVatProtocolPrint.nz(document.getSupplyTypeArt163a());
    print.diaryPeriod =
        move.getDate() == null
            ? ""
            : String.format("%02d.%d", move.getDate().getMonthValue(), move.getDate().getYear());

    print.issuedUnder = issuedUnder(operation);
    print.kindLabel = kindLabel(operation, print.supplyType);
    issuer(print, move.getCompany());
    print.currency =
        move.getCompany() != null && move.getCompany().getCurrency() != null
            ? BgVatProtocolPrint.nz(move.getCompany().getCurrency().getCodeISO())
            : "";

    rate(print, move);
    basis(print, move, operation);
    counterparty(print, move, document, operation);
    goodsAndEvent(print, move, invoice, operation);
    return print;
  }

  /** Печатат се протоколите по чл. 117 ал. 1 т. 1, 2 и 4 — операциите на самоначисляването. */
  static boolean isPrinted(String operation) {
    switch (operation) {
      case BgVatMoveOperationRouting.OPERATION_VOP:
      case BgVatMoveOperationRouting.OPERATION_SERVICE_EU:
      case BgVatMoveOperationRouting.OPERATION_SERVICE_NONEU:
      case BgVatMoveOperationRouting.OPERATION_ART163A:
      case OPERATION_IMPORT_167A:
        return true;
      default:
        return false;
    }
  }

  /**
   * Точката на чл. 117 ал. 1: т. 1 — чл. 82 ал. 2–6 и чл. 84 (получателят); т. 2 — чл. 57
   * (вносителят); т. 4 — чл. 163а ал. 2 (получателят).
   */
  static String issuedUnder(String operation) {
    switch (operation) {
      case BgVatMoveOperationRouting.OPERATION_ART163A:
        return BgVatInvoiceProtocolCase.ISSUED_UNDER_117_1_4;
      case OPERATION_IMPORT_167A:
        return ISSUED_UNDER_117_1_2;
      default:
        return BgVatInvoiceProtocolCase.ISSUED_UNDER_117_1_1;
    }
  }

  static String kindLabel(String operation, String supplyType) {
    switch (operation) {
      case BgVatMoveOperationRouting.OPERATION_VOP:
        return "Вътреобщностно придобиване на стоки";
      case BgVatMoveOperationRouting.OPERATION_SERVICE_EU:
        return "Получена доставка от лице, установено в друга държава членка";
      case BgVatMoveOperationRouting.OPERATION_SERVICE_NONEU:
        return "Получена доставка от лице, установено извън Европейския съюз";
      case BgVatMoveOperationRouting.OPERATION_ART163A:
        return BgVatInvoiceProtocolCase.SUPPLY_ANNEX2_PART2.equals(supplyType)
            ? "Доставка по чл. 163а ЗДДС — Приложение № 2, част втора"
            : "Доставка по чл. 163а ЗДДС — Приложение № 2, част първа";
      default:
        return "Внос с отложено начисляване на данъка по чл. 167а ЗДДС";
    }
  }

  /** т. 2 — регистрираното лице, което издава протокола: името и ИН по чл. 94 ал. 2 („BG…“). */
  protected void issuer(BgVatProtocolPrint print, Company company) {
    if (company == null) {
      print.warnings.add("т. 2: статията няма дружество.");
      return;
    }
    print.issuerName =
        company.getPartner() != null
                && !BgVatProtocolPrint.nz(company.getPartner().getFullName()).isEmpty()
            ? BgVatProtocolPrint.nz(company.getPartner().getFullName())
            : BgVatProtocolPrint.nz(company.getName());
    print.issuerVatNumber =
        company.getPartner() == null ? "" : BgVatProtocolPrint.nz(company.getPartner().getTaxNbr());
    if (print.issuerVatNumber.isEmpty()) {
      print.warnings.add(
          "т. 2: дружеството няма ДДС номер (ИН по чл. 94, ал. 2 ЗДДС) — попълнете го в"
              + " партньора на дружеството.");
    }
  }

  /** т. 6 — ставката, както я чете колекторът: ръчната, иначе тази по редовете. */
  protected void rate(BgVatProtocolPrint print, Move move) {
    Set<BigDecimal> rates = diaryRows.protocolRates(move);
    if (rates.isEmpty()) {
      print.rateLabel = "";
      print.warnings.add("т. 6: протоколът няма ставка — нито ръчна, нито по редовете.");
      return;
    }
    List<String> labels =
        rates.stream().map(BgVatProtocolPrintBuilder::percent).collect(Collectors.toList());
    if (labels.size() == 1) {
      print.rateLabel = labels.get(0);
      return;
    }
    print.rateLabel = "смесени: " + String.join(", ", labels);
    print.warnings.add(
        "т. 6: протоколът е със смесени ставки ("
            + String.join(", ", labels)
            + "); основата и данъкът са общите от дневника.");
  }

  /** т. 7 — основанието; празно → изведено от операцията с предупреждение. */
  protected void basis(BgVatProtocolPrint print, Move move, String operation) {
    String recorded = BgVatProtocolPrint.nz(move.getBgVatProtocolBasis());
    if (!recorded.isEmpty()) {
      print.basis = recorded;
      return;
    }
    switch (operation) {
      case BgVatMoveOperationRouting.OPERATION_VOP:
        print.basis = BgVatInvoiceProtocolCase.BASIS_VOP;
        break;
      case BgVatMoveOperationRouting.OPERATION_ART163A:
        print.basis = BgVatInvoiceProtocolCase.BASIS_ART163A;
        break;
      case OPERATION_IMPORT_167A:
        print.basis = BASIS_IMPORT_167A;
        // Основанието при внос не се записва на статията и не е изведено: протоколът по чл. 167а
        // е винаги по чл. 57 ал. 6 — предупреждение няма.
        return;
      default:
        // ⚠️ Услуга по чл. 82 ал. 2 т. 3 — най-честият случай; енергията (т. 1) е друго
        // основание и се вижда само от протокола от фактура. Да се потвърди за ръчните.
        print.basis = BgVatInvoiceProtocolCase.BASIS_ART82_SERVICES;
        break;
    }
    print.warnings.add(
        "т. 7: основанието не е записано на ръчния протокол — изведено от ДДС операцията като „"
            + print.basis
            + "“; проверете го.");
  }

  /**
   * т. 9 / т. 10 — доставчикът и фактурата му; при внос по чл. 167а — митническият документ.
   *
   * <p>Номерът на доставчика е този от дневника ({@code counterpartyVatNumber}, иначе {@code
   * counterpartyDiaryId}) — листът и PRODAGBI не спорят кой е доставчикът.
   */
  protected void counterparty(
      BgVatProtocolPrint print, Move move, BgVatDocumentDto document, String operation) {
    print.supplierName =
        move.getPartner() == null ? "" : BgVatProtocolPrint.nz(move.getPartner().getFullName());
    String vatNumber = BgVatProtocolPrint.nz(document.getCounterpartyVatNumber());
    print.supplierId =
        vatNumber.isEmpty() ? BgVatProtocolPrint.nz(document.getCounterpartyDiaryId()) : vatNumber;

    if (OPERATION_IMPORT_167A.equals(operation)) {
      // ЗДДС чл. 57 ал. 6 т. 2: „посочи митническия документ за внос“; Move.origin е MRN.
      print.customsDocument = BgVatProtocolPrint.nz(move.getOrigin());
      print.customsDate = move.getOriginDate();
      print.supplierIdLabel = "Идентификационен номер на доставчика";
      if (print.customsDocument.isEmpty()) {
        print.warnings.add(
            "Митническият документ за внос (MRN) не е записан в „Произход“ на статията (ЗДДС чл."
                + " 57, ал. 6, т. 2).");
      }
      return;
    }

    print.invoiceNumber = BgVatProtocolPrint.nz(move.getOrigin());
    print.invoiceDate = move.getOriginDate();
    switch (operation) {
      case BgVatMoveOperationRouting.OPERATION_VOP:
      case BgVatMoveOperationRouting.OPERATION_SERVICE_EU:
        // т. 9 — „…когато доставчикът е регистриран за целите на ДДС в друга държава членка, и
        // номер и дата на фактурата - когато такава е издадена до датата на издаване на протокола“.
        print.supplierIdPoint = "т. 9";
        print.supplierIdLabel =
            "ИН по ДДС на доставчика в другата държава членка; номер и дата на фактурата";
        break;
      case BgVatMoveOperationRouting.OPERATION_ART163A:
        if (BgVatInvoiceProtocolCase.SUPPLY_ANNEX2_PART2.equals(print.supplyType)) {
          // т. 10 — „идентификационен номер по чл. 84 от ДОПК на доставчика на стоки по
          // приложение № 2, част втора, и номер и дата на фактурата“.
          print.supplierIdPoint = "т. 10";
          print.supplierIdLabel = "ИН по чл. 84 ДОПК на доставчика; номер и дата на фактурата";
        } else {
          print.supplierIdLabel = "Идентификационен номер на доставчика; номер и дата на фактурата";
        }
        break;
      default:
        print.supplierIdLabel = "Идентификационен номер на доставчика; номер и дата на фактурата";
        break;
    }
    if (!print.supplierIdPoint.isEmpty()) {
      if (print.supplierId.isEmpty()) {
        print.warnings.add(print.supplierIdPoint + ": доставчикът няма идентификационен номер.");
      }
      if (print.invoiceNumber.isEmpty() || print.invoiceDate == null) {
        print.warnings.add(
            print.supplierIdPoint
                + ": номерът или датата на фактурата на доставчика не са записани („Произход“ и"
                + " „Дата на произход“ на статията).");
      }
    }
  }

  /**
   * т. 3 и т. 4. Протоколът от фактура ги взема от нея: редовете, които правят ТОЗИ протокол
   * (същата операция, ставка и основание — {@link BgVatInvoiceProtocolPlan#sameProtocol},
   * правилото, по което колекторът брои фактурата за покрита), и датата на данъчното събитие на
   * фактурата, иначе датата ѝ от доставчика — както {@code
   * BgVatDocumentCollector.applyInvoiceProtocols}.
   */
  protected void goodsAndEvent(
      BgVatProtocolPrint print, Move move, Invoice invoice, String operation) {
    if (invoice != null) {
      LocalDate event =
          invoice.getBgVatTaxEventDate() != null
              ? invoice.getBgVatTaxEventDate()
              : invoice.getOriginDate();
      print.taxEventDate = event;
      BgVatInvoiceProtocolCase.Supplier supplier = BgVatInvoiceProtocolCase.supplierOf(invoice);
      for (InvoiceLine line : lines(invoice)) {
        if (line.getTaxEquiv() == null || line.getTaxEquiv().getReverseChargeTaxSet() == null) {
          continue;
        }
        BgVatInvoiceProtocolCase kase = BgVatInvoiceProtocolCase.of(line, supplier, event);
        if (kase.getOutcome() != BgVatInvoiceProtocolCase.Outcome.PROTOCOL) {
          continue;
        }
        BgVatInvoiceProtocolPlan.Line planned =
            new BgVatInvoiceProtocolPlan.Line(
                kase,
                BgVatInvoiceProtocolCase.reverseChargeRate(line),
                line.getCompanyExTaxTotal(),
                null,
                null);
        BgVatInvoiceProtocolPlan.Group group =
            BgVatInvoiceProtocolPlan.groups(Collections.singletonList(planned)).get(0);
        if (BgVatInvoiceProtocolPlan.sameProtocol(
            group,
            move.getBgVatMoveVatOperation(),
            move.getBgVatMoveVatRate(),
            move.getBgVatProtocolBasis())) {
          print.items.add(
              new BgVatProtocolPrint.Item(
                  line.getProductName(),
                  quantity(line.getQty()),
                  line.getUnit() == null ? "" : line.getUnit().getName()));
        }
      }
      if (print.items.isEmpty()) {
        print.warnings.add("т. 3: във фактурата на протокола няма ред, който да го прави.");
      }
      if (event == null) {
        print.warnings.add("т. 4: фактурата няма нито дата на данъчното събитие, нито дата.");
      }
      return;
    }

    // Ръчен протокол: редове на стоката няма, остава описанието на статията.
    String description = BgVatProtocolPrint.nz(move.getDescription());
    print.items.add(new BgVatProtocolPrint.Item(description, "", ""));
    print.warnings.add(
        "т. 3: ръчният протокол няма редове със стоката/услугата — показано е описанието на"
            + " статията, без количеството.");
    if (OPERATION_IMPORT_167A.equals(operation)) {
      // ⚠️ Данъчното събитие при внос е по чл. 54 — датата на вноса. Приема се датата на
      // митническия документ (Move.originDate). Да се потвърди.
      print.taxEventDate = move.getOriginDate();
      if (print.taxEventDate == null) {
        print.warnings.add("т. 4: датата на митническия документ не е записана.");
      }
      return;
    }
    print.warnings.add(
        "т. 4: датата на данъчното събитие не се записва на ръчния протокол — тя е само на"
            + " протокола от фактура („Дата на данъчното събитие“ на фактурата).");
  }

  private static List<InvoiceLine> lines(Invoice invoice) {
    return invoice.getInvoiceLineList() == null ? new ArrayList<>() : invoice.getInvoiceLineList();
  }

  /** „20 %“ от дроб 0.20. */
  static String percent(BigDecimal fraction) {
    BigDecimal value = fraction.multiply(HUNDRED).stripTrailingZeros();
    return value.toPlainString().replace('.', ',') + " %";
  }

  /** „2,5“; празно при нула или без количество. */
  static String quantity(BigDecimal qty) {
    if (qty == null || qty.signum() == 0) {
      return "";
    }
    return qty.stripTrailingZeros().toPlainString().replace('.', ',');
  }

  private static AxelorException refusal(String message) {
    return new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, "%s", message);
  }
}
