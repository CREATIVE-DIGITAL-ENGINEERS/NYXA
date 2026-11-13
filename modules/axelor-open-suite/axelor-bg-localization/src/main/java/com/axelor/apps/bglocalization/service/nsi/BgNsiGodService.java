package com.axelor.apps.bglocalization.service.nsi;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgNsiGodLine;
import com.axelor.apps.bglocalization.db.BgNsiGodReport;
import com.axelor.apps.bglocalization.db.repo.BgNsiGodReportRepository;
import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import com.axelor.apps.bglocalization.service.common.BgCurrencyConversion;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodCalculator.Figures;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodCalculator.Result;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Kind;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Row;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Split;
import com.axelor.auth.AuthUtils;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ГОД КЪМ НСИ: ИЗЧИСЛЯВА, ЗАКЛЮЧВА И ОТКЛЮЧВА СПРАВКАТА.
 *
 * <p><b>Закръгляне.</b> Нито образецът (Приложение № 2), нито ръководството на ИСБС v3.5 дават
 * правило за закръгляне на баланса и ОПР — само „хил. лв.“ (проверено на 27.10.2026). Затова: всеки
 * ред по сметки се закръглява до хиляда HALF_UP, а сборните редове са сбор на ЗАКРЪГЛЕНИТЕ. Така
 * аритметичната връзка на формуляра се пази и контролите в ИСБС минават; разликата от закръглянето
 * излиза в контрола „актив = пасив“ и се коригира ръчно.
 *
 * <p><b>Предходната година.</b> Ако за нея има ЗАКЛЮЧЕНА справка в същата единица, колоната се
 * взима от нея — така, както е пренесена в ИСБС. Иначе се изчислява от счетоводството; левовите
 * суми в справка в евро се превалутират с {@link BgCurrencyConversion#toEur} (фиксираният курс).
 */
public class BgNsiGodService {

  public static final int DRAFT = 1;
  public static final int LOCKED = 2;

  public static final String BGN_K = "BGN_K";
  public static final String EUR_K = "EUR_K";

  private static final BigDecimal THOUSAND = new BigDecimal(1000);

  /** Записите на справката в заключено състояние минават само оттук (виж репозиториите). */
  private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);

  public static boolean isInternal() {
    return INTERNAL.get();
  }

  static <T> T internal(Supplier<T> work) {
    boolean before = INTERNAL.get();
    INTERNAL.set(true);
    try {
      return work.get();
    } finally {
      INTERNAL.set(before);
    }
  }

  protected final BgNsiGodLedger ledger;
  protected final BgNsiGodReportRepository repo;

  @Inject
  public BgNsiGodService(BgNsiGodLedger ledger, BgNsiGodReportRepository repo) {
    this.ledger = ledger;
    this.repo = repo;
  }

  /** Единицата по подразбиране: валутата на счетоводството към 31.12 на годината. */
  public static String defaultUnit(int year) {
    return "EUR".equals(BgAccountingCurrencyPolicy.expectedCurrency(LocalDate.of(year, 12, 31)))
        ? EUR_K
        : BGN_K;
  }

  @Transactional(rollbackOn = {Exception.class})
  public BgNsiGodReport compute(BgNsiGodReport report) {
    requireDraft(report);
    Company company = report.getCompany();
    int year = report.getReportYear();
    if (company == null || year < 2000 || year > LocalDate.now().getYear()) {
      throw new IllegalArgumentException("Изберете дружество и завършена отчетна година.");
    }
    String unit = report.getUnitSelect() == null ? defaultUnit(year) : report.getUnitSelect();
    report.setUnitSelect(unit);
    BgNsiGodForm form = BgNsiGodForm.forYear(year);
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (form.version != year) {
      warnings.add(
          "Формулярът на НСИ за "
              + year
              + " г. не е в системата — редовете са по образеца за "
              + form.version
              + " г. Кодовете и единицата да се потвърдят, когато НСИ публикува формуляра.");
    }

    Result current = BgNsiGodCalculator.compute(form, figures(form, company, year, unit));
    prefix("Текуща година: ", current, errors, warnings);

    BgNsiGodReport lockedPrevious = lockedPrevious(company, year - 1, unit);
    Map<String, BigDecimal> previousAuto = new HashMap<>();
    Map<String, BigDecimal> previousCorrection = new HashMap<>();
    if (lockedPrevious != null) {
      for (BgNsiGodLine line : lockedPrevious.getLineList()) {
        if (line.getCode() != null && !line.getCode().isEmpty()) {
          previousAuto.put(line.getCode(), line.getAutoCurrent());
          previousCorrection.put(line.getCode(), line.getCorrectionCurrent());
        }
      }
      report.setPreviousSource(
          "От заключената справка за " + (year - 1) + " г. (както е пренесена в ИСБС).");
    } else {
      Result previous = BgNsiGodCalculator.compute(form, figures(form, company, year - 1, unit));
      prefix("Предходна година: ", previous, errors, warnings);
      previousAuto.putAll(previous.values);
      report.setPreviousSource(
          "Изчислена от счетоводството за "
              + (year - 1)
              + " г."
              + (EUR_K.equals(unit) && year - 1 < BgAccountingCurrencyPolicy.TRANSITION.getYear()
                  ? " Левовите суми са превалутирани по фиксирания курс 1,95583."
                  : ""));
    }

    Map<String, BgNsiGodLine> old = new HashMap<>();
    if (report.getLineList() != null) {
      for (BgNsiGodLine line : report.getLineList()) {
        if (line.getCode() != null && !line.getCode().isEmpty()) {
          old.put(line.getCode(), line);
        }
      }
      report.getLineList().clear();
    }

    Map<String, BigDecimal> leavesCurrent = new HashMap<>();
    Map<String, BigDecimal> leavesPrevious = new HashMap<>();
    List<BgNsiGodLine> lines = new ArrayList<>();
    int sequence = 0;
    for (Row row : form.rows) {
      if (row.kind == Kind.IGNORE || row.kind == Kind.EXCL || row.kind == Kind.SPLIT) {
        continue;
      }
      BgNsiGodLine line = new BgNsiGodLine();
      line.setSequence(++sequence * 10);
      line.setTableSelect(row.table.name());
      line.setCode(row.code);
      line.setIndentLevel(row.level);
      line.setLabel(row.label);
      line.setKindSelect(row.kind.name());
      line.setMarkerSelect(marker(row));
      BgNsiGodLine before = old.get(row.code);
      if (before != null) {
        line.setNote(before.getNote());
      }
      if (row.kind == Kind.ACC || row.kind == Kind.MANUAL) {
        BigDecimal autoCur = current.values.getOrDefault(row.code, BigDecimal.ZERO);
        BigDecimal autoPrev = nz(previousAuto.get(row.code));
        BigDecimal corrCur = before == null ? null : before.getCorrectionCurrent();
        BigDecimal corrPrev =
            lockedPrevious != null
                ? previousCorrection.get(row.code)
                : before == null ? null : before.getCorrectionPrevious();
        line.setAutoCurrent(exact(autoCur));
        line.setAutoPrevious(exact(autoPrev));
        line.setCorrectionCurrent(corrCur);
        line.setCorrectionPrevious(corrPrev);
        line.setSourceAccounts(current.sources.get(row.code));
        leavesCurrent.put(row.code, thousands(autoCur).add(nz(corrCur)));
        leavesPrevious.put(row.code, thousands(autoPrev).add(nz(corrPrev)));
      }
      lines.add(line);
    }

    Map<String, BigDecimal> totalsCurrent = BgNsiGodCalculator.totals(form, leavesCurrent);
    Map<String, BigDecimal> totalsPrevious = BgNsiGodCalculator.totals(form, leavesPrevious);
    for (BgNsiGodLine line : lines) {
      if (line.getCode() != null && !line.getCode().isEmpty()) {
        line.setValueCurrent(totalsCurrent.get(line.getCode()));
        line.setValuePrevious(totalsPrevious.get(line.getCode()));
      }
      report.addLineListItem(line);
    }

    controls(form, report, totalsCurrent, "текуща", errors, warnings);
    controls(form, report, totalsPrevious, "предходна", errors, warnings);

    report.setFormVersion(form.version);
    report.setComputedOn(LocalDateTime.now());
    report.setComputedBy(AuthUtils.getUser());
    report.setErrorCount(errors.size());
    report.setWarningCount(warnings.size());
    StringBuilder text = new StringBuilder();
    errors.forEach(e -> text.append("ГРЕШКА: ").append(e).append('\n'));
    warnings.forEach(w -> text.append("⚠ ").append(w).append('\n'));
    report.setMessages(text.length() == 0 ? null : text.toString().trim());
    return internal(() -> repo.save(report));
  }

  @Transactional(rollbackOn = {Exception.class})
  public BgNsiGodReport lock(BgNsiGodReport report) {
    requireDraft(report);
    if (report.getComputedOn() == null) {
      throw new IllegalStateException("Справката не е изчислена.");
    }
    if (report.getErrorCount() > 0) {
      throw new IllegalStateException(
          "Справката има грешки ("
              + report.getErrorCount()
              + ") — сборовете не отговарят на счетоводството. Отстранете ги и изчислете отново.");
    }
    report.setStatusSelect(LOCKED);
    return internal(() -> repo.save(report));
  }

  @Transactional(rollbackOn = {Exception.class})
  public BgNsiGodReport unlock(BgNsiGodReport report) {
    if (report.getStatusSelect() != LOCKED) {
      throw new IllegalStateException("Справката не е заключена.");
    }
    report.setStatusSelect(DRAFT);
    return internal(() -> repo.save(report));
  }

  private static void requireDraft(BgNsiGodReport report) {
    if (report.getStatusSelect() == LOCKED) {
      throw new IllegalStateException("Справката е заключена. Отключете я, за да я промените.");
    }
  }

  protected BgNsiGodReport lockedPrevious(Company company, int year, String unit) {
    return repo.all()
        .filter(
            "self.company = :company AND self.reportYear = :year AND self.statusSelect = :locked"
                + " AND self.unitSelect = :unit")
        .bind("company", company)
        .bind("year", year)
        .bind("locked", LOCKED)
        .bind("unit", unit)
        .fetchOne();
  }

  /**
   * Цифрите по сметки за една колона, във валутата на справката.
   *
   * <p>Салдото към края на годината се пази и на части по режима на сметката
   * (BgNsiGodForm#splitMode): по партньор, по банкова сметка (банковата сметка на статията, иначе
   * дневникът ѝ) или цяло. Така DP/KP разпределят по знака на всяка част — клиент с надплащане е
   * получен аванс, а не намаление на вземането от друг клиент; банка с кредитно салдо е задължение
   * към банка.
   */
  protected Map<String, Figures> figures(
      BgNsiGodForm form, Company company, int year, String unit) {
    boolean eur = EUR_K.equals(unit);
    Map<String, Figures> out = new HashMap<>();
    Banks banks = new Banks(ledger.companyBanks(company));
    ledger
        .balances(company, LocalDate.of(year, 12, 31), year)
        .forEach(
            (code, byKey) -> {
              Figures f = fig(out, code);
              Split mode = form.splitMode(code);
              Map<String, BigDecimal> parts = new LinkedHashMap<>();
              for (Map.Entry<String, BigDecimal[]> e : byKey.entrySet()) {
                BigDecimal v = amount(e.getValue(), eur, code);
                f.bal = f.bal.add(v);
                parts.merge(part(mode, e.getKey(), code, banks), v, BigDecimal::add);
              }
              if (mode != Split.ACCOUNT) {
                f.balParts = new ArrayList<>(parts.values());
              }
            });
    ledger
        .balances(company, LocalDate.of(year - 1, 12, 31), year - 1)
        .forEach(
            (code, byKey) -> {
              Figures f = fig(out, code);
              for (BigDecimal[] v : byKey.values()) {
                f.balPrev = f.balPrev.add(amount(v, eur, code));
              }
            });
    boolean bgnYear =
        "BGN".equals(BgAccountingCurrencyPolicy.expectedCurrency(LocalDate.of(year, 1, 1)));
    if (!eur && !bgnYear) {
      throw new IllegalArgumentException(
          "Справка в хил. лв. за година в евро: еврото не се превалутира обратно в левове.");
    }
    boolean convert = eur && bgnYear;
    ledger
        .turnovers(company, year, false)
        .forEach(
            (code, dc) -> {
              Figures f = fig(out, code);
              f.td = convert ? BgCurrencyConversion.toEur(dc[0]) : dc[0];
              f.tc = convert ? BgCurrencyConversion.toEur(dc[1]) : dc[1];
            });
    ledger
        .turnovers(company, year, true)
        .forEach(
            (code, dc) -> {
              Figures f = fig(out, code);
              f.tdStock = convert ? BgCurrencyConversion.toEur(dc[0]) : dc[0];
              f.tcStock = convert ? BgCurrencyConversion.toEur(dc[1]) : dc[1];
            });
    // Оборотите „по насрещна сметка“ — по една заявка за всеки ключ в образеца (123+69, 60+61).
    Map<String, List<String>> specs = new LinkedHashMap<>();
    for (Row row : form.rows) {
      for (BgNsiGodForm.Token token : row.tokens) {
        if (token.against != null) {
          specs.putIfAbsent(token.againstKey(), token.against);
        }
      }
    }
    specs.forEach(
        (key, prefixes) ->
            ledger
                .turnoversAgainst(company, year, prefixes)
                .forEach(
                    (code, dc) ->
                        fig(out, code)
                            .against
                            .put(
                                key,
                                convert
                                    ? new BigDecimal[] {
                                      BgCurrencyConversion.toEur(dc[0]),
                                      BgCurrencyConversion.toEur(dc[1])
                                    }
                                    : dc)));
    return out;
  }

  /** Частта, в която отива ред с ключ „партньор|банкова сметка|дневник“ (BgNsiGodLedger). */
  static String part(Split mode, String key) {
    return part(mode, key, null, new Banks(List.of()));
  }

  /**
   * Частта на ред по сметка {@code accountCode}.
   *
   * <p><b>Банкова сметка.</b> Статиите без {@code companyBankDetails} — откриването, преоценката, а
   * на „Демо Търговия“ всички — НЕ са отделна банка. Преди тук ключът беше дневникът и една и съща
   * разплащателна сметка се цепеше на „откриване“ (+150 000) и „плащания“ (−47 012): ГОД показваше
   * несъществуващ кредит от банка 67 921 и раздуваше актива и пасива с толкова. Сега: единствената
   * банкова сметка на дружеството с тази счетоводна сметка; иначе единствената с този дневник (и
   * същата счетоводна сметка); иначе всички такива редове са една част.
   */
  static String part(Split mode, String key, String accountCode, Banks banks) {
    String[] k = key.split("\\|", -1);
    switch (mode) {
      case PARTNER:
        return "p" + k[0];
      case BANK:
        if (!k[1].isEmpty()) {
          return "b" + k[1];
        }
        Long bank = banks.of(accountCode, k[2]);
        return bank == null ? "j" + k[2] : "b" + bank;
      default:
        return "";
    }
  }

  /** Банковите сметки на дружеството по счетоводна сметка и по дневник — само еднозначните. */
  static final class Banks {
    private final Map<String, Long> byAccount = new HashMap<>();
    private final Map<String, String[]> byJournal = new HashMap<>();

    /** Редове [id на банковата сметка, id на дневника, код на счетоводната сметка]. */
    Banks(List<Object[]> rows) {
      Map<String, Integer> accountCount = new HashMap<>();
      Map<String, Integer> journalCount = new HashMap<>();
      for (Object[] r : rows) {
        String id = String.valueOf(r[0]);
        if (r[2] != null) {
          accountCount.merge((String) r[2], 1, Integer::sum);
          byAccount.put((String) r[2], Long.valueOf(id));
        }
        if (r[1] != null) {
          journalCount.merge(String.valueOf(r[1]), 1, Integer::sum);
          byJournal.put(String.valueOf(r[1]), new String[] {id, (String) r[2]});
        }
      }
      accountCount.forEach(
          (code, n) -> {
            if (n > 1) {
              byAccount.remove(code);
            }
          });
      journalCount.forEach(
          (journal, n) -> {
            if (n > 1) {
              byJournal.remove(journal);
            }
          });
    }

    Long of(String accountCode, String journalId) {
      Long bank = accountCode == null ? null : byAccount.get(accountCode);
      if (bank != null) {
        return bank;
      }
      String[] j = journalId == null ? null : byJournal.get(journalId);
      return j != null && j[1] != null && j[1].equals(accountCode) ? Long.valueOf(j[0]) : null;
    }
  }

  private static BigDecimal amount(BigDecimal[] parts, boolean eur, String code) {
    if (eur) {
      return BgCurrencyConversion.toEur(parts[0]).add(parts[1]);
    }
    if (parts[1].signum() != 0) {
      throw new IllegalArgumentException(
          "Сметка " + code + " има суми в евро, а справката е в хил. лв.");
    }
    return parts[0];
  }

  private static Figures fig(Map<String, Figures> out, String code) {
    return out.computeIfAbsent(code, k -> new Figures());
  }

  private static void prefix(
      String prefix, Result result, List<String> errors, List<String> warnings) {
    result.errors.forEach(e -> errors.add(prefix + e));
    result.warnings.forEach(w -> warnings.add(prefix + w));
  }

  /**
   * Точната сума на реда в стотинки/центове — мащабът на колоните {@code autoCurrent}/{@code
   * autoPrevious} (scale 2). Сумите идват от {@code MoveLine.debit/credit}, които в AOS са с три
   * знака; без това всяко записване на справката падаше на проверката на колоната („numeric value
   * out of bounds (&lt;18 digits&gt;.&lt;2 digits&gt; expected)“), щом базата валидира — както в
   * production. Хилядите ({@link #thousands}) се смятат от същата точна сума.
   */
  static BigDecimal exact(BigDecimal amount) {
    return nz(amount).setScale(2, RoundingMode.HALF_UP);
  }

  static BigDecimal thousands(BigDecimal amount) {
    return nz(amount).divide(THOUSAND, 0, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String marker(Row row) {
    if (row.kind == Kind.MANUAL) {
      return "MANUAL";
    }
    if (row.isService()) {
      return "SERVICE";
    }
    if (row.isCheck()) {
      return "CHECK";
    }
    if (row.isMss()) {
      return "MSS";
    }
    return null;
  }

  /** Контролите на формуляра и на справката за една колона. */
  private static void controls(
      BgNsiGodForm form,
      BgNsiGodReport report,
      Map<String, BigDecimal> v,
      String column,
      List<String> errors,
      List<String> warnings) {
    BigDecimal assets = nz(v.get("04500"));
    BigDecimal liabilities = nz(v.get("08500"));
    if (assets.compareTo(liabilities) != 0) {
      warnings.add(
          "Колона „"
              + column
              + " година“: сума на актива (04500) "
              + assets.toPlainString()
              + " ≠ сума на пасива (08500) "
              + liabilities.toPlainString()
              + " — разлика "
              + assets.subtract(liabilities).toPlainString()
              + ". Ако е от закръгляне, коригирайте ред по избор.");
    }
    if (nz(v.get("14500")).compareTo(nz(v.get("19500"))) != 0) {
      errors.add("Колона „" + column + " година“: 14500 ≠ 19500.");
    }
    BigDecimal balanceResult = nz(v.get("05600"));
    BigDecimal plResult = nz(v.get("14400")).subtract(nz(v.get("19200")));
    if (balanceResult.compareTo(plResult) != 0) {
      warnings.add(
          "Колона „"
              + column
              + " година“: текущата печалба в баланса (05600) "
              + balanceResult.toPlainString()
              + " ≠ резултата по ОПР (14400 − 19200) "
              + plResult.toPlainString()
              + ". Проверете данъка, разпределенията и неприключени минали години.");
    }
    List<String> check = new ArrayList<>();
    for (Row row : form.rows) {
      BigDecimal value = v.get(row.code);
      if (row.code.isEmpty() || value == null || value.signum() == 0) {
        continue;
      }
      if (row.isService()) {
        warnings.add(
            "Колона „"
                + column
                + " година“: "
                + row.label
                + " — "
                + value.toPlainString()
                + ". Разпределете я по редовете на групата в „Корекция“.");
      }
      if (row.isMss() && "NSS".equals(report.getStandardSelect())) {
        warnings.add(
            "Колона „" + column + " година“: ред " + row.code + " е само за МСС, а има стойност.");
      }
      if (row.isCheck() && row.kind == Kind.ACC) {
        check.add(row.code);
      }
    }
    if (!check.isEmpty()) {
      warnings.add(
          "Колона „"
              + column
              + " година“: проверете съответствието на редовете "
              + String.join(", ", check)
              + " (маркер „Проверка“).");
    }
  }
}
