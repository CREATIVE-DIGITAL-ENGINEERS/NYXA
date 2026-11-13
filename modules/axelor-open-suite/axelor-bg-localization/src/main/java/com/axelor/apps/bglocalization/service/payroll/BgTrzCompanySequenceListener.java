package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.MergeEvent;
import org.hibernate.event.spi.MergeEventListener;
import org.hibernate.event.spi.PersistEvent;
import org.hibernate.event.spi.PersistEventListener;

/**
 * НОВОТО ДРУЖЕСТВО ПОЛУЧАВА ТРЗ РЕДИЦИТЕ СИ — на ниво Hibernate, по ВСЕКИ път, по който се ражда.
 *
 * <p><b>⚠️ ЗАЩО ТУК, А НЕ В {@code BgCompanyManagementRepository#save}.</b> Хранилището се вика
 * само от формата и REST ({@code Resource.save} → {@code repository.save}). Вносът на Axelor
 * (data-init, App Install, demo-data, BG_COA — {@code CSVImporter}/{@code XMLImporter}) пише през
 * {@code JPA.manage} → {@code em.persist}; кодът и фикстурите — през {@code JPA.save}/{@code
 * em.persist}; вложена нова фирма в чужд запис — през каскада при flush или през {@code merge}.
 * Нито един от тях не минава през хранилището, тоест закачка там остава фирма без редици и първият
 * ѝ фиш се отказва. Образецът е {@code NapSubmissionsIntegrator}: регистрира се от {@link
 * BgTrzIntegrator}.
 *
 * <p><b>КАК СЕ ПОЗНАВА „НОВО“.</b> Слушателят върви СЛЕД стандартния: дружеството вече е в
 * persistence context-а. Ново е, ако записът му още не е в базата ({@link
 * EntityEntry#isExistsInDatabase()} — вярно е за всяко заредено или вече флъшнато дружество).
 * Затова {@code persist} върху заредена фирма (Axelor го прави при всеки {@code JPA.save}) не ражда
 * нищо, а {@code merge} на нова фирма — ражда за копието.
 *
 * <p><b>⚠️ БЕЗ ЗАЯВКА.</b> Дали редиците вече са създадени, се гледа в persistence context-а, а не
 * с JPQL: заявка посред събитието би флъшнала полузаписан граф. Новото дружество няма как да има
 * редици в базата, а повторно извикване в същата сесия намира създадените в контекста.
 */
public class BgTrzCompanySequenceListener implements PersistEventListener, MergeEventListener {

  private static final long serialVersionUID = 1L;

  @Override
  public void onPersist(PersistEvent event) {
    created(event.getObject(), event.getSession());
  }

  @Override
  @SuppressWarnings("rawtypes")
  public void onPersist(PersistEvent event, Map createdAlready) {
    onPersist(event);
  }

  @Override
  public void onMerge(MergeEvent event) {
    created(event.getResult(), event.getSession());
  }

  @Override
  @SuppressWarnings("rawtypes")
  public void onMerge(MergeEvent event, Map copiedAlready) {
    onMerge(event);
  }

  static void created(Object entity, EventSource session) {
    if (!(entity instanceof Company) || session == null) {
      return;
    }
    Company company = (Company) entity;
    PersistenceContext context = session.getPersistenceContextInternal();
    EntityEntry entry = context.getEntry(company);
    if (entry == null || entry.isExistsInDatabase()) {
      return;
    }
    for (String code : BgTrzSequences.CODES) {
      // ⚠️ НАНОВО ПРЕД ВСЕКИ КОД, НЕ ВЕДНЪЖ ПРЕДИ ЦИКЪЛА. persist на редицата каскадира към фирмата
      // (Sequence.company е с cascade PERSIST), тоест този метод се вика ВЛОЖЕНО и създава
      // следващите кодове още вътре; списък, прочетен преди цикъла, щеше да ги създаде втори път
      // (измерено: фиш + две обезщетения).
      if (!codesInContext(context, company).contains(code)) {
        session.persist(BgTrzSequences.newFor(company, code));
      }
    }
  }

  private static Set<String> codesInContext(PersistenceContext context, Company company) {
    Set<String> codes = new HashSet<>();
    for (Map.Entry<Object, EntityEntry> e : context.reentrantSafeEntityEntries()) {
      Object other = e.getKey();
      if (other instanceof Sequence) {
        Sequence sequence = (Sequence) other;
        Company owner = sequence.getCompany();
        if (owner == company
            || (owner != null
                && owner.getId() != null
                && Objects.equals(owner.getId(), company.getId()))) {
          codes.add(sequence.getCodeSelect());
        }
      }
    }
    return codes;
  }
}
