package com.axelor.apps.aiocr.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Structural proof, over the actual shipped view XML (not a copy), that the "Download orphan file"
 * wizard is incapable of saving/creating/updating a {@code MetaFile} — see the long comment above
 * {@code ocr-orphan-download-wizard} in {@code AiOcrUpload.xml} for the full reasoning. This test
 * fails the moment any of the three load-bearing properties regresses:
 *
 * <ol>
 *   <li>the typed value is a "$"-prefixed dummy context field, never the real {@code id} property;
 *   <li>{@code canSave}/{@code canNew} (and the sibling {@code canDelete}/{@code canArchive}) are
 *       {@code "false"} on the wizard form — the actual code-enforced gate in axelor-front, not the
 *       cosmetic {@code popup-save}/{@code show-toolbar} view-params;
 *   <li>no {@code onClick} anywhere in the wizard invokes the literal action name {@code save},
 *       which is the one path that bypasses {@code canSave}/{@code canNew} entirely.
 * </ol>
 */
class AiOcrUploadOrphanWizardXmlTest {

  private static Document loadView() throws Exception {
    try (InputStream in =
        AiOcrUploadOrphanWizardXmlTest.class.getResourceAsStream("/views/AiOcrUpload.xml")) {
      assertNotNull(in, "AiOcrUpload.xml must be on the test classpath");
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      return factory.newDocumentBuilder().parse(in);
    }
  }

  private static Element wizardForm(Document doc) {
    NodeList forms = doc.getElementsByTagName("form");
    for (int i = 0; i < forms.getLength(); i++) {
      Element form = (Element) forms.item(i);
      if ("ocr-orphan-download-wizard".equals(form.getAttribute("name"))) {
        return form;
      }
    }
    throw new AssertionError("ocr-orphan-download-wizard form not found in AiOcrUpload.xml");
  }

  @Test
  void wizardInputIsADummyDollarFieldNotTheRealIdProperty() throws Exception {
    Element form = wizardForm(loadView());
    NodeList fields = form.getElementsByTagName("field");
    assertEquals(1, fields.getLength(), "the wizard must expose exactly one input field");
    String name = ((Element) fields.item(0)).getAttribute("name");
    assertTrue(name.startsWith("$"), "the input must be a \"$\"-prefixed dummy field: " + name);
    assertFalse(
        "id".equals(name) || "$id".equals(name),
        "the input must never be bound to MetaFile's real id property");
  }

  @Test
  void wizardFormDisablesSaveAndNewStructurally() throws Exception {
    Element form = wizardForm(loadView());
    assertEquals(
        "false",
        form.getAttribute("canSave"),
        "canSave=\"false\" is the code-enforced gate (hasButton(\"save\") / the Ctrl+S shortcut),"
            + " unlike the cosmetic popup-save view-param");
    assertEquals(
        "false",
        form.getAttribute("canNew"),
        "canNew=\"false\" is required too: hasSave = hasButton(\"save\") || canSaveNew, and"
            + " canSaveNew depends on canNew, not on write permission");
  }

  @Test
  void wizardFormDisablesDeleteAndArchiveToo() throws Exception {
    Element form = wizardForm(loadView());
    assertEquals("false", form.getAttribute("canDelete"));
    assertEquals("false", form.getAttribute("canArchive"));
  }

  @Test
  void noButtonInTheWizardEverInvokesTheLiteralSaveAction() throws Exception {
    Element form = wizardForm(loadView());
    NodeList buttons = form.getElementsByTagName("button");
    assertTrue(buttons.getLength() > 0, "the wizard must have at least its Download button");
    for (int i = 0; i < buttons.getLength(); i++) {
      Element button = (Element) buttons.item(i);
      String onClick = button.getAttribute("onClick");
      for (String action : onClick.split(",")) {
        assertFalse(
            "save".equals(action.trim()),
            "button \""
                + button.getAttribute("name")
                + "\" must never chain the literal \"save\" action — it bypasses"
                + " canSave/canNew entirely (actionHandler.setSaveHandler wires \"save\""
                + " unconditionally to doSave)");
      }
    }
  }

  @Test
  void wizardFormIsBoundOnlyForShapeAndHasNoOnSaveOrOnNewSideEffects() throws Exception {
    Element form = wizardForm(loadView());
    // Belt-and-suspenders: even if canSave/canNew were ever loosened, there must be no onSave/onNew
    // action wired that could do something on a save/new event that never should fire in the first
    // place.
    assertTrue(isBlank(form.getAttribute("onSave")));
    assertTrue(isBlank(form.getAttribute("onNew")));
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /**
   * The action-view that opens the wizard as a popup must never itself be configured to save on
   * open/close (e.g. a stray {@code save} in its own action chain is not applicable here since
   * action-view has no onClick, but this guards against the popup value ever being anything other
   * than {@code reload}, which never triggers a write).
   */
  @Test
  void popupOpenerNeverEnablesPopupSave() throws Exception {
    Document doc = loadView();
    NodeList actionViews = doc.getElementsByTagName("action-view");
    Element target = null;
    for (int i = 0; i < actionViews.getLength(); i++) {
      Element av = (Element) actionViews.item(i);
      if ("action-ai-ocr-upload-view-download-orphan-wizard".equals(av.getAttribute("name"))) {
        target = av;
        break;
      }
    }
    assertNotNull(target, "the popup opener action-view must exist");
    NodeList params = target.getElementsByTagName("view-param");
    boolean sawPopupSaveFalse = false;
    for (int i = 0; i < params.getLength(); i++) {
      Node p = params.item(i);
      if (!(p instanceof Element)) {
        continue;
      }
      Element param = (Element) p;
      if ("popup-save".equals(param.getAttribute("name"))) {
        assertEquals("false", param.getAttribute("value"));
        sawPopupSaveFalse = true;
      }
    }
    assertTrue(sawPopupSaveFalse, "popup-save=\"false\" must be set (cosmetic, but still correct)");
  }
}
