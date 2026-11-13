package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.bglocalization.module.BgStockWiringTestModule;
import com.axelor.auth.AuthService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Служебният потребител „Nyxa — автоматично осчетоводяване“ срещу истинска база (HSQLDB): създава
 * се веднъж, блокиран е и не влиза, групата му е само с правата за статии — НЕ admin и без роли;
 * настройката на заварените дружества се попълва само ако е празна.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgStockWiringTestModule.class)
@Tag("integration")
class BgAutoPostingUserIntegrationTest extends JpaSupport {

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static User serviceUser() {
    return tx(
        () ->
            JPA.all(User.class)
                .filter("self.importId = ?1", BgAutoPostingUserService.USER_IMPORT_ID)
                .fetchOne());
  }

  private static Company company(String tag) {
    return tx(
        () -> {
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("U" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("Служебен " + tag);
          company.setCode("U-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);
          AccountConfig config = new AccountConfig();
          config.setCompany(company);
          JPA.save(config);
          return company;
        });
  }

  private static AccountConfig config(Company company) {
    return tx(() -> JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne());
  }

  @Test
  @DisplayName("създава се веднъж (importId), блокиран, в своя група — НЕ admin, без роли")
  void служебният_потребител() {
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    assertEquals(
        1L,
        (long)
            tx(
                () ->
                    JPA.all(User.class)
                        .filter("self.importId = ?1", BgAutoPostingUserService.USER_IMPORT_ID)
                        .count()));
    tx(
        () -> {
          User user = JPA.find(User.class, serviceUser().getId());
          assertEquals(BgAutoPostingUserService.USER_CODE, user.getCode());
          assertEquals("Nyxa — автоматично осчетоводяване", user.getName());
          assertTrue(Boolean.TRUE.equals(user.getBlocked()), "блокиран — без вход");
          assertFalse(AuthUtils.isActive(user), "неактивен за входа");
          assertFalse(AuthUtils.isAdmin(user), "никога admin");
          assertTrue(user.getRoles() == null || user.getRoles().isEmpty(), "без роли");
          assertNotNull(user.getGroup());
          assertEquals(BgAutoPostingUserService.GROUP_CODE, user.getGroup().getCode());
          assertNotEquals("admins", user.getGroup().getCode());
          assertTrue(
              user.getGroup().getRoles() == null || user.getGroup().getRoles().isEmpty(),
              "групата няма роли — значи и ролята за временно затворен период я няма");
          return user;
        });
  }

  @Test
  @DisplayName("правата на групата: Move/MoveLine — четене, създаване, запис; без триене")
  void правата_на_групата() {
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    tx(
        () -> {
          User user = JPA.find(User.class, serviceUser().getId());
          List<Permission> permissions = List.copyOf(user.getGroup().getPermissions());
          for (String object :
              List.of("com.axelor.apps.account.db.Move", "com.axelor.apps.account.db.MoveLine")) {
            Permission p =
                permissions.stream()
                    .filter(x -> object.equals(x.getObject()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("няма право за " + object));
            assertTrue(Boolean.TRUE.equals(p.getCanRead()), object);
            assertTrue(Boolean.TRUE.equals(p.getCanCreate()), object);
            assertTrue(Boolean.TRUE.equals(p.getCanWrite()), object);
            assertFalse(Boolean.TRUE.equals(p.getCanRemove()), object + " — без триене");
          }
          for (Permission p : permissions) {
            if (!p.getObject().endsWith(".Move") && !p.getObject().endsWith(".MoveLine")) {
              assertFalse(
                  Boolean.TRUE.equals(p.getCanWrite())
                      || Boolean.TRUE.equals(p.getCanCreate())
                      || Boolean.TRUE.equals(p.getCanRemove()),
                  p.getObject() + " — само четене");
            }
          }
          return null;
        });
  }

  @Test
  @DisplayName("опитът за вход се отказва — дори с вярна парола")
  void входът_се_отказва() {
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    tx(
        () -> {
          User user = JPA.find(User.class, serviceUser().getId());
          user.setPassword(Beans.get(AuthService.class).encrypt("vhod-123"));
          return JPA.save(user);
        });
    assertThrows(
        AuthenticationException.class,
        () ->
            SecurityUtils.getSubject()
                .login(new UsernamePasswordToken(BgAutoPostingUserService.USER_CODE, "vhod-123")));
  }

  @Test
  @DisplayName("заварено дружество: празната настройка става служебният; попълнената не се пипа")
  void настройката_на_дружествата() {
    Company empty = company("EMP");
    Company chosen = company("OWN");
    User own =
        tx(
            () -> {
              User u = new User();
              u.setCode("ivan-acc");
              u.setName("Иван счетоводител");
              u.setPassword("x");
              u = JPA.save(u);
              AccountConfig c = JPA.find(AccountConfig.class, config(chosen).getId());
              c.setBgAutoPostingUser(u);
              JPA.save(c);
              return u;
            });
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    Beans.get(BgAutoPostingUserService.class).ensureCompanyPostingUsers();

    assertEquals(
        BgAutoPostingUserService.USER_CODE,
        tx(() -> config(empty).getBgAutoPostingUser().getCode()));
    assertEquals(own.getId(), tx(() -> config(chosen).getBgAutoPostingUser().getId()));
  }

  @Test
  @DisplayName("стартиращият listener създава потребителя и попълва настройката")
  void listener_ът() throws IOException {
    String listener =
        new String(
            Files.readAllBytes(
                Paths.get(
                    "src/main/java/com/axelor/apps/bglocalization/listener/"
                        + "BgLocalizationServerStartListener.java")),
            StandardCharsets.UTF_8);
    assertTrue(listener.contains("ensureServiceUser()"));
    assertTrue(listener.contains("ensureCompanyPostingUsers()"));
  }
}
