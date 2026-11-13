package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.db.Model;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.proxy.HibernateProxy;

/**
 * ПОДАДЕНОТО ПОДАВАНЕ НЕ СЕ ПРЕНАПИСВА И НЕ СЕ ТРИЕ — на ниво Hibernate, по старото състояние, по
 * ВСЕКИ път: {@code /ws/rest} и UI, услуги, AI, admin.
 *
 * <p>Същият модел като {@link NapArtifactImmutabilityListener}, регистриран от {@link
 * NapSubmissionsIntegrator}. Правилата са в {@link NapSubmissionEvidence}; тук е само откъде идва
 * „преди“:
 *
 * <ul>
 *   <li>NapSubmission — {@code PreUpdate.getOldState()} и {@code PreDelete.getDeletedState()}:
 *       зареденото преди промяната.
 *   <li>NapSubmissionFile — статусът на подаването му по ЗАРЕДЕНОТО състояние в persistence
 *       context-а ({@link EntityEntry#getLoadedValue}), не по стойността в паметта. Иначе заявка,
 *       която в същия flush връща подаването в чернова, би отключила файла; а подписването, което
 *       прави подаването „подписано“ след последния файл, би заключило собствения си файл.
 * </ul>
 *
 * <p><b>⚠️ Защо не в репозиторито.</b> {@code Resource.save} (REST и UI) флъшва ПРЕДИ {@code
 * repository.save}, а {@code Resource.remove} трие с {@code em.remove} и каскада — репозиторито на
 * подаването няма {@code remove}, а каскадата към файловете не минава през тяхното. Масовото
 * обновяване (JPQL) не минава оттук — него го спира {@link NapSubmissionRequestGuard}.
 */
public class NapSubmissionImmutabilityListener
    implements PreUpdateEventListener, PreInsertEventListener, PreDeleteEventListener {

  private static final long serialVersionUID = 1L;

  private static final String STATUS = "submissionStatusSelect";

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    Object entity = event.getEntity();
    if (!(entity instanceof NapSubmission) && !(entity instanceof NapSubmissionFile)) {
      return false;
    }
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new RegulatoryImmutabilityException(
          "Cannot verify an update of "
              + entity.getClass().getSimpleName()
              + " #"
              + ((Model) entity).getId()
              + ": no previous state was loaded");
    }
    String[] names = event.getPersister().getPropertyNames();
    if (entity instanceof NapSubmission) {
      NapSubmissionEvidence.checkSubmissionUpdate(names, event.getOldState(), event.getState());
      return false;
    }
    Object before = NapSubmissionEvidence.value(names, event.getOldState(), "submission");
    Object after = NapSubmissionEvidence.value(names, event.getState(), "submission");
    boolean moved = !NapArtifactImmutabilityListener.same(before, after);
    NapSubmissionEvidence.checkFileUpdate(
        names,
        event.getOldState(),
        event.getState(),
        filed(event.getSession(), before),
        moved && filed(event.getSession(), after),
        selectionLocked(event.getSession(), before),
        moved && selectionLocked(event.getSession(), after));
    return false;
  }

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (event.getEntity() instanceof NapSubmissionFile) {
      Object submission = ((NapSubmissionFile) event.getEntity()).getSubmission();
      NapSubmissionEvidence.checkFileInsert(
          filed(event.getSession(), submission), selectionLocked(event.getSession(), submission));
    }
    return false;
  }

  @Override
  public boolean onPreDelete(PreDeleteEvent event) {
    Object entity = event.getEntity();
    if (entity instanceof NapSubmission) {
      String[] names = event.getPersister().getPropertyNames();
      Object[] state = event.getDeletedState();
      NapSubmissionEvidence.checkSubmissionDelete(
          state == null
              ? ((NapSubmission) entity).getSubmissionStatusSelect()
              : (String) NapSubmissionEvidence.value(names, state, STATUS));
    } else if (entity instanceof NapSubmissionFile) {
      Object submission = ((NapSubmissionFile) entity).getSubmission();
      NapSubmissionEvidence.checkFileDelete(
          filed(event.getSession(), submission), selectionLocked(event.getSession(), submission));
    }
    return false;
  }

  /** Подаването е доказателство (подадено/подписано) по ЗАРЕДЕНИЯ си статус. */
  static boolean filed(SharedSessionContractImplementor session, Object submission) {
    return NapSubmissionEvidence.isEvidence(loadedStatus(session, submission));
  }

  /** Подаването е заключено (изборът/съдържанието замразени) по ЗАРЕДЕНИЯ си статус. */
  static boolean selectionLocked(SharedSessionContractImplementor session, Object submission) {
    return NapSubmissionEvidence.isSelectionLocked(loadedStatus(session, submission));
  }

  /**
   * Статусът по ЗАРЕДЕНОТО състояние в persistence context-а, не по стойността в паметта — иначе
   * заявка, която в същия flush връща подаването в чернова, би отключила файла; а подписването,
   * което прави подаването „подписано“ след последния файл, би заключило собствения си файл. Proxy
   * се зарежда (иначе статус няма); обект извън context-а — по стойността си.
   */
  static String loadedStatus(SharedSessionContractImplementor session, Object submission) {
    if (submission == null) {
      return null;
    }
    Object entity =
        submission instanceof HibernateProxy
            ? ((HibernateProxy) submission).getHibernateLazyInitializer().getImplementation()
            : submission;
    EntityEntry entry = session.getPersistenceContextInternal().getEntry(entity);
    return entry != null && entry.getLoadedState() != null
        ? (String) entry.getLoadedValue(STATUS)
        : ((NapSubmission) entity).getSubmissionStatusSelect();
  }
}
