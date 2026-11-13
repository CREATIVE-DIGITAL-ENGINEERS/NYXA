package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GeneralLedgerAccounts (MF.GLA.*) ОТ СМЕТКОПЛАНА, МАПИНГА КЪМ НАП И САЛДАТА — чиста функция.
 *
 * <p><b>Нула заявки.</b> Сметкопланът, мапингът ({@code BgSafAccountMapping}), номенклатурата на
 * НАП и сумите от {@link BgSaftMoveScope} му се подават, тоест всяко правило тук се проверява с
 * числа и без Postgres.
 *
 * <p><b>⚠️ КОИ СМЕТКИ — ПО QA НА НАП, НЕ ПО КНИГАТА.</b> MF.GLA.1 казва „всички сметки“, но
 * отговорите на НАП (март 2026, с. 27–28) я стесняват: сметките със салдо, различно от нула, в
 * началото или в края на периода, и тези с дебитни или кредитни обороти в него. Сметка без нито
 * едното не излиза.
 *
 * <p><b>⚠️ ВСЕКИ РЕД Е НАША СМЕТКА, AccountID Е НАВИТИЯТ КОД.</b> 1221 и 1222 излизат като два реда
 * с AccountID 122 и различен TaxpayerAccountID (MF.GLA.2/4) — всеки със своето салдо. Обратното
 * (една наша сметка към два кода на НАП) мапингът не допуска по устройство.
 *
 * <p><b>⚠️ СМЕТКА БЕЗ РЕД В МАПИНГА СЕ КАЧВА ПО РОДИТЕЛИТЕ</b> до първия мапнат — клиентската 4111
 * получава кода на 411, с предупреждение. Ако първият мапнат родител е изключен (структурен възел
 * или задбалансова), сметката е изключена с него. Без мапнат родител AccountID остава празен и
 * находката е ГРЕШКА: празен код чупи шаблона {@code [1-9][0-9]{2,4}} на схемата.
 *
 * <p><b>⚠️ ЗАКРЪГЛЯНЕ ДО 2 ЗНАКА, HALF_UP.</b> Сумите в базата са с три знака (ЗВЕРБ чл. 13 за
 * правилото). Крайното салдо е началното плюс оборотите СЛЕД закръгляне, за да се връзва с числата,
 * които файлът показва. Всяка променена сума дава находка — тя значи, че някъде има ред с трети
 * знак, който не е нула.
 */
public final class BgSaftGlAccountsAssembler {

  /** MF.GLA.7 — ASCII, както в схемата. Книгата и QA пишат „Activе“ с кирилско „е“. */
  public static final String ACTIVE = "Active";

  public static final String PASSIVE = "Passive";
  public static final String BIFUNCTIONAL = "Bifunctional";

  private static final int SCALE = 2;

  private BgSaftGlAccountsAssembler() {}

  /** Сметка от сметкоплана на дружеството. */
  public static final class AccountRow {
    private final Long id;
    private final String code;
    private final String name;
    private final String apSelect;
    private final Long parentId;

    /**
     * @param apSelect {@code Account.bgApSelect}: 1 активна, 2 пасивна, 3 активно-пасивна
     * @param parentId {@code Account.parentAccount}; {@code null} за корен
     */
    public AccountRow(Long id, String code, String name, String apSelect, Long parentId) {
      this.id = id;
      this.code = code;
      this.name = name;
      this.apSelect = apSelect;
      this.parentId = parentId;
    }
  }

  /** Ред от {@code BgSafAccountMapping}. */
  public static final class MappingRow {
    private final String nraCode;
    private final boolean excluded;

    public MappingRow(String nraCode, boolean excluded) {
      this.nraCode = nraCode;
      this.excluded = excluded;
    }
  }

  /** Сметките и находките по пътя. */
  public static final class Result {
    private final List<SaftGlAccount> accounts;
    private final List<BgDeclarationFileFinding> findings;

    private Result(List<SaftGlAccount> accounts, List<BgDeclarationFileFinding> findings) {
      this.accounts = Collections.unmodifiableList(accounts);
      this.findings = Collections.unmodifiableList(findings);
    }

    public List<SaftGlAccount> getAccounts() {
      return accounts;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param chart сметкопланът на дружеството
   * @param mappings {@code BgSafAccountMapping} по код на сметката
   * @param nraCodes кодовете на унифицирания сметкоплан на НАП ({@link BgNraNomenclatures})
   * @param balances суровите суми по id на сметка
   * @param postedBeforeYear има ли осчетоводени статии преди началото на финансовата година
   * @param openingMoveInYear има ли откриваща статия в годината
   */
  public static Result assemble(
      Collection<AccountRow> chart,
      Map<String, MappingRow> mappings,
      Set<String> nraCodes,
      Map<Long, BgSaftBalance> balances,
      boolean postedBeforeYear,
      boolean openingMoveInYear) {
    Map<Long, AccountRow> byId = new HashMap<>();
    for (AccountRow row : chart) {
      byId.put(row.id, row);
    }
    List<BgSaftRef> unmapped = new ArrayList<>();
    List<BgSaftRef> invalidCode = new ArrayList<>();
    List<BgSaftRef> excludedWithMovement = new ArrayList<>();
    List<BgSaftRef> viaParent = new ArrayList<>();
    List<BgSaftRef> typeDefaulted = new ArrayList<>();
    List<BgSaftRef> rounded = new ArrayList<>();
    List<SaftGlAccount> accounts = new ArrayList<>();

    for (Map.Entry<Long, BgSaftBalance> entry : balances.entrySet()) {
      AccountRow account = byId.get(entry.getKey());
      BgSaftBalance balance = entry.getValue();
      BigDecimal opening = round(balance.opening());
      BigDecimal debit = round(balance.debit());
      BigDecimal credit = round(balance.credit());
      BigDecimal closing = opening.add(debit).subtract(credit);
      boolean movement = debit.signum() != 0 || credit.signum() != 0;
      if (!movement && opening.signum() == 0 && closing.signum() == 0) {
        continue;
      }
      if (account == null) {
        unmapped.add(
            BgSaftRef.account(
                entry.getKey(), "сметка #" + entry.getKey() + " извън сметкоплана на дружеството"));
        continue;
      }
      String code = trim(account.code);

      String accountId;
      Resolved resolved = resolve(account, byId, mappings);
      if (resolved == null) {
        unmapped.add(BgSaftRef.account(account.id, code));
        accountId = "";
      } else if (resolved.mapping.excluded) {
        if (movement) {
          excludedWithMovement.add(
              BgSaftRef.account(
                  account.id,
                  code.equals(resolved.via) ? code : code + " (през " + resolved.via + ")"));
        }
        continue;
      } else {
        accountId = trim(resolved.mapping.nraCode);
        if (!code.equals(resolved.via)) {
          viaParent.add(
              BgSaftRef.account(
                  account.id, code + " → " + accountId + " (през " + resolved.via + ")"));
        }
        if (accountId.isEmpty() || !nraCodes.contains(accountId)) {
          invalidCode.add(BgSaftRef.account(account.id, code + " → „" + accountId + "“"));
        }
      }

      String type = accountType(account.apSelect);
      if (type == null) {
        typeDefaulted.add(BgSaftRef.account(account.id, code));
        type = BIFUNCTIONAL;
      }
      if (changed(balance.opening(), opening)
          || changed(balance.debit(), debit)
          || changed(balance.credit(), credit)) {
        rounded.add(BgSaftRef.account(account.id, code));
      }
      accounts.add(
          new SaftGlAccount()
              .accountId(accountId)
              .accountDescription(account.name == null ? "" : account.name)
              .taxpayerAccountId(code)
              .accountType(type)
              .openingBalance(opening)
              .closingBalance(closing));
    }
    accounts.sort(
        Comparator.comparing(SaftGlAccount::getAccountId)
            .thenComparing(SaftGlAccount::getTaxpayerAccountId));

    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    if (accounts.isEmpty()) {
      findings.add(
          BgDeclarationFileFinding.error(
              "SAFT_GLA_EMPTY",
              "Няма сметка със салдо или оборот за периода — схемата иска поне една"
                  + " (GeneralLedgerAccounts/Account)."));
    }
    BgSaftRef.error(
        findings,
        "SAFT_GLA_UNMAPPED",
        "Сметки без код по сметкоплана на НАП нито при тях, нито при родител (MF.GLA.2) —"
            + " AccountID е празен и файлът не минава схемата: ",
        unmapped);
    BgSaftRef.error(
        findings,
        "SAFT_GLA_NRA_CODE_INVALID",
        "Мапингът дава код, който го няма в сметкоплана на НАП (MF.GLA.2): ",
        invalidCode);
    BgSaftRef.warning(
        findings,
        "SAFT_GLA_EXCLUDED_WITH_MOVEMENT",
        "Сметки извън SAF-T (структурни или задбалансови) имат обороти в периода — не"
            + " излизат във файла, тоест дебитът и кредитът му може да не се балансират: ",
        excludedWithMovement);
    BgSaftRef.warning(
        findings,
        "SAFT_GLA_MAPPED_VIA_PARENT",
        "Сметки без собствен ред в мапинга получават кода на родител — да се потвърди: ",
        viaParent);
    BgSaftRef.warning(
        findings,
        "SAFT_GLA_ACCOUNT_TYPE_DEFAULTED",
        "Сметки без А/П ос — подадени като Bifunctional (MF.GLA.7): ",
        typeDefaulted);
    BgSaftRef.warning(
        findings,
        "SAFT_GLA_ROUNDED",
        "Салда или обороти с трети знак след запетаята, закръглени HALF_UP до два: ",
        rounded);
    if (postedBeforeYear && !openingMoveInYear) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_GLA_NO_OPENING_MOVE",
              "Има осчетоводявания от предходни години, а финансовата година няма откриваща"
                  + " статия — началните салда на балансовите сметки излизат нула."));
    }
    return new Result(accounts, findings);
  }

  /**
   * AccountID на всяка сметка от сметкоплана по същото правило като {@link #assemble} — собственият
   * ред в мапинга или първият мапнат родител. Сметка без код или изключена не влиза.
   *
   * <p>Контрагентите (MF.C.5/MF.S.5) го ползват, за да носят СЪЩИЯ AccountID като
   * GeneralLedgerAccounts — иначе 4111 би излязла като 411 в сметките и като нещо друго в
   * клиентите.
   */
  static Map<Long, String> nraAccountIds(
      Collection<AccountRow> chart, Map<String, MappingRow> mappings) {
    Map<Long, String> ids = new HashMap<>();
    resolutions(chart, mappings)
        .forEach(
            (id, resolution) -> {
              if (!resolution.isExcluded() && !resolution.getNraCode().isEmpty()) {
                ids.put(id, resolution.getNraCode());
              }
            });
    return ids;
  }

  /**
   * Решението за всяка сметка от сметкоплана по същото правило като {@link #assemble} — включително
   * изключените и тези без код. Дневникът (G3) го ползва за реда: изключената сметка отпада, а
   * сметката без код излиза с празен AccountID, точно както в GeneralLedgerAccounts.
   */
  static Map<Long, Resolution> resolutions(
      Collection<AccountRow> chart, Map<String, MappingRow> mappings) {
    Map<Long, AccountRow> byId = new HashMap<>();
    for (AccountRow row : chart) {
      byId.put(row.id, row);
    }
    Map<Long, Resolution> resolutions = new HashMap<>();
    for (AccountRow row : chart) {
      Resolved resolved = resolve(row, byId, mappings);
      resolutions.put(
          row.id,
          new Resolution(
              trim(row.code),
              resolved == null ? "" : trim(resolved.mapping.nraCode),
              resolved == null,
              resolved != null && resolved.mapping.excluded));
    }
    return resolutions;
  }

  /** MF.GLA.7 от {@code bgApSelect}; {@code null}, когато осата не е попълнена. */
  static String accountType(String apSelect) {
    String v = trim(apSelect);
    switch (v) {
      case "1":
        return ACTIVE;
      case "2":
        return PASSIVE;
      case "3":
        return BIFUNCTIONAL;
      default:
        return null;
    }
  }

  /** AccountID на една наша сметка — кодът ѝ, кодът по НАП и дали изобщо влиза. */
  static final class Resolution {
    private final String code;
    private final String nraCode;
    private final boolean unmapped;
    private final boolean excluded;

    private Resolution(String code, String nraCode, boolean unmapped, boolean excluded) {
      this.code = code;
      this.nraCode = nraCode;
      this.unmapped = unmapped;
      this.excluded = excluded;
    }

    /** Нашият код — TaxpayerAccountID. */
    String getCode() {
      return code;
    }

    /** Кодът по НАП; празен, когато няма мапнат ред нито при сметката, нито при родител. */
    String getNraCode() {
      return nraCode;
    }

    boolean isUnmapped() {
      return unmapped;
    }

    /** Структурна или задбалансова — не влиза във файла. */
    boolean isExcluded() {
      return excluded;
    }
  }

  // ─────────────── помощници ───────────────

  private static final class Resolved {
    private final MappingRow mapping;
    private final String via;

    private Resolved(MappingRow mapping, String via) {
      this.mapping = mapping;
      this.via = via;
    }
  }

  /** Първият ред в мапинга — на самата сметка или на най-близкия родител. */
  private static Resolved resolve(
      AccountRow account, Map<Long, AccountRow> byId, Map<String, MappingRow> mappings) {
    Set<Long> seen = new HashSet<>();
    AccountRow current = account;
    while (current != null && seen.add(current.id)) {
      String code = trim(current.code);
      MappingRow mapping = mappings.get(code);
      if (mapping != null) {
        return new Resolved(mapping, code);
      }
      current = current.parentId == null ? null : byId.get(current.parentId);
    }
    return null;
  }

  private static BigDecimal round(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static boolean changed(BigDecimal raw, BigDecimal rounded) {
    return raw.compareTo(rounded) != 0;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
