package com.axelor.apps.napsubmissions.common.signing.evrotrust;

import com.axelor.apps.napsubmissions.common.signing.QesException;
import java.util.Objects;

/**
 * The NON-SECRET identity of the Evrotrust account that must sign — exactly the fields the
 * documented {@code /document/hash/online} request needs to address one signer, and nothing more.
 *
 * <p><b>Where it comes from.</b> The provider-neutral {@code QesSigningRequest} carries only an
 * opaque {@code signerProfileRef}. For Evrotrust that reference is parsed here into the concrete
 * identity the vendor API requires. The encoding below is <em>Nyxa's own</em> non-secret convention
 * — it is NOT a guess at Evrotrust wire fields; it merely transports identity values Nyxa already
 * holds (the signer identifier on {@code UserQesProfile.signerPin}) to the point where the
 * documented request is built.
 *
 * <p><b>⚠️ certificateSerialNumber.</b> The Evrotrust docs require {@code certificateSerialNumber}
 * whenever the signer is addressed by identification number, phone or e-mail. It is NEVER typed by
 * a person and never part of the reference: the provider looks it up via the documented {@code
 * /user/certificate/get} right before {@code /document/hash/online} and attaches it with {@link
 * #withCertificateSerialNumber}.
 *
 * <p>Format (semicolon-separated {@code key=value}, exactly one identity key):
 *
 * <pre>
 *   egn=&lt;identificationNumber&gt;[;country=&lt;ISO2&gt;]
 *   email=&lt;address&gt;
 *   phone=&lt;+digits&gt;
 * </pre>
 *
 * <p>No private key, PIN or password is ever carried here.
 */
public final class EvrotrustSigner {

  private final String identificationNumber;
  private final String country;
  private final String email;
  private final String phone;
  private final String certificateSerialNumber;

  private EvrotrustSigner(
      String identificationNumber,
      String country,
      String email,
      String phone,
      String certificateSerialNumber) {
    this.identificationNumber = identificationNumber;
    this.country = country;
    this.email = email;
    this.phone = phone;
    this.certificateSerialNumber = certificateSerialNumber;
  }

  /**
   * Parse the opaque signer-profile reference into a concrete Evrotrust identity, applying {@code
   * defaultCountry} when none is given. Fails closed ({@link
   * QesException.Category#INVALID_REQUEST}) when the reference is blank, carries no identity key,
   * or carries more than one.
   */
  public static EvrotrustSigner fromProfileRef(String signerProfileRef, String defaultCountry) {
    if (signerProfileRef == null || signerProfileRef.isBlank()) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust signing requires a signer profile reference (the signer's non-secret"
              + " identity); none was supplied.");
    }
    String egn = null;
    String email = null;
    String phone = null;
    String country = null;
    for (String part : signerProfileRef.split(";")) {
      String token = part.trim();
      if (token.isEmpty()) {
        continue;
      }
      int eq = token.indexOf('=');
      if (eq <= 0) {
        throw new QesException(
            QesException.Category.INVALID_REQUEST,
            "Malformed Evrotrust signer reference segment: expected key=value.");
      }
      String key = token.substring(0, eq).trim().toLowerCase();
      String value = token.substring(eq + 1).trim();
      switch (key) {
        case "egn":
          egn = value;
          break;
        case "email":
          email = value;
          break;
        case "phone":
          phone = value;
          break;
        case "country":
          country = value.toUpperCase();
          break;
        default:
          throw new QesException(
              QesException.Category.INVALID_REQUEST,
              "Unknown Evrotrust signer reference key: " + key);
      }
    }

    int identities = count(egn) + count(email) + count(phone);
    if (identities == 0) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust signer reference carries no identity (egn/email/phone).");
    }
    if (identities > 1) {
      throw new QesException(
          QesException.Category.INVALID_REQUEST,
          "Evrotrust signer reference must carry exactly one identity (egn OR email OR phone).");
    }
    String resolvedCountry = country != null ? country : defaultCountry;
    return new EvrotrustSigner(
        blankToNull(egn), resolvedCountry, blankToNull(email), blankToNull(phone), null);
  }

  /** The same signer with the serial looked up from {@code /user/certificate/get} attached. */
  public EvrotrustSigner withCertificateSerialNumber(String serial) {
    return new EvrotrustSigner(identificationNumber, country, email, phone, blankToNull(serial));
  }

  public String getIdentificationNumber() {
    return identificationNumber;
  }

  public String getCountry() {
    return country;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }

  public String getCertificateSerialNumber() {
    return certificateSerialNumber;
  }

  private static int count(String value) {
    return value != null && !value.isBlank() ? 1 : 0;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EvrotrustSigner)) {
      return false;
    }
    EvrotrustSigner that = (EvrotrustSigner) o;
    return Objects.equals(identificationNumber, that.identificationNumber)
        && Objects.equals(country, that.country)
        && Objects.equals(email, that.email)
        && Objects.equals(phone, that.phone)
        && Objects.equals(certificateSerialNumber, that.certificateSerialNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(identificationNumber, country, email, phone, certificateSerialNumber);
  }
}
