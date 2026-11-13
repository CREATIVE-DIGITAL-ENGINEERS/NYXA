package com.axelor.apps.aiaccounting.service.invoice.spi;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects the extra invoice fields every installed {@link AiInvoiceFieldContributor} offers.
 *
 * <h2>A union, and why that is safe here</h2>
 *
 * Unlike the target and domain registries, this one asks everybody and keeps every answer: two
 * modules adding two different fields is the normal case, not a conflict. What would be a conflict
 * is two contributors claiming the SAME field name, because then which one applies it depends on
 * classpath scan order. That is checked once, when the field list is built, and the duplicate is
 * dropped with an error rather than silently winning — a field nobody can edit is a visible bug, a
 * field written by whichever class was scanned first is not.
 */
@Singleton
public class AiInvoiceFieldRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(AiInvoiceFieldRegistry.class);

  private volatile List<AiInvoiceFieldContributor> contributors;
  private volatile Map<String, AiInvoiceFieldSpec> headerSpecs;
  private volatile Map<String, AiInvoiceFieldSpec> lineSpecs;

  public List<AiInvoiceFieldContributor> all() {
    List<AiInvoiceFieldContributor> local = contributors;
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

  /** Every contributed header field, by name, with duplicates dropped. */
  public Map<String, AiInvoiceFieldSpec> headerFields() {
    Map<String, AiInvoiceFieldSpec> local = headerSpecs;
    if (local == null) {
      synchronized (this) {
        if (headerSpecs == null) {
          headerSpecs = collect(true);
        }
        local = headerSpecs;
      }
    }
    return local;
  }

  /** Every contributed line field, by name, with duplicates dropped. */
  public Map<String, AiInvoiceFieldSpec> lineFields() {
    Map<String, AiInvoiceFieldSpec> local = lineSpecs;
    if (local == null) {
      synchronized (this) {
        if (lineSpecs == null) {
          lineSpecs = collect(false);
        }
        local = lineSpecs;
      }
    }
    return local;
  }

  /** The contributed header field names that apply to this direction of invoice. */
  public Set<String> headerFieldNames(boolean supplier) {
    Set<String> names = new LinkedHashSet<>();
    for (Map.Entry<String, AiInvoiceFieldSpec> e : headerFields().entrySet()) {
      if (supplier ? e.getValue().appliesToSupplier() : e.getValue().appliesToCustomer()) {
        names.add(e.getKey());
      }
    }
    return names;
  }

  /**
   * Lets every contributor default its own blank header fields on a brand-new invoice, before any
   * caller-supplied header override is applied. A contributor's own failure is logged and skipped —
   * one country's defaulting rule must not stop the invoice from being created at all.
   */
  public void applyCreationDefaults(Invoice invoice) {
    if (invoice == null) {
      return;
    }
    for (AiInvoiceFieldContributor contributor : all()) {
      try {
        contributor.applyCreationDefaults(invoice);
      } catch (RuntimeException e) {
        LOG.warn(
            "AI Accountant: field contributor {} could not default creation fields: {}",
            contributor.getClass().getSimpleName(),
            e.getMessage());
      }
    }
  }

  /**
   * Applies a contributed header field.
   *
   * @return what changed, or null when no contributor owns this field
   * @throws IllegalArgumentException when a contributor refuses the value
   */
  public AiInvoiceFieldChange applyHeaderField(Invoice invoice, String field, JsonNode value) {
    for (AiInvoiceFieldContributor contributor : all()) {
      AiInvoiceFieldChange change = contributor.applyHeaderField(invoice, field, value);
      if (change != null) {
        return change;
      }
    }
    return null;
  }

  /**
   * Applies a contributed line field.
   *
   * @return what changed, or null when no contributor owns this field
   */
  public AiInvoiceFieldChange applyLineField(InvoiceLine line, String field, JsonNode value) {
    for (AiInvoiceFieldContributor contributor : all()) {
      AiInvoiceFieldChange change = contributor.applyLineField(line, field, value);
      if (change != null) {
        return change;
      }
    }
    return null;
  }

  /** Every contributor's current header-field values, merged, for a detail view. */
  public Map<String, Object> readHeaderFields(Invoice invoice) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (invoice == null) {
      return values;
    }
    for (AiInvoiceFieldContributor contributor : all()) {
      try {
        Map<String, Object> theirs = contributor.readHeaderFields(invoice);
        if (theirs != null) {
          values.putAll(theirs);
        }
      } catch (RuntimeException e) {
        LOG.debug(
            "AI Accountant: field contributor {} could not read invoice {}: {}",
            contributor.getClass().getSimpleName(),
            invoice.getId(),
            e.getMessage());
      }
    }
    return values;
  }

  /** Every contributor's row-field values, merged, for one row of a search result. */
  public Map<String, Object> readRowFields(Invoice invoice) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (invoice == null) {
      return values;
    }
    for (AiInvoiceFieldContributor contributor : all()) {
      try {
        Map<String, Object> theirs = contributor.readRowFields(invoice);
        if (theirs != null) {
          values.putAll(theirs);
        }
      } catch (RuntimeException e) {
        LOG.debug(
            "AI Accountant: field contributor {} could not read row fields for invoice {}: {}",
            contributor.getClass().getSimpleName(),
            invoice.getId(),
            e.getMessage());
      }
    }
    return values;
  }

  /** Every contributor's current line-field values, merged, for a detail view. */
  public Map<String, Object> readLineFields(InvoiceLine line) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (line == null) {
      return values;
    }
    for (AiInvoiceFieldContributor contributor : all()) {
      try {
        Map<String, Object> theirs = contributor.readLineFields(line);
        if (theirs != null) {
          values.putAll(theirs);
        }
      } catch (RuntimeException e) {
        LOG.debug(
            "AI Accountant: field contributor {} could not read line {}: {}",
            contributor.getClass().getSimpleName(),
            line.getId(),
            e.getMessage());
      }
    }
    return values;
  }

  private Map<String, AiInvoiceFieldSpec> collect(boolean header) {
    Map<String, AiInvoiceFieldSpec> specs = new LinkedHashMap<>();
    Map<String, String> owners = new LinkedHashMap<>();
    for (AiInvoiceFieldContributor contributor : all()) {
      List<AiInvoiceFieldSpec> offered;
      try {
        offered = header ? contributor.headerFields() : contributor.lineFields();
      } catch (RuntimeException e) {
        LOG.warn(
            "AI Accountant: invoice field contributor {} could not list its fields: {}",
            contributor.getClass().getSimpleName(),
            e.getMessage());
        continue;
      }
      if (offered == null) {
        continue;
      }
      for (AiInvoiceFieldSpec spec : offered) {
        if (spec == null || spec.getName() == null || spec.getName().trim().isEmpty()) {
          continue;
        }
        String owner = contributor.getClass().getSimpleName();
        String existing = owners.get(spec.getName());
        if (existing != null) {
          LOG.error(
              "AI Accountant: {} and {} both claim invoice field '{}'; dropping it rather than"
                  + " letting scan order decide which one writes it.",
              existing,
              owner,
              spec.getName());
          specs.remove(spec.getName());
          continue;
        }
        owners.put(spec.getName(), owner);
        specs.put(spec.getName(), spec);
      }
    }
    return specs;
  }

  protected List<AiInvoiceFieldContributor> discover() {
    List<AiInvoiceFieldContributor> found = new ArrayList<>();
    for (Class<? extends AiInvoiceFieldContributor> type :
        MetaScanner.findSubTypesOf(AiInvoiceFieldContributor.class).find()) {
      if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      found.add(Beans.get(type));
    }
    LOG.info("AI Accountant: registered {} invoice field contributor(s).", found.size());
    return found;
  }
}
