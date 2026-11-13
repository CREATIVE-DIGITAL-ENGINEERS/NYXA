package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgDeclarant;
import java.util.List;

/**
 * „НЕ ПОВЕЧЕ ОТ ЕДИН декларатор по подразбиране на дружество“ — половината за ПРЕКИЯ запис: вдигне
 * ли се {@link BgDeclarant} с {@code isDefault = true}, другите на същото дружество се свалят
 * мълчаливо, без грешка към човека.
 *
 * <p><b>⚠️ И ТОВА НЕ Е „ВСЕКИ ПЪТ НА ЗАПИСА“, КАКТО ТВЪРДЕШЕ ТОЗИ КОМЕНТАР.</b> Декларантите висят
 * на дружеството като {@code one-to-many … mappedBy="company"} с {@code CascadeType.ALL}. При запис
 * на формата Дружество {@code Resource.save} вика репозиторията САМО на корена, а децата ги записва
 * Hibernate по cascade — тоест този {@code save} НЕ се вика и инвариантът не се налагаше именно
 * там, където решетката кани човека да вдигне флага. Другата половина е {@code
 * BgCompanyManagementRepository}.
 *
 * <p><b>⚠️ ЗАЩО ТУК СЕ СВАЛЯ, А ОТТАТЪК СЕ ОТКАЗВА.</b> Защото тук записваният е ЕДИН и е
 * недвусмислено онзи, когото човекът току-що е вдигнал; оттатък пристига цялата колекция и кой от
 * вдигнатите е щракнатият, подадените данни не казват. Разграничението е изписано в {@code
 * BgDeclarantResolver}.
 *
 * <p>Вързан през {@code BgLocalizationModule}.
 */
public class BgDeclarantManagementRepository extends BgDeclarantRepository {

  @Override
  public BgDeclarant save(BgDeclarant entity) {
    if (entity != null
        && Boolean.TRUE.equals(entity.getIsDefault())
        && entity.getCompany() != null) {
      for (BgDeclarant other : otherDefaults(entity)) {
        other.setIsDefault(false);
      }
    }
    return saveRecord(entity);
  }

  /** Заварените вдигнати на същото дружество — свой шев, за да е проверим пазачът без JPA. */
  protected List<BgDeclarant> otherDefaults(BgDeclarant entity) {
    long currentId = entity.getId() == null ? 0L : entity.getId();
    return all()
        .filter("self.company = :company AND self.isDefault = true AND self.id != :id")
        .bind("company", entity.getCompany())
        .bind("id", currentId)
        .fetch();
  }

  /** Самият запис — вторият шев на същия тест. */
  protected BgDeclarant saveRecord(BgDeclarant entity) {
    return super.save(entity);
  }
}
