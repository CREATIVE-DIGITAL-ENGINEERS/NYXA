package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLink;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Which record a supplier-invoice tool call is about, and therefore which path it takes.
 *
 * <h2>Two references, one operation</h2>
 *
 * These tools were built around {@code uploadId}, because every supplier invoice the assistant
 * could reach came from the OCR intake queue. That left every hand-entered supplier invoice
 * unreachable — not refused, simply invisible, with no argument that could name it. So they now
 * also accept an {@code invoiceId}.
 *
 * <p>The two are not two behaviours. An {@code invoiceId} that names an OCR-created invoice is
 * translated back to its upload and takes the OCR path, unchanged: that path reconciles the amounts
 * against the scanned document, keeps the review status in step and writes the audit note, and none
 * of that should depend on which of the two names the caller happened to use. Only an invoice with
 * no upload behind it takes the generic path.
 *
 * <p>Existing callers are unaffected: {@code uploadId} behaves exactly as it always did, and the
 * schema addition is optional.
 */
final class SupplierInvoiceTarget {

  private final Long uploadId;
  private final Long invoiceId;

  /**
   * Package-private rather than private: {@link #of} needs an injector to translate an invoice id,
   * so a test exercising a tool's no-document path has no other way to state the target it is
   * handing in. Still unreachable outside this package, which is where every caller lives.
   */
  SupplierInvoiceTarget(Long uploadId, Long invoiceId) {
    this.uploadId = uploadId;
    this.invoiceId = invoiceId;
  }

  /** True when this call goes down the OCR path, addressed by upload. */
  boolean isOcr() {
    return uploadId != null;
  }

  /** The upload id — only meaningful when {@link #isOcr()}. */
  long uploadId() {
    return uploadId;
  }

  /** The invoice id — only meaningful when this is not an OCR call. */
  long invoiceId() {
    return invoiceId;
  }

  /** {@code Upload#946} or {@code Invoice#606}, for the repeat guard and the workflow. */
  String targetKey() {
    return uploadId != null ? "Upload#" + uploadId : "Invoice#" + invoiceId;
  }

  /**
   * Reads the pair of arguments and decides the path.
   *
   * @throws AiToolException when neither or both were given
   */
  static SupplierInvoiceTarget of(JsonNode args) {
    Long uploadId = AiToolArgs.optId(args, "uploadId");
    Long invoiceId = AiToolArgs.optId(args, "invoiceId");
    if (uploadId != null && invoiceId != null) {
      throw new AiToolException(
          "Give either uploadId or invoiceId, not both. They name the same document from two"
              + " sides, and being given both is a sign the two do not agree.");
    }
    if (uploadId == null && invoiceId == null) {
      throw new AiToolException(
          "Give either uploadId (an OCR intake record) or invoiceId (any supplier invoice of this"
              + " company).");
    }
    if (uploadId != null) {
      return new SupplierInvoiceTarget(uploadId, null);
    }
    // An OCR-created invoice named by its own id still takes the OCR path — see the class comment.
    Long mapped = Beans.get(OcrInvoiceLink.class).uploadIdForInvoice(invoiceId);
    return mapped != null
        ? new SupplierInvoiceTarget(mapped, null)
        : new SupplierInvoiceTarget(null, invoiceId);
  }

  /**
   * As {@link #of}, but null instead of a refusal.
   *
   * <p>For {@code targetKey} and {@code beforeExecute}, which are called outside the try/catch that
   * turns a bad argument into an answer, and where a throw would be a failure rather than a
   * refusal.
   */
  static SupplierInvoiceTarget ofOrNull(JsonNode args) {
    try {
      return of(args);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Declares in the schema that exactly one of the two id arguments must be given.
   *
   * <p>{@code oneOf} of two single-{@code required} branches is exactly the rule {@link #of}
   * enforces: with both present both branches match and {@code oneOf} fails; with neither, neither
   * matches. Said in the schema so the model is told the rule rather than discovering it from a
   * refusal, and it sits beside {@code additionalProperties: false} without weakening it.
   *
   * @param schema the tool's root schema object, after its properties are in place
   */
  static void declareExactlyOneId(ObjectNode schema) {
    ArrayNode oneOf = schema.putArray("oneOf");
    oneOf.addObject().putArray("required").add("uploadId");
    oneOf.addObject().putArray("required").add("invoiceId");
  }

  /** Adds the two id arguments to a tool's schema, worded the same way in each of them. */
  static void describeIds(ObjectNode props, String what) {
    props
        .putObject("uploadId")
        .put("type", "integer")
        .put("description", "The OCR upload id whose supplier invoice is " + what + ".");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put(
            "description",
            "Any supplier invoice of this company, by its own id — including one entered by hand"
                + " rather than scanned. Give this OR uploadId, not both. An OCR invoice named this"
                + " way behaves exactly as if its uploadId had been given.");
  }
}
