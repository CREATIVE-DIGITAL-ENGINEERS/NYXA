package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.db.Model;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

/**
 * ПРАВИЛАТА НА {@link BgFixedAssetGuard} НА НИВО HIBERNATE — по ВСЕКИ път: екран, {@code /ws/rest},
 * услуга, импорт, admin.
 *
 * <p><b>⚠️ ЗАЩО ТУК, А НЕ В РЕПОЗИТОРИТО.</b> {@code Resource.save} (REST и UI) прави flush ПРЕДИ
 * {@code repository.save}, а активи се пишат и покрай репозиторито (каскада, услуги, вносът).
 * Сравнението е по СТАРОТО състояние на реда. Образецът е {@code BgPartnerCompanyListener}.
 *
 * <p><b>Празният номер се записва като NULL</b> ({@link #blankSeqToNull}): уникалността (company,
 * fixed_asset_seq) от домейна не сравнява NULL-ите, а празните низове — да, тоест два актива без
 * номер в едно дружество иначе биха получили отказ от базата.
 *
 * <p><b>Какво НЕ минава оттук:</b> масов JPQL UPDATE — отказва го {@link BgFixedAssetRequestGuard};
 * и SQL извън Hibernate (файловете за пайплайна).
 */
public class BgFixedAssetListener
    implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

  private static final long serialVersionUID = 1L;

  @Override
  public boolean onPreInsert(PreInsertEvent event) {
    if (!(event.getEntity() instanceof FixedAsset)) {
      return false;
    }
    FixedAsset asset = (FixedAsset) event.getEntity();
    blankSeqToNull(event.getPersister().getPropertyNames(), event.getState(), asset);
    String refusal =
        BgFixedAssetGuard.insertRefusal(
            state(event.getPersister().getPropertyNames(), event.getState()),
            BgAccountingFramework.of(asset.getCompany()));
    if (refusal != null) {
      throw new BgFixedAssetIdentityException(refusal);
    }
    return false;
  }

  @Override
  public boolean onPreUpdate(PreUpdateEvent event) {
    if (!(event.getEntity() instanceof FixedAsset)) {
      return false;
    }
    FixedAsset asset = (FixedAsset) event.getEntity();
    if (event.getOldState() == null) {
      // Без старо състояние сравнение няма; пропуск би бил тиха врата — отказ.
      throw new BgFixedAssetIdentityException(
          "Промяна на актив #"
              + asset.getId()
              + " не може да се провери: старото състояние липсва.");
    }
    String[] names = event.getPersister().getPropertyNames();
    blankSeqToNull(names, event.getState(), asset);
    String refusal =
        BgFixedAssetGuard.updateRefusal(
            asset.getId(),
            state(names, event.getOldState()),
            state(names, event.getState()),
            BgAccountingFramework.of(asset.getCompany()));
    if (refusal != null) {
      throw new BgFixedAssetIdentityException(refusal);
    }
    return false;
  }

  @Override
  public boolean onPreDelete(PreDeleteEvent event) {
    if (!(event.getEntity() instanceof FixedAsset)) {
      return false;
    }
    FixedAsset asset = (FixedAsset) event.getEntity();
    String refusal =
        BgFixedAssetGuard.deleteRefusal(
            asset.getId(), state(event.getPersister().getPropertyNames(), event.getDeletedState()));
    if (refusal != null) {
      throw new BgFixedAssetIdentityException(refusal);
    }
    return false;
  }

  /** Празен (или само интервали) инвентарен номер → NULL в записваното състояние и в обекта. */
  static void blankSeqToNull(String[] names, Object[] values, FixedAsset asset) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(BgFixedAssetGuard.SEQ)) {
        if (values[i] instanceof String && ((String) values[i]).trim().isEmpty()) {
          values[i] = null;
          asset.setFixedAssetSeq(null);
        }
        return;
      }
    }
  }

  static BgFixedAssetGuard.State state(String[] names, Object[] values) {
    return new BgFixedAssetGuard.State(
        (Integer) value(names, values, BgFixedAssetGuard.STATUS),
        (String) value(names, values, BgFixedAssetGuard.SEQ),
        idOf(value(names, values, BgFixedAssetGuard.ACCOUNT)),
        idOf(value(names, values, BgFixedAssetGuard.CATEGORY)),
        idOf(value(names, values, BgFixedAssetGuard.SPLIT_FROM)),
        (String) value(names, values, BgFixedAssetGuard.KIND),
        (Integer) value(names, values, BgFixedAssetGuard.ORIGIN));
  }

  static Object value(String[] names, Object[] values, String name) {
    if (values == null) {
      return null;
    }
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return values[i];
      }
    }
    return null;
  }

  static Long idOf(Object value) {
    return value instanceof Model ? ((Model) value).getId() : null;
  }
}
