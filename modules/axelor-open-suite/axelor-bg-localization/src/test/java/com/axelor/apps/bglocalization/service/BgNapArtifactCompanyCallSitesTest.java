package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВСЕКИ ПЪТ, КОЙТО СЪЗДАВА ФАЙЛ ЗА НАП/НОИ, КАЗВА ЧИЙ Е — свидетел върху ИЗВИКВАНИЯТА.
 *
 * <p>Каталогът на nyxa-security чете {@code NapArtifact} по {@code self.company}. Файл без
 * дружество вижда само admin — точно дефектът, заради който полето съществува (Д1/Д6/Прил. 9 със
 * {@code saftReport = null}). {@code NapArtifactCompanyTest} в nyxa-nap-submissions доказва, че
 * ВСЕКИ вход на {@code NapArtifactService} записва подаденото; този тест доказва, че всеки извикващ
 * ПОДАВА дружество.
 *
 * <p><b>Какво пази.</b> Всяко извикване в main кода на двата модула се разделя на аргументи и:
 *
 * <ul>
 *   <li>{@code createForCompany} — третият аргумент не е {@code null};
 *   <li>{@code createForSubmission} — дружеството е на подаването (задължително поле);
 *   <li>{@code createFromFile} — с три аргумента; {@code null} дружество само за схемата на SAF-T;
 *   <li>{@code createFromMetaFile} и {@code createForReport} (генерираният файл, G6b) — само SAF-T,
 *       с отчет (дружеството е неговото).
 * </ul>
 *
 * <p>И списъкът на местата е ЗАКОВАН: нов вход или изчезнал вход (напр. парсерът спре да ги намира)
 * е червено, а не мълчаливо зелено.
 */
class BgNapArtifactCompanyCallSitesTest {

  private static final Path SUITE = Paths.get("..");

  private static final List<Path> ROOTS =
      List.of(
          SUITE.resolve("axelor-bg-localization/src/main/java"),
          SUITE.resolve("nyxa-nap-submissions/src/main/java"));

  private static final Pattern CALL =
      Pattern.compile(
          "\\.(createFromMetaFile|createFromFile|createForCompany|createForSubmission|createForReport)\\(");

  /** Файл → методите, които вика, в реда на появата — всички пътища на създаване днес. */
  private static final Map<String, List<String>> EXPECTED = new TreeMap<>();

  static {
    EXPECTED.put(
        "SaftReportWorkflowService.java", List.of("createForReport", "createFromMetaFile"));
    EXPECTED.put("ManualSubmissionService.java", List.of("createForSubmission"));
    EXPECTED.put("NapProcessingResultService.java", List.of("createForSubmission"));
    EXPECTED.put("BgD1SubmissionService.java", List.of("createForCompany"));
    EXPECTED.put("BgEtzSubmissionService.java", List.of("createForCompany"));
    EXPECTED.put("BgDeclarationSubmissionService.java", List.of("createForCompany"));
    EXPECTED.put("BgApp9ExportService.java", List.of("createFromFile", "createFromFile"));
    EXPECTED.put("BgArt73Para1ReportService.java", List.of("createFromFile"));
    EXPECTED.put("BgFatcaReportService.java", List.of("createFromFile"));
    EXPECTED.put("BgArt73Para6ReportService.java", List.of("createFromFile"));
    EXPECTED.put("BgSaftSchemaRegistrar.java", List.of("createFromFile"));
  }

  /** Единственото нарочно празно дружество: схемата е една за всички дружества. */
  private static final String SCHEMA_REGISTRAR = "BgSaftSchemaRegistrar.java";

  @Test
  @DisplayName("Всеки извикващ подава дружество; празно — само схемата на SAF-T")
  void everyCallSitePassesACompany() throws IOException {
    for (Path root : ROOTS) {
      Assumptions.assumeTrue(Files.isDirectory(root), root + " не е намерен от тази директория");
    }
    Map<String, List<String>> found = new TreeMap<>();
    List<String> defects = new ArrayList<>();

    for (Path file : javaFiles()) {
      String name = file.getFileName().toString();
      if (name.equals("NapArtifactService.java")) {
        continue; // самият сервиз: входовете делегират един към друг
      }
      String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      Matcher m = CALL.matcher(src);
      while (m.find()) {
        if (!isArtifactServiceCall(src, m.start())) {
          continue;
        }
        String method = m.group(1);
        found.computeIfAbsent(name, k -> new ArrayList<>()).add(method);
        List<String> args = arguments(src, m.end());
        String where = name + " " + method + args;
        switch (method) {
          case "createForCompany":
            if (args.size() != 3 || args.get(2).equals("null")) {
              defects.add(where + " — без дружество");
            }
            break;
          case "createForSubmission":
            if (args.size() != 3) {
              defects.add(where);
            }
            break;
          case "createFromFile":
            if (args.size() != 3) {
              defects.add(where + " — вход без дружество");
            } else if (args.get(2).equals("null") && !name.equals(SCHEMA_REGISTRAR)) {
              defects.add(where + " — null дружество извън схемата");
            }
            break;
          case "createForReport": // G6b: генерираният SAF-T, дружеството е на отчета
            if (args.size() != 3 || args.get(2).equals("null")) {
              defects.add(where + " — без отчет, тоест без дружество");
            }
            break;
          default: // createFromMetaFile — само SAF-T, дружеството е на отчета
            if (args.size() != 3 || args.get(2).equals("null")) {
              defects.add(where + " — без отчет, тоест без дружество");
            }
        }
      }
    }

    assertEquals(new ArrayList<String>(), defects, "извиквания без дружество");
    assertEquals(EXPECTED, found, "местата, които създават NapArtifact");
  }

  /** {@code artifactService.} / {@code artifacts.} — не чужд метод със същото име. */
  private static boolean isArtifactServiceCall(String src, int dot) {
    int i = dot;
    while (i > 0 && Character.isWhitespace(src.charAt(i - 1))) {
      i--;
    }
    String before = src.substring(Math.max(0, i - 20), i);
    return before.endsWith("artifactService") || before.endsWith("artifacts");
  }

  /** Аргументите на горно ниво (запетая в скоби или в низ не дели). */
  private static List<String> arguments(String src, int open) {
    List<String> args = new ArrayList<>();
    int depth = 0;
    boolean inString = false;
    StringBuilder cur = new StringBuilder();
    for (int i = open; i < src.length(); i++) {
      char c = src.charAt(i);
      if (inString) {
        cur.append(c);
        if (c == '\\') {
          cur.append(src.charAt(++i));
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
        cur.append(c);
      } else if (c == '(') {
        depth++;
        cur.append(c);
      } else if (c == ')') {
        if (depth == 0) {
          args.add(cur.toString().trim().replaceAll("\\s+", " "));
          return args;
        }
        depth--;
        cur.append(c);
      } else if (c == ',' && depth == 0) {
        args.add(cur.toString().trim().replaceAll("\\s+", " "));
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    throw new IllegalStateException("незатворено извикване при " + open);
  }

  private static List<Path> javaFiles() throws IOException {
    List<Path> all = new ArrayList<>();
    for (Path root : ROOTS) {
      try (Stream<Path> s = Files.walk(root)) {
        all.addAll(
            s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList()));
      }
    }
    assertTrue(all.size() > 100, "намерени са само " + all.size() + " .java файла");
    return all;
  }
}
