package com.axelor.apps.aiocr.service.storage;

import com.axelor.db.JPA;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.db.mapper.PropertyType;
import com.axelor.dms.db.DMSFile;
import com.axelor.meta.db.MetaAttachment;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds every relation in the application that can reference a {@link MetaFile}.
 *
 * <h2>Why it is derived, not listed</h2>
 *
 * The OCR delete path knows three relations of its own ({@code originalFile}, {@code archiveFile},
 * the receipt fields) plus attachments and DMS. That is the right question for "may I delete the
 * blob I am holding", but it is the wrong question for "does anything at all still use this file":
 * roughly a hundred columns across the suite reference {@code MetaFile} — company logos, BIRT
 * templates, VAT export artifacts, expense justifications, product pictures. A hand-written list
 * would be wrong the day a module adds a field, and being wrong here means calling a live file an
 * orphan.
 *
 * <p>So the relations are <b>enumerated from the JPA metamodel</b>: every non-transient property of
 * every activated model whose target type is {@code MetaFile}. That is exactly the set of mapped
 * foreign keys and join tables, in every module, by construction — it cannot drift.
 *
 * <h2>The one channel this cannot see, and what happens then</h2>
 *
 * A custom JSON field ({@code MetaJsonField}) may also target {@code MetaFile}; those references
 * live inside an {@code attrs} JSON column, not in a foreign key, so no metamodel walk finds them.
 * {@link #jsonReferenceCaveat()} detects whether any such field is defined and returns a reason
 * string when so. The accounting service then <b>withholds orphan classification</b> rather than
 * reporting counts it cannot stand behind. Never guess on this: a file wrongly called an orphan is
 * a file somebody eventually deletes.
 */
@Singleton
public class OcrMetaFileReferenceScanner {

  private static final Logger LOG = LoggerFactory.getLogger(OcrMetaFileReferenceScanner.class);

  /** One relation that can hold a MetaFile: an entity name plus the property path on it. */
  public static final class Relation {
    private final Class<?> model;
    private final String property;
    private final boolean collection;

    Relation(Class<?> model, String property, boolean collection) {
      this.model = model;
      this.property = property;
      this.collection = collection;
    }

    public Class<?> getModel() {
      return model;
    }

    public String getProperty() {
      return property;
    }

    public boolean isCollection() {
      return collection;
    }

    /** JPQL counting rows of this model that point at {@code :file}. */
    String countQuery() {
      String entity = model.getSimpleName();
      return collection
          ? "SELECT COUNT(x) FROM " + entity + " x WHERE :file MEMBER OF x." + property
          : "SELECT COUNT(x) FROM " + entity + " x WHERE x." + property + " = :file";
    }

    /**
     * JPQL returning which of {@code :ids} this relation holds — the batched form of {@link
     * #countQuery()}. One query answers a whole page instead of one file, which is the difference
     * between pages×relations and files×relations queries per scan.
     */
    String idsQuery() {
      String entity = model.getSimpleName();
      return collection
          ? "SELECT DISTINCT f.id FROM "
              + entity
              + " x JOIN x."
              + property
              + " f WHERE f.id IN :ids"
          : "SELECT DISTINCT x."
              + property
              + ".id FROM "
              + entity
              + " x WHERE x."
              + property
              + ".id IN :ids";
    }

    @Override
    public String toString() {
      return model.getSimpleName() + "." + property;
    }
  }

  private volatile List<Relation> cached;

  /**
   * Every relation targeting {@code MetaFile}, across all activated models. Computed once — the
   * metamodel does not change at runtime — and never empty in a working application.
   */
  public List<Relation> relations() {
    List<Relation> local = cached;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      if (cached != null) {
        return cached;
      }
      List<Relation> found = new ArrayList<>();
      for (Class<?> model : JPA.models()) {
        Property[] properties;
        try {
          properties = Mapper.of(model).getProperties();
        } catch (RuntimeException e) {
          LOG.warn(
              "Skipping model {} while scanning MetaFile relations: {}", model, e.getMessage());
          continue;
        }
        for (Property p : properties) {
          if (p == null || p.isTransient() || !MetaFile.class.equals(p.getTarget())) {
            continue;
          }
          // A mappedBy side is the inverse of a relation already counted on the owning side;
          // counting both would just do the same query twice.
          if (p.getMappedBy() != null) {
            continue;
          }
          PropertyType type = p.getType();
          boolean collection =
              PropertyType.ONE_TO_MANY.equals(type) || PropertyType.MANY_TO_MANY.equals(type);
          found.add(new Relation(model, p.getName(), collection));
        }
      }
      cached = Collections.unmodifiableList(found);
      LOG.info("MetaFile reference scanner: {} relation(s) discovered.", found.size());
      return cached;
    }
  }

  /** What a batched reference check found: proven hits, unprovable ids, and what it cost. */
  public static final class BatchResult {

    private final Set<Long> referenced;
    private final Set<Long> unproven;
    private final int queriesExecuted;
    private final String failureReason;

    BatchResult(
        Set<Long> referenced, Set<Long> unproven, int queriesExecuted, String failureReason) {
      this.referenced = Collections.unmodifiableSet(referenced);
      this.unproven = Collections.unmodifiableSet(unproven);
      this.queriesExecuted = queriesExecuted;
      this.failureReason = failureReason;
    }

    /** Ids something in the application demonstrably still points at. */
    public Set<Long> getReferenced() {
      return referenced;
    }

    /**
     * Ids whose status could not be established because a relation query failed. The caller must
     * treat these as referenced: an orphan asserted on the strength of a failed query is a file
     * somebody eventually deletes.
     */
    public Set<Long> getUnproven() {
      return unproven;
    }

    public int getQueriesExecuted() {
      return queriesExecuted;
    }

    /** Why {@link #getUnproven()} is non-empty, or {@code null} when the check was complete. */
    public String getFailureReason() {
      return failureReason;
    }
  }

  /**
   * Which of {@code candidateIds} anything in the application still references — one query per
   * relation for the whole batch, not per file.
   *
   * <p>Ids are dropped from the working set as they are matched, so each relation is only ever
   * asked about the ids still in doubt, and the loop stops as soon as nothing is left to prove.
   *
   * <p><b>Fail-closed.</b> If a relation query throws, every id still unresolved at that point goes
   * to {@link BatchResult#getUnproven()} — not just the one being examined. A single unreadable
   * relation must never be able to turn a whole page of live files into reported orphans.
   */
  public BatchResult referencedIds(Collection<Long> candidateIds) {
    Set<Long> referenced = new LinkedHashSet<>();
    Set<Long> unproven = new LinkedHashSet<>();
    Set<Long> unknown = new LinkedHashSet<>();
    if (candidateIds != null) {
      for (Long id : candidateIds) {
        if (id != null) {
          unknown.add(id);
        }
      }
    }
    if (unknown.isEmpty()) {
      return new BatchResult(referenced, unproven, 0, null);
    }

    int queries = 0;
    for (Relation r : relations()) {
      if (unknown.isEmpty()) {
        break; // nothing left in doubt; the remaining relations cannot change the answer
      }
      List<Long> batch = new ArrayList<>(unknown);
      try {
        queries++;
        for (Long hit : executeRelationQuery(r, batch)) {
          if (hit != null && unknown.remove(hit)) {
            referenced.add(hit);
          }
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not check {} for {} MetaFile id(s): {}", r, batch.size(), e.getMessage());
        unproven.addAll(unknown);
        unknown.clear();
        return new BatchResult(
            referenced,
            unproven,
            queries,
            "the relation " + r + " could not be queried (" + e.getMessage() + ")");
      }
    }
    return new BatchResult(referenced, unproven, queries, null);
  }

  /**
   * The one place a relation query is executed, so a test can drive the batch without a database.
   */
  protected List<Long> executeRelationQuery(Relation relation, List<Long> ids) {
    return JPA.em()
        .createQuery(relation.idsQuery(), Long.class)
        .setParameter("ids", ids)
        .getResultList();
  }

  /**
   * Whether anything in the application still references this file. The single-file form of {@link
   * #referencedIds}; an id that could not be proven either way answers {@code true}, never orphan.
   */
  public boolean isReferencedAnywhere(MetaFile file) {
    if (file == null || file.getId() == null) {
      return false;
    }
    BatchResult result = referencedIds(Collections.singletonList(file.getId()));
    return !result.getReferenced().isEmpty() || !result.getUnproven().isEmpty();
  }

  /** The relations that DO reference this file, for diagnostics. Never used to decide deletion. */
  public Set<String> referencingRelations(MetaFile file) {
    Set<String> hits = new LinkedHashSet<>();
    if (file == null || file.getId() == null) {
      return hits;
    }
    for (Relation r : relations()) {
      try {
        Long n =
            JPA.em()
                .createQuery(r.countQuery(), Long.class)
                .setParameter("file", file)
                .getSingleResult();
        if (n != null && n > 0) {
          hits.add(r.toString());
        }
      } catch (RuntimeException e) {
        hits.add(r + " (unreadable)");
      }
    }
    return hits;
  }

  /**
   * {@code null} when orphan detection is provably complete; otherwise the reason it is not.
   *
   * <p>The only known blind spot is a custom JSON field targeting {@code MetaFile}, whose value
   * lives in an {@code attrs} JSON column rather than a foreign key.
   */
  public String jsonReferenceCaveat() {
    try {
      Long n =
          JPA.em()
              .createQuery(
                  "SELECT COUNT(f) FROM MetaJsonField f WHERE f.targetModel = :target", Long.class)
              .setParameter("target", MetaFile.class.getName())
              .getSingleResult();
      if (n != null && n > 0) {
        return n
            + " custom JSON field(s) target MetaFile. Those references live in JSON, not in a"
            + " foreign key, so an unreferenced count cannot be proven complete and is withheld.";
      }
      return null;
    } catch (RuntimeException e) {
      return "the custom-field metadata could not be read (" + e.getMessage() + ")";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Single-file reference classification (the delete/share decision)
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether this relation is one of the two platform <b>attachment carriers</b> rather than a
   * business holder. Only {@code DMSFile.metaFile} and {@code MetaAttachment.metaFile} qualify —
   * every other relation (present or added by a future module) classifies as a business holder and
   * therefore <b>protects</b> the file. The default is protection, so nothing can drift into being
   * ignored.
   */
  static boolean isDmsCarrier(Relation relation) {
    Class<?> model = relation.getModel();
    return DMSFile.class.equals(model) || MetaAttachment.class.equals(model);
  }

  /**
   * What actually references one {@link MetaFile}, split by holder kind so a caller can tell
   * "nobody holds this" from "an attachment still holds it" from "a business record still holds
   * it". <b>No relation is excluded from the scan</b>; the split is a classification, not a filter.
   */
  public static final class FileReferences {

    private final Set<Relation> dmsHolders;
    private final Set<Relation> otherHolders;
    private final String failureReason;

    FileReferences(Set<Relation> dmsHolders, Set<Relation> otherHolders, String failureReason) {
      this.dmsHolders = Collections.unmodifiableSet(dmsHolders);
      this.otherHolders = Collections.unmodifiableSet(otherHolders);
      this.failureReason = failureReason;
    }

    /** {@code DMSFile}/{@code MetaAttachment} relations that still hold the file. */
    public Set<Relation> getDmsHolders() {
      return dmsHolders;
    }

    /** Business relations (any model but the two carriers) that still hold the file. */
    public Set<Relation> getOtherHolders() {
      return otherHolders;
    }

    /** Why the scan is incomplete, or {@code null} when it is provably complete. */
    public String getFailureReason() {
      return failureReason;
    }

    /**
     * The scan could not be completed — a relation query failed, or a custom JSON field may hold
     * MetaFile ids outside any foreign key. Callers must retain the file: an unproven scan is never
     * a licence to delete.
     */
    public boolean isUnproven() {
      return failureReason != null;
    }

    public boolean hasDmsHolder() {
      return !dmsHolders.isEmpty();
    }

    public boolean hasOtherHolder() {
      return !otherHolders.isEmpty();
    }

    /** Provably nothing at all references the file. The ONLY state in which deletion is safe. */
    public boolean isUnreferenced() {
      return !isUnproven() && dmsHolders.isEmpty() && otherHolders.isEmpty();
    }
  }

  /**
   * Classify everything that references {@code file}, across <b>every</b> metamodel-discovered
   * relation.
   *
   * <p><b>Fail-closed, in this order:</b>
   *
   * <ol>
   *   <li>a {@link #jsonReferenceCaveat()} makes the result {@link FileReferences#isUnproven()} at
   *       once — query success across mapped relations proves nothing while a {@code MetaJsonField}
   *       can hold a MetaFile id in an {@code attrs} column;
   *   <li>the two DMS carriers are asked first: a remaining attachment holder is already the whole
   *       answer, so the scan stops there;
   *   <li>the business relations are asked next and stop at the first holder — one is enough to
   *       protect the file;
   *   <li>any query failure returns immediately with a reason, never a clean "unreferenced".
   * </ol>
   */
  public FileReferences referencesOf(MetaFile file) {
    Set<Relation> dms = new LinkedHashSet<>();
    Set<Relation> other = new LinkedHashSet<>();
    if (file == null || file.getId() == null) {
      // A file with no id cannot be referenced by a foreign key, and cannot be deleted either.
      return new FileReferences(dms, other, null);
    }
    String caveat = jsonReferenceCaveat();
    if (caveat != null) {
      return new FileReferences(dms, other, caveat);
    }
    List<Relation> all = relations();
    for (Relation r : all) {
      if (!isDmsCarrier(r)) {
        continue;
      }
      try {
        if (holds(r, file)) {
          dms.add(r);
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not check {} for MetaFile id={}: {}", r, file.getId(), e.getMessage());
        return new FileReferences(dms, other, unreadable(r, e));
      }
    }
    if (!dms.isEmpty()) {
      return new FileReferences(dms, other, null); // an attachment still holds it — decided
    }
    for (Relation r : all) {
      if (isDmsCarrier(r)) {
        continue;
      }
      try {
        if (holds(r, file)) {
          other.add(r);
          break; // one business holder is enough to protect the file
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not check {} for MetaFile id={}: {}", r, file.getId(), e.getMessage());
        return new FileReferences(dms, other, unreadable(r, e));
      }
    }
    return new FileReferences(dms, other, null);
  }

  private static String unreadable(Relation r, RuntimeException e) {
    return "the relation " + r + " could not be queried (" + e.getMessage() + ")";
  }

  /**
   * Whether this one relation holds this one file. Routed through {@link #executeRelationQuery} so
   * a test drives the whole classification through the single existing seam.
   */
  protected boolean holds(Relation relation, MetaFile file) {
    return !executeRelationQuery(relation, Collections.singletonList(file.getId())).isEmpty();
  }

  /** Pure: the count-once rule for a collection of relation hit counts. */
  public static boolean isUnreferenced(Collection<Long> relationHitCounts) {
    if (relationHitCounts == null) {
      return true;
    }
    for (Long n : relationHitCounts) {
      if (n != null && n > 0) {
        return false;
      }
    }
    return true;
  }
}
