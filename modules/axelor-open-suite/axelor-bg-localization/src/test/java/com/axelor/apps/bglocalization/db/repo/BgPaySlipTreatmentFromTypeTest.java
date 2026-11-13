package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТРЕТИРАНЕТО НА ПЕРОТО СЕ ПРЕПИСВА ОТ РЕЧНИКА ПРИ ЗАПИС.
 *
 * <p><b>КАКВО ПАЗИ ТОЗИ ФАЙЛ.</b> {@code BgPaySlipService#lineFrom} попълва всичките флагове от
 * типа, а {@code BgPaySlipController#lineFromType} го вика и получава ПЪЛЕН ред. Ако към формата се
 * върне само част от стойностите и {@code bgFormsGrossWage} и {@code bgIsArt17Base} липсват в
 * отговора или в изгледа, ръчно добавеното от екрана перо пада на {@code default="false"} и се
 * ЗАПИСВА така.
 *
 * <p><b>⚠️ СВИДЕТЕЛЯТ НА {@code lineFrom} НЕ СТИГА.</b> {@code BgGrossWageFlagTest} проверява
 * {@code lineFrom}, но то не е единственото място, където речникът се чете: контролерът е втори
 * читател, затова записът се пази отделно тук.
 */
class BgPaySlipTreatmentFromTypeTest {

  /** Седемте ръчни пера от група 1xx, с флаговете, които речникът им дава. */
  private static final Map<Integer, boolean[]> MANUAL_LINES = new LinkedHashMap<>();

  static {
    // {bgFormsGrossWage, bgIsArt17Base, bgIsPermanentCharacter}
    MANUAL_LINES.put(170, new boolean[] {true, true, false}); // престой по чл. 267
    MANUAL_LINES.put(175, new boolean[] {false, false, false}); // компенсация по чл. 138а
    MANUAL_LINES.put(180, new boolean[] {true, true, true}); // постоянен бонус
    MANUAL_LINES.put(181, new boolean[] {true, true, true}); // постоянна премия
    MANUAL_LINES.put(185, new boolean[] {true, false, false}); // еднократен бонус
    MANUAL_LINES.put(186, new boolean[] {true, false, false}); // годишно възнаграждение
    MANUAL_LINES.put(190, new boolean[] {true, true, false}); // за постигнати резултати
  }

  @Test
  @DisplayName("⚠️ И СЕДЕМТЕ РЪЧНИ ПЕРА · нито едно не се записва с false, ако речникът дава true")
  void noManualLineIsSavedWithTheFlagsDropped() {
    BgPaySlip slip = draft();
    for (Map.Entry<Integer, boolean[]> e : MANUAL_LINES.entrySet()) {
      slip.getBgPaySlipLineList().add(bareLine(type(e.getKey(), e.getValue())));
    }

    new Repo().fill(slip);

    List<String> wrong = new ArrayList<>();
    int i = 0;
    for (Map.Entry<Integer, boolean[]> e : MANUAL_LINES.entrySet()) {
      BgPaySlipLine line = slip.getBgPaySlipLineList().get(i++);
      boolean[] want = e.getValue();
      if (!Boolean.valueOf(want[0]).equals(line.getBgFormsGrossWage())
          || !Boolean.valueOf(want[1]).equals(line.getBgIsArt17Base())
          || !Boolean.valueOf(want[2]).equals(line.getBgIsPermanentCharacter())) {
        wrong.add(
            e.getKey()
                + ": БРЗ="
                + line.getBgFormsGrossWage()
                + " art17="
                + line.getBgIsArt17Base()
                + " perm="
                + line.getBgIsPermanentCharacter());
      }
    }
    assertEquals(
        new ArrayList<String>(),
        wrong,
        "⚠️ ВСЯКО ПЕРО СЕ ЧЕТЕ ОТ СВОЯ ТИП, не от списък в кода. Перо 175 е контролът: речникът му"
            + " дава false и по двата флага, тоест фикс „вдигни ги винаги“ пада тук. Намерено: "
            + wrong);
  }

  @Test
  @DisplayName("⚠️ МУТАЦИЯ · ред, дошъл с празни флагове, ги получава — това е самата дупка")
  void aLineThatArrivesWithFalseGetsTheDictionaryValues() {
    BgPaySlip slip = draft();
    // Точно каквото екранът записваше: типът е избран, но третирането е на default="false".
    BgPaySlipLine line = bareLine(type(180, new boolean[] {true, true, true}));
    slip.getBgPaySlipLineList().add(line);

    assertTrue(
        Boolean.FALSE.equals(line.getBgFormsGrossWage()),
        "котвата: редът наистина идва с false — инак тестът би доказвал само себе си");

    new Repo().fill(slip);

    assertTrue(
        Boolean.TRUE.equals(line.getBgFormsGrossWage()),
        "⚠️ 100,00 € ръчен бонус ИЗВЪН т. 29 на Д1 и осчетоводен в 604 вместо 6042 — това плащаше"
            + " дупката");
    assertTrue(
        Boolean.TRUE.equals(line.getBgIsArt17Base()),
        "⚠️ И ВТОРИЯТ ФЛАГ, който е ПАРИ: без него перото изпада от базата по НСОРЗ чл. 17 и сваля"
            + " отпуска в следващ месец");
  }

  // ═══ ГРАНИЦИТЕ — ИСТОРИЯТА НЕ СЕ ПРЕПИСВА ════════════════════════════════

  @Test
  @DisplayName("⚠️ ПОТВЪРДЕН ФИШ · флаговете НЕ се пипат — вносителят пише история през същия save")
  void aConfirmedSlipIsLeftAlone() {
    BgPaySlip slip = draft();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    BgPaySlipLine line = bareLine(type(180, new boolean[] {true, true, true}));
    slip.getBgPaySlipLineList().add(line);

    new Repo().fill(slip);

    assertTrue(
        Boolean.FALSE.equals(line.getBgFormsGrossWage()),
        "⚠️ MicroinvestPayrollHistoryImporter записва ПОТВЪРДЕНИ фишове през ТОЗИ save и сам"
            + " попълва флаговете от внесените данни. Преписани от днешния речник, те щяха да"
            + " пренапишат минали месеци по днешните правила — и то мълчаливо.");
  }

  @Test
  @DisplayName("⚠️ КОРИГИРАЩ ФИШ · копието трябва да е това, което оригиналът Е БИЛ")
  void aCorrectionSlipIsLeftAlone() {
    BgPaySlip slip = draft();
    slip.setBgIsCorrection(Boolean.TRUE);
    BgPaySlipLine line = bareLine(type(180, new boolean[] {true, true, true}));
    slip.getBgPaySlipLineList().add(line);

    new Repo().fill(slip);

    assertTrue(
        Boolean.FALSE.equals(line.getBgFormsGrossWage()),
        "⚠️ copyOf копира флаговете от ОРИГИНАЛА нарочно. Преписани тук, коригиращата Д1 щеше да"
            + " твърди друго за същия месец от онова, което оригиналната е твърдяла — а двете ги"
            + " чете НАП, не ние.");
  }

  @Test
  @DisplayName("⚠️ РЕД ЗА ДРУГ ПЕРИОД · ретро-делтата носи числата на ОНЗИ месец")
  void aRetroLineIsLeftAlone() {
    BgPaySlip slip = draft();
    BgPaySlipLine line = bareLine(type(180, new boolean[] {true, true, true}));
    Period past = new Period();
    past.setFromDate(LocalDate.of(2026, 11, 1));
    line.setBgCorrectsPeriod(past);
    slip.getBgPaySlipLineList().add(line);

    new Repo().fill(slip);

    assertTrue(
        Boolean.FALSE.equals(line.getBgFormsGrossWage()),
        "речникът може да се е сменил между онзи месец и този");
  }

  @Test
  @DisplayName("⚠️ МИГРАЦИЯ · нула backfill — заварен ред се пипа само когато фишът е ЖИВА чернова")
  void thereIsNoBackfill() {
    // Заварен фиш: потвърден, с ред, записан с false. Тук е и цялата миграция:
    // нищо не се пипа назад. Корекцията на историята е решение на ТРЗ, не на този клон — тя мени
    // и подадени декларации (т. 29), и вече платени отпуски (базата по чл. 17).
    BgPaySlip legacy = draft();
    legacy.setStatusSelect(BgPaySlipResolver.PAID);
    BgPaySlipLine line = bareLine(type(190, new boolean[] {true, true, false}));
    legacy.getBgPaySlipLineList().add(line);

    new Repo().fill(legacy);
    new Repo().fill(legacy);

    assertTrue(
        Boolean.FALSE.equals(line.getBgFormsGrossWage())
            && Boolean.FALSE.equals(line.getBgIsArt17Base()),
        "заварените остават каквито са, колкото и пъти да мине записът");
  }

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · втори запис дава същите пет стойности")
  void theFillIsIdempotent() {
    BgPaySlip slip = draft();
    BgPaySlipLine line = bareLine(type(170, new boolean[] {true, true, false}));
    slip.getBgPaySlipLineList().add(line);

    new Repo().fill(slip);
    Boolean gross = line.getBgFormsGrossWage();
    Boolean art17 = line.getBgIsArt17Base();
    new Repo().fill(slip);

    assertEquals(gross, line.getBgFormsGrossWage(), "същият тип дава същата стойност");
    assertEquals(art17, line.getBgIsArt17Base(), "и за втория флаг");
  }

  @Test
  @DisplayName("⚠️ ЗНАКЪТ НЕ СЕ ПРЕПИСВА · перото по чл. 49 го получава от колона 19")
  void theSignIsNotOverwritten() {
    BgPaySlip slip = draft();
    BgPaySlipLineType settlement = type(710, new boolean[] {false, false, false});
    settlement.setBgSign(BgPaySlipResolver.CREDIT);
    BgPaySlipLine line = bareLine(settlement);
    // BgArt49SettlementService записва посоката СЛЕД lineFrom, по знака на колона 19.
    line.setBgSign(BgPaySlipResolver.DEBIT);
    slip.getBgPaySlipLineList().add(line);

    new Repo().fill(slip);

    assertEquals(
        BgPaySlipResolver.DEBIT,
        line.getBgSign(),
        "⚠️ ПРЕПИСАН ОТ РЕЧНИКА, знакът щеше да ВЪРНЕ пари на всеки, който дължи по чл. 49 ал. 5,"
            + " и да УДЪРЖИ от всеки, на когото се възстановява по ал. 6. Затова bgSign НЕ е сред"
            + " петте — и това е решение, а не пропуск.");
  }

  // ═══ ПОМОЩНИЦИ ═══════════════════════════════════════════════════════════

  /** Ред, какъвто екранът го записваше: тип избран, третиране на default="false". */
  private static BgPaySlipLine bareLine(BgPaySlipLineType type) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setBgAmount(new BigDecimal("100.00"));
    line.setBgSign(BgPaySlipResolver.CREDIT);
    return line;
  }

  private static BgPaySlipLineType type(int number, boolean[] flags) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setCode("pl_" + number);
    t.setBgSign(BgPaySlipResolver.CREDIT);
    t.setBgIsTaxable(Boolean.TRUE);
    t.setBgIsInsuranceIncome(Boolean.TRUE);
    t.setBgFormsGrossWage(flags[0]);
    t.setBgIsArt17Base(flags[1]);
    t.setBgIsPermanentCharacter(flags[2]);
    return t;
  }

  private static BgPaySlip draft() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setReference("BGPS-2026-12-001");
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** Репозиторият без база — вика се САМО преписването, което не пита нищо. */
  private static final class Repo extends BgPaySlipManagementRepository {
    void fill(BgPaySlip slip) {
      fillTreatmentFromType(slip);
    }
  }

  static {
    // котва: списъкът е седем, колкото са ръчните пера в 1xx
    if (MANUAL_LINES.size() != 7) {
      throw new IllegalStateException("очаквах седем ръчни пера, намерих " + MANUAL_LINES.size());
    }
    Arrays.asList(170, 175, 180, 181, 185, 186, 190).forEach(MANUAL_LINES::get);
  }
}
