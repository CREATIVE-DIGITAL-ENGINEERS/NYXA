package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.db.Model;
import java.util.Objects;
import javax.persistence.PersistenceException;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * ДРУЖЕСТВОТО НА НОМЕРА НЕ СЕ СМЕНЯ — на ниво Hibernate, по старото състояние, по ВСЕКИ път.
 *
 * <p>{@code BgPaySlip.company} и {@code BgCompensation.company} съществуват САМО за уникалността
 * (дружество, номер): записват се веднъж при номерирането и после не се пипат. Хранилищата отказват
 * стойност, различна от платеца, но не виждат старото състояние — {@code Resource.save} (REST и UI)
 * флъшва ПРЕДИ {@code repository.save}. Заявка, която сменя наведнъж и договора, и {@code company}
 * към друго дружество, щеше да мине там (двете съвпадат). Тук не минава: {@link
 * PreUpdateEvent#getOldState()} е заредената стойност, независимо кой и как пише. Образецът е
 * {@code NapArtifactImmutabilityListener}; регистрира се от {@link BgTrzIntegrator}.
 *
 * <p>Позволено е само празно → стойност: заварен документ отпреди миграцията за номерата по
 * дружество получава дружеството си при първия запис.
 */
public class BgTrzNumberingCompanyGuard implements PreUpdateEventListener {

  private static final long serialVersionUID = 1L;

  static final String COMPANY = "company";

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    Object entity = event.getEntity();
    String code;
    String reference;
    if (entity instanceof BgPaySlip) {
      code = BgTrzSequences.PAY_SLIP;
      reference = ((BgPaySlip) entity).getReference();
    } else if (entity instanceof BgCompensation) {
      code = BgTrzSequences.COMPENSATION;
      reference = ((BgCompensation) entity).getReference();
    } else {
      return false;
    }
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new PersistenceException(
          "Дружеството на " + reference + " не може да се провери: няма заредено старо състояние.");
    }
    String[] names = event.getPersister().getPropertyNames();
    for (int i = 0; i < names.length; i++) {
      if (COMPANY.equals(names[i])) {
        String refusal = refusal(code, reference, event.getOldState()[i], event.getState()[i]);
        if (refusal != null) {
          throw new PersistenceException(refusal);
        }
      }
    }
    return false;
  }

  /** Причина за отказ, или {@code null}: старото е празно, или е същото дружество. */
  static String refusal(String code, String reference, Object before, Object after) {
    if (before == null || Objects.equals(idOf(before), idOf(after))) {
      return null;
    }
    String label = reference == null || reference.trim().isEmpty() ? "(нов)" : reference;
    if (after == null) {
      return BgTrzSequences.refuseCompanyCleared(code, label, (Company) before);
    }
    return BgTrzSequences.refuseCompanyChange(code, label, (Company) before, (Company) after);
  }

  private static Long idOf(Object value) {
    return value == null ? null : ((Model) value).getId();
  }
}
