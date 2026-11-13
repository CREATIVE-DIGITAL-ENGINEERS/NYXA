package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ЗАВАРЕНИТЕ ДРУЖЕСТВА ПОЛУЧАВАТ ТРЗ РЕДИЦИТЕ СИ ПРИ СТАРТ — продължени от НАЙ-ГОЛЕМИЯ СИ номер.
 *
 * <p><b>⚠️ ЗАЩО JAVA ПРИ СТАРТ, А НЕ SQL.</b> Правило: без ръчни SQL-и, които пишат в базата.
 * Образецът е {@code ensureVatProtocolSequences}: вика се от {@code
 * BgLocalizationServerStartListener#onStartup}, преди приложението да отговаря — тоест прозорец, в
 * който фишът на заварено дружество се отказва с „няма последователност“, няма.
 *
 * <p><b>ИДЕМПОТЕНТНО И САМО ДОБАВЯ.</b> Дружество, което ВЕЧЕ има редица с този код, не се пипа —
 * нито следващият номер, нито версиите. Второ пускане не променя нищо. Заварените номера (вкл.
 * {@code …-RES-…}) не се пренареждат; резервните не влизат в максимума — префиксът им е друг.
 *
 * <p><b>КАК СЕ НАМИРА МАКСИМУМЪТ.</b> По година: за всяка година, в която дружеството има истински
 * номер ({@code FISH-ГГГГ-N} / {@code OBEZ-ГГГГ-N}), се добавя годишна версия 01.01–31.12 със
 * следващ номер = най-големия N + 1 — редицата е с годишно нулиране и годината е в номера. За
 * година без номера Axelor създава версията сам, от 1.
 *
 * <p><b>⚠️ ДРУЖЕСТВОТО НА ДОКУМЕНТА — ОТ ДОГОВОРА, НЕ ОТ {@code company}.</b> Платецът има един
 * източник ({@code BgPaySlipWriteGuard#companyOf}): за фиша — {@code payCompany} на договора, а за
 * самоосигуряващия се — на основния договор от регистрацията; за обезщетението — на договора.
 * {@code company} е само за ключа и на заварените редове е празно до миграцията.
 */
public class BgTrzSequenceBackfill {

  /** Фишовете на дружеството по правилото на платеца — ВСЕКИ фиш с номер. */
  static final String PAY_SLIP_REFERENCES =
      "SELECT p.reference FROM BgPaySlip p"
          + " LEFT JOIN p.employmentContract c"
          + " LEFT JOIN p.bgSelfInsuredRegistration r"
          + " LEFT JOIN r.employee e"
          + " LEFT JOIN e.mainEmploymentContract m"
          + " WHERE p.reference IS NOT NULL"
          + " AND ((r.id IS NOT NULL AND m.payCompany = :company)"
          + " OR (r.id IS NULL AND c.payCompany = :company))";

  /** Обезщетенията на дружеството — платецът по договора. */
  static final String COMPENSATION_REFERENCES =
      "SELECT d.reference FROM BgCompensation d"
          + " WHERE d.reference IS NOT NULL AND d.employmentContract.payCompany = :company";

  protected final CompanyRepository companyRepo;
  protected final SequenceRepository sequenceRepo;
  protected final SequenceService sequenceService;

  @Inject
  public BgTrzSequenceBackfill(
      CompanyRepository companyRepo,
      SequenceRepository sequenceRepo,
      SequenceService sequenceService) {
    this.companyRepo = companyRepo;
    this.sequenceRepo = sequenceRepo;
    this.sequenceService = sequenceService;
  }

  /** Какво направи пускането — за лога и за свидетеля. */
  public static final class Result {
    public final List<String> created = new ArrayList<>();
    public int untouched;

    public String message() {
      return "ТРЗ редици по дружество: създадени "
          + created.size()
          + (created.isEmpty() ? "" : " — " + String.join("; ", created))
          + "; заварени, непипнати "
          + untouched
          + ".";
    }
  }

  @Transactional(rollbackOn = Exception.class)
  public Result run() {
    Result result = new Result();
    for (Company company : companyRepo.all().fetch()) {
      for (String code : BgTrzSequences.CODES) {
        if (sequenceService.getSequence(code, company) != null) {
          result.untouched++;
          continue;
        }
        Sequence sequence = BgTrzSequences.newFor(company, code);
        Map<Integer, Long> lastByYear = lastByYear(code, references(code, company));
        StringBuilder note = new StringBuilder();
        for (Map.Entry<Integer, Long> year : lastByYear.entrySet()) {
          SequenceVersion version = new SequenceVersion();
          version.setStartDate(LocalDate.of(year.getKey(), 1, 1));
          version.setEndDate(LocalDate.of(year.getKey(), 12, 31));
          version.setNextNum(year.getValue() + 1);
          sequence.addSequenceVersionListItem(version);
          note.append(' ')
              .append(year.getKey())
              .append(": последен ")
              .append(year.getValue())
              .append(" → следващ ")
              .append(year.getValue() + 1);
        }
        sequenceRepo.save(sequence);
        result.created.add(
            BgTrzSequences.companyLabel(company)
                + " · "
                + code
                + (note.length() == 0 ? " · без заварени номера (от 1)" : " ·" + note));
      }
    }
    return result;
  }

  /** Номерата на документите на дружеството с този код. Свой шев за свидетеля. */
  protected List<String> references(String code, Company company) {
    String jpql =
        BgTrzSequences.PAY_SLIP.equals(code) ? PAY_SLIP_REFERENCES : COMPENSATION_REFERENCES;
    return JPA.em()
        .createQuery(jpql, String.class)
        .setParameter("company", company)
        .getResultList();
  }

  /**
   * Най-големият истински номер по година — {@code FISH-2026-0007} → 2026: 7. Други префикси
   * (резервните {@code FISH-RES-…}, ръчно въведени) не влизат.
   */
  static Map<Integer, Long> lastByYear(String code, List<String> references) {
    String prefix = BgTrzSequences.prefixFor(code).replace("%YYYY-", "");
    Pattern number = Pattern.compile("^" + Pattern.quote(prefix) + "(\\d{4})-(\\d+)$");
    Map<Integer, Long> last = new TreeMap<>();
    for (String reference : references) {
      Matcher m = number.matcher(reference == null ? "" : reference.trim());
      if (m.matches()) {
        last.merge(Integer.valueOf(m.group(1)), Long.valueOf(m.group(2)), Math::max);
      }
    }
    return last;
  }
}
