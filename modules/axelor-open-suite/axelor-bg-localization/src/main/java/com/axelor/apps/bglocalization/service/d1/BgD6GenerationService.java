package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository;
import com.axelor.apps.bglocalization.db.repo.BgD6DeclarationRepository;
import com.axelor.apps.bglocalization.service.BgEffectivePaySlips;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ГЕНЕРИРАНЕТО НА Д6 — изнесено от контролера по образеца на {@link BgD1GenerationService}, за да е
 * достижимо за свидетел по реалния път.
 *
 * <p><b>⚠️ ФИШОВЕТЕ СА ДЕЙСТВАЩИТЕ, ПРЕЗ СЪЩИЯ МЕТОД КАТО РЕДОВНАТА Д1</b> ({@link
 * BgEffectivePaySlips#effectiveFor}). Своя заявка „всички потвърдени за периода“ би сумирала
 * оригинала И корекцията му: т. 10 = (1500 + 1700 + 1840) × 19,8 %, докато Д1 носи 1700.
 *
 * <p><b>⚠️ ФИШОВЕТЕ СА ВСИЧКИТЕ ДЕЙСТВАЩИ, включително онези, за които Д1 се ОТКАЗВА.</b> Кодове
 * 15, 88 и 89 се подават от НОИ по чл. 4 ал. 10 т. 1, но ВНОСКИТЕ за тях се дължат от работодателя
 * и влизат в Д6 — това е разликата между „кой подава данните за лицето“ и „кой дължи вноската“.
 */
public class BgD6GenerationService {

  /**
   * Генерира Д6 и връща находките. Отказът на сверката с Д1 е ИЗКЛЮЧЕНИЕ и е ПРЕДИ емитера: Д6,
   * сглобена срещу остаряла Д1, не се записва изобщо.
   */
  @Transactional
  public List<BgD1FindingDto> generate(Long declarationId) {
    BgD6DeclarationRepository declarations = Beans.get(BgD6DeclarationRepository.class);
    BgD6Declaration declaration = declarations.find(declarationId);

    List<BgPaySlip> slips = slipsFor(declaration);

    // ═══ СВЕРКАТА С Д1 — ПРЕДИ ЕМИТЕРА ═══
    BgD1Declaration d1 = regularD1For(declaration);
    List<BgD1Record> records = d1 == null ? Collections.emptyList() : recordsOf(d1);
    String stale =
        BgD6D1Reconciliation.refuse(
            declaration.getBgPeriod() == null ? null : declaration.getBgPeriod().getName(),
            slips,
            records);
    if (stale != null) {
      throw new IllegalStateException(stale);
    }

    BgD6Emitter emitter = Beans.get(BgD6Emitter.class);
    BgD6CompensationLedger ledger = ledgerFor(declaration, slips);
    BgD6Emitter.Outcome outcome = emitter.emit(declaration, slips, ledger);
    // ⚠️ ВИДЪТ ПЛАЩАНЕ СЕ ПИТА ТУК, А НЕ В КОНТРОЛЕРА: дали изобщо има блок на заплатите (и
    // ръчният 5/6 има за какво да се отнася), знае само сметката на обезщетенията по вид 27.
    // ⚠️ И СЛЕД ЕМИТЕРА: блокът на заплатите е т. 10–17 минус обезщетенията — преди емитера
    // сумите са празни и всяко обезщетение изглежда като „отрицателна заплата“. Отказът връща
    // транзакцията, т.е. и сумите, които емитерът току-що сложи.
    String paymentKind = BgD6Emitter.paymentKindRefusal(declaration, ledger);
    if (paymentKind != null) {
      throw new IllegalStateException(paymentKind);
    }
    // Блоковете се сглобяват и тук, не само при файла: неизплатено обезщетение или пети блок е
    // ОТКАЗ на генерирането, а не изненада при подаването.
    BgD6Blocks.of(declaration, ledger);

    List<BgD1FindingDto> findings = new ArrayList<>(outcome.getFindings());

    // ═══ ЧЛ. 2 АЛ. 4 — срещу записите на Д1 за същото дружество и месец ═══
    if (d1 == null) {
      findings.add(
          new BgD1FindingDto(
                  "D6_NO_D1_TO_CHECK",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "равенството по чл. 2 ал. 4 НЕ е проверено: за това дружество и месец няма"
                      + " генерирана Декларация обр. 1. ⚠️ „Всяко от декларираните месечни задължения"
                      + " … трябва да бъде равно на изчислените задължителни осигурителни вноски … от"
                      + " подадените декларация/и образец № 1.“ Тоест Д6 без Д1 е число без втори"
                      + " път до себе си — генерирай Д1 за същия период и повтори.")
              .at("10"));
    } else {
      findings.addAll(emitter.checkEquality(declaration, slips, records));
    }

    declaration.setBgValidationReport(join(findings));
    declaration.setStatusSelect("generated");
    declarations.save(declaration);
    return findings;
  }

  /**
   * ДЕЙСТВАЩИТЕ фишове на носителя — ЕДИНСТВЕНИЯТ избор, общ с редовната Д1 — плюс носителите от
   * други месеци на части от обезщетение по вид 27 ({@link
   * BgEffectivePaySlips#compensationCarriersFor}).
   */
  protected List<BgPaySlip> slipsFor(BgD6Declaration declaration) {
    return withCompensationCarriers(
        BgEffectivePaySlips.effectiveFor(
            declaration.getBgPeriod(),
            declaration.getCompany(),
            declaration.getBgSelfInsuredRegistration()),
        BgEffectivePaySlips.compensationCarriersFor(
            declaration.getBgPeriod(),
            declaration.getCompany(),
            declaration.getBgSelfInsuredRegistration()));
  }

  /**
   * СМЕТКАТА НА ОБЕЗЩЕТЕНИЯТА ПО ВИД 27 за тази Д6 — от СЪЩИТЕ фишове като сумите ѝ.
   *
   * <p>⚠️ ПУБЛИЧНА, защото файлът (при подаването) я иска отново: блоковете не се пазят в схемата,
   * а се извеждат — тоест генерирането и файлът трябва да ползват един и същ път.
   */
  public BgD6CompensationLedger ledgerFor(BgD6Declaration declaration) {
    return ledgerFor(declaration, slipsFor(declaration));
  }

  protected BgD6CompensationLedger ledgerFor(BgD6Declaration declaration, List<BgPaySlip> slips) {
    return BgD6CompensationLedger.of(
        declaration.getBgPeriod(), slips, BgCompensationCalendar::sharesOf);
  }

  /** Действащите на месеца, после носителите от други месеци — без повторения. */
  static List<BgPaySlip> withCompensationCarriers(
      List<BgPaySlip> effective, List<BgPaySlip> carriers) {
    List<BgPaySlip> out = new ArrayList<>(effective);
    for (BgPaySlip carrier : carriers) {
      if (!out.contains(carrier)) {
        out.add(carrier);
      }
    }
    return out;
  }

  /**
   * РЕДОВНАТА Декларация обр. 1 за същото дружество и месец, или {@code null}.
   *
   * <p>⚠️ ТЪРСИ СЕ ПО ПЕРИОДА НА ДАННИТЕ, не по периода на подаване: Д6 носи месеца, „за които се
   * отнасят вноските“ (указание т. 9), а на Д1 това е {@code bgDataPeriod}.
   *
   * <p>⚠️ САМО РЕДОВНАТА. Най-новата от какъвто и да е вид би направила коригираща Д1, която носи
   * само засегнатите лица, мярка за цялата Д6. Заварена декларация без вид се чете като редовна
   * ({@link BgD1DeclarationKind#of}).
   */
  protected BgD1Declaration regularD1For(BgD6Declaration declaration) {
    try {
      return Beans.get(BgD1DeclarationRepository.class)
          .all()
          .filter(
              "self.company = :company AND self.bgDataPeriod = :period"
                  // ⚠️ И НЕ ДОПЪЛНИТЕЛНАТА: тя носи само нови редове 27 за вече подаден месец.
                  + " AND (self.bgDeclarationKind IS NULL OR self.bgDeclarationKind = ''"
                  + " OR self.bgDeclarationKind = :regular)")
          .bind("company", declaration.getCompany())
          .bind("period", declaration.getBgPeriod())
          .bind("regular", BgD1DeclarationKind.REGULAR)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      // Липсваща база е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „няма Д1“ — но и двете водят до обявена
      // непроверена проверка, тоест тук разликата не мени поведението.
      return null;
    }
  }

  /**
   * ЗАПИСИТЕ НА Д1 — през ЗАЯВКА, а не през колекцията на декларацията.
   *
   * <p>⚠️ Колекцията носи онова, което е заредено в контекста, а генерирането на Д1 записва
   * записите си през repository-то, без да ги добавя в нея ({@code
   * BgD1GenerationService#generate}). В един и същ контекст — Д1, генерирана наново преди Д6 —
   * колекцията е остаряла и сверката не би видяла нищо. Измерено: свидетелят по реалния път мълчеше
   * точно така.
   */
  protected List<BgD1Record> recordsOf(BgD1Declaration d1) {
    return Beans.get(BgD1RecordRepository.class)
        .all()
        .filter("self.bgD1Declaration = :d1")
        .bind("d1", d1)
        .order("id")
        .fetch();
  }

  private static String join(List<BgD1FindingDto> findings) {
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
