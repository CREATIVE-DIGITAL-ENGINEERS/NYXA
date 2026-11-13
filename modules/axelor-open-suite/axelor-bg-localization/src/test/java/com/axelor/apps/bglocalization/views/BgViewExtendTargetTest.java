/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Guard against silently misdirected <extend target="..."> XPaths.
 */
package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Asserts that every {@code <extend target="...">} XPath declared by this module resolves to
 * <b>exactly one</b> node in the view it extends.
 *
 * <p><b>Why this has to be a test and not a review checklist.</b> {@code
 * ComputedViewXmlProcessor#processExtend} resolves the target with {@code evaluateXPath(target,
 * ..., XPathConstants.NODE)} — a single node, first in document order. Two consequences, both
 * silent:
 *
 * <ul>
 *   <li><b>Two or more matches:</b> the extend is applied to the first one and the rest are dropped
 *       without a log line. In the Axelor HR views this bites constantly, because {@code
 *       employee-form} declares almost every field twice — once in the step-by-step wizard ({@code
 *       *SBSPanel}, {@code stepByStepSelect} 1..4) and once in the classic view ({@code
 *       stepByStepSelect == 5}). A bare {@code //field[@name='x']} reaches the wizard only.
 *   <li><b>Zero matches:</b> logged as an error at view-load time and otherwise ignored. The
 *       application still starts, the view still renders — just without the change.
 * </ul>
 *
 * <p>Neither case fails compilation, {@code generateCode} or {@code build}: extend targets are
 * plain strings resolved at view-load time against a document that only exists once the modules are
 * merged. So the whole class of error is invisible to the build unless something asserts it.
 *
 * <p><b>What this test does not cover.</b> Targets are resolved against the <i>pristine</i> base
 * view, not against the fully merged document that AOP builds by applying every module's extensions
 * in dependency order. That means a target pointing at a node contributed by another module's
 * extension would show up here as a false MISS, and a node <i>duplicated</i> by another module's
 * extension could be reported as unique here yet be ambiguous at runtime. {@link
 * #targetsDoNotDependOnForeignExtensions()} guards that gap: it fails if any of our targets names
 * an element that another module's extension of the same view inserts. As long as that guard is
 * green, resolving against the pristine base is equivalent to resolving against the merged
 * document.
 *
 * <p>A collision it cannot decide may be settled by an assertion that reads the foreign extension
 * directly and registered in {@link #PROVEN_ELSEWHERE}. An entry there is a pointer to that proof,
 * never a way to skip one.
 */
class BgViewExtendTargetTest {

  private static final String NAMESPACE = "http://axelor.com/xml/ns/object-views";

  /** Same substitution AOP applies before compiling the expression. */
  private static final Pattern NS_PATTERN = Pattern.compile("/(\\w)");

  private static final Pattern VIEW_RESOURCE = Pattern.compile("^views/[^/]+\\.xml$");

  /**
   * Marks the classpath entry holding this module's own resources. Discovery is asserted non-empty
   * below, so a layout change breaks the test loudly instead of silently checking nothing.
   */
  private static final String OWN_MODULE = "axelor-bg-localization";

  private static final NamespaceContext NS_CONTEXT =
      new NamespaceContext() {
        @Override
        public String getNamespaceURI(String prefix) {
          return NAMESPACE;
        }

        @Override
        public String getPrefix(String namespaceURI) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
          throw new UnsupportedOperationException();
        }
      };

  // ---------------------------------------------------------------- model

  /**
   * One {@code <form name="x">} / {@code <grid name="y">} … element inside an object-views file.
   */
  private static final class ViewDef {
    final String type; // form, grid, tree, …
    final String name;
    final boolean extension;
    final String origin; // classpath location, for messages
    final Document document;
    final Element element;

    ViewDef(String type, String name, boolean extension, String origin, Document d, Element e) {
      this.type = type;
      this.name = name;
      this.extension = extension;
      this.origin = origin;
      this.document = d;
      this.element = e;
    }

    String key() {
      return type + "/" + name;
    }

    boolean isOurs() {
      return origin.contains(OWN_MODULE);
    }
  }

  private static final class Result {
    final ViewDef view;
    final String target;
    final int hits;
    final List<String> trails;
    final String error;

    Result(ViewDef view, String target, int hits, List<String> trails, String error) {
      this.view = view;
      this.target = target;
      this.hits = hits;
      this.trails = trails;
      this.error = error;
    }

    boolean ok() {
      return error == null && hits == 1;
    }

    String status() {
      if (error != null) return "ERROR";
      return hits == 1 ? "OK" : (hits == 0 ? "MISS" : "AMBIG");
    }
  }

  // ---------------------------------------------------------------- tests

  @Test
  @DisplayName("every <extend target> of this module resolves to exactly one node")
  void everyExtendTargetResolvesToExactlyOneNode() throws Exception {
    final Map<String, List<ViewDef>> all = scanViews();
    final List<ViewDef> ours = extensionViewsOfThisModule(all);

    assertFalse(
        ours.isEmpty(),
        "Found no extension views for "
            + OWN_MODULE
            + " on the test classpath. View discovery is broken — this test would otherwise pass "
            + "while checking nothing.");

    final List<Result> results = new ArrayList<>();
    final List<String> missingBases = new ArrayList<>();

    for (ViewDef ext : ours) {
      ViewDef base = baseOf(all, ext.key());
      if (base == null) {
        missingBases.add(ext.key() + "  (declared in " + ext.origin + ")");
        continue;
      }
      for (Element extend : childElements(ext.element, "extend")) {
        results.add(evaluate(base, ext, extend.getAttribute("target")));
      }
    }

    report(results, missingBases);

    assertTrue(
        missingBases.isEmpty(),
        "Extension views whose base view was not found on the classpath:\n  "
            + String.join("\n  ", missingBases));

    final List<Result> bad = results.stream().filter(r -> !r.ok()).collect(Collectors.toList());
    assertTrue(bad.isEmpty(), failureMessage(bad));
  }

  /**
   * A collision this test may skip, keyed by {@code viewKey|target|foreignName} and mapped to the
   * assertion that proves the case instead.
   *
   * <p><b>This is not a silencer, and an entry without a proof test is a bug.</b> The generic check
   * below cannot decide a name collision because it resolves against the pristine base view; an
   * entry here means the same gap is closed by an assertion that reads the foreign extension
   * directly and fails when the foreign module changes. That is the narrow version of the merge
   * step this test asks for — narrow on purpose, because a full merge would have to replay every
   * module's extensions in dependency order and would report false misses of its own.
   */
  private static final Map<String, String> PROVEN_ELSEWHERE = new HashMap<>();

  static {
    PROVEN_ELSEWHERE.put(
        "form/partner-form|//field[@name='localization']|localization",
        "axelor-crm DELETES panel[@name='settingsPanel'] (crm/views/Partner.xml:163-165) and "
            + "re-inserts its localization field into an anonymous panel, so base and merged "
            + "documents both hold exactly one. Proven by "
            + "BgPartnerViewXmlTest#localizationFieldStaysUniqueUnderCrm.");
  }

  /**
   * Resolving against the pristine base view is only equivalent to resolving against AOP's merged
   * document while none of our targets touches a node that another module's extension contributes.
   * If this fires, either retarget or prove the case and register it in {@link #PROVEN_ELSEWHERE} —
   * do not silence it.
   */
  @Test
  @DisplayName("no target depends on a node inserted by another module's extension")
  void targetsDoNotDependOnForeignExtensions() throws Exception {
    final Map<String, List<ViewDef>> all = scanViews();
    final List<ViewDef> ours = extensionViewsOfThisModule(all);
    final List<String> collisions = new ArrayList<>();

    for (ViewDef ext : ours) {
      final Set<String> foreign = new HashSet<>();
      for (ViewDef other : all.getOrDefault(ext.key(), List.of())) {
        if (!other.extension || other.isOurs()) continue;
        collectInsertedNames(other.element, foreign);
      }
      if (foreign.isEmpty()) continue;

      for (Element extend : childElements(ext.element, "extend")) {
        final String target = extend.getAttribute("target");
        for (String name : foreign) {
          if (!target.contains("'" + name + "'")) continue;
          if (PROVEN_ELSEWHERE.containsKey(ext.key() + "|" + target + "|" + name)) continue;
          collisions.add(ext.key() + "  target=" + target + "  foreign element name=" + name);
        }
      }
    }

    assertTrue(
        collisions.isEmpty(),
        "Targets referencing element names that another module's extension inserts. The "
            + "single-node check resolves against the pristine base view, so these are neither "
            + "proven nor disproven by it:\n  "
            + String.join("\n  ", collisions));
  }

  // ---------------------------------------------------------------- evaluation

  /** Mirrors {@code AbstractXmlProcessor#prepareXPathExpression} verbatim. */
  private static String prepareXPathExpression(String subExpression, String name, String type) {
    final String rootExpr = "/:object-views/:%s[@name='%s']";
    final String expr = subExpression.startsWith("/") ? subExpression.substring(1) : subExpression;
    return String.format(expr.isEmpty() ? rootExpr : rootExpr + "/" + expr, type, name, expr);
  }

  private static Result evaluate(ViewDef base, ViewDef ext, String target) {
    final String expression =
        NS_PATTERN.matcher(prepareXPathExpression(target, base.name, base.type)).replaceAll("/:$1");
    final XPath xPath = XPathFactory.newInstance().newXPath();
    xPath.setNamespaceContext(NS_CONTEXT);
    try {
      final NodeList hits =
          (NodeList) xPath.compile(expression).evaluate(base.document, XPathConstants.NODESET);
      final List<String> trails = new ArrayList<>();
      for (int i = 0; i < hits.getLength(); i++) {
        trails.add(trail(hits.item(i)));
      }
      return new Result(ext, target, hits.getLength(), trails, null);
    } catch (XPathExpressionException e) {
      return new Result(ext, target, -1, List.of(), "does not compile: " + e.getMessage());
    }
  }

  /** Ancestor trail up to the view element, e.g. {@code form > panel-tabs[x] > panel[y]}. */
  private static String trail(Node node) {
    final List<String> parts = new ArrayList<>();
    for (Node n = node; n instanceof Element; n = n.getParentNode()) {
      final String name = ((Element) n).getAttribute("name");
      parts.add(n.getLocalName() + (name.isEmpty() ? "" : "[" + name + "]"));
      if (n.getParentNode() == null || "object-views".equals(n.getParentNode().getLocalName())) {
        break;
      }
    }
    java.util.Collections.reverse(parts);
    return String.join(" > ", parts);
  }

  // ---------------------------------------------------------------- discovery

  private static Map<String, List<ViewDef>> scanViews() throws Exception {
    final Map<String, List<ViewDef>> index = new LinkedHashMap<>();
    for (String entry : classpathEntries()) {
      final File file = new File(entry);
      if (file.isDirectory()) {
        scanDirectory(file, index);
      } else if (file.isFile() && entry.endsWith(".jar")) {
        scanJar(file, index);
      }
    }
    return index;
  }

  private static void scanDirectory(File root, Map<String, List<ViewDef>> index) throws Exception {
    final Path views = root.toPath().resolve("views");
    if (!Files.isDirectory(views)) return;
    try (Stream<Path> stream = Files.list(views)) {
      final List<Path> files =
          stream
              .filter(p -> p.getFileName().toString().endsWith(".xml"))
              .sorted(Comparator.comparing(Path::toString))
              .collect(Collectors.toList());
      for (Path p : files) {
        try (InputStream in = Files.newInputStream(p)) {
          collect(in, p.toString(), index);
        }
      }
    }
  }

  private static void scanJar(File jar, Map<String, List<ViewDef>> index) throws Exception {
    try (JarFile jarFile = new JarFile(jar)) {
      final List<String> names =
          jarFile.stream()
              .map(java.util.zip.ZipEntry::getName)
              .filter(n -> VIEW_RESOURCE.matcher(n).matches())
              .sorted()
              .collect(Collectors.toList());
      for (String name : names) {
        try (InputStream in = jarFile.getInputStream(jarFile.getEntry(name))) {
          collect(in, jar.getPath() + "!/" + name, index);
        }
      }
    }
  }

  private static void collect(InputStream in, String origin, Map<String, List<ViewDef>> index)
      throws Exception {
    final Document document = newDocumentBuilderFactory().newDocumentBuilder().parse(in);
    final Element root = document.getDocumentElement();
    if (root == null || !"object-views".equals(root.getLocalName())) return;

    final NodeList children = root.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      final Node node = children.item(i);
      if (!(node instanceof Element) || !NAMESPACE.equals(node.getNamespaceURI())) continue;
      final Element element = (Element) node;
      final String name = element.getAttribute("name");
      if (name.isEmpty()) continue;
      final ViewDef view =
          new ViewDef(
              element.getLocalName(),
              name,
              "true".equals(element.getAttribute("extension")),
              origin,
              document,
              element);
      index.computeIfAbsent(view.key(), k -> new ArrayList<>()).add(view);
    }
  }

  private static DocumentBuilderFactory newDocumentBuilderFactory() throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    return factory;
  }

  /**
   * Classpath entries, expanding a Gradle pathing jar if one is used (a single jar whose manifest
   * carries the real classpath).
   */
  private static List<String> classpathEntries() {
    final List<String> entries =
        Stream.of(System.getProperty("java.class.path", "").split(File.pathSeparator))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    if (entries.size() != 1 || !entries.get(0).endsWith(".jar")) {
      return entries;
    }
    final File single = new File(entries.get(0));
    try (JarFile jarFile = new JarFile(single)) {
      final Manifest manifest = jarFile.getManifest();
      if (manifest == null) return entries;
      final String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      if (classPath == null || classPath.isBlank()) return entries;
      final List<String> expanded = new ArrayList<>();
      for (String part : classPath.split("\\s+")) {
        if (part.isEmpty()) continue;
        try {
          expanded.add(Paths.get(URI.create(part)).toString());
        } catch (Exception e) {
          expanded.add(new File(single.getParentFile(), part).getPath());
        }
      }
      return expanded.isEmpty() ? entries : expanded;
    } catch (IOException e) {
      return entries;
    }
  }

  private static List<ViewDef> extensionViewsOfThisModule(Map<String, List<ViewDef>> all) {
    return all.values().stream()
        .flatMap(Collection::stream)
        .filter(v -> v.extension && v.isOurs())
        .sorted(Comparator.comparing((ViewDef v) -> v.origin).thenComparing(ViewDef::key))
        .collect(Collectors.toList());
  }

  private static ViewDef baseOf(Map<String, List<ViewDef>> all, String key) {
    return all.getOrDefault(key, List.of()).stream()
        .filter(v -> !v.extension)
        .findFirst()
        .orElse(null);
  }

  private static List<Element> childElements(Element parent, String localName) {
    final List<Element> elements = new ArrayList<>();
    final NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      final Node node = children.item(i);
      if (node instanceof Element && localName.equals(node.getLocalName())) {
        elements.add((Element) node);
      }
    }
    return elements;
  }

  /** Names of every element nested under an {@code <insert>} of the given extension view. */
  private static void collectInsertedNames(Element extensionView, Set<String> sink) {
    for (Element extend : childElements(extensionView, "extend")) {
      for (Element insert : childElements(extend, "insert")) {
        collectNamesDeep(insert, sink);
      }
    }
  }

  private static void collectNamesDeep(Element element, Set<String> sink) {
    final NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      final Node node = children.item(i);
      if (!(node instanceof Element)) continue;
      final String name = ((Element) node).getAttribute("name");
      if (!name.isEmpty()) sink.add(name);
      collectNamesDeep((Element) node, sink);
    }
  }

  // ---------------------------------------------------------------- reporting

  private static void report(List<Result> results, List<String> missingBases) {
    final Map<String, List<Result>> byOrigin = new TreeMap<>();
    for (Result r : results) {
      byOrigin.computeIfAbsent(shortName(r.view.origin), k -> new ArrayList<>()).add(r);
    }
    final StringBuilder out = new StringBuilder();
    out.append("\n=== <extend target> resolution against base views ===\n");
    byOrigin.forEach(
        (origin, list) -> {
          out.append("\n--- ").append(origin).append('\n');
          for (Result r : list) {
            out.append(String.format("  %-6s %-30s %s%n", r.status(), r.view.key(), r.target));
            if (r.error != null) {
              out.append("           ").append(r.error).append('\n');
            }
            for (int i = 0; i < r.trails.size(); i++) {
              out.append("           ")
                  .append(i == 0 ? "applied : " : "IGNORED : ")
                  .append(r.trails.get(i))
                  .append('\n');
            }
          }
        });
    final long ok = results.stream().filter(Result::ok).count();
    out.append(
        String.format(
            "%n%d target(s) checked, %d OK, %d not unique, %d view(s) without a base%n",
            results.size(), ok, results.size() - ok, missingBases.size()));
    System.out.println(out);
  }

  private static String failureMessage(List<Result> bad) {
    final StringBuilder sb = new StringBuilder();
    sb.append(bad.size())
        .append(" <extend target> XPath(s) do not resolve to exactly one node.\n")
        .append("AOP resolves targets with XPathConstants.NODE: with several matches it silently ")
        .append(
            "applies the extend to the first one only; with none it logs an error at view-load ")
        .append("time and moves on. Scope the target so it identifies one node.\n");
    for (Result r : bad) {
      sb.append("\n  ")
          .append(r.status())
          .append("  ")
          .append(r.view.key())
          .append("  in ")
          .append(shortName(r.view.origin))
          .append("\n    target: ")
          .append(r.target)
          .append('\n');
      if (r.error != null) {
        sb.append("    ").append(r.error).append('\n');
      }
      for (int i = 0; i < r.trails.size(); i++) {
        sb.append("    ")
            .append(i == 0 ? "applied to : " : "IGNORED    : ")
            .append(r.trails.get(i))
            .append('\n');
      }
      if (r.hits == 0) {
        sb.append("    no match — check the element still exists in the base view\n");
      }
    }
    return sb.toString();
  }

  private static String shortName(String origin) {
    final int slash = Math.max(origin.lastIndexOf('/'), origin.lastIndexOf('\\'));
    return slash < 0 ? origin : origin.substring(slash + 1);
  }
}
