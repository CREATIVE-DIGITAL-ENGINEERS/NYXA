package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The committed catalogue is exactly what {@code tools/gen-catalogue.js} generates from the current
 * source tree.
 *
 * <p>The generated rows are derived from the domains and menus of every module. A change elsewhere
 * can shift them without anyone touching nyxa-security: a new company field on a model, for
 * example, is picked up by the generator's one-hop company rule, and the committed catalogue
 * silently stops matching the generator. This test runs the generator against a copy of the
 * committed files and fails on any difference, so the drift shows up in the PR that causes it.
 *
 * <p>Needs Node.js: {@code NODE} if set (optional), else {@code node} on the PATH (on Windows the
 * process lookup finds {@code node.exe}), else the one Gradle downloads for the front-end builds —
 * {@code .gradle/nodejs/<version>/bin/node} on Linux and macOS, {@code
 * .gradle/nodejs/<version>/node.exe} on Windows. The CI agent has Node, so the test runs there.
 * Without Node the test is skipped with the reason printed, not failed (decision of 03.11.2026,
 * pending whether to make it mandatory).
 */
class RoleCatalogueGeneratorSyncTest {

  /** The files gen-catalogue.js writes. */
  private static final List<String> GENERATED =
      List.of("nx_role.csv", "nx_permission.csv", "nx_menu.csv");

  private static final Path MODULE = Paths.get("").toAbsolutePath();
  private static final Path REPO = MODULE.getParent().getParent().getParent();
  private static final Path CATALOGUE = MODULE.resolve("src/main/resources/nyxa-security");
  private static final Path TOOLS = MODULE.resolve("tools");

  @Test
  void committedCatalogueMatchesTheGenerator() throws Exception {
    assertTrue(
        Files.isRegularFile(REPO.resolve("settings.gradle")),
        "expected the repository root at " + REPO);
    String node = node();
    Path work = Files.createTempDirectory("nx-catalogue");
    Path out = Files.createDirectory(work.resolve("out"));
    // The generator keeps the hand-written baseline rows it finds in the output directory.
    for (String file : GENERATED) {
      Files.copy(CATALOGUE.resolve(file), out.resolve(file));
    }
    run(node, TOOLS.resolve("index.js").toString(), REPO.toString(), work.toString());
    run(node, TOOLS.resolve("gen-catalogue.js").toString(), work.toString(), out.toString());

    List<String> differences = new ArrayList<>();
    for (String file : GENERATED) {
      List<String> committed = lines(CATALOGUE.resolve(file));
      List<String> generated = lines(out.resolve(file));
      if (!committed.equals(generated)) {
        differences.add(file + ":\n" + diff(committed, generated));
      }
    }
    assertEquals(
        "",
        String.join("\n", differences),
        "the committed catalogue differs from tools/gen-catalogue.js. Regenerate it"
            + " and review every changed grant, or fix the generator if the change is wrong.");
  }

  private static List<String> lines(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(l -> l.replace("\r", ""))
        .collect(Collectors.toList());
  }

  /** The rows that differ, so the message stays readable. */
  private static String diff(List<String> committed, List<String> generated) {
    Set<String> inCommitted = new HashSet<>(committed);
    Set<String> inGenerated = new HashSet<>(generated);
    List<String> out = new ArrayList<>();
    committed.stream().filter(l -> !inGenerated.contains(l)).forEach(l -> out.add("  - " + l));
    generated.stream().filter(l -> !inCommitted.contains(l)).forEach(l -> out.add("  + " + l));
    if (out.isEmpty()) {
      return "  (same rows, different order)";
    }
    int shown = Math.min(out.size(), 20);
    return String.join("\n", out.subList(0, shown))
        + (out.size() > shown ? "\n  … " + (out.size() - shown) + " more lines" : "");
  }

  private static void run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      fail(String.join(" ", command) + " did not finish in 5 minutes");
    }
    assertEquals(0, process.exitValue(), String.join(" ", command) + " failed:\n" + output);
  }

  private static String node() throws IOException {
    String fromEnv = System.getenv("NODE");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    if (works("node")) {
      return "node";
    }
    for (Path candidate : downloadedNodes(REPO)) {
      if (works(candidate.toString())) {
        return candidate.toString();
      }
    }
    String reason =
        "RoleCatalogueGeneratorSyncTest skipped: Node.js not found (NODE, node on the PATH, or"
            + " modules/*/*/.gradle/nodejs/*/bin/node | node.exe). The committed role catalogue was"
            + " NOT checked against tools/gen-catalogue.js.";
    System.out.println(reason);
    Assumptions.abort(reason);
    return null;
  }

  /**
   * The node binaries the node-gradle plugin downloaded for axelor-front / axelor-human-resource,
   * newest version first: {@code modules/<group>/<module>/.gradle/nodejs/<version>/bin/node}, or
   * {@code .../<version>/node.exe} in the Windows distribution.
   */
  static List<Path> downloadedNodes(Path repo) throws IOException {
    List<Path> candidates = new ArrayList<>();
    try (DirectoryStream<Path> groups = Files.newDirectoryStream(repo.resolve("modules"))) {
      for (Path group : groups) {
        if (!Files.isDirectory(group)) {
          continue;
        }
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(group)) {
          for (Path module : modules) {
            Path nodejs = module.resolve(".gradle/nodejs");
            if (!Files.isDirectory(nodejs)) {
              continue;
            }
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(nodejs)) {
              for (Path version : versions) {
                for (String binary : List.of("bin/node", "node.exe")) {
                  if (Files.isRegularFile(version.resolve(binary))) {
                    candidates.add(version.resolve(binary));
                  }
                }
              }
            }
          }
        }
      }
    }
    candidates.sort(Comparator.comparing(RoleCatalogueGeneratorSyncTest::versionOf).reversed());
    return candidates;
  }

  /** The version directory's name, e.g. {@code node-v20.15.1-win-x64}. */
  private static String versionOf(Path binary) {
    Path version =
        binary.endsWith("node.exe") ? binary.getParent() : binary.getParent().getParent();
    return version.getFileName().toString();
  }

  private static boolean works(String node) {
    try {
      Process process = new ProcessBuilder(node, "--version").redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }
}
