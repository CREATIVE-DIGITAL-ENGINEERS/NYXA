package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.MappingRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Customers (MF.C.*) И Suppliers (MF.S.*) ОТ САЛДАТА ПО (СМЕТКА, КОНТРАГЕНТ) — чиста функция.
 *
 * <p><b>Нула заявки.</b> Сметкопланът, мапингът, партньорите и сумите от {@link BgSaftMoveScope} му
 * се подават, тоест всяко правило тук се проверява с числа и без Postgres.
 *
 * <p><b>⚠️ КОЙ ВЛИЗА — ОБОРОТ ПО 40/41 ИЛИ РЕФЕРЕНЦИЯ.</b> Клиент е контрагент с оборот в периода
 * по сметка, чийто AccountID е от група 41 на сметкоплана на НАП; доставчик — от група 40. И всеки
 * контрагент, реферирaн от подаден документ (фактура, протокол — {@link Reference}), дори без
 * оборот: QA с. 34 и 36 (MF.C.9) — „ако в други секции на файла … са подадени данни за
 * клиент/доставчик, същите данни следва да се подадат и в секция MasterFiles“ (членство по
 * референция, решение 2 от 14.10.2026; заменя G2b). Рефериран контрагент излиза със сметките си от
 * групата; без такава — с разчетната сметка на документа (QA с. 37, MF.S.5). {@code technicalType}
 * на Axelor е твърде широк (422 подотчетни, 461 осигуровки, 651 са receivable/payable), а {@code
 * useForPartnerBalance} — твърде тесен (402 е false). 498/499 (други дебитори и кредитори) НЕ
 * влизат по оборот — да се потвърди; добавят се в {@link #EXTRA_ACCOUNTS}.
 *
 * <p><b>⚠️ ЕДНА СТРУКТУРА НА (КОНТРАГЕНТ, СМЕТКА НА НАП)</b> (MF.C.5, QA с. 34): 411 и 414 на един
 * клиент са две структури, а 4111 и 4114, навити към 411, са една — със сбора. Клиент, който е и
 * доставчик, е и в двете таблици, без компенсиране (QA с. 36). Включеният контрагент излиза с всяка
 * своя сметка от групата, която има салдо или оборот — и с тази без оборот в месеца.
 *
 * <p><b>⚠️ КОНТРАГЕНТЪТ Е ИДЕНТИФИКАТОРЪТ, НЕ ЗАПИСЪТ В AXELOR.</b> Два партньора с един ЕИК са
 * едно лице за НАП: сливат се в една структура с предупреждение — иначе файлът носи два Customer с
 * еднакви CustomerID и AccountID. Името и адресът са на партньора с най-малкото id.
 *
 * <p><b>⚠️ ЛИПСАТА ИЗЛИЗА ПРАЗНА, НЕ ИЗМИСЛЕНА.</b> Име и населено място — празни, с
 * предупреждение. Държавата — празна, с ГРЕШКА: ISO кодът е точно два знака и празният чупи
 * схемата. По-дългото от допустимото в схемата се съкращава, с предупреждение.
 *
 * <p><b>⚠️ RelatedParty (S.C.7) = „N“ ЗА ВСИЧКИ</b>, с едно предупреждение за файла: признакът за
 * свързано лице по §1, т. 3 ДР ДОПК още не се води на партньора (G6).
 *
 * <p><b>⚠️ TaxRegistration (S.C.5) НЕ СЕ ПОДАВА.</b> По книгата е задължителна само когато един
 * контрагент има ДДС номера в няколко държави членки, а партньорът носи един.
 *
 * <p><b>⚠️ ПРАЗНА ТАБЛИЦА — НУЛЕВА СТРУКТУРА С „0“</b> (решение 3 от 14.10.2026). Схемата иска поне
 * един Customer и поне един Supplier, а QA IV.2.6 (с. 97–100) дава образец само за секциите с
 * NumberOfEntries. Структурата е по аналогия с CustomerInfo на нулевата фактура: ID и
 * регистрационен номер „0“, име „ “, държава BG, AccountID 998, салда 0.00 — с предупреждение; дали
 * НАП я приема, потвърждава пробният файл.
 */
public final class BgSaftPartiesAssembler {

  /** S.C.7 — „не е свързано лице“. */
  public static final String RELATED_PARTY_NO = "N";

  /** Не задължителен по схемата, но един и същ с адреса в Header-а. */
  static final String ADDRESS_TYPE = "StreetAddress";

  /** Най-дългото, което схемата допуска: SAFmiddle2textType, SAFmiddle1textType, SAFshorttext. */
  private static final int MIDDLE2 = 70;

  private static final int MIDDLE1 = 35;
  private static final int SHORT = 18;

  private static final int SCALE = 2;

  /** {@code PartnerRepository.PARTNER_TYPE_INDIVIDUAL}. */
  private static final int INDIVIDUAL = 2;

  /** Таблицата и групата на НАП, по която контрагентът влиза в нея. */
  public enum Side {
    CUSTOMER("41", "Customers", "клиент"),
    SUPPLIER("40", "Suppliers", "доставчик");

    private final String group;
    private final String table;
    private final String noun;

    Side(String group, String table, String noun) {
      this.group = group;
      this.table = table;
      this.noun = noun;
    }
  }

  /**
   * Сметки на НАП извън групи 40 и 41, които също са на клиент или доставчик. Празно: 498 „Други
   * дебитори“ и 499 „Други кредитори“ чакат потвърждение.
   */
  static final Map<String, Side> EXTRA_ACCOUNTS = Collections.emptyMap();

  private BgSaftPartiesAssembler() {}

  /** Ключът на сумите: сметка и контрагент; {@code partnerId} е {@code null} за ред без такъв. */
  public static final class Key {
    private final Long accountId;
    private final Long partnerId;

    public Key(Long accountId, Long partnerId) {
      this.accountId = accountId;
      this.partnerId = partnerId;
    }

    public Long getAccountId() {
      return accountId;
    }

    /** {@code null} за ред без контрагент. */
    public Long getPartnerId() {
      return partnerId;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key)) {
        return false;
      }
      Key other = (Key) o;
      return Objects.equals(accountId, other.accountId)
          && Objects.equals(partnerId, other.partnerId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(accountId, partnerId);
    }

    @Override
    public String toString() {
      return accountId + "/" + partnerId;
    }
  }

  /**
   * Контрагент, рефериран от подаден документ — членът на Customers/Suppliers по референция.
   *
   * <p>{@code accountId} е разчетната сметка на документа (наша): ползва се само когато
   * контрагентът няма сметка от групата със салдо или оборот.
   */
  public static final class Reference {
    private final Long partnerId;
    private final Side side;
    private final Long accountId;
    private final String document;

    public Reference(Long partnerId, Side side, Long accountId, String document) {
      this.partnerId = partnerId;
      this.side = side;
      this.accountId = accountId;
      this.document = document;
    }

    // Имената са тези на полетата, не getX(): ръчна стойност, в която null значи „няма“ (сметка
    // без разчетна), а не генериран getter със стойност по подразбиране (BgDefaultedGetterTest).
    public Long partnerId() {
      return partnerId;
    }

    public Side side() {
      return side;
    }

    /** Разчетната сметка на документа; {@code null}, когато документът няма такава. */
    public Long accountId() {
      return accountId;
    }
  }

  /** Партньорът, както го чете {@link BgSaftLedgerReader#partners}. */
  public static final class PartyRow {
    private final Long id;
    private String partnerSeq;
    private Integer partnerTypeSelect;
    private String name;
    private String firstName;
    private String middleName;
    private String registrationCode;
    private String taxNbr;
    private String identifierType;
    private String street;
    private String number;
    private String city;
    private String postalCode;
    private String country;

    public PartyRow(Long id) {
      this.id = id;
    }

    public PartyRow partnerSeq(String value) {
      this.partnerSeq = value;
      return this;
    }

    /** 1 юридическо, 2 физическо лице. */
    public PartyRow partnerTypeSelect(Integer value) {
      this.partnerTypeSelect = value;
      return this;
    }

    /** Фирмата или фамилията на физическото лице. */
    public PartyRow name(String value) {
      this.name = value;
      return this;
    }

    public PartyRow firstName(String value) {
      this.firstName = value;
      return this;
    }

    public PartyRow middleName(String value) {
      this.middleName = value;
      return this;
    }

    public PartyRow registrationCode(String value) {
      this.registrationCode = value;
      return this;
    }

    public PartyRow taxNbr(String value) {
      this.taxNbr = value;
      return this;
    }

    /** {@code bgIdentifierType}. */
    public PartyRow identifierType(String value) {
      this.identifierType = value;
      return this;
    }

    public PartyRow street(String value) {
      this.street = value;
      return this;
    }

    public PartyRow number(String value) {
      this.number = value;
      return this;
    }

    public PartyRow city(String value) {
      this.city = value;
      return this;
    }

    public PartyRow postalCode(String value) {
      this.postalCode = value;
      return this;
    }

    /** ISO2 от основния адрес. */
    public PartyRow country(String value) {
      this.country = value;
      return this;
    }
  }

  /** Двете таблици и находките по пътя. */
  public static final class Result {
    private final List<SaftParty> customers;
    private final List<SaftParty> suppliers;
    private final List<BgDeclarationFileFinding> findings;

    private Result(
        List<SaftParty> customers,
        List<SaftParty> suppliers,
        List<BgDeclarationFileFinding> findings) {
      this.customers = Collections.unmodifiableList(customers);
      this.suppliers = Collections.unmodifiableList(suppliers);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftParty> getCustomers() {
      return customers;
    }

    public List<SaftParty> getSuppliers() {
      return suppliers;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /** Таблицата за AccountID по НАП; {@code null}, когато сметката не е на клиент или доставчик. */
  static Side sideOf(String nraCode) {
    if (nraCode == null) {
      return null;
    }
    Side extra = EXTRA_ACCOUNTS.get(nraCode);
    if (extra != null) {
      return extra;
    }
    for (Side side : Side.values()) {
      if (nraCode.length() >= 3 && nraCode.startsWith(side.group)) {
        return side;
      }
    }
    return null;
  }

  /**
   * Сметките на клиенти и доставчици — id → AccountID по НАП. Читателят пита базата само за тях;
   * {@link #assemble} проверява групата отново, тоест членството не зависи от заявката.
   */
  public static Map<Long, String> partyAccounts(
      Collection<AccountRow> chart, Map<String, MappingRow> mappings) {
    Map<Long, String> party = new HashMap<>();
    BgSaftGlAccountsAssembler.nraAccountIds(chart, mappings)
        .forEach(
            (id, code) -> {
              if (sideOf(code) != null) {
                party.put(id, code);
              }
            });
    return party;
  }

  /**
   * @param chart сметкопланът на дружеството
   * @param mappings {@code BgSafAccountMapping} по код на сметката
   * @param partners партньорите по id
   * @param balances суровите суми по (сметка, контрагент)
   */
  public static Result assemble(
      Collection<AccountRow> chart,
      Map<String, MappingRow> mappings,
      Map<Long, PartyRow> partners,
      Map<Key, BgSaftBalance> balances) {
    return assemble(chart, mappings, partners, balances, Collections.emptyList());
  }

  /**
   * Същото, с контрагентите, реферирани от подадените документи.
   *
   * @param balances суровите суми по (сметка, контрагент) — и по разчетните сметки на референциите
   * @param references контрагентите на фактурите и протоколите ({@link
   *     BgSaftInvoicesAssembler.Result#getReferences()})
   */
  public static Result assemble(
      Collection<AccountRow> chart,
      Map<String, MappingRow> mappings,
      Map<Long, PartyRow> partners,
      Map<Key, BgSaftBalance> balances,
      Collection<Reference> references) {
    Map<Long, String> accounts = partyAccounts(chart, mappings);
    Map<Long, BgSaftPartyId> ids = new HashMap<>();
    Map<String, Slot> slots = new LinkedHashMap<>();
    Map<String, BgSaftBalance> withoutPartner = new TreeMap<>();
    Set<Long> notFound = new TreeSet<>();

    for (Map.Entry<Key, BgSaftBalance> entry : balances.entrySet()) {
      String nraCode = accounts.get(entry.getKey().accountId);
      if (nraCode == null) {
        continue; // сметката не е от група 40/41 — контрагентът не влиза по нея
      }
      Long partnerId = entry.getKey().partnerId;
      if (partnerId == null) {
        withoutPartner.merge(nraCode, entry.getValue(), BgSaftBalance::plus);
        continue;
      }
      PartyRow row = partners.get(partnerId);
      if (row == null) {
        notFound.add(partnerId);
        continue;
      }
      BgSaftPartyId id = ids.computeIfAbsent(partnerId, k -> identify(row));
      // Без код идентификаторът е само типът — такива партньори не се сливат помежду си.
      String party =
          id.getIssues().contains(BgSaftPartyId.Issue.MISSING) ? "#" + partnerId : id.getPartyId();
      Side side = sideOf(nraCode);
      slots
          .computeIfAbsent(side + "|" + party + "|" + nraCode, k -> new Slot(side, party, nraCode))
          .add(partnerId, entry.getValue());
    }

    // Членството: оборот в периода по поне една сметка от групата — или референция от документ.
    Set<String> members = new HashSet<>();
    for (Slot slot : slots.values()) {
      if (slot.movement()) {
        members.add(slot.member());
      }
    }
    Findings found = new Findings();
    Map<Long, String> anyAccount = BgSaftGlAccountsAssembler.nraAccountIds(chart, mappings);
    for (Reference reference : references) {
      PartyRow row = reference.partnerId == null ? null : partners.get(reference.partnerId);
      if (row == null) {
        continue; // документът вече е ГРЕШКА „без контрагент“
      }
      BgSaftPartyId id = ids.computeIfAbsent(reference.partnerId, k -> identify(row));
      String party =
          id.getIssues().contains(BgSaftPartyId.Issue.MISSING)
              ? "#" + reference.partnerId
              : id.getPartyId();
      String member = reference.side + "|" + party;
      members.add(member);
      boolean hasSlot = slots.values().stream().anyMatch(slot -> slot.member().equals(member));
      if (hasSlot) {
        continue;
      }
      // Без сметка от групата — разчетната сметка на документа (QA с. 37, MF.S.5).
      String nraCode = reference.accountId == null ? null : anyAccount.get(reference.accountId);
      if (nraCode == null) {
        found.referenceNoAccount.add(
            BgSaftRef.partner(reference.partnerId, label(row) + " (" + reference.document + ")"));
        nraCode = "";
      }
      BgSaftBalance raw = balances.get(new Key(reference.accountId, reference.partnerId));
      Slot slot = new Slot(reference.side, party, nraCode);
      slot.add(reference.partnerId, raw == null ? new BgSaftBalance(null, null, null) : raw);
      slot.referenced = true;
      slots.put(reference.side + "|" + party + "|" + nraCode, slot);
    }

    List<SaftParty> customers = new ArrayList<>();
    List<SaftParty> suppliers = new ArrayList<>();
    for (Slot slot : slots.values()) {
      if (!members.contains(slot.member())) {
        continue;
      }
      BigDecimal opening = round(slot.raw.opening());
      BigDecimal debit = round(slot.raw.debit());
      BigDecimal credit = round(slot.raw.credit());
      BigDecimal closing = opening.add(debit).subtract(credit);
      if (!slot.movement() && !slot.referenced && opening.signum() == 0 && closing.signum() == 0) {
        continue;
      }
      PartyRow row = partners.get(slot.partnerIds.iterator().next());
      String label = label(row);
      for (Long partnerId : slot.partnerIds) {
        found.identity(partnerId, label(partners.get(partnerId)), ids.get(partnerId));
      }
      if (slot.partnerIds.size() > 1) {
        List<String> labels = new ArrayList<>();
        slot.partnerIds.forEach(partnerId -> labels.add(label(partners.get(partnerId))));
        found.merged.add(BgSaftRef.partner(row.id, slot.party + " ← " + String.join(", ", labels)));
      }
      if (changed(slot.raw.opening(), opening)
          || changed(slot.raw.debit(), debit)
          || changed(slot.raw.credit(), credit)) {
        found.rounded.add(BgSaftRef.partner(row.id, label + " / " + slot.nraCode));
      }
      SaftParty party =
          party(row, ids.get(row.id), label, found)
              .accountId(slot.nraCode)
              .openingBalance(opening)
              .closingBalance(closing);
      (slot.side == Side.CUSTOMER ? customers : suppliers).add(party);
    }
    Comparator<SaftParty> order =
        Comparator.comparing(SaftParty::getAccountId)
            .thenComparing(SaftParty::getPartyId)
            .thenComparing(SaftParty::getName);
    customers.sort(order);
    suppliers.sort(order);
    boolean noCustomers = customers.isEmpty();
    boolean noSuppliers = suppliers.isEmpty();
    if (noCustomers) {
      customers.add(zeroParty());
    }
    if (noSuppliers) {
      suppliers.add(zeroParty());
    }

    withoutPartner.forEach(
        (nraCode, raw) -> {
          BigDecimal opening = round(raw.opening());
          BigDecimal debit = round(raw.debit());
          BigDecimal credit = round(raw.credit());
          if (opening.signum() != 0 || debit.signum() != 0 || credit.signum() != 0) {
            found.withoutPartner.add(
                BgSaftRef.text(
                    nraCode
                        + " (начално "
                        + opening.toPlainString()
                        + ", дебит "
                        + debit.toPlainString()
                        + ", кредит "
                        + credit.toPlainString()
                        + ")"));
          }
        });
    notFound.forEach(
        partnerId -> found.notFound.add(BgSaftRef.partner(partnerId, "#" + partnerId)));

    return new Result(
        customers,
        suppliers,
        found.toFindings(
            noCustomers,
            noSuppliers,
            customers.size() + suppliers.size() - (noCustomers ? 1 : 0) - (noSuppliers ? 1 : 0)));
  }

  /** Нулевата структура на Customer/Supplier — по аналогия с CustomerInfo на нулевата фактура. */
  static SaftParty zeroParty() {
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    return new SaftParty()
        .registrationNumber(BgSaftGlEntriesAssembler.NO_PARTY)
        .name(" ")
        .addAddress(
            new SaftAddress()
                .city("")
                .country(BgSaftTaxTableAssembler.COUNTRY)
                .addressType(ADDRESS_TYPE))
        .relatedParty(RELATED_PARTY_NO)
        .partyId(BgSaftGlEntriesAssembler.NO_PARTY)
        .accountId(BgSaftInvoicesAssembler.EMPTY_ACCOUNT)
        .openingBalance(zero)
        .closingBalance(zero);
  }

  /** Идентификаторът от полетата на партньора. */
  static BgSaftPartyId identify(PartyRow row) {
    return BgSaftPartyId.of(
        row.partnerTypeSelect,
        row.identifierType,
        row.registrationCode,
        row.taxNbr,
        row.country,
        row.partnerSeq);
  }

  /**
   * Контрагентът на документ в SourceDocuments (S.I.2/S.I.3): идентификатор, име и адрес по същите
   * правила като структурата му в Customers/Suppliers. Находките за полетата му вече са дадени от
   * таблиците — тук не се повтарят.
   */
  static SaftParty describe(PartyRow row) {
    return party(row, identify(row), label(row), new Findings());
  }

  /** Партньор с ДДС номер — регистриран по ЗДДС (или в друга държава членка). */
  static boolean hasVatNumber(PartyRow row) {
    return trim(row.taxNbr) != null;
  }

  /** Името: физическото лице — собствено, бащино и фамилия; юридическото — фирмата. */
  static String displayName(PartyRow row) {
    if (row.partnerTypeSelect != null && row.partnerTypeSelect == INDIVIDUAL) {
      List<String> parts = new ArrayList<>();
      for (String part : new String[] {row.firstName, row.middleName, row.name}) {
        if (trim(part) != null) {
          parts.add(part.trim());
        }
      }
      return String.join(" ", parts);
    }
    String name = trim(row.name);
    return name == null ? "" : name;
  }

  // ─────────────── една структура ───────────────

  private static SaftParty party(PartyRow row, BgSaftPartyId id, String label, Findings found) {
    String name = displayName(row);
    if (name.isEmpty()) {
      found.nameMissing.add(BgSaftRef.partner(row.id, label));
    }
    String city = trim(row.city);
    String country = trim(row.country);
    if (country == null) {
      found.countryMissing.add(BgSaftRef.partner(row.id, label));
    } else if (city == null) {
      found.cityMissing.add(BgSaftRef.partner(row.id, label));
    }
    SaftAddress address =
        new SaftAddress()
            .streetName(fit(trim(row.street), MIDDLE2, row, label + " StreetName", found))
            .number(fit(trim(row.number), SHORT, row, label + " Number", found))
            .city(city == null ? "" : fit(city, MIDDLE1, row, label + " City", found))
            .postalCode(fit(trim(row.postalCode), SHORT, row, label + " PostalCode", found))
            .country(country == null ? "" : country.toUpperCase(Locale.ROOT))
            .addressType(ADDRESS_TYPE);
    return new SaftParty()
        .registrationNumber(id.getCode())
        .name(fit(name, MIDDLE2, row, label + " Name", found))
        .addAddress(address)
        .relatedParty(RELATED_PARTY_NO)
        .partyId(id.getPartyId());
  }

  // ─────────────── натрупването ───────────────

  /** Сумите на един контрагент по една сметка на НАП — от една или няколко наши сметки. */
  private static final class Slot {
    private final Side side;
    private final String party;
    private final String nraCode;
    private final Set<Long> partnerIds = new TreeSet<>();
    private BgSaftBalance raw = new BgSaftBalance(null, null, null);
    private boolean referenced;

    private Slot(Side side, String party, String nraCode) {
      this.side = side;
      this.party = party;
      this.nraCode = nraCode;
    }

    private void add(Long partnerId, BgSaftBalance balance) {
      partnerIds.add(partnerId);
      raw = raw.plus(balance);
    }

    private boolean movement() {
      return round(raw.debit()).signum() != 0 || round(raw.credit()).signum() != 0;
    }

    private String member() {
      return side + "|" + party;
    }
  }

  /** Находките, събрани по вид — всеки контрагент по веднъж, в реда на появяване. */
  private static final class Findings {
    private final Set<BgSaftRef> missing = new LinkedHashSet<>();
    private final Set<BgSaftRef> eikInvalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> personInvalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> unverified = new LinkedHashSet<>();
    private final Set<BgSaftRef> prefixRemoved = new LinkedHashSet<>();
    private final Set<BgSaftRef> internal = new LinkedHashSet<>();
    private final Set<BgSaftRef> countryMissing = new LinkedHashSet<>();
    private final Set<BgSaftRef> cityMissing = new LinkedHashSet<>();
    private final Set<BgSaftRef> nameMissing = new LinkedHashSet<>();
    private final Set<BgSaftRef> truncated = new LinkedHashSet<>();
    private final Set<BgSaftRef> merged = new LinkedHashSet<>();
    private final Set<BgSaftRef> rounded = new LinkedHashSet<>();
    private final Set<BgSaftRef> withoutPartner = new LinkedHashSet<>();
    private final Set<BgSaftRef> notFound = new LinkedHashSet<>();
    private final Set<BgSaftRef> referenceNoAccount = new LinkedHashSet<>();

    private void identity(Long partnerId, String text, BgSaftPartyId id) {
      BgSaftRef label = BgSaftRef.partner(partnerId, text);
      BgSaftRef labelled = BgSaftRef.partner(partnerId, text + " → " + id.getPartyId());
      Set<BgSaftPartyId.Issue> issues = id.getIssues();
      if (issues.contains(BgSaftPartyId.Issue.MISSING)) {
        missing.add(label);
        return;
      }
      if (issues.contains(BgSaftPartyId.Issue.EIK_INVALID)) {
        eikInvalid.add(labelled);
      }
      if (issues.contains(BgSaftPartyId.Issue.PERSON_ID_INVALID)) {
        personInvalid.add(labelled);
      }
      if (issues.contains(BgSaftPartyId.Issue.UNVERIFIED)) {
        unverified.add(labelled);
      }
      if (issues.contains(BgSaftPartyId.Issue.PREFIX_REMOVED)) {
        prefixRemoved.add(labelled);
      }
      if (issues.contains(BgSaftPartyId.Issue.INTERNAL)) {
        internal.add(labelled);
      }
    }

    private List<BgDeclarationFileFinding> toFindings(
        boolean noCustomers, boolean noSuppliers, int parties) {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      if (noCustomers) {
        findings.add(empty(Side.CUSTOMER, "SAFT_CUSTOMERS_EMPTY", "MF.C.1"));
      }
      if (noSuppliers) {
        findings.add(empty(Side.SUPPLIER, "SAFT_SUPPLIERS_EMPTY", "MF.S.1"));
      }
      error(
          findings,
          "SAFT_PARTY_ID_MISSING",
          "Контрагенти без регистрационен номер и без вътрешен номер (partnerSeq) —"
              + " CustomerID/SupplierID е само типът, а RegistrationNumber (S.C.1) е празен: ",
          missing);
      error(
          findings,
          "SAFT_PARTY_EIK_INVALID",
          "ЕИК с невярна контролна цифра (MF.C.3/MF.S.3, тип 10) — НАП няма да разпознае"
              + " контрагента: ",
          eikInvalid);
      error(
          findings,
          "SAFT_PARTY_PERSON_ID_INVALID",
          "ЕГН с невярна контролна цифра (MF.C.3/MF.S.3, тип 13) — НАП няма да разпознае"
              + " контрагента: ",
          personInvalid);
      error(
          findings,
          "SAFT_PARTY_COUNTRY_MISSING",
          "Контрагенти без държава в основния адрес — Country е празен и файлът не минава схемата"
              + " (ISO код от точно два знака): ",
          countryMissing);
      error(
          findings,
          "SAFT_PARTY_REFERENCE_NO_ACCOUNT",
          "Контрагент на подаден документ без сметка от група 40/41 и без разчетна сметка с код по"
              + " НАП — AccountID (MF.C.5/MF.S.5) не може да се определи (QA с. 37): ",
          referenceNoAccount);
      error(
          findings,
          "SAFT_PARTY_NOT_FOUND",
          "Редове по сметки на клиенти и доставчици сочат партньор, който не е прочетен: ",
          notFound);
      warning(
          findings,
          "SAFT_PARTY_ID_UNVERIFIED",
          "13-цифрен ЕИК или ЛНЧ, чиято контролна цифра не излиза — подаден както е, да се провери"
              + " (алгоритмите им не са сверени с официален източник): ",
          unverified);
      warning(
          findings,
          "SAFT_PARTY_BG_PREFIX_REMOVED",
          "Регистрационен номер с префикс „BG“ — подаден без него, защото „10BG…“ НАП отказва (QA"
              + " IV.2.4); да се поправи в партньора: ",
          prefixRemoved);
      warning(
          findings,
          "SAFT_PARTY_INTERNAL_ID",
          "Контрагенти без регистрационен номер — подадени с тип 15 и вътрешния номер"
              + " (физическо лице без данни или етажна собственост без ЕИК, QA с. 33 и 37); ако"
              + " имат ЕИК, ЕГН или ЛНЧ, да се попълни: ",
          internal);
      warning(findings, "SAFT_PARTY_NAME_MISSING", "Контрагенти без име (S.C.2): ", nameMissing);
      warning(
          findings,
          "SAFT_PARTY_CITY_MISSING",
          "Контрагенти без населено място в основния адрес: ",
          cityMissing);
      warning(
          findings,
          "SAFT_PARTY_TRUNCATED",
          "Стойности, по-дълги от допустимото в схемата — съкратени: ",
          truncated);
      warning(
          findings,
          "SAFT_PARTY_MERGED",
          "Партньори с един и същ идентификатор — подадени като един контрагент (MF.C.3/MF.S.3"
              + " идентифицира лицето, не записа): ",
          merged);
      warning(
          findings,
          "SAFT_PARTY_LINES_WITHOUT_PARTNER",
          "Редове по сметки на клиенти и доставчици без контрагент — не излизат в"
              + " Customers/Suppliers, тоест сборът по контрагенти не се връзва със салдото на"
              + " сметката: ",
          withoutPartner);
      warning(
          findings,
          "SAFT_PARTY_ROUNDED",
          "Салда или обороти на контрагенти с трети знак след запетаята, закръглени HALF_UP до"
              + " два: ",
          rounded);
      if (parties > 0) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_PARTY_RELATED_PARTY_DEFAULTED",
                "RelatedParty (S.C.7) е „N“ за всички контрагенти — признакът за свързано лице по"
                    + " §1, т. 3 ДР ДОПК още не се води на партньора; свързаните лица да се"
                    + " проверят ръчно."));
      }
      return findings;
    }

    private static BgDeclarationFileFinding empty(Side side, String code, String element) {
      return BgDeclarationFileFinding.warning(
          code,
          "Няма "
              + side.noun
              + " с оборот или референция за периода — схемата иска поне един ("
              + side.table
              + ", "
              + element
              + "); подадена е нулева структура с „0“ и AccountID 998 по аналогия с QA IV.2.6."
              + " Дали НАП я приема — да се потвърди с пробния файл.");
    }

    private static void error(
        List<BgDeclarationFileFinding> findings, String code, String text, Set<BgSaftRef> items) {
      BgSaftRef.error(findings, code, text, items);
    }

    private static void warning(
        List<BgDeclarationFileFinding> findings, String code, String text, Set<BgSaftRef> items) {
      BgSaftRef.warning(findings, code, text, items);
    }
  }

  // ─────────────── помощници ───────────────

  /** Името и вътрешният номер — за находките. */
  private static String label(PartyRow row) {
    String name = displayName(row);
    String ref = trim(row.partnerSeq) != null ? row.partnerSeq.trim() : "#" + row.id;
    return name.isEmpty() ? ref : name + " (" + ref + ")";
  }

  /** Съкращава до {@code max} знака: по-дългото схемата отказва. {@code null} остава. */
  private static String fit(String value, int max, PartyRow row, String what, Findings found) {
    if (value == null || value.codePointCount(0, value.length()) <= max) {
      return value;
    }
    found.truncated.add(BgSaftRef.partner(row.id, what));
    return value.substring(0, value.offsetByCodePoints(0, max));
  }

  private static BigDecimal round(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static boolean changed(BigDecimal raw, BigDecimal rounded) {
    return raw.compareTo(rounded) != 0;
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
