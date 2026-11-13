package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftCheckResult;
import com.axelor.apps.napsubmissions.services.saft.SaftFileEngine;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.persistence.EntityTransaction;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ПРОБНИЯТ ФАЙЛ ЗА ТЕСТОВАТА Е-УСЛУГА НА НАП (G6c; QA IV.1.1, с. 93) — генериране от истинска база.
 *
 * <p><b>Пуска се само на ръка</b> — без {@code -Dsaft.trial.eik} класът се пропуска, тоест гейтът
 * (integrationTest) не го изпълнява. Данните ги сглобява {@code tools/saft_trial_data.py} срещу
 * приложение, вдигнато от develop върху ИЗОЛИРАНА база с App Install и BG_COA; този клас само ЧЕТЕ
 * (persistence unit {@code testPostgres}, {@code hbm2ddl = none}) и генерира през {@link
 * BgSaftGenerationService} — същият път като екрана на G6b.
 *
 * <p><b>Параметрите</b> (системни свойства):
 *
 * <ul>
 *   <li>{@code saft.trial.eik} — ЕИК на дружеството (девет цифри); реален ЕИК НЕ се пише в репото;
 *   <li>{@code saft.trial.months} — месеците, напр. {@code 2026-09,2026-10};
 *   <li>{@code saft.trial.out} — папката за файловете, ИЗВЪН репото;
 *   <li>{@code saft.trial.db.url/username/password} — изолираната база (виж {@link
 *       BgSaftTrialPostgresModule});
 *   <li>{@code saft.trial.path=g6b} — и през машината на екрана ({@code SaftFileEngine}: DAYBOOK,
 *       само месечният, S.H.1 по датата); файлът трябва да е същият байт по байт.
 * </ul>
 *
 * <p><b>Какво пише за всеки месец:</b> XML-а с името по Прил. 3 т. I.2, {@code .sha256}, резултата
 * от XSD 1.0.2 ({@code -xsd.txt}), находките ({@code -findings.txt}) и попълнения контролен списък
 * ({@code -checklist.md}) — шаблонът е контролния списък за пробно подаване.
 *
 * <pre>
 * ./gradlew :modules:axelor-bg-localization:integrationTest \
 *   --tests '*BgSaftTrialFileIntegrationTest' --no-daemon \
 *   -Dsaft.trial.eik=&lt;ЕИК&gt; -Dsaft.trial.months=2026-09,2026-10,2026-11 \
 *   -Dsaft.trial.out=&lt;папка&gt; \
 *   -Dsaft.trial.db.url=&lt;JDBC адрес на изолираната база&gt; \
 *   -Dsaft.trial.db.username=&lt;потребител&gt; -Dsaft.trial.db.password=&lt;парола&gt;
 * </pre>
 */
@EnabledIfSystemProperty(named = "saft.trial.eik", matches = "[0-9]{9}")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgSaftTrialPostgresModule.class)
@Tag("integration")
class BgSaftTrialFileIntegrationTest {

  /** Една точка от контролния списък — очакваното и намереното. */
  static final class Check {
    final String id;
    final String what;
    final boolean ok;
    final String found;

    Check(String id, String what, boolean ok, String found) {
      this.id = id;
      this.what = what;
      this.ok = ok;
      this.found = found;
    }
  }

  @Test
  @DisplayName("Пробните месечни файлове: 0 ГРЕШКИ, XSD 1.0.2, име по Прил. 3 т. I.2, SHA-256")
  void пробните_файлове() throws Exception {
    // Една транзакция за целия пробег (иначе всяко JPA.em() е без сесия и мързеливите връзки
    // гърмят), винаги ВЪРНАТА назад: класът само чете.
    EntityTransaction tx = JPA.em().getTransaction();
    tx.begin();
    try {
      generateAll();
    } finally {
      tx.rollback();
    }
  }

  private void generateAll() throws Exception {
    // Пазачът: свързани сме с базата, която е поискана — и тя не е общата.
    String database =
        (String) JPA.em().createNativeQuery("SELECT current_database()").getSingleResult();
    String url = System.getProperty(BgSaftTrialPostgresModule.DB + "url", "");
    assertTrue(
        url.endsWith("/" + database) && !"nyxa".equals(database),
        "свързан с „" + database + "“, а е поискана " + url + " — общата nyxa е забранена");
    String eik = System.getProperty("saft.trial.eik");
    Path out = Paths.get(System.getProperty("saft.trial.out"));
    Files.createDirectories(out);
    List<YearMonth> months = new ArrayList<>();
    for (String m : System.getProperty("saft.trial.months", "2026-09,2026-10").split(",")) {
      months.add(YearMonth.parse(m.trim()));
    }
    LocalDate createdOn =
        LocalDate.parse(System.getProperty("saft.trial.created", LocalDate.now().toString()));
    // G6b: и през машината на екрана (-Dsaft.trial.path=g6b).
    boolean g6b = "g6b".equals(System.getProperty("saft.trial.path"));

    Company company =
        JPA.em()
            .createQuery(
                "SELECT c FROM Company c WHERE c.partner.registrationCode = :eik", Company.class)
            .setParameter("eik", eik)
            .getResultStream()
            .findFirst()
            .orElse(null);
    assertNotNull(company, "няма дружество с ЕИК " + eik + " — пуснат ли е saft_trial_data.py?");

    List<String> summary = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (YearMonth month : months) {
      Period period =
          JPA.em()
              .createQuery(
                  "SELECT p FROM Period p WHERE p.year.company = :company AND p.year.typeSelect = 1"
                      + " AND p.fromDate = :from AND p.toDate = :to",
                  Period.class)
              .setParameter("company", company)
              .setParameter("from", month.atDay(1))
              .setParameter("to", month.atEndOfMonth())
              .getResultStream()
              .findFirst()
              .orElse(null);
      assertNotNull(period, "няма месечен период " + month + " във финансовата година");

      SaftReport report = new SaftReport();
      report.setCompany(company);
      report.setPeriod(period);
      report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);

      BgSaftGenerationService.Generated generated =
          Beans.get(BgSaftGenerationService.class).generate(report, createdOn, null);
      String base = month.toString();
      Files.write(
          out.resolve(base + "-findings.txt"),
          generated.getFindings().stream()
              .map(BgDeclarationFileFinding::toString)
              .collect(Collectors.toList()),
          StandardCharsets.UTF_8);
      List<String> errors =
          generated.getFindings().stream()
              .filter(BgDeclarationFileFinding::isBlocking)
              .map(BgDeclarationFileFinding::toString)
              .collect(Collectors.toList());
      errors.forEach(e -> problems.add(month + ": ГРЕШКА " + e));
      if (!generated.isGenerated()) {
        problems.add(month + ": файлът не е произведен");
        summary.add(month + "  НЕ Е ПРОИЗВЕДЕН  грешки: " + errors.size());
        continue;
      }

      byte[] xml = generated.getBytes();
      if (g6b) {
        // Пътят на екрана (G6b): машината на „Провери“/„Генерирай“ — DAYBOOK от базата, само
        // месечният, S.H.1 по датата. Байтовете трябва да са ТЕ САМИТЕ.
        SaftCheckResult screen = Beans.get(SaftFileEngine.class).run(report, createdOn, null);
        Files.write(
            out.resolve(base + "-g6b-findings.txt"),
            screen.getFindings().stream()
                .map(SaftCheckResult.Finding::toString)
                .collect(Collectors.toList()),
            StandardCharsets.UTF_8);
        screen.getFindings().stream()
            .filter(SaftCheckResult.Finding::isError)
            .forEach(e -> problems.add(month + ": G6b ГРЕШКА " + e));
        if (!screen.isGenerated() || !generated.getSha256().equals(screen.getSha256())) {
          problems.add(month + ": пътят на G6b не дава същия файл (" + screen.getSha256() + ")");
        }
        summary.add(
            month
                + "  път G6b (SaftFileEngine): S.H.1 "
                + screen.getAuditFileVersion()
                + ", SHA-256 "
                + (generated.getSha256().equals(screen.getSha256()) ? "съвпада" : "РАЗЛИЧЕН"));
      }
      Files.write(out.resolve(generated.getFileName()), xml);
      Files.writeString(
          out.resolve(generated.getFileName() + ".sha256"),
          generated.getSha256() + "  " + generated.getFileName() + "\n");
      // Срещу ВСЯКА версия от таблицата: файлът носи S.H.1 по датата на създаване, а пробата
      // трябва да каже и дали същите байтове минават другата схема.
      List<String> xsdReport = new ArrayList<>();
      for (BgSaftXsd.Version version : BgSaftXsd.VERSIONS) {
        List<BgDeclarationFileFinding> xsd =
            BgXsdValidator.validate(
                xml, version.getResource(), version.describe(), BgSaftXsd.CODE_PREFIX);
        xsdReport.add(
            "XSD "
                + version.describe()
                + (xsd.isEmpty() ? ": валиден (0 находки)" : ": " + xsd.size() + " находки"));
        xsd.forEach(f -> xsdReport.add("  " + f));
        xsd.forEach(f -> problems.add(month + ": XSD " + version.getSchemaVersion() + " " + f));
      }
      Files.write(out.resolve(base + "-xsd.txt"), xsdReport, StandardCharsets.UTF_8);

      String expectedName =
          eik + "_" + String.format("%02d", month.getMonthValue()) + "_" + month.getYear() + ".xml";
      List<Check> checks = checklist(xml, eik, expectedName, generated.getFileName());
      Files.write(
          out.resolve(base + "-checklist.md"),
          render(month, generated, checks),
          StandardCharsets.UTF_8);
      for (Check check : checks) {
        if (!check.ok) {
          problems.add(month + " " + check.id + " " + check.what + ": " + check.found);
        }
      }
      summary.add(
          generated.getFileName()
              + "  "
              + generated.getSha256()
              + "  "
              + xml.length
              + " байта  предупреждения: "
              + generated.getFindings().size());
    }
    summary.add(problems.isEmpty() ? "ОК — 0 ГРЕШКИ, XSD валиден, списъкът изпълнен" : "");
    summary.addAll(problems);
    Files.write(out.resolve("SUMMARY.txt"), summary, StandardCharsets.UTF_8);
    assertEquals(List.of(), problems, "пробните файлове");
  }

  // ─────────────── контролният списък над готовия XML ───────────────

  static List<Check> checklist(byte[] xml, String eik, String expectedName, String fileName)
      throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    String own = BgSaftPartyId.EIK + eik;
    List<Check> checks = new ArrayList<>();

    checks.add(
        new Check("T1", "Име на файла по Прил. 3 т. I.2", expectedName.equals(fileName), fileName));

    // T2 — ID на подаващия извън MasterFiles (решение 10), но в дневника.
    Set<String> master = new HashSet<>();
    for (String table : new String[] {"Customer", "Supplier"}) {
      for (Element e : all(doc, table)) {
        master.add(text(e, table + "ID"));
      }
    }
    boolean inGl = false;
    for (Element e : all(doc, "Transaction")) {
      inGl |= own.equals(text(e, "CustomerID")) || own.equals(text(e, "SupplierID"));
    }
    // Нулев дневник (QA IV.2.6) няма вътрешни статии — там се мери само „не е в MasterFiles“.
    boolean glEmpty = "0".equals(text(all(doc, "GeneralLedgerEntries").get(0), "NumberOfEntries"));
    checks.add(
        new Check(
            "T2",
            "ID на подаващия (" + own + ") е в дневника и НЕ е в" + " Customers/Suppliers",
            (inGl || glEmpty) && !master.contains(own),
            (glEmpty ? "дневникът е нулев" : "в дневника: " + inGl)
                + ", в MasterFiles: "
                + master.contains(own)));

    // T3 — ProductCode „0“ без запис в Products (QA с. 33).
    Set<String> products = new HashSet<>();
    for (Element e : all(doc, "Product")) {
      products.add(text(e, "ProductCode"));
    }
    boolean zeroUsed = false;
    for (Element e : all(doc, "InvoiceLine")) {
      zeroUsed |= "0".equals(text(e, "ProductCode"));
    }
    checks.add(
        new Check(
            "T3",
            "ProductCode „0“ няма запис в Products",
            !products.contains("0"),
            "ползван: " + zeroUsed + ", в Products: " + products.contains("0")));

    // T4 — кодовете в дневника са сред кодовете на протокола (вид 09); редът по сметката S.I.4 на
    // протокола носи кода му, а 000000 — само където кодът не е еднозначен. Съвпадението е наше
    // решение от 15.10.2026; НАП допуска 000/000000 в GLE (Прил. № 3 т. IV.8.2).
    Map<String, Set<String>> protocolCodes = new LinkedHashMap<>();
    Map<String, Map<String, Set<String>>> protocolHeads = new HashMap<>();
    for (Element invoice : all(doc, "Invoice")) {
      if (!"09".equals(text(invoice, "InvoiceType"))) {
        continue;
      }
      String id = text(invoice, "TransactionID");
      Set<String> codes = new TreeSet<>();
      for (Element ti : within(invoice, "TaxCode")) {
        codes.add(ti.getTextContent());
      }
      protocolCodes.computeIfAbsent(id, k -> new TreeSet<>()).addAll(codes);
      protocolHeads
          .computeIfAbsent(id, k -> new HashMap<>())
          .computeIfAbsent(child(invoice, "AccountID"), k -> new TreeSet<>())
          .addAll(codes);
    }
    boolean same = true;
    StringBuilder found = new StringBuilder();
    for (Element t : all(doc, "Transaction")) {
      String id = text(t, "TransactionID");
      if (!protocolCodes.containsKey(id)) {
        continue;
      }
      Set<String> section = protocolCodes.get(id);
      found.append(id).append(": секция ").append(section).append(", дневник");
      for (Element line : within(t, "TransactionLine")) {
        String account = child(line, "AccountID");
        Set<String> codes = new TreeSet<>();
        for (Element ti : within(line, "TaxCode")) {
          if (!"000000".equals(ti.getTextContent())) {
            codes.add(ti.getTextContent());
          }
        }
        Set<String> head = protocolHeads.get(id).getOrDefault(account, Set.of());
        same &= section.containsAll(codes) && (head.size() != 1 || codes.equals(head));
        found.append(' ').append(account).append(codes.isEmpty() ? "=000000" : "=" + codes);
      }
      found.append("; ");
    }
    checks.add(
        new Check(
            "T4",
            "Кодовете на протоколите (вид 09) в дневника — сред кодовете на протокола; по сметката"
                + " S.I.4 — неговият код, 000000 само при нееднозначен",
            same,
            protocolCodes.isEmpty() ? "няма протоколи в месеца" : found.toString()));

    // T5 — нулевите структури (QA IV.2.6, с. 97–100), където секцията е празна.
    for (String section :
        new String[] {"SalesInvoices", "PurchaseInvoices", "Payments", "GeneralLedgerEntries"}) {
      Element s = all(doc, section).get(0);
      if (!"0".equals(text(s, "NumberOfEntries"))) {
        continue;
      }
      boolean ok;
      if (section.equals("Payments")) {
        ok =
            "0".equals(text(s, "PaymentRefNo"))
                && "0".equals(text(s, "PaymentMethod"))
                && "998".equals(text(s, "AccountID"));
      } else if (section.equals("GeneralLedgerEntries")) {
        ok = "998".equals(text(s, "AccountID")) && "1900-01-01".equals(text(s, "GLPostingDate"));
      } else {
        ok =
            "01".equals(text(s, "InvoiceType"))
                && "998".equals(text(s, "AccountID"))
                && "0".equals(text(s, "ProductCode"));
      }
      checks.add(
          new Check(
              "T5", "Нулева структура " + section + " по образеца на QA", ok, "NumberOfEntries 0"));
    }
    // T6 — Customer/Supplier „0“ (решение 3; да се потвърди с портала).
    for (String table : new String[] {"Customer", "Supplier"}) {
      List<Element> rows = all(doc, table);
      if (rows.size() == 1 && "0".equals(text(rows.get(0), table + "ID"))) {
        checks.add(
            new Check(
                "T6",
                table + " „0“ с AccountID 998",
                "998".equals(text(rows.get(0), "AccountID")),
                "единствен " + table + " „0“"));
      }
    }
    return checks;
  }

  private static List<String> render(
      YearMonth month, BgSaftGenerationService.Generated generated, List<Check> checks) {
    List<String> lines = new ArrayList<>();
    lines.add("# Пробен SAF-T — " + month + " — " + generated.getFileName());
    lines.add("");
    lines.add("SHA-256: `" + generated.getSha256() + "`");
    lines.add("");
    lines.add("| # | Очаквано | Резултат | Намерено |");
    lines.add("|---|---|---|---|");
    for (Check c : checks) {
      lines.add(
          "| "
              + c.id
              + " | "
              + c.what
              + " | "
              + (c.ok ? "PASS" : "FAIL")
              + " | "
              + c.found.replace("|", "/")
              + " |");
    }
    lines.add("");
    lines.add(
        "Предупреждения (не спират файла): "
            + generated.getFindings().size()
            + " — виж "
            + month
            + "-findings.txt");
    return lines;
  }

  private static List<Element> all(Document doc, String local) {
    return list(doc.getElementsByTagNameNS("*", local));
  }

  private static List<Element> within(Element e, String local) {
    return list(e.getElementsByTagNameNS("*", local));
  }

  private static List<Element> list(NodeList nodes) {
    List<Element> out = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
        out.add((Element) nodes.item(i));
      }
    }
    return out;
  }

  /** Текстът на първия наследник с това име; {@code null}, ако няма. */
  private static String text(Element e, String local) {
    List<Element> found = within(e, local);
    return found.isEmpty() ? null : found.get(0).getTextContent();
  }

  /** Само пряк наследник — S.I.4 и GL.24 не са AccountID от вложените структури. */
  private static String child(Element e, String local) {
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(n.getLocalName())) {
        return n.getTextContent();
      }
    }
    return null;
  }
}
