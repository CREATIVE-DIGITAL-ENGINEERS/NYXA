package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.google.inject.Singleton;

/**
 * The only {@link NapSubmissionClient} implementation until the official НАП API contract is
 * supplied. It binds cleanly and NEVER fails at construction/injection — it reports "API not
 * configured" only when a method is actually called. This keeps the app booting and the manual
 * portal workflow fully usable while API submission is unavailable.
 *
 * <p>Do NOT turn this into a fake HTTP client or invent endpoints. Replace it with real,
 * service-specific clients once the official NAP API specification holds the official
 * specification.
 */
@Singleton
public class UnsupportedNapApiSubmissionClient implements NapSubmissionClient {

  private static final String MESSAGE =
      "NAP API submission is not configured. The official API contract has not been supplied yet"
          + ". Use the manual portal workflow.";

  @Override
  public NapSubmissionResult submit(NapSubmission submission) {
    return NapSubmissionResult.notConfigured(MESSAGE);
  }

  @Override
  public NapSubmissionResult getStatus(NapSubmission submission) {
    return NapSubmissionResult.notConfigured(MESSAGE);
  }
}
