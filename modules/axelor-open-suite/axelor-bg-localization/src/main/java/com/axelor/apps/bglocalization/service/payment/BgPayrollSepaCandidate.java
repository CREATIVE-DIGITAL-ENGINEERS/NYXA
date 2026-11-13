package com.axelor.apps.bglocalization.service.payment;

import java.math.BigDecimal;

/**
 * ЕДНО ЛИЦЕ ОТ ПРОБЕГА, СВЕДЕНО ДО ТОВА, КОЕТО ПЛАТЕЖНИЯТ ФАЙЛ ИСКА.
 *
 * <p><b>⚠️ ПРОСТИ СТОЙНОСТИ, А НЕ ЕНТИТЕТИ, И ТОВА Е ЦЯЛАТА ПРИЧИНА КЛАСЪТ ДА СЪЩЕСТВУВА.</b>
 * Модулът няма mock-подпора в тестовете: правило, което иска свидетел с ЧИСЛА и с ИМЕНА, трябва да
 * се извика без Guice и без база. Затова тук стои {@code slipId} вместо {@code BgPaySlip} и {@code
 * iban} вместо {@code BankDetails} — {@link BgPayrollSepaRules} се проверява с ръчно сглобени
 * редове, а услугата остава единственото място, което чете обектния граф.
 *
 * <p><b>⚠️ И ЗАТОВА „ЗАЩО СМЕТКАТА НЕ СТАВА“ Е НИЗ, А НЕ ФЛАГ.</b> Отказът е ПОИМЕНЕН и трябва да
 * каже на човека какво да попълни: лице без партньор, партньор без сметка по подразбиране,
 * деактивирана сметка и сметка с празен IBAN се поправят на четири различни екрана. Булево „не е
 * годна“ би ги слепило в едно изречение, което не сочи никъде.
 */
public final class BgPayrollSepaCandidate {

  private final Long slipId;
  private final String slipReference;
  private final String personName;
  private final String iban;
  private final String ibanProblem;
  private final BigDecimal net;

  private BgPayrollSepaCandidate(
      Long slipId,
      String slipReference,
      String personName,
      String iban,
      String ibanProblem,
      BigDecimal net) {
    this.slipId = slipId;
    this.slipReference = slipReference;
    this.personName = personName;
    this.iban = iban;
    this.ibanProblem = ibanProblem;
    this.net = net;
  }

  /** Годно лице: намерена, активна сметка с IBAN. */
  public static BgPayrollSepaCandidate payable(
      Long slipId, String slipReference, String personName, String iban, BigDecimal net) {
    return new BgPayrollSepaCandidate(slipId, slipReference, personName, iban, null, net);
  }

  /** Лице, чиято платежна сметка не става — {@code problem} казва КОЕ поле липсва. */
  public static BgPayrollSepaCandidate withoutAccount(
      Long slipId, String slipReference, String personName, String problem, BigDecimal net) {
    return new BgPayrollSepaCandidate(slipId, slipReference, personName, null, problem, net);
  }

  public Long getSlipId() {
    return slipId;
  }

  public String getSlipReference() {
    return slipReference == null ? "(без номер)" : slipReference;
  }

  public String getPersonName() {
    return personName == null || personName.trim().isEmpty() ? "(без име)" : personName.trim();
  }

  public String getIban() {
    return iban;
  }

  public String getIbanProblem() {
    return ibanProblem;
  }

  public BigDecimal getNet() {
    return net;
  }

  /** Има ли какво да се плати на това лице — нулата е ВАЛИДЕН отговор, не липса. */
  public boolean hasNet() {
    return net != null && net.signum() > 0;
  }

  public boolean hasNegativeNet() {
    return net != null && net.signum() < 0;
  }

  public boolean hasUsableAccount() {
    return ibanProblem == null && iban != null && !iban.trim().isEmpty();
  }
}
