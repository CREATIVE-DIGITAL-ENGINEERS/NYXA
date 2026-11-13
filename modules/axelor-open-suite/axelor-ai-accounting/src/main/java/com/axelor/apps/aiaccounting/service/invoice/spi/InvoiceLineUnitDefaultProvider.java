package com.axelor.apps.aiaccounting.service.invoice.spi;

import com.axelor.apps.base.db.Unit;

/**
 * Supplies the localization's default unit of measure for a new <b>normal</b> invoice line.
 *
 * <h2>Why this is an SPI and not a lookup</h2>
 *
 * The rule "a purchased line always states a unit" is a LOCALIZATION rule, and the record that
 * answers it lives in {@code axelor-bg-localization} ({@code
 * AppBgLocalization.bgDefaultInvoiceLineUnit}). The services that CREATE invoice lines — OCR
 * intake, the AI invoice tools, the AI OCR line editor — live in this module and in {@code
 * axelor-ai-ocr}, both of which are UPSTREAM of the localization. They must not know it exists, and
 * a dependency the other way would be a cycle. Same arrangement as {@link
 * AiInvoiceReadinessContributor} and {@code OcrRoundingLineCreditType}.
 *
 * <h2>What an implementation must and must not do</h2>
 *
 * <ul>
 *   <li>Return the CONFIGURED unit, or {@code null} when the administrator has not configured one.
 *       {@code null} means "installed but unconfigured" and is reported to the user as a setup
 *       problem — it is never silently accepted.
 *   <li>Never guess. No lookup by (translatable) name, no hardcoded id, no "first record", no
 *       ordering, no fallback to a similar unit. A wrong unit on a purchase line is worse than a
 *       loud refusal, because nothing downstream would ever flag it.
 * </ul>
 *
 * <p>At most ONE implementation may be installed. Two localizations disagreeing about the default
 * is a configuration error, not something to resolve by scan order — {@link
 * com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService} refuses instead of
 * picking.
 */
public interface InvoiceLineUnitDefaultProvider {

  /**
   * The configured default unit for a new normal invoice line, or {@code null} when the
   * localization is installed but nobody has configured one yet.
   */
  Unit defaultInvoiceLineUnit();

  /**
   * What the user has to go and do, named precisely enough to act on without asking. Shown verbatim
   * when {@link #defaultInvoiceLineUnit()} returns {@code null}, so it must name the screen and the
   * setting, not merely state that something is missing.
   */
  String notConfiguredMessage();
}
