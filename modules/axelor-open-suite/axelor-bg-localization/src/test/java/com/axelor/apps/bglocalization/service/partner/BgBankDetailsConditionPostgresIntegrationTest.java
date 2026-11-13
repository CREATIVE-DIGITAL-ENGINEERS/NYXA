package com.axelor.apps.bglocalization.service.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.db.User;
import com.axelor.db.JpaScanner;
import com.axelor.db.hibernate.dialect.CustomDialectResolver;
import com.axelor.db.hibernate.naming.ImplicitNamingStrategyImpl;
import com.axelor.db.hibernate.naming.PhysicalNamingStrategyImpl;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.collection.QueryableCollection;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * УСЛОВИЕТО ЗА БАНКОВИТЕ СМЕТКИ — старото (264 знака) и новото (252 знака) виждат едни и същи
 * редове, мерено в PostgreSQL.
 *
 * <p>Новото сравнява партньора като обект ({@code self.partner in (select p ...)}) вместо по {@code
 * .id}; смисълът е същият, а условието се събира в колоната {@code condition_value} (виж {@code
 * RoleCatalogueFilesTest#conditionsFitTheColumn}). Новото се чете от {@code nx_permission.csv} —
 * т.е. мери се това, което се внася, а не копие.
 *
 * <p>Стартът е истинският Hibernate с настройките на {@code JpaModule} срещу СОБСТВЕНА временна
 * PostgreSQL база (създава се и се трие тук; без база — пропуснат с причина). Пълна схема на целия
 * модел не се вдига: Hibernate пише DDL-а си в скрипт и от него се пускат само таблиците, които
 * заявката чете. Двете условия минават като JPQL през Hibernate, така че се сравнява истинският
 * превод в SQL, а не ръчно написан. Данните са измислени: само идентификатори.
 */
@Tag("integration")
class BgBankDetailsConditionPostgresIntegrationTest {

  /** Старото условие (264 знака), с параметри {@code company, company, company, user}. */
  static final String OLD_CONDITION =
      "self.company = ? or self.partner.id in (select p.id from Partner p join p.companySet c"
          + " where c = ?) or self.partner.id in (select e.contactPartner.id from Employee e"
          + " left join e.mainEmploymentContract m where m.payCompany = ? or (m.id is null and"
          + " e.createdBy = ?))";

  private static final String CATALOGUE = "nyxa-security/nx_permission.csv";
  private static final String BANK_DETAILS = BankDetails.class.getName();

  // Измислени идентификатори.
  private static final long COMPANY_A = 1, COMPANY_B = 2, COMPANY_EMPTY = 3;
  private static final long USER_ME = 10, USER_OTHER = 11, USER_NOBODY = 12;

  private static String scratch;
  private static String baseUrl;
  private static EntityManagerFactory emf;
  private static String newCondition;
  private static String newParams;

  @BeforeAll
  static void setUp() throws Exception {
    readCatalogue();
    baseUrl = BgTestDb.url();
    scratch = "nyxa_bank_condition_it_" + System.nanoTime();
    try (Connection admin = connect(baseUrl);
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE " + scratch + " TEMPLATE template0");
    } catch (SQLException e) {
      scratch = null;
      BgSkip.because("временна PostgreSQL база не може да се създаде: " + e.getMessage());
    }
    Path ddl = Files.createTempFile("bank-condition-ddl", ".sql");
    try {
      emf = boot(ddl);
      try (Connection c = connect(scratchUrl())) {
        createTables(c, Files.readAllLines(ddl, StandardCharsets.UTF_8));
        seed(c);
      }
    } finally {
      Files.deleteIfExists(ddl);
    }
  }

  @AfterAll
  static void tearDown() throws SQLException {
    if (emf != null) {
      emf.close();
    }
    if (scratch == null || baseUrl == null) {
      return;
    }
    try (Connection admin = connect(baseUrl);
        Statement st = admin.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + scratch + " WITH (FORCE)");
    }
  }

  @Test
  @DisplayName("новото условие от каталога е по-късо от колоната и с параметрите на старото")
  void newConditionIsTheCatalogueOne() {
    assertNotEquals(OLD_CONDITION, newCondition);
    assertEquals(264, OLD_CONDITION.length());
    assertTrue(newCondition.length() <= 255, "дължина " + newCondition.length());
    assertEquals(
        "__user__.activeCompany,__user__.activeCompany,__user__.activeCompany,__user__", newParams);
  }

  @Test
  @DisplayName("дружество А, моят потребител: точно очакваните сметки и при двете условия")
  void companyAndMe() {
    Set<Long> expected =
        new TreeSet<>(
            List.of(
                1000L, // сметка на дружеството, без партньор
                1002L, // партньор на дружеството
                1004L, // партньор на две дружества, едното е А
                1005L, // служител с договор в А
                1007L, // служител без договор, създаден от мен
                1012L, // чуждо дружество, но партньор на А
                1013L)); // на А, с партньор на друго дружество
    assertEquals(expected, visible(OLD_CONDITION, COMPANY_A, USER_ME), "старото условие");
    assertEquals(expected, visible(newCondition, COMPANY_A, USER_ME), "новото условие");
  }

  @Test
  @DisplayName("всяка комбинация дружество × потребител: старото и новото виждат същото")
  void sameRowsForEveryPerspective() {
    for (long company : List.of(COMPANY_A, COMPANY_B, COMPANY_EMPTY)) {
      for (long user : List.of(USER_ME, USER_OTHER, USER_NOBODY)) {
        Set<Long> old = visible(OLD_CONDITION, company, user);
        Set<Long> now = visible(newCondition, company, user);
        assertEquals(old, now, "дружество " + company + ", потребител " + user);
      }
    }
    // Не е празно сравнение: другото дружество вижда своето, празното — нищо.
    assertEquals(
        new TreeSet<>(List.of(1001L, 1003L, 1004L, 1006L, 1008L, 1009L, 1012L, 1013L)),
        visible(newCondition, COMPANY_B, USER_OTHER));
    assertEquals(Set.of(), visible(newCondition, COMPANY_EMPTY, USER_NOBODY));
  }

  @Test
  @DisplayName(
      "новото не съединява self с партньора извън подзаявките (сметка без партньор остава)")
  void noImplicitJoinOnSelf() {
    String sql = sql(newCondition);
    System.out.println("SQL на новото условие: " + sql);
    System.out.println("SQL на старото условие: " + sql(OLD_CONDITION));
    String outer = sql.substring(0, sql.indexOf(" where "));
    assertFalse(outer.contains(" join ") || outer.contains(","), "външното FROM: " + outer);
  }

  // ── Заявката ─────────────────────────────────────────────────────────────────────────────────

  /** Както AuthSecurity: параметрите от conditionParams, всяко {@code ?} — поредното. */
  private static Set<Long> visible(String condition, long company, long user) {
    EntityManager em = emf.createEntityManager();
    try {
      TypedQuery<Long> q = em.createQuery(jpql(condition), Long.class);
      String[] params = newParams.split(",");
      for (int i = 0; i < params.length; i++) {
        Object value =
            "__user__".equals(params[i].trim())
                ? em.getReference(User.class, user)
                : em.getReference(Company.class, company);
        q.setParameter(i + 1, value);
      }
      return new TreeSet<>(q.getResultList());
    } finally {
      em.close();
    }
  }

  private static String jpql(String condition) {
    StringBuilder numbered = new StringBuilder();
    int n = 0;
    for (char ch : condition.toCharArray()) {
      numbered.append(ch);
      if (ch == '?') {
        numbered.append(++n);
      }
    }
    return "select self.id from BankDetails self where (" + numbered + ")";
  }

  private static String sql(String condition) {
    SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
    String[] sql =
        sf.getQueryPlanCache()
            .getHQLQueryPlan(jpql(condition), false, Collections.emptyMap())
            .getSqlStrings();
    assertEquals(1, sql.length);
    return sql[0];
  }

  private static void readCatalogue() throws IOException {
    Set<String> conditions = new LinkedHashSet<>();
    Set<String> params = new LinkedHashSet<>();
    int rows = 0;
    try (InputStream in =
            Thread.currentThread().getContextClassLoader().getResourceAsStream(CATALOGUE);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    Objects.requireNonNull(in, CATALOGUE), StandardCharsets.UTF_8))) {
      List<String> header = List.of(cells(reader.readLine()));
      int object = header.indexOf("object");
      int condition = header.indexOf("condition");
      int conditionParams = header.indexOf("conditionParams");
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        String[] c = cells(line);
        if (c.length > conditionParams && BANK_DETAILS.equals(c[object])) {
          rows++;
          conditions.add(c[condition]);
          params.add(c[conditionParams]);
        }
      }
    }
    assertTrue(rows > 0, "няма права за " + BANK_DETAILS);
    assertEquals(1, conditions.size(), "всички права за BankDetails с едно условие: " + conditions);
    assertEquals(1, params.size(), "и с едни параметри: " + params);
    newCondition = conditions.iterator().next();
    newParams = params.iterator().next();
  }

  private static String[] cells(String line) {
    String[] cells = line.split(";", -1);
    for (int i = 0; i < cells.length; i++) {
      cells[i] = cells[i].trim().replaceAll("^\"|\"$", "");
    }
    return cells;
  }

  // ── Базата ───────────────────────────────────────────────────────────────────────────────────

  /**
   * Hibernate с настройките на JpaModule, без ddl към базата: DDL-ът на целия модел отива в скрипт,
   * от който {@link #createTables} пуска само нужните таблици.
   */
  private static EntityManagerFactory boot(Path ddl) {
    Map<String, Object> p = new HashMap<>();
    p.put(AvailableSettings.SCANNER, JpaScanner.class.getName());
    p.put(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true");
    p.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, ImplicitNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.DIALECT_RESOLVERS, CustomDialectResolver.class.getName());
    p.put(AvailableSettings.JPA_JDBC_DRIVER, "org.postgresql.Driver");
    p.put(AvailableSettings.JPA_JDBC_URL, scratchUrl());
    p.put(AvailableSettings.JPA_JDBC_USER, BgTestDb.user());
    p.put(AvailableSettings.JPA_JDBC_PASSWORD, BgTestDb.password());
    p.put(AvailableSettings.HBM2DDL_AUTO, "none");
    p.put(AvailableSettings.HBM2DDL_DATABASE_ACTION, "none");
    p.put(AvailableSettings.HBM2DDL_SCRIPTS_ACTION, "create");
    p.put(AvailableSettings.HBM2DDL_SCRIPTS_CREATE_TARGET, ddl.toString());
    p.put(AvailableSettings.HBM2DDL_DELIMITER, ";");
    p.put("javax.persistence.validation.mode", "none");
    return Persistence.createEntityManagerFactory("testUnit", p);
  }

  private static AbstractEntityPersister persister(Class<?> entity) {
    return (AbstractEntityPersister)
        emf.unwrap(SessionFactoryImplementor.class).getMetamodel().entityPersister(entity);
  }

  private static QueryableCollection companySet() {
    return (QueryableCollection)
        emf.unwrap(SessionFactoryImplementor.class)
            .getMetamodel()
            .collectionPersister(Partner.class.getName() + ".companySet");
  }

  private static String table(Class<?> entity) {
    return persister(entity).getTableName();
  }

  private static String col(Class<?> entity, String property) {
    return persister(entity).getPropertyColumnNames(property)[0];
  }

  private static String id(Class<?> entity) {
    return persister(entity).getIdentifierColumnNames()[0];
  }

  /** Само таблиците на заявката, от DDL-а на Hibernate; без NOT NULL извън ключа. */
  private static void createTables(Connection c, List<String> ddl) throws SQLException {
    List<String> tables = new ArrayList<>();
    for (Class<?> e :
        List.of(
            BankDetails.class,
            Partner.class,
            Company.class,
            Employee.class,
            EmploymentContract.class)) {
      tables.add(table(e));
    }
    tables.add(companySet().getTableName());
    try (Statement st = c.createStatement()) {
      for (String table : tables) {
        String create =
            ddl.stream()
                .filter(l -> l.startsWith("create table " + table + " ("))
                .findFirst()
                .orElse(null);
        assertNotNull(create, "DDL-ът на Hibernate няма таблица " + table);
        st.execute(create.replaceAll(";\\s*$", ""));
        List<String> notNull = new ArrayList<>();
        try (ResultSet rs =
            st.executeQuery(
                "SELECT a.attname FROM pg_attribute a WHERE a.attrelid = '"
                    + table
                    + "'::regclass AND a.attnum > 0 AND a.attnotnull AND NOT EXISTS (SELECT 1"
                    + " FROM pg_index i WHERE i.indrelid = a.attrelid AND i.indisprimary"
                    + " AND a.attnum = ANY(i.indkey))")) {
          while (rs.next()) {
            notNull.add(rs.getString(1));
          }
        }
        for (String column : notNull) {
          st.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
        }
      }
    }
  }

  /**
   * Измислени данни, само идентификатори. Партньори: 100 на А; 101 на Б; 102 на А и Б; 103 служител
   * с договор в А; 104 служител с договор в Б; 105 служител без договор, създаден от мен; 106
   * служител без договор, създаден от друг; 107 служител с договор в Б, създаден от мен; 108 без
   * дружество и без служител; 109 служител с договор без плащащо дружество, създаден от мен.
   * Отделно служител без партньор, без договор, създаден от мен.
   */
  private static void seed(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (long company : List.of(COMPANY_A, COMPANY_B, COMPANY_EMPTY)) {
        insert(st, table(Company.class), new String[] {id(Company.class)}, company);
      }
      for (long partner = 100; partner <= 109; partner++) {
        insert(st, table(Partner.class), new String[] {id(Partner.class)}, partner);
      }
      QueryableCollection set = companySet();
      String[] setColumns = {set.getKeyColumnNames()[0], set.getElementColumnNames()[0]};
      insert(st, set.getTableName(), setColumns, 100L, COMPANY_A);
      insert(st, set.getTableName(), setColumns, 101L, COMPANY_B);
      insert(st, set.getTableName(), setColumns, 102L, COMPANY_A);
      insert(st, set.getTableName(), setColumns, 102L, COMPANY_B);

      String[] contract = {
        id(EmploymentContract.class), col(EmploymentContract.class, "payCompany")
      };
      insert(st, table(EmploymentContract.class), contract, 300L, COMPANY_A);
      insert(st, table(EmploymentContract.class), contract, 301L, COMPANY_B);
      insert(st, table(EmploymentContract.class), contract, 302L, COMPANY_B);
      insert(st, table(EmploymentContract.class), contract, 303L, null);

      String[] employee = {
        id(Employee.class),
        col(Employee.class, "contactPartner"),
        col(Employee.class, "mainEmploymentContract"),
        col(Employee.class, "createdBy")
      };
      insert(st, table(Employee.class), employee, 203L, 103L, 300L, USER_OTHER);
      insert(st, table(Employee.class), employee, 204L, 104L, 301L, USER_ME);
      insert(st, table(Employee.class), employee, 205L, 105L, null, USER_ME);
      insert(st, table(Employee.class), employee, 206L, 106L, null, USER_OTHER);
      insert(st, table(Employee.class), employee, 207L, 107L, 302L, USER_ME);
      insert(st, table(Employee.class), employee, 209L, 109L, 303L, USER_ME);
      insert(st, table(Employee.class), employee, 210L, null, null, USER_ME);

      String[] bank = {
        id(BankDetails.class), col(BankDetails.class, "company"), col(BankDetails.class, "partner")
      };
      Object[][] accounts = {
        {1000L, COMPANY_A, null},
        {1001L, COMPANY_B, null},
        {1002L, null, 100L},
        {1003L, null, 101L},
        {1004L, null, 102L},
        {1005L, null, 103L},
        {1006L, null, 104L},
        {1007L, null, 105L},
        {1008L, null, 106L},
        {1009L, null, 107L},
        {1010L, null, 108L},
        {1011L, null, null},
        {1012L, COMPANY_B, 100L},
        {1013L, COMPANY_A, 101L},
        {1014L, null, 109L}
      };
      for (Object[] account : accounts) {
        insert(st, table(BankDetails.class), bank, account);
      }
    }
  }

  private static void insert(Statement st, String table, String[] columns, Object... values)
      throws SQLException {
    List<String> literals = new ArrayList<>();
    for (Object v : values) {
      literals.add(v == null ? "NULL" : String.valueOf(v));
    }
    st.execute(
        "INSERT INTO "
            + table
            + " ("
            + String.join(", ", columns)
            + ") VALUES ("
            + String.join(", ", literals)
            + ")");
  }

  private static Connection connect(String url) throws SQLException {
    try {
      return DriverManager.getConnection(url, BgTestDb.user(), BgTestDb.password());
    } catch (SQLException e) {
      BgSkip.because("PostgreSQL не е достъпна (" + url + "): " + e.getMessage());
      throw e;
    }
  }

  private static String scratchUrl() {
    int slash = baseUrl.lastIndexOf('/');
    int query = baseUrl.indexOf('?', slash);
    return baseUrl.substring(0, slash + 1) + scratch + (query < 0 ? "" : baseUrl.substring(query));
  }
}
