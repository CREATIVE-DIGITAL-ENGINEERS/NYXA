/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service;

/**
 * Static facade for Bulgarian identifier validation, usable from action-validate expressions and
 * controllers. EGN/ЛНЧ checksum + date logic is REUSED from {@link BgEgnValidationService} and ЕИК
 * from {@link BgEikValidationService} (single source of truth each); this class only adds the
 * OR-combination, the postal-code check and the branch by partner/identifier type.
 */
public final class BgIdentifierValidator {

  private static final BgEgnValidationService EGN = new BgEgnValidationService();
  private static final BgEikValidationService EIK = new BgEikValidationService();

  /** Стойностите на {@code bg.income.payment.identifier.type.select} (полето bgIdentifierType). */
  private static final String TYPE_EGN = "0";

  private static final String TYPE_LN_LNCH_NAP = "1";
  private static final String TYPE_BULSTAT = "2";
  private static final String TYPE_NO_IDENTIFIER = "3";

  /** Какво изобщо се проверява за даден ред. */
  private enum Kind {
    EIK,
    EGN,
    EGN_OR_LNCH,
    SKIP
  }

  private BgIdentifierValidator() {}

  /** True for a structurally valid ЕГН (10 digits, valid encoded date, control digit). */
  public static boolean isValidEgn(String value) {
    return EGN.validateEgn(value);
  }

  /** True for a structurally valid ЛНЧ (10 digits, control digit; no date component). */
  public static boolean isValidLnch(String value) {
    return EGN.validateLnch(value);
  }

  /** True when the value is a valid ЕГН OR a valid ЛНЧ. */
  public static boolean isValidEgnOrLnch(String value) {
    return isValidEgn(value) || isValidLnch(value);
  }

  /** True for a structurally valid ЕИК — 9-значен или 13-значен. Всяка друга дължина е false. */
  public static boolean isValidEik(String value) {
    return EIK.validateEik(value);
  }

  /** True for a Bulgarian postal code: exactly 4 digits in the range 1000–9999. */
  public static boolean isValidPostalCode(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (!trimmed.matches("\\d{4}")) {
      return false;
    }
    int code = Integer.parseInt(trimmed);
    return code >= 1000 && code <= 9999;
  }

  // ------------------------------------------------------------------ Partner.registrationCode

  /**
   * Верен ли е {@code registrationCode} за този партньор — общата присъда.
   *
   * <p>Празен код и неприложим случай дават {@code true}: тази функция отговаря на „има ли ГРЕШКА",
   * а не на „попълнено ли е". Задължителността на полето е друг въпрос и не се решава тук.
   *
   * <p><b>Кое се проверява се решава от ПРИЗНАКА, не от вида партньор.</b> {@code bgIdentifierType}
   * е данни, въведени от човек, и бие подразбирането; {@code partnerTypeSelect} се ползва само
   * когато признакът е празен. Причината е, че физическо лице МОЖЕ да носи БУЛСТАТ (свободна
   * професия), а тогава видът партньор сочи ЕГН и проверката би отхвърлила законен код.
   *
   * <p>⚠️ Тип „3" (чуждестранно лице без идентификатор) се ПРЕСКАЧА нарочно: по определение няма
   * какво да се провери, а колона 4 на справката по чл. 73, ал. 1 при него остава празна.
   */
  public static boolean isValidRegistrationCode(
      Integer partnerTypeSelect, String identifierType, String code) {
    return !isBlockingViolation(partnerTypeSelect, identifierType, code)
        && !isWarningViolation(partnerTypeSelect, identifierType, code);
  }

  /**
   * Нарушение, което ОТМЕНЯ записа: код от точно девет цифри, чиято контролна цифра не излиза.
   *
   * <p>⚠️ Блокира се САМО този случай, и то защото алгоритъмът за 9-значния ЕИК е доказан срещу
   * шест реални кода от Търговския регистър. Тринайсетцифреният и всяка друга дължина минават през
   * {@link #isWarningViolation} — там сметката не е сверена срещу реален код и блокиране би
   * отказало запис на законен клон.
   */
  public static boolean isBlockingViolation(
      Integer partnerTypeSelect, String identifierType, String code) {
    if (isBlank(code) || kindOf(partnerTypeSelect, identifierType) != Kind.EIK) {
      return false;
    }
    return isNineDigits(code) && !EIK.validateEik9(code);
  }

  /**
   * Нарушение, което само ПРЕДУПРЕЖДАВА — записът минава след потвърждение.
   *
   * <p>Тук влизат ЕГН, ЛНЧ, 13-значният ЕИК и всяка чужда на чл. 6 дължина. ⚠️ Тип „1" е ОБЕДИНЕНИЕ
   * от ЛН, ЛНЧ и служебен номер от регистъра на НАП; служебният номер няма контролна цифра, тоест
   * строг отказ там би отхвърлил законна стойност — затова и той е предупреждение.
   */
  public static boolean isWarningViolation(
      Integer partnerTypeSelect, String identifierType, String code) {
    if (isBlank(code)) {
      return false;
    }
    switch (kindOf(partnerTypeSelect, identifierType)) {
      case EIK:
        return !isNineDigits(code) && !isValidEik(code);
      case EGN:
        return !isValidEgn(code);
      case EGN_OR_LNCH:
        return !isValidEgnOrLnch(code);
      default:
        return false;
    }
  }

  /**
   * КАК СЕ КАЗВА проверяваното на екрана — {@code true} за ЕИК/Булстат, {@code false} за ЕГН/ЛНЧ.
   *
   * <p>⚠️ Нула ново правило. Съобщението иска същото разклонение, което {@link #kindOf} вече прави,
   * и точно затова не се преписва в XML-а: там {@code bgIdentifierType} щеше да се забрави и
   * физическо лице с БУЛСТАТ (свободна професия) щеше да получи съобщение за ЕГН. Един клон, един
   * източник.
   */
  public static boolean isCompanyIdentifier(Integer partnerTypeSelect, String identifierType) {
    return kindOf(partnerTypeSelect, identifierType) == Kind.EIK;
  }

  /**
   * Признакът бие вида партньор; празен признак пада към вида. Непозната стойност се ПРЕСКАЧА,
   * вместо да се познава — нова стойност в селекцията не бива да почне да отказва записи, преди
   * някой да е решил какво значи тя.
   */
  private static Kind kindOf(Integer partnerTypeSelect, String identifierType) {
    if (!isBlank(identifierType)) {
      switch (identifierType.trim()) {
        case TYPE_EGN:
          return Kind.EGN;
        case TYPE_LN_LNCH_NAP:
          return Kind.EGN_OR_LNCH;
        case TYPE_BULSTAT:
          return Kind.EIK;
        case TYPE_NO_IDENTIFIER:
          return Kind.SKIP;
        default:
          return Kind.SKIP;
      }
    }
    if (partnerTypeSelect == null) {
      return Kind.SKIP;
    }
    // PartnerRepository.PARTNER_TYPE_COMPANY == 1, PARTNER_TYPE_INDIVIDUAL == 2.
    return partnerTypeSelect == 1 ? Kind.EIK : Kind.EGN_OR_LNCH;
  }

  /**
   * Точно девет ЦИФРИ — не просто девет знака. Стойност като {@code "13017500X"} не е 9-значен код
   * и затова не бива да ОТМЕНЯ запис; тя пада в предупреждението, където ѝ е мястото.
   */
  private static boolean isNineDigits(String code) {
    return code != null && code.trim().matches("\\d{9}");
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
