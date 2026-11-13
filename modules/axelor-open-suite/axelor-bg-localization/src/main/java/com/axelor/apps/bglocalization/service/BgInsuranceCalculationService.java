package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgTzpbRate;
import com.axelor.apps.bglocalization.db.repo.BgInsuranceContributionRepository;
import com.axelor.apps.bglocalization.db.repo.BgTzpbRateRepository;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Database layer for the social-insurance contribution calculation.
 *
 * <p>Derives the УПФ cohort, fetches the matching {@link BgInsuranceContribution} rows for the
 * period, and delegates the arithmetic to the pure {@link BgContributionResolver}. POJO with no
 * injection; repositories via {@link Beans}, matching {@code BgMinInsuranceIncomeService}. It never
 * throws — a blocking status is returned in the result.
 */
public class BgInsuranceCalculationService {

  /** Кодът на фонд ЗОВ в {@code bg_contribution_fund} — същият низ, който резолверът ползва. */
  static final String FUND_HEALTH = "HEALTH";

  /**
   * Calculates the contribution lines for one request.
   *
   * <p>Cohort/scope филтърът ползва заместителя {@code ANY}, НЕ {@code NULL}: фондове, независими
   * от кохортата (ОЗМ, безработица, ЗОВ, ППФ, УчПФ, ГВРС) носят {@code bgUpfRegime = 'ANY'} и
   * минават веднъж, а двата пенсионни реда се стесняват до единствения съвпадащ режим. Същото за
   * СОЛ обхвата и за учителския: под вид осигурен 04 стоят и учители, и неучители, тоест редът за
   * учителския фонд носи {@code bgTeacherScope = 'TEACHER'}, а всички останали — {@code ANY}.
   * Колоните са NOT NULL (виж домейна) — {@code IS NULL} тук би изхвърлил тихо всички ANY редове.
   *
   * <p>{@code bgCompensationTypeCode} е ТОЧНО СЪВПАДЕНИЕ (не позволително ANY): за едно лице важи
   * само едно основание. Позволителен филтър тук би вкарал и STANDARD редовете заедно с
   * обезщетението — двойно начисляване, което изглежда като {@code DUPLICATE_FUND_ROWS}, но по
   * друга причина.
   */
  public BgContributionResult calculate(BgContributionRequest request) {
    String cohort =
        BgContributionResolver.deriveCohort(request.isBornAfter1959(), request.getPensionChoice());

    List<BgInsuranceContribution> rows =
        Beans.get(BgInsuranceContributionRepository.class)
            .all()
            .filter(
                "self.bgInsuredTypeCode = :code AND self.laborCategory = :cat "
                    + "AND self.validFrom <= :refDate "
                    + "AND (self.validTo IS NULL OR self.validTo >= :refDate) "
                    + "AND (self.bgUpfRegime = 'ANY' OR self.bgUpfRegime = :cohort) "
                    + "AND (self.bgSolScope = 'ANY' OR self.bgSolScope = :scope) "
                    + "AND (self.bgTeacherScope = 'ANY' OR self.bgTeacherScope = :teacherScope) "
                    + "AND self.bgCompensationTypeCode = :compensationTypeCode")
            .bind("code", request.getInsuredTypeCode())
            .bind("cat", request.getLaborCategory())
            .bind("refDate", request.getReferenceDate())
            .bind("cohort", cohort)
            .bind("scope", request.getSolScope())
            .bind("teacherScope", request.getTeacherScope())
            .bind("compensationTypeCode", request.getCompensationTypeCode())
            .fetch();

    return BgContributionResolver.resolve(rows, request);
  }

  /**
   * СТАВКАТА НА ФОНД ЗОВ ЗА СМЕТКА НА РАБОТОДАТЕЛЯ — за здравната вноска по ЗЗО чл. 40 ал. 1 т. 5.
   *
   * <p><b>⚠️ ЗАЩО ТУК, А НЕ В ЕНДЖИНА ЗА БОЛНИЧНИ.</b> Това е ставка от {@code
   * bg_insurance_contribution} и се чете по СЪЩИЯ механизъм като всяка друга — един прочит, един
   * филтър, едни и същи правила за периода. Втори механизъм за едно и също нещо е свободен да се
   * размине с първия по датите, а разминаване по дати значи, че две части на един фиш смятат по
   * различни версии на закона. Затова здравната вноска за дните ВНР РАЗШИРЯВА този клас, вместо да
   * си пише своя заявка.
   *
   * <p><b>Какво тази ставка НЕ е.</b> Не е нова ставка и не е ставка „за отсъствие“: това е точно
   * частта на работодателя от здравната вноска (за вид 01, трета категория, 2026 г. — 4,8000 на
   * сто), приложена върху ДРУГА база — {@code HEALTH_ABSENCE_BASE}, а не върху възнаграждението. Че
   * именно тя възпроизвежда реалния фиш, е записано като решение в {@code
   * BgSickLeaveResolver#HEALTH_ABSENCE_RATE_IS_EMPLOYER_SHARE}.
   *
   * <p>⚠️ Ставката НЕ се пише в кода точно защото е темпорална и не е една за всички: от 01.08.2026
   * г. до 31.12.2026 г. видове 05 и 24 са със съотношение 80/20, тоест 6,4 на сто за работодателя.
   *
   * <p>Редът се намира от {@link #findHealthRow} — същия прочит, който ползва и {@link
   * #findHealthTotalRate}, за да не се разминат двата ръба на чл. 40 по период.
   *
   * @return ставката в проценти (4.8000 значи 4,8 на сто), или {@code null}, ако ред не е намерен
   */
  public BigDecimal findHealthEmployerRate(
      String insuredTypeCode, String laborCategory, LocalDate date) {
    BgInsuranceContribution row = findHealthRow(insuredTypeCode, laborCategory, date);
    if (row == null) {
      return null;
    }
    // ⚠️ ВИДЪТ НА СТАВКАТА СЕ ПИТА, а не само знакът ѝ. MISSING значи „стойността не е установена“
    // и
    // е РАЗЛИЧНО от структурна нула: върнато като число, то би сметнало вноска нула за сметка на
    // работодателя — недекларирана вноска с вид на пресметната. Същото правило като в
    // BgContributionResolver.refuseOnKind, само че тук отговорът е null и повикващият отказва.
    return BgContributionResolver.KIND_VALUE.equals(row.getBgEmployerRateKind())
        ? row.getEmployerRate()
        : null;
  }

  /**
   * ЦЯЛАТА СТАВКА НА ФОНД ЗОВ — за здравната вноска при НЕПЛАТЕН ОТПУСК по ЗЗО чл. 40 ал. 1 т. 1 б.
   * „б“. Сборът на двете части, а не едната.
   *
   * <p><b>⚠️ ЗАЩО СБОР, А НЕ „ЧАСТТА НА ПЛАТЕЦА“ — ТУК Е ЦЯЛАТА РАЗЛИКА С {@link
   * #findHealthEmployerRate}.</b> Двата съседни ръба на ЗЗО чл. 40 казват РАЗЛИЧНИ неща и изглеждат
   * еднакво. Т. 5 (дните ДОО): „вноските са за сметка на работодателя … и са равни на ДЪЛЖИМАТА ОТ
   * НЕГО ЧАСТ от вноската“ — тоест 4,8 на сто. Т. 1 б. „б“ (неплатен отпуск): „вноската е ИЗЦЯЛО за
   * сметка на осигуреното лице … и за сметка на работодателя …“ — тоест 8 на сто, а платецът само
   * се сменя. Един метод за двете би сметнал единия ръб грешно, при това с вид на пресметнат.
   *
   * <p>⚠️ Сборът се чете от ДАННИТЕ, а не се пише като 8,00 в кода, точно защото ЧАСТИТЕ се менят
   * по транш и по вид осигурен: 4,8 + 3,2 за вид 01 през 2026 г. и 6,4 + 1,6 за видове 05 и 24 от
   * 01.08.2026 г. Двете дават 8,0 днес — но константа в кода би преживяла мълчаливо и деня, в който
   * се смени самият сбор.
   *
   * <p>⚠️ И ДВЕТЕ части се питат за ВИДА на ставката, и то по СЪЩОТО правило като {@code
   * BgContributionResolver.refuseOnKind}: неустановена е само {@code MISSING} (и необявеният вид).
   * {@code LEGAL_ZERO} и {@code STRUCTURAL_ZERO} са ИСТИНСКИ нули и се събират като нула — иначе
   * видове 12 и 92, при които цялата вноска от 8 на сто е на лицето, а частта на работодателя е
   * структурна нула, биха останали без сбор, тоест без вноска при неплатен отпуск. Половин ставка,
   * върната като цяла, дава вноска под дължимата — затова {@code MISSING} пак е {@code null}.
   *
   * @return сборът в проценти (8.0000 значи 8 на сто), или {@code null}, ако ред не е намерен или
   *     някоя от двете части не е установена
   */
  public BigDecimal findHealthTotalRate(
      String insuredTypeCode, String laborCategory, LocalDate date) {
    BgInsuranceContribution row = findHealthRow(insuredTypeCode, laborCategory, date);
    if (row == null
        || !isEstablished(row.getBgEmployerRateKind())
        || !isEstablished(row.getBgEmployeeRateKind())) {
      return null;
    }
    return row.getEmployerRate().add(row.getEmployeeRate());
  }

  /** Установена ли е стойността на ставката — всичко освен {@code MISSING} и необявен вид. */
  private static boolean isEstablished(String rateKind) {
    return rateKind != null
        && !rateKind.trim().isEmpty()
        && !BgContributionResolver.KIND_MISSING.equals(rateKind);
  }

  /**
   * Редът на фонд ЗОВ за вид осигурен, категория и дата — ЕДИН прочит за двете ставки.
   *
   * <p>Филтърът е {@code STANDARD} по основанието и {@code ANY} по трите оси, защото такива са
   * ВСИЧКИ редове на фонд ЗОВ в seed-а. Прочитът е {@code fetchOne} — същият, който {@link
   * #findTzpbRate} ползва: двойният ред е спрян от уникалното ограничение на таблицата и от {@code
   * BgHealthContributionInvariantTest}, тоест {@code null} тук значи ЛИПСВА, не „има два“.
   */
  private BgInsuranceContribution findHealthRow(
      String insuredTypeCode, String laborCategory, LocalDate date) {
    if (insuredTypeCode == null
        || insuredTypeCode.trim().isEmpty()
        || laborCategory == null
        || laborCategory.trim().isEmpty()
        || date == null) {
      return null;
    }
    return Beans.get(BgInsuranceContributionRepository.class)
        .all()
        .filter(
            "self.bgInsuredTypeCode = :code AND self.laborCategory = :cat "
                + "AND self.fundCode = :fund "
                + "AND self.validFrom <= :d "
                + "AND (self.validTo IS NULL OR self.validTo >= :d) "
                + "AND self.bgCompensationTypeCode = 'STANDARD'")
        .bind("code", insuredTypeCode.trim())
        .bind("cat", laborCategory.trim())
        .bind("fund", FUND_HEALTH)
        .bind("d", date)
        .fetchOne();
  }

  /**
   * КАТЕГОРИИТЕ ТРУД, ЗА КОИТО КАТАЛОГЪТ НОСИ СТАВКИ ЗА ТОЗИ ВИД ОСИГУРЕН.
   *
   * <p>Наборът, който {@link BgInsuredTypeCategoryRule} сверява при ЗАПИС на трудовия договор. Стои
   * тук, а не в репозитория на договора, по същия довод като {@link #findHealthRow}: това е прочит
   * от {@code bg_insurance_contribution} и минава през ЕДИНСТВЕНИЯ механизъм, който модулът има за
   * този каталог. Втора заявка за същите редове е свободна да се размине с първата.
   *
   * <p><b>⚠️ БЕЗ ФИЛТЪР ПО ПЕРИОД — нарочно и записано на две места.</b> Договорът се записва
   * веднъж и живее през месеци, тоест „валидни на днес“ би отказало договор, чиито ставки влизат в
   * сила другия месец. Периодът е въпрос на ФИША и се решава в {@link #calculate}. Същото важи и за
   * пазача от seed-а — {@code BgInsuredTypeHomeFileTest} също не филтрира по дата, и точно затова
   * двете страни дават един и същи отговор.
   *
   * @return подредените категории; ПРАЗЕН набор значи „каталогът мълчи за този код“, което {@link
   *     BgInsuredTypeCategoryRule} чете като липса на правило, не като забрана
   */
  public Set<String> laborCategoriesWithRates(String insuredTypeCode) {
    Set<String> out = new TreeSet<>();
    if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty()) {
      return out;
    }
    List<BgInsuranceContribution> rows =
        Beans.get(BgInsuranceContributionRepository.class)
            .all()
            .filter("self.bgInsuredTypeCode = :code")
            .bind("code", insuredTypeCode.trim())
            .fetch();
    for (BgInsuranceContribution row : rows) {
      String category = row.getLaborCategory();
      if (category != null && !category.trim().isEmpty()) {
        out.add(category.trim());
      }
    }
    return out;
  }

  /**
   * Resolves the ТЗПБ rate for an economic activity at a date, for use as {@code
   * BgContributionRequest.tzpbRate}. The stored rate is a percent number (0.9 means 0.9%).
   *
   * @return the rate, or {@code null} when no ТЗПБ row matches
   */
  public BigDecimal findTzpbRate(String kidCode, LocalDate date) {
    BgTzpbRate row =
        Beans.get(BgTzpbRateRepository.class)
            .all()
            .filter(
                "self.kidCode = :k AND self.validFrom <= :d "
                    + "AND (self.validTo IS NULL OR self.validTo >= :d)")
            .bind("k", kidCode)
            .bind("d", date)
            .fetchOne();
    return row == null ? null : row.getRate();
  }
}
