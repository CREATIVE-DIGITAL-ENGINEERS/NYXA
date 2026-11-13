package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Всеки изглед на модула минава ПРЕЗ СХЕМАТА на Axelor, преди да стигне до база.
 *
 * <p><b>Защо съществува.</b> {@code ViewLoader} чете файловете един по един и при невалиден
 * изхвърля ЦЕЛИЯ файл, като оставя един ред в лога: „Error while loading …“ — без стек, без име на
 * елемента, без причина. Останалите файлове се зареждат нормално, boot-ът минава, приложението
 * работи, а няколко екрана просто ги няма. Точно това се случи с {@code bg-compensation-views.xml}:
 * атрибут {@code color="secondary"} на {@code hilite} не е в изброените стойности на {@code
 * HiliteStyle} и отнесе със себе си два изгледа, шест действия и едно меню.
 *
 * <p>Това е СЪЩИЯТ клас грешка като „extension без id“, само че по-широк: там се губи разширението,
 * тук — целият файл. Затова проверката е тук, а не в нечия памет.
 *
 * <p>Схемата се взима от {@code object-views.xsd} в axelor-core — тоест от същата версия, срещу
 * която ще зарежда и сървърът, а не от копие в репото, което остарява.
 */
class BgViewSchemaTest {

  @Test
  @DisplayName("Всеки XML в views/ е валиден срещу object-views.xsd")
  void everyViewFileValidatesAgainstTheSchema() throws Exception {
    Path viewsDir = Paths.get("src/main/resources/views");
    assertTrue(Files.isDirectory(viewsDir), "не намирам " + viewsDir.toAbsolutePath());

    Schema schema;
    try (InputStream xsd = getClass().getResourceAsStream("/object-views.xsd")) {
      if (xsd == null) {
        fail("object-views.xsd не е в classpath-а — без него проверката е безсмислена");
      }
      schema =
          SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
              .newSchema(new StreamSource(xsd));
    }

    List<String> broken = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> walk = Files.list(viewsDir)) {
      files = walk.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
    }
    assertTrue(files.size() > 10, "твърде малко файлове — сгрешена ли е папката?");

    for (Path p : files) {
      Validator v = schema.newValidator();
      try {
        v.validate(new StreamSource(new File(p.toString())));
      } catch (Exception e) {
        broken.add(p.getFileName() + " → " + e.getMessage());
      }
    }

    if (!broken.isEmpty()) {
      fail(
          "невалидни изгледи ("
              + broken.size()
              + " от "
              + files.size()
              + "). ⚠️ Такъв файл се изхвърля ЦЯЛ при зареждане, с един ред в лога:\n  "
              + String.join("\n  ", broken));
    }
  }
}
