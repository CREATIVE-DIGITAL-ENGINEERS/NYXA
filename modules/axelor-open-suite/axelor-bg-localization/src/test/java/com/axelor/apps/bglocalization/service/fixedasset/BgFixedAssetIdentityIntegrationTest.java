package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ИНВЕНТАРНИЯТ НОМЕР И КАКВО ТОЙ ОЗНАЧАВА (SAF-T A1) — пазачът на ниво Hibernate ({@link
 * BgFixedAssetListener}) в истинското свързване на приложението, плюс масовото обновяване по
 * истинския {@link Resource#updateMass}.
 *
 * <p><b>⚠️ Защо не през {@code Resource.save}.</b> Активът изисква доставчик (Partner), а {@code
 * JPA.manage} в {@code Resource.save} вика {@code hashCode} на прокси-то му — тоест SELECT върху
 * Partner, който HSQLDB не изпълнява заради PostgreSQL формулата на AOS. Затова всеки случай
 * създава фикстурите и прави промяната в ЕДНА транзакция, без да чисти контекста: flush-ът минава
 * през същия PreUpdate/PreDelete, през който минава и {@code Resource.save} (той прави flush ПРЕДИ
 * {@code repository.save}), а доставчикът остава в контекста и не се чете.
 *
 * <p><b>⚠️ Отказът трябва да е от ПАЗАЧА</b> ({@link BgFixedAssetIdentityException} във веригата на
 * причините, с правилното съобщение) — не какъв да е.
 *
 * <p>HSQLDB: частичната уникалност на номера е само в PostgreSQL (SQL файлът) — тя е в {@code
 * BgFixedAssetInventoryNumberMigrationDbTest}. Данните са измислени.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgFixedAssetIdentityIntegrationTest extends JpaSupport {

  /** Сцената на един случай: двете дружества, сметките и активите — всичко в контекста. */
  private static final class Scene {
    Company nss;
    Company ifrs;
    Account tangible;
    Account other;
    Account intangible;
    Journal journal;
    Partner supplier;
    FixedAssetCategory category;
    FixedAsset validated;
    FixedAsset draft;
    FixedAsset ifrsAsset;
  }

  // ─────────────── свързването ───────────────

  @Test
  @DisplayName("FixedAssetService в приложението е BgFixedAssetServiceImpl (split пише връзката)")
  void свързването() {
    assertTrue(
        Beans.get(FixedAssetService.class) instanceof BgFixedAssetServiceImpl,
        "FixedAssetService е " + Beans.get(FixedAssetService.class).getClass());
  }

  // ─────────────── валидиран актив ───────────────

  @Test
  @DisplayName("Инвентарният номер на валидиран актив не се сменя")
  void номерът() {
    assertRefusal(change(s -> s.validated.setFixedAssetSeq("IMMO-ДРУГ")), "инвентарният номер");
  }

  @Test
  @DisplayName("Сметката и категорията на валидиран актив не се сменят — вътрешен трансфер")
  void сметката_и_категорията() {
    assertRefusal(change(s -> s.validated.setPurchaseAccount(s.other)), "сметката");
    assertRefusal(
        change(
            s -> {
              FixedAssetCategory second = category(s.journal);
              s.validated.setFixedAssetCategory(second);
            }),
        "категорията");
  }

  @Test
  @DisplayName("Валидиран актив не се връща в чернова и не се трие")
  void черновата_и_триенето() {
    assertRefusal(
        change(s -> s.validated.setStatusSelect(FixedAssetRepository.STATUS_DRAFT)), "чернова");
    assertRefusal(change(s -> JPA.em().remove(s.validated)), "не се трие");
  }

  @Test
  @DisplayName("Масово обновяване на номера и на статуса — отказ (Resource.updateMass)")
  void масовото() {
    Long[] id = new Long[1];
    JPA.runInTransaction(() -> id[0] = scene().validated.getId());
    assertRefusal(massUpdate("fixedAssetSeq", "X", id[0]), "масово обновяване");
    assertRefusal(
        massUpdate("statusSelect", FixedAssetRepository.STATUS_DRAFT, id[0]), "масово обновяване");
  }

  // ─────────────── чернова ───────────────

  @Test
  @DisplayName("Чернова: сметката и номерът са свободни; празният номер на валидиран — се попълва")
  void свободното() {
    assertNull(
        change(
            s -> {
              s.draft.setPurchaseAccount(s.other);
              s.draft.setFixedAssetSeq("РЪЧЕН-1");
            }));
    assertNull(
        change(
            s -> {
              FixedAsset imported =
                  asset(
                      s.nss,
                      s.journal,
                      s.supplier,
                      s.category,
                      s.tangible,
                      null,
                      FixedAssetRepository.STATUS_VALIDATED);
              JPA.flush();
              imported.setFixedAssetSeq("INV-17");
            }),
        "внесен без номер — номерът се попълва");
  }

  // ─────────────── видът и „отделен от“ ───────────────

  @Test
  @DisplayName("Видът „държан за продажба“: при НСС — отказ, при МСФО — минава")
  void видът_по_рамката() {
    assertRefusal(
        change(s -> s.draft.setBgAssetKindSelect(BgFixedAssetKinds.HELD_FOR_SALE)), "МСФО");
    assertNull(change(s -> s.ifrsAsset.setBgAssetKindSelect(BgFixedAssetKinds.HELD_FOR_SALE)));
    assertRefusal(
        change(
            s -> {
              FixedAsset rou =
                  asset(
                      s.nss,
                      s.journal,
                      s.supplier,
                      s.category,
                      s.tangible,
                      null,
                      FixedAssetRepository.STATUS_DRAFT);
              rou.setBgAssetKindSelect(BgFixedAssetKinds.RIGHT_OF_USE);
              JPA.flush();
            }),
        "МСФО");
  }

  @Test
  @DisplayName("„Отделен от“ на ръчен актив — отказ; на отделен със split — веднъж")
  void отделен_от() {
    assertRefusal(change(s -> s.draft.setBgSplitFrom(s.validated)), "split");
    assertNull(
        change(
            s -> {
              s.draft.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_SCISSION);
              s.draft.setBgSplitFrom(s.validated);
            }));
    assertRefusal(
        change(
            s -> {
              s.draft.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_SCISSION);
              s.draft.setBgSplitFrom(s.validated);
              JPA.flush();
              s.draft.setBgSplitFrom(s.ifrsAsset);
            }),
        "не се сменя");
  }

  @Test
  @DisplayName("Записът през репозиторито попълва празния вид от сметката: 21x → НМА, 20x → ДМА")
  void видът_по_подразбиране() {
    assertNull(
        change(
            s -> {
              FixedAsset software = blank(s, s.intangible);
              FixedAsset machine = blank(s, s.tangible);
              Beans.get(FixedAssetRepository.class).save(software);
              Beans.get(FixedAssetRepository.class).save(machine);
              assertEquals(BgFixedAssetKinds.INTANGIBLE, software.getBgAssetKindSelect());
              assertEquals(BgFixedAssetKinds.TANGIBLE, machine.getBgAssetKindSelect());
            }));
  }

  // ─────────────── сцената ───────────────

  /**
   * Сцената и промяната в ЕДНА транзакция; flush след сцената (вмъкването) и след промяната
   * (обновяването / триенето). Връща отказа или {@code null}.
   */
  private static Throwable change(Consumer<Scene> action) {
    try {
      JPA.runInTransaction(
          () -> {
            Scene s = scene();
            JPA.flush();
            action.accept(s);
            JPA.flush();
          });
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      JPA.clear();
    }
  }

  private static Scene scene() {
    String tag = String.valueOf(System.nanoTime());
    Scene s = new Scene();
    Currency currency = currency(tag);
    s.nss = company("Н" + tag, BgAccountingFramework.NSS, currency);
    s.ifrs = company("М" + tag, BgAccountingFramework.IFRS, currency);
    s.tangible = account(s.nss, "2041", "Машини");
    s.other = account(s.nss, "2042", "Компютри");
    s.intangible = account(s.nss, "2141", "Софтуер");
    s.journal = journal(s.nss, tag);
    s.supplier = partner(s.nss);
    s.category = category(s.journal);
    s.validated =
        asset(
            s.nss,
            s.journal,
            s.supplier,
            s.category,
            s.tangible,
            "IMMO-" + tag,
            FixedAssetRepository.STATUS_VALIDATED);
    s.draft =
        asset(
            s.nss,
            s.journal,
            s.supplier,
            s.category,
            s.tangible,
            null,
            FixedAssetRepository.STATUS_DRAFT);
    Journal ifrsJournal = journal(s.ifrs, "M" + tag);
    s.ifrsAsset =
        asset(
            s.ifrs,
            ifrsJournal,
            partner(s.ifrs),
            category(ifrsJournal),
            account(s.ifrs, "2041", "Машини"),
            null,
            FixedAssetRepository.STATUS_DRAFT);
    return s;
  }

  /**
   * Глобалните настройки, които веригата на репозиторито чете; в празна тестова база са {@code
   * null}. Нито една няма задължително поле — по един празен ред стига (както в {@code
   * BgVatProtocolReachabilityIntegrationTest}).
   */
  private static void ensureAppConfigs() {
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppBase());
    }
    if (Beans.get(com.axelor.studio.db.repo.AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppAccount());
    }
  }

  /** Една валута за двете дружества на сцената: кодът по ISO е уникален. */
  private static Currency currency(String tag) {
    ensureAppConfigs();
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("E" + tag.substring(Math.max(0, tag.length() - 6)));
    return JPA.save(currency);
  }

  private static Company company(String tag, String framework, Currency currency) {
    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode("FA-" + tag);
    company.setCurrency(currency);
    company.setBgAccountingFramework(framework);
    return JPA.save(company);
  }

  private static Account account(Company company, String code, String name) {
    Account a = new Account();
    a.setCode(code);
    a.setName(name);
    a.setCompany(company);
    a.setCommonPosition(1);
    return JPA.save(a);
  }

  private static Journal journal(Company company, String tag) {
    JournalType type = new JournalType();
    type.setName("Активи " + tag);
    type.setCode("FA-" + tag);
    type.setTechnicalTypeSelect(5);
    type = JPA.save(type);
    Journal journal = new Journal();
    journal.setName("Активи " + tag);
    journal.setCode("FAJ-" + tag);
    journal.setJournalType(type);
    journal.setCompany(company);
    return JPA.save(journal);
  }

  private static Partner partner(Company company) {
    Partner supplier = new Partner();
    supplier.setName("Доставчик");
    supplier.setCompany(company);
    return JPA.save(supplier);
  }

  private static FixedAssetCategory category(Journal journal) {
    FixedAssetCategory category = new FixedAssetCategory();
    category.setName("Машини");
    category.setJournal(journal);
    return JPA.save(category);
  }

  private static FixedAsset asset(
      Company company,
      Journal journal,
      Partner supplier,
      FixedAssetCategory category,
      Account account,
      String seq,
      int status) {
    FixedAsset asset = new FixedAsset();
    asset.setName("Актив " + seq);
    asset.setCompany(company);
    asset.setJournal(journal);
    asset.setPartner(supplier);
    asset.setFixedAssetCategory(category);
    asset.setPurchaseAccount(account);
    asset.setFixedAssetSeq(seq);
    asset.setStatusSelect(status);
    asset.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_MANUAL);
    asset.setBgAssetKindSelect(BgFixedAssetKinds.TANGIBLE);
    return JPA.save(asset);
  }

  /** Нов актив без вид — за подразбирането от сметката. */
  private static FixedAsset blank(Scene s, Account account) {
    FixedAsset asset = new FixedAsset();
    asset.setName("Без вид");
    asset.setCompany(s.nss);
    asset.setJournal(s.journal);
    asset.setPartner(s.supplier);
    asset.setFixedAssetCategory(s.category);
    asset.setPurchaseAccount(account);
    asset.setStatusSelect(FixedAssetRepository.STATUS_DRAFT);
    return asset;
  }

  @SuppressWarnings("unchecked")
  private static Resource<FixedAsset> rest() {
    return (Resource<FixedAsset>)
        Beans.get(Injector.class)
            .getInstance(Key.get(Types.newParameterizedType(Resource.class, FixedAsset.class)));
  }

  private static Throwable massUpdate(String field, Object value, Long... ids) {
    Map<String, Object> values = new HashMap<>();
    values.put(field, value);
    List<Object> records = new ArrayList<>();
    records.add(values);
    Map<String, Object> context = new HashMap<>();
    context.put("_model", FixedAsset.class.getName());
    context.put("__ids__", List.of(ids));
    Map<String, Object> data = new HashMap<>();
    data.put("_domain", "self.id IN (:__ids__)");
    data.put("_domainContext", context);
    Request request = new Request();
    request.setModel(FixedAsset.class.getName());
    request.setRecords(records);
    request.setData(data);
    JPA.clear();
    try {
      rest().updateMass(request);
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      JPA.clear();
    }
  }

  private static void assertRefusal(Throwable t, String text) {
    assertNotNull(t, "записът мина");
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof BgFixedAssetIdentityException) {
        assertTrue(c.getMessage().contains(text), c.getMessage());
        return;
      }
    }
    throw new AssertionError("отказът не е от пазача на актива, а: " + t, t);
  }
}
