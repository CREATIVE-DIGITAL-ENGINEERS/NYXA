package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure contribution logic: given the matching {@link BgInsuranceContribution} rows and a {@link
 * BgContributionRequest}, produces the per-fund, per-payer lines. No database, no Guice — every
 * branch is unit testable in memory. Database fetching lives in {@code
 * BgInsuranceCalculationService}.
 */
public class BgContributionResolver {

  static final String FUND_PENSION = "PENSION";

  /**
   * Фонд „Пенсии за лицата по чл. 69 КСО“ — ВТОРИЯТ пенсионен фонд, а не разновидност на първия.
   *
   * <p><b>⚠️ ЗАЩО СЪЩЕСТВУВА ТУК.</b> Пазачът {@code MISSING_MANDATORY_FUND} питаше само за {@link
   * #FUND_PENSION} и това беше вярно, докато нито един seed ред не носеше друг пенсионен фонд.
   * Речникът {@code BgContributionFund} обаче носи {@code PENSION_ART69} от самото начало, а НАП
   * Приложение № 1 разд. 6 и 7.3 слагат военнослужещите (код 06) и служителите на ДАР и „Военно
   * разузнаване“ (код 83) точно в него — с други ставки (60,8 и 55,8) и с друг платец.
   *
   * <p>Тоест за тези два кода наборът редове съдържа ПЪЛНОЦЕНЕН пенсионен фонд, но старият пазач не
   * го разпознаваше и отказваше целия фиш с „липсва фонд Пенсии“. Отказът щеше да е особено
   * подвеждащ: съобщението твърди липса, а редът стои в базата и се чете.
   *
   * <p><b>⚠️ И НЕ Е ПРЕИМЕНУВАНЕ.</b> Двата фонда съществуват ЕДНОВРЕМЕННО и се различават по
   * получател и по ставка — никой код не носи и двата, и точно затова проверката е „поне един от
   * двата“, а не „и двата“.
   */
  static final String FUND_PENSION_ART69 = "PENSION_ART69";

  static final String FUND_TZPB = "TZPB";

  static final String REGIME_WITH_UPF = "WITH_UPF";
  static final String REGIME_WITHOUT_UPF = "WITHOUT_UPF";

  static final String CHOICE_ART4B = "ART4B";

  static final String KIND_VALUE = "VALUE";
  static final String KIND_LEGAL_ZERO = "LEGAL_ZERO";
  static final String KIND_STRUCTURAL_ZERO = "STRUCTURAL_ZERO";
  static final String KIND_MISSING = "MISSING";

  private static final Set<String> KNOWN_KINDS =
      new HashSet<>(Arrays.asList(KIND_VALUE, KIND_LEGAL_ZERO, KIND_STRUCTURAL_ZERO, KIND_MISSING));

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private BgContributionResolver() {}

  /**
   * Derives the ДЗПО/УПФ cohort used to filter the pension rows.
   *
   * <ul>
   *   <li>born before 1960 → WITHOUT_UPF (the 5% УПФ goes into the state pension fund)
   *   <li>born after 1959, default → WITH_UPF
   *   <li>born after 1959, чл.4б КСО → WITHOUT_UPF (contributions transferred from УПФ to ДОО)
   *   <li>born after 1959, чл.4в КСО → WITH_UPF (чл.4в concerns ППФ, not УПФ, so it does not touch
   *       this axis at all — it flows through the default branch, and the ППФ row is <b>kept</b>:
   *       both Д1 т. 25 and Д6 т. 13 declare the чл. 4в case in the ППФ point)
   * </ul>
   */
  public static String deriveCohort(boolean bornAfter1959, String pensionChoice) {
    if (!bornAfter1959) {
      return REGIME_WITHOUT_UPF;
    }
    if (CHOICE_ART4B.equals(pensionChoice)) {
      return REGIME_WITHOUT_UPF;
    }
    return REGIME_WITH_UPF;
  }

  /**
   * Builds the contribution lines from the already-filtered rows.
   *
   * <p>Guards run before any line is built, so a data problem blocks rather than producing a
   * silently wrong total:
   *
   * <ul>
   *   <li>no rows → {@code NO_CONTRIBUTIONS_FOUND} (unknown insured type or no data for the period)
   *   <li>the same fund on more than one row → {@code DUPLICATE_FUND_ROWS} (a NULL in the unique
   *       constraint lets PostgreSQL keep two rows with the same key; summing them would
   *       double-charge, so this blocks instead of guessing)
   *   <li>a rate declared {@code MISSING} → {@code MISSING_RATE}, and a rate with no declared kind
   *       or an unrecognised one → {@code UNDECLARED_RATE_KIND}. Both run before any line is built,
   *       because the line loop below reads only {@code signum()} and at that point all three kinds
   *       of zero are the same {@code 0.0000}.
   * </ul>
   *
   * <p><b>⚠️ ЧЛ. 4В КСО ВЕЧЕ НЕ БЛОКИРА, И ТОВА НЕ Е ОТСЛАБЕН ПАЗАЧ.</b> Дотук заявка с избор
   * {@code ART4C} и наличен ред за ППФ се отказваше с {@code ART4C_NOT_MODELLED}, по довода „чл. 4в
   * пренасочва вноската към фонд „Пенсии“, което данните не моделират“. Доводът беше верен за
   * ПЛАЩАНЕТО и грешен за ДЕКЛАРИРАНЕТО, а блокираше и двете.
   *
   * <p>Указание т. 25 от Приложение № 1 казва дословно: „размерът на вноската … за професионален
   * пенсионен фонд <b>ИЛИ за лицата по чл. 4в от КСО за фонд „Пенсии“ с увеличена осигурителна
   * вноска в размера на вноската за професионален пенсионен фонд</b>“. Същото прави и Д6: т. 13 е
   * „ДЗПО в ППФ <b>и/или</b> фонд „Пенсии“ за лицата по чл. 4в ал. 1“. Тоест и двете декларации
   * СЛИВАТ двата случая в ЕДНА точка — тази на ППФ. Редът за ППФ, оставен както е, дава точно
   * верните т. 22 (основната пенсионна ставка) и т. 25 (ставката за ППФ); за морско лице от първа
   * категория това е 17,8 и 12,0, чийто сбор 29,8 е числото от справката на НАП.
   *
   * <p><b>⚠️ И КАКВО ОСТАВА НЕМОДЕЛИРАНО — ПО ИМЕ.</b> ПОЛУЧАТЕЛЯТ. {@code
   * BgContributionFund.bgRecipient} за ППФ е {@code PENSION_COMPANY}, а при избор по чл. 4в парите
   * отиват в НОИ. Това мени банковия превод и осчетоводяването, НЕ декларацията. Границата е тясна
   * и назована тук, вместо цял фиш да не може да се потвърди заради нея — виж {@code
   * apps/init-data/manifest/seafarers-2026.yml}.
   *
   * <p>Then each row yields up to two lines because it carries both rates; a zero rate produces no
   * line ({@code signum} only — the generated decimal getter never returns null, see
   * axelor-decimal-getter-zero). Each amount is {@code base × rate / 100} rounded to two decimals
   * independently (чл.2 ал.4: separate bases, each rounded on its own). ТЗПБ is added from {@code
   * request.tzpbRate} when nonzero <b>and the rows carry no ТЗПБ row of their own</b> — insured
   * types 05 and 24 have a fixed rate that overrides the activity one. Missing pension → {@code
   * MISSING_MANDATORY_FUND}. A WITH_UPF cohort whose rows carry a pension row but no УПF row →
   * {@code RESOLVED_INCOMPLETE}: the lines are returned but the missing 5 points are flagged.
   *
   * @param rows the {@code BgInsuranceContribution} rows already filtered by insured type,
   *     category, period, cohort and СОЛ scope; empty when the insured type code is unknown
   */
  public static BgContributionResult resolve(
      List<BgInsuranceContribution> rows, BgContributionRequest request) {

    if (rows == null || rows.isEmpty()) {
      // ⚠️ ДВЕТЕ ПОЛЕТА, НЕ ЕДНОТО. Дотук причината беше „вид осигурен: 01“ и това пращаше
      // оператора към ПЪРВОТО поле на ключа, докато сгрешено можеше да е второто: ключът в
      // каталога е ДВОЙКАТА (вид осигурен, категория труд) и празният набор не казва кое от двете
      // е сгрешено. Именуването е споделено с отказа при ЗАПИС на договора — виж
      // BgInsuredTypeCategoryRule, за да не се разминат двете съобщения за едно и също състояние.
      return BgContributionResult.failure(
          BgContributionResult.Status.NO_CONTRIBUTIONS_FOUND,
          BgInsuredTypeCategoryRule.noRatesFor(
                  request.getInsuredTypeCode(), request.getLaborCategory())
              // ⚠️ И ДАТАТА. Откакто записът на договора отказва невъзможната двойка, празният
              // набор ТУК най-често значи друго: редовете съществуват, но не са в сила на този
              // ден. Без датата изречението твърди повече, отколкото резолверът знае — той вижда
              // само че заявката не е върнала нищо.
              + (request.getReferenceDate() == null
                  ? ""
                  : " (търсено към " + request.getReferenceDate() + ")"));
    }

    // Guard: the same fund must not appear twice for one key (NULLs defeat the unique constraint).
    Map<String, List<String>> byFund = new LinkedHashMap<>();
    for (BgInsuranceContribution row : rows) {
      byFund
          .computeIfAbsent(row.getFundCode(), k -> new ArrayList<>())
          .add(String.valueOf(row.getImportId()));
    }
    for (Map.Entry<String, List<String>> e : byFund.entrySet()) {
      if (e.getValue().size() > 1) {
        return BgContributionResult.failure(
            BgContributionResult.Status.DUPLICATE_FUND_ROWS,
            "фонд " + e.getKey() + ": " + String.join(", ", e.getValue()));
      }
    }

    // Guard: a rate whose kind is MISSING has no established value, and a rate with no declared
    // kind at all is not assumed to be a VALUE. Both refuse here, before any line is built, because
    // the loop below reads only signum() — by then MISSING, LEGAL_ZERO and STRUCTURAL_ZERO are the
    // same 0.0000 and the missing one would silently drop out of the total.
    for (BgInsuranceContribution row : rows) {
      BgContributionResult refusal = refuseOnKind(row, row.getBgEmployerRateKind(), "работодател");
      if (refusal == null) {
        refusal = refuseOnKind(row, row.getBgEmployeeRateKind(), "лице");
      }
      if (refusal != null) {
        return refusal;
      }
    }

    BigDecimal base = request.getInsurableIncome();
    // „Поне един от двата пенсионни фонда“ — виж FUND_PENSION_ART69. Код 06 и код 83 носят
    // ART69 вместо общия, тоест проверка само за общия би отказала фиш с наличен пенсионен фонд.
    boolean hasPension = byFund.containsKey(FUND_PENSION) || byFund.containsKey(FUND_PENSION_ART69);
    boolean hasUpf = byFund.containsKey("UPF");
    List<BgContributionLine> lines = new ArrayList<>();

    for (BgInsuranceContribution row : rows) {
      String fund = row.getFundCode();
      BigDecimal employerRate = row.getEmployerRate();
      if (employerRate.signum() != 0) {
        lines.add(line(fund, Payer.EMPLOYER, base, employerRate));
      }
      BigDecimal employeeRate = row.getEmployeeRate();
      if (employeeRate.signum() != 0) {
        lines.add(line(fund, Payer.INSURED, base, employeeRate));
      }
    }

    if (!hasPension) {
      return BgContributionResult.failure(
          BgContributionResult.Status.MISSING_MANDATORY_FUND,
          "липсва пенсионен фонд (нито PENSION, нито PENSION_ART69) за вид осигурен "
              + request.getInsuredTypeCode());
    }

    // ТЗПБ — entirely employer-borne. Normally it is not in the row set at all: the rate depends on
    // the employer's economic activity, so it arrives through the request from the КИД table. But
    // insured types 05 and 24 carry a fixed 0,70% regardless of activity, and that exception lives
    // in the table like every other rate. A row therefore WINS over the request rate: the loop
    // above
    // has already emitted its line, and adding the activity rate on top would produce two ТЗПБ
    // lines
    // — the double charge that DUPLICATE_FUND_ROWS guards against everywhere else.
    BigDecimal tzpbRate = request.getTzpbRate();
    if (!byFund.containsKey(FUND_TZPB) && tzpbRate != null && tzpbRate.signum() != 0) {
      lines.add(line(FUND_TZPB, Payer.EMPLOYER, base, tzpbRate));
    }

    // A WITH_UPF cohort with a pension row but no УПФ row loses the 5 УПФ points silently — flag
    // it.
    String cohort = deriveCohort(request.isBornAfter1959(), request.getPensionChoice());
    if (REGIME_WITH_UPF.equals(cohort) && hasPension && !hasUpf) {
      return BgContributionResult.resolvedIncomplete(
          lines, "липсва УПФ ред при кохорта WITH_UPF — 5 пункта не се начисляват");
    }

    return BgContributionResult.resolved(lines);
  }

  /**
   * Returns the refusal a single rate's kind demands, or {@code null} when the rate may be used.
   *
   * <p>{@code LEGAL_ZERO} and {@code STRUCTURAL_ZERO} pass: both are genuine zeros and the line
   * loop correctly emits nothing for them. Only {@code MISSING} — value never established — and an
   * absent or unrecognised kind refuse.
   */
  private static BgContributionResult refuseOnKind(
      BgInsuranceContribution row, String kind, String payer) {
    String where = "ред " + row.getImportId() + ", фонд " + row.getFundCode() + ", " + payer;
    if (kind == null || kind.trim().isEmpty() || !KNOWN_KINDS.contains(kind)) {
      return BgContributionResult.failure(
          BgContributionResult.Status.UNDECLARED_RATE_KIND,
          where + ": вид на ставката " + (kind == null ? "липсва" : "„" + kind + "“ е непознат"));
    }
    if (KIND_MISSING.equals(kind)) {
      return BgContributionResult.failure(
          BgContributionResult.Status.MISSING_RATE,
          where + ": ставката не е установена — това е липсваща стойност, а не нула");
    }
    return null;
  }

  private static BgContributionLine line(
      String fundCode, Payer payer, BigDecimal base, BigDecimal rate) {
    BigDecimal amount = base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    return new BgContributionLine(fundCode, payer, base, rate, amount);
  }
}
