package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.meta.db.MetaFile;
import org.junit.jupiter.api.Test;

/**
 * Правилото на Hibernate пазача: кое поле на файла (или на връзката му с подаването) е сменено
 * спрямо старото състояние. Без Hibernate — само сравнението по имена и стойности, което {@code
 * onPreUpdate} подава от {@code PreUpdateEvent}.
 */
class NapArtifactImmutabilityListenerTest {

  private static final String[] NAMES = {
    "name", "metaFile", "sha256", "byteLength", "artifactTypeSelect", "saftReport", "company"
  };

  private static MetaFile file(long id) {
    MetaFile f = new MetaFile();
    f.setId(id);
    return f;
  }

  private static <T extends com.axelor.db.Model> T ref(T model, long id) {
    model.setId(id);
    return model;
  }

  private static Object[] state(
      String name, MetaFile file, String sha, String type, SaftReport report, Company company) {
    return new Object[] {name, file, sha, 10L, type, report, company};
  }

  private static String changed(Object[] before, Object[] after) {
    return NapArtifactImmutabilityListener.firstChanged(
        NAMES, before, after, NapArtifactImmutabilityListener.ARTIFACT_FIXED);
  }

  private static Object[] base() {
    return state(
        "a.xml", file(1), "abc", "payload", ref(new SaftReport(), 5), ref(new Company(), 7));
  }

  @Test
  void sha256ChangeIsFound() {
    Object[] after = base();
    after[2] = "deadbeef";
    assertEquals("sha256", changed(base(), after));
  }

  @Test
  void anotherStoredFileIsFound() {
    Object[] after = base();
    after[1] = file(2);
    assertEquals("metaFile", changed(base(), after));
  }

  @Test
  void typeReportAndCompanyChangesAreFound() {
    Object[] type = base();
    type[4] = "protocol";
    assertEquals("artifactTypeSelect", changed(base(), type));
    Object[] report = base();
    report[5] = ref(new SaftReport(), 6);
    assertEquals("saftReport", changed(base(), report));
    Object[] blank = base();
    blank[6] = null;
    assertEquals("company", changed(blank, base()), "null → стойност през flush също е смяна");
  }

  @Test
  void sameReferenceInAnotherInstanceIsNotAChange() {
    assertNull(changed(base(), base()));
  }

  @Test
  void theDisplayNameMayChange() {
    Object[] after = base();
    after[0] = "друго име.xml";
    assertNull(changed(base(), after));
  }

  @Test
  void theSubmissionLinkIsFixedToo() {
    String[] names = {"submission", "artifact", "roleSelect"};
    Object[] before = {ref(new NapSubmission(), 1), ref(new NapArtifact(), 2), "payload"};
    Object[] after = {ref(new NapSubmission(), 1), ref(new NapArtifact(), 3), "payload"};
    assertEquals(
        "artifact",
        NapArtifactImmutabilityListener.firstChanged(
            names, before, after, NapArtifactImmutabilityListener.LINK_FIXED));
  }
}
