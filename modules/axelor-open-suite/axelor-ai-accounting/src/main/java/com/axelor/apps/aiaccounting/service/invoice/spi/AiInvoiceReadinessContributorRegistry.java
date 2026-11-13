package com.axelor.apps.aiaccounting.service.invoice.spi;

import com.axelor.apps.account.db.Invoice;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks every {@link AiInvoiceReadinessContributor} what it has to add, and keeps all of it.
 *
 * <h2>A union, deliberately</h2>
 *
 * Blockers are not competing opinions about one fact; they are separate facts, and the caller needs
 * every one of them. First-answer-wins here would hide a blocker behind another blocker and send
 * somebody to fix half a problem. So the lists are concatenated, in a stable order, and a
 * contributor that throws is logged and skipped rather than allowed to cost the whole answer.
 *
 * <p>The fingerprint is the one value that is combined rather than listed, because a fingerprint is
 * compared for equality. Contributions are appended in discovery order, which would make the result
 * scan-order-dependent if more than one module ever contributed — so they are sorted before
 * joining, and the result is stable whatever order the scan returns them in.
 */
@Singleton
public class AiInvoiceReadinessContributorRegistry {

  private static final Logger LOG =
      LoggerFactory.getLogger(AiInvoiceReadinessContributorRegistry.class);

  private volatile List<AiInvoiceReadinessContributor> contributors;

  public List<AiInvoiceReadinessContributor> all() {
    List<AiInvoiceReadinessContributor> local = contributors;
    if (local == null) {
      synchronized (this) {
        if (contributors == null) {
          contributors = discover();
        }
        local = contributors;
      }
    }
    return local;
  }

  /** Every extra blocker anybody has for this invoice. Never null, never throws. */
  public List<String> extraBlockers(Invoice invoice, String task) {
    List<String> blockers = new ArrayList<>();
    if (invoice == null) {
      return blockers;
    }
    for (AiInvoiceReadinessContributor contributor : all()) {
      try {
        List<String> theirs = contributor.extraBlockers(invoice, task);
        if (theirs != null) {
          for (String blocker : theirs) {
            if (blocker != null && !blocker.trim().isEmpty()) {
              blockers.add(blocker.trim());
            }
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "AI Accountant: readiness contributor {} failed on invoice {}: {}",
            contributor.getClass().getSimpleName(),
            invoice.getId(),
            e.getMessage());
      }
    }
    return blockers;
  }

  /**
   * Everybody's extra fingerprint state, combined into one stable string, or null when nobody has
   * any.
   */
  public String extraFingerprint(Invoice invoice) {
    if (invoice == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (AiInvoiceReadinessContributor contributor : all()) {
      try {
        String part = contributor.extraFingerprint(invoice);
        if (part != null && !part.isEmpty()) {
          parts.add(part);
        }
      } catch (Exception e) {
        LOG.debug(
            "AI Accountant: readiness contributor {} could not fingerprint invoice {}: {}",
            contributor.getClass().getSimpleName(),
            invoice.getId(),
            e.getMessage());
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    // Sorted, not in discovery order: two runs of the same build must produce the same fingerprint
    // for the same state, and MetaScanner does not promise an order.
    java.util.Collections.sort(parts);
    return String.join("|", parts);
  }

  protected List<AiInvoiceReadinessContributor> discover() {
    List<AiInvoiceReadinessContributor> found = new ArrayList<>();
    for (Class<? extends AiInvoiceReadinessContributor> type :
        MetaScanner.findSubTypesOf(AiInvoiceReadinessContributor.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} invoice readiness contributor(s).", found.size());
    return found;
  }
}
