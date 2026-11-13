package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.InvoiceLineUnitDefaultProvider;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE place a newly created invoice line gets its unit of measure settled.
 *
 * <h2>Why it exists</h2>
 *
 * Without it, an ordinary OCR import persists every line with {@code unit = null}. Not an edge case
 * — the ordinary path: {@code BgOcrIntakeService} resolves each line to the per-company generic
 * purchase product ({@code OCR-GEN}), that product carries no unit of its own, and an assignment
 * guarded by {@code if (product.getUnit() != null)} does nothing. No product unit, no assignment,
 * no complaint. A rounding-correction line has no product at all and goes the same way.
 *
 * <p>A UI default (an {@code onNew} action on the invoice line form) is irrelevant here: OCR intake
 * runs server-side in a worker thread, where no view action executes. A UI default is a convenience
 * for the person typing; it is not, and cannot be, the production guarantee.
 *
 * <h2>Precedence</h2>
 *
 * <ol>
 *   <li>a unit already on the line wins — whether a caller stated it explicitly or product
 *       resolution put it there. It is never replaced by the default;
 *   <li>otherwise the selected product's own unit;
 *   <li>otherwise the localization default from {@link InvoiceLineUnitDefaultProvider}.
 * </ol>
 *
 * <p>Step 2 is deliberately repeated here even though every caller already fills from the product:
 * it makes the precedence readable in one place, and it costs nothing when the caller did its job.
 *
 * <h2>Scope</h2>
 *
 * Normal lines only ({@link InvoiceLineRepository#TYPE_NORMAL}, which is also what a null {@code
 * typeSelect} reads as through the generated getter). Title lines and the two pack markers carry no
 * quantity anybody measures, and Axelor legitimately leaves them without a unit — forcing one there
 * would put a measurement on a heading.
 *
 * <h2>Missing configuration is loud</h2>
 *
 * A provider installed but unconfigured raises a configuration error, so the caller fails with one
 * clear setup problem instead of producing a draft full of invalid lines. The alternative — carry
 * on and leave the unit empty — is exactly the failure this class was written for.
 *
 * <p>NO provider installed at all is a different case and stays silent: the rule belongs to a
 * localization, and a deployment without one has nothing to state a default. That is the same
 * boundary {@code OcrRoundingLineService} draws for the VAT-credit backend.
 */
@Singleton
public class InvoiceLineUnitDefaultService {

  private volatile List<InvoiceLineUnitDefaultProvider> providers;

  /**
   * Settles the unit of one line, in place. Does nothing to a line that is not a normal line, and
   * nothing to a line that already has a unit.
   *
   * @throws AxelorException when the line would be left without a unit although a localization is
   *     installed to supply one
   */
  public void applyTo(InvoiceLine line) throws AxelorException {
    if (line == null || !isApplicable(line) || line.getUnit() != null) {
      return;
    }

    Product product = line.getProduct();
    if (product != null && product.getUnit() != null) {
      line.setUnit(product.getUnit());
      return;
    }

    List<InvoiceLineUnitDefaultProvider> installed = providers();
    if (installed.isEmpty()) {
      return; // no localization installed: nothing to state a default, nothing to enforce
    }
    if (installed.size() > 1) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          "More than one localization supplies a default invoice line unit (%s). Exactly one may be"
              + " installed — refusing to pick between them.",
          names(installed));
    }

    InvoiceLineUnitDefaultProvider provider = installed.get(0);
    Unit unit = provider.defaultInvoiceLineUnit();
    if (unit == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, "%s", provider.notConfiguredMessage());
    }
    line.setUnit(unit);
  }

  /**
   * Settles the unit of every line of an invoice. Used where lines are built in bulk (OCR intake),
   * so one unconfigured default reports once, before anything is persisted, rather than per line.
   */
  public void applyToAll(Invoice invoice) throws AxelorException {
    if (invoice == null || invoice.getInvoiceLineList() == null) {
      return;
    }
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      applyTo(line);
    }
  }

  /**
   * Whether the rule applies to this line at all. A null {@code typeSelect} counts as normal: the
   * generated getter already reads it as {@link InvoiceLineRepository#TYPE_NORMAL}, and a line
   * built in Java without setting it is an ordinary purchased line.
   */
  public boolean isApplicable(InvoiceLine line) {
    return line != null && line.getTypeSelect() == InvoiceLineRepository.TYPE_NORMAL;
  }

  // ---------------------------------------------------------------- discovery

  /**
   * Same classpath discovery {@code AiInvoiceFieldRegistry} uses, for the same reason: the
   * implementation lives in a module this one must not name, so it cannot be a Guice binding
   * declared here.
   */
  protected List<InvoiceLineUnitDefaultProvider> providers() {
    List<InvoiceLineUnitDefaultProvider> local = providers;
    if (local == null) {
      synchronized (this) {
        if (providers == null) {
          providers = discover();
        }
        local = providers;
      }
    }
    return local;
  }

  protected List<InvoiceLineUnitDefaultProvider> discover() {
    List<InvoiceLineUnitDefaultProvider> found = new ArrayList<>();
    for (Class<? extends InvoiceLineUnitDefaultProvider> type :
        MetaScanner.findSubTypesOf(InvoiceLineUnitDefaultProvider.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    return found;
  }

  private static String names(List<InvoiceLineUnitDefaultProvider> installed) {
    StringBuilder sb = new StringBuilder();
    for (InvoiceLineUnitDefaultProvider provider : installed) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(provider.getClass().getName());
    }
    return sb.toString();
  }
}
