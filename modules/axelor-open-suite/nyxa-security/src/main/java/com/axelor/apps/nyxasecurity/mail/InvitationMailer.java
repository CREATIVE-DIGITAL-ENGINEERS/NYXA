package com.axelor.apps.nyxasecurity.mail;

import com.axelor.app.AppSettings;
import com.axelor.app.AvailableAppSettings;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.message.db.EmailAccount;
import com.axelor.message.db.EmailAddress;
import com.axelor.message.db.Message;
import com.axelor.message.db.repo.EmailAddressRepository;
import com.axelor.message.db.repo.MessageRepository;
import com.axelor.message.service.MailAccountService;
import com.axelor.message.service.MessageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the invitation email once the directory has granted access.
 *
 * <p>Same content as the onboarding job's email ({@code invite-email.html}), which still invites
 * each tenant's first administrator; keep the two in step. English and Bulgarian, chosen by the
 * inviting administrator's language.
 *
 * <p>Goes out as an ordinary {@code Message}, so it is listed with the application's other emails,
 * with its status, and is sent by the same queue. Every value is HTML-escaped before it is put into
 * the template.
 */
@Singleton
public class InvitationMailer {

  private static final Logger LOG = LoggerFactory.getLogger(InvitationMailer.class);

  static final String TEMPLATE = "nyxa-security/mail/invitation_%s.html";

  private static final Map<String, String> SUBJECTS =
      Map.of(
          "en", "You have been given access to %s",
          "bg", "Получихте достъп до %s");

  private final MessageService messageService;
  private final MailAccountService mailAccountService;
  private final EmailAddressRepository emailAddressRepo;

  @Inject
  public InvitationMailer(
      MessageService messageService,
      MailAccountService mailAccountService,
      EmailAddressRepository emailAddressRepo) {
    this.messageService = messageService;
    this.mailAccountService = mailAccountService;
    this.emailAddressRepo = emailAddressRepo;
  }

  /**
   * Queues the invitation.
   *
   * @param language the inviting administrator's language; Bulgarian if it starts with "bg"
   * @return null when queued, else why it wasn't (for the administrator; access is granted anyway)
   */
  public String send(User invited, Collection<String> roles, String language) {
    EmailAccount account = mailAccountService.getDefaultSender();
    if (account == null) {
      return "No email account is configured, so no invitation email was sent.";
    }
    String lang = language != null && language.toLowerCase().startsWith("bg") ? "bg" : "en";
    String tenant = tenantName();
    String address = invited.getEmail() != null ? invited.getEmail() : invited.getCode();
    String html = render(template(lang), address, tenant, roles, loginUrl());
    String subject = String.format(SUBJECTS.get(lang), tenant);

    try {
      Message[] message = new Message[1];
      JPA.runInTransaction(
          () ->
              message[0] =
                  messageService.createMessage(
                      User.class.getName(),
                      invited.getId(),
                      subject,
                      html,
                      null,
                      List.of(),
                      List.of(emailAddress(address)),
                      List.of(),
                      List.of(),
                      Set.of(),
                      null,
                      MessageRepository.MEDIA_TYPE_EMAIL,
                      account,
                      null));
      messageService.sendMessage(message[0]);
      LOG.info("Invitation email queued for user {}", invited.getId());
      return null;
    } catch (Exception e) {
      LOG.warn("Invitation email for user {} not sent: {}", invited.getId(), e.getMessage());
      return "Access was granted, but the invitation email could not be sent: " + e.getMessage();
    }
  }

  /** The template with every placeholder replaced by its HTML-escaped value. */
  static String render(
      String template, String email, String tenant, Collection<String> roles, String loginUrl) {
    String display =
        roles == null || roles.isEmpty() ? "-" : String.join(", ", new TreeSet<>(roles));
    String html =
        template
            .replace("{{USER_EMAIL}}", escape(email))
            .replace("{{CLIENT_ID}}", escape(tenant))
            .replace("{{ROLES}}", escape(display))
            .replace("{{LOGIN_URL}}", escape(loginUrl));
    if (html.contains("{{")) {
      throw new IllegalStateException("Invitation template has an unknown placeholder.");
    }
    return html;
  }

  /** {@code &} first, so the entities added after it aren't escaped again. */
  static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }

  /**
   * Where the person signs in: this app's own address. Infra sets the OIDC callback URL per tenant
   * ({@code https://<client>.<domain>/callback}), and treats it as the authoritative app URL too;
   * {@code application.base-url} can't be set from the environment (it has a hyphen).
   */
  static String loginUrl() {
    AppSettings settings = AppSettings.get();
    String callback = settings.get("auth.provider.oidc.callback.url", "").trim();
    if (callback.endsWith("/callback")) {
      return callback.substring(0, callback.length() - "/callback".length()) + "/";
    }
    return settings.get(AvailableAppSettings.APPLICATION_BASE_URL, "");
  }

  /** The tenant as people know it in Keycloak and in the onboarding email: its client id. */
  private static String tenantName() {
    return AppSettings.get().get("auth.provider.oidc.client.id", "Nyxa");
  }

  private EmailAddress emailAddress(String address) {
    EmailAddress existing = emailAddressRepo.findByAddress(address);
    if (existing != null) {
      return existing;
    }
    EmailAddress created = new EmailAddress();
    created.setAddress(address);
    return emailAddressRepo.save(created);
  }

  private static String template(String lang) {
    String path = String.format(TEMPLATE, lang);
    try (InputStream in = InvitationMailer.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing invitation template " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
