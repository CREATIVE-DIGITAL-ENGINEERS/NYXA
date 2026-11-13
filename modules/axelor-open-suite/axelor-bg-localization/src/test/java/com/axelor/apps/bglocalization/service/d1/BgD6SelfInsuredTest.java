package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ДЕКЛАРАЦИЯ ОБР. 6 НА САМООСИГУРЯВАЩО СЕ ЛИЦЕ: ЕГН, не ЕИК — и никакво смесване.
 *
 * <p><b>Нормата, дословно.</b> Наредба Н-13, указания за декларация обр. 6, т. 1: „работодателите,
 * осигурителите и техните клонове и поделенията попълват ЕИК по БУЛСТАТ/ЕИК по ЗТРРЮЛНЦ или
 * служебен номер, издаден от НАП; <b>самоосигуряващите се лица и лицата по чл. 4, ал. 9 от КСО
 * попълват ЕГН (ЛН, ЛНЧ)</b> или служебен номер, издаден от НАП.“ Т. 2 съответно иска „имена по
 * документ за самоличност“ вместо наименование на осигурител.
 *
 * <p><b>⚠️ И ОТТАМ СЛЕДВА, ЧЕ ЕДИН ФАЙЛ НОСИ ЕДНО ЗАДЪЛЖЕНО ЛИЦЕ.</b> Т. 1 е поле на ДЕКЛАРАЦИЯТА,
 * а не на реда — значи фиш на нает и фиш на самоосигуряващо се лице в една Д6 биха дали сбор,
 * деклариран под чуждо число. Тук няма верен изход и затова смесването е ОТКАЗ, а не находка върху
 * иначе произведени суми.
 *
 * <p><b>Какво НЕ се променя.</b> Сумите. {@link BgD6Aggregator#byPoint} стига до точките през ФОНДА
 * и не знае откъде е дошъл фишът: Пенсии и ОЗМ → т. 10, УПФ → т. 12, здравно → т. 14. Това е
 * проверено тук нарочно — ако някой ден агрегаторът започне да чете носителя, свидетелят пада.
 */
class BgD6SelfInsuredTest {

  private static final BgD6Emitter EMITTER = new BgD6Emitter();

  private static BgContributionFund fund(String code, String d1Point) {
    BgContributionFund f = new BgContributionFund();
    f.setCode(code);
    f.setBgD1Field(d1Point);
    return f;
  }

  /** Перо на вноска ИЗЦЯЛО за сметка на лицето — КСО чл. 6 ал. 8. */
  private static BgPaySlipLine own(int number, String amount, BgContributionFund f) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(BgPaySlipResolver.DEBIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgContributionFund(f);
    return l;
  }

  private static BgSelfInsuredRegistration registration(String egn) {
    Partner person = new Partner();
    person.setName("ПЕТРОВ");
    person.setFirstName("Иван");

    Employee employee = new Employee();
    employee.setBgPersonalIdNumber(egn);
    employee.setContactPartner(person);

    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setEmployee(employee);
    reg.setBgIsSelfInsured(Boolean.TRUE);
    reg.setBgInsuredTypeCode("12");
    // ⚠️ НАРОЧНО РАЗЛИЧЕН ОТ ЕГН-а. Кодът на задълженото лице на регистрацията е за т. 4 на Д1 и
    // при съдружник носи ЕИК на ДРУЖЕСТВОТО; т. 1 на Д6 иска ЕГН. Две различни точки, две различни
    // числа — заети едно за друго, декларацията тръгва под грешен подател, а еднакви фикстури тук
    // биха скрили точно тази размяна.
    reg.setBgObligedPersonCode("831919536");
    return reg;
  }

  /** Фишът на самоосигуряващото се лице: четирите му вноски, всичките лични. */
  private static BgPaySlip selfInsuredSlip(BgSelfInsuredRegistration reg) {
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(own(501, "148.00", fund("PENSION", BgD1Point.T22_DOO)));
    lines.add(own(505, "35.00", fund("OZM", BgD1Point.T22_DOO)));
    lines.add(own(513, "50.00", fund("UPF", BgD1Point.T26_UPF)));
    lines.add(own(517, "80.00", fund("HEALTH", BgD1Point.T22_1_HEALTH)));

    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-2026-03-0007");
    s.setBgPaySlipLineList(lines);
    s.setBgSelfInsuredRegistration(reg);
    return s;
  }

  private static BgPaySlip employedSlip() {
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(own(501, "10.00", fund("PENSION", BgD1Point.T22_DOO)));

    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-2026-03-0001");
    s.setBgPaySlipLineList(lines);
    s.setEmploymentContract(new EmploymentContract());
    return s;
  }

  private static BgD6Declaration declaration(BgSelfInsuredRegistration reg) {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("201234567");
    Company company = new Company();
    company.setPartner(companyPartner);

    BgD6Declaration d = new BgD6Declaration();
    d.setCompany(company);
    d.setBgSelfInsuredRegistration(reg);
    return d;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ЗЛАТНОТО: т. 1 носи ЕГН, а не ЕИК на дружеството — макар дружество да Е закачено.
   *
   * <p>Дружеството остава на декларацията (тя си има {@code company} като {@code required}), тоест
   * най-правдоподобната грешка е т. 1 да продължи да идва оттам. Фикстурата затова носи И двете:
   * ЕИК 201234567 на дружеството и ЕГН 7501015678 на лицето.
   */
  @Test
  @DisplayName("⚠️ т. 1 · САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ попълва ЕГН, не ЕИК на дружеството")
  void theSelfInsuredDeclarationCarriesThePersonalId() {
    BgSelfInsuredRegistration reg = registration("7501015678");
    BgD6Declaration d = declaration(reg);

    BgD6Emitter.Outcome outcome = EMITTER.emit(d, Collections.singletonList(selfInsuredSlip(reg)));

    assertEquals("7501015678", d.getT1ObligedPersonCode());
    assertTrue(
        !"201234567".equals(d.getT1ObligedPersonCode()),
        "ЕИК на дружеството НЕ е задълженото лице при самоосигуряващ се");
    assertEquals("Иван ПЕТРОВ", d.getT2Name());
    assertTrue(outcome.getFindings().isEmpty() || noRefusal(outcome), outcome.findingReport());
  }

  /**
   * ⚠️ СУМИТЕ НЕ ЗАВИСЯТ ОТ НОСИТЕЛЯ — агрегаторът стига до точките през ФОНДА.
   *
   * <p>Пенсии 148,00 + ОЗМ 35,00 → т. 10; УПФ 50,00 → т. 12; здравно 80,00 → т. 14. И нито един лев
   * в т. 11 (УчПФ), т. 13 (ППФ) или т. 15 (ГВРС): самоосигуряващият се не е по трудово
   * правоотношение и КСО чл. 4 ал. 3 не му дава тези фондове.
   */
  @Test
  @DisplayName("⚠️ т. 10/12/14 · 183,00 + 50,00 + 80,00 — и нищо в УчПФ, ППФ и ГВРС")
  void theAmountsFollowTheFundNotTheCarrier() {
    BgSelfInsuredRegistration reg = registration("7501015678");
    BgD6Declaration d = declaration(reg);
    EMITTER.emit(d, Collections.singletonList(selfInsuredSlip(reg)));

    // 148,00 (Пенсии) + 35,00 (ОЗМ) — т. 10 е ВСИЧКИ фондове на ДОО.
    assertEquals(0, new BigDecimal("183.00").compareTo(d.getT10Doo()));
    assertEquals(0, new BigDecimal("50.00").compareTo(d.getT12Upf()));
    assertEquals(0, new BigDecimal("80.00").compareTo(d.getT14Health()));
    assertEquals(0, BigDecimal.ZERO.compareTo(d.getT11TeacherPf()));
    assertEquals(0, BigDecimal.ZERO.compareTo(d.getT13Ppf()));
    assertEquals(0, BigDecimal.ZERO.compareTo(d.getT15Gvrs()));
  }

  /**
   * ⚠️ СМЕСЕНИ НОСИТЕЛИ → ОТКАЗ, И НИТО ЕДНО ПОЛЕ НЕ СЕ ПЪЛНИ.
   *
   * <p>Ранното връщане е част от твърдението: попълнени и после отказани, сумите биха стояли на
   * екрана и биха изглеждали като готова декларация.
   */
  @Test
  @DisplayName("⚠️ СМЕСВАНЕ · фиш на нает в декларация на СОЛ → ОТКАЗ, полетата остават празни")
  void mixingAnEmployedSlipIntoASelfInsuredDeclarationIsRefused() {
    BgSelfInsuredRegistration reg = registration("7501015678");
    BgD6Declaration d = declaration(reg);

    BgD6Emitter.Outcome outcome =
        EMITTER.emit(d, Arrays.asList(selfInsuredSlip(reg), employedSlip()));

    assertTrue(hasRefusal(outcome), outcome.findingReport());
    assertTrue(
        outcome.findingReport().contains("САМООСИГУРЯВАЩО СЕ ЛИЦЕ"), outcome.findingReport());
    assertTrue(outcome.findingReport().contains("FISH-2026-03-0001"), "отказът назовава фиша");
    assertNull(d.getT1ObligedPersonCode(), "нито едно поле не се пълни при отказ");
    assertEquals(0, BigDecimal.ZERO.compareTo(d.getT10Doo()));
  }

  /** ⚠️ И ОБРАТНАТА ПОСОКА: фиш на СОЛ в декларация на осигурител също се отказва. */
  @Test
  @DisplayName("⚠️ СМЕСВАНЕ · фиш на СОЛ в декларация на ОСИГУРИТЕЛ → също ОТКАЗ")
  void mixingASelfInsuredSlipIntoAnEmployerDeclarationIsRefused() {
    BgD6Declaration d = declaration(null);
    BgD6Emitter.Outcome outcome =
        EMITTER.emit(d, Arrays.asList(employedSlip(), selfInsuredSlip(registration("7501015678"))));

    assertTrue(hasRefusal(outcome), outcome.findingReport());
    assertTrue(outcome.findingReport().contains("ОСИГУРИТЕЛ"), outcome.findingReport());
  }

  /**
   * ⚠️ ЧИСТАТА ДЕКЛАРАЦИЯ НА ОСИГУРИТЕЛЯ ПРОДЪЛЖАВА ДА РАБОТИ — пазачът не спира редовната работа.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · декларация само с наети минава и т. 1 пак е ЕИК")
  void theEmployerPathIsUnchanged() {
    BgD6Declaration d = declaration(null);
    BgD6Emitter.Outcome outcome = EMITTER.emit(d, Collections.singletonList(employedSlip()));

    assertTrue(!hasRefusal(outcome), outcome.findingReport());
    assertEquals("201234567", d.getT1ObligedPersonCode());
    assertEquals("Никса ЕООД", d.getT2Name());
    assertEquals(0, new BigDecimal("10.00").compareTo(d.getT10Doo()));
  }

  /** ⚠️ ЛИПСВАЩО ЕГН е НАХОДКА, не тиха празнота: декларация без подател не се приема. */
  @Test
  @DisplayName("⚠️ т. 1 · липсващо ЕГН вдига находка, а не оставя полето мълчаливо празно")
  void aMissingPersonalIdIsAnnounced() {
    BgSelfInsuredRegistration reg = registration(null);
    BgD6Declaration d = declaration(reg);
    BgD6Emitter.Outcome outcome = EMITTER.emit(d, Collections.singletonList(selfInsuredSlip(reg)));

    assertNull(d.getT1ObligedPersonCode());
    assertTrue(outcome.findingReport().contains("ЕГН/ЛНЧ"), outcome.findingReport());
  }

  private static boolean hasRefusal(BgD6Emitter.Outcome outcome) {
    for (BgD1FindingDto f : outcome.getFindings()) {
      if (BgD1FindingDto.SEVERITY_REFUSAL.equals(f.getSeverity())) {
        return true;
      }
    }
    return false;
  }

  private static boolean noRefusal(BgD6Emitter.Outcome outcome) {
    return !hasRefusal(outcome);
  }
}
