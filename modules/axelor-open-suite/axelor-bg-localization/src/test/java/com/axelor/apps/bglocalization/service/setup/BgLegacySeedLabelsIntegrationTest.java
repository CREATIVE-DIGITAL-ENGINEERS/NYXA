package com.axelor.apps.bglocalization.service.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.bankpayment.db.BankStatementQuery;
import com.axelor.apps.bglocalization.listener.BgLocalizationServerStartListener;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Заварените английски текстове от началните данни срещу истинска база (HSQLDB) — виж {@link
 * BgLegacySeedLabels}: сменя се точно старият текст, редактираният остава, вторият старт не прави
 * нищо, а listener-ът я вика.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgLegacySeedLabelsIntegrationTest extends JpaSupport {

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static Journal journal(String code, String description, String tag) {
    return tx(
        () -> {
          JournalType type = new JournalType();
          type.setName("Вид " + tag);
          type.setCode("T" + tag);
          type.setTechnicalTypeSelect(1);
          type = JPA.save(type);
          Journal journal = new Journal();
          journal.setCode(code);
          journal.setName("Дневник " + tag);
          journal.setJournalType(type);
          journal.setDescriptionModel(description);
          return JPA.save(journal);
        });
  }

  private static <T extends Model> T reload(Class<T> type, Long id) {
    return tx(() -> JPA.find(type, id));
  }

  @Test
  @DisplayName("заварените записи получават българския текст; редактираният остава")
  void заварените_получават_българския_текст() {
    Journal sales = journal("SAL", "Cust. Inv.", "LS1");
    Journal edited = journal("PCH", "Покупка от доставчик", "LS2");
    PaymentCondition net30 =
        tx(
            () -> {
              PaymentCondition c =
                  JPA.all(PaymentCondition.class).filter("self.code = 'NET30'").fetchOne();
              if (c == null) {
                c = new PaymentCondition();
                c.setCode("NET30");
              }
              c.setName("30 days net");
              return JPA.save(c);
            });
    BankStatementQuery rule =
        tx(
            () -> {
              BankStatementQuery q = new BankStatementQuery();
              q.setName("Origin and amount equal");
              q.setQuery("true");
              q.setRuleTypeSelect(1);
              return JPA.save(q);
            });

    Beans.get(BgLocalizationServerStartListener.class).ensureLegacySeedLabels();

    assertEquals("Фактура за продажба", reload(Journal.class, sales.getId()).getDescriptionModel());
    assertEquals(
        "Покупка от доставчик", reload(Journal.class, edited.getId()).getDescriptionModel());
    assertEquals(
        "30 дни от датата на фактурата", reload(PaymentCondition.class, net30.getId()).getName());
    assertEquals(
        "Еднакви произход и сума", reload(BankStatementQuery.class, rule.getId()).getName());

    String second = Beans.get(BgLegacySeedLabels.class).run();
    assertEquals(
        "Legacy seed labels: journals 0, payment conditions 0, accounts 0, bank statement rules 0.",
        second,
        "вторият старт не намира нищо");
  }
}
