package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.base.db.Company;
import com.axelor.auth.AuthService;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSecurity.AccessType;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Служебният потребител „Nyxa — автоматично осчетоводяване“ и настройката {@code
 * AccountConfig.bgAutoPostingUser}.
 *
 * <ul>
 *   <li>Създава се при стартиране, по importId; блокиран (без вход — {@code AuthRealm} отказва
 *       неактивен потребител) и с паролата на случаен низ.
 *   <li>Своя група само с правата, които осчетоводяването ползва: Move и MoveLine — четене,
 *       създаване, запис; счетоводните обекти — само четене. НИКОГА admin и без роли, тоест и без
 *       ролята за запис във временно затворен период.
 *   <li>Настройката на дружествата се попълва САМО ако е празна.
 * </ul>
 *
 * <p>Заварен потребител с този код, но без нашия importId, не се присвоява и не се пипа — чуждо е;
 * настройката тогава остава празна и изписването излиза с причина.
 */
public class BgAutoPostingUserService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String USER_CODE = "nyxa-auto-posting";
  public static final String USER_NAME = "Nyxa — автоматично осчетоводяване";
  public static final String USER_IMPORT_ID = "bg-nyxa-auto-posting-user";
  public static final String GROUP_CODE = "bg-auto-posting";
  public static final String GROUP_IMPORT_ID = "bg-nyxa-auto-posting-group";

  static final String MOVE = "com.axelor.apps.account.db.Move";
  static final String MOVE_LINE = "com.axelor.apps.account.db.MoveLine";
  static final String ACCOUNTING_READ = "com.axelor.apps.account.db.*";

  /** Служебният потребител — създаден, ако липсва. {@code null}, когато кодът е зает от чужд. */
  @Transactional(rollbackOn = Exception.class)
  public User ensureServiceUser() {
    User user = JPA.all(User.class).filter("self.importId = ?1", USER_IMPORT_ID).fetchOne();
    if (user != null) {
      return user;
    }
    if (JPA.all(User.class).filter("self.code = ?1", USER_CODE).count() > 0) {
      log.error(
          "A user with code {} exists but was not created by Nyxa; it is NOT used for automatic"
              + " posting. Fill AccountConfig.bgAutoPostingUser by hand.",
          USER_CODE);
      return null;
    }
    user = new User();
    user.setCode(USER_CODE);
    user.setName(USER_NAME);
    user.setImportId(USER_IMPORT_ID);
    user.setBlocked(true);
    user.setPassword(Beans.get(AuthService.class).encrypt(UUID.randomUUID().toString()));
    user.setGroup(ensureGroup());
    user = JPA.save(user);
    log.info("Created the service user {} for automatic posting.", USER_CODE);
    return user;
  }

  protected Group ensureGroup() {
    Group group = JPA.all(Group.class).filter("self.importId = ?1", GROUP_IMPORT_ID).fetchOne();
    if (group != null) {
      return group;
    }
    group = new Group();
    group.setCode(GROUP_CODE);
    group.setName(USER_NAME);
    group.setImportId(GROUP_IMPORT_ID);
    group.setTechnicalStaff(false);
    group.addPermission(permission("perm.bg.auto-posting.move", MOVE, true));
    group.addPermission(permission("perm.bg.auto-posting.move-line", MOVE_LINE, true));
    group.addPermission(permission("perm.bg.auto-posting.accounting-read", ACCOUNTING_READ, false));
    return JPA.save(group);
  }

  protected Permission permission(String name, String object, boolean write) {
    Permission p = JPA.all(Permission.class).filter("self.name = ?1", name).fetchOne();
    if (p != null) {
      return p;
    }
    p = new Permission();
    p.setName(name);
    p.setObject(object);
    p.setCanRead(true);
    p.setCanCreate(write);
    p.setCanWrite(write);
    p.setCanRemove(false);
    p.setCanImport(false);
    p.setCanExport(false);
    return JPA.save(p);
  }

  /** Попълва празната настройка на всички дружества. Връща колко са попълнени. */
  @Transactional(rollbackOn = Exception.class)
  public int ensureCompanyPostingUsers() {
    User user = ensureServiceUser();
    if (user == null) {
      return 0;
    }
    List<AccountConfig> empty =
        JPA.all(AccountConfig.class).filter("self.bgAutoPostingUser IS NULL").fetch();
    for (AccountConfig config : empty) {
      config.setBgAutoPostingUser(user);
      JPA.save(config);
    }
    return empty.size();
  }

  /** Попълва празната настройка на едно дружество — при активиране на политиката. */
  @Transactional(rollbackOn = Exception.class)
  public void ensureCompanyPostingUser(Company company) {
    AccountConfig config =
        JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
    if (config == null || config.getBgAutoPostingUser() != null) {
      return;
    }
    User user = ensureServiceUser();
    if (user != null) {
      config.setBgAutoPostingUser(user);
      JPA.save(config);
    }
  }

  /**
   * Защо потребителят не може да осчетоводява, или {@code null}. Вика се ПОД неговата идентичност
   * ({@link BgAutoPostingIdentity}) — правата ги решава Axelor ({@link JpaSecurity}).
   *
   * <p>„Неактивен“ тук значи изтрит (архивиран) или извън срока си. Блокирането не е отказ: то
   * спира само входа и точно така е затворен служебният потребител.
   */
  public static String refusal(Company company, User configured, User current) {
    String name = company == null ? "?" : company.getName();
    if (configured == null) {
      return String.format(
          "В счетоводната конфигурация на „%s“ няма „Потребител за автоматично осчетоводяване“ —"
              + " статията не се валидира. Попълнете го (по подразбиране служебният „%s“).",
          name, USER_NAME);
    }
    LocalDateTime now = LocalDateTime.now();
    if (Boolean.TRUE.equals(configured.getArchived())
        || (configured.getExpiresOn() != null && configured.getExpiresOn().isBefore(now))
        || (configured.getActivateOn() != null && configured.getActivateOn().isAfter(now))) {
      return String.format(
          "Потребителят за автоматично осчетоводяване „%s“ на „%s“ е неактивен (изтрит или извън"
              + " срока си). Изберете активен потребител в счетоводната конфигурация.",
          configured.getName(), name);
    }
    if (current == null || !configured.getId().equals(current.getId())) {
      return String.format(
          "Задачата не работи като потребителя за автоматично осчетоводяване „%s“ на „%s“ —"
              + " статията не се валидира от чуждо име.",
          configured.getName(), name);
    }
    JpaSecurity security = Beans.get(JpaSecurity.class);
    for (Class<? extends com.axelor.db.Model> model :
        List.<Class<? extends com.axelor.db.Model>>of(
            com.axelor.apps.account.db.Move.class, com.axelor.apps.account.db.MoveLine.class)) {
      if (!security.isPermitted(AccessType.CREATE, model)
          || !security.isPermitted(AccessType.WRITE, model)) {
        return String.format(
            "Потребителят за автоматично осчетоводяване „%s“ няма право да създава и записва"
                + " %s. Дайте му правата (групата „%s“ ги има) или изберете друг потребител.",
            configured.getName(), model.getSimpleName(), USER_NAME);
      }
    }
    return null;
  }
}
