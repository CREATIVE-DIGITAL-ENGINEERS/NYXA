package com.axelor.apps.aiocr.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.aiocr.service.invoice.OcrInvoiceServiceImpl;
import com.axelor.apps.businessproject.service.InvoiceServiceProjectImpl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ КАПАНЪТ: ВТОРО ВРЪЗВАНЕ НА СЪЩИЯ КЛЮЧ Е ОТКАЗ ПРИ СТАРТ, НЕ ПРИ КОМПИЛАЦИЯ.
 *
 * <p>Guice допуска ВЕРИГА — {@code bind(A).to(B)} в един модул и {@code bind(B).to(C)} в друг, — но
 * отказва ДВЕ различни връзвания на един и същ ключ, независимо в кои модули са. Резултатът е
 * {@code CreationException} на старта на приложението: компилацията минава, тестовете минават,
 * контейнерът не тръгва.
 *
 * <p>Точно това се случи тук веднъж. Веригата за {@code InvoiceService} изглеждаше като {@code
 * InvoiceService → InvoiceServiceImpl (account) → InvoiceServiceSupplychainImpl (supplychain)} и
 * опашката ѝ изглеждаше свободна — но {@code BusinessProjectModule} вече връзваше {@code
 * InvoiceServiceSupplychainImpl → InvoiceServiceProjectImpl}. Второ връзване на същия ключ оттук
 * щеше и да счупи старта, и да прескочи проектния слой.
 *
 * <p>Затова тестът чете ИЗХОДНИЯ КОД на всички Guice модули и вали билда, когато някой ключ, чието
 * име съдържа „Invoice", е вързан към два различни класа. Еднакво повторено връзване (supplychain
 * връзва {@code InvoiceManagementRepository → InvoiceSupplychainRepository} два пъти) е допустимо —
 * Guice приравнява идентични връзвания — затова се броят РАЗЛИЧНИТЕ цели.
 */
class OcrInvoiceBindingChainTest {

  /** {@code bind(A.class).to(B.class)} — с нов ред между двете, както е форматирано на места. */
  private static final Pattern BINDING =
      Pattern.compile("bind\\(\\s*(\\w+)\\.class\\s*\\)\\s*\\.to\\(\\s*(\\w+)\\.class\\s*\\)");

  @Test
  void noInvoiceKeyIsBoundTwiceToDifferentClasses() throws IOException {
    Map<String, Set<String>> targetsByKey = new LinkedHashMap<>();
    Map<String, List<String>> whereByKey = new LinkedHashMap<>();

    for (Path module : guiceModuleSources()) {
      String source = new String(Files.readAllBytes(module), StandardCharsets.UTF_8);
      Matcher matcher = BINDING.matcher(source);
      while (matcher.find()) {
        String key = matcher.group(1);
        if (!key.contains("Invoice")) {
          continue; // фокусиран тест: само веригите около фактурата
        }
        targetsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(matcher.group(2));
        whereByKey
            .computeIfAbsent(key, k -> new ArrayList<>())
            .add(module.getFileName() + " → " + matcher.group(2));
      }
    }

    Assumptions.assumeTrue(
        !targetsByKey.isEmpty(), "Guice module sources not reachable from the test working dir");

    List<String> clashes =
        targetsByKey.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + " bound by " + String.join(", ", whereByKey.get(e.getKey())))
            .collect(Collectors.toList());
    if (!clashes.isEmpty()) {
      fail("Duplicate Guice binding — the app will not boot:\n  " + String.join("\n  ", clashes));
    }

    // И положителната страна: опашката на веригата наистина е там, където този модул я закача.
    assertEquals(
        new LinkedHashSet<>(java.util.Collections.singletonList("OcrInvoiceServiceImpl")),
        targetsByKey.get("InvoiceServiceProjectImpl"),
        "ai-ocr must extend the tail of the InvoiceService chain");
    assertEquals(
        new LinkedHashSet<>(java.util.Collections.singletonList("BgInvoiceRepository")),
        targetsByKey.get("OcrInvoiceRepository"),
        "bg-localization must extend the tail of the Invoice repository chain");
  }

  @Test
  void theOcrServiceReallySitsUnderTheProjectLayer() {
    assertTrue(
        InvoiceServiceProjectImpl.class.isAssignableFrom(OcrInvoiceServiceImpl.class),
        "binding InvoiceServiceProjectImpl to a class that does not extend it would drop every"
            + " layer below it");
  }

  /** Every {@code *Module.java} under the open-suite modules, or an empty list when not found. */
  private static List<Path> guiceModuleSources() throws IOException {
    Path suite = Paths.get("").toAbsolutePath();
    while (suite != null && !Files.isDirectory(suite.resolve("modules/axelor-open-suite"))) {
      suite = suite.getParent();
    }
    if (suite == null) {
      return new ArrayList<>();
    }
    try (Stream<Path> paths = Files.walk(suite.resolve("modules/axelor-open-suite"))) {
      return paths
          .filter(p -> p.getFileName().toString().endsWith("Module.java"))
          .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
          .collect(Collectors.toList());
    }
  }
}
