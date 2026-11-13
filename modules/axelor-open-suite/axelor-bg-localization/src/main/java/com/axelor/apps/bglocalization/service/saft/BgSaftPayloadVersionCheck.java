package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.napsubmissions.services.saft.SaftPayloadVersionCheck;
import java.time.LocalDate;

/** SAF-T BG за точката за разширение на nyxa-nap-submissions — {@link BgSaftSubmissionVersion}. */
public class BgSaftPayloadVersionCheck extends SaftPayloadVersionCheck {

  @Override
  public String refusal(byte[] payload, LocalDate today) {
    return BgSaftSubmissionVersion.refusal(payload, today);
  }
}
