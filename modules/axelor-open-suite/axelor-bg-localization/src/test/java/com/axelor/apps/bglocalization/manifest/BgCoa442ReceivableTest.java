package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сметка 442 „Вземания по липси и начети“ — в чарта, в ДВАТА баланса, и никъде в ОПР.
 *
 * <p><b>Счетоводното твърдение, което тези пазачи държат.</b> Възстановимата липса не е разход, а
 * ВЗЕМАНЕ: когато има отговорно лице, стойността не напуска предприятието, а сменя формата си от
 * актив в претенция към това лице. По приложимите СС вземането е БАЛАНСОВА позиция (краткотрайни
 * активи, раздел „Вземания“); разход на 691 би намалил финансовия резултат с нещо, което ще се
 * събере, и би извадил от баланса стойност, която още стои там.
 *
 * <p>⚠️ <b>Номерът 442 е ИЗБОР НА СЪСТАВИТЕЛЯ, не нормативен.</b> Той идва от стария национален
 * сметкоплан, а задължителен национален сметкоплан няма от 2002 г. — сметкопланът е на
 * предприятието. Тестът пази СЪОТВЕТСТВИЕТО между избора и справките, не самия номер.
 *
 * <p><b>Защо файлов пазач и защо тук.</b> Сметкопланът не идва през App Install: той е зип от
 * CSV-та към {@code AccountingConfigTemplate}, внасян през {@code l10n/chart-config.xml}, и живее в
 * ЧУЖД модул ({@code axelor-account}). Тоест нито един от seed пазачите на този модул не го гледа —
 * същата дупка, която {@code BgFixedAssetCategorySeedTest} вече запушва за категориите.
 */
class BgCoa442ReceivableTest {

  private static final String CODE = "442";

  /** Братската сметка, от която е клониран редът: другото невземане-от-клиент вземане. */
  private static final String BROTHER = "498";

  /** Репортната група „Търговски вземания“ под „Краткотрайни активи“. */
  private static final String PARENT = "B020";

  /** {@code account_accountType.csv} importId 5 = {@code receivable}. */
  private static final String TYPE_RECEIVABLE = "5";

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path ACCOUNTS = COA.resolve("account_account.csv");

  private static final Path REPORT_LINES = COA.resolve("account_accountingReportConfigLine.csv");

  /** Двата БАЛАНСА и видимият им подсбор „Вземания“. */
  private static final Map<String, String> BALANCE_SUBTOTALS = new LinkedHashMap<>();

  /** Двата ОПР — там 442 няма работа. */
  private static final List<String> PROFIT_AND_LOSS = Arrays.asList("27", "45");

  static {
    // 28 = „Счетоводен баланс (НСС)“, ред „   II. Вземания“
    BALANCE_SUBTOTALS.put("28", "A_ST2_2");
    // 46 = „Отчет за финансовото състояние / Баланс (МСС)“, ред „   Търговски и други вземания“
    BALANCE_SUBTOTALS.put("46", "MA_ST2_2");
  }

  @Test
  @DisplayName("442 е в сметкоплана, тип „вземане“, под групата на вземанията")
  void сметката_съществува_и_е_вземане() {
    Map<String, String[]> byCode = rowsByCode(ACCOUNTS);
    String[] row = byCode.get(CODE);
    if (row == null) {
      fail(
          "сметка "
              + CODE
              + " липсва в BG_COA. Възстановимата липса се отписва на нея, а без реда мапингът"
              + " сочи несъществуваща сметка и поведението мълчаливо остава 691 — разход вместо"
              + " вземане");
    }
    List<String> h = header(ACCOUNTS);
    assertEquals(
        TYPE_RECEIVABLE,
        col(h, row, "accountType.importId"),
        CODE
            + ": типът трябва да е „receivable“ (importId 5 в account_accountType.csv). Разходен"
            + " тип би върнал сметката в ОПР, а възстановимата липса е ВЗЕМАНЕ — претенция към"
            + " отговорното лице, която ще се събере");
    assertEquals(
        PARENT,
        col(h, row, "parent_code"),
        CODE
            + ": родителят е репортната група „Търговски вземания“, не число. В BG_COA parent_code"
            + " е РЕПОРТНА група, а не числова йерархия — група „44“ изобщо не съществува и"
            + " създаването ѝ би извадило сметката от раздел „Вземания“ на баланса");
  }

  @Test
  @DisplayName("редът на 442 е клониран от 498 и се различава само по код и име")
  void редът_е_огледало_на_братската_сметка() {
    Map<String, String[]> byCode = rowsByCode(ACCOUNTS);
    String[] row = byCode.get(CODE);
    String[] brother = byCode.get(BROTHER);
    if (row == null || brother == null) {
      fail("липсва " + CODE + " или братската " + BROTHER);
    }
    List<String> h = header(ACCOUNTS);
    List<String> differs = new ArrayList<>();
    for (int i = 0; i < h.size(); i++) {
      if (!row[i].equals(brother[i])) {
        differs.add(h.get(i));
      }
    }
    assertEquals(
        Arrays.asList("code", "name"),
        differs,
        CODE
            + " трябва да е точно братската "
            + BROTHER
            + " („Други дебитори“) с друг код и име. Двайсетте останали колони — тип, родител,"
            + " commonPosition, reconcileOk, useForPartnerBalance — решават как сметката се държи"
            + " в статии, равнения и справки. Разминаване тук значи, че 442 се държи различно от"
            + " всяко друго вземане в чарта, и то без обявена причина. Разлики: "
            + differs);
  }

  @Test
  @DisplayName("двата баланса събират 442 в реда „Вземания“")
  void балансите_показват_442_при_вземанията() {
    List<String> h = header(REPORT_LINES);
    List<String[]> rows = rows(REPORT_LINES);

    for (Map.Entry<String, String> entry : BALANCE_SUBTOTALS.entrySet()) {
      String reportType = entry.getKey();
      String subtotal = entry.getValue();

      String detailCode = null;
      for (String[] row : rows) {
        if (reportType.equals(col(h, row, "reportType_importId"))
            && CODE.equals(col(h, row, "accountCode"))) {
          detailCode = col(h, row, "code");
        }
      }
      assertTrue(
          detailCode != null,
          "справка "
              + reportType
              + ": няма детайлен ред с accountCode "
              + CODE
              + ". Импортът превръща accountCode в accountSet"
              + " (ImportAccountingReportConfigLine#setAccounts) — без този ред сметката просто не"
              + " се сумира никъде");

      String rule = null;
      for (String[] row : rows) {
        if (reportType.equals(col(h, row, "reportType_importId"))
            && subtotal.equals(col(h, row, "code"))) {
          rule = col(h, row, "rule");
        }
      }
      assertTrue(rule != null, "справка " + reportType + ": липсва подсборът " + subtotal);
      assertTrue(
          Arrays.asList(rule.split("\\s*\\+\\s*")).contains(detailCode),
          "справка "
              + reportType
              + ": подсборът "
              + subtotal
              + " не събира "
              + detailCode
              + ". Детайлният ред е hidden=true — той съществува САМО за да влезе в това правило,"
              + " тъй че забравен там означава 442 да не се яви в баланса изобщо. Правилото е: "
              + rule);
    }
  }

  @Test
  @DisplayName("ОПР не показва 442 — вземането не е разход")
  void опр_не_съдържа_442() {
    List<String> h = header(REPORT_LINES);
    for (String[] row : rows(REPORT_LINES)) {
      String reportType = col(h, row, "reportType_importId");
      if (!PROFIT_AND_LOSS.contains(reportType)) {
        continue;
      }
      assertFalse(
          CODE.equals(col(h, row, "accountCode")),
          "справка "
              + reportType
              + " (ОПР) носи ред за "
              + CODE
              + " на "
              + col(h, row, "code")
              + ". Възстановимата липса е ВЗЕМАНЕ, не разход: стойността не напуска предприятието."
              + " Показана в ОПР, тя би намалила финансовия резултат с нещо, което ще се събере —"
              + " а на 691 остава само НЕвъзстановимата липса");
      assertFalse(
          col(h, row, "rule").contains("_" + CODE),
          "справка "
              + reportType
              + " (ОПР): правилото на "
              + col(h, row, "code")
              + " сочи "
              + CODE);
    }
  }

  @Test
  @DisplayName("детайлните редове за 442 носят свободни importId и не крадат заварени")
  void новите_редове_не_крадат_номера() {
    List<String> h = header(REPORT_LINES);
    List<String[]> rows = rows(REPORT_LINES);
    Map<String, String> seen = new LinkedHashMap<>();
    for (String[] row : rows) {
      String id = col(h, row, "importId");
      String previous = seen.put(id, col(h, row, "code"));
      assertTrue(
          previous == null,
          "importId "
              + id
              + " се среща два пъти ("
              + previous
              + " и "
              + col(h, row, "code")
              + "). Вносът е upsert по (_companyCode + '-' + importId) и НЕ трие — зает номер"
              + " ПРЕИМЕНУВА заварен ред, вместо да добави нов");
    }
  }

  @Test
  @DisplayName("секвенциите остават уникални в рамките на всяка справка")
  void секвенциите_са_уникални_по_справка() {
    // Вмъкването е с НОВА секвенция в края, нарочно: детайлните редове са hidden=true, а правилата
    // се решават с итерация до неподвижна точка (AccountingReportValueServiceImpl), тъй че редът
    // не гейтва изчислението. Уникалността обаче трябва да се пази — дублирана секвенция прави
    // подредбата зависима от реда на извличане.
    List<String> h = header(REPORT_LINES);
    Map<String, Map<String, String>> byReport = new LinkedHashMap<>();
    for (String[] row : rows(REPORT_LINES)) {
      String reportType = col(h, row, "reportType_importId");
      String sequence = col(h, row, "sequence");
      if (reportType.isEmpty() || sequence.isEmpty()) {
        continue;
      }
      Map<String, String> seqs = byReport.computeIfAbsent(reportType, k -> new LinkedHashMap<>());
      String previous = seqs.put(sequence, col(h, row, "code"));
      assertTrue(
          previous == null,
          "справка "
              + reportType
              + ": секвенция "
              + sequence
              + " е на два реда ("
              + previous
              + " и "
              + col(h, row, "code")
              + ")");
    }
  }

  // ─── четене на CSV-тата с кавички ───

  private static List<String> header(Path file) {
    return Arrays.asList(unquote(lines(file).get(0)));
  }

  private static List<String[]> rows(Path file) {
    List<String> lines = lines(file);
    List<String[]> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (!lines.get(i).isEmpty()) {
        out.add(unquote(lines.get(i)));
      }
    }
    return out;
  }

  private static Map<String, String[]> rowsByCode(Path file) {
    Map<String, String[]> byCode = new LinkedHashMap<>();
    for (String[] row : rows(file)) {
      byCode.put(row[0], row);
    }
    return byCode;
  }

  private static String col(List<String> header, String[] row, String name) {
    int i = header.indexOf(name);
    if (i < 0) {
      fail("няма колона " + name + " — заглавният ред е " + header);
    }
    return i < row.length ? row[i] : "";
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

  /**
   * Цепи ред с QUOTE_ALL колони. Гол {@code split(";")} не върши работа: описанията носят запетаи,
   * тирета и вложени кавички-лапички, а тук — и правила със знаци плюс.
   */
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
