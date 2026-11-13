package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.d1.BgD1RecordBuilder;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ЧЕТЕНЕТО НА ДОГОВОРА — <b>само каквото вече е в модела, и нито едно ново поле</b>.
 *
 * <p><b>⚠️ ТОЗИ КЛАС НАРОЧНО НЕ ЗНАЕ НИЩО ЗА ЕТЗ.</b> Той събира реквизитите от {@link
 * EmploymentContract}, БГ разширението му, {@link Employee} и {@link Company} и спира дотам.
 * Преобразуването към номенклатурите на регистъра и решението кой реквизит е задължителен за кое
 * събитие са в {@link BgEtzResolver}. Слети, двете биха дали клас, който не може да се провери без
 * база: четенето иска записи, а правилата — не.
 *
 * <p><b>⚠️ И ЗАТОВА ПОЛЕТАТА ТУК МОГАТ ДА СА ПРАЗНИ.</b> Фактите описват какво ИМА, не какво
 * ТРЯБВА. Липсата се превръща в {@link BgEtzGap} едно ниво по-горе, където се знае кое събитие се
 * подава — датата на прекратяване е задължителна при прекратяване и е празна по правило при
 * сключване.
 *
 * <p><b>⚠️ NULL-ПРОВЕРКИТЕ СА САМО ТАМ, КЪДЕТО NULL Е ВЪЗМОЖЕН.</b> Генерираните getter-и на Axelor
 * връщат стойност по подразбиране за {@code BigDecimal}, {@code Boolean}, {@code Integer} и {@code
 * Long} — проверка за null върху тях е мъртъв код и {@code BgDefaultedGetterTest} вали билда заради
 * нея. {@code String}, {@code LocalDate} и връзките наистина могат да са null и се пазят.
 */
public final class BgEtzFacts {

  private final EmploymentContract contract;

  private BgEtzFacts(EmploymentContract contract) {
    this.contract = contract;
  }

  /**
   * Фактите на един договор.
   *
   * @throws IllegalArgumentException при {@code null} договор — това е програмна грешка, не липса
   *     на данни, и не бива да се смесва с обявените дупки
   */
  public static BgEtzFacts of(EmploymentContract contract) {
    if (contract == null) {
      throw new IllegalArgumentException("няма договор, от който да се четат факти");
    }
    return new BgEtzFacts(contract);
  }

  /** Самият договор — за връзката към подаването ({@code relatedRecordId}). */
  public EmploymentContract getContract() {
    return contract;
  }

  // ── работодател ───────────────────────────────────────────────────────────

  /** Дружеството-работодател. */
  public Company getCompany() {
    return contract.getPayCompany();
  }

  /**
   * ЕИК по БУЛСТАТ на работодателя.
   *
   * <p>⚠️ {@code registrationCode}, НЕ {@code taxNbr}. Същият капан като при т. 4 на Д1: ДДС
   * номерът носи представка „BG“ и е различно число за едно и също дружество.
   */
  public String getEmployerEik() {
    Company company = getCompany();
    Partner partner = company == null ? null : company.getPartner();
    String code = partner == null ? null : partner.getRegistrationCode();
    return code == null ? null : code.trim();
  }

  // ── наето лице ────────────────────────────────────────────────────────────

  public Employee getEmployee() {
    return contract.getEmployee();
  }

  /** ЕГН или ЛНЧ на наетото лице. */
  public String getPersonalIdNumber() {
    Employee employee = getEmployee();
    String value = employee == null ? null : employee.getBgPersonalIdNumber();
    return value == null ? null : value.trim();
  }

  /** Видът на идентификатора по {@code bg.personal.id.type.select}: „1“ ЕГН, „2“ ЛНЧ. */
  public String getPersonalIdType() {
    Employee employee = getEmployee();
    String value = employee == null ? null : employee.getBgPersonalIdType();
    return value == null ? null : value.trim();
  }

  // ── правоотношение ────────────────────────────────────────────────────────

  /** Видът договор по {@code bg.employment.contract.type.select} — „67“, „68“, „111“, „duk“ … */
  public String getContractType() {
    String value = contract.getBgContractType();
    return value == null ? null : value.trim();
  }

  /** Актът, по който се прекратява — „KT“, „ZDSL“, „ZVO“; носител е речникът на основанията. */
  public String getLegalFamily() {
    EndOfContractReason reason = contract.getEndOfContractReason();
    String value = reason == null ? null : reason.getBgLegalFamily();
    return value == null ? null : value.trim();
  }

  /** Членът от акта — „325_1_1“, „328_1_2“ … по {@code bg.termination.ground.select}. */
  public String getTerminationGround() {
    EndOfContractReason reason = contract.getEndOfContractReason();
    String value = reason == null ? null : reason.getBgTerminationGround();
    return value == null ? null : value.trim();
  }

  /** Кодът по НКПД на длъжността. */
  public String getNkpdCode() {
    return contract.getBgNkpd() == null ? null : contract.getBgNkpd().getCode();
  }

  /** Партньорът-контакт на лицето — носителят на трите имена. */
  private Partner contactPartner() {
    Employee employee = getEmployee();
    return employee == null ? null : employee.getContactPartner();
  }

  /**
   * Собственото име (т. 7).
   *
   * <p>⚠️ БЪЛГАРСКОТО ИМЕ Е ТРИЧАСТНО И СТОИ НА ТРИ РАЗЛИЧНИ ПОЛЕТА: {@code Partner.firstName} е
   * собственото, {@code Partner.bgMiddleName} — презимето, а {@code Partner.name} — ФАМИЛИЯТА.
   * Последното е капанът: в стоковия модел {@code name} е „името“ на партньора, тоест изглежда като
   * пълно име, а регистърът го иска като фамилия.
   */
  public String getFirstName() {
    Partner partner = contactPartner();
    return partner == null ? null : trim(partner.getFirstName());
  }

  /** Презимето (т. 8) — единственото име, което схемата приема празно. */
  public String getMiddleName() {
    Partner partner = contactPartner();
    return partner == null ? null : trim(partner.getBgMiddleName());
  }

  /** Фамилията (т. 9) — виж бележката при {@link #getFirstName()} защо е {@code name}. */
  public String getLastName() {
    Partner partner = contactPartner();
    return partner == null ? null : trim(partner.getName());
  }

  /** Основното трудово възнаграждение (т. 18). */
  public BigDecimal getBaseSalary() {
    return contract.getBgBaseSalary();
  }

  /** Дневната продължителност на работното време — вход за т. 23 и 23А. */
  public BigDecimal getHoursPerDay() {
    return contract.getBgHoursPerDay();
  }

  /** Уговореният платен годишен отпуск (т. 24). */
  public BigDecimal getAnnualLeaveDays() {
    return contract.getBgAnnualLeaveDays();
  }

  /**
   * Кодът по КИД (т. 21).
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ОТ ДРУЖЕСТВОТО, А НАРЕДБАТА ГОВОРИ ЗА ЛИЦЕТО.</b> Т. 21 иска кода на
   * икономическата дейност, в която е заето ЛИЦЕТО; носителят в модула е {@code Company.bgNkid},
   * тоест основната дейност на ДРУЖЕСТВОТО. За едно-дейностен работодател двете съвпадат; за
   * работодател с няколко дейности — не. Наредбата дава и изричен изход за спомагателните дейности
   * („0000“), но той е ИЗЯВЛЕНИЕ и не се слага автоматично.
   *
   * <p><b>⚠️ ЧЕТИРИ ЦИФРИ, БЕЗ ТОЧКАТА.</b> Речникът носи „62.01“ (всичките редове на {@code
   * bg_nkid.csv}), а т. 21 е четирицифрен код — схемата иска {@code [0-9]{4}}. Същото правило като
   * поле 29 на Д1 ({@link BgD1RecordBuilder#activityCode}): точката се маха, не се реже.
   */
  public String getKidCode() {
    Company company = getCompany();
    return company == null || company.getBgNkid() == null
        ? null
        : BgD1RecordBuilder.activityCode(company.getBgNkid().getCode());
  }

  /** Кодът по ЕКАТТЕ на мястото на работа (т. 22). */
  public String getEkatteCode() {
    return trim(contract.getBgWorkplaceEkatte());
  }

  // ── дати ──────────────────────────────────────────────────────────────────

  /** Датата на подписване на договора. */
  public LocalDate getSignatureDate() {
    return contract.getSignatureDate();
  }

  /** Началото на изпълнението. */
  public LocalDate getStartDate() {
    return contract.getStartDate();
  }

  /**
   * Уговореният край при СРОЧНО правоотношение.
   *
   * <p>⚠️ Различна дата от {@link #getTerminationDate()}: {@code endDate} се попълва при
   * подписването и означава УГОВОРЕНИЯ край, а прекратяването е денят, в който договорът наистина е
   * свършил. Съвпадат, когато срокът просто е изтекъл.
   */
  public LocalDate getAgreedEndDate() {
    return contract.getEndDate();
  }

  /** Денят, в който договорът наистина е прекратен. */
  public LocalDate getTerminationDate() {
    return contract.getBgTerminationDate();
  }

  /**
   * Датата на ПОСЛЕДНОТО изменение.
   *
   * <p><b>⚠️ ПОСЛЕДНОТО, А НЕ ВСЯКО — И ТОВА Е ДУПКА В МОДЕЛА, НЕ В ТОЗИ КЛАС.</b> {@link
   * EmploymentContract} носи едно-единствено {@code amendmentDate} и го ПРЕЗАПИСВА при всяко ново
   * допълнително споразумение. {@code DEC_ETZ62} иска всяко изменение поотделно. Тоест за договор с
   * три анекса модулът може да възпроизведе само третия. Поправката не е приложена — тя мени
   * заварена entity.
   */
  public LocalDate getLastAmendmentDate() {
    return contract.getAmendmentDate();
  }

  /**
   * Версията на договора — колко изменения са минали.
   *
   * <p>⚠️ {@code Integer} с подразбиране: генерираният getter връща 0, не null.
   */
  public int getVersion() {
    return contract.getEmploymentContractVersion();
  }

  /** Изрязва и връща {@code null} за празното — празният низ не е стойност, а липса. */
  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Видът на последното изменение.
   *
   * <p><b>⚠️ БЕЗПОЛЕЗЕН Е КАТО ОСНОВАНИЕ, И ТОВА Е ИЗМЕРЕНО, НЕ ПРЕДПОЛОЖЕНО.</b> {@code
   * amendmentTypeSelect} е цяло число от селекция на upstream модула и не носи кодирано основание
   * от номенклатурата на регистъра. Регистърът иска кодирано основание за изменението — тоест това
   * поле не може да го даде и стойността му тук се чете само за отчет.
   */
  public int getAmendmentType() {
    return contract.getAmendmentTypeSelect();
  }
}
