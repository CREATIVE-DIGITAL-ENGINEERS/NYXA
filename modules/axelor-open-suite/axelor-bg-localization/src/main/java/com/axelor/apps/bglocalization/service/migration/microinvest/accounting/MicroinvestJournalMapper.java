package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.repo.MoveRepository;
import java.util.Locale;

/**
 * Maps a Microinvest document type plus the resolved direction onto a Nyxa journal code and a
 * functional origin.
 *
 * <p>The journal code is matched against journals that must ALREADY exist for the company — this
 * class never creates or reconfigures anything. Preflight verifies existence, status, sequence and
 * that the journal authorises the origin returned here; a mismatch is reported as a blocker naming
 * the exact operator action.
 *
 * <p>⚠️ A null functional origin is a deliberate, valid outcome, not a workaround. Axelor's
 * enumeration has no value for "miscellaneous accounting operation" — a Bulgarian мемориален ордер
 * or a ЗДДС протокол is none of Sale, Purchase, Payment, Opening, Closure, Cut-off, Fixed asset,
 * Doubtful customer or Irrecoverable — and {@code
 * MoveValidateServiceImpl.checkFunctionalOriginSelect} returns silently for null. Inventing one of
 * the nine would misclassify the document in every downstream report.
 */
public final class MicroinvestJournalMapper {

  /** A journal code plus the functional origin to post with (null = leave unset). */
  public static final class Target {
    public final String journalCode;
    public final Integer functionalOrigin;

    Target(String journalCode, Integer functionalOrigin) {
      this.journalCode = journalCode;
      this.functionalOrigin = functionalOrigin;
    }
  }

  public static final String DIRECTION_SALE = "SALE";
  public static final String DIRECTION_PURCHASE = "PURCHASE";

  /** The opening-balance move always goes to the carried-balance journal. */
  public static final Target OPENING = new Target("RPB", MoveRepository.FUNCTIONAL_ORIGIN_OPENING);

  private MicroinvestJournalMapper() {}

  /**
   * @param documentTypeShortName Microinvest DocumentTypes.ShortName, e.g. "Ф-ра", "МО", "БИ"
   * @param direction SALE / PURCHASE / UNDEFINED, as resolved by the exporter
   * @return the target, or {@code null} when this document type has no mapping — Preflight then
   *     blocks and names the document type, rather than the importer guessing a journal
   */
  public static Target map(String documentTypeShortName, String direction) {
    String doc = documentTypeShortName == null ? "" : documentTypeShortName.trim();
    boolean sale = DIRECTION_SALE.equalsIgnoreCase(direction);
    boolean purchase = DIRECTION_PURCHASE.equalsIgnoreCase(direction);

    switch (normalize(doc)) {
      case "Ф-РА":
      case "Ф-РА(КО)":
      case "ДИ":
      case "ДИ(КО)":
        // Invoices and debit notes follow the direction.
        if (sale) {
          return new Target("SAL", MoveRepository.FUNCTIONAL_ORIGIN_SALE);
        }
        if (purchase) {
          return new Target("PCH", MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE);
        }
        return null;
      case "КИ":
      case "КИ(КО)":
      case "КИ126Б":
        if (sale) {
          return new Target("RFS", MoveRepository.FUNCTIONAL_ORIGIN_SALE);
        }
        if (purchase) {
          return new Target("RFP", MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE);
        }
        return null;
      case "МД":
        // Митническа декларация — always an import, i.e. a purchase, whatever the header says.
        return new Target("PCH", MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE);
      case "ПКО":
      case "РКО":
        return new Target("CSH", MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT);
      case "БИ":
      case "ПН":
      case "ВНБ":
      case "БПЛН":
        return new Target("BNK", MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT);
      case "ОП":
      case "ОПС":
        // Отчет за извършени продажби (Z-report).
        return new Target("FIS", MoveRepository.FUNCTIONAL_ORIGIN_SALE);
      case "ПЗДДС":
      case "ПРОТ":
      case "ПРОТ(КО)":
      case "ПРОТ(КОДК)":
      case "ПРОТ126Б":
        // ЗДДС protocol: no Axelor functional origin describes it. See the class comment.
        return new Target("VAT", null);
      case "МО":
        // Мемориален ордер: likewise none.
        return new Target("MOM", null);
      default:
        return null;
    }
  }

  /**
   * The mapping for a whole entry. An entry WITH a document type keeps {@link #map(String,
   * String)}; one without (362 historic entries) is routed deterministically, in this order, by the
   * SOURCE accounts it touches: 501 or 509/1 → cash, 503/2 or 504/1 → bank, else PURCHASE → PCH,
   * else SALE → SAL, else the memorandum journal. Purchase/sale routing carries no functional
   * origin: nothing says the entry is an invoice, and a sale/purchase origin would demand a tax
   * line on every VAT-account row.
   */
  public static Target map(MicroinvestAccountingSourceModels.Entry entry) {
    if (entry.documentType != null && !entry.documentType.trim().isEmpty()) {
      return map(entry.documentType, entry.direction);
    }
    boolean cash = false;
    boolean bank = false;
    for (MicroinvestAccountingSourceModels.Line line : entry.lines) {
      String account =
          line.sourceAccountNumber != null ? line.sourceAccountNumber : line.accountNumber;
      account = account == null ? "" : account.trim();
      cash |= "501".equals(account) || "509/1".equals(account);
      bank |= "503/2".equals(account) || "504/1".equals(account);
    }
    if (cash) {
      return new Target("CSH", MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT);
    }
    if (bank) {
      return new Target("BNK", MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT);
    }
    if (DIRECTION_PURCHASE.equalsIgnoreCase(entry.direction)) {
      return new Target("PCH", null);
    }
    if (DIRECTION_SALE.equalsIgnoreCase(entry.direction)) {
      return new Target("SAL", null);
    }
    return new Target("MOM", null);
  }

  private static String normalize(String value) {
    return value.toUpperCase(Locale.ROOT).replace(" ", "");
  }
}
