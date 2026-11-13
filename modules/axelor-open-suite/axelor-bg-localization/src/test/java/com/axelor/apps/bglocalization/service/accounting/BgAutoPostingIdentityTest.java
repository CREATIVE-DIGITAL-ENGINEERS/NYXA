package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Идентичността на служебния потребител (ИЗП): Shiro subject само за времето на една задача, в
 * try/finally. Проверено и срещу уеб SecurityManager-а — такъв е в продукция (pac4j), — и срещу
 * обикновения, какъвто е в тестовете на модула.
 */
class BgAutoPostingIdentityTest {

  @AfterEach
  void clean() {
    ThreadContext.remove();
  }

  private static Object principal() {
    return SecurityUtils.getSubject().getPrincipal();
  }

  private static void check(SecurityManager manager) throws Exception {
    SecurityUtils.setSecurityManager(manager);
    assertNull(principal(), "преди задачата нишката няма идентичност");
    String inside =
        BgAutoPostingIdentity.runAs(
            manager, "nyxa-auto-posting", () -> String.valueOf(principal()));
    assertEquals("nyxa-auto-posting", inside, "по време на задачата");
    assertNull(principal(), "след задачата — без идентичност");

    assertThrows(
        IllegalStateException.class,
        () ->
            BgAutoPostingIdentity.runAs(
                manager,
                "nyxa-auto-posting",
                () -> {
                  throw new IllegalStateException("грешка в задачата");
                }));
    assertNull(principal(), "и след изключение — без идентичност");
  }

  @Test
  @DisplayName(
      "уеб SecurityManager (продукция): идентичност само в задачата, и след изключение — няма")
  void уеб_мениджърът() throws Exception {
    check(new DefaultWebSecurityManager());
  }

  @Test
  @DisplayName("обикновен SecurityManager (тестове): същото")
  void обикновеният_мениджър() throws Exception {
    check(new DefaultSecurityManager());
  }

  @Test
  @DisplayName("без потребител (празна настройка) — задачата върви без идентичност, не като друг")
  void без_потребител() throws Exception {
    SecurityManager manager = new DefaultSecurityManager();
    SecurityUtils.setSecurityManager(manager);
    assertNull(BgAutoPostingIdentity.runAs(manager, null, () -> principal()));
  }
}
