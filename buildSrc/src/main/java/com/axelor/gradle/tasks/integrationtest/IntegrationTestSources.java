package com.axelor.gradle.tasks.integrationtest;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Picks the test classes that carry a JUnit tag, from the test SOURCES, at configuration time.
 *
 * <p>Why sources and not the JUnit tag filter alone: with {@code forkEvery = 1} Gradle forks one JVM
 * for every class file it is handed, and the tag filter only runs inside that JVM. In
 * axelor-bg-localization that meant ~440 JVMs started just to find out they had nothing to run —
 * about 19 of the 40 minutes of {@code integrationTest}. Handing Gradle only the tagged classes
 * removes those forks.
 *
 * <p>What it understands: {@code @Tag("x")} (also inside {@code @Tags}) on a top-level type, and
 * inheritance of it through {@code extends}/{@code implements} between types of the same source
 * set — JUnit's {@code @Tag} is {@code @Inherited}, and BgLocalizationTest / AiOcrTest rely on that.
 * Abstract classes and interfaces are not selected (JUnit does not run them).
 *
 * <p>What it deliberately does not try to understand: a tag spelled through a constant, a
 * meta-annotation, or on a nested class. {@link CheckIntegrationTestSelection} reads the compiled
 * classes, where all of those are visible, and fails the build if this selection disagrees.
 */
public final class IntegrationTestSources {

  private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
  private static final Pattern IMPORT = Pattern.compile("\\bimport\\s+([\\w.]+)\\s*;");
  private static final Pattern GENERICS = Pattern.compile("<[^<>]*>");

  private IntegrationTestSources() {}

  /** Fully qualified names of the concrete top-level test classes that carry {@code tag}. */
  public static List<String> select(Iterable<File> srcDirs, String tag) {
    Map<String, TypeInfo> byFqn = new HashMap<>();
    for (File dir : srcDirs) {
      if (!dir.isDirectory()) {
        continue;
      }
      try (Stream<Path> files = Files.walk(dir.toPath())) {
        files
            .filter(p -> p.toString().endsWith(".java"))
            .map(p -> parse(p, tag))
            .filter(t -> t != null)
            .forEach(t -> byFqn.put(t.fqn, t));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    Map<String, List<TypeInfo>> bySimpleName = new HashMap<>();
    for (TypeInfo t : byFqn.values()) {
      bySimpleName.computeIfAbsent(t.simpleName, k -> new ArrayList<>()).add(t);
    }

    Set<String> selected = new TreeSet<>();
    for (TypeInfo t : byFqn.values()) {
      if (!t.isAbstract && isTagged(t, byFqn, bySimpleName, new HashSet<>())) {
        selected.add(t.fqn);
      }
    }
    return new ArrayList<>(selected);
  }

  private static boolean isTagged(
      TypeInfo type,
      Map<String, TypeInfo> byFqn,
      Map<String, List<TypeInfo>> bySimpleName,
      Set<String> visiting) {
    if (type.directlyTagged) {
      return true;
    }
    if (!visiting.add(type.fqn)) {
      return false;
    }
    for (String parent : type.parents) {
      TypeInfo resolved = resolve(type, parent, byFqn, bySimpleName);
      if (resolved != null && isTagged(resolved, byFqn, bySimpleName, visiting)) {
        return true;
      }
    }
    return false;
  }

  /** A parent reference as written in the source: qualified, same package, imported, or unique. */
  private static TypeInfo resolve(
      TypeInfo from,
      String reference,
      Map<String, TypeInfo> byFqn,
      Map<String, List<TypeInfo>> bySimpleName) {
    if (reference.contains(".")) {
      return byFqn.get(reference);
    }
    TypeInfo samePackage = byFqn.get(from.packageName + "." + reference);
    if (samePackage != null) {
      return samePackage;
    }
    for (String imported : from.imports) {
      if (imported.endsWith("." + reference)) {
        return byFqn.get(imported);
      }
    }
    List<TypeInfo> candidates = bySimpleName.get(reference);
    return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
  }

  private static TypeInfo parse(Path file, String tag) {
    String source;
    try {
      source = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String fileName = file.getFileName().toString();
    String simpleName = fileName.substring(0, fileName.length() - ".java".length());
    if (simpleName.equals("package-info") || simpleName.equals("module-info")) {
      return null;
    }

    Matcher declaration =
        Pattern.compile(
                "\\b((?:(?:public|protected|private|abstract|final|static|strictfp"
                    + "|sealed|non-sealed)\\s+)*)"
                    + "(class|interface|@\\s*interface|enum|record)\\s+"
                    + Pattern.quote(simpleName)
                    + "\\b")
            .matcher(source);
    if (!declaration.find()) {
      return null;
    }

    // Annotations of the top-level type: everything between the last import/package statement and
    // the declaration itself.
    int headerStart = 0;
    Matcher pkg = PACKAGE.matcher(source);
    String packageName = "";
    if (pkg.find() && pkg.start() < declaration.start()) {
      packageName = pkg.group(1);
      headerStart = pkg.end();
    }
    String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

    List<String> imports = new ArrayList<>();
    Matcher imp = IMPORT.matcher(source);
    while (imp.find() && imp.start() < declaration.start()) {
      imports.add(imp.group(1));
      headerStart = imp.end();
    }
    String annotations = source.substring(headerStart, declaration.start());
    boolean directlyTagged =
        Pattern.compile(
                "@\\s*(?:org\\.junit\\.jupiter\\.api\\.)?Tag\\s*\\(\\s*(?:value\\s*=\\s*)?\""
                    + Pattern.quote(tag)
                    + "\"\\s*\\)")
            .matcher(annotations)
            .find();

    String kind = declaration.group(2);
    boolean isAbstract =
        !kind.equals("class") || declaration.group(1).matches(".*\\babstract\\b.*");

    int bodyStart = source.indexOf('{', declaration.end());
    String header =
        source.substring(declaration.end(), bodyStart < 0 ? source.length() : bodyStart);
    String previous;
    do {
      previous = header;
      header = GENERICS.matcher(header).replaceAll("");
    } while (!header.equals(previous));
    List<String> parents = new ArrayList<>();
    Matcher clause =
        Pattern.compile("\\b(?:extends|implements)\\s+([\\w.\\s,]+?)(?=\\bimplements\\b|$)")
            .matcher(header);
    while (clause.find()) {
      for (String parent : clause.group(1).split(",")) {
        String name = parent.trim();
        if (!name.isEmpty()) {
          parents.add(name);
        }
      }
    }

    return new TypeInfo(fqn, simpleName, packageName, imports, directlyTagged, isAbstract, parents);
  }

  /** Removes comments, keeping string and char literals intact so "//" inside a URL survives. */
  static String stripComments(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == '"' || c == '\'') {
        int end = i + 1;
        while (end < n && source.charAt(end) != c) {
          end += source.charAt(end) == '\\' ? 2 : 1;
        }
        end = Math.min(end + 1, n);
        out.append(source, i, end);
        i = end;
      } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
        while (i < n && source.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
        out.append(' ');
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  private static final class TypeInfo {
    final String fqn;
    final String simpleName;
    final String packageName;
    final List<String> imports;
    final boolean directlyTagged;
    final boolean isAbstract;
    final List<String> parents;

    TypeInfo(
        String fqn,
        String simpleName,
        String packageName,
        List<String> imports,
        boolean directlyTagged,
        boolean isAbstract,
        List<String> parents) {
      this.fqn = fqn;
      this.simpleName = simpleName;
      this.packageName = packageName;
      this.imports = imports;
      this.directlyTagged = directlyTagged;
      this.isAbstract = isAbstract;
      this.parents = parents;
    }
  }
}
