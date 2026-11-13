package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import com.axelor.auth.db.User;
import com.axelor.db.Model;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;

/**
 * „ТОЗИ ЗАПИС Е ВМЪКНАТ В ТЕКУЩАТА ТРАНЗАКЦИЯ“ — за репозиторитата, които правят нещо само при
 * създаване (дружество, потребител).
 *
 * <p><b>⚠️ ЗАЩО НЕ {@code getId() == null}.</b> {@code Resource.save} (REST и екранът) прави {@code
 * JPA.manage} → {@code persist} → flush ПРЕДИ {@code repository.save}: новият запис вече има id,
 * когато веригата на репозиторито тръгне. Проверката по id казва „заварен“ точно за пътя, по който
 * се създават дружества и потребители. Така {@code afterCreate} на дружеството никога не е тръгвал
 * от екрана.
 *
 * <p>Слушателят на Hibernate (PostInsert) отбелязва вмъкнатите Company и User в нишката. Белегът се
 * вдига от {@link #consume} или при края на транзакцията — запис, вмъкнат покрай репозиторито
 * ({@code JPA.save}), не остава „нов“ за следваща транзакция в същата нишка.
 */
public class BgJustInserted implements PostInsertEventListener {

  private static final long serialVersionUID = 1L;

  private static final ThreadLocal<Set<Object>> INSERTED =
      ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

  /**
   * {@code true}, ако {@code entity} е нов: без id (още не е вмъкнат) или вмъкнат в тази транзакция
   * и още не е обработен. Вдига белега — второ питане за същия запис дава {@code false}.
   */
  public static boolean consume(Model entity) {
    if (entity == null) {
      return false;
    }
    if (entity.getId() == null) {
      return true;
    }
    return INSERTED.get().remove(entity);
  }

  static void mark(Object entity) {
    INSERTED.get().add(entity);
  }

  static void clear(Object entity) {
    INSERTED.get().remove(entity);
  }

  static boolean tracked(Object entity) {
    return entity instanceof Company || entity instanceof User;
  }

  @Override
  public void onPostInsert(PostInsertEvent event) {
    Object entity = event.getEntity();
    if (!tracked(entity)) {
      return;
    }
    mark(entity);
    // Белегът живее само до края на транзакцията — успешна или не.
    event.getSession().getActionQueue().registerProcess((success, session) -> clear(entity));
  }

  @Override
  public boolean requiresPostCommitHanding(EntityPersister persister) {
    return false;
  }
}
