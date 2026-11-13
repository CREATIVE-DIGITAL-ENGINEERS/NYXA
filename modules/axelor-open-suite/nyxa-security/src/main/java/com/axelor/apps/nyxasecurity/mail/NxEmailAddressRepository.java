package com.axelor.apps.nyxasecurity.mail;

import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.message.db.EmailAddress;
import com.axelor.message.db.repo.EmailAddressRepo;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * Replaces the address check of the message add-on's {@link EmailAddressRepo}, which refuses valid
 * addresses: its pattern ({@code ^[_A-Za-z0-9-\+]+(\.[_A-Za-z0-9-]+)*@...}) allows "+" only before
 * the first dot, so {@code first.last+tag@gmail.com} was rejected as "Invalid address format". That
 * broke invitations to such addresses, and every other place that stores one (partners, contacts,
 * message recipients).
 *
 * <p>An address is accepted here when JavaMail's strict RFC 822 parser accepts it as a single plain
 * address ({@code local@domain}, no display name), and its domain has a dot. Same error message as
 * the add-on, so existing translations still apply.
 *
 * <p>Bound on top of {@code EmailAddressRepo} (chained, as {@code MessageModule} already binds
 * {@code EmailAddressRepository} to it). {@code EmailAddressRepository} adds nothing to {@code
 * save}, so skipping the add-on's override means calling {@link JPA#save} directly.
 */
public class NxEmailAddressRepository extends EmailAddressRepo {

  @Override
  public EmailAddress save(EmailAddress entity) {
    String address = entity.getAddress();
    if (address != null && !isValid(address)) {
      throw new IllegalArgumentException(
          String.format(I18n.get("Invalid address format : %s"), address));
    }
    return JPA.save(entity);
  }

  static boolean isValid(String address) {
    String value = address.trim();
    if (!value.equals(address) || value.isEmpty()) {
      return false;
    }
    try {
      InternetAddress parsed = new InternetAddress(value, true);
      parsed.validate();
      String plain = parsed.getAddress();
      int at = plain == null ? -1 : plain.lastIndexOf('@');
      return value.equals(plain)
          && parsed.getPersonal() == null
          && at > 0
          && plain.indexOf('.', at) > at + 1
          && !plain.endsWith(".");
    } catch (AddressException e) {
      return false;
    }
  }
}
