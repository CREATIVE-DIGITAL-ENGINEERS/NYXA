package com.axelor.apps.bglocalization.service.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.rpc.Request;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Правилата на номера по дружество — без база: откъде продължава новата поредица, кое дружество
 * получава новият партньор, какво отказват слушателят, пазачът на заявката и подменената услуга.
 */
class BgPartnerSequenceRulesTest {

  private static Company company(long id, String code) {
    Company company = new Company();
    company.setId(id);
    company.setCode(code);
    return company;
  }

  // ── Откъде продължава ─────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("продължава над най-големия заварен номер и над общия брояч")
  void следващият_номер() {
    List<String> codes =
        Arrays.asList("P0001", "P0040", "P0007", "X-9999", "P12A", null, " P0041 ");
    assertEquals(42, BgPartnerSequence.nextNum("P", null, codes, 0), "над P0041");
    assertEquals(45, BgPartnerSequence.nextNum("P", null, codes, 45), "общият брояч е по-напред");
    assertEquals(1, BgPartnerSequence.nextNum("P", null, Collections.emptyList(), 0), "от 1");
    assertEquals(
        8,
        BgPartnerSequence.nextNum("T", "-BG", Arrays.asList("T0007-BG", "P0090"), 0),
        "префикс и суфикс — само кодовете в същия формат");
  }

  @Test
  @DisplayName("число само от префикс + цифри + суфикс")
  void числото_в_кода() {
    assertEquals(40L, BgPartnerSequence.numberOf("P", null, "P0040"));
    assertNull(BgPartnerSequence.numberOf("P", null, "P"));
    assertNull(BgPartnerSequence.numberOf("P", null, "Q0040"));
    assertNull(BgPartnerSequence.numberOf("P", null, "P00-40"));
    assertNull(BgPartnerSequence.numberOf("P", null, "P1234567890123456789"), "не прелива");
    assertEquals(7L, BgPartnerSequence.numberOf(null, null, "7"));
  }

  @Test
  @DisplayName("форматът — от общата поредица, броячът — свой")
  void новата_поредица() {
    Company a = company(1, "A");
    Sequence common = new Sequence();
    common.setPrefixe("T");
    common.setSuffixe("-X");
    common.setPadding(6);
    Sequence s = BgPartnerSequence.newFor(a, common, 53);
    assertSame(a, s.getCompany());
    assertEquals(SequenceRepository.PARTNER, s.getCodeSelect());
    assertEquals("T", s.getPrefixe());
    assertEquals("-X", s.getSuffixe());
    assertEquals(6, s.getPadding());
    assertEquals("A-partner", s.getImportId());
    assertEquals(1, s.getSequenceVersionList().size());
    assertEquals(53L, s.getSequenceVersionList().get(0).getNextNum());
    assertFalse(s.getYearlyResetOk());

    Sequence plain = BgPartnerSequence.newFor(a, null, 1);
    assertEquals("P", plain.getPrefixe());
    assertEquals(4, plain.getPadding());
  }

  // ── Кое дружество ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("записът → изричното → активното; по-слабото не се пита, ако има по-силно")
  void редът_на_избора() {
    Company onRecord = company(1, "R");
    Company explicit = company(2, "E");
    Company active = company(3, "U");
    assertSame(onRecord, BgPartnerCompany.choose(onRecord, () -> explicit, () -> active));
    assertSame(
        explicit,
        BgPartnerCompany.choose(
            null,
            () -> explicit,
            () -> {
              throw new AssertionError("потребителят не се пита при изрично дружество");
            }));
    assertSame(active, BgPartnerCompany.choose(null, () -> null, () -> active));
    assertNull(BgPartnerCompany.choose(null, () -> null, () -> null));
  }

  @Test
  @DisplayName("изричното дружество важи само в блока и вложеното връща предишното")
  void изричното_дружество() throws Exception {
    Company a = company(1, "A");
    Company b = company(2, "B");
    assertNull(BgExplicitCompany.current());
    BgExplicitCompany.call(
        a,
        () -> {
          assertSame(a, BgExplicitCompany.current());
          BgExplicitCompany.run(b, () -> assertSame(b, BgExplicitCompany.current()));
          assertSame(a, BgExplicitCompany.current());
          return null;
        });
    assertNull(BgExplicitCompany.current());
    assertThrows(
        IllegalStateException.class,
        () ->
            BgExplicitCompany.run(
                a,
                () -> {
                  throw new IllegalStateException("x");
                }));
    assertNull(BgExplicitCompany.current(), "и след изключение");
  }

  // ── Слушателят ────────────────────────────────────────────────────────────────────────────────

  private static final String[] NAMES = {"name", "company", "partnerSeq", "nxSystem"};

  private static EntityPersister persister() {
    return (EntityPersister)
        Proxy.newProxyInstance(
            EntityPersister.class.getClassLoader(),
            new Class<?>[] {EntityPersister.class},
            (proxy, method, args) -> {
              if ("getPropertyNames".equals(method.getName())) {
                return NAMES;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  @DisplayName("INSERT: празното дружество се попълва от изричното; сложеното остава")
  void вмъкване() {
    Company a = company(1, "A");
    Company b = company(2, "B");
    BgPartnerCompanyListener listener = new BgPartnerCompanyListener();

    Partner fresh = new Partner();
    Object[] state = {"Нов", null, null, false};
    BgExplicitCompany.run(
        b,
        () ->
            assertFalse(
                listener.onPreInsert(new PreInsertEvent(fresh, 1L, state, persister(), null))));
    assertSame(b, state[1], "в INSERT-а");
    assertSame(b, fresh.getCompany(), "и в обекта");

    Partner owned = new Partner();
    owned.setCompany(a);
    Object[] ownedState = {"Свой", a, null, false};
    BgExplicitCompany.run(
        b,
        () -> listener.onPreInsert(new PreInsertEvent(owned, 2L, ownedState, persister(), null)));
    assertSame(a, ownedState[1], "сложеното от кода не се подменя");
  }

  @Test
  @DisplayName("UPDATE: смяна, изчистване и попълване на празното → отказ; същото (proxy) минава")
  void промяна() {
    Company a = company(1, "A");
    Company aAgain = company(1, "A");
    Company b = company(2, "B");
    BgPartnerCompanyListener listener = new BgPartnerCompanyListener();
    Partner p = new Partner();
    p.setId(7L);

    assertFalse(update(listener, p, a, aAgain), "същият ред, друг екземпляр");
    assertFalse(update(listener, p, null, null), "заварен, празното остава празно");
    for (Object[] pair : new Object[][] {{a, b}, {a, null}, {null, b}}) {
      BgPartnerCompanyException e =
          assertThrows(
              BgPartnerCompanyException.class,
              () -> update(listener, p, (Company) pair[0], (Company) pair[1]));
      assertTrue(e.getMessage().contains("#7"), e.getMessage());
    }
    assertThrows(
        BgPartnerCompanyException.class,
        () ->
            listener.onPreUpdate(
                new PreUpdateEvent(
                    p, 7L, new Object[] {"x", a, null, false}, null, persister(), null)),
        "без старо състояние — отказ, не пропуск");
  }

  private static boolean update(
      BgPartnerCompanyListener listener, Partner p, Company before, Company after) {
    return listener.onPreUpdate(
        new PreUpdateEvent(
            p,
            p.getId(),
            new Object[] {"x", after, "P0001", false},
            new Object[] {"x", before, "P0001", false},
            persister(),
            null));
  }

  @Test
  @DisplayName("слушателят не пипа други модели")
  void друг_модел() {
    Object[] state = {"x", null, null, false};
    assertFalse(
        new BgPartnerCompanyListener()
            .onPreInsert(new PreInsertEvent(new Invoice(), 1L, state, persister(), null)));
    assertNull(state[1]);
  }

  // ── Системният партньор на потребителя ─────────────────────────────────────────────────────────

  /** Слушател, при който „влязъл не-admin“ е зададено от теста (без Shiro). */
  private static BgPartnerCompanyListener listener(boolean nonAdmin) {
    return new BgPartnerCompanyListener() {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean writtenByNonAdmin() {
        return nonAdmin;
      }
    };
  }

  @Test
  @DisplayName("системен партньор: само в пътя „нов потребител“ и само без дружество")
  void системен_при_вмъкване() {
    BgPartnerCompanyListener l = listener(false);
    Partner system = new Partner();
    system.setNxSystem(true);

    Object[] inScope = {"Иван", null, "SYS-ivan", true};
    BgSystemPartner.creating(
        () -> l.onPreInsert(new PreInsertEvent(system, 1L, inScope, persister(), null)));
    assertNull(inScope[1], "без дружество — и изричното не го попълва");

    Company a = company(1, "A");
    BgPartnerCompanyException outside =
        assertThrows(
            BgPartnerCompanyException.class,
            () ->
                BgExplicitCompany.run(
                    a,
                    () ->
                        l.onPreInsert(
                            new PreInsertEvent(
                                system,
                                2L,
                                new Object[] {"x", null, null, true},
                                persister(),
                                null))));
    assertEquals(BgPartnerCompany.SYSTEM_OUTSIDE_NEW_USER, outside.getMessage());

    assertThrows(
        BgPartnerCompanyException.class,
        () ->
            BgSystemPartner.creating(
                () ->
                    l.onPreInsert(
                        new PreInsertEvent(
                            system, 3L, new Object[] {"x", a, null, true}, persister(), null))),
        "системен с дружество");
  }

  @Test
  @DisplayName("системен партньор: флагът не се сменя, а не-admin не го пипа")
  void системен_при_промяна() {
    Partner p = new Partner();
    p.setId(8L);
    Object[] old = {"x", null, "SYS-x", true};
    assertFalse(
        listener(false)
            .onPreUpdate(
                new PreUpdateEvent(
                    p, 8L, new Object[] {"y", null, "SYS-x", true}, old, persister(), null)),
        "admin (или без потребител) — минава");
    BgPartnerCompanyException nonAdmin =
        assertThrows(
            BgPartnerCompanyException.class,
            () ->
                listener(true)
                    .onPreUpdate(
                        new PreUpdateEvent(
                            p,
                            8L,
                            new Object[] {"y", null, "SYS-x", true},
                            old,
                            persister(),
                            null)));
    assertTrue(nonAdmin.getMessage().contains("nyxa.admin"), nonAdmin.getMessage());
    assertThrows(
        BgPartnerCompanyException.class,
        () ->
            listener(false)
                .onPreUpdate(
                    new PreUpdateEvent(
                        p, 8L, new Object[] {"x", null, "SYS-x", false}, old, persister(), null)),
        "системен → обикновен");
    assertThrows(
        BgPartnerCompanyException.class,
        () ->
            listener(false)
                .onPreUpdate(
                    new PreUpdateEvent(
                        p,
                        8L,
                        new Object[] {"x", null, "P1", true},
                        new Object[] {"x", null, "P1", false},
                        persister(),
                        null)),
        "обикновен → системен");
  }

  @Test
  @DisplayName(
      "системният партньор на потребителя: SYS-<код>, физическо лице, свързан с потребителя")
  void системният_партньор() {
    com.axelor.auth.db.User user = new com.axelor.auth.db.User("ivan", "Иван Петров");
    Partner p = BgSystemPartner.newFor(user);
    assertTrue(p.getNxSystem());
    assertNull(p.getCompany());
    assertEquals("SYS-ivan", p.getPartnerSeq());
    assertEquals("Иван Петров", p.getName());
    assertSame(user, p.getLinkedUser());
    assertFalse(BgSystemPartner.isCreating(), "извън пътя „нов потребител“");
    assertTrue(BgSystemPartner.creating(BgSystemPartner::isCreating));
    assertFalse(BgSystemPartner.isCreating(), "и след него");
  }

  // ── Проверката на схемата при старт ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("стартът спира, ако глобалната уникалност е още там — и казва кой файл не е пуснат")
  void проверката_на_схемата() {
    assertNull(BgPartnerSchemaCheck.refusal(0L), "файлът е пуснат");
    String refusal = BgPartnerSchemaCheck.refusal(1L);
    assertTrue(refusal.contains(BgPartnerSchemaCheck.MIGRATION_FILE), refusal);
    assertTrue(refusal.contains("срещу базата"), refusal);
  }

  // ── Пазачът на заявката ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("нов партньор от клиента: само активното дружество (или празно)")
  void дружество_от_клиента() {
    Company active = company(3, "U");
    assertNull(
        BgPartnerRequestGuard.refuseClientCompany(null, active), "празно — сървърът го слага");
    assertNull(BgPartnerRequestGuard.refuseClientCompany(3L, active), "активното");
    assertTrue(BgPartnerRequestGuard.refuseClientCompany(4L, active).contains("U"));
    assertTrue(
        BgPartnerRequestGuard.refuseClientCompany(3L, null).contains("не е избрано"),
        "без активно дружество клиентът не може да избере");
  }

  @Test
  @DisplayName("записът на дружество не носи нов вложен партньор; препратка {id} минава")
  void вложен_партньор() {
    assertTrue(BgPartnerRequestGuard.isNewNested(Collections.singletonMap("name", "Ново ЕООД")));
    assertFalse(BgPartnerRequestGuard.isNewNested(Collections.singletonMap("id", 5)));
    assertFalse(BgPartnerRequestGuard.isNewNested(null));
  }

  @Test
  @DisplayName("масово обновяване на company → отказ; стойностите са в records[0], не в data")
  void масово_обновяване() {
    BgPartnerRequestGuard guard = new BgPartnerRequestGuard();
    Request request = new Request();
    Map<String, Object> values = new HashMap<>();
    values.put("company", Collections.singletonMap("id", 2));
    request.setRecords(new ArrayList<>(Collections.singletonList(values)));
    Map<String, Object> filter = new HashMap<>();
    filter.put("_domain", "self.id in (1)");
    request.setData(filter);
    assertEquals(values, BgPartnerRequestGuard.massUpdateValues(request));
    assertTrue(BgPartnerRequestGuard.massUpdateValues(request).containsKey("company"));

    Map<String, Object> harmless = new HashMap<>();
    harmless.put("self.isCustomer", true);
    Request ok = new Request();
    ok.setRecords(new ArrayList<>(Collections.singletonList(harmless)));
    assertFalse(BgPartnerRequestGuard.massUpdateValues(ok).containsKey("company"));
    assertEquals(1L, BgPartnerRequestGuard.idOf(Collections.singletonMap("id", "1")));
    assertNull(
        BgPartnerRequestGuard.idOf(Collections.singletonMap("id", -3)), "нов запис в клиента");
  }

  // ── Подменената услуга ────────────────────────────────────────────────────────────────────────

  private static BgSequenceService service() {
    return new BgSequenceService(null, null, null, null);
  }

  @Test
  @DisplayName("само кодът partner за партньор отива към поредицата на дружеството")
  void кой_код() {
    assertTrue(BgSequenceService.isPartnerNumber(SequenceRepository.PARTNER, new Partner()));
    assertFalse(BgSequenceService.isPartnerNumber(SequenceRepository.PARTNER, new Invoice()));
    assertFalse(BgSequenceService.isPartnerNumber("bgPaySlip", new Partner()), "ТРЗ не се пипа");
    assertEquals(" AND self.company = :company", service().computeCompanyQuery(Partner.class));
  }

  @Test
  @DisplayName("дружеството на номера: своето; за нов — изричното; заварен без дружество → отказ")
  void дружеството_на_номера() throws AxelorException {
    Company a = company(1, "A");
    Company b = company(2, "B");
    BgSequenceService service = service();

    Partner owned = new Partner();
    owned.setCompany(a);
    assertSame(a, service.partnerCompany(owned, null));
    assertSame(a, service.partnerCompany(owned, a));
    AxelorException foreign =
        assertThrows(AxelorException.class, () -> service.partnerCompany(owned, b));
    assertTrue(foreign.getMessage().contains("A") && foreign.getMessage().contains("B"));

    Partner fresh = new Partner();
    Company[] chosen = new Company[1];
    BgExplicitCompany.run(
        b,
        () -> {
          try {
            chosen[0] = service.partnerCompany(fresh, null);
          } catch (AxelorException e) {
            throw new IllegalStateException(e);
          }
        });
    assertSame(b, chosen[0]);
    assertSame(b, fresh.getCompany(), "номерът и дружеството — едно решение");

    Partner legacy = new Partner();
    legacy.setId(99L);
    AxelorException refused =
        assertThrows(AxelorException.class, () -> service.partnerCompany(legacy, null));
    assertTrue(refused.getMessage().contains("#99"), refused.getMessage());
    assertNull(legacy.getCompany(), "заварен не се попълва оттук");
  }
}
