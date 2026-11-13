package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.BgCompensationParts;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ОБЕЗЩЕТЕНИЯТА СТАВАТ ПЕРА — генераторът на група 9xx.
 *
 * <p><b>Границата на този енджин: НЕ СМЯТА НИЩО.</b> Сумата е ЗАМРАЗЕНА върху документа ({@code
 * BgCompensation.bgFinalAmount}) в мига на потвърждаването му и оттам само се КОПИРА. Това не е
 * пестене на код, а самото правило на документа: „Заповедта за прекратяване е подписана на
 * конкретна дата с конкретно число… Документ, който се преизчислява ретроактивно, е грешен по
 * определение“ — виж домейна на {@code BgCompensation}. Тоест ако някой ден тук се появи
 * аритметика, тя ще е второто мнение за число, което вече е подписано.
 *
 * <p><b>Как се намира типът перо — ПО ВИДА ОБЕЗЩЕТЕНИЕ, не по код.</b> {@code
 * BgPaySlipLineType.bgCompensationType} сочи към същия каталог, от който документът си е взел вида.
 * Тоест ключът е ОБЩАТА препратка, а не низ, съвпаднал на две места. Групата 9xx е ГЕНЕРИРАНА от
 * каталога (25 вида → 25 реда) и {@code BgPaySlipLineTypeCatalogueTest} пази съответствието едно
 * към едно; файловата половина на същото твърдение е {@code BgPaySlipLineTypeSeedMappingTest} и тя
 * върви без база.
 *
 * <p>⚠️ Оттам следва и че нов вид обезщетение НЕ иска промяна тук: щом каталогът и {@code
 * bg_pay_slip_line_type_compensation.csv} го носят, генераторът го намира сам.
 *
 * <p><b>⚠️ ФЛАГОВЕТЕ И ЗНАКЪТ ИДВАТ ОТ ТИПА, НЕ ОТ ДОКУМЕНТА.</b> Перото се строи през {@link
 * BgPaySlipService#lineFrom} — единственото място, където речникът се чете и замразява по копиране.
 * Двата флага на документа ({@code bgIsTaxable}, {@code bgSocialContributionsDue}) са СЪЩИТЕ,
 * копирани от същия каталог при потвърждаването му, и seed-ът го гарантира днес. Разминат ли се все
 * пак — документ, потвърден ПРЕДИ каталогът да се поправи — перото пак се записва по речника, а
 * разминаването се ОБЯВЯВА като {@link BgPaySlipLineOutcome.Status#DIVERGENT}. Виж javadoc-а на
 * състоянието защо нито отказ, нито мълчание е верният изход.
 *
 * <p>⚠️ И ЗНАКЪТ Е НА ТИПА, а не изведен от посоката на документа. Две от двайсет и петте пера са
 * DEBIT (чл. 221 ал. 2 и чл. 232 ал. 3 КТ — лицето дължи НА работодателя) и точно затова {@code
 * bgAmount} остава ПОЛОЖИТЕЛНА: „знакът е на перото, не на сумата“ (виж домейна на {@code
 * BgPaySlipLine}). Отрицателна сума с положителен знак дава същия сбор и различно четене — а фишът
 * се чете от човек.
 */
public class BgCompensationLineService {

  // ═══ ГЕНЕРАТОРЪТ ════════════════════════════════════════════════════════

  /**
   * ЗАПИСВА ПО ЕДНО ПЕРО ЗА ВСЯКО ПОТВЪРДЕНО ОБЕЗЩЕТЕНИЕ в този период и връща какво е станало с
   * всяко.
   *
   * <p><b>Върнатият списък е част от договора, не отпадък.</b> Той носи и отказите — а те са
   * единственото, което различава „обезщетение няма“ от „обезщетението не можа да стане перо“.
   * Изхвърли ли го повикващият, вторият случай става неразличим от първия и лицето тихо остава без
   * пари, които са му признати с подписан документ.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО, И ТО ПО ДРУГ КЛЮЧ ОТ ОСТАНАЛИТЕ ГЕНЕРАТОРИ.</b> Начисленията и
   * болничният търсят перото си по КОДА НА ТИПА, защото на един фиш може да има само едно основно
   * възнаграждение. Тук не е така: два РАЗЛИЧНИ документа от един и същи вид могат да са на един
   * фиш (поправката на обезщетение става чрез отказ и НОВ документ — виж домейна). Затова ключът е
   * ПРЕПРАТКАТА КЪМ ДОКУМЕНТА ({@code BgPaySlipLine.bgCompensation}), а не кодът: по код второто
   * обезщетение щеше да ПРЕПИШЕ първото и едно от двете щеше да изчезне тихо.
   *
   * <p><b>⚠️ АДИТИВНО.</b> Нищо не се трие и нищо чуждо не се пипа — генераторът докосва САМО
   * редовете със своята препратка. Ръчно въведените пера трябва да преживеят всяко {@code
   * prepare()}.
   *
   * <p><b>⚠️ КОРИГИРАЩ И ЗАЛИЧАВАЩ ФИШ СЕ ПРОПУСКАТ</b>, по същия довод като при начисленията и
   * болничния: ретро-копието вече НОСИ перата на оригинала, включително препратките към документите
   * ({@code BgPaySlipService#copyOf}). Пуснат върху него, генераторът щеше да ги намери по
   * препратката и да ги „обнови“ с ДНЕШНАТА сума на документа — тоест да пренапише историята точно
   * там, където ретро-механизмът съществува, за да не я пренаписва.
   *
   * @param slip фишът, върху който се пише. Само в статус ЧЕРНОВА
   * @param input входът по § 13.1 — ЕДИНСТВЕНИЯТ източник за това КОИ документи влизат в този фиш
   */
  public List<BgPaySlipLineOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
    if (!isEligibleDraft(slip)) {
      return Collections.emptyList();
    }
    List<BgPaySlipLineOutcome> outcomes = new ArrayList<>();
    for (BgCompensation doc : documents(input)) {
      // ⚠️ РЕЧНИКЪТ СЕ ПИТА ВЕДНЪЖ ЗА ДОКУМЕНТ. Типът е нужен и на отговора (за кода му), и на
      // перото (за флаговете), затова се намира ТУК и се подава на двете. Две търсения за един
      // документ са два прочита на едно и също нещо и са свободни да се разминат.
      BgPaySlipLineType type = lineTypeOrRefuse(doc);
      BgPaySlipLineOutcome outcome = outcomeFor(doc, type);
      outcomes.add(outcome);
      if (outcome.isComputed()) {
        upsert(slip, doc, type, outcome);
      }
    }
    return outcomes;
  }

  /**
   * РЕДЪТ НА РЕЧНИКА за този документ, или {@code null}, когато самият документ няма вид.
   *
   * <p>⚠️ ЛИПСВАЩИЯТ РЕД Е ИЗКЛЮЧЕНИЕ, не отказ-в-списъка: това НЕ е дефект на документа, а дупка в
   * seed-а, и тя засяга ВСЕКИ фиш с такъв вид. Мълчаливо прескачане би оставило признато
   * обезщетение неизплатено; отказ в списъка щеше да сочи документа, а поправката е във файла с
   * данни. Същият избор като {@code BgEarningsService#upsert} и {@code BgSickLeaveService#upsert}.
   *
   * <p>⚠️ Липсващият ВИД, обратно, е дефект на документа и се връща като {@code null}, за да стане
   * отказ, който НАЗОВАВА номера му.
   *
   * <p><b>⚠️ ТУК РЕЧНИКЪТ СЕ ПИТА ПРЕДИ СУМАТА, А ПРИ ОСИГУРОВКИТЕ — СЛЕД НЕЯ. Асиметрията е
   * НАРОЧНА.</b> При {@code BgContributionLineService} нулева вноска е ЗАКОННА нула: тя по правило
   * не прави ред, тоест речникът не бива да се пита за нея — инак дупка в seed-а щеше да се обажда
   * за перо, което и без това не се създава. Тук нула е ОТКАЗ: документът е подписан и сумата му е
   * трябвало да бъде замразена. Тоест дефект има и в двата случая, а дупката в seed-а е
   * по-фундаменталната от двете и се съобщава първа.
   */
  protected BgPaySlipLineType lineTypeOrRefuse(BgCompensation doc) {
    BgCompensationType kind = doc.getBgCompensationType();
    if (kind == null) {
      return null;
    }
    BgPaySlipLineType type = lineTypeFor(kind);
    if (type == null) {
      // Речникът няма ред за този вид — App Install не е пускан след добавянето му в каталога.
      throw new IllegalStateException(
          "видът обезщетение „"
              + kind.getCode()
              + "“ няма ред в речника с типове пера (bg_pay_slip_line_type_compensation.csv) —"
              + " обезщетението „"
              + reference(doc)
              + "“ не може да стане перо, а безтипов ред НЕ се създава. Пусни App Install"
              + ".");
    }
    return type;
  }

  /**
   * КАКВО БИ СТАНАЛО С ЕДИН ДОКУМЕНТ — без да се пише нищо.
   *
   * <p>⚠️ ТРИТЕ ОТКАЗА НАЗОВАВАТ ДОКУМЕНТА. Отказ без номер е безполезен: фишът може да носи
   * няколко обезщетения и „сумата липсва“ не казва на кое.
   *
   * @param type редът на речника, вече намерен от {@link #lineTypeOrRefuse}, или {@code null}
   */
  protected BgPaySlipLineOutcome outcomeFor(BgCompensation doc, BgPaySlipLineType type) {
    String reference = reference(doc);
    if (type == null) {
      // Документът е required по схема, тоест това е недописан обект от тест или от контролер.
      return BgPaySlipLineOutcome.refused(
          reference,
          "обезщетението „"
              + reference
              + "“ няма вид — без него типът перо не може да се намери, а безтипов ред би се"
              + " отпечатал без име, без номер и без знак.");
    }
    // ⚠️ signum(), НЕ == null. Генерираният getter за decimal връща ZERO, тоест „още не е
    // попълнено“ се пише като „е нула“ — проверка за null тук е МЪРТЪВ КОД (виж
    // BgDefaultedGetterTest).
    BigDecimal amount = doc.getBgFinalAmount();
    if (amount.signum() <= 0) {
      return BgPaySlipLineOutcome.refused(
          type.getCode(),
          "обезщетението „"
              + reference
              + "“ е потвърдено със сума за изплащане "
              + amount.toPlainString()
              + " — перо НЕ се създава. ⚠️ Нула тук НЕ е законна нула: документът е подписан, тоест"
              + " сумата е трябвало да бъде замразена при потвърждаването му. Отрицателна сума също"
              + " се отказва — посоката е на ЗНАКА на перото ("
              + type.getBgSign()
              + "), не на числото.");
    }
    String divergence = divergence(doc, type, reference);
    if (divergence != null) {
      return BgPaySlipLineOutcome.divergent(type.getCode(), amount, null, null, divergence);
    }
    return BgPaySlipLineOutcome.computed(type.getCode(), amount, null, null);
  }

  /**
   * РАЗМИНАВАНЕТО МЕЖДУ ДОКУМЕНТА И РЕЧНИКА, или {@code null}, ако няма.
   *
   * <p>⚠️ Двата флага са ЕДНО И СЪЩО нещо под две имена: {@code
   * BgPaySlipLineType.bgIsInsuranceIncome} и {@code BgCompensation.bgSocialContributionsDue}.
   * Имената идват от различни страни — речникът гледа перото, документът гледа задължението — но и
   * двете отговарят на въпроса „дължат ли се вноски върху тази сума“. Днес seed-ът ги държи еднакви
   * за всичките 25 вида (заковано във файловата половина); това тук пази деня, в който единият
   * източник се поправи, а другият — не.
   */
  protected String divergence(BgCompensation doc, BgPaySlipLineType type, String reference) {
    List<String> found = new ArrayList<>();
    if (!sameFlag(type.getBgIsTaxable(), doc.getBgIsTaxable())) {
      found.add(
          "облагаемост по ЗДДФЛ: речникът казва "
              + type.getBgIsTaxable()
              + ", документът — "
              + doc.getBgIsTaxable());
    }
    if (!sameFlag(type.getBgIsInsuranceIncome(), doc.getBgSocialContributionsDue())) {
      found.add(
          "осигурителен доход: речникът казва "
              + type.getBgIsInsuranceIncome()
              + ", документът (bgSocialContributionsDue) — "
              + doc.getBgSocialContributionsDue());
    }
    if (found.isEmpty()) {
      return null;
    }
    return "обезщетението „"
        + reference
        + "“ е потвърдено с друго третиране от това в речника ("
        + type.getCode()
        + "): "
        + String.join("; ", found)
        + ". ⚠️ ПЕРОТО СЕ ЗАПИСВА ПО РЕЧНИКА — той е правилото и е сверен с каталога на"
        + " обезщетенията. Документът е замразил своето при потвърждаването си, тоест разминаването"
        + " значи, че каталогът се е променил след това. Провери кое от двете е вярно за ТОЗИ месец:"
        + " данъчното и осигурителното третиране решават и данъчната основа, и Д1.";
  }

  /** Чернова, която НЕ е коригираща и НЕ е заличаваща — единственото, върху което се пише. */
  protected boolean isEligibleDraft(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return false;
    }
    return !Boolean.TRUE.equals(slip.getBgIsCorrection())
        && !Boolean.TRUE.equals(slip.getBgIsDeletion());
  }

  /**
   * Записва перото — ново или обновено. ⚠️ Ключът е ПРЕПРАТКАТА КЪМ ДОКУМЕНТА, не кодът на типа.
   *
   * <p>⚠️ Препратката се слага ВЕДНАГА след създаването на реда, преди закачането: тя Е ключът на
   * идемпотентността и ред без нея би се създал втори път при всяко {@code prepare()}.
   *
   * <p>⚠️ ТИПЪТ СЕ ПОДАВА, не се търси пак: {@link #generate} вече го е намерил.
   */
  protected void upsert(
      BgPaySlip slip, BgCompensation doc, BgPaySlipLineType type, BgPaySlipLineOutcome outcome) {
    BgPaySlipLine line = lineWithCompensation(slip, doc);
    if (line == null) {
      line = lineFrom(type, outcome.amount);
      line.setBgCompensation(doc);
      addLine(slip, line);
    }
    line.setBgAmount(outcome.amount);
  }

  /**
   * Перото на този фиш за ТОЗИ документ, или {@code null}.
   *
   * <p>⚠️ Сравнява се по ОБЕКТ, а при запазен документ — и по идентификатор. Само по идентификатор
   * не става: при незапазен фиш и незапазен документ идентификатор още няма, а „кое перо е на кой
   * документ“ трябва да е известно и тогава. Само по обект също не става: {@code prepare()} чете
   * документите наново от базата, тоест втория път идват ДРУГИ инстанции на същия ред.
   */
  protected BgPaySlipLine lineWithCompensation(BgPaySlip slip, BgCompensation doc) {
    if (slip.getBgPaySlipLineList() == null) {
      return null;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      // ⚠️ САМО ПЕРОТО 9xx: вноските върху обезщетение по вид 27 носят СЪЩАТА препратка — намерена
      // първа, вноската щеше да получи сумата на обезщетението.
      BgCompensation carried =
          BgCompensationParts.isCompensationLine(line) ? line.getBgCompensation() : null;
      if (carried == null) {
        continue;
      }
      if (carried == doc) {
        return line;
      }
      if (carried.getId() != null && carried.getId().equals(doc.getId())) {
        return line;
      }
    }
    return null;
  }

  /** Номерът на документа за съобщенията, или обяснение защо го няма. */
  protected String reference(BgCompensation doc) {
    String reference = doc.getReference();
    if (reference != null && !reference.trim().isEmpty()) {
      return reference.trim();
    }
    return doc.getId() == null ? "без номер" : "без номер (id " + doc.getId() + ")";
  }

  /**
   * Съвпадат ли двата флага. Генерираните getter-и за {@code Boolean} никога не връщат {@code
   * null}.
   */
  private static boolean sameFlag(Boolean fromType, Boolean fromDocument) {
    return Boolean.TRUE.equals(fromType) == Boolean.TRUE.equals(fromDocument);
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * ПОТВЪРДЕНИТЕ документи от входа, по реда си.
   *
   * <p>⚠️ Статусът се пита ПАК, макар {@code BgPayrollInputBuilder#compensationDocumentIds} вече да
   * филтрира по него. Това не е второ копие на правило, а второ четене на едно поле — и разликата е
   * във времето: входът се сглобява веднъж, а между сглобяването и записването на перата документът
   * може да е бил ОТКАЗАН. Тук се пишат пари; отказан документ не бива да стане перо, дори когато е
   * бил потвърден преди минута.
   *
   * <p>⚠️ Празният списък дава празна заявка, а не {@code IN ()} — Hibernate отказва второто.
   */
  protected List<BgCompensation> documents(BgPayrollInput input) {
    if (input == null
        || input.compensationDocumentIds == null
        || input.compensationDocumentIds.isEmpty()) {
      return Collections.emptyList();
    }
    return JPA.em()
        .createQuery(
            "SELECT c FROM BgCompensation c WHERE c.id IN :ids"
                + " AND c.statusSelect = :confirmed ORDER BY c.id",
            BgCompensation.class)
        .setParameter("ids", input.compensationDocumentIds)
        .setParameter("confirmed", BgCompensationResolver.CONFIRMED)
        .getResultList();
  }

  /**
   * Редът на речника за ТОЗИ вид обезщетение, или {@code null}.
   *
   * <p>⚠️ Само активните: закрит тип перо е бил валиден за минали периоди и остава видим там, но
   * нов фиш не бива да го носи. Същият филтър като {@code BgEarningsService#lineTypes}.
   */
  protected BgPaySlipLineType lineTypeFor(BgCompensationType kind) {
    if (kind == null || kind.getId() == null) {
      return null;
    }
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter("self.bgCompensationType.id = :kind AND self.active IS TRUE")
        .bind("kind", kind.getId())
        .fetchOne();
  }

  /**
   * Ново перо от речника — ПРЕЗ {@code BgPaySlipService#lineFrom}, за да остане ЕДНО мястото,
   * където речникът се чете и замразява по копиране. Второ копие на това правило е свободно да
   * пропусне флаг — а тук флаговете СА правилото.
   */
  protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
    return Beans.get(BgPaySlipService.class).lineFrom(type, amount);
  }

  /** Закачането от двете страни на връзката — o2m с mappedBy не се пълни сам. */
  protected void addLine(BgPaySlip slip, BgPaySlipLine line) {
    if (slip.getBgPaySlipLineList() == null) {
      slip.setBgPaySlipLineList(new ArrayList<>());
    }
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }
}
