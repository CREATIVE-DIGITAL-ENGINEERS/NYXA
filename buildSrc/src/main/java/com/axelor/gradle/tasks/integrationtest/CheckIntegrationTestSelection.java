package com.axelor.gradle.tasks.integrationtest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fails the build when the source-based selection of {@link IntegrationTestSources} disagrees with
 * what JUnit would actually treat as tagged.
 *
 * <p>It reads the COMPILED test classes, so it sees what the source scan cannot: a tag value coming
 * from a constant (javac inlines it into the annotation), a meta-annotation carrying the tag, a tag
 * inherited from a class or interface outside the test sources, and tagged nested classes. Both
 * directions are errors: a tagged class missing from the selection would silently never run, and a
 * selected class that is not tagged costs a JVM fork for nothing.
 */
public abstract class CheckIntegrationTestSelection extends DefaultTask {

  private static final String TAG = "Lorg/junit/jupiter/api/Tag;";
  private static final String TAGS = "Lorg/junit/jupiter/api/Tags;";
  private static final int NOT_CONCRETE =
      Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM;

  /** Compiled test classes of the module. */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getClassesDirs();

  /** Where superclasses, interfaces and annotation types outside the test classes are looked up. */
  @Classpath
  public abstract ConfigurableFileCollection getClasspath();

  @Input
  public abstract Property<String> getTag();

  /** The fully qualified names that {@code integrationTest} was configured to run. */
  @Input
  public abstract ListProperty<String> getSelectedClasses();

  /** The checked selection, one class per line: an up-to-date marker and a readable record. */
  @OutputFile
  public abstract RegularFileProperty getReport();

  private final Map<String, ClassInfo> cache = new HashMap<>();
  private final Map<String, Boolean> tagged = new HashMap<>();
  private URLClassLoader lookup;

  @TaskAction
  public void check() throws IOException {
    String tag = getTag().get();
    Set<String> selected = new TreeSet<>(getSelectedClasses().get());
    Map<String, ClassInfo> testClasses = readTestClasses();

    List<URL> urls = new ArrayList<>();
    for (File file : getClasspath().getFiles()) {
      urls.add(toUrl(file));
    }
    List<String> errors = new ArrayList<>();
    try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
      lookup = loader;

      for (ClassInfo info : testClasses.values()) {
        if ((info.access & NOT_CONCRETE) != 0 || !isTagged(info.name, tag, new HashSet<>())) {
          continue;
        }
        String fqn = info.name.replace('/', '.');
        if (info.outerName == null) {
          if (!selected.contains(fqn)) {
            errors.add(
                fqn
                    + " is tagged \""
                    + tag
                    + "\" but integrationTest does not select it, so it would never run. Put"
                    + " @Tag(\""
                    + tag
                    + "\") literally on the class or on a base class in the same module.");
          }
        } else {
          String outer = fqn.substring(0, fqn.indexOf('$'));
          if (info.isStaticNested || !selected.contains(outer)) {
            errors.add(
                fqn
                    + " is a tagged nested class; integrationTest only runs it as a @Nested (inner)"
                    + " class of a selected top-level class.");
          }
        }
      }

      for (String fqn : selected) {
        ClassInfo info = testClasses.get(fqn.replace('.', '/'));
        if (info == null) {
          errors.add(fqn + " is selected for integrationTest but was not compiled.");
        } else if ((info.access & NOT_CONCRETE) != 0) {
          errors.add(fqn + " is selected for integrationTest but is not a concrete class.");
        } else if (!isTagged(info.name, tag, new HashSet<>())) {
          errors.add(
              fqn + " is selected for integrationTest but is not tagged \"" + tag + "\".");
        }
      }
    } finally {
      lookup = null;
    }

    Path report = getReport().get().getAsFile().toPath();
    Files.createDirectories(report.getParent());
    Files.write(report, selected, StandardCharsets.UTF_8);

    if (!errors.isEmpty()) {
      throw new GradleException(
          "integrationTest selection does not match the @Tag(\""
              + tag
              + "\") classes:\n  "
              + String.join("\n  ", errors));
    }
    getLogger().info("integrationTest selection verified: {} classes", selected.size());
  }

  private Map<String, ClassInfo> readTestClasses() throws IOException {
    Map<String, ClassInfo> result = new HashMap<>();
    for (File dir : getClassesDirs().getFiles()) {
      if (!dir.isDirectory()) {
        continue;
      }
      try (Stream<Path> files = Files.walk(dir.toPath())) {
        for (Path file : (Iterable<Path>) files::iterator) {
          String name = file.getFileName().toString();
          if (!name.endsWith(".class")
              || name.equals("module-info.class")
              || name.equals("package-info.class")) {
            continue;
          }
          try (InputStream in = Files.newInputStream(file)) {
            ClassInfo info = ClassInfo.read(in);
            result.put(info.name, info);
            cache.put(info.name, info);
          }
        }
      }
    }
    return result;
  }

  /** JUnit's view: tag on the type, on one of its annotations (meta), or on a supertype. */
  private boolean isTagged(String internalName, String tag, Set<String> visiting) {
    Boolean known = tagged.get(internalName);
    if (known != null) {
      return known;
    }
    if (!visiting.add(internalName)) {
      return false;
    }
    ClassInfo info = find(internalName);
    boolean result = false;
    if (info != null) {
      result = info.tags.contains(tag);
      for (int i = 0; !result && i < info.annotationTypes.size(); i++) {
        result = isTagged(info.annotationTypes.get(i), tag, visiting);
      }
      if (!result && info.superName != null) {
        result = isTagged(info.superName, tag, visiting);
      }
      for (int i = 0; !result && i < info.interfaces.size(); i++) {
        result = isTagged(info.interfaces.get(i), tag, visiting);
      }
    }
    tagged.put(internalName, result);
    return result;
  }

  private ClassInfo find(String internalName) {
    if (internalName.startsWith("java/")
        || internalName.startsWith("javax/")
        || internalName.startsWith("kotlin/")
        || internalName.startsWith("org/junit/")) {
      return null;
    }
    if (cache.containsKey(internalName)) {
      return cache.get(internalName);
    }
    ClassInfo info = null;
    try (InputStream in = lookup.getResourceAsStream(internalName + ".class")) {
      if (in != null) {
        info = ClassInfo.read(in);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    cache.put(internalName, info);
    return info;
  }

  private static URL toUrl(File file) {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static final class ClassInfo {
    String name;
    int access;
    String superName;
    List<String> interfaces = new ArrayList<>();
    String outerName;
    boolean isStaticNested;
    final Set<String> tags = new HashSet<>();
    final List<String> annotationTypes = new ArrayList<>();

    static ClassInfo read(InputStream in) throws IOException {
      ClassInfo info = new ClassInfo();
      new ClassReader(in)
          .accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces) {
                  info.name = name;
                  info.access = access;
                  info.superName = superName;
                  info.interfaces = interfaces == null ? List.of() : List.of(interfaces);
                }

                @Override
                public void visitInnerClass(
                    String name, String outerName, String innerName, int access) {
                  if (name.equals(info.name)) {
                    info.outerName = outerName != null ? outerName : name;
                    info.isStaticNested = (access & Opcodes.ACC_STATIC) != 0;
                  }
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                  if (TAG.equals(descriptor)) {
                    return tagValue(info);
                  }
                  if (TAGS.equals(descriptor)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                      @Override
                      public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                          @Override
                          public AnnotationVisitor visitAnnotation(String n, String d) {
                            return tagValue(info);
                          }
                        };
                      }
                    };
                  }
                  if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    info.annotationTypes.add(descriptor.substring(1, descriptor.length() - 1));
                  }
                  return null;
                }
              },
              ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return info;
    }

    private static AnnotationVisitor tagValue(ClassInfo info) {
      return new AnnotationVisitor(Opcodes.ASM9) {
        @Override
        public void visit(String name, Object value) {
          if ("value".equals(name) && value instanceof String) {
            info.tags.add(((String) value).trim());
          }
        }
      };
    }
  }
}
