package com.axelor.apps.nyxasecurity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * The platform validates view XML at startup, and an invalid attribute breaks view loading: the
 * whole re-import fails and no view or menu change of any module reaches the database. Check this
 * module's views against the same schema at build time instead.
 *
 * <p>Every file under {@code src/main/resources/views} is checked. It used to be a fixed list, so a
 * new view was never validated: a {@code priority} attribute on a menu item (not in the 7.4 schema)
 * passed here and then failed the re-import.
 */
class ViewSchemaTest {

  private static final Path VIEWS = Paths.get("src", "main", "resources", "views");

  @Test
  void viewsAreValid() throws Exception {
    List<Path> views = views();
    assertFalse(views.isEmpty(), "no views found under " + VIEWS.toAbsolutePath());
    for (Path view : views) {
      validate(view);
    }
  }

  private static List<Path> views() throws IOException {
    try (Stream<Path> files = Files.list(VIEWS)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".xml"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private void validate(Path view) throws Exception {
    URL xsd = getClass().getClassLoader().getResource("object-views.xsd");
    assertNotNull(xsd, "object-views.xsd not on the test classpath");

    Validator validator =
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd).newValidator();
    validator.validate(new StreamSource(view.toFile()));
  }
}
