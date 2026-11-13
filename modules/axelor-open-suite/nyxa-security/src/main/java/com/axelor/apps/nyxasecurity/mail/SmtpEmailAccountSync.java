package com.axelor.apps.nyxasecurity.mail;

import com.axelor.app.AppSettings;
import com.axelor.app.AvailableAppSettings;
import com.axelor.db.JPA;
import com.axelor.message.db.EmailAccount;
import com.axelor.message.db.repo.EmailAccountRepository;
import com.axelor.message.service.MailAccountService;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the platform's {@code mail.smtp.*} settings into the default sending {@code EmailAccount}.
 *
 * <p>Infra hands each tenant app its SMTP settings as {@code AXELOR_CONFIG_MAIL_SMTP_*} environment
 * variables. The platform reads those, but Open Suite sends through the default {@code
 * EmailAccount} row instead, and template emails fail outright without one ("There is no default
 * email account"). This creates that row at every boot and keeps it in step with the settings, so
 * the environment stays the single place SMTP is configured.
 *
 * <p>Without {@code mail.smtp.host} it does nothing: an environment without mail keeps working, and
 * an account an administrator set up by hand is left alone.
 */
@Singleton
public class SmtpEmailAccountSync {

  private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailAccountSync.class);

  /** Name of the managed row. Changing it in the UI makes it unmanaged. */
  static final String ACCOUNT_NAME = "SMTP (mail.smtp.* settings)";

  private final EmailAccountRepository accountRepo;
  private final MailAccountService mailAccountService;

  @Inject
  public SmtpEmailAccountSync(
      EmailAccountRepository accountRepo, MailAccountService mailAccountService) {
    this.accountRepo = accountRepo;
    this.mailAccountService = mailAccountService;
  }

  public void syncOnStartup() {
    AppSettings settings = AppSettings.get();
    String host = settings.get(AvailableAppSettings.MAIL_SMTP_HOST, "").trim();
    if (host.isEmpty()) {
      LOG.info("No mail.smtp.host: the application sends no email until an account is set up.");
      return;
    }
    String channel = settings.get(AvailableAppSettings.MAIL_SMTP_CHANNEL, "").trim();
    int security = security(channel);
    int port = settings.getInt(AvailableAppSettings.MAIL_SMTP_PORT, defaultPort(security));
    String user = settings.get(AvailableAppSettings.MAIL_SMTP_USER, "").trim();
    String password = settings.get(AvailableAppSettings.MAIL_SMTP_PASSWORD, "");
    String from = settings.get(AvailableAppSettings.MAIL_SMTP_FROM, "").trim();

    JPA.runInTransaction(
        () -> {
          EmailAccount account = accountRepo.findByName(ACCOUNT_NAME);
          if (account == null) {
            account = new EmailAccount();
            account.setName(ACCOUNT_NAME);
          }
          account.setServerTypeSelect(EmailAccountRepository.SERVER_TYPE_SMTP);
          account.setHost(host);
          account.setPort(port);
          account.setSecuritySelect(security);
          account.setLogin(user.isEmpty() ? null : user);
          account.setPassword(
              password.isEmpty() ? null : mailAccountService.getEncryptPassword(password));
          account.setFromAddress(from.isEmpty() ? null : from);
          account.setIsValid(true);
          account.setIsDefault(true);
          // One default sender: the environment's.
          for (EmailAccount other :
              accountRepo
                  .all()
                  .filter(
                      "self.isDefault = true AND self.serverTypeSelect = ? AND self.name != ?",
                      EmailAccountRepository.SERVER_TYPE_SMTP,
                      ACCOUNT_NAME)
                  .fetch()) {
            other.setIsDefault(false);
            accountRepo.save(other);
          }
          accountRepo.save(account);
        });
    LOG.info("Default email account synced from mail.smtp.* ({}:{}, {})", host, port, channel);
  }

  /** {@code mail.smtp.channel}: {@code starttls} or {@code ssl}, as the platform reads it. */
  static int security(String channel) {
    if ("ssl".equalsIgnoreCase(channel)) {
      return EmailAccountRepository.SECURITY_SSL;
    }
    if ("starttls".equalsIgnoreCase(channel)) {
      return EmailAccountRepository.SECURITY_STARTTLS;
    }
    return EmailAccountRepository.SECURITY_NONE;
  }

  static int defaultPort(int security) {
    return security == EmailAccountRepository.SECURITY_SSL
        ? 465
        : security == EmailAccountRepository.SECURITY_STARTTLS ? 587 : 25;
  }
}
