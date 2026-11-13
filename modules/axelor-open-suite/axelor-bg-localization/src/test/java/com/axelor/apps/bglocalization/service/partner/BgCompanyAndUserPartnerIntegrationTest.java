package com.axelor.apps.bglocalization.service.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.rpc.Response;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppAccount;
import com.axelor.studio.db.AppBase;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПАРТНЬОРЪТ НА ДРУЖЕСТВОТО Е В САМОТО ДРУЖЕСТВО, А ПАРТНЬОРЪТ НА ПОТРЕБИТЕЛЯ — СИСТЕМЕН — през
 * истинския {@code Resource.save} (пътят на екрана и REST) и през репозиторитата (служебният път).
 *
 * <p>По пътя на екрана записът е вмъкнат ПРЕДИ веригата на репозиторито; тук се доказва, че
 * създаването на дружество и на потребител все пак се познава ({@link BgJustInserted}).
 *
 * <p>⚠️ HSQLDB не чете Partner (формулата) — записаното след {@code Resource.save} се чете с native
 * SQL. ⚠️ {@code @Tag("integration")} — {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCompanyAndUserPartnerIntegrationTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static String tag() {
    return "U" + Long.toString(System.nanoTime(), 36).toUpperCase();
  }

  private static void ensureApps() {
    tx(
        () -> {
          if (JPA.all(AppBase.class).fetchOne() != null) {
            return null;
          }
          App base = new App();
          base.setCode("base");
          base.setName("Base");
          base.setActive(true);
          JPA.save(base);
          AppBase appBase = new AppBase();
          appBase.setApp(base);
          appBase.setGeneratePartnerSequence(true);
          appBase.setCheckExistingSequenceOnGeneration(false); // HSQLDB не чете Partner
          JPA.save(appBase);
          App account = new App();
          account.setCode("account");
          account.setName("Account");
          account.setActive(true);
          JPA.save(account);
          AppAccount appAccount = new AppAccount();
          appAccount.setApp(account);
          JPA.save(appAccount);
          return null;
        });
  }

  private static Long currency(String tag) {
    return tx(
        () -> {
          Currency c = new Currency();
          c.setName("Евро " + tag);
          c.setCode("€");
          c.setCodeISO("C" + tag);
          return JPA.save(c).getId();
        });
  }

  /** Администратор БЕЗ активно дружество — чиста инсталация, преди първото дружество. */
  private static void loginAdminWithoutCompany() {
    String code = "it-admin0-" + System.nanoTime();
    tx(
        () -> {
          Group admins =
              JPA
                  .em()
                  .createQuery("SELECT g FROM Group g WHERE g.code = 'admins'", Group.class)
                  .getResultList()
                  .stream()
                  .findFirst()
                  .orElse(null);
          if (admins == null) {
            admins = new Group("admins", "Administrators");
            JPA.em().persist(admins);
          }
          User user = new User(code, "Тест admin без дружество");
          user.setPassword("x");
          user.setGroup(admins);
          JPA.em().persist(user);
          return null;
        });
    BgRoleLogin.loginExisting(code);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Model> Resource<T> rest(Class<T> model) {
    return (Resource<T>)
        Beans.get(Injector.class)
            .getInstance(Key.get(Types.newParameterizedType(Resource.class, model)));
  }

  /** {@code POST /ws/rest/<model>} — нов запис, както го праща клиентът. */
  private static <T extends Model> Long restCreate(Class<T> model, Map<String, Object> data) {
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    Response response = rest(model).save(request);
    assertEquals(0, response.getStatus(), "status: " + response.getData());
    Object first = ((List<?>) response.getData()).get(0);
    return ((Number) ((Map<?, ?>) first).get("id")).longValue();
  }

  private static Throwable restFails(Class<? extends Model> model, Map<String, Object> data) {
    try {
      restCreate(model, data);
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** Изменяема — {@code Resource} пише в подадените карти. */
  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  /** {partnerCompany, partnerSeq, nxSystem} на партньора на реда — native SQL. */
  private static Object[] partnerOf(String table, Long id) {
    return tx(
        () ->
            (Object[])
                JPA.em()
                    .createNativeQuery(
                        "SELECT p.company, p.partner_seq, p.nx_system FROM "
                            + table
                            + " r JOIN base_partner p ON p.id = r.partner WHERE r.id = ?1")
                    .setParameter(1, id)
                    .getSingleResult());
  }

  private static Long idOf(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static long partnerSequenceCount(Long companyId) {
    return tx(
        () ->
            ((Number)
                    JPA.em()
                        .createNativeQuery(
                            "SELECT count(*) FROM base_sequence WHERE code_select = 'partner'"
                                + " AND company = ?1")
                        .setParameter(1, companyId)
                        .getSingleResult())
                .longValue());
  }

  private static String messages(Throwable e) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      out.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return out.toString();
  }

  // ── Партньорът на дружеството ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("чиста инсталация: първото дружество през екрана, admin без активно дружество")
  void първото_дружество_през_екрана() {
    ensureApps();
    String tag = tag();
    Long currency = currency(tag);
    loginAdminWithoutCompany();

    Map<String, Object> data = new HashMap<>();
    data.put("name", "Първо дружество " + tag);
    data.put("code", tag);
    data.put("currency", ref(currency));
    Long companyId = restCreate(Company.class, data);

    assertEquals(1, partnerSequenceCount(companyId), "поредицата — при създаването, и от екрана");
    Object[] partner = partnerOf("base_company", companyId);
    assertEquals(companyId, idOf(partner[0]), "партньорът на дружеството е в самото дружество");
    assertNotNull(partner[1], "номер от поредицата на дружеството");
    assertTrue(((String) partner[1]).startsWith("P"), (String) partner[1]);
  }

  @Test
  @DisplayName(
      "служебен път, без потребител: дружеството първо, партньорът му — в него, с първия номер")
  void дружество_през_репозиторито() {
    ensureApps();
    String tag = tag();
    Long currency = currency(tag);
    tx(
        () -> {
          Company company = new Company();
          company.setName("Служебно " + tag);
          company.setCode(tag);
          company.setCurrency(JPA.em().find(Currency.class, currency));
          Company saved = Beans.get(CompanyRepository.class).save(company);
          JPA.flush();
          Partner own = saved.getPartner();
          assertNotNull(own, "партньорът е създаден");
          assertEquals(saved.getId(), own.getCompany().getId());
          assertTrue(
              own.getCompanySet() == null || own.getCompanySet().isEmpty(),
              "без счетоводна ситуация за самото дружество (ново дружество няма AccountConfig)");
          assertNotNull(own.getPartnerSeq());
          return null;
        });
  }

  @Test
  @DisplayName("нов партньор, закачен в кода: записва се СЛЕД дружеството и в него")
  void нов_партньор_в_кода() {
    ensureApps();
    String tag = tag();
    Long currency = currency(tag);
    tx(
        () -> {
          Partner pending = new Partner();
          pending.setName("Подаден " + tag);
          pending.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
          Company company = new Company();
          company.setName("С подаден партньор " + tag);
          company.setCode(tag);
          company.setCurrency(JPA.em().find(Currency.class, currency));
          company.setPartner(pending);
          Company saved = Beans.get(CompanyRepository.class).save(company);
          JPA.flush();
          assertEquals(pending, saved.getPartner(), "същият партньор");
          assertEquals(saved.getId(), pending.getCompany().getId(), "в самото дружество");
          return null;
        });
  }

  @Test
  @DisplayName("партньор на друго дружество като партньор на дружество → отказ")
  void чужд_партньор() {
    ensureApps();
    String tagA = tag();
    Long currencyA = currency(tagA);
    Company a =
        tx(
            () -> {
              Company company = new Company();
              company.setName("А " + tagA);
              company.setCode(tagA);
              company.setCurrency(JPA.em().find(Currency.class, currencyA));
              return Beans.get(CompanyRepository.class).save(company);
            });
    String tagB = tag();
    Long currencyB = currency(tagB);
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                tx(
                    () -> {
                      Company b = new Company();
                      b.setName("Б " + tagB);
                      b.setCode(tagB);
                      b.setCurrency(JPA.em().find(Currency.class, currencyB));
                      b.setPartner(JPA.em().getReference(Partner.class, partnerIdOf(a.getId())));
                      return Beans.get(CompanyRepository.class).save(b);
                    }));
    assertTrue(messages(e).contains("е в самото дружество"), messages(e));
  }

  private static Long partnerIdOf(Long companyId) {
    return tx(
        () ->
            ((Number)
                    JPA.em()
                        .createNativeQuery("SELECT partner FROM base_company WHERE id = ?1")
                        .setParameter(1, companyId)
                        .getSingleResult())
                .longValue());
  }

  @Test
  @DisplayName("REST: нов партньор, вложен в записа на дружество → отказ с обяснение")
  void вложен_нов_партньор() {
    ensureApps();
    String tag = tag();
    Long currency = currency(tag);
    loginAdminWithoutCompany();
    Map<String, Object> data = new HashMap<>();
    data.put("name", "Вложено " + tag);
    data.put("code", tag);
    data.put("currency", ref(currency));
    Map<String, Object> nested = new HashMap<>();
    nested.put("name", "Нов вложен " + tag);
    data.put("partner", nested);
    Throwable t = restFails(Company.class, data);
    assertNotNull(t, "записът мина");
    assertTrue(messages(t).contains("се създава от сървъра СЛЕД дружеството"), messages(t));
  }

  // ── Партньорът на потребителя ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("нов потребител по реда на екрана (вмъкнат ПРЕДИ репозиторито) → системен партньор")
  void нов_потребител_по_реда_на_екрана() {
    // Точно редът на Resource.save: JPA.manage (persist + flush) и чак после repository.save. Без
    // влязъл потребител: в HSQLDB studio-ският GlobalEntityListener чете auth_user в отделна нишка
    // и
    // чака заключването на тази транзакция (PostgreSQL — не). REST пътят с admin е доказан на
    // PostgreSQL с вдигнатото приложение.
    ensureApps();
    String code = "it-e-" + System.nanoTime();
    Long userId =
        tx(
            () -> {
              User user = new User(code, "Нов от екрана");
              user.setPassword("x");
              user = JPA.manage(user);
              assertNotNull(user.getId(), "вмъкнат преди веригата — както от екрана");
              return Beans.get(UserRepository.class).save(user).getId();
            });
    Object[] partner = partnerOf("auth_user", userId);
    assertNull(partner[0], "без дружество");
    assertEquals("SYS-" + code, partner[1]);
    assertTrue(Boolean.TRUE.equals(partner[2]), "системен");
  }

  @Test
  @DisplayName("нов потребител по служебния път (без потребител) → също системен партньор")
  void нов_потребител_през_репозиторито() {
    ensureApps();
    String code = "it-s-" + System.nanoTime();
    tx(
        () -> {
          User user = new User(code, "Синхронизиран");
          user.setPassword("x");
          User saved = Beans.get(UserRepository.class).save(user);
          JPA.flush();
          Partner p = saved.getPartner();
          assertNotNull(p);
          assertTrue(p.getNxSystem());
          assertNull(p.getCompany());
          assertEquals("SYS-" + code, p.getPartnerSeq());
          return null;
        });
  }

  @Test
  @DisplayName("партньор без дружество по друг път → отказ (системен извън „нов потребител“)")
  void системен_по_друг_път() {
    ensureApps();
    Exception viaRepo =
        assertThrows(
            Exception.class,
            () ->
                tx(
                    () -> {
                      Partner p = new Partner();
                      p.setName("Опит " + tag());
                      p.setNxSystem(true);
                      p.setPartnerSeq("SYS-fake-" + System.nanoTime());
                      return Beans.get(PartnerRepository.class).save(p);
                    }));
    assertTrue(messages(viaRepo).contains("само заедно с нов потребител"), messages(viaRepo));

    loginAdminWithoutCompany();
    Map<String, Object> data = new HashMap<>();
    data.put("name", "REST опит " + tag());
    data.put("nxSystem", true);
    Throwable rest = restFails(Partner.class, data);
    assertNotNull(rest, "клиентът създаде системен партньор");
    assertTrue(messages(rest).contains("само заедно с нов потребител"), messages(rest));
  }

  @Test
  @DisplayName("системният партньор: не-admin не го пипа")
  void системен_само_за_admin() {
    ensureApps();
    String tag = tag();
    Long currency = currency(tag);
    Company a =
        tx(
            () -> {
              Company company = new Company();
              company.setName("За роля " + tag);
              company.setCode(tag);
              company.setCurrency(JPA.em().find(Currency.class, currency));
              return Beans.get(CompanyRepository.class).save(company);
            });
    Long roleUserId = BgRoleLogin.createUser(a, "Роля all.write", BgRoleLogin.WRITE_ROLE);
    String roleCode = tx(() -> JPA.em().find(User.class, roleUserId).getCode());
    // Създаването — без вход и в своя транзакция: в HSQLDB незавършен запис в auth_user заключва
    // studio-ския слушател (той чете потребителя в отделна нишка). Партньорът остава managed в
    // същия EntityManager — HSQLDB не може да го прочете наново (формулата).
    Partner p =
        tx(
            () -> {
              User user = new User("it-t-" + System.nanoTime(), "Цел");
              user.setPassword("x");
              return Beans.get(UserRepository.class).save(user).getPartner();
            });
    assertTrue(p.getNxSystem(), "системен");
    BgRoleLogin.loginExisting(roleCode);
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                tx(
                    () -> {
                      // Поле без връзка с потребителя: името се пренася в User.fullName, а него
                      // AuditInterceptor на Axelor пази от не-admin сам — тук се мери пазачът на
                      // партньора, не този на потребителя.
                      p.setFixedPhone("029999999");
                      JPA.flush();
                      return p;
                    }));
    assertTrue(messages(e).contains("само nyxa.admin"), messages(e));
  }
}
