package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Move;
import com.lowagie.text.FontFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.eclipse.birt.report.engine.api.ReportEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТНИЯТ ПРОТОКОЛ — <b>истинският PDF</b>, рендиран от {@code BgVatProtocol.rptdesign} с BIRT и
 * прочетен обратно с PDFBox.
 *
 * <p><b>⚠️ ЗАЩО ТУК РЕНДИРАНЕТО Е ВЪЗМОЖНО, А ПРИ ФИША — НЕ.</b> Образецът няма JDBC набор данни:
 * всичко идва като параметри от {@link BgVatProtocolPrint#params()} (редовете на т. 3 — през
 * скриптов набор). Тоест не трябва нито база, нито приложение — само engine-ът, сглобен като в
 * {@code com.axelor.report.ReportEngineProvider} (шрифтовете DejaVu — иначе кирилицата изчезва).
 *
 * <p>Доказва: всеки реквизит на ЗДДС чл. 117 ал. 2 (т. 1–10) има етикет И стойност върху листа, а
 * основата и данъкът са числата на реда в PRODAGBI, закръглени по документ. Не доказва, че листът е
 * красив — това се вижда с очи.
 */
class BgVatProtocolPdfTest {

  private static final Path DESIGN =
      Paths.get("src/main/resources/reports/BgVatProtocol.rptdesign");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static IReportEngine engine;

  @BeforeAll
  static void engine() {
    EngineConfig config = new EngineConfig();
    config.setFontConfig(
        BgVatProtocolPdfTest.class.getResource("/com/axelor/report/fonts/fontsConfig.xml"));
    FontFactory.register("/com/axelor/report/fonts/dejavu/DejaVuSans.ttf");
    FontFactory.register("/com/axelor/report/fonts/dejavu/DejaVuSans-Bold.ttf");
    engine = new ReportEngine(config);
  }

  @AfterAll
  static void shutdown() {
    if (engine != null) {
      engine.destroy();
    }
  }

  /** PDF-ът на протокола като текст; самият файл остава в build/protocol-pdf/ за преглед с очи. */
  static String render(BgVatProtocolPrint print) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream design = Files.newInputStream(DESIGN)) {
      IReportRunnable report = engine.openReportDesign(design);
      IRunAndRenderTask task = engine.createRunAndRenderTask(report);
      RenderOption options = new RenderOption();
      options.setOutputFormat(IRenderOption.OUTPUT_FORMAT_PDF);
      options.setOutputStream(out);
      task.setRenderOption(options);
      task.setParameterValues(print.params());
      task.run();
      assertEquals(0, task.getErrors().size(), "BIRT: " + task.getErrors());
      task.close();
    }
    Path dir = Paths.get("build", "protocol-pdf");
    Files.createDirectories(dir);
    Files.write(dir.resolve("protocol-" + print.getNumber() + ".pdf"), out.toByteArray());
    try (PDDocument pdf = Loader.loadPDF(out.toByteArray())) {
      // Интервалите се свиват — разделителят на хилядите и пренасянето на реда не са съдържание.
      return new PDFTextStripper().getText(pdf).replace(' ', ' ').replaceAll("\\s+", " ");
    }
  }

  private static void has(String text, String expected) {
    assertTrue(text.contains(expected), "липсва „" + expected + "“ в:\n" + text);
  }

  private static String day(LocalDate date) {
    return date.format(DAY);
  }

  @Test
  @DisplayName("ВОП: всички реквизити на чл. 117 ал. 2 и сумите на реда в дневниците")
  void вопът() throws Exception {
    Move protocol = BgVatProtocolPrintBuilderTest.generatedVop();
    BgVatMoveDiaryRows.Row row = BgVatProtocolPrintBuilderTest.diaryRow(protocol);
    String text = render(BgVatProtocolPrintBuilderTest.BUILDER.build(protocol));

    has(text, "ПРОТОКОЛ");
    has(text, "по чл. 117, ал. 1, т. 1 ЗДДС");
    // т. 1
    has(text, "№ " + row.getDocument().getDocumentNumber());
    has(text, day(row.getDocument().getDocumentDate()));
    // т. 2
    has(text, "Дружество ЕООД");
    has(text, "BG100000033");
    // т. 3
    has(text, "Стока");
    // т. 4
    has(text, day(BgVatProtocolPrintBuilderTest.JUNE_10));
    // т. 5 и т. 8 — числата на PRODAGBI (к.09/к.10), както ги форматира листът
    has(text, BgVatProtocolPrint.money(row.getSalesTotalBase()));
    has(text, BgVatProtocolPrint.money(row.getSalesTotalVat()));
    has(text, "1 000,00");
    has(text, "200,00");
    // т. 6, т. 7
    has(text, "20 %");
    has(text, "чл. 84 ЗДДС");
    // т. 9
    has(text, "DE123456789");
    has(text, "INV-1");
    has(text, day(BgVatProtocolPrintBuilderTest.JUNE_12));
    for (int point = 1; point <= 9; point++) {
      has(text, "т. " + point + " ");
    }
  }

  @Test
  @DisplayName("Закръгляне по документ: върху листа е 246,92 — колкото к.10 в PRODAGBI")
  void закръглянето() throws Exception {
    Move protocol =
        BgVatProtocolPrintBuilderTest.manual(41, "SERVICE_EU", "20", "1234.575", "246.915");
    protocol.setBgVatProtocolBase(new java.math.BigDecimal("1234.575"));
    String text = render(BgVatProtocolPrintBuilderTest.BUILDER.build(protocol));

    has(text, "1 234,58");
    has(text, "246,92");
    assertTrue(!text.contains("246,915") && !text.contains("246.915"), text);
  }

  @Test
  @DisplayName("Внос по чл. 167а: митническият документ и т. 2 на чл. 117 ал. 1")
  void вносът() throws Exception {
    Move protocol = BgVatProtocolPrintBuilderTest.manual(42, "IMPORT_167A", "20", "60000", "12000");
    protocol.setPartner(BgVatInvoiceProtocolFixtures.supplier(40, null, "CN"));
    protocol.setOrigin("26BG001000000123R4");
    String text = render(BgVatProtocolPrintBuilderTest.BUILDER.build(protocol));

    has(text, "по чл. 117, ал. 1, т. 2 ЗДДС");
    has(text, "26BG001000000123R4");
    has(text, "60 000,00");
    has(text, "12 000,00");
  }

  @Test
  @DisplayName("163а ч. II: т. 10 върху листа")
  void зърното() throws Exception {
    String text =
        render(
            BgVatProtocolPrintBuilderTest.BUILDER.build(
                BgVatProtocolPrintBuilderTest.generatedGrain()));
    has(text, "т. 10 ");
    has(text, "INV-3");
  }

  @Test
  @DisplayName("Ръчният протокол: предупрежденията излизат на листа като „(!)“")
  void предупрежденията() throws Exception {
    BgVatProtocolPrint print =
        BgVatProtocolPrintBuilderTest.BUILDER.build(
            BgVatProtocolPrintBuilderTest.manual(43, "SERVICE_EU", "20", "100", "20"));
    String text = render(print);
    assertTrue(!print.getWarnings().isEmpty());
    for (String warning : print.getWarnings()) {
      has(text, "(!) " + warning.replaceAll("\\s+", " "));
    }
  }

  @Test
  @DisplayName("Параметрите на образеца = параметрите, които печатът подава — нито един в повече")
  void параметрите() throws Exception {
    String design = new String(Files.readAllBytes(DESIGN), StandardCharsets.UTF_8);
    Set<String> declared = new TreeSet<>();
    Matcher m = Pattern.compile("<scalar-parameter name=\"([A-Za-z]+)\"").matcher(design);
    while (m.find()) {
      declared.add(m.group(1));
    }
    Map<String, Object> params =
        BgVatProtocolPrintBuilderTest.BUILDER
            .build(BgVatProtocolPrintBuilderTest.generatedVop())
            .params();
    assertEquals(new TreeSet<>(params.keySet()), declared);
    for (String name : declared) {
      assertTrue(
          design.contains("<property name=\"isRequired\">false</property>")
              && design.contains("params[\"" + name + "\"]"),
          "параметърът " + name + " не се показва никъде или е задължителен");
    }
  }
}
