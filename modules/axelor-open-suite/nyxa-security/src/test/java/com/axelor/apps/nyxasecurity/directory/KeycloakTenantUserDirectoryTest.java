package com.axelor.apps.nyxasecurity.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeycloakTenantUserDirectoryTest {

  private static final String ISSUER = "https://login.example/realms/wa";
  private static final String SECRET = "s3cr3t-value";

  private static final String USER =
      "{\"id\":\"0b6c1f2e-1111-2222-3333-444455556666\",\"email\":\"ana@example.com\","
          + "\"enabled\":true,\"hasLoggedIn\":false,\"roles\":[\"all.read\"],"
          + "\"managedByNyxa\":false}";

  /** Records calls and answers from a script, instead of talking HTTP. */
  private static final class Stub extends KeycloakTenantUserDirectory {
    final Deque<Response> answers = new ArrayDeque<>();
    final List<String> calls = new ArrayList<>();
    final List<String> tokensUsed = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    int tokenRequests;
    long now;

    Stub(DirectorySettings settings) {
      super(settings);
    }

    @Override
    protected Response send(String method, String url, String token, String json) {
      calls.add(method + " " + url);
      tokensUsed.add(token);
      bodies.add(json);
      return answers.removeFirst();
    }

    @Override
    protected Response sendForm(String url, String form) {
      tokenRequests++;
      assertEquals(ISSUER + "/protocol/openid-connect/token", url);
      assertTrue(form.contains("grant_type=client_credentials"));
      return new Response(
          200, "{\"access_token\":\"token-" + tokenRequests + "\",\"expires_in\":300}");
    }

    @Override
    protected long monotonicMillis() {
      return now;
    }
  }

  private static Stub stub() {
    return new Stub(new DirectorySettings("keycloak", ISSUER, "acme", SECRET, 5));
  }

  @Test
  void tokenIsCachedUntilShortlyBeforeExpiry() {
    Stub d = stub();
    d.answers.add(new KeycloakTenantUserDirectory.Response(200, USER));
    d.answers.add(new KeycloakTenantUserDirectory.Response(200, USER));
    d.answers.add(new KeycloakTenantUserDirectory.Response(200, USER));
    String id = "0b6c1f2e-1111-2222-3333-444455556666";

    d.get(id);
    d.now = 200_000L;
    d.get(id);
    assertEquals(1, d.tokenRequests);

    d.now = 280_000L; // inside the refresh margin of a 300 s token
    d.get(id);
    assertEquals(2, d.tokenRequests);
    assertEquals("token-2", d.tokensUsed.get(2));
  }

  @Test
  void a401RefreshesTheTokenAndRetriesOnce() {
    Stub d = stub();
    d.answers.add(new KeycloakTenantUserDirectory.Response(401, ""));
    d.answers.add(new KeycloakTenantUserDirectory.Response(200, USER));

    DirectoryUser user = d.get("0b6c1f2e-1111-2222-3333-444455556666");

    assertEquals("ana@example.com", user.getEmail());
    assertEquals(List.of("token-1", "token-2"), d.tokensUsed);
  }

  @Test
  void refusalsCarryTheDirectoryMessageAndCode() {
    Stub d = stub();
    d.answers.add(
        new KeycloakTenantUserDirectory.Response(
            409, "{\"error\":\"last_admin\",\"message\":\"At least one admin must remain.\"}"));

    DirectoryException e =
        assertThrows(
            DirectoryException.class, () -> d.revoke("0b6c1f2e-1111-2222-3333-444455556666"));

    assertEquals(409, e.getStatus());
    assertEquals("last_admin", e.getCode());
    assertEquals("At least one admin must remain.", e.getMessage());
    assertTrue(e.isRefusal());
    assertEquals(
        "DELETE " + ISSUER + "/client-users/users/0b6c1f2e-1111-2222-3333-444455556666",
        d.calls.get(0));
  }

  @Test
  void serverErrorsAreReportedAsOutagesWithoutTheirBody() {
    Stub d = stub();
    d.answers.add(new KeycloakTenantUserDirectory.Response(500, "{\"message\":\"stack trace\"}"));

    DirectoryException e = assertThrows(DirectoryException.class, d::list);

    assertFalse(e.isRefusal());
    assertFalse(e.getMessage().contains("stack trace"));
  }

  @Test
  void listFollowsPagesUntilTheTotal() {
    Stub d = stub();
    d.answers.add(
        new KeycloakTenantUserDirectory.Response(200, "{\"users\":[" + USER + "],\"total\":2}"));
    d.answers.add(
        new KeycloakTenantUserDirectory.Response(200, "{\"users\":[" + USER + "],\"total\":2}"));

    assertEquals(2, d.list().size());
    assertTrue(d.calls.get(0).endsWith("/users?first=0&max=500"));
    assertTrue(d.calls.get(1).endsWith("/users?first=1&max=500"));
  }

  @Test
  void inviteSendsEmailAndRolesAndReadsTheResult() {
    Stub d = stub();
    d.answers.add(
        new KeycloakTenantUserDirectory.Response(
            201, "{\"user\":" + USER + ",\"created\":true,\"rolesAdded\":[\"all.read\"]}"));

    InviteResult result = d.invite("ana@example.com", Set.of("all.read"));

    assertTrue(result.isCreated());
    assertEquals(Set.of("all.read"), result.getRolesAdded());
    assertEquals("POST " + ISSUER + "/client-users/users", d.calls.get(0));
    assertEquals("{\"email\":\"ana@example.com\",\"roles\":[\"all.read\"]}", d.bodies.get(0));
  }

  @Test
  void anIdThatIsNotAUuidNeverReachesAUrl() {
    Stub d = stub();
    assertThrows(DirectoryException.class, () -> d.get("../../admin"));
    assertTrue(d.calls.isEmpty());
  }

  @Test
  void missingConfigurationIsNamedAndTheSecretNeverAppears() {
    Stub d = new Stub(new DirectorySettings("keycloak", ISSUER, "acme", "", 5));
    DirectoryException e = assertThrows(DirectoryException.class, d::list);
    assertTrue(e.getMessage().contains(DirectorySettings.CLIENT_SECRET));
    assertTrue(d.calls.isEmpty());
  }

  /** Simulates the network below the client: each connection attempt fails or answers in turn. */
  private static final class Flaky extends KeycloakTenantUserDirectory {
    final Deque<Object> attempts = new ArrayDeque<>();
    int calls;

    Flaky() {
      super(new DirectorySettings("keycloak", ISSUER, "acme", SECRET, 5));
    }

    @Override
    protected Response exchange(HttpRequest request) throws IOException {
      calls++;
      if (request.uri().getPath().endsWith("/token")) {
        return new Response(200, "{\"access_token\":\"t\",\"expires_in\":300}");
      }
      Object next = attempts.removeFirst();
      if (next instanceof IOException) {
        throw (IOException) next;
      }
      return (Response) next;
    }
  }

  @Test
  void aLostConnectionIsTriedOnceMore() {
    Flaky d = new Flaky();
    d.attempts.add(new HttpConnectTimeoutException("HTTP connect timed out"));
    d.attempts.add(new KeycloakTenantUserDirectory.Response(204, ""));

    d.revoke("0b6c1f2e-1111-2222-3333-444455556666");

    assertTrue(d.attempts.isEmpty());
  }

  @Test
  void twoLostConnectionsGiveAPlainMessage() {
    Flaky d = new Flaky();
    d.attempts.add(new HttpConnectTimeoutException("HTTP connect timed out"));
    d.attempts.add(new HttpConnectTimeoutException("HTTP connect timed out"));

    DirectoryException e =
        assertThrows(
            DirectoryException.class, () -> d.revoke("0b6c1f2e-1111-2222-3333-444455556666"));

    assertEquals("The identity provider could not be reached. Try again.", e.getMessage());
    assertFalse(e.isRefusal());
  }

  @Test
  void issuerIsDerivedFromTheDiscoveryUri() {
    assertEquals(ISSUER, DirectorySettings.issuerOf(ISSUER + "/.well-known/openid-configuration"));
    assertEquals("", DirectorySettings.issuerOf("https://login.example/other"));
    assertEquals("", DirectorySettings.issuerOf(null));
  }
}
