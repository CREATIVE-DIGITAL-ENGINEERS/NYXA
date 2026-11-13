package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgEffectivePaySlips;
import com.axelor.apps.bglocalization.service.BgPaySlipRegimeAudit;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipWriteGuard;
import com.axelor.apps.bglocalization.service.BgSocialExpenseCapResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1PeriodLockService;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSequenceService;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSequences;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.persistence.PersistenceException;

/**
 * Дава номер на фиша при първия запис — по същия модел като {@code
 * BgCompensationManagementRepository}.
 *
 * <p>⚠️ ГОДИНАТА ИДВА ОТ ПЕРИОДА, НЕ ОТ ДНЕШНИЯ ДЕН. Фиш за декември, издаден през януари, носи
 * ДЕКЕМВРИЙСКАТА година — инак номерът твърди друга година от периода, който описва.
 *
 * <p>⚠️ БРОЯЧЪТ Е НА ДРУЖЕСТВОТО ({@link BgTrzSequences}). Обща за всички дружества редица и
 * резервен брояч FISH-RES-… ({@code count + 1}) няма: дружество без редица получава отказ, който го
 * назовава. Заварените FISH-RES-… номера остават, каквито са.
 */
public class BgPaySlipManagementRepository extends BgPaySlipRepository {

  /**
   * ИЗТРИВАНЕТО НА ДЕКЛАРИРАН ФИШ — ключ (Б) на заключването на периода и единствената му врата
   * ИЗВЪН услугата.
   *
   * <p><b>⚠️ ТУК, А НЕ В КОНТРОЛЕРА ИЛИ В ИЗГЛЕДА.</b> Списъкът предлага изтриване, но той не е
   * единственият вход: REST, импортът и масовото изтриване от решетката минават покрай всяка
   * проверка на екрана и стигат право до репозитория. {@code canDelete="false"} в изгледа щеше да
   * скрие бутона и да остави дупката.
   *
   * <p><b>⚠️ И ЗАЩО ИЗТРИВАНЕТО Е ПО-ГРУБО ОТ РЕДАКЦИЯТА.</b> Променен фиш поне оставя запис, който
   * се разминава с подаденото; изтритият не оставя нищо — тоест разминаването вече не може дори да
   * се открие. За НАП записът продължава да съществува.
   *
   * <p>⚠️ Отказът е {@code PersistenceException} по същия идиом като {@code
   * MoveManagementRepository}: {@code AxelorException} не се обявява в подписа на {@code remove} и
   * не може да се хвърли оттук, а съобщението стига до екрана и по двата пътя.
   */
  @Override
  public void remove(BgPaySlip slip) {
    Period period = slip == null ? null : slip.getPeriod();
    String refusal = BgPaySlipResolver.refuseDeleteInLockedPeriod(slip, declaredToNap(period));
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
    removeRecord(slip);
  }

  /** Самото триене — свой шев, за да може тестът да провери пазача без JPA. */
  protected void removeRecord(BgPaySlip slip) {
    super.remove(slip);
  }

  /**
   * КЛЮЧ (Б) — същият шев като {@code BgPaySlipService#declaredToNap} и по същия довод.
   *
   * <p>⚠️ Чистият изход преди {@code Beans.get}: период без {@code id} е сглобен наум и подаване
   * към него не може да сочи.
   */
  protected boolean declaredToNap(Period period) {
    if (period == null || period.getId() == null) {
      return false;
    }
    return Beans.get(BgD1PeriodLockService.class).isDeclaredToNap(period);
  }

  @Override
  public BgPaySlip save(BgPaySlip slip) {
    // ⚠️ ПАЗАЧИТЕ — ПЪРВИ, преди номера: отказан запис не бива да харчи номер от редицата.
    guardCompany(slip);
    guardDraftPeriod(slip);
    guardSecondRegular(slip);
    // СЛЕД отказите, ПРЕДИ номера: pinCompany и отказва (дружеството на номера ≠ платеца), и
    // ПИШЕ company — отказан от горните запис не бива да го получава. Номерът (nextReference)
    // брои по записаното тук дружество, затова е след него.
    pinCompany(slip);
    if (slip.getReference() == null || slip.getReference().trim().isEmpty()) {
      slip.setReference(nextReference(slip));
    }
    fillTreatmentFromType(slip);
    String manualExcess = refuseNewManualDerivedSocialExpense(slip);
    if (manualExcess != null) {
      throw new PersistenceException(manualExcess);
    }
    // ⚠️ ОДИТЪТ НА РЕЖИМА — ПРИ ВСЕКИ ЗАПИС НА КОПИЕ, НЕ САМО ОТ ФОРМАТА. Промяна на вида осигурен
    // или на ТЕЛК в коригиращо копие мени коригиращата декларация; следата ѝ трябва да остане и
    // когато записът идва през REST или внос. Само добавяне — виж BgPaySlipRegimeAudit.
    if (BgPaySlipResolver.isRetro(slip) && BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      BgPaySlipRegimeAudit.sync(slip, currentUser(), LocalDateTime.now());
    }
    return super.save(slip);
  }

  /**
   * САМО АКТИВНОТО ДРУЖЕСТВО — при всеки запис, и при НОВ фиш.
   *
   * <p><b>⚠️ ЗАЩО ТУК, А НЕ САМО В ГЕЙТА НА КОНТРОЛЕРА.</b> Правото за запис на Axelor пита
   * ЗАПИСАНИЯ ред преди редакцията, а за нов ред (без id) не пита условието на каталога изобщо
   * ({@code AuthSecurity#isPermitted}). Без пазача стандартният запис и REST биха сменили договора
   * на фиша към друго дружество или родили фиш с чужд договор, без нито един отказ.
   *
   * <p>⚠️ Без потребител (няма сесия) отказът е „Not logged in“ — фишът не се пише от анонимен
   * контекст; системните пътища на модула вървят от вписан потребител.
   */
  protected void guardCompany(BgPaySlip slip) {
    String noCompany = BgPaySlipWriteGuard.noCompanyRefusal(slip);
    if (noCompany != null) {
      throw new PersistenceException(noCompany);
    }
    requireActiveCompany(BgPaySlipWriteGuard.companyOf(slip));
  }

  /**
   * ЧЕРНОВА В ЗАКЛЮЧЕН ИЛИ ЗАТВАРЯЩ СЕ ПЕРИОД НЕ СЕ ЗАПИСВА — отказите на потвърждаването, вече и
   * на записа ({@link BgPaySlipWriteGuard#refusePeriod}).
   *
   * <p><b>⚠️ ПИТА ЗАПИСВАНОТО, НЕ ЗАПИСАНОТО — нарочно.</b> {@code Resource#save} (стандартният
   * запис и REST) флъшва през {@code JPA.manage} ПРЕДИ {@code repository.save}, тоест заявка тук
   * вече чете новото. Правилото затова е за реда, КАКЪВТО ЩЕ ОСТАНЕ: чернова в такъв период.
   *
   * <p><b>Системните пътища минават по правилото, без пропуск:</b> потвърждаването записва
   * CONFIRMED и преди това прилага същите два отказа; отказът — CANCELLED (почистването на чернова
   * в заключен месец остава възможно); плащането, SEPA и вносът от Microinvest не пишат чернова;
   * новото коригиращо или заличаващо копие (и копието на „Отрази пристигнал болничен“) е РЕТРО, а
   * {@code refuseLockedPeriod} пуска ретро, и е в CLOSED период ({@code refuseRetroPeriod}), не в
   * затварящ се. Заключването на периода (CLOSED, подадена Д1/Д6) не записва фишове.
   *
   * <p>⚠️ Признак „ретро“ или статус, сменени от клиента В СЪЩИЯ запис, тук не се познават — нито
   * тук, нито където и да е в репозитория. Това иска пазач на Hibernate по старото състояние (както
   * {@code NapArtifactImmutabilityListener}); „Преизчисли“ ги отказва преди редакцията.
   */
  protected void guardDraftPeriod(BgPaySlip slip) {
    if (!BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return;
    }
    String refusal =
        BgPaySlipWriteGuard.refusePeriod(
            slip, declaredToNap(slip.getPeriod()), BgPaySlipResolver.isRetro(slip));
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
  }

  /**
   * ВТОРИ РЕДОВЕН ФИШ ЗА СЪЩИЯ ДОГОВОР И ПЕРИОД НЕ СЕ ЗАПИСВА — още при запис, не чак при
   * „Потвърди“.
   *
   * <p>Броят се всички неотказани редовни фишове, ЧЕРНОВАТА също. Два редовни фиша за един месец
   * влизат в Д1 и Д6 едновременно; открит при потвърждаване, вторият вече е бил попълван и
   * преизчисляван. Коригиращите и заличаващите копия минават по своя път ({@code
   * BgPaySlipService#openRetro}) и тук не се броят — нито като нов фиш, нито като съществуващ.
   *
   * <p>⚠️ Отказът назовава СЪЩЕСТВУВАЩИЯ фиш с номер и статус: поправката е в него.
   */
  protected void guardSecondRegular(BgPaySlip slip) {
    String refusal = BgEffectivePaySlips.refuseSecondRegular(slip, otherRegular(slip));
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
  }

  /** Шев към {@link BgEffectivePaySlips#otherRegular}. */
  protected BgPaySlip otherRegular(BgPaySlip slip) {
    return BgEffectivePaySlips.otherRegular(slip);
  }

  /** Шев към {@link CapabilityGate#requireActiveCompany}. */
  protected void requireActiveCompany(Company company) {
    CapabilityGate.requireActiveCompany(company);
  }

  /**
   * ⚠️ НОВ РЪЧЕН РЕД „ПРЕВИШЕНИЕ НАД КАПАЧКАТА“ НЕ СЕ ЗАПИСВА — причина за отказ, или {@code null}.
   *
   * <p><b>Защо тук, а не само в изгледа.</b> Домейнът на полето в решетката крие перата, но REST,
   * вносът и решетката на фиша стигат право до репозитория — същият довод като при {@link
   * #fillTreatmentFromType}.
   *
   * <p><b>⚠️ САМО НОВИТЕ РЕДОВЕ, И ТОВА Е НАРОЧНО.</b> Стара чернова, която вече носи такъв ред,
   * трябва да може да се запише — включително с махнат ред. Нея я спират „Преизчисли“ и „Потвърди“
   * с назован ред ({@code BgPaySlipService}), не тиха загуба на записа. Същите три изключения като
   * при третирането: само чернова, без копия (те носят редовете на оригинала) и без внесена
   * история.
   */
  protected String refuseNewManualDerivedSocialExpense(BgPaySlip slip) {
    if (slip == null
        || slip.getBgPaySlipLineList() == null
        || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())
        || Boolean.TRUE.equals(slip.getBgIsHistoricalImport())
        || BgPaySlipResolver.isRetro(slip)) {
      return null;
    }
    List<BgPaySlipLine> fresh = new ArrayList<>();
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line != null && line.getId() == null) {
        fresh.add(line);
      }
    }
    return BgSocialExpenseCapResolver.refuseManualDerivedSocialExpense(fresh);
  }

  /** Потребителят на заявката, или {@code null} извън нея (вносът, тестовете). */
  protected User currentUser() {
    try {
      return AuthUtils.getUser();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * ТРЕТИРАНЕТО НА ВСЯКО ПЕРО СЕ ПРЕПИСВА ОТ РЕЧНИКА — <b>тук, а не на екрана</b>.
   *
   * <p><b>⚠️ КАКВО ПАДАШЕ.</b> {@code BgPaySlipService#lineFrom} отдавна попълва всичките флагове
   * от типа, а {@code BgPaySlipController#lineFromType} го вика и получава ПЪЛЕН ред — но после
   * връща към формата само ШЕСТ стойности. {@code bgFormsGrossWage} и {@code bgIsArt17Base} не са
   * нито в отговора, нито в изгледа, тоест новият ред пада на {@code default="false"} и се ЗАПИСВА
   * така. Следствието е измеримо: ръчно въведен постоянен бонус 100,00 излиза ИЗВЪН т. 29 на
   * Декларация обр. 1 и се осчетоводява в синтетичната 604 вместо в 6042; ръчно въведен престой
   * (170) или възнаграждение за резултати (190) изпада от базата по НСОРЗ чл. 17 и сваля отпуска в
   * следващ месец с 47,73 € при 210,00 € и 5 дни отпуск.
   *
   * <p><b>⚠️ ЗАЩО В РЕПОЗИТОРИЯ, А НЕ В КОНТРОЛЕРА.</b> Ехото към формата е ЕДИН вход. REST-ът,
   * вносът и решетката на фиша стигат право дотук; решетката на перата дори не е
   * inline-редактируема и записва детето ПРЕЗ родителя, тоест собствен репозиторий на реда би
   * мълчал. Поправено само в контролера или само в услугата, същият флаг пада при първия друг вход.
   *
   * <p><b>⚠️ ПЕТ ФЛАГА, А НЕ ДВАТА ПРОПУСНАТИ, И ТОВА Е РАЗЛИКАТА МЕЖДУ ПОПРАВКА И ПРАВИЛО.</b>
   * Списък от два кода е списък, който следващият редактор ще забрави да допълни. Правилото е
   * „третирането Е копие от речника“ — точно каквото твърди и заглавието на панела в изгледа.
   * Затова се преписват и трите, които днес случайно се ехо-ват.
   *
   * <p><b>⚠️ {@code bgSign} НЕ Е СРЕД ТЯХ, И ТОВА НЕ Е ПРОПУСК.</b> Перото по чл. 49 (710) получава
   * посоката си от ЗНАКА НА КОЛОНА 19, не от речника — {@code BgArt49SettlementService} я записва
   * след {@code lineFrom}. Преписан оттук, знакът щеше да върне пари на всеки, който дължи, и да
   * удържи от всеки, на когото се възстановява.
   *
   * <p><b>⚠️ И ТРИТЕ ГРАНИЦИ СА ЗАРАДИ ИСТОРИЯТА, НЕ ЗАРАДИ ПРОИЗВОДИТЕЛНОСТ.</b>
   *
   * <ul>
   *   <li><b>САМО ЧЕРНОВА.</b> Вносителят на историята от Microinvest записва ПОТВЪРДЕНИ фишове
   *       през същия този {@code save} и сам попълва флаговете от внесените данни. Преписани от
   *       днешния речник, те щяха да пренапишат минали месеци по днешните правила.
   *   <li><b>БЕЗ КОРИГИРАЩ И ЗАЛИЧАВАЩ ФИШ.</b> {@code BgPaySlipService#copyOf} копира флаговете от
   *       ОРИГИНАЛА, защото „копието трябва да е това, което оригиналът Е БИЛ“. Преписани тук, те
   *       щяха да направят коригиращата Д1 да твърди друго за същия месец.
   *   <li><b>БЕЗ РЕДОВЕ ЗА ДРУГ ПЕРИОД.</b> Делта-редът от ретро-корекция носи числата на онзи
   *       месец; речникът междувременно може да се е сменил.
   * </ul>
   *
   * <p>⚠️ Идемпотентно: същият тип дава същите пет стойности при всеки запис.
   */
  protected void fillTreatmentFromType(BgPaySlip slip) {
    if (slip == null
        || slip.getBgPaySlipLineList() == null
        || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())
        || Boolean.TRUE.equals(slip.getBgIsCorrection())
        || Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      return;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgIsArt17Base(type.getBgIsArt17Base());
      line.setBgFormsGrossWage(type.getBgFormsGrossWage());
    }
  }

  /**
   * ДРУЖЕСТВОТО НА ФИША СЕ ЗАПИСВА — при номерирането, и после не се сменя.
   *
   * <p>По правилото на {@link BgPaySlipWriteGuard#companyOf}: платецът по договора, а за
   * самоосигуряващия се — по основния договор от регистрацията. По колоната базата пази
   * уникалността (дружество, номер), а Axelor търси повтарящ се номер само в брояча на дружеството.
   *
   * <p>⚠️ Заварен фиш без колоната (преди миграцията, която я добави) я получава при следващия
   * запис — по същото правило, по което SQL-ът я попълва.
   */
  protected void pinCompany(BgPaySlip slip) {
    Company derived = BgPaySlipWriteGuard.companyOf(slip);
    String refusal =
        BgTrzSequences.refusePayerMismatch(
            BgTrzSequences.PAY_SLIP, label(slip), slip.getCompany(), derived);
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
    if (slip.getCompany() == null) {
      slip.setCompany(derived);
    }
  }

  /**
   * Номерът — САМО от редицата на дружеството на фиша. Без нея — отказ с името на дружеството; общ
   * брояч и резервен номер няма.
   */
  protected String nextReference(BgPaySlip slip) {
    return Beans.get(BgTrzSequenceService.class)
        .nextNumber(
            BgTrzSequences.PAY_SLIP,
            slip.getCompany(),
            periodDate(slip),
            BgPaySlip.class,
            "reference",
            slip,
            label(slip));
  }

  private static String label(BgPaySlip slip) {
    String reference = slip.getReference();
    return reference == null || reference.trim().isEmpty() ? "(нов)" : reference;
  }

  private static LocalDate periodDate(BgPaySlip slip) {
    return slip.getPeriod() != null && slip.getPeriod().getFromDate() != null
        ? slip.getPeriod().getFromDate()
        : LocalDate.now();
  }
}
