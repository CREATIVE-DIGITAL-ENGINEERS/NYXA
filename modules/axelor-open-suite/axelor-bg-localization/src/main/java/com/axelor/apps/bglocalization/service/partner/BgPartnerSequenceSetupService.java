package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.List;

/**
 * ПОРЕДИЦАТА {@code partner} НА ВСЯКО ДРУЖЕСТВО — идемпотентно, при всеки старт и при създаване на
 * дружество.
 *
 * <p><b>{@link #ensureFor}</b> създава поредицата на дружество, което я няма, продължавайки над
 * всеки номер, който то може да срещне ({@link BgPartnerSequence#nextNum}). Съществуваща поредица
 * не се пипа — нито номерът, нито форматът. Вика се от {@code BgCompanyManagementRepository} при
 * ново дружество и от стартовата стъпка за всички.
 *
 * <p><b>Схемата не се пипа оттук</b> (приложението не променя схемата). Смяната на уникалността на
 * номера идва от {@code db/migration/2026-10-20-partner-seq-per-company.sql}; при старт {@link
 * BgPartnerSchemaCheck} само проверява.
 */
@Singleton
public class BgPartnerSequenceSetupService {

  /** Какво е направило едно пускане — за реда в лога. */
  public static final class Result {
    public final List<String> created = new ArrayList<>();

    public String message() {
      StringBuilder out = new StringBuilder("Partner sequences: създадени ");
      out.append(created.size());
      if (!created.isEmpty()) {
        out.append(" (").append(String.join(", ", created)).append(')');
      }
      return out.append('.').toString();
    }
  }

  protected final SequenceRepository sequenceRepo;
  protected final CompanyRepository companyRepo;

  @Inject
  public BgPartnerSequenceSetupService(
      SequenceRepository sequenceRepo, CompanyRepository companyRepo) {
    this.sequenceRepo = sequenceRepo;
    this.companyRepo = companyRepo;
  }

  /** Стартовата стъпка: поредиците на всички дружества (само данни, не схема). */
  public Result ensureAll() {
    Result result = new Result();
    for (Company company : companyRepo.all().order("id").fetch()) {
      if (ensureFor(company)) {
        result.created.add(company.getCode() != null ? company.getCode() : "#" + company.getId());
      }
    }
    return result;
  }

  /**
   * Създава поредицата на {@code company}, ако я няма.
   *
   * @return {@code true} само ако е създадена сега
   */
  @Transactional(rollbackOn = Exception.class)
  public boolean ensureFor(Company company) {
    if (company == null || company.getId() == null) {
      return false;
    }
    if (sequenceRepo.find(BgPartnerSequence.CODE, company) != null) {
      return false;
    }
    Sequence template = commonSequence();
    String prefix = template != null ? template.getPrefixe() : BgPartnerSequence.DEFAULT_PREFIX;
    String suffix = template != null ? template.getSuffixe() : null;
    long nextNum =
        BgPartnerSequence.nextNum(prefix, suffix, codesSeenBy(company), counterNext(template));
    sequenceRepo.save(BgPartnerSequence.newFor(company, template, nextNum));
    return true;
  }

  /** Общата поредица (company празно), ако още я има — само за формата и докъде е стигнала. */
  protected Sequence commonSequence() {
    return sequenceRepo
        .all()
        .filter("self.codeSelect = :code AND self.company IS NULL")
        .bind("code", BgPartnerSequence.CODE)
        .order("id")
        .fetchOne();
  }

  /**
   * Номерата, които дружеството може да срещне: своите и на заварените без дружество. Само
   * колоната, не целият партньор — формулата на Partner не се чете.
   */
  protected List<String> codesSeenBy(Company company) {
    return JPA.em()
        .createQuery(
            "SELECT p.partnerSeq FROM Partner p WHERE p.partnerSeq IS NOT NULL"
                + " AND (p.company = :company OR p.company IS NULL)",
            String.class)
        .setParameter("company", company)
        .getResultList();
  }

  /** Следващият номер на общия брояч; 0 — няма обща поредица или версия. */
  static long counterNext(Sequence common) {
    if (common == null || common.getSequenceVersionList() == null) {
      return 0;
    }
    long next = 0;
    for (SequenceVersion version : common.getSequenceVersionList()) {
      next = Math.max(next, version.getNextNum());
    }
    return next;
  }
}
