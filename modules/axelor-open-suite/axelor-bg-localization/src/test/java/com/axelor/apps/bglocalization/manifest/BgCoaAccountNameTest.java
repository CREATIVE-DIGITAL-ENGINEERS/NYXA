package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Имената на сметките в BG_COA: поправените дословно по НАП и пазач срещу ново дублиране на имена.
 *
 * <p><b>Дефектът, който го роди.</b> 704 и 706 се казваха еднакво — „Приходи от продажба на
 * материали“. Мапингът към SAF-T беше верен (704 → 704 „Приходи от наеми“), тоест износът и UI-ят
 * назоваваха една и съща сметка различно, а счетоводителят виждаше две сметки за едно и също.
 *
 * <p><b>Защо „НАП дословно“ е изключение от пазача.</b> Номенклатурата сама дава едно име на два
 * кода (131/195, 661/681 и др. — застраховането). Група, в която ВСЯКА сметка носи точно своето НАП
 * име, е вярна по определение и се приема без списък.
 *
 * <p><b>Храповата.</b> Заварените двойници са изброени поименно в {@link #KNOWN}. Нов двойник пада;
 * поправен двойник също пада, докато не бъде изваден от списъка — така списъкът само намалява.
 *
 * <p>Сметкопланът живее в ЧУЖД модул ({@code axelor-account}) и не минава през App Install, тоест
 * seed пазачите на този модул не го виждат — същата дупка, която запушва {@code
 * BgCoa442ReceivableTest}.
 */
class BgCoaAccountNameTest {

  private static final Path ACCOUNTS =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA"
                  + "/account_account.csv");

  /**
   * Заварените двойници: ключ = кодовете, сортирани и слепени с „|“. Нито един не е дословно НАП;
   * всеки иска решение по сметка. 728|729 и 791|799 излязоха с кръг 2 на имената.
   */
  private static final Map<String, String> KNOWN = new LinkedHashMap<>();

  static {
    // Репортни възли и НАП листото под тях.
    KNOWN.put("609|60909|H000", "„Други разходи“ — възлите 609 и H000 и НАП листото 60909");
    // Стара сметка извън НАП редом с НАП листото.
    KNOWN.put("6044|60901", "„Разходи за командировки“ — 6044 извън НАП, 60901 по НАП");
    KNOWN.put("121|1222", "„Непокрита загуба от минали години“ — 1222 извън НАП, 121 по НАП");
  }

  /**
   * Поправените имена: 704/706 (#280), 728/791 (#281), 603, 691 (Активи — 691), 4538/4539 (ДДС).
   */
  private static final List<String> FIXED_BY_NRA =
      Arrays.asList("704", "706", "728", "791", "603", "691", "4538", "4539");

  @Test
  @DisplayName("поправените сметки носят дословно имената си от NRA_Nom_Accounts")
  void поправените_сметки_са_по_НАП() {
    Map<String, String> seed = namesByCode();
    for (String code : FIXED_BY_NRA) {
      String inSeed = seed.get(code);
      if (inSeed == null) {
        fail("сметка " + code + " липсва в BG_COA");
      }
      assertEquals(
          nraName(code),
          inSeed,
          code
              + ": името в BG_COA трябва да е дословно това на НАП. SAF-T износът ползва НАП"
              + " наименованието по мапинга (IDENTITY); различно име в сметкоплана значи, че"
              + " счетоводителят осчетоводява по едно, а НАП получава друго");
    }
  }

  @Test
  @DisplayName("две сметки с едно име → пада (освен дословно НАП и изброените заварени)")
  void няма_нови_двойници_по_име() {
    Map<String, String> seed = namesByCode();
    Map<String, TreeSet<String>> byName = new TreeMap<>();
    for (Map.Entry<String, String> e : seed.entrySet()) {
      byName.computeIfAbsent(normalized(e.getValue()), k -> new TreeSet<>()).add(e.getKey());
    }

    List<String> fresh = new ArrayList<>();
    List<String> stillThere = new ArrayList<>();
    for (Map.Entry<String, TreeSet<String>> e : byName.entrySet()) {
      TreeSet<String> codes = e.getValue();
      if (codes.size() < 2 || isVerbatimNra(codes, seed)) {
        continue;
      }
      String key = String.join("|", codes);
      if (KNOWN.containsKey(key)) {
        stillThere.add(key);
      } else {
        fresh.add(key + " „" + seed.get(codes.first()) + "“");
      }
    }

    assertTrue(
        fresh.isEmpty(),
        "нов двойник по име в BG_COA: "
            + fresh
            + ". Две сметки с едно име са неразличими в падащите списъци и в справките; ако"
            + " НАП ги назовава различно, вземи имената от nra_nom_accounts.csv");

    List<String> fixed = new ArrayList<>(KNOWN.keySet());
    fixed.removeAll(stillThere);
    assertTrue(
        fixed.isEmpty(),
        "двойникът е поправен — извади го от KNOWN, за да не се върне незабелязано: " + fixed);
  }

  /** Всяка сметка в групата е в НАП и носи точно своето НАП име. */
  private static boolean isVerbatimNra(TreeSet<String> codes, Map<String, String> seed) {
    for (String code : codes) {
      if (!BgNraNomenclatures.accounts().containsKey(code)
          || !nraName(code).equals(seed.get(code))) {
        return false;
      }
    }
    return true;
  }

  /** НАП пише наименованието с кода отпред: „704 Приходи от наеми“. */
  private static String nraName(String code) {
    String full = BgNraNomenclatures.accounts().get(code);
    if (full == null) {
      fail("код " + code + " липсва в nra_nom_accounts.csv");
    }
    String prefix = code + " ";
    if (!full.startsWith(prefix)) {
      fail("НАП наименованието на " + code + " не започва с кода си: " + full);
    }
    return full.substring(prefix.length());
  }

  /** Двойник, различен само по главни букви или интервали, пак е двойник. */
  private static String normalized(String name) {
    return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /** Разбор като вносителя: {@code CSVFile} с „;“, RFC 4180 кавички. */
  private static Map<String, String> namesByCode() {
    if (!Files.exists(ACCOUNTS)) {
      fail("липсва " + ACCOUNTS.toAbsolutePath());
    }
    Map<String, String> out = new LinkedHashMap<>();
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(ACCOUNTS.toFile(), StandardCharsets.UTF_8)) {
      for (CSVRecord r : parser) {
        if (out.put(r.get("code"), r.get("name")) != null) {
          fail("код " + r.get("code") + " се среща два пъти в BG_COA");
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("не се чете " + ACCOUNTS, e);
    }
    return out;
  }
}
