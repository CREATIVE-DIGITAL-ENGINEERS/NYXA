package com.axelor.apps.bglocalization.service.accounting;

import java.util.concurrent.Callable;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.UnavailableSecurityManagerException;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.DelegatingSubject;
import org.apache.shiro.subject.support.SubjectThreadState;

/**
 * Идентичността на потребителя за автоматично осчетоводяване за времето на ЕДНА задача.
 *
 * <p>Задачите на Quartz нямат влязъл потребител, а Axelor тогава пада обратно на „admin“ ({@code
 * UserServiceImpl.getUser}) — или отказва периода, ако admin е преименуван или изключен. Тук
 * нишката получава Shiro subject с главницата на потребителя от настройката: {@code
 * AuthUtils.getUser()} го намира по кода, тоест и проверката на периода, и одитът ({@code
 * createdBy}/{@code updatedBy}) виждат него.
 *
 * <p>⚠️ Само за времето на задачата, в try/finally: {@link SubjectThreadState#restore()} връща
 * нишката както е била — и след изключение. Образецът е {@code WebSocketSecurityInterceptor} на
 * платформата. Без вход и без сесия: subject-ът е построен с главница, не е удостоверен с парола,
 * тоест блокираният служебен потребител работи, но не може да влезе.
 */
public final class BgAutoPostingIdentity {

  /** Името на „реалма“ в главницата — само етикет; AuthUtils чете кода. */
  static final String REALM = "nyxa-auto-posting";

  private BgAutoPostingIdentity() {}

  /** С мениджъра на приложението (сложен от {@code AuthModule.Initializer}). */
  public static <T> T runAs(String userCode, Callable<T> work) throws Exception {
    SecurityManager manager;
    try {
      manager = SecurityUtils.getSecurityManager();
    } catch (UnavailableSecurityManagerException e) {
      manager = null;
    }
    return runAs(manager, userCode, work);
  }

  /**
   * Изпълнява задачата като потребителя с този код. Без код (празна настройка) — без идентичност:
   * НИКОГА като друг потребител; валидирането тогава отказва с причина.
   */
  public static <T> T runAs(SecurityManager manager, String userCode, Callable<T> work)
      throws Exception {
    if (userCode == null || manager == null) {
      return work.call();
    }
    // DelegatingSubject пряко, а не Subject.Builder: buildSubject() записва главницата в сесия
    // (DefaultSubjectDAO), а сесия тук няма и не бива да има — обикновеният SecurityManager гърми
    // с DisabledSessionException, а с разрешени сесии всяка задача би оставяла по една.
    Subject subject =
        new DelegatingSubject(
            new SimplePrincipalCollection(userCode, REALM), true, null, null, false, manager);
    SubjectThreadState state = new SubjectThreadState(subject);
    state.bind();
    try {
      return work.call();
    } finally {
      state.restore();
    }
  }
}
