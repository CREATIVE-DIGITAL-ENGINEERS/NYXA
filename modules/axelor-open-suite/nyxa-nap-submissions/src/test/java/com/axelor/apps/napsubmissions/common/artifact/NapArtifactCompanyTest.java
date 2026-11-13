package com.axelor.apps.napsubmissions.common.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВСЕКИ ВХОД НА {@link NapArtifactService} ЗАПИСВА ЧИЙ Е ФАЙЛЪТ ({@code company}).
 *
 * <p>Каталогът на nyxa-security чете NapArtifact по {@code self.company}; празно дружество значи
 * файл само за admin. Затова всеки вход се пробва поотделно: SAF-T (от отчета), Д1/Д6/ЕТЗ/портал
 * ({@code createForCompany}), отговор и протокол ({@code createForSubmission}), генерираният SAF-T
 * ({@code createForReport}), генерираните файлове и подписът ({@code createFromFile} с дружество) —
 * и единственото нарочно празно: схемата.
 *
 * <p>Без база и без файлова система: записът и четенето на байтовете са подменени през шевовете на
 * сервиза; отпечатъкът се смята от истинския код.
 */
class NapArtifactCompanyTest {

  private static final byte[] BYTES = "EMPL2021".getBytes(StandardCharsets.UTF_8);

  /** Записът връща реда; байтовете са винаги {@link #BYTES}; качването прави само MetaFile. */
  private static final NapArtifactService SERVICE =
      new NapArtifactService(
          new NapArtifactRepository() {
            @Override
            public NapArtifact save(NapArtifact entity) {
              return entity;
            }
          },
          null,
          new MetaFiles(null) {
            @Override
            public MetaFile upload(InputStream stream, String fileName) {
              return metaFile(fileName);
            }
          }) {
        @Override
        protected byte[] readStoredBytes(MetaFile metaFile) {
          return BYTES;
        }
      };

  private static MetaFile metaFile(String name) {
    MetaFile metaFile = new MetaFile();
    metaFile.setFileName(name);
    return metaFile;
  }

  private static Company company(String name) {
    Company company = new Company();
    company.setName(name);
    return company;
  }

  private static RegulatoryFile file(Institution institution) {
    return new RegulatoryFile(institution, "Pril9.xml", "text/xml", BYTES);
  }

  @Test
  @DisplayName("SAF-T: дружеството на отчета — файлът остава видим както досега")
  void saftFileTakesTheReportsCompany() throws Exception {
    Company a = company("А");
    SaftReport report = new SaftReport();
    report.setCompany(a);

    NapArtifact artifact =
        SERVICE.createFromMetaFile(
            metaFile("saft.xml"), NapArtifactRepository.TYPE_PAYLOAD, report);

    assertSame(a, artifact.getCompany());
    assertSame(report, artifact.getSaftReport());
    assertEquals(NapArtifactRepository.INSTITUTION_NAP, artifact.getInstitutionSelect());
  }

  @Test
  @DisplayName("Генериран SAF-T (G6b): байтовете се качват тук; дружеството и отчетът са на отчета")
  void generatedSaftFileTakesTheReportsCompany() throws Exception {
    Company a = company("А");
    SaftReport report = new SaftReport();
    report.setCompany(a);

    NapArtifact artifact =
        SERVICE.createForReport(file(Institution.NAP), NapArtifactRepository.TYPE_PAYLOAD, report);

    assertSame(a, artifact.getCompany());
    assertSame(report, artifact.getSaftReport());
    assertEquals(NapArtifactRepository.INSTITUTION_NAP, artifact.getInstitutionSelect());
    assertEquals(file(Institution.NAP).getSha256(), artifact.getSha256());
  }

  @Test
  @DisplayName("Д1/Д6, ЕТЗ, портал: дружеството на документа, без отчет")
  void declarationFileTakesTheGivenCompany() throws Exception {
    Company a = company("А");

    NapArtifact artifact =
        SERVICE.createForCompany(metaFile("EMPL2021.TXT"), NapArtifactRepository.TYPE_PAYLOAD, a);

    assertSame(a, artifact.getCompany());
    assertNull(artifact.getSaftReport());
    assertEquals(NapArtifactRepository.INSTITUTION_NAP, artifact.getInstitutionSelect());
    assertEquals(BYTES.length, artifact.getByteLength().intValue());
  }

  @Test
  @DisplayName("Отговор и протокол на подаване: дружеството на подаването, и отчетът му")
  void responseFileTakesTheSubmissionsCompany() throws Exception {
    Company a = company("А");
    SaftReport report = new SaftReport();
    report.setCompany(a);
    NapSubmission withReport = new NapSubmission();
    withReport.setCompany(a);
    withReport.setSaftReport(report);
    NapSubmission d1 = new NapSubmission();
    d1.setCompany(a);

    NapArtifact saftProtocol =
        SERVICE.createForSubmission(
            metaFile("protocol.pdf"), NapArtifactRepository.TYPE_PROTOCOL, withReport);
    NapArtifact d1Protocol =
        SERVICE.createForSubmission(
            metaFile("protocol.pdf"), NapArtifactRepository.TYPE_PROCESSING_RESULT, d1);

    assertSame(a, saftProtocol.getCompany());
    assertSame(report, saftProtocol.getSaftReport());
    assertSame(a, d1Protocol.getCompany());
    assertNull(d1Protocol.getSaftReport());
  }

  @Test
  @DisplayName("Генериран файл (Прил. 9, чл. 73) и подпис/сертификат: подаденото дружество")
  void generatedFileTakesTheGivenCompany() throws Exception {
    Company a = company("А");

    NapArtifact noi =
        SERVICE.createFromFile(file(Institution.NOI), NapArtifactRepository.TYPE_PAYLOAD, a);
    NapArtifact signature =
        SERVICE.createFromFile(file(Institution.NAP), NapArtifactRepository.TYPE_SIGNATURE, a);

    assertSame(a, noi.getCompany());
    assertEquals(NapArtifactRepository.INSTITUTION_NOI, noi.getInstitutionSelect());
    assertSame(a, signature.getCompany());
  }

  @Test
  @DisplayName("Изрично дадено дружество бие това на отчета")
  void explicitCompanyWinsOverTheReports() throws Exception {
    Company a = company("А");
    Company b = company("Б");
    SaftReport report = new SaftReport();
    report.setCompany(a);

    NapArtifact artifact =
        SERVICE.createFromMetaFile(
            metaFile("x.xml"), NapArtifactRepository.TYPE_OTHER, report, Institution.NAP, b);

    assertSame(b, artifact.getCompany());
  }

  @Test
  @DisplayName("Схемата (без дружество) и старият вход без дружество — празно, не измислено")
  void companylessEntryPointsLeaveItBlank() throws Exception {
    NapArtifact schema =
        SERVICE.createFromFile(file(Institution.NAP), NapArtifactRepository.TYPE_SCHEMA, null);
    NapArtifact legacy =
        SERVICE.createFromFile(file(Institution.NAP), NapArtifactRepository.TYPE_PAYLOAD);
    NapArtifact noReport =
        SERVICE.createFromMetaFile(metaFile("x.xml"), NapArtifactRepository.TYPE_PAYLOAD, null);

    assertNull(schema.getCompany());
    assertNull(legacy.getCompany());
    assertNull(noReport.getCompany());
  }
}
