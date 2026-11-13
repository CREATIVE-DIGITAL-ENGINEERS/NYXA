package com.axelor.apps.napsubmissions.services.declarations.common;

import java.util.Collections;
import java.util.Map;

/**
 * What {@link DeclarationsResultParser} could establish from one НАП result body. It is a reading
 * of the response, never a replacement for it: the untouched bytes are always stored as an
 * immutable artifact regardless of what this says.
 */
public class ParsedDeclarationsResult {

  /** One {@code nap.processing.derived.outcome.select} value per НАП file document type. */
  public final Map<FileDocumentType, String> fileOutcomes;

  /** The conservative overall outcome (same selection). */
  public final String overallOutcome;

  /** Sanitized, short human summary. Never markup. */
  public final String summary;

  /** True when nothing in the body could be recognised — a human must look at the raw result. */
  public final boolean needsReview;

  public ParsedDeclarationsResult(
      Map<FileDocumentType, String> fileOutcomes,
      String overallOutcome,
      String summary,
      boolean needsReview) {
    this.fileOutcomes = Collections.unmodifiableMap(fileOutcomes);
    this.overallOutcome = overallOutcome;
    this.summary = summary;
    this.needsReview = needsReview;
  }
}
