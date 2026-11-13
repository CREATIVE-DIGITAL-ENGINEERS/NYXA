package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.service.BgDeclarantResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТОЧНО ЕДИН ДЕКЛАРАТОР ПО ПОДРАЗБИРАНЕ — пазачът на пътя, по който инвариантът не се налагаше.
 *
 * <p>⚠️ КАКВО ДОКАЗВА И КАКВО НЕ. Доказва решението: два вдигнати флага в колекцията на дружеството
 * СПИРАТ записа, един или нула — не. НЕ доказва, че Hibernate вика точно този {@code save} — това е
 * твърдение за {@code Resource.save} и се чете в платформата, не се симулира тук (модулът няма
 * mock-подпора и заявка не се сверява без база).
 *
 * <p>⚠️ И ЗАЩО ОТКАЗ, А НЕ АВТОМАТИЧНО СВАЛЯНЕ, както при прекия запис на декларанта. През
 * решетката на Дружеството пристига цялата колекция и в нея са вдигнати и щракнатият, и заварения;
 * кой от двата е щракнатият, подадените данни не казват. Правило „печели последният ред“ познава
 * едната посока (вдигаш ВТОРИЯ) и мълчаливо погажда другата (вдигаш ПЪРВИЯ) — а тихо погрешното е
 * по-лошо от шумния отказ.
 */
class BgDeclarantSingleDefaultTest {

  /** Репозиторията с отрязан JPA изход — записът само се брои. */
  private static class Probe extends BgCompanyManagementRepository {
    private Company saved;

    @Override
    protected Company saveRecord(Company company) {
      this.saved = company;
      return company;
    }
  }

  private static BgDeclarant declarant(String names, boolean isDefault) {
    BgDeclarant d = new BgDeclarant();
    d.setDeclarantNames(names);
    d.setIsDefault(isDefault);
    return d;
  }

  private static Company company(BgDeclarant... declarants) {
    Company c = new Company();
    c.setBgDeclarantList(new ArrayList<>(Arrays.asList(declarants)));
    return c;
  }

  @Test
  @DisplayName("⚠️ ДВА вдигнати през решетката на Дружеството СПИРАТ записа — счупеният път")
  void twoDefaultsThroughTheCompanyGridRefuseTheSave() {
    Probe probe = new Probe();
    Company c = company(declarant("Иван Иванов", true), declarant("Мария Петрова", true));

    PersistenceException thrown = assertThrows(PersistenceException.class, () -> probe.save(c));

    assertTrue(thrown.getMessage().contains("Иван Иванов"), "казва КОИ редове пречат");
    assertTrue(thrown.getMessage().contains("Мария Петрова"), "и двата, не само единия");
    assertNull(probe.saved, "нищо не е записано");
  }

  @Test
  @DisplayName("Един вдигнат минава — това е самият инвариант, не изключение от него")
  void exactlyOneDefaultPasses() {
    Probe probe = new Probe();
    Company c = company(declarant("Иван Иванов", true), declarant("Мария Петрова", false));

    assertSame(c, probe.save(c));
    assertSame(c, probe.saved);
  }

  @Test
  @DisplayName("⚠️ НУЛА вдигнати също минава — „поне един“ би спрял всяко ново дружество")
  void zeroDefaultsIsAWorkingState() {
    Probe probe = new Probe();
    Company c = company(declarant("Иван Иванов", false), declarant("Мария Петрова", false));

    assertSame(c, probe.save(c));
    assertSame(c, probe.saved);
  }

  @Test
  @DisplayName("Дружество без декларатори изобщо — колекцията е null и това не е отказ")
  void aCompanyWithoutDeclarantsSavesFine() {
    Probe probe = new Probe();
    Company c = new Company();

    assertSame(c, probe.save(c));
    assertSame(c, probe.saved);
  }

  /** Прекият път: репозиторията на декларанта с отрязани и заявката, и записът. */
  private static class DeclarantProbe extends BgDeclarantManagementRepository {
    private final List<BgDeclarant> persisted;
    private BgDeclarant saved;

    DeclarantProbe(List<BgDeclarant> persisted) {
      this.persisted = persisted;
    }

    @Override
    protected List<BgDeclarant> otherDefaults(BgDeclarant entity) {
      List<BgDeclarant> others = new ArrayList<>();
      for (BgDeclarant candidate : persisted) {
        if (candidate != entity && Boolean.TRUE.equals(candidate.getIsDefault())) {
          others.add(candidate);
        }
      }
      return others;
    }

    @Override
    protected BgDeclarant saveRecord(BgDeclarant entity) {
      this.saved = entity;
      return entity;
    }
  }

  @Test
  @DisplayName("РЕГРЕСИЯ: през самия BgDeclarant вдигнатият сваля другия — мълчаливо, както досега")
  void throughTheDeclarantItselfTheOtherIsCleared() {
    Company c = new Company();
    BgDeclarant first = declarant("Иван Иванов", true);
    BgDeclarant second = declarant("Мария Петрова", true);
    first.setCompany(c);
    second.setCompany(c);

    DeclarantProbe probe = new DeclarantProbe(Arrays.asList(first, second));
    assertSame(second, probe.save(second));

    assertFalse(first.getIsDefault(), "завареният се сваля");
    assertTrue(second.getIsDefault(), "записваният остава вдигнат");
    assertSame(second, probe.saved);
  }

  @Test
  @DisplayName("⚠️ Запис БЕЗ вдигнат флаг не пипа заварения — свалянето е следствие, не ритуал")
  void savingANonDefaultLeavesTheExistingDefaultAlone() {
    Company c = new Company();
    BgDeclarant existing = declarant("Иван Иванов", true);
    BgDeclarant plain = declarant("Мария Петрова", false);
    existing.setCompany(c);
    plain.setCompany(c);

    DeclarantProbe probe = new DeclarantProbe(Arrays.asList(existing, plain));
    probe.save(plain);

    assertTrue(existing.getIsDefault(), "нищо не се сваля, когато нищо не се вдига");
  }

  @Test
  @DisplayName("Празна и липсваща колекция не са отказ")
  void anEmptyOrMissingCollectionIsNotARefusal() {
    assertNull(BgDeclarantResolver.refuseMultipleDefaults(null));
    assertNull(BgDeclarantResolver.refuseMultipleDefaults(Collections.<BgDeclarant>emptyList()));
    assertNull(BgDeclarantResolver.refuseMultipleDefaults(Arrays.asList((BgDeclarant) null, null)));
  }

  @Test
  @DisplayName("Три вдигнати ги изброява и трите, а не първите два")
  void allRaisedRowsAreNamed() {
    List<BgDeclarant> three =
        Arrays.asList(declarant("А", true), declarant("Б", true), declarant("В", true));
    String refusal = BgDeclarantResolver.refuseMultipleDefaults(three);

    assertNotNull(refusal);
    assertTrue(refusal.contains("А") && refusal.contains("Б") && refusal.contains("В"));
    assertEquals(3, BgDeclarantResolver.defaults(three).size());
  }

  @Test
  @DisplayName("⚠️ Декларант без име се назовава „(без име)“, а не „null“")
  void anUnnamedDeclarantStillReadsAsARow() {
    String refusal =
        BgDeclarantResolver.refuseMultipleDefaults(
            Arrays.asList(declarant(null, true), declarant("   ", true)));

    assertNotNull(refusal);
    assertTrue(refusal.contains("(без име)"), refusal);
    assertTrue(!refusal.contains("null"), refusal);
  }
}
