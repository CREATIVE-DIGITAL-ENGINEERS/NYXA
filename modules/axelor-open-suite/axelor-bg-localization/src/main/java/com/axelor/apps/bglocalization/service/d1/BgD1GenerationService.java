package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository;
import com.axelor.apps.bglocalization.service.BgEffectivePaySlips;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ГЕНЕРИРАНЕТО НА ЗАПИСИТЕ ЗА ЕДНА Д1 — изнесено от контролера, за да е ПРОВЕРИМ РЕДЪТ.
 *
 * <p><b>ЗАЩО УСЛУГА, А НЕ МЕТОД НА КОНТРОЛЕРА.</b> Тялото беше в {@code
 * BgD1DeclarationController#generateFor} и стигаше до базата с четири {@code Beans.get} — тоест
 * недостижимо за свидетел без инсталация. А твърдението, което иска пазене, е точно РЕДЪТ:
 * <b>отказът е ПРЕДИ изтриването</b>. Изтриването е {@code delete()} по идентификатор на родителя
 * върху ВЕЧЕ ПОДАДЕНА декларация; сложен след него, пазачът щеше да я остави празна и чак тогава да
 * се оплаче. Това не е нещо, което се доказва с четене.
 *
 * <p>⚠️ Шевовете са {@code protected} по образеца на {@code BgPaySlipService}: така целият ред се
 * проверява без Postgres и без Guice, а производственият път остава непокътнат.
 */
public class BgD1GenerationService {

  /**
   * ФИШОВЕТЕ, КОИТО ЕДНА ДЕКЛАРАЦИЯ НОСИ — по ПЕРИОД, по НОСИТЕЛ и по ВИД.
   *
   * <p><b>⚠️ РЕДОВНАТА ЧЕТЕ ДЕЙСТВАЩИЯ ФИШ — ПРЕЗ СЪЩИЯ МЕТОД КАТО Д6</b> ({@link
   * BgEffectivePaySlips#effectiveFor}). Коригиращото копие носи СЪЩИЯ период като оригинала и го
   * ЗАМЕНЯ; редовна, генерирана преди подаването, трябва да носи копието, не оригинала. Със свои
   * заявки („всички без ретро“ срещу „всички“) двете декларации за един месец се разминават.
   *
   * <p>⚠️ КОРИГИРАЩАТА чете ретро-копията, които редовната още НЕ носи ({@link
   * BgEffectivePaySlips#correctingFor}) — иначе корекция, влязла в редовната преди подаването, би
   * стигнала до НАП втори път като „К“.
   *
   * <p>⚠️ {@code BgPaySlipLine.bgCorrectsPeriod} НЕ участва и не бива: той е на ПЕРОТО на ТЕКУЩИЯ
   * месец (делтата) и сочи НАЗАД към коригирания период. Филтър по него би търсил ретро-фиша в
   * грешния месец.
   */
  protected List<BgPaySlip> slipsFor(BgD1Declaration declaration) {
    if (BgD1DeclarationKind.isCorrecting(declaration)) {
      return BgEffectivePaySlips.correctingFor(
          declaration.getBgDataPeriod(),
          declaration.getCompany(),
          declaration.getBgSelfInsuredRegistration());
    }
    List<BgPaySlip> effective =
        BgEffectivePaySlips.effectiveFor(
            declaration.getBgDataPeriod(),
            declaration.getCompany(),
            declaration.getBgSelfInsuredRegistration());
    // ⚠️ ЧАСТИТЕ НА ОБЕЗЩЕТЕНИЕ ПО ВИД 27 СЕ ДЕКЛАРИРАТ В МЕСЕЦА СИ, а фишът, който ги носи, може
    // да е от ДРУГ месец — затова и носителите (BgEffectivePaySlips#compensationCarriersFor).
    List<BgPaySlip> carriers =
        BgEffectivePaySlips.compensationCarriersFor(
            declaration.getBgDataPeriod(),
            declaration.getCompany(),
            declaration.getBgSelfInsuredRegistration());
    if (BgD1DeclarationKind.isSupplementary(declaration)) {
      List<BgPaySlip> withParts = new ArrayList<>();
      for (BgPaySlip slip : BgD6GenerationService.withCompensationCarriers(effective, carriers)) {
        if (!BgCompensationParts.carried(slip).isEmpty()) {
          withParts.add(slip);
        }
      }
      return withoutDeclaredParts(declaration, withParts);
    }
    return BgD6GenerationService.withCompensationCarriers(effective, carriers);
  }

  /**
   * ЗАМЯНА, НЕ ДОПЪЛВАНЕ — и редът на трите стъпки е твърдение, не подредба.
   *
   * <p>Редът е: прочети фишовете → <b>произведи</b> → изтрий старите → запиши новите. Емитерът може
   * да откаже фиш или да хвърли; изтрити преди него, записите на предишното генериране изчезват
   * заради провал, който изобщо не ги засяга — а върху ВЕЧЕ ПОДАДЕНА декларация това значи месец
   * без записи срещу файл, който е в НАП.
   *
   * <p><b>⚠️ ПАЗАЧЪТ СРЕЩУ ПРЕЗАПИСВАНЕ НА ПОДАДЕНОТО Е ТУК, И Е СПИСЪК НА ПОЗВОЛЕНОТО.</b> Отказ
   * на набор, в който едно лице има и редовен, и ретро-фиш, би затворил ЕДИНСТВЕНИЯ изход, който
   * сам сочи — коригиращата декларация. Затова разграничителят е ВИДЪТ на декларацията ({@link
   * BgD1DeclarationKind}): редовната носи записи БЕЗ код, коригиращата — само с код, тоест смесен
   * файл НЕ се ражда, а {@link #refuseRegularRegeneration} пуска пренаписването на редовна
   * декларация само когато НЯМА подаване или последното е отхвърлено/с техническа грешка ({@link
   * BgD1RegenerationRules}). Находката {@code D1_MIXED_CODES} ({@code SEVERITY_ERROR}, {@code
   * BgD1Emitter}) остава като допълнителен пазач.
   *
   * <p>⚠️ И ОБЯВЕНАТА ГРАНИЦА: подаването НЕ спира на {@code SEVERITY_ERROR} ({@code
   * BgD1SubmissionService}), тоест находка все още не е спирачка. Отделна задача, защото важи и за
   * Д6.
   */
  @Transactional
  public BgD1Emitter.Outcome generate(Long declarationId) {
    BgD1Declaration declaration = declarations().find(declarationId);

    // ⚠️ ОТКАЗИТЕ СА ПРЕДИ ИЗТРИВАНЕТО, И РЕДЪТ Е ЦЯЛАТА ИМ СТОЙНОСТ. По-долу delete() маха
    // записите на декларацията; спрян случай не бива да е струвал нищо.
    refuse(BgD1DeclarationKind.refuseCorrectingTarget(declaration));
    refuse(refuseRegularRegeneration(declaration));
    refuse(refuseCorrectingBeforeAccepted(declaration));
    refuse(refuseSupplementary(declaration));

    List<BgPaySlip> slips = slipsFor(declaration);
    BgD1Emitter.Outcome outcome = emitter().emit(declaration, slips);
    outcome.getFindings().addAll(partsForFiledMonths(declaration, slips));
    deleteRecordsOf(declarationId);
    for (BgD1Record record : outcome.getRecords()) {
      record.setBgD1Declaration(declaration);
      records().save(record);
    }

    declaration.setBgRefusalReport(outcome.refusalReport());
    declaration.setBgValidationReport(outcome.findingReport());
    declaration.setStatusSelect("generated");
    declarations().save(declaration);
    return outcome;
  }

  /**
   * ПОВТОРНО ГЕНЕРИРАНЕ НА РЕДОВНА ДЕКЛАРАЦИЯ — разрешено по СПИСЪК НА ПОЗВОЛЕНОТО.
   *
   * <p>Правилото е чиста функция и живее в {@link BgD1RegenerationRules}; тук стои само сглобката:
   * вид на декларацията + заявка за ПОСЛЕДНОТО подаване. Разделението не е украса — правилото иска
   * свидетел за осем статуса, а заявката иска база.
   *
   * <p>⚠️ И се пита само за РЕДОВНАТА. Коригиращата СЪЩЕСТВУВА, защото редовната е подадена — отказ
   * и за нея би затворил изхода, който този отказ сочи.
   */
  protected String refuseRegularRegeneration(BgD1Declaration declaration) {
    if (declaration == null
        || BgD1DeclarationKind.isCorrecting(declaration)
        || BgD1DeclarationKind.isSupplementary(declaration)) {
      return null;
    }
    Period period = declaration.getBgDataPeriod();
    return BgD1RegenerationRules.refuse(
        period == null ? null : period.getName(),
        latestSubmissionStatus(period),
        blockingHistoryStatus(period));
  }

  /**
   * ДОПЪЛНИТЕЛНАТА Д1 — само когато НАП държи редовната за месеца, и само докато самата тя не е
   * подадена.
   *
   * <p>⚠️ Преди редовната да е подадена допълнителна няма смисъл: редовната се генерира наново и
   * носи частта като обикновен ред 27. След подаването на допълнителната — същото правило като за
   * редовната: подаденото не се пренаписва.
   */
  protected String refuseSupplementary(BgD1Declaration declaration) {
    if (declaration == null || !BgD1DeclarationKind.isSupplementary(declaration)) {
      return null;
    }
    Period period = declaration.getBgDataPeriod();
    String name = period == null ? "(без период)" : period.getName();
    if (blockingHistoryStatus(period) == null) {
      return "Допълнителната Д1 за „"
          + name
          + "“ не се генерира: НАП още не държи РЕДОВНАТА за този месец. Генерирайте наново"
          + " РЕДОВНАТА — тя носи частта на обезщетението като обикновен ред 27.";
    }
    String status = declaration.getStatusSelect();
    if ("submitted".equals(status) || "accepted".equals(status)) {
      return "Допълнителната Д1 за „"
          + name
          + "“ вече е подадена — генерирането е ЗАМЯНА и би подменило подаденото. За нова част"
          + " направете нова допълнителна декларация.";
    }
    return null;
  }

  /**
   * ЧАСТИТЕ ЗА ВЕЧЕ ПОДАДЕН МЕСЕЦ — находка какво трябва да се подаде.
   *
   * <p>Обезщетение по чл. 222 ал. 1 КТ се декларира в Д1 на МЕСЕЦА НА ЧАСТТА. Ако Д1 за онзи месец
   * вече е подадена, частта НЕ е корекция на подаден запис — тя е нов ред, който НАП още не е
   * виждала. Затова отива в ДОПЪЛНИТЕЛНА Д1 за онзи месец ({@link
   * BgD1DeclarationKind#SUPPLEMENTARY}), а Д6 за онзи месец — като нова декларация само с
   * положителната разлика (Н-13 чл. 9 ал. 7).
   *
   * <p>⚠️ Находка, не отказ: декларацията за ТОЗИ месец е вярна. Липсва друга.
   */
  protected List<BgD1FindingDto> partsForFiledMonths(
      BgD1Declaration declaration, List<BgPaySlip> slips) {
    List<BgD1FindingDto> findings = new ArrayList<>();
    Period own = declaration == null ? null : declaration.getBgDataPeriod();
    if (own == null || own.getFromDate() == null || slips == null) {
      return findings;
    }
    for (BgPaySlip slip : slips) {
      if (!BgD1Emitter.ownPeriod(slip, own)) {
        continue;
      }
      List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
      for (BgCompensationParts.Carried c : carried) {
        List<BgCompensationParts.Share> shares;
        try {
          shares = emitter().compensationShares(slip, c, carried);
        } catch (RuntimeException e) {
          continue; // Емитерът вече е обявил провала на календара.
        }
        for (BgCompensationParts.Share share : shares) {
          LocalDate month = share.getSegment().getFrom().withDayOfMonth(1);
          if (month.equals(own.getFromDate().withDayOfMonth(1))) {
            continue;
          }
          Period other = regularPeriodFor(declaration, month);
          if (other == null
              || blockingHistoryStatus(other) == null
              || isDeclaredIn(other, declaration, slip)) {
            continue;
          }
          findings.add(supplementaryNeeded(slip, share, other));
        }
      }
    }
    return findings;
  }

  static BgD1FindingDto supplementaryNeeded(
      BgPaySlip slip, BgCompensationParts.Share share, Period other) {
    Map<String, BigDecimal> d6 = share.d6ByPoint();
    LocalDate paid = share.paidDate();
    return new BgD1FindingDto(
            "D1_T27_PART_NEEDS_SUPPLEMENTARY",
            BgD1FindingDto.SEVERITY_WARNING,
            "част от обезщетението „"
                + share.reference()
                + "“ ("
                + share.getSegment()
                + ") е за „"
                + other.getName()
                + "“, а Д1 за този месец вече е подадена — частта НЕ влиза в тази декларация. Да"
                + " се подаде: (1) ДОПЪЛНИТЕЛНА Д1 за „"
                + other.getName()
                + "“ (вид „Допълнителна“) — нов ред 27, не корекция; (2) нова Д6 за „"
                + other.getName()
                + "“ само с положителната разлика (Н-13 чл. 9 ал. 7): блок с вид 6, дата "
                + (paid == null
                    ? "— последният ден от месеца на изплащане (обезщетението още не е изплатено)"
                    : YearMonth.from(paid).atEndOfMonth().toString())
                + ", ДОО "
                + amount(d6.get(BgD6Point.T10_DOO))
                + ", УПФ "
                + amount(d6.get(BgD6Point.T12_UPF))
                + ", данък — с Д6 за месеца на изплащане.")
        .on(slip.getReference(), BgCompensationParts.INSURED_TYPE)
        .at("2");
  }

  private static String amount(BigDecimal value) {
    return value == null ? "0.00" : value.toPlainString();
  }

  /** Периодът на РЕДОВНАТА Д1 на дружеството за този месец, или {@code null}. */
  protected Period regularPeriodFor(BgD1Declaration declaration, LocalDate month) {
    BgD1Declaration regular =
        declarations()
            .all()
            .filter(
                "self.company = :company AND self.bgDataPeriod.fromDate = :month"
                    + " AND (self.bgDeclarationKind IS NULL OR self.bgDeclarationKind = :regular)")
            .bind("company", declaration.getCompany())
            .bind("month", month)
            .bind("regular", BgD1DeclarationKind.REGULAR)
            .order("-id")
            .fetchOne();
    return regular == null ? null : regular.getBgDataPeriod();
  }

  /** Има ли вече ред 27 за този фиш в друга (редовна или допълнителна) Д1 за периода. */
  protected boolean isDeclaredIn(Period period, BgD1Declaration except, BgPaySlip slip) {
    if (period == null || slip == null || slip.getId() == null) {
      return false;
    }
    return records()
            .all()
            .filter(
                "self.paySlip = :slip AND self.t12InsuredTypeCode = :code"
                    + " AND self.bgD1Declaration.bgDataPeriod = :period"
                    + " AND self.bgD1Declaration.id <> :except"
                    + " AND (self.bgD1Declaration.bgDeclarationKind IS NULL"
                    + " OR self.bgD1Declaration.bgDeclarationKind <> :correcting)")
            .bind("slip", slip)
            .bind("code", BgCompensationParts.INSURED_TYPE)
            .bind("period", period)
            .bind("except", except == null || except.getId() == null ? -1L : except.getId())
            .bind("correcting", BgD1DeclarationKind.CORRECTING)
            .count()
        > 0;
  }

  /**
   * ДОПЪЛНИТЕЛНАТА носи само частите, които още нямат ред в друга Д1 за месеца — инак НАП би
   * получила един и същ ред 27 втори път.
   */
  protected List<BgPaySlip> withoutDeclaredParts(
      BgD1Declaration declaration, List<BgPaySlip> slips) {
    List<BgPaySlip> out = new ArrayList<>();
    for (BgPaySlip slip : slips) {
      if (!isDeclaredIn(declaration.getBgDataPeriod(), declaration, slip)) {
        out.add(slip);
      }
    }
    return out;
  }

  /**
   * КОРИГИРАЩАТА — само когато НАП държи редовната. Същата заявка за историята като при редовната,
   * тоест двете врати се допълват: коригиращата се отваря точно когато редовната се затваря.
   * Правилото е в {@link BgD1DeclarationKind#refuseCorrectingBeforeAccepted}.
   */
  protected String refuseCorrectingBeforeAccepted(BgD1Declaration declaration) {
    if (declaration == null || !BgD1DeclarationKind.isCorrecting(declaration)) {
      return null;
    }
    return BgD1DeclarationKind.refuseCorrectingBeforeAccepted(
        declaration, blockingHistoryStatus(declaration.getBgDataPeriod()));
  }

  /**
   * Има ли В ИСТОРИЯТА на периода подаване, което значи „НАП държи данните“ — и кое е то.
   *
   * <p>⚠️ ВТОРА ЗАЯВКА, А НЕ ПРОЧИТ НА ПЪРВАТА. Последното подаване и историята отговарят на
   * различни въпроси и ги събира само периодът; извлечени от един ред, единият въпрос щеше да се
   * чете през отговора на другия — а точно тяхното разминаване е целият смисъл („последното е
   * отхвърлено, но по-рано е имало прието“).
   *
   * <p>Подредбата е по {@code id} надолу само за да е предвидимо КОЙ от двата блокиращи статуса ще
   * бъде назован, когато ги има и двата. Самото решение не зависи от реда.
   */
  protected String blockingHistoryStatus(Period period) {
    if (period == null || period.getId() == null) {
      return null;
    }
    NapSubmission blocking =
        Beans.get(NapSubmissionRepository.class)
            .all()
            .filter(BgD1PeriodLockRules.FILTER)
            .bind("period", period)
            .bind("service", BgD1PeriodLockRules.SERVICE_CODE)
            .bind("documentType", BgD1PeriodLockRules.DOCUMENT_TYPE)
            .bind("statuses", BgD1RegenerationRules.HISTORY_BLOCKING_STATUSES)
            .order("-id")
            .fetchOne();
    return blocking == null ? null : blocking.getSubmissionStatusSelect();
  }

  /**
   * Статусът на ПОСЛЕДНОТО подаване за този период, или {@code null}, ако подаване няма.
   *
   * <p>⚠️ ПОДРЕДБАТА Е ПО {@code id} НАДОЛУ, а не по дата. Датата на подаване е ПРАЗНА за чернова и
   * за техническа грешка — тоест сортиране по нея слага точно неуспелите опити на непредвидимо
   * място, а те са половината от въпроса. Редът на вмъкване е монотонен и не се пренаписва.
   *
   * <p>⚠️ Обхватът е СЪЩИЯТ като при ключ (Б) — {@link BgD1PeriodLockRules#SCOPE_FILTER}: същата
   * услуга и същият вид документ. SAF-T или ДДС-справка за същия месец нямат нищо общо с фишовете и
   * широкият прочит би отказал генерирането заради чужд документ.
   */
  protected String latestSubmissionStatus(Period period) {
    if (period == null || period.getId() == null) {
      return null;
    }
    NapSubmission latest =
        Beans.get(NapSubmissionRepository.class)
            .all()
            .filter(BgD1PeriodLockRules.SCOPE_FILTER)
            .bind("period", period)
            .bind("service", BgD1PeriodLockRules.SERVICE_CODE)
            .bind("documentType", BgD1PeriodLockRules.DOCUMENT_TYPE)
            .order("-id")
            .fetchOne();
    return latest == null ? null : latest.getSubmissionStatusSelect();
  }

  /** Отказът е изключение, а не върнат низ: генерирането няма междинно състояние да върне. */
  protected void refuse(String reason) {
    if (reason != null) {
      throw new IllegalStateException(reason);
    }
  }

  /**
   * Изтриването минава през ЗАЯВКА, а не през изчистване на колекцията: колекцията носи онова,
   * което формата е заредила, а изтриването трябва да хване всичко, което базата пази за тази
   * декларация. {@code orphanRemoval} на домейна остава резервата, не механизмът.
   */
  protected void deleteRecordsOf(Long declarationId) {
    records().all().filter("self.bgD1Declaration.id = :id").bind("id", declarationId).delete();
  }

  protected BgD1DeclarationRepository declarations() {
    return Beans.get(BgD1DeclarationRepository.class);
  }

  protected BgD1RecordRepository records() {
    return Beans.get(BgD1RecordRepository.class);
  }

  protected BgD1Emitter emitter() {
    return Beans.get(BgD1Emitter.class);
  }
}
