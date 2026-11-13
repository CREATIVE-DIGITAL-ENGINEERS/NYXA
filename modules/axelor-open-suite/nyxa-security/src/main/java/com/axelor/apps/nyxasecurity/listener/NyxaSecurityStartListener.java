package com.axelor.apps.nyxasecurity.listener;

import com.axelor.apps.nyxasecurity.mail.SmtpEmailAccountSync;
import com.axelor.apps.nyxasecurity.service.EncryptionKeyCheck;
import com.axelor.apps.nyxasecurity.service.RoleCatalogueImporter;
import com.axelor.event.Observes;
import com.axelor.events.StartupEvent;
import com.axelor.inject.Beans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NyxaSecurityStartListener {

  private static final Logger LOG = LoggerFactory.getLogger(NyxaSecurityStartListener.class);

  public void onStartup(@Observes StartupEvent event) {
    // First, and allowed to fail the boot: an empty key means encrypted fields are stored in
    // plain text from here on.
    EncryptionKeyCheck.checkOnStartup();
    // StartupEvent fires after every module's views and menus are loaded, so the catalogue's menu
    // grants always find their MetaMenu rows, whatever the module load order.
    Beans.get(RoleCatalogueImporter.class).importOnStartup();
    // Mail is not worth failing the boot over: without it, invitations still grant access and say
    // that no email was sent.
    try {
      Beans.get(SmtpEmailAccountSync.class).syncOnStartup();
    } catch (RuntimeException e) {
      LOG.error("Could not sync the email account from mail.smtp.*", e);
    }
  }
}
