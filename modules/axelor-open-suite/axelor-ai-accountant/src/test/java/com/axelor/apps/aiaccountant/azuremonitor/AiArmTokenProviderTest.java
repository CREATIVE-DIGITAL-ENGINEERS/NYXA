package com.axelor.apps.aiaccountant.azuremonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.client.AiAccountantException;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import org.junit.jupiter.api.Test;

/**
 * The ARM token path asks for the ARM audience, and is not the chat's token path.
 *
 * <p>This matters because a token is minted for one audience: if reading metrics ever borrowed the
 * chat's provider, the change would land in the code path every chat turn depends on, and getting
 * it wrong would take the assistant down to fix a dashboard.
 */
class AiArmTokenProviderTest {

  private static AiAccountantConfig credentials(String authMode) {
    return new AiAccountantConfig() {
      @Override
      protected String authMode() {
        return authMode;
      }

      @Override
      protected String tenantId() {
        return "tenant-1";
      }

      @Override
      protected String clientId() {
        return "client-1";
      }

      @Override
      protected String clientSecret() {
        return "secret-1";
      }
    };
  }

  /** Captures the form body that would have gone to Entra. */
  private static class CapturingProvider extends AiArmTokenProvider {
    String form;
    String url;

    CapturingProvider(AiAccountantConfig config) {
      super(config);
    }

    @Override
    protected TokenResponse httpPost(String url, String form) {
      this.url = url;
      this.form = form;
      return new TokenResponse(200, "{\"access_token\":\"arm-token\",\"expires_in\":3600}");
    }
  }

  @Test
  void requestsTheResourceManagerAudienceAndNotTheFoundryOne() {
    CapturingProvider provider = new CapturingProvider(credentials(""));

    assertEquals("arm-token", provider.bearerToken());
    assertTrue(provider.url.contains("tenant-1"));
    // URL-encoded https://management.azure.com/.default
    assertTrue(
        provider.form.contains("management.azure.com%2F.default"),
        "the ARM audience must be requested: " + provider.form);
    assertFalse(
        provider.form.contains("ai.azure.com"),
        "the Foundry data-plane audience must not appear on the ARM request");
    assertTrue(provider.form.contains("grant_type=client_credentials"));
  }

  @Test
  void theTwoAudiencesAreDifferentConstants() {
    // The chat's scope is untouched by this feature; that they differ is the whole reason the ARM
    // path exists as its own class.
    assertNotEquals(AiAccountantConfig.SCOPE, AiAccountantConfig.ARM_SCOPE);
    assertEquals("https://ai.azure.com/.default", AiAccountantConfig.SCOPE);
    assertEquals("https://management.azure.com/.default", AiAccountantConfig.ARM_SCOPE);
  }

  @Test
  void cachesTheTokenRatherThanReAcquiringItPerRequest() {
    CapturingProvider provider =
        new CapturingProvider(credentials("")) {
          int calls;

          @Override
          protected TokenResponse httpPost(String url, String form) {
            calls++;
            return new TokenResponse(
                200, "{\"access_token\":\"arm-token-" + calls + "\",\"expires_in\":3600}");
          }
        };

    assertEquals("arm-token-1", provider.bearerToken());
    assertEquals("arm-token-1", provider.bearerToken(), "the second call must reuse the token");
  }

  @Test
  void missingCredentialsAreRefusedWithAUsefulMessage() {
    AiArmTokenProvider provider =
        new AiArmTokenProvider(
            new AiAccountantConfig() {
              @Override
              protected String tenantId() {
                return "";
              }

              @Override
              protected String clientId() {
                return "";
              }

              @Override
              protected String clientSecret() {
                return "";
              }

              @Override
              protected String authMode() {
                return "";
              }
            });

    AiAccountantException e = assertThrows(AiAccountantException.class, provider::bearerToken);
    assertTrue(e.getMessage().contains("client credentials"));
  }

  @Test
  void anEntraRejectionReportsOnlyTheErrorCode() {
    AiArmTokenProvider provider =
        new AiArmTokenProvider(credentials("")) {
          @Override
          protected TokenResponse httpPost(String url, String form) {
            return new TokenResponse(
                401, "{\"error\":\"invalid_client\",\"error_description\":\"secret ...\"}");
          }
        };

    AiAccountantException e = assertThrows(AiAccountantException.class, provider::bearerToken);
    assertTrue(e.getMessage().contains("invalid_client"));
    // The description can echo configuration back; only the code is surfaced.
    assertFalse(e.getMessage().contains("secret ..."));
  }
}
