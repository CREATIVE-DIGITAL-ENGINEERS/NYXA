package com.axelor.apps.nyxasecurity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.nyxasecurity.service.RoleCatalogueRetirement.Entry;
import com.axelor.apps.nyxasecurity.service.RoleCatalogueRetirement.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleCatalogueRetirementTest {

  @Test
  void parsesEveryKind() throws IOException {
    List<Entry> entries =
        parse(
            "\"kind\";\"target\";\"from\"\n"
                + "\"permission-role\";\"nx.x.r\";\"ACC Read\"\n"
                + "\"role-group\";\"ACC Read\";\"staff\"\n"
                + "\"role-menu\";\"ACC Read\";\"account-root\"\n"
                + "\"permission\";\"nx.y.r\";\"\"\n"
                + "\"role\";\"ACC Read\";\"\"\n"
                + "\"\";\"\";\"\"\n");
    assertEquals(5, entries.size());
    assertEquals(Kind.PERMISSION_ROLE, entries.get(0).kind);
    assertEquals("ACC Read", entries.get(0).from);
    assertEquals(Kind.ROLE_GROUP, entries.get(1).kind);
    assertEquals(Kind.ROLE_MENU, entries.get(2).kind);
    assertEquals(Kind.PERMISSION, entries.get(3).kind);
    assertEquals("", entries.get(3).from);
    assertEquals(Kind.ROLE, entries.get(4).kind);
    assertEquals("ACC Read", entries.get(4).target);
  }

  @Test
  void rejectsUnknownKind() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("\"kind\";\"target\";\"from\"\n\"role-user\";\"ACC Read\";\"jane\"\n"));
  }

  @Test
  void rejectsMissingFrom() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("\"kind\";\"target\";\"from\"\n\"permission-role\";\"nx.x.r\";\"\"\n"));
  }

  @Test
  void shippedFileParses() throws IOException {
    // The file in the jar; a bad entry would otherwise only surface as a boot-time error.
    RoleCatalogueRetirement.read();
  }

  private static List<Entry> parse(String csv) throws IOException {
    return RoleCatalogueRetirement.parse(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
  }
}
