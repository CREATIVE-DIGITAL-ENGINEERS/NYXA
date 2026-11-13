package com.axelor.apps.aiaccountant.client;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.google.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only token provider that borrows the developer's {@code az login} session.
 *
 * <p>Exists because the Foundry agent endpoint is Entra-protected and a service principal may not
 * be available on a workstation. This is exactly how the A0 spike authenticated.
 *
 * <p><b>Never use this in production</b> — it shells out to the Azure CLI and depends on an
 * interactive user session. It is selected only when {@code aiaccountant.azure.auth=azure-cli}.
 */
@Singleton
public class AzureCliTokenProvider implements AiTokenProvider {

  private static final Logger LOG = LoggerFactory.getLogger(AzureCliTokenProvider.class);

  private static final long REFRESH_SKEW_MS = 300_000L;
  private static final long CLI_TIMEOUT_SECONDS = 60L;

  /** az returns tokens valid ~60-75 min; re-shell every 30 min rather than parsing expiry. */
  private static final long ASSUMED_LIFETIME_MS = 1_800_000L;

  private final AiAccountantConfig config;

  private String cachedToken;
  private long expiresAtMs;

  @Inject
  public AzureCliTokenProvider(AiAccountantConfig config) {
    this.config = config;
  }

  @Override
  public synchronized String bearerToken() {
    if (cachedToken != null && monotonicMillis() < expiresAtMs - REFRESH_SKEW_MS) {
      return cachedToken;
    }
    String token = runCli();
    if (token == null || token.isEmpty()) {
      throw new AiAccountantException(
          "Azure CLI returned no token. Run 'az login', or configure client credentials instead.");
    }
    cachedToken = token;
    expiresAtMs = monotonicMillis() + ASSUMED_LIFETIME_MS;
    LOG.info("AI Accountant: acquired Entra token via Azure CLI (development mode).");
    return cachedToken;
  }

  protected String runCli() {
    List<String> cmd = new ArrayList<>();
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    if (windows) {
      cmd.add("cmd.exe");
      cmd.add("/c");
    }
    cmd.add("az");
    cmd.add("account");
    cmd.add("get-access-token");
    cmd.add("--resource");
    cmd.add("https://ai.azure.com/");
    cmd.add("--query");
    cmd.add("accessToken");
    cmd.add("-o");
    cmd.add("tsv");

    Process p = null;
    try {
      p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
      String out;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        out = r.readLine();
      }
      if (!p.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new AiAccountantException("Azure CLI timed out while acquiring a token.");
      }
      if (p.exitValue() != 0) {
        throw new AiAccountantException(
            "Azure CLI failed (exit " + p.exitValue() + "). Is 'az login' current?");
      }
      return out == null ? null : out.trim();
    } catch (IOException e) {
      throw new AiAccountantException(
          "Could not run the Azure CLI ('az' not on PATH?): " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAccountantException("Interrupted while running the Azure CLI.", e);
    } finally {
      if (p != null && p.isAlive()) {
        p.destroyForcibly();
      }
    }
  }

  protected long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }
}
