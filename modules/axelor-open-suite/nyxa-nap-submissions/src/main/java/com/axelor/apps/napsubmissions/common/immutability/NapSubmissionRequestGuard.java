package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.Query;
import com.axelor.event.Observes;
import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.events.qualifiers.EntityType;
import com.axelor.rpc.Criteria;
import com.axelor.rpc.Request;
import com.axelor.rpc.filter.JPQLFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Named;

/**
 * МАСОВОТО ОБНОВЯВАНЕ И МАСОВОТО ТРИЕНЕ НЕ ПИПАТ ПОДАДЕНОТО — още в заявката, за всички, и за
 * admin.
 *
 * <p>{@code Resource.updateMass} пише с JPQL {@code UPDATE} без да зарежда редовете — без {@code
 * PreUpdate}, тоест {@link NapSubmissionImmutabilityListener} не го вижда. Затова тук, в {@link
 * PreRequest}: ако филтърът на заявката хваща поне едно подаване, което е доказателство ({@link
 * NapSubmissionEvidence#isEvidence}), или файл от такова — отказ. Статусът на подаването и
 * подаването на файла не се обновяват масово изобщо: те местят запис в/от доказателство.
 *
 * <p>Триенето ({@code DELETE /ws/rest/<model>/<id>} и {@code POST …/removeAll}) минава през {@code
 * em.remove} и слушателят го хваща и без това; тук се отказва преди да е пипнат какъвто и да е ред,
 * с ясна причина. Черновите се обновяват и трият както досега.
 */
public class NapSubmissionRequestGuard {

  /** Подаване, което е доказателство (обратното на draft/signing; виж NapSubmissionEvidence). */
  static final String FILED =
      "self.submissionStatusSelect IS NOT NULL"
          + " AND self.submissionStatusSelect NOT IN ('draft', 'signing')";

  /** Файл на такова подаване. */
  static final String FILE_OF_FILED =
      "self.submission.submissionStatusSelect IS NOT NULL"
          + " AND self.submission.submissionStatusSelect NOT IN ('draft', 'signing')";

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(NapSubmission.class)
          PreRequest event) {
    refuseField(event.getRequest(), "submissionStatusSelect", "the status of НАП submissions");
    if (targetsFiled(event.getRequest(), NapSubmission.class, FILED)) {
      throw new RegulatoryImmutabilityException(
          "Cannot mass-update submitted НАП submissions — a filed submission is evidence");
    }
  }

  public void onFileMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(NapSubmissionFile.class)
          PreRequest event) {
    refuseField(event.getRequest(), "submission", "the submission of НАП submission files");
    if (targetsFiled(event.getRequest(), NapSubmissionFile.class, FILE_OF_FILED)) {
      throw new RegulatoryImmutabilityException(
          "Cannot mass-update files of a submitted НАП submission — they are part of the evidence");
    }
  }

  public void onRemove(
      @Observes @Named(RequestEvent.REMOVE) @EntityType(NapSubmission.class) PreRequest event) {
    if (countFiled(NapSubmission.class, FILED, ids(event.getRequest())) > 0) {
      throw new RegulatoryImmutabilityException(
          "Cannot delete a submitted НАП submission — a filed submission is evidence");
    }
  }

  public void onFileRemove(
      @Observes @Named(RequestEvent.REMOVE) @EntityType(NapSubmissionFile.class) PreRequest event) {
    if (countFiled(NapSubmissionFile.class, FILE_OF_FILED, ids(event.getRequest())) > 0) {
      throw new RegulatoryImmutabilityException(
          "Cannot delete a file of a submitted НАП submission — it is part of the evidence");
    }
  }

  static void refuseField(Request request, String field, String what) {
    for (String key : NapArtifactRequestGuard.massUpdateValues(request).keySet()) {
      if (field.equals(NapArtifactRequestGuard.fieldOf(key))) {
        throw new RegulatoryImmutabilityException("Cannot mass-update " + what);
      }
    }
  }

  /** Записите, които масовото обновяване ще хване — същият филтър като в {@code Resource}. */
  private static boolean targetsFiled(Request request, Class<? extends Model> model, String filed) {
    JPQLFilter filter = new JPQLFilter(filed);
    Criteria criteria = request == null ? null : request.getCriteria();
    Query<? extends Model> query =
        criteria == null ? filter.build(model) : criteria.createQuery(model, filter);
    return query.count() > 0;
  }

  private static List<Long> ids(Request request) {
    List<Long> ids = new ArrayList<>();
    for (Map<String, Object> record : NapArtifactRequestGuard.records(request)) {
      Long id = NapArtifactRequestGuard.idOf(record);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static long countFiled(Class<? extends Model> model, String filed, List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    return JPA.all(model).filter("self.id IN (:ids) AND " + filed).bind("ids", ids).count();
  }
}
