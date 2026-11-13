package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.db.NapSubmission;

/**
 * Stable abstraction for sending a payload to НАП and checking its status. This is the ONLY seam
 * the rest of the module depends on for API transport.
 *
 * <p>The contract is intentionally minimal: {@code submit} and {@code getStatus}. No {@code
 * cancel}, no polling format, no endpoint or error-code assumptions — those are added only once the
 * official НАП API specification is available. Real implementations must be service-specific (one
 * per НАП service), not one universal client.
 */
public interface NapSubmissionClient {

  /**
   * Send the submission's prepared payload. Must never assume an endpoint that is not configured.
   */
  NapSubmissionResult submit(NapSubmission submission);

  /** Query the external status of a previously sent submission. */
  NapSubmissionResult getStatus(NapSubmission submission);
}
