package com.axelor.apps.nyxasecurity.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.base.quickmenu.ActiveCompanyUpdateQuickMenuCreator;
import com.axelor.apps.nyxasecurity.directory.DirectorySettings;
import com.axelor.apps.nyxasecurity.directory.KeycloakTenantUserDirectory;
import com.axelor.apps.nyxasecurity.directory.MockTenantUserDirectory;
import com.axelor.apps.nyxasecurity.directory.TenantUserDirectory;
import com.axelor.apps.nyxasecurity.listener.NyxaSecurityStartListener;
import com.axelor.apps.nyxasecurity.mail.NxEmailAddressRepository;
import com.axelor.apps.nyxasecurity.user.ActiveCompanyOnLogin;
import com.axelor.apps.nyxasecurity.user.ActiveCompanyQuickMenuCreator;
import com.axelor.apps.nyxasecurity.web.NxAppController;
import com.axelor.message.db.repo.EmailAddressRepo;
import com.axelor.studio.app.web.AppController;

/**
 * Binds the startup listener that applies the role catalogue. Axelor fires {@code StartupEvent}
 * observers only for explicitly bound listeners (cf. {@code AiOcrModule}).
 */
public class NyxaSecurityModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(NyxaSecurityStartListener.class);
    bind(ActiveCompanyOnLogin.class);
    // Base registers its active company quick menu by class; this swaps in our subclass.
    bind(ActiveCompanyUpdateQuickMenuCreator.class).to(ActiveCompanyQuickMenuCreator.class);
    // The message add-on's address check refuses valid addresses such as first.last+tag@gmail.com.
    // Chained: MessageModule binds EmailAddressRepository to EmailAddressRepo.
    bind(EmailAddressRepo.class).to(NxEmailAddressRepository.class);
    // Apps management without importing demo data or roles (views/App.xml).
    bind(AppController.class).to(NxAppController.class);
    // Tenant users are managed through Keycloak's client-users endpoint. The mock is for
    // development and tests only (nyxa.users.directory = mock).
    if (DirectorySettings.fromAppSettings().isMock()) {
      bind(TenantUserDirectory.class).to(MockTenantUserDirectory.class);
    } else {
      bind(TenantUserDirectory.class).to(KeycloakTenantUserDirectory.class);
    }
  }
}
