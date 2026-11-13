package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link BgVatSourceFingerprint} — the basis of stale-package detection: identical
 * inputs must fingerprint identically; any change in the source documents or the manual DEKLAR
 * cells must change the fingerprint (so the prepared package is flagged stale and regeneration is
 * required).
 */
class BgVatSourceFingerprintTest {

  private BgVatExportRequestDto request() {
    BgVatExportRequestDto r = new BgVatExportRequestDto();
    r.setCompanyVatNumber("BG123456789");
    r.setPeriodFrom(LocalDate.of(2026, 6, 1));
    r.setPeriodTo(LocalDate.of(2026, 6, 30));
    r.setReportType("all");
    r.setEncoding("cp1251");
    r.setCoefficientK33(new BigDecimal("0.85"));
    r.setCell70(BigDecimal.ZERO);
    r.setDeclarantEgn("6803030040");
    r.setDeclarantNames("Иван");
    r.setDeclarantCapacity("A");
    return r;
  }

  private BgVatDocumentDto doc(String number, String base, String vat) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setDocumentNumber(number);
    d.setDocumentDate(LocalDate.of(2026, 6, 15));
    d.setCounterpartyVatNumber("BG999888777");
    d.setTotalBase(new BigDecimal(base));
    d.setTotalVat(new BigDecimal(vat));
    d.setTotalGross(new BigDecimal(base).add(new BigDecimal(vat)));
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode("BG_SRS_20");
    a.setBase(new BigDecimal(base));
    a.setVatAmount(new BigDecimal(vat));
    d.getAmounts().add(a);
    return d;
  }

  @Test
  void sameInputs_sameFingerprint() {
    List<BgVatDocumentDto> docs = new ArrayList<>();
    docs.add(doc("INV-1", "1000", "200"));
    docs.add(doc("INV-2", "500", "100"));

    String a = BgVatSourceFingerprint.compute(docs, request());
    String b = BgVatSourceFingerprint.compute(docs, request());
    assertEquals(a, b, "deterministic for identical inputs");
  }

  @Test
  void changedDocumentAmount_changesFingerprint() {
    List<BgVatDocumentDto> before = Collections.singletonList(doc("INV-1", "1000", "200"));
    List<BgVatDocumentDto> after = Collections.singletonList(doc("INV-1", "1500", "300"));

    assertNotEquals(
        BgVatSourceFingerprint.compute(before, request()),
        BgVatSourceFingerprint.compute(after, request()),
        "a changed amount must be detected as stale");
  }

  @Test
  void addedDocument_changesFingerprint() {
    List<BgVatDocumentDto> one = new ArrayList<>();
    one.add(doc("INV-1", "1000", "200"));
    List<BgVatDocumentDto> two = new ArrayList<>(one);
    two.add(doc("INV-2", "500", "100"));

    assertNotEquals(
        BgVatSourceFingerprint.compute(one, request()),
        BgVatSourceFingerprint.compute(two, request()),
        "an added document must be detected as stale");
  }

  @Test
  void changedManualCell_changesFingerprint() {
    List<BgVatDocumentDto> docs = Collections.singletonList(doc("INV-1", "1000", "200"));

    BgVatExportRequestDto changed = request();
    changed.setCell70(new BigDecimal("50.00"));

    assertNotEquals(
        BgVatSourceFingerprint.compute(docs, request()),
        BgVatSourceFingerprint.compute(docs, changed),
        "a changed manual DEKLAR cell must be detected as stale");
  }

  @Test
  void emptyPeriod_isStableAndDistinctFromNonEmpty() {
    String empty = BgVatSourceFingerprint.compute(Collections.emptyList(), request());
    String emptyAgain = BgVatSourceFingerprint.compute(new ArrayList<>(), request());
    assertEquals(empty, emptyAgain, "empty period fingerprints identically (zero declaration)");

    String nonEmpty =
        BgVatSourceFingerprint.compute(
            Collections.singletonList(doc("INV-1", "1000", "200")), request());
    assertNotEquals(empty, nonEmpty);
  }
}
