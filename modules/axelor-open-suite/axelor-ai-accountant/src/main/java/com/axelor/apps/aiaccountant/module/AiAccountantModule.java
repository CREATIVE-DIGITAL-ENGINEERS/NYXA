package com.axelor.apps.aiaccountant.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.aiaccountant.client.AiTokenProvider;
import com.axelor.apps.aiaccountant.client.AzureCliTokenProvider;
import com.axelor.apps.aiaccountant.client.ClientSecretTokenProvider;
import com.axelor.apps.aiaccountant.client.FoundryResponsesClient;
import com.axelor.apps.aiaccountant.client.FoundryResponsesRestClient;
import com.axelor.apps.aiaccountant.client.MockFoundryResponsesClient;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.listener.AiAccountantServerStartListener;
import com.axelor.apps.aiaccountant.router.AiRouterClient;
import com.axelor.apps.aiaccountant.router.FoundryRouterRestClient;
import com.axelor.apps.aiaccountant.service.AiAccountantService;
import com.axelor.apps.aiaccountant.service.AiAccountantServiceImpl;

/**
 * Guice bindings for AI Accountant. Auto-discovered at boot by {@code AppModule} (MetaScanner finds
 * {@link AxelorModule} subtypes per module).
 *
 * <p>Tool implementations are intentionally NOT bound here — {@code AiToolRegistry} discovers every
 * {@code AiTool} on the classpath, so feature modules contribute tools without a binding and
 * without this module depending on them.
 */
public class AiAccountantModule extends AxelorModule {

  @Override
  protected void configure() {
    // Explicit binding required for Axelor to fire StartupEvent on the observer.
    bind(AiAccountantServerStartListener.class);

    bind(AiAccountantService.class).to(AiAccountantServiceImpl.class);

    AiAccountantConfig config = new AiAccountantConfig();

    // Offline stand-in for development/CI, mirroring the aiocr Mock/Azure extractor split.
    if (config.isMockMode()) {
      bind(FoundryResponsesClient.class).to(MockFoundryResponsesClient.class);
    } else {
      bind(FoundryResponsesClient.class).to(FoundryResponsesRestClient.class);
    }

    // The Router Agent is a second, tool-free agent on its own endpoint. Bound unconditionally: it
    // reads its own configuration and reports itself unconfigured, and AiRouterService refuses to
    // call it unless aiaccountant.router.enabled says so. Mock mode leaves it unconfigured, which
    // is the correct offline behaviour — an offline router would be measuring itself.
    bind(AiRouterClient.class).to(FoundryRouterRestClient.class);

    // Production credentials are still TBD (the deployed app will be granted Azure AI permissions,
    // mechanism unknown). AiTokenProvider is the seam: adding that mechanism later is a new class
    // plus one line here, with no change to the client, orchestrator, tools or frontend.
    if (AiAccountantConfig.AUTH_AZURE_CLI.equalsIgnoreCase(config.getAuthMode())) {
      // Development only: borrows the developer's `az login` session.
      bind(AiTokenProvider.class).to(AzureCliTokenProvider.class);
    } else {
      bind(AiTokenProvider.class).to(ClientSecretTokenProvider.class);
    }
  }
}
