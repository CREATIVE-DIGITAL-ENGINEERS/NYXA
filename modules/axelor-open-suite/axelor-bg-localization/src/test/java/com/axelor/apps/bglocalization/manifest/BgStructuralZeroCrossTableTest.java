package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ ПРЕЗ ДВЕ ТАБЛИЦИ: каквото речникът на фиша обявява за СТРУКТУРНО НЕПЪЛНИМО,
 * таблицата с вноските не го пълни — освен трите известни разминавания, изброени поименно долу.
 *
 * <p><b>Защо кръстосан.</b> Двата файла днес си говорят само в проза. {@code
 * bg_pay_slip_line_type_contribution.csv} твърди за някои пера „СТРУКТУРНА НУЛА — дял за сметка на
 * това лице не съществува и този ред не може да се напълни“, а числата, които биха го опровергали,
 * живеят в {@code bg_insurance_contribution.csv}. Твърдение в единия файл и данни в другия не се
 * сверяват от нищо — и точно затова остаряват безшумно.
 *
 * <p><b>Как остарява.</b> Бележка може сама да запише условието си: „чл. 4а ал. 1 КСО са МОРСКИТЕ
 * ЛИЦА и при тях структурната нула НЕ важи… морско лице не е моделирано в данните към днес. Ако се
 * появи, редът трябва да се раздели, а не да се препише.“ Когато условието настъпи (вид осигурен
 * 92), никой не се връща към бележката. Този тест е връщането — постоянно и автоматично.
 *
 * <p><b>⚠️ ВНИМАНИЕ ПРИ ТЪРСЕНЕТО: ОТРИЧАНЕТО СЪДЪРЖА ТВЪРДЕНИЕТО ДОСЛОВНО.</b> Речникът носи и „⚠️
 * НЕ Е СТРУКТУРНА НУЛА — ПРАЗЕН Е ЗА ВСИЧКИ ОСВЕН ЗА МОРСКИТЕ ЛИЦА“ (PLT-513, ППФ). Търсене само по
 * подниза {@code "СТРУКТУРНА НУЛА"} хваща и този ред и го обявява за нарушен — тоест докладва като
 * противоречие точно реда, който сам е записал изключението си. Без филтъра за отречената форма
 * тестът дава пет разминавания вместо три, а двете в повече са IC-139 и IC-146. <b>Затова {@link
 * #NEGATED_MARKER} не е излишна предпазливост и не се „опростява“.</b> Ако някой ден маркерът се
 * смени, сменя се и отречената му форма — двете се движат заедно.
 *
 * <p><b>Защо СТРУКТУРНАТА, а не всяка нула.</b> {@code LEGAL_ZERO} е нула ЗА ПЕРИОДА и е нарочно
 * пълнима: бележката за ГВРС изрично казва, че възстановяването става с НОВ ред с нов {@code
 * validFrom}. Ненулева законова ставка не е противоречие. Само структурната твърди „никога“, и само
 * тя може да бъде опровергана от един ред с число.
 *
 * <p><b>Посоката се чете от данните, не се помни.</b> Платецът идва от {@code bgSign} (CREDIT = за
 * сметка на осигурителя, DEBIT = за сметка на лицето) — същото съответствие, с което {@code
 * tools/generate_payslip_line_types.py} генерира перата. Фондът идва от {@code bgContributionFund}.
 */
class BgStructuralZeroCrossTableTest {

  private static final String DICTIONARY = "bg_pay_slip_line_type_contribution.csv";
  private static final String CONTRIBUTIONS = "bg_insurance_contribution.csv";

  private static final String STRUCTURAL_MARKER = "СТРУКТУРНА НУЛА";

  /** Отречената форма — виж предупреждението в javadoc-а на класа. Движи се заедно с маркера. */
  private static final String NEGATED_MARKER = "НЕ Е " + STRUCTURAL_MARKER;

  /** CREDIT = осигурител, DEBIT = лице — съответствието от генератора на речника. */
  private static final String SIGN_EMPLOYER = "CREDIT";

  /**
   * ⚠️ ИЗКЛЮЧЕНИЯТА СЕ ПИШАТ С ПРИЧИНА, ИНАК СА КРИЕНЕ НА ГРЕШКА.
   *
   * <p>Трите реда долу са ИСТИНСКИ разминавания, не фалшива тревога. Записани са поименно, защото
   * поправката им пипа речника на фиша и механизма на генератора, тоест чужд seed със свои тестове.
   * Червен тест, който стои червен, учи да не се гледа червено — затова известното е изброено, а не
   * заглушено с широко правило.
   *
   * <p>⚠️ И ЗАЩО НЕ СЕ ПОПРАВЯ С ПУСКАНЕ НА ГЕНЕРАТОРА. Пуснат, {@code
   * tools/generate_payslip_line_types.py} ИЗТРИВА бележките на PLT-509, PLT-511 и PLT-513 изцяло.
   * Портата, която ги закача, виси на ПРЕБРОЕНИ ненулеви редове, а морските лица правят и трите
   * брояча ненулеви. Тоест трите сверени цитата са писани на ръка в CSV-то и генераторът не може да
   * ги възпроизведе. Поправката е портата да се закача за ОБЯВЕНИЯ вид ({@code bgEmployerRateKind}
   * / {@code bgEmployeeRateKind}), а не за преброени нули.
   */
  private static final Map<String, String> KNOWN_MISMATCH = new LinkedHashMap<>();

  static {
    String tzpb =
        "ТЗПБ/лице при вид осигурен 92: PLT-509 още твърди „морско лице не е моделирано в данните"
            + " към днес“, а вид осигурен 92 съществува. Самата бележка записва условието —"
            + " „ако се появи, редът трябва да се раздели“. Поправя се заедно с портата на"
            + " генератора.";
    KNOWN_MISMATCH.put("IC-132-2026", tzpb);
    KNOWN_MISMATCH.put("IC-138-2026", tzpb);
    KNOWN_MISMATCH.put("IC-145-2026", tzpb);
  }

  /** Перо, което обявява една страна на един фонд за структурно непълнима. */
  private static final class Claim {
    final String lineTypeId;
    final String rateColumn;
    final String payer;

    Claim(String lineTypeId, String rateColumn, String payer) {
      this.lineTypeId = lineTypeId;
      this.rateColumn = rateColumn;
      this.payer = payer;
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: структурната нула се пълни точно от трите известни реда")
  void theCrossTableMismatchesAreExactlyTheKnownOnes() {
    Map<String, Claim> claims = structuralClaims();
    if (claims.isEmpty()) {
      fail(
          "речникът "
              + DICTIONARY
              + " не обявява нито една структурна нула — или маркерът „"
              + STRUCTURAL_MARKER
              + "“ се е сменил, или твърдението е изчезнало. Тест, който няма какво да провери,"
              + " минава от празнота, а не от вярност");
    }

    List<String> head = BgSeed.header(CONTRIBUTIONS);
    Set<String> seedIds = new LinkedHashSet<>();
    Map<String, String> found = new LinkedHashMap<>();

    for (String[] row : BgSeed.rows(CONTRIBUTIONS)) {
      String id = BgSeed.col(head, row, "importId");
      seedIds.add(id);

      Claim claim = claims.get(BgSeed.col(head, row, "fundCode"));
      if (claim == null) {
        continue;
      }
      String raw = BgSeed.col(head, row, claim.rateColumn);
      if (new BigDecimal(raw).signum() == 0) {
        continue;
      }
      found.put(
          id,
          String.format(
              "вид осигурен %s, категория %s: %s = %s, а %s обявява %s за структурно непълним",
              BgSeed.col(head, row, "bgInsuredTypeCode"),
              BgSeed.col(head, row, "laborCategory"),
              claim.rateColumn,
              raw,
              claim.lineTypeId,
              claim.payer));
    }

    List<String> problems = new ArrayList<>();

    // ⚠️ ПЪТ 1 — появило се е ново разминаване, което никой не е обявявал.
    for (String id : new TreeSet<>(found.keySet())) {
      if (!KNOWN_MISMATCH.containsKey(id)) {
        problems.add(
            "НОВО разминаване, което не е в KNOWN_MISMATCH: "
                + id
                + " ("
                + found.get(id)
                + "). Или речникът вече не бива да твърди „не може да се напълни“, или редът носи"
                + " ставка на грешния платец. Ако е известно и нарочно — впиши го С ПРИЧИНА.");
      }
    }

    // ⚠️ ПЪТ 2 — известно разминаване е изчезнало, тоест поправено е и записът е излишен.
    // ⚠️ ПЪТ 3 — записът сочи ред, който вече не съществува в seed-а.
    for (String id : KNOWN_MISMATCH.keySet()) {
      if (found.containsKey(id)) {
        continue;
      }
      if (seedIds.contains(id)) {
        problems.add(
            "ПОПРАВЕНО разминаване, още записано като известно: "
                + id
                + " вече не противоречи на речника. Махни го от KNOWN_MISMATCH — списък, който"
                + " надживява причината си, започва да крие следващата грешка.");
      } else {
        problems.add(
            "ОСТАРЯЛ запис в KNOWN_MISMATCH: реда "
                + id
                + " го няма в "
                + CONTRIBUTIONS
                + ". Изключение за несъществуващ ред не пази нищо и заблуждава, че пази.");
      }
    }

    if (!problems.isEmpty()) {
      fail(
          "наборът известни разминавания се е променил ("
              + problems.size()
              + "):\n  "
              + String.join("\n  ", problems)
              + "\n\nизвестни днес: "
              + KNOWN_MISMATCH.keySet()
              + "\nнамерени сега: "
              + found.keySet());
    }
  }

  /** Перата, чиято бележка обявява страната им за структурно непълнима, по фонд. */
  private static Map<String, Claim> structuralClaims() {
    List<String> head = BgSeed.header(DICTIONARY);
    Map<String, Claim> byFund = new LinkedHashMap<>();

    for (String[] row : BgSeed.rows(DICTIONARY)) {
      String note = BgSeed.col(head, row, "note");
      if (!note.contains(STRUCTURAL_MARKER) || note.contains(NEGATED_MARKER)) {
        continue;
      }
      String fund = BgSeed.col(head, row, "bgContributionFund");
      boolean employer = SIGN_EMPLOYER.equals(BgSeed.col(head, row, "bgSign"));
      Claim claim =
          new Claim(
              BgSeed.col(head, row, "importId"),
              employer ? "employerRate" : "employeeRate",
              employer ? "осигурителя" : "лицето");

      Claim previous = byFund.put(fund, claim);
      if (previous != null) {
        fail(
            "фонд "
                + fund
                + " има ДВЕ пера с обявена структурна нула ("
                + previous.lineTypeId
                + " и "
                + claim.lineTypeId
                + ") — тогава никой от двамата платци не плаща и фондът не съществува."
                + " Този тест не знае кое от двете да провери");
      }
    }
    return byFund;
  }
}
