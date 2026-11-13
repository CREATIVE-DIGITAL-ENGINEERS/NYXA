package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import com.axelor.apps.hr.db.Employee;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ЕМИТЕРЪТ НА Д6: фишовете на едно дружество за един месец → ЕДНА декларация с осем числа.
 *
 * <p><b>⚠️ РАВЕНСТВОТО ПО ЧЛ. 2 АЛ. 4 СЕ ПРОВЕРЯВА, А НЕ СЕ ПОСТИГА ПО УСТРОЙСТВО.</b> „Всяко от
 * декларираните месечни задължения по ал. 2 т. 2 … трябва да бъде равно на изчислените задължителни
 * осигурителни вноски … от подадените декларация/и образец № 1.“ Ако Д6 се смяташе ОТ Д1,
 * равенството би било тъждество и нямаше да пази нищо. Затова Д6 се смята от ПЕРАТА, а {@link
 * #checkEquality} сравнява ДВА независими пътя.
 *
 * <p><b>⚠️ ЕДИН РЕД, НЕ ПО ЕДИН НА ЛИЦЕ.</b> Тук няма дъщерни записи и това не е опростяване: Д6 е
 * агрегат на ДРУЖЕСТВОТО. Заключването и подаването са per-company (решение по въпрос 9), точно
 * както при Д1.
 */
@Singleton
public class BgD6Emitter {

  /** Какво излезе от едно емитиране — декларацията и всичко, което трябва да види човек. */
  public static final class Outcome {
    private final List<BgD1FindingDto> findings = new ArrayList<>();

    public List<BgD1FindingDto> getFindings() {
      return findings;
    }

    public String findingReport() {
      StringBuilder sb = new StringBuilder();
      for (BgD1FindingDto f : findings) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append(f.describe());
      }
      return sb.toString();
    }
  }

  /**
   * Пълни декларацията от фишовете.
   *
   * @param declaration носителят — company и bgPeriod вече попълнени
   * @param slips фишовете на ДРУЖЕСТВОТО за месеца. ⚠️ ВСИЧКИТЕ, включително онези, за които Д1 се
   *     ОТКАЗВА: кодове 15, 88 и 89 се подават от НОИ по чл. 4 ал. 10 т. 1, но ВНОСКИТЕ за тях се
   *     дължат от работодателя и влизат в Д6. Филтър, повторен от Д1, би свалил задължение, което
   *     се плаща — и точно това е разликата между „кой подава данните за лицето“ и „кой дължи
   *     вноската“.
   */
  public Outcome emit(BgD6Declaration declaration, List<BgPaySlip> slips) {
    return emit(
        declaration,
        slips,
        BgD6CompensationLedger.of(
            declaration == null ? null : declaration.getBgPeriod(),
            slips,
            BgCompensationCalendar::sharesOf));
  }

  /**
   * Същото, с обезщетенията по вид 27 на месеца вече изведени ({@link BgD6CompensationLedger}).
   *
   * <p>⚠️ СУМИТЕ СА ОТ ФИШОВЕТЕ НА МЕСЕЦА, БЕЗ ПЕРАТА НА ВИД 27, ПЛЮС ЧАСТИТЕ ЗА МЕСЕЦА. Фишът носи
   * вноските върху ЦЯЛОТО обезщетение; т. 10 и т. 12 за месеца искат само частта му. Носителят от
   * друг месец ({@code BgEffectivePaySlips#compensationCarriersFor}) влиза САМО с частта си.
   */
  public Outcome emit(
      BgD6Declaration declaration, List<BgPaySlip> slips, BgD6CompensationLedger ledger) {
    Outcome outcome = new Outcome();
    if (declaration == null) {
      return outcome;
    }

    // ═══ СМЕСЕНИ НОСИТЕЛИ — ОТКАЗ, А НЕ НАХОДКА ═══
    // ⚠️ ЕДИН ФАЙЛ, ЕДНО ЗАДЪЛЖЕНО ЛИЦЕ. Указание т. 1 дава на осигурителя ЕИК, а на
    // самоосигуряващото се лице ЕГН — тоест сборът в една декларация би бил деклариран под чуждо
    // число. Тук няма верен изход, затова това е ОТКАЗ на цялата декларация, а не предупреждение
    // върху иначе произведени суми.
    String mixed = refuseMixedCarriers(declaration, slips);
    if (mixed != null) {
      // ⚠️ НАХОДКА СЪС ЗНАЧЕНИЕ „ОТКАЗ“, защото Outcome тук няма отделен списък за откази —
      // за разлика от BgD1Emitter.Outcome. Тежестта носи разликата, а ранното връщане я налага:
      // нито едно поле на декларацията не се пълни.
      outcome
          .getFindings()
          .add(new BgD1FindingDto("D6_MIXED_CARRIERS", BgD1FindingDto.SEVERITY_REFUSAL, mixed));
      return outcome;
    }

    // ═══ т. 1 / т. 2 — задълженото лице ═══
    // ⚠️ ДВА ИЗТОЧНИКА, И ТЕ НОСЯТ РАЗЛИЧЕН ВИД ЧИСЛО. Наредба Н-13, указания за Д6, т. 1:
    // осигурителят попълва ЕИК по БУЛСТАТ, а самоосигуряващото се лице и лицата по чл. 4 ал. 9 КСО
    // попълват ЕГН. Пренесено едно към едно от пътя на осигурителя, ЕГН-полето щеше да остане
    // празно, а декларацията — без подател.
    BgSelfInsuredRegistration registration = declaration.getBgSelfInsuredRegistration();
    if (registration != null) {
      fillSelfInsuredObligedPerson(declaration, registration, outcome);
    } else {
      Company company = declaration.getCompany();
      Partner partner = company == null ? null : company.getPartner();
      if (partner != null) {
        // ⚠️ registrationCode (ЕИК по БУЛСТАТ), НЕ taxNbr — същият капан като при т. 4 на Д1.
        declaration.setT1ObligedPersonCode(trim(partner.getRegistrationCode()));
        declaration.setT2Name(trim(partner.getName()));
      }
    }

    // ═══ т. 8.1 — ВИД ПЛАЩАНЕ, КОГАТО ДЕКЛАРАЦИЯТА Е ЗА СЪБИРАЧ НА ДИВОРАСТЯЩИ ГЪБИ И ПЛОДОВЕ ═══
    // ⚠️ ЕДИН КОД ЗА ДВЕТЕ НЕЩА, И ТОВА Е ДОСЛОВНО. Наредба Н-13, Приложение № 4, указание т. 8
    // буква „в“: „за дължимите осигурителни вноски за лицата, които извършват дейност по събиране
    // на диворастящи гъби и плодове, И ДАНЪК ПО ЧЛ. 41А от Закона за данъците върху доходите на
    // физическите лица, се попълва код - 7.“ Тоест вноските и данъкът на код 41 НЕ се разделят на
    // два вида плащане — както се разделят при всички останали (5 или 6 за вноските, 8 за данъка
    // по чл. 42).
    //
    // ⚠️ И ТОЧНО ЗАТОВА СЕ ПОПЪЛВА ТУК, А НЕ СЕ ОСТАВЯ НА ЧОВЕКА. Код 5 е верният избор за всеки
    // друг фиш и е първият в списъка; оставена празна, точката щеше да бъде попълнена с него по
    // навик. Декларация с код 5 обаче твърди „възнаграждение за месеца“ — а тук няма нито
    // възнаграждение, нито работодател, който да го е начислил.
    //
    // ⚠️ КОД 8 БИ БИЛ ГРЕШНИЯТ СЪСЕД: той е „за удържания по ЧЛ. 42“. Чл. 41а не е негова
    // разновидност — ал. 2 му дава друг удържащ (държавните предприятия по чл. 163 ал. 1 от Закона
    // за горите), а чл. 65 ал. 15 ЗДДФЛ — свой срок за внасяне.
    if (registration != null
        && BgSelfInsuredRules.alternativeTaxApplies(registration.getBgInsuredTypeCode())) {
      declaration.setT81PaymentKind(BgD6Point.PAYMENT_KIND_ART41A_FOREST);
    }

    List<BgPaySlip> own = ownSlips(declaration, slips);
    Map<String, BigDecimal> byPoint =
        new java.util.LinkedHashMap<>(BgD6Aggregator.byPointWithoutCompensationParts(own));
    if (ledger != null) {
      for (Map.Entry<String, BigDecimal> e : ledger.contributionsByPoint().entrySet()) {
        byPoint.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }
    declaration.setT10Doo(byPoint.get(BgD6Point.T10_DOO));
    declaration.setT11TeacherPf(byPoint.get(BgD6Point.T11_TEACHER_PF));
    declaration.setT12Upf(byPoint.get(BgD6Point.T12_UPF));
    declaration.setT13Ppf(byPoint.get(BgD6Point.T13_PPF));
    declaration.setT14Health(byPoint.get(BgD6Point.T14_HEALTH));
    declaration.setT15Gvrs(byPoint.get(BgD6Point.T15_GVRS));
    declaration.setT16Tax(byPoint.get(BgD6Point.T16_TAX));

    // ═══ т. 17 — ОБЯВЕНА ГРАНИЦА, но вече с ПОСОЧЕН КАНДИДАТ ═══
    // ⚠️ ДВЕ РАЗЛИЧНИ НЕЩА, ЕДНОТО ОПРЕДЕЛИМО, ДРУГОТО — НЕ. СУМАТА на частичните плащания е
    // определима: перата 600 и 610 са точно тя. ХИПОТЕЗАТА не е: чл. 42 ал. 6 се прилага само
    // когато през месеца са направени САМО частични плащания, а ал. 8 я ИЗКЛЮЧВА, ако пълният
    // размер е изплатен до 25-о число на СЛЕДВАЩИЯ месец — факт от БЪДЕЩЕТО спрямо фиша.
    // Затова числото се ПОКАЗВА, а точката остава празна. Попълнена автоматично, тя щеше да носи
    // данък, който при плащане до 25-о число НЕ се дължи, и щеше да се приспадне два пъти по ал. 7.
    BigDecimal partial = BgD6Aggregator.partialPayments(own);
    outcome
        .getFindings()
        .add(
            new BgD1FindingDto(
                    "D6_T17_NO_CARRIER",
                    BgD1FindingDto.SEVERITY_WARNING,
                    "т. 17 (авансов данък по чл. 42 ал. 6 ЗДДФЛ) остава празна: ХИПОТЕЗАТА „през"
                        + " месеца са направени САМО ЧАСТИЧНИ плащания“ няма носител — чл. 42 ал. 8"
                        + " я изключва, ако пълният размер е изплатен до 25-о число на СЛЕДВАЩИЯ"
                        + " месец, тоест зависи от факт СЛЕД този фиш."
                        + (partial.signum() == 0
                            ? " ⚠️ Фишовете на месеца НЕ носят пера за частично плащане (600/610),"
                                + " тоест кандидат за база няма — най-вероятно точката наистина не се"
                                + " полага."
                            : " ⚠️ КАНДИДАТЪТ ЗА БАЗА Е ИЗВЕСТЕН: сборът на перата 600/610 за месеца"
                                + " е "
                                + partial.toPlainString()
                                + ". Ал. 6 иска данъка по ал. 4 върху БРУТНИЯ размер на тази сума —"
                                + " попълни го на ръка, ако месецът е приключил само с частични"
                                + " плащания.")
                        + " ⚠️ И т. 16 НЕ се събира с т. 17: указанието казва „Не се включва данъкът"
                        + " по т. 17.“")
                .at(BgD6Point.T17_ADVANCE_TAX));

    return outcome;
  }

  /**
   * ЧЛ. 2 АЛ. 4 — сравнява Д6 срещу сумите на Д1, точка по точка.
   *
   * <p><b>⚠️ ЗДРАВНОТО СЕ ПРОВЕРЯВА ПО ДРУГ ПЪТ ОТ ОСТАНАЛИТЕ, и точно то е свидетелят на
   * дискриминатора.</b> Д6 т. 14 се получава през ФОНДА — всяко перо на HEALTH, независимо от
   * базата. Сборът от Д1 се получава през ТОЧКИТЕ (22.1 + 18 + 28). Двата пътя са различни и трябва
   * да дадат едно число; ако не дадат, някоя база е паднала извън трите — тоест вноската ѝ е
   * изчезнала от Д1, без да изчезне от Д6. Погрешно написан дискриминатор („ART40_15“ без
   * подчертавка) се хваща САМО тук.
   *
   * <p>⚠️ Записите на Д1 се подават, а не се четат от базата: емитерът на Д1 вече ги е построил в
   * паметта и второ четене е свободно да види друго състояние.
   *
   * @param records записите на Д1 за същото дружество и месец — за проверката на здравното
   */
  public List<BgD1FindingDto> checkEquality(
      BgD6Declaration declaration, List<BgPaySlip> slips, List<BgD1Record> records) {

    List<BgD1FindingDto> findings = new ArrayList<>();
    if (declaration == null) {
      return findings;
    }

    BigDecimal viaD1 = BgD6Aggregator.healthViaD1Points(ownSlips(declaration, slips));
    BigDecimal viaFund = declaration.getT14Health();
    if (viaD1.compareTo(viaFund) != 0) {
      findings.add(
          new BgD1FindingDto(
                  "D6_HEALTH_EQUALITY_BROKEN",
                  BgD1FindingDto.SEVERITY_ERROR,
                  "т. 14 на Д6 е "
                      + viaFund.toPlainString()
                      + ", а сборът на трите здравни точки на Д1 (т. 22.1 + т. 18 + т. 28) е "
                      + viaD1.toPlainString()
                      + ". Разлика: "
                      + viaFund.subtract(viaD1).toPlainString()
                      + ". ⚠️ ДВАТА ПЪТЯ СА РАЗЛИЧНИ НАРОЧНО: Д6 стига до т. 14 през ФОНДА (всяко"
                      + " перо на HEALTH), а Д1 — през БАЗАТА на перото. Разминаване значи, че перо"
                      + " носи база, която не е нито една от трите — например написана с грешен низ."
                      + " Вноската ѝ изчезва от Д1, без да изчезне от Д6, и чл. 2 ал. 4 не се"
                      + " затваря.")
              .at(BgD6Point.T14_HEALTH));
    }

    // ⚠️ РАВЕНСТВОТО ЗА ДОО НЕ СЕ ПРОВЕРЯВА СРЕЩУ Т. 22 НА Д1, И ТОВА НЕ Е ПРОПУСК: Д1 т. 22 е
    // СТАВКА (процент), а не сума, тоест няма какво да се сравни. Единственото сравнимо число там е
    // база × ставка — втора сметка, която този модул нарочно не прави. Проверката, която ИМА
    // смисъл,
    // е че т. 10 включва ТЗПБ: тя е единствената разлика в групирането, която мени СУМА.
    if (records != null && !records.isEmpty() && !isPositive(declaration.getT10Doo())) {
      findings.add(
          new BgD1FindingDto(
                  "D6_T10_EMPTY_WITH_D1_RECORDS",
                  BgD1FindingDto.SEVERITY_ERROR,
                  "Д1 носи "
                      + records.size()
                      + " записа, а т. 10 на Д6 (дължими вноски за ДОО) е празна. Вноски за"
                      + " фондовете на ДОО се дължат върху всеки осигурителен доход — тоест или"
                      + " фишовете нямат пера от 5xx, или групирането по фонд не е намерило нито"
                      + " един. ⚠️ Провери и ТЗПБ: за Д6 тя е ВЪТРЕ в т. 10, а за Д1 е отделна"
                      + " точка (22.2).")
              .at(BgD6Point.T10_DOO));
    }
    return findings;
  }

  private static boolean isPositive(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  /**
   * Видът плащане при обезщетения по вид 27 на месеца: ръчният избор се иска САМО когато има блок
   * на заплатите ({@link BgD6Blocks#hasSalaryBlock}). Д6 само с обезщетение (образецът 01.2025) има
   * блок с вид 6 по норма и блок с вид 8 — там 5/6 няма за какво да се отнася.
   */
  public static String paymentKindRefusal(
      BgD6Declaration declaration, BgD6CompensationLedger ledger) {
    if (declaration == null || !BgD6Blocks.hasSalaryBlock(declaration, ledger)) {
      return null;
    }
    return paymentKindRefusal(declaration);
  }

  /**
   * ФИШОВЕТЕ НА МЕСЕЦА — без носителите от други месеци. Те влизат в Д6 САМО с частта си от
   * обезщетение по вид 27 ({@link BgD6CompensationLedger}); заплатата, данъкът и здравното им са на
   * техния месец.
   */
  static List<BgPaySlip> ownSlips(BgD6Declaration declaration, List<BgPaySlip> slips) {
    List<BgPaySlip> out = new ArrayList<>();
    if (slips == null) {
      return out;
    }
    for (BgPaySlip slip : slips) {
      if (slip != null && BgD1Emitter.ownPeriod(slip, declaration.getBgPeriod())) {
        out.add(slip);
      }
    }
    return out;
  }

  /**
   * ПРИЧИНАТА ДЕКЛАРАЦИЯТА ДА НЕ СЕ ГЕНЕРИРА ЗАРАДИ ВИДА ПЛАЩАНЕ (т. 8.1), или {@code null}.
   *
   * <p><b>⚠️ 5 ИЛИ 6 ИЗБИРА ЧОВЕК, И ТОВА НЕ Е ВРЕМЕННО.</b> 5 е „изплатено през същия месец“, 6 —
   * „изплатено в по-късен месец“. Фишът носи НАЧИСЛЯВАНЕТО, а не плащането, тоест кодът няма от
   * какво да реши. Попълнен по навик с 5, видът би декларирал плащане, което може да не е станало.
   * Затова празното спира генерирането, вместо да излезе „0“ във файла.
   *
   * <p>⚠️ ПРИ ВИД 6 Т. 18 Е ЗАДЪЛЖИТЕЛНА: тя е датата на изплащане и от нея идват месецът и датата
   * на блок 2 (данъка). Без нея блок 2 няма месец — а месецът на периода би бил тихо грешен.
   *
   * <p>⚠️ Събирачът на гъби и плодове (код 7) НЕ се пита: видът му е по НОРМА и емитерът го попълва
   * сам.
   */
  public static String paymentKindRefusal(BgD6Declaration declaration) {
    if (declaration == null) {
      return null;
    }
    BgSelfInsuredRegistration registration = declaration.getBgSelfInsuredRegistration();
    if (registration != null
        && BgSelfInsuredRules.alternativeTaxApplies(registration.getBgInsuredTypeCode())) {
      return null;
    }
    String kind = trim(declaration.getT81PaymentKind());
    if (!BgD6Point.PAYMENT_KIND_SAME_MONTH.equals(kind)
        && !BgD6Point.PAYMENT_KIND_LATER_MONTH.equals(kind)) {
      return "Изберете т. 8.1 „Вид плащане“ на вноските: "
          + BgD6Point.PAYMENT_KIND_HINT
          + ". Изборът е ръчен — фишът не е вързан с реалното плащане. Данъкът (вид 8) не се"
          + " избира: файлът го пише сам в блок 2.";
    }
    if (BgD6Point.PAYMENT_KIND_LATER_MONTH.equals(kind)
        && declaration.getT18PaymentDate() == null) {
      return "Вид плащане 6 иска т. 18 — датата на изплащане: от нея идват месецът и датата на"
          + " блок 2 (данъка, вид 8). Попълнете я и генерирайте отново.";
    }
    return null;
  }

  /**
   * ПРИЧИНАТА ТАЗИ ДЕКЛАРАЦИЯ ДА СМЕСВА ДВА ВИДА ПОДАТЕЛИ, или {@code null}.
   *
   * <p><b>⚠️ ПРОВЕРЯВА СЕ ВЪРХУ ЦЕЛИЯ НАБОР, защото смесването е свойство на ФАЙЛА.</b> Същият
   * идиом като {@code BgD1CorrectionRules#refuseMixedCodes}: погледнат поотделно, всеки фиш е
   * допустим сам за себе си, и правилото няма как да се види от него.
   *
   * <p>⚠️ И ДВЕТЕ ПОСОКИ СЕ ОТКАЗВАТ. Декларация БЕЗ регистрация, съдържаща фиш на самоосигуряващо
   * се лице, би декларирала неговите вноски под ЕИК на дружеството. Декларация С регистрация,
   * съдържаща фиш на нает, би декларирала чужди вноски под ЕГН на едно лице. Второто е по-рядко и
   * по-скъпо: то приписва на човек задължение, което не е негово.
   */
  protected String refuseMixedCarriers(BgD6Declaration declaration, List<BgPaySlip> slips) {
    if (declaration == null || slips == null) {
      return null;
    }
    boolean declarationIsSelfInsured = declaration.getBgSelfInsuredRegistration() != null;
    List<String> wrongSide = new ArrayList<>();
    for (BgPaySlip slip : slips) {
      if (slip == null) {
        continue;
      }
      boolean slipIsSelfInsured = slip.getBgSelfInsuredRegistration() != null;
      if (slipIsSelfInsured != declarationIsSelfInsured) {
        wrongSide.add(trim(slip.getReference()));
      }
    }
    if (wrongSide.isEmpty()) {
      return null;
    }
    return "декларацията е за "
        + (declarationIsSelfInsured ? "САМООСИГУРЯВАЩО СЕ ЛИЦЕ" : "ОСИГУРИТЕЛ")
        + ", а носи "
        + wrongSide.size()
        + " фиша от другия вид: "
        + wrongSide
        + ". ⚠️ Указание т. 1 на Д6 дава на осигурителя ЕИК по БУЛСТАТ, а на самоосигуряващото се"
        + " лице — ЕГН. Един файл има ЕДНО задължено лице; смесени, сумите биха се декларирали под"
        + " чуждо число. Поправка: отделна декларация за всеки подател.";
  }

  /**
   * Т. 1 и Т. 2 при самоосигуряващото се лице — ЕГН и имена по документ за самоличност.
   *
   * <p>⚠️ ЕГН, А НЕ ЕИК, и това е дословно от указанието: „самоосигуряващите се лица и лицата по
   * чл. 4, ал. 9 от КСО попълват ЕГН (ЛН, ЛНЧ) или служебен номер, издаден от НАП“. ⚠️ И НЕ Е
   * {@code bgObligedPersonCode} НА РЕГИСТРАЦИЯТА: онова поле е за т. 4 на Д1 и при съдружник носи
   * ЕИК на дружеството. Две различни точки, две различни числа — заети едно за друго, декларацията
   * тръгва под грешен подател.
   */
  protected void fillSelfInsuredObligedPerson(
      BgD6Declaration declaration, BgSelfInsuredRegistration registration, Outcome outcome) {
    Employee employee = registration.getEmployee();
    String egn = employee == null ? null : trim(employee.getBgPersonalIdNumber());
    declaration.setT1ObligedPersonCode(egn);
    if (egn == null) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D6_T1_NO_PERSONAL_ID",
                      BgD1FindingDto.SEVERITY_ERROR,
                      "т. 1 остава празна: самоосигуряващото се лице няма попълнено ЕГН/ЛНЧ."
                          + " Указание т. 1 иска точно него, а не ЕИК — декларация без подател не"
                          + " се приема.")
                  .at("1"));
    }
    Partner person = employee == null ? null : employee.getContactPartner();
    if (person != null) {
      // ⚠️ Имена по документ за самоличност — указание т. 2, а не наименование на осигурител.
      String first = trim(person.getFirstName());
      String last = trim(person.getName());
      declaration.setT2Name(
          first == null ? last : (first + " " + (last == null ? "" : last)).trim());
    }
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
