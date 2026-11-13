package com.axelor.apps.aiaccountant.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which records the current chat turn actually changed, so the open form can refresh itself.
 *
 * <p>Without this the user is told "I set the origin date on invoice 576" and then has to press F5
 * to see it, which reads as the assistant not having done anything. The reply therefore carries the
 * records it touched, and the client refreshes the tab it already has open — and only that tab.
 *
 * <h2>Only committed writes</h2>
 *
 * A write tool is transactional: it either commits or rolls back entirely. Only a tool that
 * returned normally reports here, so what reaches the reply is what the database actually kept.
 * Reporting a rolled-back change would be worse than not refreshing at all: the form would reload
 * and show the old values while the assistant claimed a change.
 *
 * <p>Thread-local and turn-scoped, like {@link AiTurnReadMemory}: one chat request is one thread,
 * and two users' turns must never see each other's effects.
 *
 * <p>Nothing here is a permission or a control. It is a notification channel — the client decides
 * whether the record is on screen, and the server has already decided whether the write was
 * allowed.
 */
public final class AiWriteEffects {

  /** Ceiling, so a runaway batch cannot grow the reply without bound. */
  private static final int MAX_RECORDS = 20;

  private static final ThreadLocal<Set<Ref>> TOUCHED = new ThreadLocal<>();

  private AiWriteEffects() {}

  /** One changed record: the fully-qualified model name and its id. */
  public static final class Ref {
    private final String model;
    private final Long id;

    Ref(String model, Long id) {
      this.model = model;
      this.id = id;
    }

    public String getModel() {
      return model;
    }

    public Long getId() {
      return id;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Ref)) {
        return false;
      }
      Ref other = (Ref) o;
      return model.equals(other.model) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
      return model.hashCode() * 31 + id.hashCode();
    }

    @Override
    public String toString() {
      return model + "#" + id;
    }
  }

  /** Opens collection for a turn, discarding anything left over. */
  public static void begin() {
    TOUCHED.set(new LinkedHashSet<>());
  }

  /** Ends collection and releases the thread-local. */
  public static void end() {
    TOUCHED.remove();
  }

  /**
   * Forgets everything reported so far.
   *
   * <p>Called around a rolled-back rehearsal, and after a failed apply. This is the whole reason
   * the client can trust the list.
   */
  public static void clear() {
    Set<Ref> set = TOUCHED.get();
    if (set != null) {
      set.clear();
    }
  }

  /**
   * Forgets one record, because the write that reported it did not survive.
   *
   * <h2>The gap this closes</h2>
   *
   * The class contract above says only committed writes report here, and that is true of a tool
   * that <em>returned</em>. It is not true of one that did not: a service records the id it saved
   * while still inside its transaction — that is the only moment the id exists — so a commit that
   * then fails leaves a reference here to a row that never landed. The client would be told to
   * refresh a record that does not exist and the workflow would carry a phantom target.
   *
   * <p>Removed only once reconciliation has <em>proven</em> the write did not happen. An uncertain
   * outcome keeps its reference: the row may well be there, and dropping it would hide a change
   * from the one person able to check.
   */
  public static void forget(String model, Long id) {
    Set<Ref> set = TOUCHED.get();
    if (set == null || model == null || id == null) {
      return;
    }
    set.remove(new Ref(model, id));
  }

  /**
   * Records that a write changed this record. Safe to call repeatedly for the same record, and a
   * no-op outside a chat turn (the OCR intake pipeline has nobody to notify).
   *
   * @param model fully-qualified entity class name, e.g. {@code com.axelor.apps.account.db.Invoice}
   */
  public static void touched(String model, Long id) {
    Set<Ref> set = TOUCHED.get();
    if (set == null || model == null || id == null || set.size() >= MAX_RECORDS) {
      return;
    }
    set.add(new Ref(model, id));
  }

  /** What is currently recorded, oldest first. Never null. */
  public static List<Ref> snapshot() {
    Set<Ref> set = TOUCHED.get();
    return set == null ? new ArrayList<>() : new ArrayList<>(set);
  }
}
