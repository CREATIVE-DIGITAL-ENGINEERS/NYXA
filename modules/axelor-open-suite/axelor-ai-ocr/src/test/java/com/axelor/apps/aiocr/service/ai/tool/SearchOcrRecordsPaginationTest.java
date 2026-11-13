package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.record.OcrRecordQuery;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Can «всички» actually mean all of them?
 *
 * <p>Rule 11 tells the model that a named set means the complete eligible set and that it must read
 * {@code totalMatches} rather than stop at what it was shown. With {@code limit} capped at 25 and
 * no {@code offset}, records 26 and beyond were simply unreachable: the instruction was honest and
 * the mechanism behind it was not. These tests build a set larger than one page and prove the tool
 * can now deliver every record in it.
 */
class SearchOcrRecordsPaginationTest extends AiOcrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Bigger than one full page, so the last page is deliberately a partial one. */
  private static final int TOTAL = 30;

  private SearchOcrRecordsTool tool() {
    return Beans.get(SearchOcrRecordsTool.class);
  }

  private void asSystem(String companyCode, Consumer<Company> body) {
    inTransaction(
        () -> {
          Company scope = company(companyCode);
          AiExecutionContext.runAsSystem(scope.getId(), "test", () -> body.accept(scope));
        });
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c = JPA.save(c);
    }
    return c;
  }

  private void uploads(Company company, int count) {
    for (int i = 0; i < count; i++) {
      AiOcrUpload u = new AiOcrUpload();
      u.setFileName("page-" + i + ".pdf");
      u.setCompany(company);
      u.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
      JPA.save(u);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> search(int limit, Integer offset) {
    ObjectNode args = MAPPER.createObjectNode();
    args.put("status", AiOcrUploadStatus.NEEDS_REVIEW);
    args.put("limit", limit);
    if (offset != null) {
      args.put("offset", offset);
    }
    return (Map<String, Object>) tool().execute(args);
  }

  @SuppressWarnings("unchecked")
  private static Set<Object> idsOf(Map<String, Object> result) {
    Set<Object> ids = new LinkedHashSet<>();
    for (Map<String, Object> record :
        (List<Map<String, Object>>) result.getOrDefault("records", List.of())) {
      ids.add(record.get("uploadId"));
    }
    return ids;
  }

  /** The whole point: thirty records, twenty-five to a page, none of them unreachable. */
  @Test
  void everyRecordOfALargeSetIsReachableByPaging() {
    asSystem(
        "PAGE1",
        c -> {
          uploads(c, TOTAL);

          Map<String, Object> first = search(OcrRecordQuery.MAX_LIMIT, null);
          assertEquals((long) TOTAL, first.get("totalMatches"));
          assertEquals(OcrRecordQuery.MAX_LIMIT, first.get("returned"));
          assertEquals(Boolean.TRUE, first.get("truncated"));
          assertEquals(
              (long) OcrRecordQuery.MAX_LIMIT,
              first.get("nextOffset"),
              "the answer has to say where the next page starts");

          Set<Object> all = new LinkedHashSet<>(idsOf(first));
          Map<String, Object> second =
              search(OcrRecordQuery.MAX_LIMIT, (int) (long) (Long) first.get("nextOffset"));
          assertEquals(TOTAL - OcrRecordQuery.MAX_LIMIT, second.get("returned"));
          assertEquals(Boolean.FALSE, second.get("truncated"));
          assertEquals(Boolean.TRUE, second.get("complete"), "the last page says so");
          assertNull(second.get("nextOffset"), "and offers no page after it");

          all.addAll(idsOf(second));
          assertEquals(TOTAL, all.size(), "paging must not skip or repeat a record");
        });
  }

  /**
   * The trap this fix had to avoid. A thirty-record set paged twenty-five at a time leaves exactly
   * one record on the last page, and {@code isUnique()} is page-local — so the old shape would have
   * answered "Exactly one record matched… do not search again" while five records still waited.
   */
  @Test
  void aSingleRecordOnALaterPageIsNotReportedAsTheOnlyMatch() {
    asSystem(
        "PAGE2",
        c -> {
          uploads(c, 26);

          Map<String, Object> last = search(OcrRecordQuery.MAX_LIMIT, OcrRecordQuery.MAX_LIMIT);

          assertEquals(1, last.get("returned"));
          assertEquals("page", last.get("outcome"), "a page, never 'single'");
          assertNull(last.get("record"), "the single-match shape must not be used here");
          assertNotNull(last.get("records"));
          String nextStep = String.valueOf(last.get("nextStep"));
          assertFalse(
              nextStep.contains("Do not search again"),
              "that sentence would end the job while records remained");
          assertTrue(nextStep.contains("last page"));
        });
  }

  /** One genuine match at offset 0 still gets the direct answer it always did. */
  @Test
  void aGenuinelyUniqueMatchIsStillReportedAsSingle() {
    asSystem(
        "PAGE3",
        c -> {
          uploads(c, 1);

          Map<String, Object> only = search(OcrRecordQuery.MAX_LIMIT, null);

          assertEquals("single", only.get("outcome"));
          assertNotNull(only.get("record"));
          assertEquals(0, only.get("offset"));
          assertNull(only.get("nextOffset"));
        });
  }

  /**
   * Paging past the end is not "your filters match nothing" — told that, the model would go and
   * change filters that were correct.
   */
  @Test
  void pagingPastTheEndSaysTheSetIsCompleteNotThatNothingMatched() {
    asSystem(
        "PAGE4",
        c -> {
          uploads(c, 3);

          Map<String, Object> beyond = search(OcrRecordQuery.MAX_LIMIT, OcrRecordQuery.MAX_LIMIT);

          assertEquals("page", beyond.get("outcome"));
          assertEquals(0, beyond.get("returned"));
          assertEquals(3L, beyond.get("totalMatches"));
          assertTrue(String.valueOf(beyond.get("hint")).contains("complete"));
          assertNull(beyond.get("suggestions"), "near-miss suggestions are for a real not_found");
        });
  }

  /** A hostile or mistaken offset costs one bounded query, not a table scan. */
  @Test
  void anAbsurdOffsetIsClampedRatherThanObeyed() {
    asSystem(
        "PAGE5",
        c -> {
          uploads(c, 2);

          Map<String, Object> out = search(5, Integer.MAX_VALUE);

          assertEquals(SearchOcrRecordsTool.MAX_OFFSET, out.get("offset"));
          assertEquals(0, out.get("returned"));
        });
  }

  /**
   * A negative offset is the first page, not an error. An offset is a row count to skip, so zero
   * and negatives are legitimate (clamped to the first page); only an id must be positive. Reading
   * it through the id validator wrongly rejected it with "must be a positive id".
   */
  @Test
  void aNegativeOffsetIsTreatedAsTheFirstPage() {
    asSystem(
        "PAGE6",
        c -> {
          uploads(c, 2);

          Map<String, Object> out = search(5, -10);

          assertEquals(0, out.get("offset"));
          assertEquals(2, out.get("returned"));
        });
  }
}
