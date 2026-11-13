package com.axelor.apps.bglocalization.service.napartifact;

import com.axelor.apps.base.db.Company;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ПОПЪЛВА ПРАЗНОТО {@code NapArtifact.company} ПРИ ВСЕКИ СТАРТ — идемпотентно, като останалите
 * стартови стъпки на модула.
 *
 * <p>Каталогът на nyxa-security чете файловете за НАП/НОИ по {@code self.company}. Колоната е нова;
 * заварените файлове я нямат и без тази стъпка ги вижда само admin. Затова тя тръгва при старта,
 * преди приложението да отговаря: деплоят няма ред, който може да се обърка.
 *
 * <p><b>Откъде</b> — същите три стъпки като {@code
 * db/migration/2026-10-17-nap-artifact-company.sql} (той остава за ръчно пускане):
 *
 * <ol>
 *   <li>отчет — {@code saftReport.company};
 *   <li>подаване — {@code NapSubmissionArtifact.artifact} и {@code NapSubmissionFile.{payload,
 *       signature, signerCertificate}Artifact} → {@code submission.company};
 *   <li>документ — неподаден файл: износ и удостоверение по Прил. 9 ({@code bgNoiArtifact}),
 *       справка по чл. 73 ал. 1 и ал. 6 ({@code generatedArtifact}).
 * </ol>
 *
 * <p><b>Правила.</b> Само редове с {@code company IS NULL}. Само ЕДНОЗНАЧНОТО: различни дружества
 * за един файл → остава празен и се брои като двусмислен. Записът е масов JPQL UPDATE: той минава
 * покрай пазача на репозиторито, който отказва ВСЯКА смяна на company през save (REST/UI, и за
 * admin). Това е единственото място освен миграцията, откъдето празното се попълва. {@code version}
 * се увеличава, за да не може стара сесия да запише обратно празното. Схемата на SAF-T е нарочно
 * без дружество (една за всички).
 */
@Singleton
public class BgNapArtifactCompanyBackfill {

  /** Приоритетът на източника — само за броячите. */
  static final int REPORT = 1;

  static final int SUBMISSION = 2;
  static final int DOCUMENT = 3;

  /** Масовият UPDATE носи id-тата на части — IN списъкът има граници в драйверите. */
  private static final int CHUNK = 500;

  /** Какво е направило едно пускане. */
  public static final class Result {
    public int fromReport;
    public int fromSubmission;
    public int fromDocument;
    public int ambiguous;
    public long blankAfter;
    public long schemaBlank;

    public int filled() {
      return fromReport + fromSubmission + fromDocument;
    }

    /** Редът за лога. */
    public String message() {
      return "NapArtifact.company: попълнени "
          + filled()
          + " (от отчет "
          + fromReport
          + ", от подаване "
          + fromSubmission
          + ", от документ "
          + fromDocument
          + "); двусмислени "
          + ambiguous
          + "; остават празни "
          + blankAfter
          + " (от тях схема на SAF-T "
          + schemaBlank
          + ") — видими само за admin.";
    }
  }

  /** Един кандидат: файл, дружество, източник. */
  static final class Candidate {
    final long artifact;
    final long company;
    final int source;

    Candidate(long artifact, long company, int source) {
      this.artifact = artifact;
      this.company = company;
      this.source = source;
    }
  }

  /** Изборът за един файл: дружеството (ако е еднозначно) и най-силният източник. */
  static final class Pick {
    final Set<Long> companies = new LinkedHashSet<>();
    int source = Integer.MAX_VALUE;

    boolean unambiguous() {
      return companies.size() == 1;
    }

    long company() {
      return companies.iterator().next();
    }
  }

  /** Чиста функция: кандидатите → изборът на файл (подреден по id). */
  static Map<Long, Pick> resolve(List<Candidate> candidates) {
    Map<Long, Pick> picks = new TreeMap<>();
    for (Candidate c : candidates) {
      Pick pick = picks.computeIfAbsent(c.artifact, k -> new Pick());
      pick.companies.add(c.company);
      pick.source = Math.min(pick.source, c.source);
    }
    return picks;
  }

  @Transactional(rollbackOn = Exception.class)
  public Result run() {
    Result result = new Result();
    // (източник, дружество) → файловете; всяка група е един масов UPDATE и брои каквото е записал.
    Map<List<Long>, List<Long>> groups = new LinkedHashMap<>();
    for (Map.Entry<Long, Pick> e : resolve(candidates()).entrySet()) {
      Pick pick = e.getValue();
      if (!pick.unambiguous()) {
        result.ambiguous++;
        continue;
      }
      groups
          .computeIfAbsent(List.of((long) pick.source, pick.company()), k -> new ArrayList<>())
          .add(e.getKey());
    }

    for (Map.Entry<List<Long>, List<Long>> e : groups.entrySet()) {
      int source = e.getKey().get(0).intValue();
      Company company = JPA.em().getReference(Company.class, e.getKey().get(1));
      List<Long> ids = e.getValue();
      for (int from = 0; from < ids.size(); from += CHUNK) {
        int n =
            JPA.em()
                .createQuery(
                    "UPDATE NapArtifact a SET a.company = :company,"
                        + " a.version = COALESCE(a.version, 0) + 1"
                        + " WHERE a.id IN :ids AND a.company IS NULL")
                .setParameter("company", company)
                .setParameter("ids", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                .executeUpdate();
        if (source == REPORT) {
          result.fromReport += n;
        } else if (source == SUBMISSION) {
          result.fromSubmission += n;
        } else {
          result.fromDocument += n;
        }
      }
    }

    result.blankAfter =
        JPA.em()
            .createQuery("SELECT COUNT(a) FROM NapArtifact a WHERE a.company IS NULL", Long.class)
            .getSingleResult();
    result.schemaBlank =
        JPA.em()
            .createQuery(
                "SELECT COUNT(a) FROM NapArtifact a WHERE a.company IS NULL"
                    + " AND a.artifactTypeSelect = 'schema'",
                Long.class)
            .getSingleResult();
    return result;
  }

  /** Всички кандидати за файлове с празно дружество, от трите стъпки. */
  protected List<Candidate> candidates() {
    List<Candidate> all = new ArrayList<>();
    // 1. отчет
    add(
        all,
        REPORT,
        "SELECT a.id, r.company.id FROM NapArtifact a JOIN a.saftReport r"
            + " WHERE a.company IS NULL AND r.company IS NOT NULL");
    // 2. подаване
    add(
        all,
        SUBMISSION,
        "SELECT a.id, s.company.id FROM NapSubmissionArtifact l JOIN l.artifact a"
            + " JOIN l.submission s WHERE a.company IS NULL AND s.company IS NOT NULL");
    for (String field :
        new String[] {"payloadArtifact", "signatureArtifact", "signerCertificateArtifact"}) {
      add(
          all,
          SUBMISSION,
          "SELECT a.id, s.company.id FROM NapSubmissionFile f JOIN f."
              + field
              + " a JOIN f.submission s WHERE a.company IS NULL AND s.company IS NOT NULL");
    }
    // 3. документ
    add(
        all,
        DOCUMENT,
        "SELECT a.id, e.company.id FROM BgApp9Export e JOIN e.bgNoiArtifact a"
            + " WHERE a.company IS NULL AND e.company IS NOT NULL");
    add(
        all,
        DOCUMENT,
        "SELECT a.id, c.payCompany.id FROM BgApp9Declaration d JOIN d.bgNoiArtifact a"
            + " JOIN d.sickLeave sl JOIN sl.employmentContract c"
            + " WHERE a.company IS NULL AND c.payCompany IS NOT NULL");
    for (String entity : new String[] {"BgArt73Para1Report", "BgArt73Para6Report"}) {
      add(
          all,
          DOCUMENT,
          "SELECT a.id, t.company.id FROM "
              + entity
              + " t JOIN t.generatedArtifact a WHERE a.company IS NULL AND t.company IS NOT NULL");
    }
    return all;
  }

  private static void add(List<Candidate> all, int source, String jpql) {
    for (Object[] row : JPA.em().createQuery(jpql, Object[].class).getResultList()) {
      all.add(new Candidate((Long) row[0], (Long) row[1], source));
    }
  }
}
