package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.service.vat.BgVatReturnCorrectionService;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОРИГИРАЩАТА НЕ ЗАСЯДА: „Създай корекция“ прави запис в {@code corrected}, а формата трябва да
 * има за него „Генерирай“ и „Отбележи подадена“. Маркерът на коригиращата е {@code
 * correctsPrevious}; {@code corrected} е само началният ѝ статус.
 */
class BgVatReturnCorrectionTest {

  private static Company company(long id) {
    Company c = new Company();
    c.setId(id);
    return c;
  }

  private static BgVatReturn vatReturn(Company company, String period, String status) {
    BgVatReturn r = new BgVatReturn();
    r.setCompany(company);
    r.setPeriod(period);
    r.setStatusSelect(status);
    return r;
  }

  private static BgVatReturn correctionOf(BgVatReturn original) {
    BgVatReturn r = vatReturn(original.getCompany(), original.getPeriod(), "generated");
    r.setCorrectsPrevious(original);
    return r;
  }

  // ═══ т. 2 — формата ═════════════════════════════════════════════════════

  private static String view() throws IOException {
    return new String(
        Files.readAllBytes(
            BgDomainColumns.modulesRoot()
                .resolve(
                    "axelor-open-suite/axelor-bg-localization/src/main/resources/views/BgVatReturn.xml")),
        StandardCharsets.UTF_8);
  }

  private static String showIf(String view, String button) {
    Matcher m =
        Pattern.compile("<button name=\"" + button + "\"[^>]*?showIf=\"([^\"]*)\"", Pattern.DOTALL)
            .matcher(view);
    assertTrue(m.find(), "бутонът " + button + " няма showIf");
    return m.group(1);
  }

  @Test
  @DisplayName(
      "т. 2: „Генерирай“ и протоколите — и при corrected; „Отбележи подадена“ — по статуса")
  void формата() throws IOException {
    String view = view();
    assertTrue(showIf(view, "generateBtn").contains("statusSelect == 'corrected'"));
    assertTrue(showIf(view, "bgVatInvoiceProtocolsBtn").contains("statusSelect == 'corrected'"));
    assertEquals("statusSelect == 'generated'", showIf(view, "markSubmittedBtn"));
    assertTrue(
        view.contains("<field name=\"correctsPrevious\"/>\n    <field name=\"napEntryNumber\"/>"),
        "колоната „Коригира декларация“ в списъка");
  }

  // ═══ т. 3 — пазачът на generate() ═══════════════════════════════════════

  /** Пазачът не чете нито датата, нито календара. */
  private static final BgVatReturnCorrectionService CORRECTIONS =
      new BgVatReturnCorrectionService(null, null);

  @Test
  @DisplayName("т. 3: генерира се от draft, corrected, generated и нов запис")
  void генерира_се() {
    for (String status : new String[] {null, "draft", "corrected", "generated"}) {
      assertNull(CORRECTIONS.generateRefusal(status), String.valueOf(status));
    }
  }

  @Test
  @DisplayName("т. 3: подадена, приета, отхвърлена — отказ с указание „Създай корекция“")
  void не_се_генерира() {
    for (String status : new String[] {"submitted", "accepted", "rejected"}) {
      String refusal = CORRECTIONS.generateRefusal(status);
      assertNotNull(refusal, status);
      assertTrue(refusal.contains("„Създай корекция“"), refusal);
    }
  }

  // ═══ т. 4 — предусловието на markSubmitted ══════════════════════════════

  @Test
  @DisplayName("т. 4: коригираща към подаден/приет/отхвърлен оригинал за същия период — минава")
  void предусловието_минава() {
    for (String status : new String[] {"submitted", "accepted", "rejected"}) {
      BgVatReturn original = vatReturn(company(1), "202606", status);
      BgVatReturn correction = correctionOf(original);
      assertNull(BgVatReturnController.correctionRefusal(correction, original), status);
    }
    // Редовната декларация не е засегната.
    BgVatReturn regular = vatReturn(company(1), "202606", "generated");
    assertNull(BgVatReturnController.correctionRefusal(regular, null));
  }

  @Test
  @DisplayName("т. 4: чужд период — отказ")
  void чужд_период() {
    BgVatReturn original = vatReturn(company(1), "202606", "submitted");
    BgVatReturn correction = correctionOf(original);
    correction.setPeriod("202607");
    String refusal = BgVatReturnController.correctionRefusal(correction, original);
    assertNotNull(refusal);
    assertTrue(refusal.contains("202607") && refusal.contains("202606"), refusal);
  }

  @Test
  @DisplayName("т. 4: чуждо дружество — отказ")
  void чуждо_дружество() {
    BgVatReturn original = vatReturn(company(1), "202606", "submitted");
    BgVatReturn correction = correctionOf(original);
    correction.setCompany(company(2));
    assertEquals(
        "Коригиращата и декларацията, която поправя, са на различни дружества.",
        BgVatReturnController.correctionRefusal(correction, original));
    // Същото дружество, заредено като друг обект (контекстът на формата) — минава.
    correction.setCompany(company(1));
    assertNull(BgVatReturnController.correctionRefusal(correction, original));
  }

  @Test
  @DisplayName("т. 4: сочи коригираща — отказ; липсващ запис — отказ")
  void сочи_коригираща() {
    BgVatReturn original = vatReturn(company(1), "202606", "submitted");
    BgVatReturn first = correctionOf(original);
    first.setStatusSelect("submitted");
    BgVatReturn second = correctionOf(first);
    assertTrue(BgVatReturnController.correctionRefusal(second, first).contains("друга коригираща"));
    assertTrue(BgVatReturnController.correctionRefusal(second, null).contains("не е намерена"));
  }

  @Test
  @DisplayName("т. 4: оригиналът не е подаден (draft/generated/corrected) — отказ")
  void оригиналът_не_е_подаден() {
    for (String status : new String[] {"draft", "generated", "corrected"}) {
      BgVatReturn original = vatReturn(company(1), "202606", status);
      assertEquals(
          "Декларацията, която корекцията поправя, не е подадена — подайте нея, не корекция.",
          BgVatReturnController.correctionRefusal(correctionOf(original), original),
          status);
    }
  }

  @Test
  @DisplayName("т. 4: корекция от коригираща сочи оригинала — веригата не се удължава")
  void веригата_сочи_оригинала() {
    BgVatReturn original = vatReturn(company(1), "202606", "submitted");
    assertSame(original, BgVatReturnController.correctionRoot(original));
    BgVatReturn first = correctionOf(original);
    first.setStatusSelect("rejected");
    assertSame(original, BgVatReturnController.correctionRoot(first));
  }

  @Test
  @DisplayName("т. 4: submitRefusal вика предусловието — записаният оригинал, не копието")
  void submitRefusal_вика_предусловието() {
    BgVatReturn original = vatReturn(company(1), "202606", "submitted");
    BgVatReturn stored = vatReturn(company(1), "202605", "submitted");
    BgVatReturn correction = correctionOf(original);
    BgVatReturnController controller =
        new BgVatReturnController() {
          @Override
          protected List<String> validateForSubmit(BgVatReturn r) {
            return Collections.emptyList();
          }

          @Override
          protected BgVatPackage latestPackage(BgVatReturn vatReturn) {
            return null;
          }

          @Override
          protected boolean isLatestPackageStale(BgVatReturn vatReturn) {
            return false;
          }

          @Override
          protected BgVatReturn correctionTarget(BgVatReturn c) {
            return stored;
          }
        };
    String refusal = controller.submitRefusal(correction);
    assertNotNull(refusal);
    assertTrue(refusal.contains("202605"), refusal);
  }
}
