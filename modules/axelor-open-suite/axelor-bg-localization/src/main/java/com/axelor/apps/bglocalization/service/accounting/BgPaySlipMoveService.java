package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.google.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * СТАТИЯТА ОТ ФИША — счетоводната половина на последната миля.
 *
 * <p><b>⚠️ ТАЗИ УСЛУГА НЕ РЕШАВА КОЕ ПЕРО В КОЯ СМЕТКА.</b> Целият постинг модел живее в {@link
 * BgPayrollPostingPlan} — чиста функция, която се проверява с числа без база. Тук стоят само
 * нещата, които чистата функция не може: намирането на сметката по код в ТАЗИ фирма, четенето на
 * журнала от настройката, ПЕРИОДЪТ на документа, намирането на ретро-статията и записът.
 * Разделението не е козметично: статия, чиито сметки се избират в същия метод, който пипа
 * Hibernate, се сверява само на жива база — тоест на практика не се сверява.
 *
 * <p>═══ ЛИПСАТА Е БЕЛЕЖКА — ОСВЕН ДВЕ НАСТРОЙКИ ПРИ ИЗПЛАЩАНЕ ═══
 *
 * <p><b>⚠️ ПОЧТИ НИКОЯ ОТ ПРИЧИНИТЕ ДА НЯМА СТАТИЯ НЕ ОТКАЗВА ИЗПЛАЩАНЕТО.</b> Фишът е верен,
 * парите се дължат на човека, а повечето причини са в самия фиш или в чужд период. Причината се
 * ЗАПИСВА върху фиша ({@code bgPayrollMoveNote}) и се вижда на екрана — същият идиом като {@code
 * bgGeneratorGaps}.
 *
 * <p>Причините с бележка: няма фирма (СОЛ фиш без трудов договор), фирмата няма валута, няма период
 * (тоест няма и дата), планът отказва поне едно перо, журналът не приема типа на някоя сметка,
 * отложено перо без своя ретро-статия, корекция ПРЕЗ ГОДИНА, перата не дават нито един ред.
 * Затворен или липсващ фискален период е бележка само за ретро-статията; при изплащане е отказ (виж
 * долу).
 *
 * <p><b>⚠️ ДВЕ ПРИЧИНИ ОТКАЗВАТ {@code pay()} НА ГЛАС:</b> непопълнен {@code
 * AccountConfig.bgPayrollJournal} и сметка от {@link BgPayrollAccount}, която я няма в сметкоплана
 * на дружеството. И двете са настройка на ДРУЖЕСТВОТО, не на фиша, и важат за ВСЕКИ следващ фиш.
 * Бележка върху всеки от тях значи изплатена ведомост без нито една статия, която никой не
 * забелязва до приключването на месеца. Отказът назовава полето и екрана; поправката е една и после
 * всички фишове минават. Към тях се добавят фискалният период (липсващ или затворен на датата на
 * изплащане) и всеки провал на осчетоводяването — изплатен фиш значи осчетоводена статия.
 *
 * <p>⚠️ САМО ПРИ ИЗПЛАЩАНЕ. Ретро-фишът ражда статията си при ПОТВЪРЖДАВАНЕ, а потвърждаването носи
 * коригиращата Д1/Д6 — тя не се спира заради счетоводна настройка. Там двете причини остават
 * бележка.
 *
 * <p>═══ ИЗПЛАЩАНЕТО ОСЧЕТОВОДЯВА — КАКТО ФАКТУРАТА ПРИ ВАЛИДИРАНЕ ═══
 *
 * <p><b>⚠️ РЕДОВНАТА СТАТИЯ СЕ ОСЧЕТОВОДЯВА ОТ {@code MoveValidateService#accounting}, В СЪЩАТА
 * ТРАНЗАКЦИЯ С {@code pay()}.</b> Същият механизъм на AOS, с който фактурата ({@code
 * MoveCreateFromInvoiceServiceImpl}), разходът и плащанията осчетоводяват статията си: проверките
 * на статията, номер от поредицата на журнала и статус. Статусът го решава AOS, не този модул —
 * дружество в режим „дневник“ ({@code AccountConfig.accountingDaybook} и {@code
 * Journal.allowAccountingDaybook}) получава статия в „Дневник“, без номер, а другите —
 * „Осчетоводена“ с номер. Останала чернова, статията оставя изплатената ведомост с Д1/Д6, но без
 * разхода за заплати в оборотната ведомост, ОПР, SAF-T и ГОД.
 *
 * <p>⚠️ ПРОВАЛЪТ ВРЪЩА ИЗПЛАЩАНЕТО ЦЯЛО. Затворен или липсващ фискален период и всяка грешка на
 * {@code accounting} спират {@code pay()} с изречение: фишът остава потвърден, а черновата статия,
 * записана от {@code createMove} в същата транзакция, изчезва с нея. Висяща чернова до непроведено
 * изплащане би било същата грешка, само с обратен знак.
 *
 * <p>⚠️ РЕТРО-СТАТИЯТА (от {@code confirm()}) ОСТАВА ЧЕРНОВА. Тя сяда в коригирания месец, който
 * обичайно е приключен; осчетоводяването ѝ би спряло коригиращата Д1/Д6 заради счетоводна
 * настройка.
 *
 * <p>═══ ПЕРИОДО-ОСЪЗНАТА ═══
 *
 * <p><b>⚠️ ДАТАТА ИДВА ОТ ФИША, НЕ ОТ ДНЕС, И ТОВА Е ЦЯЛАТА РЕТРО-МЕХАНИКА.</b> Редовният фиш се
 * осчетоводява на датата на изплащане (по нея се дължат вноските и данъкът), а при липсваща — на
 * края на своя период. КОРИГИРАЩИЯТ фиш никога не се изплаща ({@code
 * BgPaySlipResolver#refusePayRetro}), тоест датата му е винаги краят на СВОЯ период — а неговият
 * период е коригираният месец. Оттам следва, без нито едно допълнително правило, че ретро-статията
 * сяда в коригирания месец, огледално на коригиращата Д1/Д6 за него.
 */
public class BgPaySlipMoveService {

  /**
   * ФУНКЦИОНАЛНАТА ПРИНАДЛЕЖНОСТ — {@code FUNCTIONAL_ORIGIN_PAYMENT}.
   *
   * <p><b>⚠️ ИЗБРАНА ОТ ТРИТЕ, КОИТО ЖУРНАЛЪТ ПОЗВОЛЯВА, а не измислена.</b> Журнал PAY носи {@code
   * authorizedFunctionalOriginSelect = "5,6,7"} — плащане, cut-off и дълготраен актив, — а {@code
   * MoveValidateServiceImpl#checkFunctionalOriginSelect} отказва статия извън списъка. От трите
   * единствената, която описва заплата, е 5. Оставена празна, проверката щеше да се пропусне изобщо
   * (методът излиза при 0), но статията щеше да е без принадлежност и да не се намира в нито една
   * справка, която филтрира по нея.
   */
  protected static final int FUNCTIONAL_ORIGIN = MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT;

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveRepository moveRepository;
  protected final AccountRepository accountRepository;

  /**
   * ⚠️ ПЕТА ЗАВИСИМОСТ, ДОБАВЕНА ЗА РЕТРО-МРЕЖАТА. Текущата статия трябва да ПРОВЕРИ, че
   * ретро-статията за коригирания период съществува, преди да пропусне перо 620 — а ретро-фишът се
   * намира по (договор, период, ретро-признак). Взета през {@code Beans.get} вътре в метода, тази
   * заявка нямаше да може да се подмени в тест без контейнер и цялата мрежа щеше да се проверява
   * само на жива база.
   */
  protected final BgPaySlipRepository paySlipRepository;

  /**
   * ⚠️ ШЕСТА ЗАВИСИМОСТ — ЗАРАДИ ЕДНО ХВЪРЛЯНЕ В ЧУЖДА УСЛУГА. {@code
   * MoveCreateServiceImpl#createMove} слага периода на статията с {@code
   * periodService.getActivePeriod(date, company, TYPE_FISCAL)}, а той ХВЪРЛЯ — за липсващ и за
   * ЗАТВОРЕН фискален период. Тоест статия с дата в затворен месец връща цялото {@code pay()} с
   * „Period 1“ и фишът не може да се изплати. За ретро-статията това е основният случай, не
   * крайният: коригираният месец често е точно затворен. Питано преди строенето, същото е обявена
   * причина.
   */
  protected final PeriodService periodService;

  /**
   * ⚠️ СЕДМА ЗАВИСИМОСТ — ОСЧЕТОВОДЯВАНЕТО НА AOS. Не собствен механизъм: {@code accounting} е
   * вратата, през която минават фактурата, разходът и плащанията — проверки, номер, дневник или
   * осчетоводена.
   */
  protected final MoveValidateService moveValidateService;

  @Inject
  public BgPaySlipMoveService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveRepository moveRepository,
      AccountRepository accountRepository,
      BgPaySlipRepository paySlipRepository,
      PeriodService periodService,
      MoveValidateService moveValidateService) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveRepository = moveRepository;
    this.accountRepository = accountRepository;
    this.paySlipRepository = paySlipRepository;
    this.periodService = periodService;
    this.moveValidateService = moveValidateService;
  }

  /**
   * Строи статията за този фиш, ако може. Записва я на {@code bgPayrollMove}, или обявената причина
   * на {@code bgPayrollMoveNote}.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНОСТТА Е ПЪРВИЯТ РЕД, ПРЕДИ ВСЯКО ЧЕТЕНЕ.</b> Повторно {@code pay()} — след
   * връщане от грешка, от двоен клик или от бъдещ пакетен режим — намира препратката попълнена и
   * излиза, без да пипа нищо. Проверката не може да е по-надолу: тя пази точно от втория запис, а
   * всичко под нея вече строи.
   */
  public void generate(BgPaySlip slip) throws AxelorException {
    if (slip == null || slip.getBgPayrollMove() != null) {
      return;
    }
    Company company = companyOf(slip);
    if (company == null) {
      note(
          slip,
          "Няма фирма: статията се записва в сметкоплана на дружеството, а то се чете от трудовия"
              + " договор (payCompany). Фиш на самоосигуряващо се лице няма договор и затова няма"
              + " и фирма — виж BgSelfInsuredRegistration, което не носи дружество.");
      return;
    }
    if (company.getCurrency() == null) {
      // ⚠️ ПИТА СЕ ТУК, А НЕ СЕ ОСТАВЯ НА AXELOR. MoveLineCreateServiceImpl прави
      // move.getCurrency().equals(...) без проверка, тоест дружество без валута дава ГОЛ NPE вътре
      // в чужда услуга — по време на изплащане на заплата, където никой не търси счетоводна
      // настройка. Изречението тук казва кое поле липсва.
      note(
          slip,
          "Дружеството „"
              + company.getName()
              + "“ няма валута, а статията се записва в нея. Попълва се в настройките на"
              + " дружеството.");
      return;
    }
    boolean retro = BgPaySlipResolver.isRetro(slip);
    Journal journal = payrollJournalOf(company);
    if (journal == null) {
      String reason = missingJournalReason(company);
      if (!retro) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            "%s",
            "Фишът не се изплаща: " + reason);
      }
      note(slip, reason);
      return;
    }
    if (dateOf(slip) == null) {
      // ⚠️ ПЕРИОДЪТ Е ЗАДЪЛЖИТЕЛЕН НА ФИША, но датата му на изплащане може да липсва при
      // коригиращо копие — и тогава краят на периода е единственото, което остава. И двете
      // празни значи, че документът няма кога да е бил, а статия без дата не сяда в никой месец.
      note(
          slip,
          "Фишът няма нито дата на изплащане, нито период с крайна дата — статията няма кога да"
              + " бъде. Датата решава В КОЙ МЕСЕЦ сяда разходът и е част от самата корекция за"
              + " минал период.");
      return;
    }

    if (retro) {
      generateRetro(slip, company, journal);
      return;
    }

    BgPayrollPostingPlan.Plan plan =
        BgPayrollPostingPlan.of(
            slip.getBgPaySlipLineList(), BgPaySlipResolver.insuredTypeCodeOf(slip));
    if (!plan.getRefusals().isEmpty()) {
      note(
          slip,
          "Статия не се генерира, защото "
              + plan.getRefusals().size()
              + " перо/пера нямат еднозначна сметка. ⚠️ ЦЯЛО ИЛИ НИЩО: пропуснато перо не разваля"
              + " баланса и затова частичната статия изглежда вярна, а е по-малка от истината."
              + System.lineSeparator()
              + String.join(System.lineSeparator(), prefixed(plan.getRefusals())));
      return;
    }
    String deferredRefusal = deferredRefusal(slip, plan);
    if (deferredRefusal != null) {
      note(slip, deferredRefusal);
      return;
    }
    if (!plan.isPostable()) {
      note(slip, "Перата на фиша не дават нито един счетоводен ред.");
      return;
    }

    List<String> codes = codesOf(plan.getPostings());
    String missing = missingAccountsReason(codes, company);
    if (missing != null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          "%s",
          "Фишът не се изплаща: " + missing);
    }
    String environment = environmentRefusal(codes, company, journal);
    if (environment != null) {
      note(slip, environment);
      return;
    }
    String fiscal = fiscalPeriodRefusal(company, dateOf(slip));
    if (fiscal != null) {
      // ⚠️ ОТКАЗ, НЕ БЕЛЕЖКА: изплатен фиш без осчетоводена статия не бива да съществува.
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, "%s", "Фишът не се изплаща: " + fiscal);
    }

    Move move = build(slip, company, journal, plan);
    post(move);
    slip.setBgPayrollMove(move);
    slip.setBgPayrollMoveNote(null);
  }

  /**
   * ОСЧЕТОВОДЯВАНЕТО — {@code MoveValidateService#accounting}, както при фактурата.
   *
   * <p>⚠️ ГРЕШКАТА СЕ ПРЕВЕЖДА В ИЗРЕЧЕНИЕ И СЕ ХВЪРЛЯ НАГОРЕ, НЕ СЕ ПРЕВРЪЩА В БЕЛЕЖКА. Тя връща
   * транзакцията на {@code pay()}: фишът остава потвърден, а черновата статия от {@code createMove}
   * не остава. Текстът на AOS е на езика на потребителя и назовава причината; отпред стои какво се
   * е случило с фиша. Шев, за да се подменя в тестовете без контейнер.
   */
  protected void post(Move move) throws AxelorException {
    try {
      moveValidateService.accounting(move);
    } catch (AxelorException e) {
      throw new AxelorException(
          e.getCategory(),
          "%s",
          "Фишът не се изплаща: статията по заплатата не може да се осчетоводи — "
              + e.getMessage()
              + " Нищо не е записано: фишът остава потвърден и статия не е създадена.");
    }
  }

  // ── ретро-режим (март-режим) ────────────────────────────────────────────

  /**
   * СТАТИЯТА НА КОРИГИРАЩИЯ (или заличаващия) ФИШ — делтата, в КОРИГИРАНИЯ период.
   *
   * <p><b>⚠️ ТУК Е ДУПКАТА, КОЯТО ЗАТВАРЯ ЦЕЛИЯ РЕТРО-ПОТОК.</b> До този клон коригиращият фиш НЕ
   * пораждаше статия НИКОГА — не по решение, а по устройство: генераторът се викаше само от {@code
   * pay()}, а {@code refusePayRetro} отказва изплащането на ретро-фиш още на първия ред. Тоест
   * корекцията хранеше коригираща Д1/Д6 и не влизаше в счетоводството НИКЪДЕ. Затова шевът се вика
   * и от {@code confirm()} за ретро-фишовете — единственото им състояние, което наистина настъпва.
   *
   * <p>⚠️ И ЗАТОВА перо 620 в ТЕКУЩИЯ фиш се изключва само СЛЕД проверка, че тази статия съществува
   * — виж {@link #deferredRefusal}. Изключено безусловно, то щеше да мълчи точно когато няма кой да
   * поеме разхода.
   */
  protected void generateRetro(BgPaySlip slip, Company company, Journal journal)
      throws AxelorException {
    boolean deletion = Boolean.TRUE.equals(slip.getBgIsDeletion());
    BgPaySlip original = deletion ? slip.getBgDeletionOf() : slip.getBgCorrectionOf();
    if (original == null) {
      note(
          slip,
          "Фишът е обявен за "
              + (deletion ? "заличаващ" : "коригиращ")
              + ", но не сочи оригинал. Делтата е разлика СПРЯМО осчетоводеното и без оригинал няма"
              + " от какво да се извади.");
      return;
    }
    if (original.getBgPayrollMove() == null) {
      // ⚠️ ДЕЛТАТА ПРЕДПОЛАГА, ЧЕ ОРИГИНАЛЪТ Е ОСЧЕТОВОДЕН. Ако не е — фишът е бил изплатен,
      // когато счетоводната настройка още я е нямало, — тогава разликата е ГРЕШНОТО число:
      // липсва цялата оригинална статия, не само разликата. Поправката е да се осчетоводи
      // първо оригиналът, и това се казва, а не се заобикаля.
      note(
          slip,
          "Оригиналният фиш "
              + reference(original)
              + " НЕ е осчетоводен (няма статия), а ретро-статията е РАЗЛИКА спрямо него. Проведена"
              + " така, тя щеше да внесе само делтата в книги, в които оригиналът никога не е"
              + " влизал. Причината за липсата стои върху самия оригинал:"
              + System.lineSeparator()
              + "· "
              + (original.getBgPayrollMoveNote() == null
                  ? "(без обявена причина)"
                  : original.getBgPayrollMoveNote()));
      return;
    }

    // ⚠️ ПРИ ЗАЛИЧАВАНЕ КОРИГИРАЩИЯТ ПЛАН Е ПРАЗЕН, А НЕ „плана на заличаващото копие“. Копието
    // носи числата на ОРИГИНАЛА (refuseAlteredDeletion го пази) — тоест делтата срещу него е нула,
    // а верният отговор е минус целият оригинал: записът трябва да изчезне.
    BgPayrollPostingPlan.Plan corrected =
        deletion
            ? null
            : BgPayrollPostingPlan.of(
                slip.getBgPaySlipLineList(), BgPaySlipResolver.insuredTypeCodeOf(slip));
    BgPayrollPostingPlan.Plan originalPlan =
        BgPayrollPostingPlan.of(
            original.getBgPaySlipLineList(), BgPaySlipResolver.insuredTypeCodeOf(original));

    List<String> refusals = new ArrayList<>();
    if (corrected != null) {
      refusals.addAll(corrected.getRefusals());
    }
    refusals.addAll(originalPlan.getRefusals());
    if (!refusals.isEmpty()) {
      note(
          slip,
          "Ретро-статия не се генерира, защото "
              + refusals.size()
              + " перо/пера нямат еднозначна сметка — в коригиращия или в оригиналния фиш. ⚠️ ЦЯЛО"
              + " ИЛИ НИЩО важи и за делтата: непълна разлика балансира и е по-малка от истината."
              + System.lineSeparator()
              + String.join(System.lineSeparator(), prefixed(refusals)));
      return;
    }

    BgPayrollRetroDelta delta = BgPayrollRetroDelta.of(corrected, originalPlan);
    if (delta.isEmpty()) {
      // ⚠️ НЕ Е ГРЕШКА И НЕ Е ПРОПУСК. Корекция може да смени декларацията, без да мени нито едно
      // салдо — разместване между две пера с една и същата сметка например. Тогава нетната делта
      // също е нула, перо 620 не се слага в текущия фиш и дупка няма.
      note(
          slip,
          "Корекцията не мени нито едно счетоводно салдо за периода — статия не е нужна."
              + " Коригиращата Д1/Д6 обаче се подава: декларацията и счетоводството отговарят на"
              + " различни въпроси.");
      return;
    }

    String environment = environmentRefusal(codesOf(delta.getPostings()), company, journal);
    if (environment != null) {
      note(slip, environment);
      return;
    }
    String fiscal = fiscalPeriodRefusal(company, dateOf(slip));
    if (fiscal != null) {
      note(slip, fiscal + RETRO_CLOSED_PERIOD_NOTE);
      return;
    }

    slip.setBgPayrollMove(buildRetro(slip, company, journal, delta, deletion));
    slip.setBgPayrollMoveNote(null);
  }

  /**
   * ФАЙЛ-ЛАУД МРЕЖАТА НА ОТЛОЖЕНИТЕ ПЕРА. Връща причината да няма статия, или {@code null}.
   *
   * <p><b>⚠️ ТИХОТО ИЗКЛЮЧВАНЕ Е ПО-ЛОШО ОТ ЛИПСВАЩАТА СТАТИЯ.</b> Перо с {@code bgCorrectsPeriod}
   * се маха от текущата статия, защото разходът му е в коригирания месец. Ако там статия НЯМА,
   * корекцията не се осчетоводява никъде: текущата статия балансира без нея (перото е едно и същото
   * от двете страни на изключването), тоест документът изглежда напълно сметнат, а книгите са
   * по-малки от ведомостта точно с делтата. Затова липсващата ретро-статия отменя ТЕКУЩАТА — докато
   * ретро-Move-ът не се закачи.
   *
   * <p><b>⚠️ И КОРЕКЦИЯТА ПРЕЗ ГОДИНА СЕ ОТКАЗВА ТУК.</b> Корекция на период от вече ЗАКЛЮЧЕНА
   * минала ГОДИНА не е март-режим: разходът не може да сяда в приключена година, а минава през 122
   * „Неразпределена печалба от минали години“ в ТЕКУЩИЯ период. Този режим не е построен и НЕ се
   * гади — отказва се поименно, за да не мине като март-режим.
   */
  protected String deferredRefusal(BgPaySlip slip, BgPayrollPostingPlan.Plan plan) {
    for (BgPayrollPostingPlan.Deferred deferred : plan.getDeferred()) {
      Period corrected = deferred.getPeriod();
      if (corrected == null) {
        return "Перо "
            + deferred.getLineNumber()
            + " е отложено за чужд период, но периодът е празен — тоест не се знае къде е разходът"
            + " му.";
      }
      if (crossesYear(slip.getPeriod(), corrected)) {
        return "Перо "
            + deferred.getLineNumber()
            + " коригира период „"
            + BgPaySlipResolver.periodLabel(corrected)
            + "“ от ДРУГА година. Този режим (корекция от заключена минала година → текущ период"
            + " през 122 „Неразпределена печалба от минали години“) НЕ е построен и не се гади:"
            + " днес се осчетоводява само корекция В РАМКИТЕ на годината, при която разходът сяда"
            + " в коригирания месец. Отказът е нарочен — проведена като март-режим, корекцията"
            + " щеше да мени приключена година.";
      }
      BgPaySlip retro = retroSlipFor(slip, corrected);
      if (retro == null || retro.getBgPayrollMove() == null) {
        return "Перо "
            + deferred.getLineNumber()
            + " („"
            + deferred.getLine().getName()
            + "“, "
            + deferred.getSignedAmount()
            + ") носи нетната делта за период „"
            + BgPaySlipResolver.periodLabel(corrected)
            + "“ и затова НЕ влиза в тази статия — разходът му е в ретро-статията за онзи месец. А"
            + " такава "
            + (retro == null
                ? "НЯМА: коригиращ фиш за онзи период не се намира."
                : "НЯМА: коригиращият фиш " + reference(retro) + " е без статия.")
            + " ⚠️ Изключено тихо, перото щеше да изчезне от счетоводството напълно — текущата"
            + " статия пак балансира и нищо не би подсказало, че делтата не е осчетоводена никъде."
            + " Затова статия не се строи, докато ретро-статията не се закачи.";
      }
    }
    return null;
  }

  /** Различни ли са годините на двата периода. И двете липсващи дати значат „не се знае“ → не. */
  protected boolean crossesYear(Period current, Period corrected) {
    LocalDate here = current == null ? null : current.getFromDate();
    LocalDate there = corrected == null ? null : corrected.getFromDate();
    return here != null && there != null && here.getYear() != there.getYear();
  }

  // ── самото построяване ──────────────────────────────────────────────────

  /**
   * Самото построяване — вече без нито едно „ако“: всичко е проверено отгоре.
   *
   * <p>⚠️ ДАТАТА Е ДАТАТА НА ИЗПЛАЩАНЕ, а при липсваща — краят на периода. Първата е фактът, който
   * поражда задължението за внасяне на вноските и данъка (те се дължат по датата на плащане), а
   * втората е единственото, което фишът знае винаги. Взета датата на СЪЗДАВАНЕ, статията щеше да
   * падне в месеца, в който операторът е отворил екрана.
   */
  protected Move build(
      BgPaySlip slip, Company company, Journal journal, BgPayrollPostingPlan.Plan plan)
      throws AxelorException {
    return write(slip, company, journal, plan.getPostings(), description(slip));
  }

  /** Ретро-статията: същият запис, друго описание и датата на КОРИГИРАНИЯ период. */
  protected Move buildRetro(
      BgPaySlip slip, Company company, Journal journal, BgPayrollRetroDelta delta, boolean deletion)
      throws AxelorException {
    String label =
        (deletion ? "Заличаване · период " : "Корекция · период ")
            + BgPaySlipResolver.periodLabel(slip.getPeriod())
            + " · фиш "
            + reference(slip);
    return write(slip, company, journal, delta.getPostings(), label);
  }

  /** Общият запис — един и същ за редовната и за ретро-статията. */
  protected Move write(
      BgPaySlip slip,
      Company company,
      Journal journal,
      List<BgPayrollPosting> postings,
      String description)
      throws AxelorException {
    LocalDate date = dateOf(slip);
    Partner partner = partnerOf(slip);
    String origin = slip.getReference();

    Move move =
        moveCreateService.createMove(
            journal,
            company,
            company.getCurrency(),
            partner,
            date,
            date,
            null,
            null,
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            FUNCTIONAL_ORIGIN,
            origin,
            description,
            null);

    List<MoveLine> lines = new ArrayList<>();
    int counter = 1;
    for (BgPayrollPosting posting : postings) {
      lines.add(
          moveLineCreateService.createMoveLine(
              move,
              partnerFor(posting, partner),
              accountFor(posting.getAccountCode(), company),
              posting.getAmount(),
              posting.isDebit(),
              date,
              counter++,
              origin,
              lineDescription(description, posting)));
    }
    // ⚠️ ДОБАВЯНЕ, НЕ ПОДМЯНА. createMove вече е ЗАПИСАЛ статията с управляван празен списък;
    // нов списък на негово място Hibernate отказва при flush („collection with
    // cascade="all-delete-orphan" was no longer referenced“) и връща цялото изплащане.
    lines.forEach(move::addMoveLineListItem);
    return moveRepository.save(move);
  }

  /**
   * ДАТАТА НА СТАТИЯТА — периодо-осъзната.
   *
   * <p>⚠️ ЕДИН МЕТОД ЗА ДВАТА ПОТОКА, И ТОВА Е ЦЯЛАТА „ПЕРИОДО-ОСЪЗНАТОСТ“. Редовният фиш идва с
   * дата на изплащане и статията сяда в месеца на плащането. Ретро-фишът НЕ се изплаща никога,
   * тоест {@code bgPaidDate} е празна и остава краят на периода — а неговият период е коригираният
   * месец. Отделно правило „ако е корекция, вземи периода“ би било същото нещо, написано два пъти.
   */
  protected LocalDate dateOf(BgPaySlip slip) {
    if (slip.getBgPaidDate() != null) {
      return slip.getBgPaidDate();
    }
    return slip.getPeriod() == null ? null : slip.getPeriod().getToDate();
  }

  /** Описанието на РЕДА — носи аналитичността, когато перото я има. */
  protected String lineDescription(String description, BgPayrollPosting posting) {
    return posting.getAnalytic() == null
        ? description
        : description + " · " + posting.getAnalytic();
  }

  // ── шевовете към средата ────────────────────────────────────────────────

  /**
   * Сметката по код в сметкоплана на ТАЗИ фирма, или {@code null}.
   *
   * <p>⚠️ ПО (код, фирма), защото точно това е уникалното ограничение върху {@code
   * account_account}. Търсене само по код връща сметката на ПЪРВОТО заредено дружество и статията
   * щеше да се запише в чужд сметкоплан — дефект, който на едно дружество не се вижда никога.
   */
  protected Account accountFor(String code, Company company) {
    return accountRepository
        .all()
        .filter("self.code = :code AND self.company = :company")
        .bind("code", code)
        .bind("company", company)
        .fetchOne();
  }

  /**
   * СРЕДАТА: липсва ли сметка, или журналът не приема типа ѝ. Връща причината, или {@code null}.
   *
   * <p><b>⚠️ ВТОРАТА ПОЛОВИНА НЕ Е ФОРМАЛНОСТ — БЕЗ НЕЯ {@code pay()} ХВЪРЛЯ.</b> {@code
   * MoveLineControlServiceImpl#controlAccountingAccount} се вика от {@code
   * MoveManagementRepository#save}, тоест ПРИ ЗАПИС, и отказва ред със сметка, чийто тип не е в
   * {@code validAccountTypeSet} на журнала (нито самата сметка в {@code validAccountSet}).
   * Изключението се вдига ВЪТРЕ в транзакцията на изплащането и връща цялото {@code pay()} — фиш,
   * който не може да се плати заради ред в чужд CSV. Открито тук, същото е обявена причина.
   *
   * <p>⚠️ И ПРАЗНИТЕ ДВА НАБОРА СЪЩО СА ОТКАЗ, макар да изглеждат като „няма ограничение“.
   * Проверката на Axelor тръгва от {@code isValid = false} и я вдига САМО при попадение — тоест
   * журнал без нито един позволен тип отказва ВСЯКА сметка. Пропуснато тук, това щеше да е точно
   * най-честата грешка в настройката, посрещната с изключение вместо с изречение.
   */
  protected String environmentRefusal(List<String> codes, Company company, Journal journal) {
    String missing = missingAccountsReason(codes, company);
    if (missing != null) {
      return missing;
    }
    List<String> rejected = new ArrayList<>();
    Set<String> allowedTypes = allowedAccountTypes(journal);
    Set<String> allowedCodes = allowedAccountCodes(journal);
    for (String code : codes) {
      Account account = accountFor(code, company);
      if (allowedCodes.contains(code)) {
        continue;
      }
      String type =
          account.getAccountType() == null
              ? null
              : account.getAccountType().getTechnicalTypeSelect();
      if (type == null || !allowedTypes.contains(type)) {
        rejected.add(code + " (" + (type == null ? "без тип" : type) + ")");
      }
    }
    if (!rejected.isEmpty()) {
      return "Журнал „"
          + journal.getCode()
          + "“ не приема "
          + rejected.size()
          + " от сметките на статията: "
          + String.join(", ", rejected)
          + ". Позволените типове се четат от Journal.validAccountTypeSet (в BG_COA —"
          + " account_journal.csv, колона validAccountTypes) и днес са: "
          + (allowedTypes.isEmpty() ? "НИТО ЕДИН" : String.join(", ", allowedTypes))
          + ". ⚠️ Записана така, статията щеше да хвърли в MoveManagementRepository#save и да върне"
          + " цялото изплащане.";
    }
    return null;
  }

  /**
   * Кои сметки от {@link BgPayrollAccount} ги няма в сметкоплана на дружеството. Връща причината с
   * кодовете и екрана, или {@code null}.
   *
   * <p>⚠️ Отделно от {@link #environmentRefusal}, защото двете се посрещат различно: при изплащане
   * липсващата сметка е ОТКАЗ (настройка на дружеството, а не на фиша), а журналът, който не приема
   * типа на сметката, остава бележка.
   */
  protected String missingAccountsReason(List<String> codes, Company company) {
    List<String> missing = new ArrayList<>();
    for (String code : codes) {
      if (accountFor(code, company) == null) {
        missing.add(code);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    return "липсват сметки в сметкоплана на „"
        + company.getName()
        + "“: "
        + String.join(", ", missing)
        + ". Статията от фиша ги търси по код в това дружество (BgPayrollAccount). Добавят се в"
        + " Счетоводство › Конфигурация › Финансов › Финансови сметки, за дружеството, с кода и"
        + " типа от BG_COA. Заредена ли е локализацията за това дружество?";
  }

  /** Причината при празен заплатен журнал — назовава полето и екрана. */
  protected String missingJournalReason(Company company) {
    return "заплатният журнал не е попълнен за „"
        + company.getName()
        + "“. Попълва се в конфигурацията на счетоводството на дружеството, панел „Заплати (BG)“,"
        + " поле „Заплатен журнал (BG)“ (AccountConfig.bgPayrollJournal). В BG_COA това е журнал"
        + " PAY „Заплати и осигуровки“. ⚠️ expenseJournal НЕ се ползва — той сочи MOM и обслужва"
        + " разходните отчети.";
  }

  /**
   * ФИСКАЛНИЯТ ПЕРИОД ЗА ТАЗИ ДАТА — има ли го и отворен ли е. Връща причината, или {@code null}.
   *
   * <p><b>⚠️ ДВЕ ПЕРИОДНИ СТЪЛБИ, И ТЕ НЕ СА ЕДНА И СЪЩА.</b> {@code BgPaySlip.period} е ЗАПЛАТЕН
   * период ({@code year.typeSelect = 2}), а статията иска ФИСКАЛЕН ({@code TYPE_FISCAL}) — Axelor
   * го намира сам по датата и фирмата. Тоест „периодът на фиша е ADJUSTING“ НЕ значи, че
   * счетоводният месец е отворен: заплатната година и финансовата година се затварят поотделно и от
   * различни хора.
   *
   * <p>⚠️ Шев, а не вградена заявка: {@code periodService} е {@code null} в тестовете без контейнер
   * и там проверката е ням изход — същият идиом като чистия изход в {@code
   * BgPaySlipService#generatePayrollMove}. Мери се с подменен шев.
   */
  protected String fiscalPeriodRefusal(Company company, LocalDate date) throws AxelorException {
    if (periodService == null || date == null) {
      return null;
    }
    Period fiscal = periodService.getPeriod(date, company, YearRepository.TYPE_FISCAL);
    if (fiscal == null) {
      return "За дата "
          + date
          + " няма ФИСКАЛЕН период в „"
          + company.getName()
          + "“. Статията не може да съществува без период, а заплатният период на фиша НЕ е той:"
          + " финансовата година (typeSelect = 1) се води отделно от заплатната (2).";
    }
    if (periodService.isClosedPeriod(fiscal)) {
      return "Фискалният период „"
          + fiscal.getName()
          + "“ (дата "
          + date
          + ") е ЗАТВОРЕН и статия в него не се записва. Периодът се отваря от счетоводителя във"
          + " финансовата година на дружеството, или се избира друга дата на изплащане.";
    }
    return null;
  }

  /** Обяснението, което ретро-бележката носи след причината за затворения период. */
  protected static final String RETRO_CLOSED_PERIOD_NOTE =
      " ⚠️ Това е нормалният случай при корекция за минал месец: разходът принадлежи на онзи"
          + " период, а онзи период е приключен. Изходите са два и двата са на счетоводителя —"
          + " отваряне на периода, или пренасяне на корекцията в текущия през 122 „Неразпределена"
          + " печалба от минали години“ (режим, който този модул НЕ построява и не гади).";

  /** Позволените ТИПОВЕ сметки на журнала, по технически код. Шев към базата. */
  protected Set<String> allowedAccountTypes(Journal journal) {
    Set<String> out = new LinkedHashSet<>();
    Set<AccountType> types = journal.getValidAccountTypeSet();
    if (types != null) {
      for (AccountType type : types) {
        if (type != null && type.getTechnicalTypeSelect() != null) {
          out.add(type.getTechnicalTypeSelect());
        }
      }
    }
    return out;
  }

  /** Поименно позволените сметки на журнала. Празно в BG_COA — разрешението там е по тип. */
  protected Set<String> allowedAccountCodes(Journal journal) {
    Set<String> out = new LinkedHashSet<>();
    Set<Account> accounts = journal.getValidAccountSet();
    if (accounts != null) {
      for (Account account : accounts) {
        if (account != null && account.getCode() != null) {
          out.add(account.getCode());
        }
      }
    }
    return out;
  }

  /**
   * КОРИГИРАЩИЯТ (или заличаващият) ФИШ за този период и този договор. Шев към базата.
   *
   * <p>⚠️ ТЪРСИ СЕ ПО (договор, период, ретро-признак), а не по препратка от перото: перото носи
   * само периода ({@code bgCorrectsPeriod}) — същият ключ, с който {@code settleRetroDelta} го е
   * сложило. Нова колона „към кой ретро-фиш сочи този ред“ би описала същото отношение втори път и
   * би могла да се разминава с първото.
   */
  protected BgPaySlip retroSlipFor(BgPaySlip slip, Period corrected) {
    EmploymentContract contract = slip.getEmploymentContract();
    if (contract == null || corrected == null) {
      return null;
    }
    if (paySlipRepository == null) {
      return null;
    }
    return paySlipRepository
        .all()
        .filter(
            "self.employmentContract = :contract AND self.period = :period"
                + " AND (self.bgIsCorrection = true OR self.bgIsDeletion = true)")
        .bind("contract", contract)
        .bind("period", corrected)
        .order("-id")
        .fetchOne();
  }

  /** Заплатният журнал от настройките, или {@code null}. Шев към базата. */
  protected Journal payrollJournalOf(Company company) {
    AccountConfig config = company.getAccountConfig();
    return config == null ? null : config.getBgPayrollJournal();
  }

  /** Дружеството, което плаща — от трудовия договор. {@code null} за фиш без договор. */
  protected Company companyOf(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    return contract == null ? null : contract.getPayCompany();
  }

  /**
   * ПАРТНЬОРЪТ НА ЕДИН РЕД — лицето, ОСВЕН по насрещниците на натурата.
   *
   * <p><b>⚠️ ТРИ СМЕТКИ НЕ НОСЯТ ПАРТНЬОРА НА СТАТИЯТА, И ТОВА НЕ Е КОЗМЕТИКА.</b> 409, 304 и 401
   * описват отношение към ДРУГ — оператора на ваучери, склада и доставчика, — а партньорът на
   * заплатната статия е ЛИЦЕТО. 401 е и с {@code useForPartnerBalance = true}: записан с партньора
   * на статията, кредитът щеше да вкара служителя в справката за задълженията към доставчици като
   * доставчик, на когото се дължи премията на застрахователя. Дефект, който не разваля баланса и не
   * се вижда в самата статия.
   *
   * <p>⚠️ {@code null} се ПРИЕМА от Axelor — {@code MoveLineControlServiceImpl#checkPartner} пада
   * само при партньор на реда, РАЗЛИЧЕН от този на статията, а не при липсващ. Проверено в кода.
   */
  protected Partner partnerFor(BgPayrollPosting posting, Partner slipPartner) {
    return BgPayrollAccount.isPartnerless(posting.getAccountCode()) ? null : slipPartner;
  }

  /**
   * Партньорът на статията — лицето, чрез {@code Employee.contactPartner}.
   *
   * <p>⚠️ {@code null} се ПРИЕМА, а не отказва: сметките, които Axelor би поискал да носят партньор
   * ({@code useForPartnerBalance}), се пишат БЕЗ него — виж {@link #partnerFor}. Партньорът тук
   * служи на четимостта и на бъдещото плащане, не на баланса.
   */
  protected Partner partnerOf(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    return employee == null ? null : employee.getContactPartner();
  }

  protected String description(BgPaySlip slip) {
    return "Заплати · фиш " + reference(slip);
  }

  protected void note(BgPaySlip slip, String text) {
    slip.setBgPayrollMoveNote(text);
  }

  private static String reference(BgPaySlip slip) {
    String reference = slip == null ? null : slip.getReference();
    return reference == null ? "(без номер)" : reference;
  }

  private static List<String> codesOf(List<BgPayrollPosting> postings) {
    Set<String> codes = new LinkedHashSet<>();
    for (BgPayrollPosting posting : postings) {
      codes.add(posting.getAccountCode());
    }
    return new ArrayList<>(codes);
  }

  private static List<String> prefixed(List<String> refusals) {
    List<String> out = new ArrayList<>();
    for (String refusal : refusals) {
      out.add("· " + refusal);
    }
    return out;
  }
}
