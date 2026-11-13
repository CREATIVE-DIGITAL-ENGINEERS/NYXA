/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: четирите референтни таблици на SAF-T се сверяват срещу ТРИТЕ СИ ИЗТОЧНИКА и срещу
 * номенклатурите на НАП — всеки бизнес-код оттам, всеки код по НАП оттам, нищо измислено.
 *
 * <p><b>Защо изобщо съществува.</b> Таблиците са ДАННИ: сметкопланът, данъчният каталог и режимите
 * на плащане живеят в <b>друг модул</b> ({@code axelor-account}), а кодовете по НАП — в {@code
 * nomenclatures/nra/}. Вносителят не вижда нито едното: входовете в {@code
 * bg-localization-config.xml} нямат {@code search=} към чуждите таблици, защото връзката е по КОД,
 * не по външен ключ. Тоест разминаване между таблица и източник НЕ гърми при импорт — получава се
 * ред, който сочи сметка, данък или режим, какъвто няма. Този тест е единственото място, където
 * двете страни се поставят една срещу друга.
 *
 * <p><b>Защо е файлов, а не през базата.</b> Въпросът „покрива ли таблицата сметкоплана“ има
 * отговор от двата файла; база не е нужна, за да се отговори, а манифестните тестове се пропускат,
 * когато App Install не е пускан — тоест точно този инвариант щеше да мълчи в CI.
 *
 * <p><b>Защо твърденията са за СЪВПАДЕНИЕ на набори, а не за брой.</b> „457 реда“ минава и когато
 * една сметка е в таблицата два пъти, а друга липсва. Броят е следствие; равенството на двата
 * набора е самото твърдение, и провалът му назовава кода, не числото.
 */
class BgSafMappingSeedTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path ACCOUNTS = COA.resolve("account_account.csv");
  private static final Path TAXES = COA.resolve("account_tax.csv");
  private static final Path PAYMENT_MODES = COA.resolve("account_paymentMode.csv");

  private static final String ACC_MAP = "bg_saf_account_mapping.csv";
  private static final String TAX_MAP = "bg_saf_tax_mapping.csv";
  private static final String PAY_MAP = "bg_saf_payment_mapping.csv";
  private static final String CELL_MAP = "bg_saf_vat_cell_code.csv";

  private static final String NOM = "/nomenclatures/nra/";

  /**
   * Кодът по НАП, както го ограничава схемата: {@code SAFAccountType} е {@code [1-9][0-9]{2,4}} —
   * три до пет цифри без водеща нула (BG_SAFT_Schema_V_1.0.2.xsd, ред 4352). ⚠️ Това е ФОРМАТ, не
   * класификация: въпреки името си типът не казва нищо за вида на сметката.
   */
  private static final Pattern SAF_ACCOUNT = Pattern.compile("^[1-9][0-9]{2,4}$");

  /** TAX-IMP кодовете са точно шест цифри — същият образец, който пази и номенклатурата. */
  private static final Pattern TAX_CODE = Pattern.compile("^[0-9]{6}$");

  /** Поле от дневника: раздел 02 (продажби) или 03 (покупки) плюс номер. */
  private static final Pattern CELL = Pattern.compile("^0[23]-[0-9]{2}$");

  /**
   * Изключените са точно {@code view} и {@code special} — репортните възли и задбалансовите. Това е
   * ТИПЪТ, а не списък: списък би трябвало да се дописва при всяка нова сметка.
   */
  private static final List<String> STRUCTURAL_TYPES = Arrays.asList("1", "14");

  /** Петте правила, по които сметка стига до кода си. Пети няма; шести би бил мълчалив. */
  private static final Set<String> RULES =
      new LinkedHashSet<>(
          Arrays.asList("IDENTITY", "ROLLUP", "EXPLICIT", "CONFIRMED", "STRUCTURAL"));

  /**
   * Седемте данъка БЕЗ установен код по TAX-IMP — изброени поименно, защото празнотата им е
   * ИЗЯВЛЕНИЕ, а не пропуск. Празният код е ГРЕШКА в дневника (QA IV.2.3), не предупреждение.
   * BG_ZRS (100214), BG_OSS_EU и BG_MOSS (100218) са попълнени — кодът им е еднозначен и същият
   * като на рутера на фактурите (Блок Б к.19 и к.23, описанията в TAX-IMP). Останалите зависят от
   * сделката (ставка, страна, основна доставка) или чакат НАП (BG_PROTO_151V).
   *
   * <p>⚠️ Точно затова тестът ги брои в ДВЕТЕ посоки: тези десет трябва да са празни, и никой друг
   * не бива да е. Еднопосочно твърдение („не повече от 10 празни“) би минало и ако утре някой
   * изпразни BG_VOD, за да „мине“ импортът.
   *
   * <p>⚠️ И НЕ СЕ ПЪЛНЯТ С 000000. Черновата предлагаше правило „няма код → TaxType 000, TaxCode
   * 000000“. То е отхвърлено по измерима причина: 000000 ГО НЯМА сред 169-те реда на {@code
   * tax_imp.csv}, тоест такъв ред би минал за попълнен и би се счупил чак при НАП.
   */
  private static final Set<String> TO_CONFIRM =
      new LinkedHashSet<>(
          Arrays.asList(
              "BG_IMP_ART57",
              "BG_ADJ_ART79",
              "BG_ADJ_ART79A",
              "BG_ADJ_CREDIT",
              "BG_ADJ_DEBIT",
              "BG_PROTO_151V",
              "BG_PROTO_117"));

  /**
   * Петте кода, при които аритметиката „код = 100 + раздел + номер на поле“ НЕ важи.
   *
   * <p>Четирите по чл. 163а: последните две цифри не са номер на поле, а СТОЙНОСТ в едно поле —
   * „Доставка по чл. 163а ЗДДС“ приема {@code 01} (част I на Приложение №2) или {@code 02} (част
   * II). Тоест 100227 и 100228 делят поле 02-27, а 100345 и 100346 делят поле 03-45.
   *
   * <p>⚠️ И 100242 „Годишна корекция по чл. 73, ал. 8 от ЗДДС“ (TAX-IMP) — КАПАНЪТ „100 + НОМЕР“.
   * По ППЗДДС Прил. № 12 годишната корекция е поле 03-43 на POKUPKI (к.14, число ≥ 0 или &lt; 0), а
   * в DEKLAR 01-43 = 03-43. Аритметиката би дала 02-42 — поле, което PRODAGBI НЯМА (там полетата за
   * стойност са 02-10 … 02-25), а от клетката 03-43 — несъществуващия код 100343.
   */
  private static final Map<String, String[]> CELL_EXCEPTIONS = new LinkedHashMap<>();

  static {
    CELL_EXCEPTIONS.put("100227", new String[] {"02-27", "01"});
    CELL_EXCEPTIONS.put("100228", new String[] {"02-27", "02"});
    CELL_EXCEPTIONS.put("100345", new String[] {"03-45", "02"});
    CELL_EXCEPTIONS.put("100346", new String[] {"03-45", "01"});
    CELL_EXCEPTIONS.put("100242", new String[] {"03-43", ""});
  }

  // ═══════════════════════════ СМЕТКА → КОД ПО НАП ═══════════════════════════

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: всяка сметка от BG_COA има РЕД в таблицата, и нито един повече")
  void всяка_сметка_има_ред() {
    List<String> h = BgSeed.header(ACC_MAP);
    Set<String> mapped = new TreeSet<>();
    List<String> duplicates = new ArrayList<>();
    for (String[] row : BgSeed.rows(ACC_MAP)) {
      String code = BgSeed.col(h, row, "accountCode");
      if (!mapped.add(code)) {
        duplicates.add(code);
      }
    }
    assertEquals(
        Collections.emptyList(),
        duplicates,
        "една сметка с ДВА реда в мапинга: "
            + duplicates
            + ". Вторият ред печели при upsert, а първият остава да виси в базата — и двата кода"
            + " изглеждат внесени.");

    Set<String> chart = new TreeSet<>();
    List<String> ch = header(ACCOUNTS);
    for (String[] row : rows(ACCOUNTS)) {
      chart.add(col(ch, row, "code"));
    }

    Set<String> missing = new TreeSet<>(chart);
    missing.removeAll(mapped);
    Set<String> extra = new TreeSet<>(mapped);
    extra.removeAll(chart);

    assertEquals(
        Collections.emptySet(),
        missing,
        "сметки от BG_COA без ред в мапинга: "
            + missing
            + ". Липсващият ред не се различава от забравен: износът към SAF-T просто не намира"
            + " код и сметката изпада безшумно. Всяка сметка е или мапната, или ИЗРИЧНО изключена.");
    assertEquals(
        Collections.emptySet(),
        extra,
        "мапинг към сметка, която я НЯМА в BG_COA: "
            + extra
            + ". Такъв ред не гърми при импорт — просто никога не се чете, и остава да изглежда"
            + " като покритие.");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: изключени са ТОЧНО view и special — границата е типът, не списък")
  void изключените_съвпадат_с_репортните_и_задбалансовите() {
    Set<String> structural = new TreeSet<>();
    List<String> ch = header(ACCOUNTS);
    for (String[] row : rows(ACCOUNTS)) {
      if (STRUCTURAL_TYPES.contains(col(ch, row, "accountType.importId"))) {
        structural.add(col(ch, row, "code"));
      }
    }

    Set<String> excluded = new TreeSet<>();
    List<String> h = BgSeed.header(ACC_MAP);
    for (String[] row : BgSeed.rows(ACC_MAP)) {
      if ("true".equals(BgSeed.col(h, row, "isExcluded"))) {
        excluded.add(BgSeed.col(h, row, "accountCode"));
      }
    }

    Set<String> notExcluded = new TreeSet<>(structural);
    notExcluded.removeAll(excluded);
    Set<String> wronglyExcluded = new TreeSet<>(excluded);
    wronglyExcluded.removeAll(structural);

    assertEquals(
        Collections.emptySet(),
        notExcluded,
        "репортен възел или задбалансова сметка с код по НАП: "
            + notExcluded
            + ". Възел със собствен код значи един и същи оборот, изнесен и на листото, и на"
            + " родителя — сборът при НАП го брои ДВА пъти.");
    assertEquals(
        Collections.emptySet(),
        wronglyExcluded,
        "ОСЧЕТОВОДИМА сметка, обявена за структурна: "
            + wronglyExcluded
            + ". Оборотът ѝ изчезва от износа мълчаливо — това е точно обратната грешка и се вижда"
            + " още по-трудно.");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: всеки код по НАП съществува в номенклатурата и пасва на схемата")
  void всеки_нап_код_е_от_номенклатурата() {
    Set<String> nra = new TreeSet<>();
    for (CSVRecord r : nomenclature("nra_nom_accounts.csv")) {
      nra.add(r.get(0).trim());
    }
    assertTrue(nra.size() > 300, "номенклатурата на НАП дава само " + nra.size() + " кода");

    List<String> unknown = new ArrayList<>();
    List<String> malformed = new ArrayList<>();
    List<String> mismatched = new ArrayList<>();
    List<String> emptyButMapped = new ArrayList<>();
    List<String> codedButExcluded = new ArrayList<>();
    List<String> badRule = new ArrayList<>();

    List<String> h = BgSeed.header(ACC_MAP);
    for (String[] row : BgSeed.rows(ACC_MAP)) {
      String acc = BgSeed.col(h, row, "accountCode");
      String nap = BgSeed.col(h, row, "nraCode");
      boolean excluded = "true".equals(BgSeed.col(h, row, "isExcluded"));
      String rule = BgSeed.col(h, row, "ruleSelect");

      if (!RULES.contains(rule)) {
        badRule.add(acc + " · " + rule);
      }
      if (excluded) {
        if (!nap.isEmpty()) {
          codedButExcluded.add(acc + " → " + nap);
        }
        continue;
      }
      if (nap.isEmpty()) {
        emptyButMapped.add(acc);
        continue;
      }
      if (!SAF_ACCOUNT.matcher(nap).matches()) {
        malformed.add(acc + " → " + nap);
      }
      if (!nra.contains(nap)) {
        unknown.add(acc + " → " + nap);
      }
      // ⚠️ ВОДЕЩАТА ЦИФРА Е РАЗДЕЛЪТ. Измерено: и 385-те мапнати сметки пазят раздела си, нула
      // изключения. Навиването мести НАДОЛУ в семейството (1221 → 122), не през раздел — а
      // пресечен раздел значи разход, изнесен като приход.
      if (!nap.isEmpty() && !acc.isEmpty() && nap.charAt(0) != acc.charAt(0)) {
        mismatched.add(acc + " → " + nap);
      }
    }

    assertEquals(Collections.emptyList(), badRule, "непознато правило в мапинга: " + badRule);
    assertEquals(
        Collections.emptyList(),
        codedButExcluded,
        "изключена сметка С код: "
            + codedButExcluded
            + ". Двете колони си противоречат и генераторът няма как да реши коя да чете.");
    assertEquals(
        Collections.emptyList(),
        emptyButMapped,
        "осчетоводима сметка с ПРАЗЕН код по НАП: "
            + emptyButMapped
            + ". Празно значи „структурна“ и се носи от isExcluded — празнота без флаг е пропуск.");
    assertEquals(
        Collections.emptyList(),
        malformed,
        "код, който схемата няма да приеме (SAFAccountType = [1-9][0-9]{2,4}): " + malformed);
    assertEquals(
        Collections.emptyList(),
        unknown,
        "код, който ГО НЯМА в nra_nom_accounts.csv: "
            + unknown
            + ". Схемата не изброява сметките, тоест XSD-то никога няма да хване такъв код —"
            + " отказът идва чак от НАП.");
    assertEquals(
        Collections.emptyList(),
        mismatched,
        "мапинг през раздел на сметкоплана: "
            + mismatched
            + ". Навиването мести надолу в СЕМЕЙСТВОТО, не през раздел.");
  }

  // ═══════════════════════════ ДАНЪК → TAX-IMP ═══════════════════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: всеки BG данък има ред, и всеки код е от tax_imp.csv")
  void данъчният_мапинг_покрива_каталога() {
    Set<String> catalogue = new TreeSet<>();
    List<String> th = header(TAXES);
    for (String[] row : rows(TAXES)) {
      catalogue.add(col(th, row, "code"));
    }

    Set<String> mapped = new TreeSet<>();
    List<String> h = BgSeed.header(TAX_MAP);
    for (String[] row : BgSeed.rows(TAX_MAP)) {
      mapped.add(BgSeed.col(h, row, "taxCode"));
    }
    assertEquals(
        catalogue,
        mapped,
        "данъчният мапинг се разминава с account_tax.csv. Непокрит данък значи документ, който"
            + " излиза в износа без TaxCode изобщо.");

    Set<String> taxImp = new TreeSet<>();
    Set<String> taxTypes = new TreeSet<>();
    for (CSVRecord r : nomenclature("tax_imp.csv")) {
      taxTypes.add(r.get(0).trim());
      taxImp.add(r.get(1).trim());
    }

    List<String> unknown = new ArrayList<>();
    List<String> malformed = new ArrayList<>();
    List<String> badType = new ArrayList<>();
    Set<String> empty = new TreeSet<>();
    int fixed = 0;
    int dynamic = 0;

    for (String[] row : BgSeed.rows(TAX_MAP)) {
      String code = BgSeed.col(h, row, "taxCode");
      String type = BgSeed.col(h, row, "safTaxType");
      String saf = BgSeed.col(h, row, "safTaxCode");
      boolean isDynamic = "true".equals(BgSeed.col(h, row, "isDynamic"));

      if (!taxTypes.contains(type)) {
        badType.add(code + " · TaxType " + type);
      }
      if (saf.isEmpty()) {
        empty.add(code);
        continue;
      }
      if (!TAX_CODE.matcher(saf).matches()) {
        malformed.add(code + " → " + saf);
      }
      if (!taxImp.contains(saf)) {
        unknown.add(code + " → " + saf);
      }
      if (isDynamic) {
        dynamic++;
      } else {
        fixed++;
      }
    }

    assertEquals(Collections.emptyList(), badType, "TaxType извън номенклатурата: " + badType);
    assertEquals(
        Collections.emptyList(), malformed, "TaxCode, който не е шестцифрен: " + malformed);
    assertEquals(
        Collections.emptyList(),
        unknown,
        "TaxCode, който ГО НЯМА в tax_imp.csv: "
            + unknown
            + ". SAFcodeType е само „до 9 знака“, тоест XSD-то приема всичко — отказът идва от НАП.");
    assertEquals(
        TO_CONFIRM,
        empty,
        "празните TaxCode-ове не са точно седемте „да се потвърди“. Празнотата е ИЗЯВЛЕНИЕ:"
            + " генераторът вдига ГРЕШКА за нея. Изпразнен друг код мълчи по същия начин,"
            + " а попълнен наслуки — още по-тихо.");
    // G6a: +BG_ZRS, BG_OSS_EU, BG_MOSS (попълнени) и BG_ZRP (100330 винаги — QA с. 81).
    assertEquals(20, fixed, "фиксираните данъци (със собствен код, независим от сделката)");
    assertEquals(
        23,
        dynamic,
        "динамичните данъци — кодът им е ПОДРАЗБИРАНЕ, а клетката се смята на ред от генератора");
  }

  // ═══════════════════════════ ПЛАЩАНЕ → МЕТОД / МЕХАНИЗЪМ ═══════════════════════════

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: всеки режим има ДВОЙКА метод-механизъм — или е изрично изключен")
  void плащанията_покриват_режимите() {
    Set<String> modes = new TreeSet<>();
    List<String> ph = header(PAYMENT_MODES);
    for (String[] row : rows(PAYMENT_MODES)) {
      modes.add(col(ph, row, "code"));
    }

    Set<String> mapped = new TreeSet<>();
    List<String> h = BgSeed.header(PAY_MAP);
    for (String[] row : BgSeed.rows(PAY_MAP)) {
      mapped.add(BgSeed.col(h, row, "paymentModeCode"));
    }
    assertEquals(
        modes,
        mapped,
        "мапингът на плащанията се разминава с account_paymentMode.csv. Непокрит режим значи"
            + " документ, който излиза в износа без начин на плащане изобщо.");

    // ⚠️ КЛЮЧЪТ НА НОМЕНКЛАТУРАТА Е ДВОЙКАТА, НЕ МЕТОДЪТ. nom_paymentmethod.csv носи 3 метода в 9
    // реда; „02“ сам по себе си е валиден метод, но „02/42“ не съществува. Проверка по колони
    // поотделно би пуснала точно такава комбинация.
    Set<String> pairs = new TreeSet<>();
    for (CSVRecord r : nomenclature("nom_paymentmethod.csv")) {
      pairs.add(r.get(0).trim() + "/" + r.get(2).trim());
    }
    assertEquals(9, pairs.size(), "номенклатурата на плащанията дава " + pairs.size() + " двойки");

    List<String> unknown = new ArrayList<>();
    List<String> codedButExcluded = new ArrayList<>();
    List<String> emptyButMapped = new ArrayList<>();
    Set<String> excluded = new TreeSet<>();

    for (String[] row : BgSeed.rows(PAY_MAP)) {
      String code = BgSeed.col(h, row, "paymentModeCode");
      String method = BgSeed.col(h, row, "methodCode");
      String mech = BgSeed.col(h, row, "mechanismCode");
      boolean isExcluded = "true".equals(BgSeed.col(h, row, "isExcluded"));

      if (isExcluded) {
        excluded.add(code);
        if (!method.isEmpty() || !mech.isEmpty()) {
          codedButExcluded.add(code + " → " + method + "/" + mech);
        }
        continue;
      }
      if (method.isEmpty() || mech.isEmpty()) {
        emptyButMapped.add(code);
        continue;
      }
      if (!pairs.contains(method + "/" + mech)) {
        unknown.add(code + " → " + method + "/" + mech);
      }
    }

    assertEquals(
        Collections.emptyList(),
        unknown,
        "двойка метод/механизъм извън номенклатурата на НАП: " + unknown);
    assertEquals(
        Collections.emptyList(),
        codedButExcluded,
        "изключен режим С код: "
            + codedButExcluded
            + ". Двете колони си противоречат и генераторът няма как да реши коя да чете.");
    assertEquals(
        Collections.emptyList(),
        emptyButMapped,
        "режим с ПРАЗЕН метод или механизъм, без да е изключен: "
            + emptyButMapped
            + ". Празнотата се носи от isExcluded — празнота без флаг е пропуск, не изявление.");

    // ⚠️ ДВАТА ОСТАТЪЧНИ РЕЖИМА СА ИЗБРОЕНИ ПОИМЕННО, И ТО В ДВЕТЕ ПОСОКИ. Номенклатурата на НАП
    // няма остатъчен механизъм — три метода, девет двойки, и нито една не значи „друго“. Тоест
    // точно тези два не могат да получат код. Еднопосочно твърдение („не повече от два изключени“)
    // би минало и ако утре някой изключи INC_CSH, за да „мине“ пазачът.
    assertEquals(
        new TreeSet<>(Arrays.asList("INC_OTH", "OUT_OTH")),
        excluded,
        "изключените режими не са точно двата остатъчни „Друго“. Изключването е ИЗЯВЛЕНИЕ за това,"
            + " че номенклатурата не ги описва — не удобен начин да се премълчи липсващ мапинг.");
  }

  // ═══════════════════════════ КЛЕТКА → TAX-IMP ═══════════════════════════

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: клетката дава кода СМЕТКОВО, а двете изключения са изброени")
  void клетките_се_пресмятат_от_кода() {
    Set<String> expected = new TreeSet<>();
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (CSVRecord r : nomenclature("tax_imp.csv")) {
      if (!"100".equals(r.get(0).trim())) {
        continue;
      }
      String code = r.get(1).trim();
      // ⚠️ Суфикс „00“ НЕ е клетка. 100100 „Лихви“ и 100200 „Глоби, санкции“ са категории на
      // задължението, а аритметиката би им дала несъществуващото поле 01-00.
      if (code.endsWith("00")) {
        continue;
      }
      expected.add(code);
      descriptions.put(code, r.get(3).trim());
    }

    Set<String> seeded = new TreeSet<>();
    List<String> wrong = new ArrayList<>();
    List<String> badCell = new ArrayList<>();
    List<String> strayValue = new ArrayList<>();
    List<String> wrongDesc = new ArrayList<>();

    List<String> h = BgSeed.header(CELL_MAP);
    for (String[] row : BgSeed.rows(CELL_MAP)) {
      String code = BgSeed.col(h, row, "safTaxCode");
      String cell = BgSeed.col(h, row, "cellCode");
      String value = BgSeed.col(h, row, "cellValue");
      seeded.add(code);

      if (!CELL.matcher(cell).matches()) {
        badCell.add(code + " → " + cell);
        continue;
      }
      // Описанието е дословно това на TAX-IMP — и за изключенията.
      String expectedDesc = descriptions.get(code);
      if (expectedDesc != null
          && !expectedDesc.replace(';', ',').equals(BgSeed.col(h, row, "description"))) {
        wrongDesc.add(code);
      }
      String[] exception = CELL_EXCEPTIONS.get(code);
      if (exception != null) {
        if (!exception[0].equals(cell) || !exception[1].equals(value)) {
          wrong.add(
              code
                  + " → "
                  + cell
                  + " ["
                  + value
                  + "], а изключението дава "
                  + exception[0]
                  + " ["
                  + exception[1]
                  + "]");
        }
        continue;
      }
      if (!value.isEmpty()) {
        strayValue.add(code + " → " + cell + " [" + value + "]");
      }
      // Аритметиката: код = „100“ + раздел + номер на поле; клетка = „0“ + раздел + „-“ + номер.
      String derived = "0" + code.charAt(3) + "-" + code.substring(4);
      if (!derived.equals(cell)) {
        wrong.add(code + " → " + cell + ", а аритметиката дава " + derived);
      }
    }

    assertEquals(
        Collections.emptyList(), badCell, "клетка, която не е 02-XX или 03-XX: " + badCell);
    assertEquals(
        Collections.emptyList(),
        wrong,
        "клетка, която се разминава с кода си: "
            + wrong
            + ". Съответствието е МЕХАНИЧНО и точно затова се пресмята тук, вместо да се преписва.");
    assertEquals(
        Collections.emptyList(),
        strayValue,
        "стойност в поле извън четирите кода по чл. 163а: "
            + strayValue
            + ". Само при тях последните две цифри са стойност, а не номер на поле.");
    assertEquals(
        Collections.emptyList(),
        wrongDesc,
        "описание, което се разминава с tax_imp.csv: "
            + wrongDesc
            + ". То е ПРЕПИС от номенклатурата, не собствена формулировка.");
    assertEquals(
        expected,
        seeded,
        "таблицата клетка → TAX-IMP не покрива ДДС кодовете от номенклатурата. Непокрит код значи"
            + " клетка, за която генераторът няма какво да изнесе.");
  }

  // ═══════════════════════════ ПОМОЩНИ ═══════════════════════════

  /** Разборът е кеширан: пет теста иначе четат едни и същи файлове по няколко пъти. */
  private static final Map<String, List<CSVRecord>> PARSED = new LinkedHashMap<>();

  /**
   * Номенклатурите са със ЗАПЕТАЯ и кавички по RFC 4180 — не се четат с голо цепене по {@code ;}.
   */
  private static List<CSVRecord> nomenclature(String file) {
    List<CSVRecord> cached = PARSED.get(file);
    if (cached != null) {
      return cached;
    }
    try (InputStream in = BgSafMappingSeedTest.class.getResourceAsStream(NOM + file)) {
      if (in == null) {
        fail("липсва номенклатура на classpath-а: " + NOM + file);
      }
      try (CSVParser parser = CSVFile.DEFAULT.parse(in, StandardCharsets.UTF_8)) {
        List<CSVRecord> all = new ArrayList<>();
        for (CSVRecord record : parser) {
          all.add(record);
        }
        List<CSVRecord> out =
            all.size() <= 1 ? Collections.emptyList() : all.subList(1, all.size());
        PARSED.put(file, out);
        return out;
      }
    } catch (IOException e) {
      throw new IllegalStateException("не се чете " + file, e);
    }
  }

  private static List<String> lines(Path file) {
    if (!Files.exists(file)) {
      fail("липсва " + file.toAbsolutePath());
    }
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> header(Path file) {
    return Arrays.asList(unquote(lines(file).get(0)));
  }

  private static List<String[]> rows(Path file) {
    List<String> all = lines(file);
    List<String[]> out = new ArrayList<>();
    for (int i = 1; i < all.size(); i++) {
      if (!all.get(i).isEmpty()) {
        out.add(unquote(all.get(i)));
      }
    }
    return out;
  }

  private static String col(List<String> header, String[] row, String name) {
    int i = header.indexOf(name);
    if (i < 0) {
      fail("няма колона " + name + " — заглавният ред е " + header);
    }
    return i < row.length ? row[i] : "";
  }

  /** Файловете на сметкоплана са с кавички около всяка стойност; тукашният seed — без. */
  private static String[] unquote(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ';' && !inQuotes) {
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString());
    return out.toArray(new String[0]);
  }
}
