package com.axelor.apps.bglocalization.service.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.csv.script.ImportPartner;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppAccount;
import com.axelor.studio.db.AppBase;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * НОМЕРЪТ НА ПАРТНЬОРА Е ПО ДРУЖЕСТВО — през истинската верига на репозиторито, истинския {@code
 * SequenceService} (подменен от модула) и слушателя на Hibernate.
 *
 * <p>Без потребител: дружеството идва от изричния контекст ({@link BgExplicitCompany}) или от
 * системния контекст на AI — точно пътищата на вноса и на AI.
 *
 * <p>⚠️ HSQLDB не чете Partner (PostgreSQL {@code @Formula}), затова: проверката за вече даден
 * номер е изключена в AppBase, промяната на дружеството се прави върху managed партньор в същата
 * транзакция, а заварените редове се пишат и четат с native SQL.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgPartnerSequencePerCompanyIntegrationTest extends JpaSupport {

  /** Работата с {@code company} като изрично дружество — както вносът и задачите. */
  private static <T> T in(Company company, Callable<T> work) {
    try {
      return BgExplicitCompany.call(company, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  // ── Фикстури ──────────────────────────────────────────────────────────────────────────────────

  /** Base и Account с AppBase, който иска номер на всеки партньор — като на живо. */
  private static void ensureApps() {
    if (JPA.all(AppBase.class).fetchOne() != null) {
      return;
    }
    App base = new App();
    base.setCode("base");
    base.setName("Base");
    base.setActive(true);
    JPA.save(base);
    AppBase appBase = new AppBase();
    appBase.setApp(base);
    appBase.setGeneratePartnerSequence(true);
    // Проверката за вече даден номер е SELECT на Partner — HSQLDB не го чете (формулата). Правилото
    // й (по company, не по companySet) се проверява в BgPartnerSequenceRulesTest.
    appBase.setCheckExistingSequenceOnGeneration(false);
    JPA.save(appBase);
    App account = new App();
    account.setCode("account");
    account.setName("Account");
    account.setActive(true);
    JPA.save(account);
    AppAccount appAccount = new AppAccount();
    appAccount.setApp(account);
    JPA.save(appAccount);
  }

  private static Currency currency(String tag) {
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("P" + tag);
    return JPA.save(currency);
  }

  /** Ново дружество през хранилището — както от екрана: поредицата се създава при създаването. */
  private static Company newCompany(String tag) {
    ensureApps();
    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode(tag);
    company.setCurrency(currency(tag));
    return Beans.get(CompanyRepository.class).save(company);
  }

  /** Заварено дружество — записано покрай хранилището, без поредица за партньори. */
  private static Company legacyCompany(String tag) {
    ensureApps();
    Company company = new Company();
    company.setName("Заварено " + tag);
    company.setCode(tag);
    company.setCurrency(currency(tag));
    return JPA.save(company);
  }

  private static Partner partner(String name) {
    Partner partner = new Partner();
    partner.setName(name);
    partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
    return partner;
  }

  private static Partner saveIn(Company company, String name) {
    return in(company, () -> Beans.get(PartnerRepository.class).save(partner(name)));
  }

  private static Sequence sequenceOf(Company company) {
    return Beans.get(SequenceRepository.class).find(SequenceRepository.PARTNER, company);
  }

  private static long nextNum(Company company) {
    Sequence sequence = sequenceOf(company);
    assertNotNull(sequence, "поредица partner на " + company.getCode());
    List<SequenceVersion> versions = sequence.getSequenceVersionList();
    assertEquals(1, versions.size(), "една отворена версия");
    return versions.get(0).getNextNum();
  }

  private static String code(Sequence sequence, long number) {
    return StringUtils.defaultString(sequence.getPrefixe())
        + StringUtils.leftPad(Long.toString(number), sequence.getPadding(), '0')
        + StringUtils.defaultString(sequence.getSuffixe());
  }

  private static String messages(Throwable e) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      out.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return out.toString();
  }

  private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  private static String tag() {
    return "T" + Long.toString(System.nanoTime(), 36).toUpperCase();
  }

  // ── Свидетели ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("две дружества: всяко брои от своята поредица и един и същ номер е позволен")
  void всяко_дружество_брои_само() {
    tx(
        () -> {
          Company a = newCompany(tag());
          Company b = newCompany(tag());
          long startA = nextNum(a);
          long startB = nextNum(b);
          // И двете продължават над едно и също (заварените и общият брояч) — тоест първият номер
          // на А и първият на Б са един и същ низ.
          assertEquals(startA, startB, "същото начало за двете нови дружества");

          Partner a1 = saveIn(a, "А1");
          Partner a2 = saveIn(a, "А2");
          Partner b1 = saveIn(b, "Б1");
          JPA.flush(); // уникалността (company, partnerSeq) се проверява тук

          assertEquals(a.getId(), a1.getCompany().getId(), "А1 е на А");
          assertEquals(b.getId(), b1.getCompany().getId(), "Б1 е на Б");
          assertEquals(code(sequenceOf(a), startA), a1.getPartnerSeq(), "А1 — първият на А");
          assertEquals(code(sequenceOf(a), startA + 1), a2.getPartnerSeq(), "А2 — вторият на А");
          assertEquals(code(sequenceOf(b), startB), b1.getPartnerSeq(), "Б1 — първият на Б");
          assertEquals(a1.getPartnerSeq(), b1.getPartnerSeq(), "един и същ номер в А и Б");
          assertEquals(startA + 2, nextNum(a), "броячът на А е минал с 2");
          assertEquals(startB + 1, nextNum(b), "броячът на Б е минал с 1 — не с 3");
          return null;
        });
  }

  @Test
  @DisplayName("дружество без поредица → ясен отказ, без резервна поредица")
  void липсваща_поредица() {
    Company c = tx(() -> legacyCompany(tag()));
    assertNull(tx(() -> sequenceOf(c)), "заварено дружество, поредица още няма");
    Exception e = assertThrows(Exception.class, () -> tx(() -> saveIn(c, "В1")));
    assertTrue(
        messages(e).contains("няма поредица за номерата на партньорите"),
        "отказът казва какво липсва: " + messages(e));
  }

  @Test
  @DisplayName("без потребител и без изрично дружество → отказ (и покрай репозиторито)")
  void без_дружество() {
    tx(
        () -> {
          ensureApps();
          return null;
        });
    Exception viaRepo =
        assertThrows(
            Exception.class,
            () -> tx(() -> Beans.get(PartnerRepository.class).save(partner("Без дружество"))));
    assertTrue(messages(viaRepo).contains("Партньорът няма дружество"), messages(viaRepo));

    Exception direct =
        assertThrows(
            Exception.class,
            () ->
                tx(
                    () -> {
                      Partner p = partner("Направо");
                      p.setPartnerSeq("X-" + tag());
                      JPA.save(p);
                      JPA.flush();
                      return p;
                    }));
    assertTrue(hasCause(direct, BgPartnerCompanyException.class), messages(direct));
  }

  @Test
  @DisplayName("дружеството на партньора не се сменя след създаването")
  void смяна_на_дружеството() {
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                tx(
                    () -> {
                      Company a = newCompany(tag());
                      Company b = newCompany(tag());
                      Partner p = saveIn(a, "Местен");
                      JPA.flush();
                      p.setCompany(b);
                      JPA.flush();
                      return p;
                    }));
    assertTrue(hasCause(e, BgPartnerCompanyException.class), messages(e));
    assertTrue(messages(e).contains("не се сменя"), messages(e));
  }

  @Test
  @DisplayName("внос (ImportPartner) с изрично дружество — номерът и дружеството са на него")
  void внос_с_изрично_дружество() {
    tx(
        () -> {
          Company a = newCompany(tag());
          Company b = newCompany(tag());
          long startB = nextNum(b);
          Partner imported = partner("Внесен");
          in(
              b,
              () -> {
                Beans.get(ImportPartner.class).importPartner(imported, new HashMap<>());
                return JPA.save(imported);
              });
          JPA.flush();
          assertEquals(b.getId(), imported.getCompany().getId(), "внесеният е на Б");
          assertEquals(code(sequenceOf(b), startB), imported.getPartnerSeq(), "номер от Б");
          assertEquals(startB + 1, nextNum(b));
          assertEquals(startB, nextNum(a), "поредицата на А не е пипната");
          return null;
        });
  }

  @Test
  @DisplayName("AI в системен контекст (без потребител) — дружеството на контекста")
  void ai_с_изрично_дружество() {
    tx(
        () -> {
          Company a = newCompany(tag());
          Company b = newCompany(tag());
          long startB = nextNum(b);
          Partner created =
              AiExecutionContext.callAsSystem(
                  b.getId(),
                  "partner-sequence-test",
                  () -> Beans.get(PartnerRepository.class).save(partner("От AI")));
          JPA.flush();
          assertEquals(b.getId(), created.getCompany().getId(), "партньорът от AI е на Б");
          assertEquals(code(sequenceOf(b), startB), created.getPartnerSeq());
          assertEquals(startB, nextNum(a), "поредицата на А не е пипната");
          return null;
        });
  }

  @Test
  @DisplayName("миграцията: поредица за заварено дружество, над всеки заварен номер; идемпотентна")
  void миграция_на_заварените() {
    String tag = tag();
    long base = 9_900_000L + (System.nanoTime() % 90_000L) * 10;
    Company d =
        tx(
            () -> {
              Company company = legacyCompany(tag);
              // Заварени партньори без дружество — както ги е оставил общият брояч. Native SQL: те
              // предхождат правилото и слушателят не ги вижда.
              insertLegacy(base + 1, "Заварен 1 " + tag, "P0040");
              insertLegacy(base + 2, "Заварен 2 " + tag, "P0052");
              insertLegacy(base + 3, "Внесен отвън " + tag, "X-7777");
              // Общата поредица: следващ 45. P0052 над нея е внесен с номер (не от брояча) —
              // новата поредица трябва да продължи над него, не над брояча.
              Sequence common = new Sequence();
              common.setName("partner " + tag);
              common.setCodeSelect(SequenceRepository.PARTNER);
              common.setPrefixe("P");
              common.setPadding(4);
              common.setToBeAdded(1);
              SequenceVersion version = new SequenceVersion();
              version.setStartDate(LocalDate.of(2000, 1, 1));
              version.setNextNum(45L);
              common.addSequenceVersionListItem(version);
              JPA.save(common);
              return company;
            });

    BgPartnerSequenceSetupService.Result first =
        tx(() -> Beans.get(BgPartnerSequenceSetupService.class).ensureAll());
    assertTrue(first.created.contains(d.getCode()), "създадена за заварено: " + first.message());
    long start = tx(() -> nextNum(d));
    assertEquals(53L, start, "над P0052 (заварен без дружество), не над брояча (45)");

    BgPartnerSequenceSetupService.Result second =
        tx(() -> Beans.get(BgPartnerSequenceSetupService.class).ensureAll());
    assertFalse(
        second.created.contains(d.getCode()), "второ пускане не създава: " + second.message());
    assertEquals(start, (long) tx(() -> nextNum(d)), "второ пускане не мести брояча");

    tx(
        () -> {
          Partner p = saveIn(d, "Нов в заварено");
          JPA.flush();
          assertEquals(code(sequenceOf(d), start), p.getPartnerSeq(), "продължава оттам");
          return null;
        });

    // Заварените не са пипнати: номерата и празното дружество са същите.
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        tx(
            () ->
                JPA.em()
                    .createNativeQuery(
                        "SELECT partner_seq, company FROM base_partner WHERE id IN (?1, ?2, ?3)"
                            + " ORDER BY id")
                    .setParameter(1, base + 1)
                    .setParameter(2, base + 2)
                    .setParameter(3, base + 3)
                    .getResultList());
    assertEquals(3, rows.size());
    assertEquals("P0040", rows.get(0)[0]);
    assertEquals("P0052", rows.get(1)[0]);
    assertEquals("X-7777", rows.get(2)[0]);
    for (Object[] row : rows) {
      assertNull(row[1], "заварен без дружество остава без дружество");
    }
  }

  private static void insertLegacy(long id, String name, String code) {
    JPA.em()
        .createNativeQuery(
            "INSERT INTO base_partner (id, version, name, full_name, partner_seq)"
                + " VALUES (?1, 0, ?2, ?2, ?3)")
        .setParameter(1, id)
        .setParameter(2, name)
        .setParameter(3, code)
        .executeUpdate();
  }
}
