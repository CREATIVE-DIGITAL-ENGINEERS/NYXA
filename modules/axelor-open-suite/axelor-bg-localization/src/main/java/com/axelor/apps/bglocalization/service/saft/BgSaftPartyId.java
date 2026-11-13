package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.BgEikValidationService;
import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * CustomerID / SupplierID (MF.C.3 / MF.S.3) — ДВУЦИФРЕН ТИП И КОДЪТ ЗАД НЕГО. Чиста функция.
 *
 * <p>Типовете нямат отделна номенклатура — стоят в колона F на MF.C.3 в книгата: 10 ЕИК; 11 ДДС
 * номер в ЕС с префикса на държавата; 12 извън ЕС — ISO2 и ДДС или регистров номер; 13 ЕГН/ЛНЧ; 14
 * чужд без номер — ISO2 и вътрешен код; 15 вътрешен код (физическо лице без данни, етажна
 * собственост без ЕИК — QA с. 33 и 37); 16 служебен номер на НАП (307…).
 *
 * <p><b>⚠️ RegistrationNumber (S.C.1) Е КОДЪТ БЕЗ ТИПА</b> — „DE123…“, не „11DE123…“.
 *
 * <p><b>⚠️ ПРИ 10, 13 И 16 НЯМА ПРЕФИКС НА ДЪРЖАВА.</b> „10BG…“ е грешката от QA IV.2.4 (с. 96),
 * затова „BG“ пред цифрите на регистрационния номер се маха — с предупреждение, защото полето е
 * попълнено грешно.
 *
 * <p><b>⚠️ ДЪРЖАВАТА Е НА АДРЕСА; ПРЕФИКСЪТ НА ДДС НОМЕРА Я ЗАМЕСТВА САМО ПРИ ЛИПСА.</b> Без нито
 * едното контрагентът се чете като български — тоест без регистрационен номер пада в 15.
 *
 * <p><b>⚠️ ДДС НОМЕР В ЕС БИЕ СЛУЖЕБНИЯ НОМЕР</b> (QA с. 36): доставчик от ЕС със служебен номер
 * 307… от НАП се подава с идентификатора си по ДДС (11).
 *
 * <p><b>⚠️ ЕИК НЕ СЕ ИЗВЕЖДА ОТ ДДС НОМЕРА.</b> От 01.01.2026 чл. 94, ал. 2 ЗДДС вече не прави
 * номера „BG“ + ЕИК (виж {@link BgVatNumberValidator}); контрагент без регистрационен номер е 15, а
 * находката казва да се попълни.
 *
 * <p><b>⚠️ ПРИ 14 КОДЪТ Е ВЪТРЕШНИЯТ НОМЕР</b> (partnerSeq) — по книгата „присъединен от
 * задълженото лице“. QA (с. 31) допуска вместо него и данъчния номер в държавата, ако е известен —
 * да се потвърди, ако трябва да се предпочете.
 */
public final class BgSaftPartyId {

  public static final String EIK = "10";
  public static final String EU_VAT = "11";
  public static final String NON_EU = "12";
  public static final String PERSON = "13";
  public static final String FOREIGN_INTERNAL = "14";
  public static final String INTERNAL = "15";
  public static final String NRA_SERVICE = "16";

  static final String BG = "BG";

  /** Началото на служебния номер от регистъра на НАП (MF.C.3, т. 7). */
  static final String NRA_SERVICE_PREFIX = "307";

  /** {@code bgIdentifierType} — {@code bg.income.payment.identifier.type.select}. */
  private static final String ID_EGN = "0";

  private static final String ID_LN_LNCH_NAP = "1";
  private static final String ID_NONE = "3";

  /** {@code PartnerRepository.PARTNER_TYPE_INDIVIDUAL}. */
  private static final int INDIVIDUAL = 2;

  private static final BgEikValidationService EIK_CHECK = new BgEikValidationService();

  /** Какво не е наред с идентификатора. Находките от тях прави assembler-ът. */
  public enum Issue {
    /** ГРЕШКА: 9-цифрен ЕИК, чиято контролна цифра не излиза. */
    EIK_INVALID,
    /**
     * Предупреждение: проверка, на която не може да се вярва достатъчно, за да блокира — 13-цифрен
     * ЕИК (опашката не е сверена с реален код, {@code BgEikValidationService#validateEik13}) и ЛНЧ
     * или идентификатор на физическо лице без признак (теглата на ЛНЧ нямат официален източник).
     */
    UNVERIFIED,
    /**
     * ГРЕШКА: ЕГН (признак „0“) с грешна контролна цифра. Алгоритъмът е еднозначен ({@code
     * BgEgnValidationService}); идентификатор, който не излиза, НАП не разпознава (MF.C.3).
     */
    PERSON_ID_INVALID,
    /** Предупреждение: префиксът „BG“ е махнат от регистрационния номер (QA IV.2.4). */
    PREFIX_REMOVED,
    /** Предупреждение: тип 15 — вътрешен номер вместо идентификатор. */
    INTERNAL,
    /** ГРЕШКА: няма нито идентификатор, нито вътрешен номер — кодът е празен. */
    MISSING
  }

  private final String type;
  private final String code;
  private final Set<Issue> issues;

  private BgSaftPartyId(String type, String code, Set<Issue> issues) {
    this.type = type;
    this.code = code;
    this.issues = Collections.unmodifiableSet(issues);
  }

  /** Двуцифреният тип. */
  public String getType() {
    return type;
  }

  /** Кодът без типа — това е RegistrationNumber (S.C.1). Празен при {@link Issue#MISSING}. */
  public String getCode() {
    return code;
  }

  /** CustomerID / SupplierID — типът и кодът. */
  public String getPartyId() {
    return type + code;
  }

  public Set<Issue> getIssues() {
    return issues;
  }

  /**
   * @param partnerTypeSelect 1 юридическо, 2 физическо лице
   * @param identifierType {@code Partner.bgIdentifierType}: 0 ЕГН, 1 ЛН/ЛНЧ/служебен №, 2 БУЛСТАТ,
   *     3 чуждестранно лице без идентификатор
   * @param registrationCode {@code Partner.registrationCode}
   * @param taxNbr {@code Partner.taxNbr} — ДДС номерът
   * @param country ISO2 на държавата от основния адрес
   * @param partnerSeq вътрешният номер на партньора
   */
  public static BgSaftPartyId of(
      Integer partnerTypeSelect,
      String identifierType,
      String registrationCode,
      String taxNbr,
      String country,
      String partnerSeq) {
    String vat = trim(BgVatNumberValidator.normalize(taxNbr));
    String vatPrefix = vat == null ? null : BgVatNumberValidator.countryCode(vat);
    String iso = trim(country) == null ? null : country.trim().toUpperCase(Locale.ROOT);
    boolean foreign = iso != null ? !BG.equals(iso) : vatPrefix != null && !BG.equals(vatPrefix);
    Set<Issue> issues = EnumSet.noneOf(Issue.class);
    return foreign
        ? foreign(
            iso != null ? iso : isoOf(vatPrefix),
            trim(registrationCode),
            vat,
            trim(partnerSeq),
            issues)
        : bulgarian(
            partnerTypeSelect,
            trim(identifierType),
            trim(registrationCode),
            trim(partnerSeq),
            issues);
  }

  // ─────────────── България ───────────────

  private static BgSaftPartyId bulgarian(
      Integer partnerTypeSelect, String idType, String reg, String seq, Set<Issue> issues) {
    if (reg != null
        && reg.length() > 2
        && reg.regionMatches(true, 0, BG, 0, 2)
        && Character.isDigit(reg.charAt(2))) {
      reg = reg.substring(2);
      issues.add(Issue.PREFIX_REMOVED);
    }
    if (reg == null || ID_NONE.equals(idType)) {
      issues.add(Issue.INTERNAL);
      return internal(INTERNAL, "", seq, issues);
    }
    boolean validEik = EIK_CHECK.validateEik(reg);
    if (reg.startsWith(NRA_SERVICE_PREFIX) && (ID_LN_LNCH_NAP.equals(idType) || !validEik)) {
      return new BgSaftPartyId(NRA_SERVICE, reg, issues);
    }
    boolean person =
        ID_EGN.equals(idType)
            || ID_LN_LNCH_NAP.equals(idType)
            || (idType == null
                && partnerTypeSelect != null
                && partnerTypeSelect == INDIVIDUAL
                && !validEik);
    if (person) {
      boolean valid =
          ID_EGN.equals(idType)
              ? BgIdentifierValidator.isValidEgn(reg)
              : BgIdentifierValidator.isValidEgnOrLnch(reg);
      if (!valid) {
        // ГРЕШКА само за ЕГН по признака — алгоритъмът му е еднозначен. ЛНЧ и „без признак“ (може
        // да е ЛНЧ) предупреждават: теглата на ЛНЧ нямат официален източник — да се потвърди.
        issues.add(ID_EGN.equals(idType) ? Issue.PERSON_ID_INVALID : Issue.UNVERIFIED);
      }
      return new BgSaftPartyId(PERSON, reg, issues);
    }
    // ЕИК — на юридическо лице или БУЛСТАТ на физическо (признак „2“). Блокира само 9-цифреният:
    // само неговият алгоритъм е сверен срещу реални кодове (виж BgEikValidationService).
    if (reg.matches("\\d{9}") && !validEik) {
      issues.add(Issue.EIK_INVALID);
    } else if (!validEik) {
      issues.add(Issue.UNVERIFIED);
    }
    return new BgSaftPartyId(EIK, reg, issues);
  }

  // ─────────────── чужбина ───────────────

  private static BgSaftPartyId foreign(
      String iso, String reg, String vat, String seq, Set<Issue> issues) {
    if (vat != null) {
      String prefix = BgVatNumberValidator.countryCode(vat);
      String cc = prefix != null ? prefix : vatPrefixOf(iso);
      if (!BG.equals(cc) && BgVatNumberValidator.isViesCountry(cc)) {
        return new BgSaftPartyId(EU_VAT, prefix != null ? vat : cc + vat, issues);
      }
    }
    if (reg != null && reg.startsWith(NRA_SERVICE_PREFIX)) {
      return new BgSaftPartyId(NRA_SERVICE, reg, issues);
    }
    if (!BgVatNumberValidator.isViesCountry(vatPrefixOf(iso))) {
      String number = vat != null ? withoutOwnPrefix(vat, iso) : reg;
      if (number != null) {
        return new BgSaftPartyId(NON_EU, iso + number, issues);
      }
    }
    return internal(FOREIGN_INTERNAL, iso, seq, issues);
  }

  // ─────────────── помощници ───────────────

  /** 14 и 15: вътрешният номер; без него кодът е празен и находката е ГРЕШКА. */
  private static BgSaftPartyId internal(String type, String prefix, String seq, Set<Issue> issues) {
    if (seq == null) {
      issues.add(Issue.MISSING);
      return new BgSaftPartyId(type, "", issues);
    }
    return new BgSaftPartyId(type, prefix + seq, issues);
  }

  /** ISO код → префикс по ДДС: единственото разминаване в ЕС е Гърция (GR → EL). */
  static String vatPrefixOf(String iso) {
    return "GR".equals(iso) ? "EL" : iso;
  }

  /** Префикс по ДДС → ISO код: EL → GR, XI (Северна Ирландия) → GB. */
  static String isoOf(String vatPrefix) {
    if ("EL".equals(vatPrefix)) {
      return "GR";
    }
    return "XI".equals(vatPrefix) ? "GB" : vatPrefix;
  }

  /** „GB123…“ при ISO GB → „123…“; префикс, след който не следва цифра, остава („CHE-…“). */
  private static String withoutOwnPrefix(String vat, String iso) {
    String own = vatPrefixOf(iso);
    if (vat.length() > 2 && vat.startsWith(own) && Character.isDigit(vat.charAt(2))) {
      return vat.substring(2);
    }
    return vat;
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
