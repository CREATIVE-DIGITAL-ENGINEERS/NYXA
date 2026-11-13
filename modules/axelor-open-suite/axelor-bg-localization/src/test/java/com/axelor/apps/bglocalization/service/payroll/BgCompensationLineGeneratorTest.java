package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕНЕРАТОРЪТ НА ГРУПА 9xx — обезщетението-документ става перо на фиша.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА.</b> {@code lineFrom} тук повтаря ДОСЛОВНО
 * копирането на {@code BgPaySlipService#lineFrom} — иначе тестът щеше да иска Guice, а
 * замразяването по копиране е точно това, което се проверява. Подменени са трите шева към базата:
 * документите, речникът и създаването на перото.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgCompensationLineGeneratorTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  /** Речникът и документите, подадени наготово. */
  private static final class Service extends BgCompensationLineService {

    /** Вид обезщетение → ред на речника. Липсващ ключ значи „речникът няма такъв ред“. */
    final Map<String, BgPaySlipLineType> catalogue = new LinkedHashMap<>();

    /** Документите, които входът обявява — вече филтрирани по статус, както прави заявката. */
    List<BgCompensation> documents = new ArrayList<>();

    /** ⚠️ Броено: шевът към базата не бива да се разкачи тихо. */
    int documentReads;

    @Override
    protected List<BgCompensation> documents(BgPayrollInput input) {
      documentReads++;
      if (input == null || input.compensationDocumentIds.isEmpty()) {
        return Collections.emptyList();
      }
      List<BgCompensation> out = new ArrayList<>();
      for (BgCompensation doc : documents) {
        // ⚠️ Филтърът е ДОСЛОВНО този на заявката: по идентификатор И по статус ПОТВЪРДЕН.
        if (input.compensationDocumentIds.contains(doc.getId())
            && BgCompensationResolver.CONFIRMED.equals(doc.getStatusSelect())) {
          out.add(doc);
        }
      }
      return out;
    }

    @Override
    protected BgPaySlipLineType lineTypeFor(BgCompensationType kind) {
      return kind == null ? null : catalogue.get(kind.getCode());
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      // ДОСЛОВНО копие на BgPaySlipService#lineFrom — замразяване чрез копиране.
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgAmount(amount);
      return line;
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  /**
   * Редът от bg_pay_slip_line_type_compensation.csv за чл. 222 ал. 1 КТ: 900, CREDIT, облагаем,
   * осиг.
   */
  private static BgPaySlipLineType type900() {
    return type(
        900, "pl_obez_kt_222_1", "Обезщетение за оставане без работа", "CREDIT", true, true);
  }

  /** Редът за чл. 221 ал. 2 КТ: 909, ⚠️ DEBIT — лицето дължи НА работодателя. */
  private static BgPaySlipLineType type909() {
    return type(
        909,
        "pl_obez_kt_221_2",
        "Обезщетение от работника при неспазено предизвестие",
        "DEBIT",
        false,
        false);
  }

  private static BgPaySlipLineType type(
      int number, String code, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setId((long) number);
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(false);
    t.setActive(true);
    return t;
  }

  private static BgCompensationType kind(String code, boolean taxable, boolean contributions) {
    BgCompensationType k = new BgCompensationType();
    k.setId((long) code.hashCode());
    k.setCode(code);
    k.setName(code);
    k.setIsTaxable(taxable);
    k.setSocialContributionsDue(contributions);
    return k;
  }

  private static BgCompensation doc(
      long id, String reference, BgCompensationType kind, String amount) {
    BgCompensation d = new BgCompensation();
    d.setId(id);
    d.setReference(reference);
    d.setBgCompensationType(kind);
    d.setBgFinalAmount(amount == null ? null : new BigDecimal(amount));
    d.setStatusSelect(BgCompensationResolver.CONFIRMED);
    // ⚠️ Флаговете на ДОКУМЕНТА се копират от вида при потвърждаване — точно както прави
    // BgCompensationService. Тестът за разминаване по-долу нарочно ги разминава.
    d.setBgIsTaxable(kind.getIsTaxable());
    d.setBgSocialContributionsDue(kind.getSocialContributionsDue());
    return d;
  }

  private static BgPaySlip slip() {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());
    return s;
  }

  private static BgPayrollInput input(Long... ids) {
    BgPayrollInput.Builder b =
        BgPayrollInput.builder()
            .insuredTypeCode("01")
            .laborCategory("3")
            .periodFrom(FROM)
            .periodTo(TO)
            .parameterReferenceDate(FROM);
    for (Long id : ids) {
      b.compensationDocumentId(id);
    }
    return b.build();
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number) {
        return line;
      }
    }
    return null;
  }

  // ═══ 1 · ПЕРОТО СЕ ПОЯВЯВА, С ФЛАГОВЕТЕ НА ТИПА ═══════════════════════════

  /**
   * ⚠️ FAIL-FIRST: преди {@link BgCompensationLineService} да съществува, фишът оставаше с нула
   * пера и този тест падаше на {@code assertNotNull}.
   */
  @Test
  @DisplayName("⚠️ потвърдено обезщетение става перо 9xx със сумата, замразена върху документа")
  void aConfirmedCompensationBecomesALine() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));

    assertEquals(1, outcomes.size(), "по един отговор за всеки документ");
    assertEquals(
        BgPaySlipLineOutcome.Status.COMPUTED,
        outcomes.get(0).status,
        String.valueOf(outcomes.get(0).detail));
    assertEquals(1, s.documentReads, "шевът към документите не е извикан — генераторът е РАЗКАЧЕН");

    BgPaySlipLine line = lineOf(slip, 900);
    assertNotNull(line, "перото трябва да е на фиша");
    assertEquals(
        0,
        new BigDecimal("1450.00").compareTo(line.getBgAmount()),
        "⚠️ СУМАТА СЕ КОПИРА ОТ bgFinalAmount, не се смята. Намерено: " + line.getBgAmount());
    assertEquals("pl_obez_kt_222_1", line.getBgPaySlipLineType().getCode());
    assertEquals("Обезщетение за оставане без работа", line.getName(), "името е ОТ ТИПА");
    assertEquals("CREDIT", line.getBgSign(), "знакът е ОТ ТИПА");
    assertTrue(line.getBgIsTaxable(), "облагаемостта е ОТ ТИПА");
    assertTrue(line.getBgIsInsuranceIncome(), "осигурителният характер е ОТ ТИПА");
    assertEquals(slip, line.getPaySlip(), "закачено и от двете страни на връзката");
    assertEquals(11L, line.getBgCompensation().getId(), "препратката към документа Е ключът");
  }

  /**
   * ⚠️ ЗНАКЪТ Е НА ТИПА, А СУМАТА ОСТАВА ПОЛОЖИТЕЛНА — две от двайсет и петте пера са DEBIT.
   *
   * <p>Обезщетението по чл. 221 ал. 2 КТ се дължи ОТ лицето НА работодателя. Записано с отрицателна
   * сума и знак CREDIT, сборът щеше да е същият, а фишът щеше да се чете обратно.
   */
  @Test
  @DisplayName(
      "⚠️ DEBIT обезщетение (чл. 221 ал. 2 КТ) намалява нетото, а сумата остава положителна")
  void aDebitCompensationKeepsAPositiveAmount() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_221_2", false, false);
    s.catalogue.put("comp_kt_221_2", type909());
    s.documents = Arrays.asList(doc(12L, "OBEZ-2026-0002", kind, "600.00"));
    BgPaySlip slip = slip();

    s.generate(slip, input(12L));
    BgPaySlipLine line = lineOf(slip, 909);

    assertNotNull(line);
    assertEquals("DEBIT", line.getBgSign());
    assertEquals(
        1, line.getBgAmount().signum(), "⚠️ ПОЛОЖИТЕЛНА — знакът е на перото, не на сумата");
    assertEquals(
        0,
        new BigDecimal("-600.00")
            .compareTo(BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList())),
        "DEBIT перо намалява нетото");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList())),
        "и НЕ влиза в брутото — там влизат само CREDIT перата от 1xx и 9xx");
  }

  // ═══ 2 · ИДЕМПОТЕНТНОСТ И АДИТИВНОСТ ══════════════════════════════════════

  /**
   * ⚠️ КЛЮЧЪТ Е ПРЕПРАТКАТА, НЕ КОДЪТ — и точно затова два документа от ЕДИН вид дават ДВА реда.
   *
   * <p>Поправката на обезщетение става чрез отказ и НОВ документ, тоест няколко документа за една и
   * съща двойка (договор, вид) са нормално състояние. Ключ по код щеше да препише първия ред с
   * втория и едно от двете обезщетения щеше да изчезне тихо.
   */
  @Test
  @DisplayName("⚠️ ДВА документа от ЕДИН вид дават ДВА реда — ключът е препратката, не кодът")
  void twoDocumentsOfOneKindProduceTwoLines() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents =
        Arrays.asList(
            doc(11L, "OBEZ-2026-0001", kind, "1000.00"),
            doc(12L, "OBEZ-2026-0002", kind, "450.00"));
    BgPaySlip slip = slip();

    s.generate(slip, input(11L, 12L));

    assertEquals(
        2,
        slip.getBgPaySlipLineList().size(),
        "⚠️ ДВА реда. Един значи, че вторият документ е ПРЕПИСАЛ първия — 450 вместо 1450 общо.");
    assertEquals(
        0,
        new BigDecimal("1450.00")
            .compareTo(BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList())),
        "1000 + 450");
  }

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · три пъти generate() дава един ред с една сума")
  void generateIsIdempotent() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    BgPaySlip slip = slip();

    s.generate(slip, input(11L));
    s.generate(slip, input(11L));
    s.generate(slip, input(11L));

    assertEquals(1, slip.getBgPaySlipLineList().size(), "три реда значат, че ключът не работи");
    assertEquals(
        0,
        new BigDecimal("1450.00").compareTo(lineOf(slip, 900).getBgAmount()),
        "⚠️ ПРИСВОЯВА СЕ, не се натрупва — 4350,00 тук значи адитивен upsert");
  }

  /**
   * ⚠️ ИДЕМПОТЕНТНОСТТА ТРЯБВА ДА ИЗДЪРЖИ И ДРУГА ИНСТАНЦИЯ НА СЪЩИЯ РЕД.
   *
   * <p>{@code prepare()} чете документите ОТ БАЗАТА наново, тоест второто извикване получава други
   * Java обекти за същия ред. Сравнение само по обект щеше да мине този тест като „нов документ“ и
   * да създаде втори ред — при всяко отваряне на фиша.
   */
  @Test
  @DisplayName("⚠️ второ четене на СЪЩИЯ документ (друга инстанция) не създава втори ред")
  void aReReadDocumentDoesNotDuplicateTheLine() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    BgPaySlip slip = slip();
    s.generate(slip, input(11L));

    // Същият ред, прочетен наново: друг обект, същият идентификатор.
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    s.generate(slip, input(11L));

    assertEquals(
        1,
        slip.getBgPaySlipLineList().size(),
        "⚠️ Сравнение само по обект дава ДВА реда тук — тоест двойно обезщетение при всяко prepare()");
  }

  @Test
  @DisplayName("⚠️ АДИТИВНО · ръчно въведените пера преживяват generate()")
  void manualLinesSurvive() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    BgPaySlip slip = slip();
    BgPaySlipLine manual =
        s.lineFrom(
            type(100, "pl_osnovna", "Основно възнаграждение", "CREDIT", true, true),
            new BigDecimal("1500.00"));
    s.addLine(slip, manual);

    s.generate(slip, input(11L));

    assertEquals(2, slip.getBgPaySlipLineList().size());
    assertTrue(slip.getBgPaySlipLineList().contains(manual), "ръчното перо е пипнато или махнато");
    assertEquals(0, new BigDecimal("1500.00").compareTo(manual.getBgAmount()));
  }

  // ═══ 3 · ГРАНИЦИТЕ: РЕТРО И СТАТУС ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ коригиращ и заличаващ фиш се ПРОПУСКАТ — ретро-копието вече носи перата")
  void retroSlipsAreSkipped() {
    BgCompensationType kind = kind("comp_kt_222_1", true, true);

    for (String flag : Arrays.asList("correction", "deletion")) {
      Service s = new Service();
      s.catalogue.put("comp_kt_222_1", type900());
      s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
      BgPaySlip slip = slip();
      if ("correction".equals(flag)) {
        slip.setBgIsCorrection(true);
      } else {
        slip.setBgIsDeletion(true);
      }

      List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));

      assertTrue(outcomes.isEmpty(), flag + ": трябва да се пропусне");
      assertTrue(
          slip.getBgPaySlipLineList().isEmpty(),
          "⚠️ " + flag + ": перо тук би пренаписало историята с ДНЕШНАТА сума на документа");
      assertEquals(0, s.documentReads, "документите дори не се четат");
    }
  }

  @Test
  @DisplayName("потвърден фиш не стига до генератора")
  void aConfirmedSlipIsSkipped() {
    Service s = new Service();
    BgPaySlip slip = slip();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    assertTrue(s.generate(slip, input(11L)).isEmpty());
    assertEquals(0, s.documentReads);
  }

  /**
   * ⚠️ ОТКАЗАН ДОКУМЕНТ НЕ СТАВА ПЕРО, дори когато входът още го обявява.
   *
   * <p>Входът се сглобява веднъж; между сглобяването и записването документът може да е бил
   * отказан. Тук се пишат пари.
   */
  @Test
  @DisplayName("⚠️ отказан документ не става перо, макар входът да го обявява")
  void aCancelledDocumentProducesNoLine() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    BgCompensation cancelled = doc(11L, "OBEZ-2026-0001", kind, "1450.00");
    cancelled.setStatusSelect(BgCompensationResolver.CANCELLED);
    s.documents = Arrays.asList(cancelled);
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));

    assertTrue(outcomes.isEmpty(), "отказаният документ изобщо не влиза в обхода");
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  // ═══ 4 · ОТКАЗИТЕ ═════════════════════════════════════════════════════════

  /**
   * ⚠️ ЛИПСВАЩ РЕД В РЕЧНИКА Е ИЗКЛЮЧЕНИЕ, НЕ ТИХО ПРЕСКАЧАНЕ.
   *
   * <p>Същият избор като {@code BgEarningsService#upsert} и {@code BgSickLeaveService#upsert}: това
   * не е дефект на документа, а дупка в seed-а, засягаща ВСЕКИ фиш с такъв вид.
   */
  @Test
  @DisplayName("⚠️ вид без ред в речника вдига ИЗКЛЮЧЕНИЕ, което сочи файла и App Install")
  void aKindWithoutADictionaryRowThrows() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    // ⚠️ Речникът е ПРАЗЕН — App Install не е пускан след добавянето на вида в каталога.
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));
    BgPaySlip slip = slip();

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> s.generate(slip, input(11L)));

    assertTrue(e.getMessage().contains("comp_kt_222_1"), e.getMessage());
    assertTrue(e.getMessage().contains("bg_pay_slip_line_type_compensation.csv"), e.getMessage());
    assertTrue(
        e.getMessage().contains("OBEZ-2026-0001"), "отказът НАЗОВАВА документа: " + e.getMessage());
    assertTrue(e.getMessage().contains("App Install"), e.getMessage());
    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "⚠️ и БЕЗТИПОВ ред НЕ се създава");
  }

  /**
   * ⚠️ НУЛА И ОТРИЦАТЕЛНО СЕ ОТКАЗВАТ, И ОТКАЗЪТ НАЗОВАВА ДОКУМЕНТА.
   *
   * <p>Проверката е {@code signum()}, а не {@code == null}: генерираният getter за decimal връща
   * ZERO, тоест проверка за null тук е мъртъв код (виж {@code BgDefaultedGetterTest}). Точно затова
   * подаденият по-долу {@code null} се държи като нула.
   */
  @Test
  @DisplayName("⚠️ сума нула, null или отрицателна → ОТКАЗ с номера на документа, без перо")
  void aMissingOrNegativeAmountIsRefused() {
    BgCompensationType kind = kind("comp_kt_222_1", true, true);

    for (String amount : Arrays.asList("0.00", null, "-10.00")) {
      Service s = new Service();
      s.catalogue.put("comp_kt_222_1", type900());
      s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, amount));
      BgPaySlip slip = slip();

      List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));

      assertEquals(1, outcomes.size());
      assertEquals(
          BgPaySlipLineOutcome.Status.REFUSED,
          outcomes.get(0).status,
          "сума „" + amount + "“ трябва да е ОТКАЗ, а не законна нула");
      assertNull(outcomes.get(0).amount, "⚠️ сума при отказ би се сумирала някъде");
      assertTrue(
          outcomes.get(0).detail.contains("OBEZ-2026-0001"),
          "отказът НАЗОВАВА документа — фишът може да носи няколко: " + outcomes.get(0).detail);
      assertTrue(slip.getBgPaySlipLineList().isEmpty(), "перо НЕ се създава");
    }
  }

  @Test
  @DisplayName("документ без вид се отказва, вместо да стане безтипов ред")
  void aDocumentWithoutAKindIsRefused() {
    Service s = new Service();
    BgCompensation broken = doc(11L, "OBEZ-2026-0001", kind("comp_kt_222_1", true, true), "100.00");
    broken.setBgCompensationType(null);
    s.documents = Arrays.asList(broken);
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));

    assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status);
    assertTrue(outcomes.get(0).detail.contains("OBEZ-2026-0001"), outcomes.get(0).detail);
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  // ═══ 5 · РАЗМИНАВАНЕТО ТИП ↔ ДОКУМЕНТ ═════════════════════════════════════

  /**
   * ⚠️ РАЗМИНАВАНЕТО СЕ ОБЯВЯВА, ПЕРОТО СЕ ЗАПИСВА ПО РЕЧНИКА.
   *
   * <p>Нито отказ (би спрял изплащане по причина, за която лицето не отговаря), нито мълчание
   * (фишът щеше да плаща по едни флагове, документът да твърди други). Виж {@code
   * BgPaySlipLineOutcome.Status#DIVERGENT}.
   */
  @Test
  @DisplayName("⚠️ документ с ДРУГО третиране от речника → перо ПО РЕЧНИКА + обявено разминаване")
  void aDivergentDocumentStillProducesALineFromTheDictionary() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    BgCompensation doc = doc(11L, "OBEZ-2026-0001", kind, "1450.00");
    // Документът е потвърден ПРЕДИ каталогът да се поправи: и двата флага са другите.
    doc.setBgIsTaxable(false);
    doc.setBgSocialContributionsDue(false);
    s.documents = Arrays.asList(doc);
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, input(11L));
    BgPaySlipLineOutcome outcome = outcomes.get(0);

    assertEquals(
        BgPaySlipLineOutcome.Status.DIVERGENT, outcome.status, "нито COMPUTED, нито REFUSED");
    assertTrue(outcome.isComputed(), "⚠️ перото ВСЕ ПАК се записва");
    assertTrue(outcome.detail.contains("OBEZ-2026-0001"), outcome.detail);
    assertTrue(outcome.detail.contains("облагаемост"), outcome.detail);
    assertTrue(outcome.detail.contains("осигурителен доход"), outcome.detail);

    BgPaySlipLine line = lineOf(slip, 900);
    assertNotNull(line, "⚠️ разминаването НЕ спира изплащането");
    assertTrue(line.getBgIsTaxable(), "флагът е ОТ РЕЧНИКА, не от документа");
    assertTrue(line.getBgIsInsuranceIncome(), "флагът е ОТ РЕЧНИКА, не от документа");
  }

  @Test
  @DisplayName("съвпадащи флагове НЕ вдигат разминаване — иначе всеки фиш би шумял")
  void matchingFlagsAreSilent() {
    Service s = new Service();
    BgCompensationType kind = kind("comp_kt_222_1", true, true);
    s.catalogue.put("comp_kt_222_1", type900());
    s.documents = Arrays.asList(doc(11L, "OBEZ-2026-0001", kind, "1450.00"));

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip(), input(11L));

    assertEquals(BgPaySlipLineOutcome.Status.COMPUTED, outcomes.get(0).status);
    assertNull(outcomes.get(0).detail, "чисто сметнатото перо не носи обяснение");
  }

  // ═══ 6 · ПРАЗНИЯТ ВХОД ════════════════════════════════════════════════════

  @Test
  @DisplayName("празен вход и null вход дават празен списък, без заявка")
  void anEmptyInputProducesNothing() {
    Service s = new Service();

    assertTrue(s.generate(slip(), input()).isEmpty(), "празен списък документи");
    assertTrue(s.generate(slip(), null).isEmpty(), "null вход");
  }
}
