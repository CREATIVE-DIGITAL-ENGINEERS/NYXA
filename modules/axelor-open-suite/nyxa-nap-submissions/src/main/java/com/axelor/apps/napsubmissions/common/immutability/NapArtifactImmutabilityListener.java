package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.db.Model;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * ДОКАЗАТЕЛСТВОТО НЕ СЕ ПРЕНАПИСВА — на ниво Hibernate, по старото състояние, по ВСЕКИ път.
 *
 * <p>Файлът (NapArtifact) и връзката му с подаването (NapSubmissionArtifact) доказват, че ТОЧНО
 * ТОЗИ файл е подаден: съхраненият файл, SHA-256, дължината, типът, институцията, отчетът,
 * дружеството (решения от 17.10 и 18.10.2026). След създаването никой не ги сменя — нито през
 * {@code /ws/rest} и UI, нито услуга, нито AI, нито admin. Променимо остава само името за показ.
 *
 * <p><b>⚠️ ЗАЩО ТУК, А НЕ В РЕПОЗИТОРИТО.</b> {@code Resource.save} (REST и UI) прави {@code
 * JPA.manage} → flush ПРЕДИ {@code repository.save}; пазач, който чете „записаното“ с JPQL, вижда
 * вече новото и мълчи (измерено 18.10.2026: admin смени SHA-256 и файла през REST). {@code
 * PreUpdateEvent.getOldState()} е състоянието, заредено ПРЕДИ промяната, независимо кой и как я
 * прави — образецът е G12 ({@code UserSelfEditListener} в nyxa-security). Регистрира се от {@link
 * NapSubmissionsIntegrator} през {@code META-INF/services}.
 *
 * <p><b>Какво НЕ минава оттук:</b> масов JPQL UPDATE. Така пишат стартовата стъпка и миграцията —
 * само {@code company IS NULL → стойност}, което е и единственото позволено. Масовото обновяване от
 * UI/REST ({@code mass-update}) пише по същия начин и затова се отказва в {@link
 * NapArtifactRequestGuard}. Създаването (INSERT) не е промяна — полетата, които услугата попълва
 * при създаване, се записват свободно.
 */
public class NapArtifactImmutabilityListener implements PreUpdateEventListener {

  private static final long serialVersionUID = 1L;

  /** Полетата на файла, които не се сменят след създаването. {@code name} — за показ, свободно. */
  static final List<String> ARTIFACT_FIXED =
      List.of(
          "metaFile",
          "sha256",
          "byteLength",
          "mimeType",
          "originalFileName",
          "artifactTypeSelect",
          "institutionSelect",
          "finalized",
          "saftReport",
          "company");

  /** Връзката подаване ↔ файл — кой файл, в кое подаване, в каква роля. */
  static final List<String> LINK_FIXED = List.of("submission", "artifact", "roleSelect");

  /**
   * Първото неизменно поле, което се различава от старото състояние; {@code null} — няма такова.
   */
  static String firstChanged(
      String[] names, Object[] oldState, Object[] state, Collection<String> fixed) {
    for (int i = 0; i < names.length; i++) {
      if (fixed.contains(names[i]) && !same(oldState[i], state[i])) {
        return names[i];
      }
    }
    return null;
  }

  /** Връзките се сравняват по id: друг екземпляр (или proxy) на същия ред не е промяна. */
  static boolean same(Object before, Object after) {
    if (before instanceof Model || after instanceof Model) {
      return Objects.equals(idOf(before), idOf(after));
    }
    return Objects.equals(before, after);
  }

  private static Long idOf(Object value) {
    return value == null ? null : ((Model) value).getId();
  }

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    Object entity = event.getEntity();
    Collection<String> fixed;
    String what;
    if (entity instanceof NapArtifact) {
      fixed = ARTIFACT_FIXED;
      what = "regulatory file (NapArtifact #" + ((Model) entity).getId() + ")";
    } else if (entity instanceof NapSubmissionArtifact) {
      fixed = LINK_FIXED;
      what = "submission-file link (NapSubmissionArtifact #" + ((Model) entity).getId() + ")";
    } else {
      return false;
    }
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new RegulatoryImmutabilityException(
          "Cannot verify an update of a " + what + ": no previous state was loaded");
    }
    String changed =
        firstChanged(
            event.getPersister().getPropertyNames(), event.getOldState(), event.getState(), fixed);
    if (changed != null) {
      throw new RegulatoryImmutabilityException(
          "Cannot change '" + changed + "' of a " + what + " once it has been persisted");
    }
    return false;
  }
}
