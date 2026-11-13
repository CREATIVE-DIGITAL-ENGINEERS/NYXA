package com.axelor.apps.bglocalization.service.setup;

import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.db.repo.SequenceVersionRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repairs the numbering sequences of Bulgarian companies created before the BG template import was
 * fixed.
 *
 * <h2>The defect being repaired</h2>
 *
 * {@code chart-config.xml} binds the reset flag as {@code yearlyResetOk == '1' ? true : false},
 * accepting only the literal {@code '1'}. The BG {@code base_sequence.csv} wrote {@code "true"} /
 * {@code "false"}, so <b>every</b> BG sequence imported with {@code yearlyResetOk = false},
 * silently discarding the intent of the ones that declared {@code "true"}.
 *
 * <p>{@code ImportSequence} then seeded each sequence's version through {@code getVersion(sequence,
 * LocalDate.now())}. With no reset flag set, {@code
 * SequenceVersionGeneratorServiceImpl.findStartDateFromPreviousVersion} has no previous end date to
 * work from and falls back to <em>today</em>, so the version starts on the company's setup day.
 *
 * <p>{@code SequenceVersionRepository.findByDate} requires {@code startDate <= refDate}, and the
 * reference date is the document's own date. Every back-dated document therefore matches no
 * version, Axelor mints another one starting today at {@code nextNum = 1}, and the numbering series
 * restarts. The first such document takes number 1; the next asks for number 1 again and dies on
 * "The generated sequence %s already exists".
 *
 * <h2>What this repairs, and what it refuses to touch</h2>
 *
 * The template is fixed for new companies. Existing ones keep the imported rows, so they need this.
 * It is deliberately narrow:
 *
 * <ul>
 *   <li>only sequences carrying {@code importOrigin = BG_COA.zip} — the BG accounting template's
 *       own file name — and belonging to a company. A user-created sequence has no import origin
 *       and is never considered, however odd its versions look;
 *   <li>the intended reset mode is read from the packaged template CSV, the same file the importer
 *       reads, matched on the template's own import id. Nothing is inferred from the data;
 *   <li>the reset flag is only ever corrected {@code false → true}. The defect could not produce
 *       the opposite, so a {@code true} in the database is somebody's decision and is left alone;
 *   <li>versions are reshaped only when they are all open-ended, which is the exact shape the
 *       defect produces. A sequence that already owns a real yearly version (one with an end date)
 *       keeps its per-year versions untouched;
 *   <li>where the next number cannot be established from issued data, the sequence is skipped with
 *       a warning rather than guessed at.
 * </ul>
 *
 * <h2>Numbers already issued are never reused</h2>
 *
 * The next number is the highest of two independent lower bounds: the counters the existing
 * versions already reached, and the highest number actually present on the records this sequence
 * numbers. It is never lowered. Existing invoices and moves are never renumbered — this touches
 * {@code Sequence} and {@code SequenceVersion} only.
 */
@Singleton
public class BgSequenceImportRepairService {

  private static final Logger LOG = LoggerFactory.getLogger(BgSequenceImportRepairService.class);

  /** The BG accounting template's code; its zip is what {@code importOrigin} records. */
  static final String TEMPLATE_CODE = "BG_COA";

  /** {@code ImportAccountingConfigTemplate} names the metaFile {@code <code>.zip}. */
  static final String TEMPLATE_ORIGIN = TEMPLATE_CODE + ".zip";

  /** The same CSV the importer reads, so intent has exactly one source. */
  static final String TEMPLATE_RESOURCE = "/l10n/l10n_bgr/" + TEMPLATE_CODE + "/base_sequence.csv";

  /**
   * Start date given to a repaired perpetual version.
   *
   * <p>The same constant the fixed template seeds, so a repaired company and a newly created one
   * end up in the same shape. It only has to precede every document the company will ever post; it
   * is not an accounting date and appears on nothing.
   */
  static final LocalDate PERPETUAL_START = LocalDate.of(2000, 1, 1);

  /** Column order of {@code base_sequence.csv}. */
  private static final int COL_IMPORT_ID = 0;

  private static final int COL_YEARLY_RESET = 10;

  private final SequenceRepository sequenceRepository;
  private final SequenceVersionRepository sequenceVersionRepository;

  @Inject
  public BgSequenceImportRepairService(
      SequenceRepository sequenceRepository, SequenceVersionRepository sequenceVersionRepository) {
    this.sequenceRepository = sequenceRepository;
    this.sequenceVersionRepository = sequenceVersionRepository;
  }

  /** What one sequence's inspection concluded, for the summary log. */
  private enum Outcome {
    UNCHANGED,
    REPAIRED,
    SKIPPED
  }

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  /**
   * Inspects every BG-imported sequence and repairs the ones the import defect damaged.
   *
   * <p>Safe to run on every startup: a sequence already in its canonical shape is recognised and
   * left alone, so a repaired database converges to zero repairs and logs one summary line.
   */
  public void repairAll() {
    Map<String, Boolean> intent = readTemplateIntent();
    if (intent.isEmpty()) {
      LOG.warn(
          "BG sequence repair skipped: could not read the intended reset modes from {}.",
          TEMPLATE_RESOURCE);
      return;
    }

    // The whole derivation below assumes no duplicate number was ever committed, which is only
    // guaranteed while Axelor's own generation-time uniqueness check is on. Without it the counters
    // could understate what was issued, so refuse rather than compute a number that might repeat.
    if (!checkExistingSequenceGuardEnabled()) {
      LOG.warn(
          "BG sequence repair skipped: AppBase.checkExistingSequenceOnGeneration is off, so the"
              + " highest issued number cannot be established safely. Turn it on and restart.");
      return;
    }

    List<Sequence> sequences =
        sequenceRepository
            .all()
            .filter("self.importOrigin = :origin AND self.company IS NOT NULL")
            .bind("origin", TEMPLATE_ORIGIN)
            .fetch();
    if (sequences.isEmpty()) {
      return; // no BG company on this instance
    }

    int repaired = 0;
    int skipped = 0;
    for (Sequence sequence : sequences) {
      try {
        Outcome outcome = repairOne(sequence, intent);
        if (outcome == Outcome.REPAIRED) {
          repaired++;
        } else if (outcome == Outcome.SKIPPED) {
          skipped++;
        }
      } catch (RuntimeException e) {
        skipped++;
        LOG.warn(
            "BG sequence repair: sequence {} (company {}) left untouched — {}",
            sequenceLabel(sequence),
            companyLabel(sequence),
            e.getMessage());
      }
    }
    LOG.info(
        "BG sequence repair: {} inspected, {} repaired, {} left for manual review.",
        sequences.size(),
        repaired,
        skipped);
  }

  // ---------------------------------------------------------------------------
  // One sequence
  // ---------------------------------------------------------------------------

  @Transactional(rollbackOn = Exception.class)
  protected Outcome repairOne(Sequence sequence, Map<String, Boolean> intent) {
    Boolean yearlyIntended = intent.get(templateImportId(sequence));
    if (yearlyIntended == null) {
      return Outcome.UNCHANGED; // not a row of the current template — not ours to judge
    }

    boolean changed = false;

    // 1. The reset flag. Only the direction the defect could produce.
    if (yearlyIntended && !Boolean.TRUE.equals(sequence.getYearlyResetOk())) {
      sequence.setYearlyResetOk(true);
      changed = true;
      LOG.info(
          "BG sequence repair: {} (company {}) reset mode corrected to yearly.",
          sequenceLabel(sequence),
          companyLabel(sequence));
    }

    List<SequenceVersion> versions =
        sequence.getSequenceVersionList() == null
            ? new ArrayList<>()
            : new ArrayList<>(sequence.getSequenceVersionList());

    // 2. Versions. A real yearly version always carries an end date; the defect never produces one.
    // Anything with an end date is therefore genuine and its per-year semantics are preserved.
    boolean anyClosed = versions.stream().anyMatch(v -> v.getEndDate() != null);
    if (anyClosed) {
      if (changed) {
        sequenceRepository.save(sequence);
      }
      return changed ? Outcome.REPAIRED : Outcome.UNCHANGED;
    }
    if (versions.isEmpty()) {
      // Nothing to reshape and nothing issued through a version; Axelor will create the right one
      // now that the flag is correct.
      if (changed) {
        sequenceRepository.save(sequence);
      }
      return changed ? Outcome.REPAIRED : Outcome.UNCHANGED;
    }

    LocalDate targetStart;
    LocalDate targetEnd;
    if (yearlyIntended) {
      Set<Integer> years = new LinkedHashSet<>();
      for (SequenceVersion v : versions) {
        if (v.getStartDate() != null) {
          years.add(v.getStartDate().getYear());
        }
      }
      if (years.size() != 1) {
        LOG.warn(
            "BG sequence repair: {} (company {}) left untouched — its {} open version(s) span {}"
                + " years, so the correct yearly shape is ambiguous. Repair it in"
                + " Administration → Sequences.",
            sequenceLabel(sequence),
            companyLabel(sequence),
            versions.size(),
            years.size());
        return Outcome.SKIPPED;
      }
      int year = years.iterator().next();
      targetStart = LocalDate.of(year, 1, 1);
      targetEnd = LocalDate.of(year, 12, 31);
    } else {
      targetStart = PERPETUAL_START;
      targetEnd = null;
    }

    Long nextNum = deriveNextNum(sequence, versions, targetStart, targetEnd);
    if (nextNum == null) {
      LOG.warn(
          "BG sequence repair: {} (company {}) left untouched — it has already issued numbers and"
              + " this service cannot tell which records hold them (codeSelect '{}'). Repair it in"
              + " Administration → Sequences.",
          sequenceLabel(sequence),
          companyLabel(sequence),
          sequence.getCodeSelect());
      return Outcome.SKIPPED;
    }

    // 3. Already canonical? Then this is a repeat run and there is nothing to do.
    SequenceVersion keeper =
        versions.stream()
            .max(Comparator.comparing(v -> v.getNextNum() == null ? 0L : v.getNextNum()))
            .orElse(null);
    if (versions.size() == 1
        && keeper != null
        && targetStart.equals(keeper.getStartDate())
        && java.util.Objects.equals(targetEnd, keeper.getEndDate())
        && keeper.getNextNum() != null
        && keeper.getNextNum() >= nextNum) {
      if (changed) {
        sequenceRepository.save(sequence);
      }
      return changed ? Outcome.REPAIRED : Outcome.UNCHANGED;
    }

    // 4. Consolidate onto the version that got furthest, and drop the rest. They are counters the
    // defect created, not history: nothing references them and they carry no accounting content.
    for (SequenceVersion v : versions) {
      if (v == keeper) {
        continue;
      }
      sequence.removeSequenceVersionListItem(v);
      sequenceVersionRepository.remove(v);
    }
    keeper.setStartDate(targetStart);
    keeper.setEndDate(targetEnd);
    keeper.setNextNum(nextNum);
    sequenceVersionRepository.save(keeper);
    sequenceRepository.save(sequence);

    LOG.info(
        "BG sequence repair: {} (company {}) consolidated {} open version(s) into one covering"
            + " {}..{} with next number {}.",
        sequenceLabel(sequence),
        companyLabel(sequence),
        versions.size(),
        targetStart,
        targetEnd == null ? "open" : targetEnd,
        nextNum);
    return Outcome.REPAIRED;
  }

  // ---------------------------------------------------------------------------
  // Next number
  // ---------------------------------------------------------------------------

  /**
   * The next number the repaired version must start from, or null when it cannot be established.
   *
   * <p>Two independent lower bounds, and the answer is the higher:
   *
   * <ol>
   *   <li><b>the counters themselves.</b> Every version starts at 1 and advances only on a
   *       successful issue, and Axelor's uniqueness check (verified on above) means a number that
   *       collided was never committed. The furthest counter is therefore a true count of what this
   *       sequence issued;
   *   <li><b>the records.</b> The highest number actually present on the entity this sequence
   *       numbers, plus its increment. This catches anything the counters could have lost.
   * </ol>
   *
   * <p>When the records cannot be inspected — a sequence numbering something this service has no
   * mapping for — the second bound is unavailable. That is only acceptable if nothing was ever
   * issued, i.e. every counter is still at 1; otherwise null is returned and the caller skips.
   */
  protected Long deriveNextNum(
      Sequence sequence,
      List<SequenceVersion> versions,
      LocalDate targetStart,
      LocalDate targetEnd) {
    long fromCounters = 1L;
    for (SequenceVersion v : versions) {
      if (v.getNextNum() != null && v.getNextNum() > fromCounters) {
        fromCounters = v.getNextNum();
      }
    }

    Long fromRecords = highestIssued(sequence, targetStart, targetEnd);
    if (fromRecords == null) {
      // No mapping for this kind of sequence. Safe only when nothing has been issued at all.
      return fromCounters == 1L ? 1L : null;
    }
    long increment = sequence.getToBeAdded();
    long fromData = fromRecords + Math.max(1L, increment);
    return Math.max(fromCounters, fromData);
  }

  /**
   * The highest number this sequence has already put on a record, or null when unknown.
   *
   * <p>Mapped from {@code codeSelect} to the entity and field Axelor numbers with it — {@code
   * VentilateState.setInvoiceId} uses {@code Invoice.invoiceId}, {@code MoveSequenceService} uses
   * {@code Move.reference}. Only the two that number back-dated accounting documents are mapped,
   * because those are the ones this defect can damage; everything else falls through to the
   * "nothing issued" test rather than to a guess.
   *
   * <p>Values are matched against the shape this sequence generates and the numeric run is read out
   * of them. The prefix and suffix are turned into a pattern rather than regenerated, so no part of
   * Axelor's numbering is reimplemented here.
   */
  protected Long highestIssued(Sequence sequence, LocalDate targetStart, LocalDate targetEnd) {
    String entity;
    String field;
    switch (sequence.getCodeSelect() == null ? "" : sequence.getCodeSelect()) {
      case SequenceRepository.INVOICE:
        entity = "Invoice";
        field = "invoiceId";
        break;
      case SequenceRepository.MOVE:
        entity = "Move";
        field = "reference";
        break;
      default:
        return null;
    }

    Pattern pattern = valuePattern(sequence, targetEnd == null ? null : targetStart.getYear());
    if (pattern == null) {
      return null;
    }

    List<?> values;
    try {
      values =
          JPA.em()
              .createQuery(
                  "SELECT self."
                      + field
                      + " FROM "
                      + entity
                      + " self WHERE self.company = :company AND self."
                      + field
                      + " IS NOT NULL")
              .setParameter("company", sequence.getCompany())
              .getResultList();
    } catch (RuntimeException e) {
      LOG.debug("BG sequence repair: could not read issued {} values: {}", entity, e.getMessage());
      return null;
    }

    long highest = 0L;
    for (Object raw : values) {
      if (raw == null) {
        continue;
      }
      Matcher m = pattern.matcher(String.valueOf(raw).trim());
      if (!m.matches()) {
        continue; // a draft placeholder, or a number from a different sequence
      }
      try {
        long n = Long.parseLong(m.group(1));
        if (n > highest) {
          highest = n;
        }
      } catch (NumberFormatException ignored) {
        // a value long enough to overflow is not a number this sequence issued
      }
    }
    return highest;
  }

  /**
   * A pattern matching exactly the values this sequence produces, with the counter as group 1.
   *
   * <p>{@code year} pins the date tokens when the target version covers a single year, so a yearly
   * sequence only ever compares against its own year. Left null for a perpetual sequence, where the
   * counter runs across years and every year's values are relevant.
   *
   * <p>Returns null for a script-driven prefix or suffix: those are arbitrary Groovy and the values
   * they produced cannot be recognised without running them.
   */
  protected Pattern valuePattern(Sequence sequence, Integer year) {
    if (Boolean.TRUE.equals(sequence.getPrefixGroovyOk())
        || Boolean.TRUE.equals(sequence.getSuffixGroovyOk())) {
      return null;
    }
    try {
      String prefix = tokensToRegex(sequence.getPrefixe(), year);
      String suffix = tokensToRegex(sequence.getSuffixe(), year);
      return Pattern.compile("^" + prefix + "(\\d+)" + suffix + "$");
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Literal text quoted, date tokens turned into the digits they stand for. */
  private String tokensToRegex(String raw, Integer year) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < raw.length()) {
      if (raw.charAt(i) == '%') {
        // Same tokens as SequenceService.PATTERN_*, and %YYYY must be tested before %YY for the
        // same reason SequenceService substitutes it first: otherwise the longer token is eaten by
        // the shorter one and two stray digits are left behind.
        String rest = raw.substring(i);
        if (rest.startsWith("%YYYY")) {
          out.append(year == null ? "\\d{4}" : Pattern.quote(String.valueOf(year)));
          i += 5;
          continue;
        }
        if (rest.startsWith("%YY")) {
          out.append(year == null ? "\\d{2}" : Pattern.quote(String.format("%02d", year % 100)));
          i += 3;
          continue;
        }
        if (rest.startsWith("%FM")) {
          out.append("\\d{2}");
          i += 3;
          continue;
        }
        if (rest.startsWith("%WY")) {
          out.append("\\d{1,2}");
          i += 3;
          continue;
        }
        if (rest.startsWith("%M") || rest.startsWith("%D")) {
          out.append("\\d{1,2}");
          i += 2;
          continue;
        }
      }
      out.append(Pattern.quote(String.valueOf(raw.charAt(i))));
      i++;
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------
  // Template intent
  // ---------------------------------------------------------------------------

  /**
   * The intended reset mode of every sequence in the packaged BG template, by its template import
   * id.
   *
   * <p>Read from the very CSV the importer reads, and interpreted with the very expression {@code
   * chart-config.xml} uses ({@code == '1'}), so this cannot disagree with what a fresh import would
   * now produce.
   */
  protected Map<String, Boolean> readTemplateIntent() {
    Map<String, Boolean> intent = new HashMap<>();
    try (InputStream in = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        return intent;
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line = reader.readLine(); // header
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) {
          continue;
        }
        String[] cells = line.split(";", -1);
        if (cells.length <= COL_YEARLY_RESET) {
          continue;
        }
        String importId = unquote(cells[COL_IMPORT_ID]);
        if (importId.isEmpty()) {
          continue;
        }
        intent.put(importId, "1".equals(unquote(cells[COL_YEARLY_RESET])));
      }
    } catch (Exception e) {
      LOG.warn("BG sequence repair: could not read {}: {}", TEMPLATE_RESOURCE, e.getMessage());
    }
    return intent;
  }

  /**
   * The template's own import id for this sequence.
   *
   * <p>The importer stores {@code <companyCode>-<templateImportId>}, and a company code may itself
   * contain a hyphen, so the split is on the last one.
   */
  protected String templateImportId(Sequence sequence) {
    String importId = sequence.getImportId();
    if (importId == null) {
      return "";
    }
    int dash = importId.lastIndexOf('-');
    return dash < 0 ? importId : importId.substring(dash + 1);
  }

  protected boolean checkExistingSequenceGuardEnabled() {
    try {
      return Boolean.TRUE.equals(
          Beans.get(AppBaseService.class).getAppBase().getCheckExistingSequenceOnGeneration());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String unquote(String cell) {
    String v = cell == null ? "" : cell.trim();
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      v = v.substring(1, v.length() - 1);
    }
    return v.trim();
  }

  /**
   * How a sequence is named in the log.
   *
   * <p>{@code Sequence} has no code of its own — it is identified by {@code codeSelect} (what kind
   * of document it numbers) plus its name, and {@code fullName} is the display column.
   */
  private static String sequenceLabel(Sequence sequence) {
    String name =
        sequence.getFullName() == null || sequence.getFullName().isEmpty()
            ? sequence.getName()
            : sequence.getFullName();
    return (name == null ? "(unnamed)" : name) + " [" + sequence.getImportId() + "]";
  }

  /** Company code for the log; never an accounting value. */
  private static String companyLabel(Sequence sequence) {
    return sequence.getCompany() == null ? "-" : sequence.getCompany().getCode();
  }
}
