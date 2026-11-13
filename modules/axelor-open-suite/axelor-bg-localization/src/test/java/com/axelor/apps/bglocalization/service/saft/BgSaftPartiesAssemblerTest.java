package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.MappingRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.Key;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.PartyRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Customers и Suppliers с числа: членството по групата на НАП и по оборота в периода, салдата по
 * (контрагент, сметка на НАП), сливането по идентификатор и находките.
 *
 * <p>Сметкопланът: 4111 и 4112 под 411 (навиват се към 411), 414, 401, 503 (банка) и 422
 * (подотчетни лица — група 42, не е контрагент по MF.C/MF.S).
 */
class BgSaftPartiesAssemblerTest {

  private static final long A411 = 1;
  private static final long A4111 = 2;
  private static final long A4112 = 3;
  private static final long A414 = 4;
  private static final long A401 = 5;
  private static final long A503 = 6;
  private static final long A422 = 7;

  private final List<AccountRow> chart = new ArrayList<>();
  private final Map<String, MappingRow> mappings = new HashMap<>();
  private final Map<Long, PartyRow> partners = new HashMap<>();
  private final Map<Key, BgSaftBalance> balances = new LinkedHashMap<>();

  @BeforeEach
  void сметкопланът() {
    chart.add(new AccountRow(A411, "411", "Клиенти", "1", null));
    chart.add(new AccountRow(A4111, "4111", "Клиенти в страната", "1", A411));
    chart.add(new AccountRow(A4112, "4112", "Клиенти в чужбина", "1", A411));
    chart.add(new AccountRow(A414, "414", "Клиенти по продажби при условия", "1", null));
    chart.add(new AccountRow(A401, "401", "Доставчици", "2", null));
    chart.add(new AccountRow(A503, "503", "Разплащателна сметка", "1", null));
    chart.add(new AccountRow(A422, "422", "Подотчетни лица", "3", null));
    for (String code : List.of("411", "414", "401", "503", "422")) {
      mappings.put(code, new MappingRow(code, false));
    }
  }

  private PartyRow partner(long id) {
    PartyRow row = new PartyRow(id).partnerSeq("P" + id).partnerTypeSelect(1).country("BG");
    partners.put(id, row);
    return row;
  }

  private void balance(long account, Long partner, String opening, String debit, String credit) {
    balances.merge(
        new Key(account, partner),
        new BgSaftBalance(new BigDecimal(opening), new BigDecimal(debit), new BigDecimal(credit)),
        BgSaftBalance::plus);
  }

  private Result assemble() {
    return BgSaftPartiesAssembler.assemble(chart, mappings, partners, balances);
  }

  private static SaftParty one(List<SaftParty> parties, String partyId, String accountId) {
    List<SaftParty> found =
        parties.stream()
            .filter(p -> p.getPartyId().equals(partyId) && p.getAccountId().equals(accountId))
            .collect(Collectors.toList());
    assertEquals(1, found.size(), partyId + " / " + accountId);
    return found.get(0);
  }

  private static Optional<BgDeclarationFileFinding> finding(Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code);
  }

  private static List<String> codes(Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  private static void assertBalances(String opening, String closing, SaftParty party) {
    assertEquals(new BigDecimal(opening), party.getOpeningBalance(), "начално");
    assertEquals(new BigDecimal(closing), party.getClosingBalance(), "крайно");
  }

  /** Сценарият от описанието на класа: шест партньора, три от които не влизат. */
  private void месецът() {
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София").street("Витоша 1");
    partner(20).name("Beta GmbH").taxNbr("DE123456789").country("DE").city("Berlin");
    partner(30)
        .partnerTypeSelect(2)
        .firstName("Иван")
        .middleName("Петров")
        .name("Иванов")
        .city("Пловдив");
    partner(40).name("Само начално салдо ЕООД").registrationCode("812114069").city("Варна");
    partner(50).name("Само банка АД").registrationCode("202048483").city("Русе");
    partner(60).partnerTypeSelect(2).firstName("Подотчетно").name("Лице").city("София");

    // Алфа: клиент по 4111 и 4112 (→ 411), по 414 само с начално салдо, и доставчик по 401.
    balance(A4111, 10L, "100", "50", "30");
    balance(A4112, 10L, "0", "20", "0");
    balance(A414, 10L, "300", "0", "0");
    balance(A401, 10L, "-80", "80", "200");
    // Beta: клиент по 4111.
    balance(A4111, 20L, "0", "120", "0");
    // Иван Петров Иванов: доставчик без ЕГН.
    balance(A401, 30L, "0", "0", "45");
    // Не влизат: само начално салдо (няма оборот в месеца), само банка, само подотчетни.
    balance(A4111, 40L, "500", "0", "0");
    balance(A503, 50L, "0", "10", "0");
    balance(A422, 60L, "0", "15", "0");
    // Ред без контрагент по 4111.
    balance(A4111, null, "0", "7", "0");
  }

  @Test
  @DisplayName("Клиенти: група 41, по една структура на (контрагент, сметка на НАП), със салдата")
  void клиентите() {
    месецът();
    Result result = assemble();

    List<SaftParty> customers = result.getCustomers();
    assertEquals(
        List.of("411/10130175000", "411/11DE123456789", "414/10130175000"),
        customers.stream()
            .map(p -> p.getAccountId() + "/" + p.getPartyId())
            .collect(Collectors.toList()));
    // 4111 и 4112 се навиват към 411 в ЕДНА структура: 100 + (50 + 20) − 30 = 140.
    SaftParty alpha411 = one(customers, "10130175000", "411");
    assertBalances("100.00", "140.00", alpha411);
    assertEquals("130175000", alpha411.getRegistrationNumber());
    assertEquals("Алфа ООД", alpha411.getName());
    assertEquals("N", alpha411.getRelatedParty());
    assertEquals("София", alpha411.getAddresses().get(0).getCity());
    assertEquals("Витоша 1", alpha411.getAddresses().get(0).getStreetName());
    assertEquals("BG", alpha411.getAddresses().get(0).getCountry());
    // 414 няма оборот в месеца, но Алфа е клиент на месеца — сметката излиза със салдото си.
    assertBalances("300.00", "300.00", one(customers, "10130175000", "414"));
    SaftParty beta = one(customers, "11DE123456789", "411");
    assertBalances("0.00", "120.00", beta);
    assertEquals("DE123456789", beta.getRegistrationNumber());
    assertEquals("DE", beta.getAddresses().get(0).getCountry());
  }

  @Test
  @DisplayName(
      "Доставчици: група 40, кредитното салдо е със знак минус; клиент-доставчик е в двете")
  void доставчиците() {
    месецът();
    Result result = assemble();

    List<SaftParty> suppliers = result.getSuppliers();
    assertEquals(2, suppliers.size());
    // Алфа и като доставчик — без компенсиране с вземането по 411 (QA с. 36).
    assertBalances("-80.00", "-200.00", one(suppliers, "10130175000", "401"));
    SaftParty ivan = one(suppliers, "15P30", "401");
    assertBalances("0.00", "-45.00", ivan);
    assertEquals("Иван Петров Иванов", ivan.getName());
    assertEquals("P30", ivan.getRegistrationNumber());
  }

  @Test
  @DisplayName("Членството: без оборот в периода, само по банката и само по 422 — НЕ влизат")
  void кой_не_влиза() {
    месецът();
    Result result = assemble();

    List<String> ids = new ArrayList<>();
    result.getCustomers().forEach(p -> ids.add(p.getPartyId()));
    result.getSuppliers().forEach(p -> ids.add(p.getPartyId()));
    assertFalse(ids.contains("10812114069"), "само начално салдо, без оборот в месеца");
    assertFalse(ids.contains("10202048483"), "оборот само по 503 — не е клиент или доставчик");
    assertFalse(ids.contains("15P60"), "422 подотчетни лица не са контрагенти");
    assertEquals(5, ids.size());
  }

  @Test
  @DisplayName(
      "Находките на сценария: вътрешен номер, редове без контрагент, RelatedParty — нула грешки")
  void находките_на_месеца() {
    месецът();
    Result result = assemble();

    assertEquals(
        List.of(
            "SAFT_PARTY_INTERNAL_ID",
            "SAFT_PARTY_LINES_WITHOUT_PARTNER",
            "SAFT_PARTY_RELATED_PARTY_DEFAULTED"),
        codes(result));
    assertTrue(result.getFindings().stream().noneMatch(BgDeclarationFileFinding::isBlocking));
    assertTrue(
        finding(result, "SAFT_PARTY_INTERNAL_ID")
            .get()
            .getMessage()
            .contains("Иван Петров Иванов (P30) → 15P30"));
    assertTrue(
        finding(result, "SAFT_PARTY_LINES_WITHOUT_PARTNER")
            .get()
            .getMessage()
            .contains("411 (начално 0.00, дебит 7.00, кредит 0.00)"));
  }

  @Test
  @DisplayName(
      "Няма клиенти и доставчици — нулева структура с „0“ и AccountID 998 и предупреждение"
          + " (решение 3 от 14.10.2026; да се потвърди с пробния файл)")
  void празно() {
    partner(10).name("Само банка АД").registrationCode("130175000").city("София");
    balance(A503, 10L, "0", "10", "0");
    Result result = assemble();

    for (List<SaftParty> table : List.of(result.getCustomers(), result.getSuppliers())) {
      assertEquals(1, table.size());
      SaftParty zero = table.get(0);
      assertEquals("0", zero.getPartyId());
      assertEquals("0", zero.getRegistrationNumber());
      assertEquals("998", zero.getAccountId());
      assertEquals(new BigDecimal("0.00"), zero.getOpeningBalance());
      assertEquals(new BigDecimal("0.00"), zero.getClosingBalance());
      assertEquals("BG", zero.getAddresses().get(0).getCountry());
    }
    assertEquals(List.of("SAFT_CUSTOMERS_EMPTY", "SAFT_SUPPLIERS_EMPTY"), codes(result));
    assertFalse(finding(result, "SAFT_CUSTOMERS_EMPTY").get().isBlocking());
    assertFalse(finding(result, "SAFT_SUPPLIERS_EMPTY").get().isBlocking());
    assertTrue(finding(result, "SAFT_CUSTOMERS_EMPTY").get().getMessage().contains("пробния файл"));
  }

  @Test
  @DisplayName(
      "Членство по референция (решение 2): документ без оборот по 40/41 — контрагентът влиза със"
          + " сметката си от групата и салдото ѝ")
  void по_референция_със_сметка_от_групата() {
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София");
    // Само начално салдо по 4111 — без оборот, тоест по G2b НЕ би влязъл.
    balance(A4111, 10L, "100", "0", "0");
    Result base = assemble();
    assertEquals(List.of("0"), ids(base.getCustomers()));

    Result result =
        BgSaftPartiesAssembler.assemble(
            chart,
            mappings,
            partners,
            balances,
            List.of(
                new BgSaftPartiesAssembler.Reference(
                    10L, BgSaftPartiesAssembler.Side.CUSTOMER, A4111, "INV-1")));
    SaftParty alpha = one(result.getCustomers(), "10130175000", "411");
    assertBalances("100.00", "100.00", alpha);
    assertFalse(codes(result).contains("SAFT_CUSTOMERS_EMPTY"));
  }

  @Test
  @DisplayName(
      "Членство по референция без сметка от групата — разчетната сметка на документа (QA с. 37,"
          + " MF.S.5); без код по НАП — ГРЕШКА с указател към партньора")
  void по_референция_без_сметка_от_групата() {
    chart.add(new AccountRow(20L, "499", "Други кредитори", "2", null));
    mappings.put("499", new MappingRow("499", false));
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София");
    partner(11).name("Бета ООД").registrationCode("831919536").city("Варна");
    balance(20L, 10L, "0", "0", "50");
    Result result =
        BgSaftPartiesAssembler.assemble(
            chart,
            mappings,
            partners,
            balances,
            List.of(
                new BgSaftPartiesAssembler.Reference(
                    10L, BgSaftPartiesAssembler.Side.SUPPLIER, 20L, "ПР-1"),
                new BgSaftPartiesAssembler.Reference(
                    11L, BgSaftPartiesAssembler.Side.SUPPLIER, null, "ПР-2")));
    SaftParty alpha = one(result.getSuppliers(), "10130175000", "499");
    assertBalances("0.00", "-50.00", alpha);
    BgDeclarationFileFinding noAccount =
        result.getFindings().stream()
            .filter(f -> f.getCode().equals("SAFT_PARTY_REFERENCE_NO_ACCOUNT"))
            .findFirst()
            .get();
    assertTrue(noAccount.isBlocking());
    assertTrue(noAccount.getMessage().endsWith("Бета ООД (P11) (ПР-2)"), noAccount.getMessage());
    assertEquals(BgSaftRef.PARTNER, noAccount.getTargetModel());
    assertEquals(11L, noAccount.getTargetId());
  }

  private static List<String> ids(List<SaftParty> parties) {
    return parties.stream().map(SaftParty::getPartyId).collect(Collectors.toList());
  }

  @Test
  @DisplayName("Два партньора с един ЕИК са един контрагент: една структура със сбора")
  void сливане() {
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София");
    partner(11).name("Алфа ООД (дубликат)").registrationCode("BG130175000").city("София");
    balance(A4111, 10L, "10", "5", "0");
    balance(A4111, 11L, "0", "0", "3");
    balance(A401, 10L, "0", "0", "1");
    Result result = assemble();

    assertEquals(1, result.getCustomers().size());
    SaftParty alpha = result.getCustomers().get(0);
    assertBalances("10.00", "12.00", alpha);
    assertEquals("Алфа ООД", alpha.getName(), "името е на партньора с по-малкото id");
    assertTrue(
        finding(result, "SAFT_PARTY_MERGED")
            .get()
            .getMessage()
            .contains("10130175000 ← Алфа ООД (P10), Алфа ООД (дубликат) (P11)"));
    assertTrue(finding(result, "SAFT_PARTY_BG_PREFIX_REMOVED").isPresent());
  }

  @Test
  @DisplayName("Без идентификатор и без вътрешен номер: RegistrationNumber празен и ГРЕШКА")
  void без_идентификатор() {
    partner(10).partnerSeq(null).name("Никой").city("София");
    partner(11).partnerSeq(" ").name("Също никой").city("София");
    balance(A4111, 10L, "0", "5", "0");
    balance(A4111, 11L, "0", "6", "0");
    balance(A401, 12L, "0", "0", "1");
    partner(12).name("Доставчик").registrationCode("130175000").city("София");
    Result result = assemble();

    // Двата без код НЕ се сливат помежду си по общото „15“.
    assertEquals(2, result.getCustomers().size());
    result.getCustomers().forEach(p -> assertEquals("", p.getRegistrationNumber()));
    BgDeclarationFileFinding missing = finding(result, "SAFT_PARTY_ID_MISSING").get();
    assertTrue(missing.isBlocking());
    assertTrue(missing.getMessage().contains("Никой (#10)"), missing.getMessage());
    assertFalse(finding(result, "SAFT_PARTY_INTERNAL_ID").isPresent(), "грешката стига");
  }

  @Test
  @DisplayName("Без държава: празен Country и ГРЕШКА; без град — празен и предупреждение")
  void адресът() {
    partner(10).name("Без адрес ООД").registrationCode("130175000").country(null);
    partner(11).name("Без град ООД").registrationCode("812114069");
    balance(A4111, 10L, "0", "5", "0");
    balance(A401, 11L, "0", "0", "5");
    Result result = assemble();

    assertEquals("", result.getCustomers().get(0).getAddresses().get(0).getCountry());
    assertTrue(finding(result, "SAFT_PARTY_COUNTRY_MISSING").get().isBlocking());
    assertEquals("", result.getSuppliers().get(0).getAddresses().get(0).getCity());
    BgDeclarationFileFinding city = finding(result, "SAFT_PARTY_CITY_MISSING").get();
    assertFalse(city.isBlocking());
    assertTrue(city.getMessage().contains("Без град ООД (P11)"));
    assertFalse(city.getMessage().contains("Без адрес"), "без държава вече е грешка");
  }

  @Test
  @DisplayName("ЕИК с грешна контролна цифра е ГРЕШКА")
  void невалиден_еик() {
    partner(10).name("Грешен ЕИК ООД").registrationCode("130175001").city("София");
    balance(A4111, 10L, "0", "5", "0");
    Result result = assemble();

    assertEquals("10130175001", result.getCustomers().get(0).getPartyId());
    assertTrue(finding(result, "SAFT_PARTY_EIK_INVALID").get().isBlocking());
  }

  @Test
  @DisplayName("ЕГН с грешна контролна цифра е ГРЕШКА, не предупреждение")
  void невалидно_егн() {
    partner(10)
        .name("Иван Иванов")
        .partnerTypeSelect(2)
        .identifierType("0")
        .registrationCode("5009163204")
        .city("София");
    balance(A4111, 10L, "0", "5", "0");
    Result result = assemble();

    assertEquals("135009163204", result.getCustomers().get(0).getPartyId());
    assertTrue(finding(result, "SAFT_PARTY_PERSON_ID_INVALID").get().isBlocking());
    assertFalse(finding(result, "SAFT_PARTY_ID_UNVERIFIED").isPresent(), "вече не е само проверка");
  }

  @Test
  @DisplayName("Трети знак: HALF_UP до два и предупреждение; крайното е от закръглените")
  void закръгляне() {
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София");
    balance(A4111, 10L, "0.005", "10.004", "0");
    Result result = assemble();

    assertBalances("0.01", "10.01", result.getCustomers().get(0));
    assertTrue(
        finding(result, "SAFT_PARTY_ROUNDED").get().getMessage().contains("Алфа ООД (P10) / 411"));
  }

  @Test
  @DisplayName("Името над 70 знака се съкращава до 70 — схемата отказва по-дългото")
  void дълго_име() {
    String name = "А".repeat(80);
    partner(10).name(name).registrationCode("130175000").city("София");
    balance(A4111, 10L, "0", "5", "0");
    Result result = assemble();

    assertEquals("А".repeat(70), result.getCustomers().get(0).getName());
    assertTrue(finding(result, "SAFT_PARTY_TRUNCATED").isPresent());
  }

  @Test
  @DisplayName("Изключена сметка от група 41 не прави клиент; 498/499 не са клиенти засега")
  void изключени() {
    chart.add(new AccountRow(8L, "4119", "Изключена", "1", null));
    mappings.put("4119", new MappingRow(null, true));
    chart.add(new AccountRow(9L, "498", "Други дебитори", "1", null));
    mappings.put("498", new MappingRow("498", false));
    partner(10).name("Алфа ООД").registrationCode("130175000").city("София");
    balance(8L, 10L, "0", "5", "0");
    balance(9L, 10L, "0", "5", "0");
    Result result = assemble();

    // Само нулевата структура — нито 4119 (изключена), нито 498 правят клиент по оборот.
    assertEquals(List.of("0"), ids(result.getCustomers()));
    assertTrue(
        BgSaftPartiesAssembler.partyAccounts(chart, mappings).keySet().stream()
            .noneMatch(id -> id == 8L || id == 9L));
    assertEquals(BgSaftPartiesAssembler.Side.CUSTOMER, BgSaftPartiesAssembler.sideOf("419"));
    assertEquals(BgSaftPartiesAssembler.Side.SUPPLIER, BgSaftPartiesAssembler.sideOf("409"));
    assertNull(BgSaftPartiesAssembler.sideOf("421"));
    assertNull(BgSaftPartiesAssembler.sideOf("498"));
    assertNull(BgSaftPartiesAssembler.sideOf("499"));
  }
}
