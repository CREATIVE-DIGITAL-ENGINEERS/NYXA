package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.saft.BgSaftPartyId.Issue;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CustomerID/SupplierID — ТАБЛИЦАТА ОТ КОЛОНА F НА MF.C.3, ВСЕКИ КЛОН С ЧИСЛА.
 *
 * <p>Еталоните за ЕИК/ЕГН/ЛНЧ са същите като в тестовете на валидаторите: 130175000 и 1301750000015
 * са валидни ЕИК, 130175001 — с грешна контролна цифра; 6803030040 е валидно ЕГН, 1000000001 —
 * валиден ЛНЧ.
 */
class BgSaftPartyIdTest {

  private static final Integer COMPANY = 1;
  private static final Integer INDIVIDUAL = 2;

  private static void assertId(
      String partyId, String code, Set<Issue> issues, BgSaftPartyId actual) {
    assertEquals(partyId, actual.getPartyId(), "CustomerID");
    assertEquals(code, actual.getCode(), "RegistrationNumber");
    assertEquals(partyId.substring(0, 2), actual.getType());
    assertEquals(issues, actual.getIssues());
  }

  private static Set<Issue> none() {
    return EnumSet.noneOf(Issue.class);
  }

  // ─────────────── 10 ───────────────

  @Test
  @DisplayName("10: българско юридическо лице — ЕИК, без префикс")
  void десет_еик() {
    assertId(
        "10130175000",
        "130175000",
        none(),
        BgSaftPartyId.of(COMPANY, null, "130175000", "BG130175000", "BG", "P1"));
  }

  @Test
  @DisplayName("10: 9-цифрен ЕИК с грешна контролна цифра е ГРЕШКА, 13-цифреният — предупреждение")
  void десет_невалиден() {
    assertId(
        "10130175001",
        "130175001",
        EnumSet.of(Issue.EIK_INVALID),
        BgSaftPartyId.of(COMPANY, null, "130175001", null, "BG", "P1"));
    assertId(
        "101301750000015",
        "1301750000015",
        none(),
        BgSaftPartyId.of(COMPANY, null, "1301750000015", null, "BG", "P1"));
    assertId(
        "101301750000016",
        "1301750000016",
        EnumSet.of(Issue.UNVERIFIED),
        BgSaftPartyId.of(COMPANY, null, "1301750000016", null, "BG", "P1"));
  }

  @Test
  @DisplayName("10: „BG“ в регистрационния номер се маха — „10BG…“ НАП отказва (QA IV.2.4)")
  void десет_без_префикс() {
    assertId(
        "10130175000",
        "130175000",
        EnumSet.of(Issue.PREFIX_REMOVED),
        BgSaftPartyId.of(COMPANY, null, "BG130175000", null, "BG", "P1"));
  }

  @Test
  @DisplayName("10: физическо лице с БУЛСТАТ — по признака „2“ и по 9-цифрен валиден ЕИК")
  void десет_булстат_на_физическо_лице() {
    assertId(
        "10130175000",
        "130175000",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, "2", "130175000", null, "BG", "P1"));
    assertId(
        "10130175000",
        "130175000",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, null, "130175000", null, "BG", "P1"));
  }

  // ─────────────── 11 ───────────────

  @Test
  @DisplayName("11: ДДС номер в ЕС с префикса на държавата; RegistrationNumber е без типа")
  void единайсет() {
    assertId(
        "11DE123456789",
        "DE123456789",
        none(),
        BgSaftPartyId.of(COMPANY, null, "HRB 1234", "DE123456789", "DE", "P2"));
    // Без префикс в полето — добавя се този на държавата от адреса.
    assertId(
        "11DE123456789",
        "DE123456789",
        none(),
        BgSaftPartyId.of(COMPANY, null, null, "123456789", "DE", "P2"));
    // Гърция: ISO GR, префиксът по ДДС е EL (примерът в книгата е 11EL…).
    assertId(
        "11EL123456789",
        "EL123456789",
        none(),
        BgSaftPartyId.of(COMPANY, null, null, "123456789", "GR", "P2"));
    // Без държава в адреса — префиксът на номера казва, че е чужд.
    assertId(
        "11ATU12345678",
        "ATU12345678",
        none(),
        BgSaftPartyId.of(COMPANY, null, null, "ATU 123 456 78", null, "P2"));
  }

  @Test
  @DisplayName("11 бие служебния номер 307… (QA с. 36)")
  void единайсет_преди_служебния() {
    assertId(
        "11DE123456789",
        "DE123456789",
        none(),
        BgSaftPartyId.of(COMPANY, null, "3070001234", "DE123456789", "DE", "P2"));
  }

  // ─────────────── 12 ───────────────

  @Test
  @DisplayName("12: извън ЕС — ISO2 и ДДС номерът без собствения префикс, или регистровият номер")
  void дванайсет() {
    assertId(
        "12US987654321",
        "US987654321",
        none(),
        BgSaftPartyId.of(COMPANY, null, null, "987654321", "US", "P3"));
    assertId(
        "12GB123456789",
        "GB123456789",
        none(),
        BgSaftPartyId.of(COMPANY, null, null, "GB123456789", "GB", "P3"));
    assertId(
        "12TR123005284",
        "TR123005284",
        none(),
        BgSaftPartyId.of(COMPANY, null, "123005284", null, "TR", "P3"));
  }

  // ─────────────── 13 ───────────────

  @Test
  @DisplayName("13: ЕГН и ЛНЧ — по признака или по вида партньор")
  void тринайсет() {
    assertId(
        "136803030040",
        "6803030040",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, "0", "6803030040", null, "BG", "P4"));
    assertId(
        "131000000001",
        "1000000001",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, "1", "1000000001", null, "BG", "P4"));
    assertId(
        "136803030040",
        "6803030040",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, null, "6803030040", null, null, "P4"));
  }

  @Test
  @DisplayName(
      "13: ЕГН (признак „0“) с грешна контролна цифра е ГРЕШКА; ЛНЧ и без признак — предупреждение")
  void тринайсет_невалиден() {
    assertId(
        "135009163204",
        "5009163204",
        EnumSet.of(Issue.PERSON_ID_INVALID),
        BgSaftPartyId.of(INDIVIDUAL, "0", "5009163204", null, "BG", "P4"));
    // 1000000002: нито ЛНЧ (контролната е 1), нито ЕГН (месец 00). Теглата на ЛНЧ нямат официален
    // източник — затова е предупреждение, както 13-цифреният ЕИК.
    assertId(
        "131000000002",
        "1000000002",
        EnumSet.of(Issue.UNVERIFIED),
        BgSaftPartyId.of(INDIVIDUAL, "1", "1000000002", null, "BG", "P4"));
    // Без признак се пита ЕГН ИЛИ ЛНЧ — а 5009163204 е невалидно ЕГН, но ВАЛИДЕН ЛНЧ (контролна 4),
    // затова свидетелят тук е 5009163205: нито едното, нито другото. Без признак не се знае дали е
    // ЕГН или ЛНЧ — предупреждение.
    assertId(
        "135009163205",
        "5009163205",
        EnumSet.of(Issue.UNVERIFIED),
        BgSaftPartyId.of(INDIVIDUAL, null, "5009163205", null, "BG", "P4"));
  }

  // ─────────────── 14 ───────────────

  @Test
  @DisplayName("14: чужд без ДДС номер — ISO2 и вътрешният номер")
  void четиринайсет() {
    assertId(
        "14ROP0042", "ROP0042", none(), BgSaftPartyId.of(COMPANY, null, null, null, "RO", "P0042"));
    // В ЕС регистровият номер не прави 12 — 12 е само извън ЕС.
    assertId(
        "14ROP0042",
        "ROP0042",
        none(),
        BgSaftPartyId.of(COMPANY, null, "J40/1234/2020", null, "RO", "P0042"));
    assertId(
        "14USP0042",
        "USP0042",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, "3", null, null, "US", "P0042"));
  }

  // ─────────────── 15 ───────────────

  @Test
  @DisplayName("15: без регистрационен номер — вътрешният номер, с предупреждение")
  void петнайсет() {
    // Физическо лице без данни (QA с. 33).
    assertId(
        "15P0007",
        "P0007",
        EnumSet.of(Issue.INTERNAL),
        BgSaftPartyId.of(INDIVIDUAL, null, null, null, "BG", "P0007"));
    // Етажна собственост без ЕИК (QA с. 37) — юридическото лице без код.
    assertId(
        "15P0008",
        "P0008",
        EnumSet.of(Issue.INTERNAL),
        BgSaftPartyId.of(COMPANY, null, "  ", null, "BG", "P0008"));
    // ЕИК НЕ се извежда от ДДС номера (чл. 94, ал. 2 ЗДДС след 01.01.2026).
    assertId(
        "15P0009",
        "P0009",
        EnumSet.of(Issue.INTERNAL),
        BgSaftPartyId.of(COMPANY, null, null, "BG130175000", "BG", "P0009"));
    // Признак „3“ с български адрес.
    assertId(
        "15P0010",
        "P0010",
        EnumSet.of(Issue.INTERNAL),
        BgSaftPartyId.of(INDIVIDUAL, "3", null, null, "BG", "P0010"));
  }

  @Test
  @DisplayName("Без идентификатор и без вътрешен номер кодът е празен — находката е ГРЕШКА")
  void без_нищо() {
    assertId(
        "15",
        "",
        EnumSet.of(Issue.INTERNAL, Issue.MISSING),
        BgSaftPartyId.of(INDIVIDUAL, null, null, null, "BG", null));
    assertId(
        "14",
        "",
        EnumSet.of(Issue.MISSING),
        BgSaftPartyId.of(COMPANY, null, null, null, "RO", " "));
  }

  // ─────────────── 16 ───────────────

  @Test
  @DisplayName("16: служебният номер на НАП (307…) — по признака „1“ или когато не е ЕИК")
  void шестнайсет() {
    assertId(
        "163070000123",
        "3070000123",
        none(),
        BgSaftPartyId.of(INDIVIDUAL, "1", "3070000123", null, "BG", "P5"));
    assertId(
        "163070000123",
        "3070000123",
        none(),
        BgSaftPartyId.of(COMPANY, null, "3070000123", null, null, "P5"));
    // Чужд без ДДС номер в ЕС, но със служебен номер.
    assertId(
        "163070000123",
        "3070000123",
        none(),
        BgSaftPartyId.of(COMPANY, null, "3070000123", null, "UA", "P5"));
  }
}
