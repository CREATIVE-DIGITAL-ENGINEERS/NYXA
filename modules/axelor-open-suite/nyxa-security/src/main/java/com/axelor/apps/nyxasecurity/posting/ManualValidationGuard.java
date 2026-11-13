package com.axelor.apps.nyxasecurity.posting;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.apps.nyxasecurity.user.UserSelfEditListener;
import com.axelor.apps.nyxasecurity.user.UserSelfEditPolicy;
import com.axelor.db.Model;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.StatelessSession;
import org.hibernate.action.spi.AfterTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * Enforces {@link ManualValidationPolicy} on every write of a {@code Move}, whatever the path: the
 * move form, {@code /ws/action}, a direct {@code /ws/rest} save, mass validation.
 *
 * <p>Like {@code UserSelfEditListener}: a request with no logged-in user (login, startup, Quartz
 * jobs) and administrators are not restricted. The actor's group and permissions are read through a
 * separate stateless session, because querying the session being flushed is not safe.
 *
 * <p>The source document of a module's entry is read from the move's own state ({@code invoice},
 * {@code stockMove}, …), so nothing is queried for it; only the actor's permissions are.
 *
 * <p>"Created in this flow" is known from the inserts themselves: the ids of the moves inserted in
 * the current transaction are kept on the thread and dropped when the transaction completes. A move
 * that is not among them was in the database before this request.
 */
public class ManualValidationGuard implements PreUpdateEventListener, PreInsertEventListener {

  private static final long serialVersionUID = 1L;

  private static final String STATUS = "statusSelect";
  private static final String ORIGIN = "technicalOriginSelect";
  private static final String JOURNAL = "journal";
  private static final String COMPANY = "company";

  /** Ids of the moves inserted in the transaction running on this thread. */
  private static final ThreadLocal<Set<Serializable>> INSERTED = new ThreadLocal<>();

  /** Where Axelor looks for a user's permissions ({@code AuthResolver.resolve}). */
  private static final List<String> PERMISSION_SOURCES =
      List.of(
          "FROM User u JOIN u.permissions p",
          "FROM User u JOIN u.roles r JOIN r.permissions p",
          "FROM User u JOIN u.group g JOIN g.permissions p",
          "FROM User u JOIN u.group g JOIN g.roles r JOIN r.permissions p");

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (event.getEntity() instanceof Move && event.getOldState() != null) {
      String[] names = event.getPersister().getPropertyNames();
      Set<Serializable> inserted = INSERTED.get();
      check(
          event.getSession(),
          (Integer) value(names, event.getOldState(), STATUS),
          (Integer) value(names, event.getState(), STATUS),
          (Integer) value(names, event.getState(), ORIGIN),
          inserted != null && inserted.contains(event.getId()),
          ManualValidationPolicy.sourceModel(p -> value(names, event.getState(), p)),
          id(value(names, event.getState(), JOURNAL)),
          id(value(names, event.getState(), COMPANY)));
    }
    return false;
  }

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (event.getEntity() instanceof Move) {
      remember(event.getSession(), event.getId());
      String[] names = event.getPersister().getPropertyNames();
      check(
          event.getSession(),
          null,
          (Integer) value(names, event.getState(), STATUS),
          (Integer) value(names, event.getState(), ORIGIN),
          true,
          ManualValidationPolicy.sourceModel(p -> value(names, event.getState(), p)),
          id(value(names, event.getState(), JOURNAL)),
          id(value(names, event.getState(), COMPANY)));
    }
    return false;
  }

  /** Keeps the id until the transaction completes, committed or rolled back. */
  private static void remember(SessionImplementor session, Serializable id) {
    Set<Serializable> inserted = INSERTED.get();
    if (inserted == null) {
      inserted = new HashSet<>();
      INSERTED.set(inserted);
      session
          .getActionQueue()
          .registerProcess((AfterTransactionCompletionProcess) (success, s) -> INSERTED.remove());
    }
    inserted.add(id);
  }

  private static void check(
      SessionImplementor session,
      Integer oldStatus,
      Integer newStatus,
      Integer origin,
      boolean createdInThisTransaction,
      String sourceModel,
      Long journalId,
      Long companyId) {
    String model =
        ManualValidationPolicy.requiredModel(
            oldStatus, newStatus, origin, createdInThisTransaction, sourceModel);
    if (model == null) {
      return;
    }
    String actorCode = UserSelfEditListener.actorCode();
    if (actorCode == null) {
      return;
    }
    if (ManualValidationPolicy.MOVE.equals(model)) {
      // Only now, so the journal is read only for a person's validation that would need Move.
      String payroll =
          ManualValidationPolicy.payrollSource(
              origin,
              createdInThisTransaction,
              sourceModel,
              journalId,
              payrollJournalId(session, companyId));
      model =
          ManualValidationPolicy.requiredModel(
              oldStatus, newStatus, origin, createdInThisTransaction, payroll);
      sourceModel = payroll;
    }
    if (ManualValidationPolicy.AUTO_POSTING_USER.equals(actorCode)) {
      if (!ManualValidationPolicy.autoPostingMay(
          origin,
          createdInThisTransaction,
          sourceModel,
          journalId,
          stockIssueJournalId(session, companyId))) {
        throw new Refusals.Refusal(Refusals.AUTO_POSTING_ONLY_STOCK_ISSUES);
      }
      return;
    }
    String groupCode;
    long writePermissions = 0;
    try (StatelessSession reader = session.getFactory().openStatelessSession()) {
      groupCode =
          (String)
              reader
                  .createQuery("SELECT g.code FROM User u LEFT JOIN u.group g WHERE u.code = :c")
                  .setParameter("c", actorCode)
                  .uniqueResult();
      for (String source : PERMISSION_SOURCES) {
        writePermissions +=
            (Long)
                reader
                    .createQuery(
                        "SELECT COUNT(p) "
                            + source
                            + " WHERE u.code = :c AND p.canWrite = true AND p.object IN (:o)")
                    .setParameter("c", actorCode)
                    .setParameterList("o", ManualValidationPolicy.objectsFor(model))
                    .uniqueResult();
      }
    }
    boolean admin = UserSelfEditPolicy.isAdmin(actorCode, groupCode);
    if (!ManualValidationPolicy.mayValidate(admin, writePermissions)) {
      throw ManualValidationPolicy.MOVE.equals(model)
          ? new Refusals.Refusal(Refusals.NO_WRITE_ON_MOVES)
          : new Refusals.Refusal(Refusals.NO_WRITE_ON_SOURCE, new Refusals.Term(title(model)));
    }
  }

  /** "com.axelor.apps.account.db.PaymentVoucher" → "Payment voucher", the model's title key. */
  static String title(String model) {
    String simple = model.substring(model.lastIndexOf('.') + 1);
    StringBuilder title = new StringBuilder();
    for (int i = 0; i < simple.length(); i++) {
      char c = simple.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) {
        title.append(' ').append(Character.toLowerCase(c));
      } else {
        title.append(c);
      }
    }
    return title.toString();
  }

  /**
   * The company's stock issue journal ({@code AccountConfig.bgStockIssueJournal}, a bg-localization
   * field — read by name, so nyxa-security needs no dependency on it), or null.
   */
  private static Long stockIssueJournalId(SessionImplementor session, Long companyId) {
    if (companyId == null) {
      return null;
    }
    try (StatelessSession reader = session.getFactory().openStatelessSession()) {
      return (Long)
          reader
              .createQuery(
                  "SELECT c.bgStockIssueJournal.id FROM AccountConfig c WHERE c.company.id = :c")
              .setParameter("c", companyId)
              .uniqueResult();
    } catch (RuntimeException e) {
      // No such field (bg-localization absent) or no config: nothing to allow.
      return null;
    }
  }

  /**
   * The company's payroll journal ({@code AccountConfig.bgPayrollJournal}, a bg-localization field
   * — read by name, as {@link #stockIssueJournalId}), or null.
   */
  private static Long payrollJournalId(SessionImplementor session, Long companyId) {
    if (companyId == null) {
      return null;
    }
    try (StatelessSession reader = session.getFactory().openStatelessSession()) {
      return (Long)
          reader
              .createQuery(
                  "SELECT c.bgPayrollJournal.id FROM AccountConfig c WHERE c.company.id = :c")
              .setParameter("c", companyId)
              .uniqueResult();
    } catch (RuntimeException e) {
      // No such field (bg-localization absent) or no config: nothing to recognise.
      return null;
    }
  }

  private static Long id(Object entity) {
    return entity instanceof Model ? ((Model) entity).getId() : null;
  }

  /** A property's value in the entity state, or null when the entity has no such property. */
  private static Object value(String[] names, Object[] state, String property) {
    for (int i = 0; i < names.length; i++) {
      if (property.equals(names[i])) {
        return state[i];
      }
    }
    return null;
  }
}
