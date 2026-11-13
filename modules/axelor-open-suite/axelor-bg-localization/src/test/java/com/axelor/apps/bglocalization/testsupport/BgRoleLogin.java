package com.axelor.apps.bglocalization.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

/**
 * ПОТРЕБИТЕЛ С РОЛЯ ОТ КАТАЛОГА — за интеграционни тестове, които минават през контролер зад {@code
 * CapabilityGate}.
 *
 * <p><b>Правата са производствените.</b> Редовете на ролята се четат от {@code
 * nyxa-security/nx_role.csv} и {@code nx_permission.csv} на classpath-а и се зареждат през {@code
 * security-config.xml} — същия {@link CSVImporter} и същия {@code
 * ImportPermission#importPermissionToRole}, които {@code RoleCatalogueImporter} пуска при всеки
 * старт. Филтрира се само до една роля: целият каталог е ~6 хил. реда и струва минути.
 *
 * <p><b>⚠️ Не администратор.</b> Администраторът (група {@code admins}) минава покрай всеки гейт и
 * всяко условие — тест с него не доказва, че ролята стига. Потребителят тук има точно поисканите
 * роли, активно дружество и {@code companySet} с него: условията в каталога са по {@code
 * __user__.activeCompany}.
 *
 * <p><b>⚠️ Субект, закачен на нишката, а не вход с парола:</b> realm-ът на инсталацията
 * (Keycloak/Entra) не е предмет тук; {@code AuthUtils#getUser} чете само принципала. {@link
 * #logout()} е ЗАДЪЛЖИТЕЛЕН след всеки тест — интеграционните класове вървят в една JVM.
 */
public final class BgRoleLogin {

  /** Най-тясната роля, която минава гейта {@code Capabilities.PAYROLL_WRITE}. */
  public static final String PAYROLL_WRITE = "hr.payroll.write";

  /**
   * Админ write: пише навсякъде, само в активното дружество. Гейтовете в кода са по способност
   * ({@code Capabilities.*_WRITE}), не по име на роля — тази роля е във всяка от тях.
   */
  public static final String WRITE_ROLE = "all.write";

  /** Админ read: чете навсякъде в активното дружество, не пише нищо. */
  public static final String READ_ROLE = "all.read";

  private static final String DIR = "nyxa-security/";
  private static final String CONFIG = "security-config.xml";

  private BgRoleLogin() {}

  /** Вход с {@link #WRITE_ROLE}. */
  public static void loginWrite(Company company) {
    login(company, WRITE_ROLE);
  }

  /** Вход с {@link #WRITE_ROLE} и други фирми в {@code companySet} освен активната. */
  public static void loginWrite(Company company, Set<Company> others) {
    login(company, others, WRITE_ROLE);
  }

  /** Вход с {@link #READ_ROLE}. */
  public static void loginRead(Company company) {
    login(company, READ_ROLE);
  }

  /**
   * Потребител с ролите (заредени от каталога, ако още ги няма), закачен на нишката.
   *
   * @param company активното дружество — условията на каталога са по него
   * @param roles имената на ролите от каталога; празно — потребител БЕЗ роля
   */
  public static void login(Company company, String... roles) {
    login(company, java.util.Collections.<Company>emptySet(), roles);
  }

  /**
   * Същото, но с ДРУГИ фирми в {@code companySet} освен активната — за изолацията: потребителят има
   * права за няколко дружества, а вижда само активното.
   *
   * @param company активното дружество; {@code null} — потребител БЕЗ активно дружество
   * @param others другите фирми в профила
   */
  public static void login(Company company, Set<Company> others, String... roles) {
    Long id = createUser(company, others, "Тест " + String.join(", ", roles), roles);
    loginExisting(JPA.em().find(User.class, id).getCode());
  }

  /** Потребител с ролите и активното дружество — без вход (напр. деклараторът и неговият КЕП). */
  public static Long createUser(Company company, String name, String... roles) {
    return createUser(company, java.util.Collections.<Company>emptySet(), name, roles);
  }

  private static Long createUser(
      Company company, Set<Company> others, String name, String... roles) {
    for (String role : roles) {
      grant(role);
    }
    String code = "it-" + System.nanoTime();
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          User user = new User(code, name);
          user.setPassword("x-it"); // задължителна, ≥ 4 знака (PG валидира); входът е през субекта
          user.setCompanySet(new HashSet<>());
          if (company != null) {
            Company active = JPA.em().find(Company.class, company.getId());
            user.setActiveCompany(active);
            user.getCompanySet().add(active);
          }
          for (Company other : others) {
            user.getCompanySet().add(JPA.em().find(Company.class, other.getId()));
          }
          user.setRoles(new HashSet<>());
          for (String role : roles) {
            user.getRoles().add(role(role));
          }
          JPA.em().persist(user);
          id[0] = user.getId();
        });
    return id[0];
  }

  /** Закача на нишката съществуващ потребител по код. */
  public static void loginExisting(String code) {
    bind(code);
  }

  private static void bind(String code) {
    Subject subject =
        new Subject.Builder(SecurityUtils.getSecurityManager())
            .principals(new SimplePrincipalCollection(code, "it"))
            .authenticated(true)
            .buildSubject();
    ThreadContext.bind(subject);
  }

  /**
   * Технически администратор (група {@code admins}) с активно дружество — за доказателства, че едно
   * правило важи и за него: admin минава покрай всяко условие на каталога, така че отказ, който го
   * спира, идва от кода, а не от правата.
   */
  public static void loginAdmin(Company company) {
    String code = "it-admin-" + System.nanoTime();
    JPA.runInTransaction(
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
          User user = new User(code, "Тест admin");
          user.setPassword("x-it");
          user.setGroup(admins);
          Company active = JPA.em().find(Company.class, company.getId());
          user.setActiveCompany(active);
          user.setCompanySet(new HashSet<>());
          user.getCompanySet().add(active);
          JPA.em().persist(user);
        });
    bind(code);
  }

  /** Откача субекта от нишката. */
  public static void logout() {
    ThreadContext.unbindSubject();
  }

  private static Role role(String name) {
    return JPA.em()
        .createQuery("SELECT r FROM Role r WHERE r.name = :n", Role.class)
        .setParameter("n", name)
        .getSingleResult();
  }

  /** Ролята и правата ѝ от каталога — веднъж на схема. */
  private static synchronized void grant(String role) {
    long present =
        JPA.em()
            .createQuery("SELECT COUNT(r) FROM Role r WHERE r.name = :n", Long.class)
            .setParameter("n", role)
            .getSingleResult();
    if (present > 0) {
      return;
    }
    try {
      Path dir = Files.createTempDirectory("bg-role-login-");
      String config = text(CONFIG);
      Files.write(
          dir.resolve(CONFIG),
          withoutInput(config, "nx_group.csv").getBytes(StandardCharsets.UTF_8));
      Files.write(
          dir.resolve("nx_role.csv"),
          rowsOf(text("nx_role.csv"), "\"" + role + "\";").getBytes(StandardCharsets.UTF_8));
      String permissions = rowsEndingWith(text("nx_permission.csv"), ";\"" + role + "\"");
      assertTrue(permissions.split("\n").length > 1, "каталогът дава права на " + role);
      Files.write(dir.resolve("nx_permission.csv"), permissions.getBytes(StandardCharsets.UTF_8));

      CSVImporter importer = new CSVImporter(dir.resolve(CONFIG).toString(), dir.toString(), null);
      List<String> errors = new ArrayList<>();
      importer.addListener(
          new Listener() {
            @Override
            public void imported(Model bean) {}

            @Override
            public void imported(Integer total, Integer success) {
              if (total != null && success != null && !total.equals(success)) {
                errors.add((total - success) + " от " + total + " реда");
              }
            }

            @Override
            public void handle(Model bean, Exception e) {
              errors.add(String.valueOf(bean) + ": " + e);
            }
          });
      importer.run();
      assertEquals(new ArrayList<String>(), errors, "ролята " + role + " се зарежда без грешки");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(String name) throws IOException {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(DIR + name)) {
      assertNotNull(in, DIR + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Конфигурацията без един вход (групите не са нужни: ролята се дава пряко на потребителя). */
  private static String withoutInput(String config, String file) {
    Matcher m =
        Pattern.compile(
                "\\s*<input file=\"" + Pattern.quote(file) + "\"[^>]*?(?:/>|[^/]>.*?</input>)",
                Pattern.DOTALL)
            .matcher(config);
    assertTrue(m.find(), file);
    return m.replaceFirst("");
  }

  private static String rowsOf(String csv, String prefix) {
    List<String> lines = csv.lines().collect(Collectors.toList());
    return lines.get(0)
        + "\n"
        + lines.stream().skip(1).filter(l -> l.startsWith(prefix)).collect(Collectors.joining("\n"))
        + "\n";
  }

  private static String rowsEndingWith(String csv, String suffix) {
    List<String> lines = csv.lines().collect(Collectors.toList());
    return lines.get(0)
        + "\n"
        + lines.stream().skip(1).filter(l -> l.endsWith(suffix)).collect(Collectors.joining("\n"))
        + "\n";
  }
}
