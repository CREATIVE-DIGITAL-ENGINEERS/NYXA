package com.axelor.apps.napsubmissions.common.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code nap.qes.provider.select} options against the provider ids the Java side uses.
 *
 * <p>The stored value of {@code UserQesProfile.providerIdentifier} is compared by plain string
 * equality to {@link CloudQesProvider#getProviderId()} before signing. A typo in the XML — or a
 * well-meaning edit of an option value to something more readable — would therefore not fail the
 * build, it would silently make every profile unsignable. This test reads the real resource so a
 * drift between the two is caught here instead of in the signing screen.
 */
class CloudQesProviderSelectionTest {

  private static final String SELECTION_NAME = "nap.qes.provider.select";

  private static String selectsXml() {
    try (InputStream in =
        CloudQesProviderSelectionTest.class.getResourceAsStream("/views/Selects.xml")) {
      assertNotNull(in, "views/Selects.xml must be on the test classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read views/Selects.xml", e);
    }
  }

  /** The {@code <option>} elements of the provider selection, in document order. */
  private static List<String[]> providerOptions() {
    String xml = selectsXml();
    Matcher block =
        Pattern.compile(
                "<selection\\s+name=\""
                    + Pattern.quote(SELECTION_NAME)
                    + "\"\\s*>(.*?)</selection>",
                Pattern.DOTALL)
            .matcher(xml);
    assertTrue(block.find(), SELECTION_NAME + " must be declared in views/Selects.xml");

    List<String[]> options = new ArrayList<>();
    Matcher option =
        Pattern.compile("<option\\s+value=\"([^\"]*)\"\\s*>(.*?)</option>", Pattern.DOTALL)
            .matcher(block.group(1));
    while (option.find()) {
      options.add(new String[] {option.group(1), option.group(2).trim()});
    }
    return options;
  }

  @Test
  void theSelectionStoresExactlyTheThreeProviderIds() {
    List<String> values = new ArrayList<>();
    providerOptions().forEach(option -> values.add(option[0]));

    assertEquals(
        Arrays.asList(
            CloudQesProviderIds.MOCK, CloudQesProviderIds.BTRUST, CloudQesProviderIds.EVROTRUST),
        values);
  }

  @Test
  void theMockOptionValueIsTheMockProvidersOwnId() {
    // The one option backed by a working implementation today: its value must be what the live
    // provider reports, or a mock profile cannot sign through the mock provider.
    assertEquals(
        new MockCloudQesProvider(MockQesScenario.IMMEDIATE_SUCCESS).getProviderId(),
        providerOptions().get(0)[0]);
  }

  @Test
  void theOptionTitlesAreTheAgreedEnglishKeys() {
    List<String[]> options = providerOptions();
    assertEquals("Mock (tests only)", options.get(0)[1]);
    assertEquals("B-Trust", options.get(1)[1]);
    assertEquals("Evrotrust", options.get(2)[1]);
  }

  /**
   * {@code none} is what the fail-closed provider reports when nothing is configured. It must never
   * be offered as a profile value: "sign through nothing" is not a choice an operator can make.
   */
  @Test
  void theUnconfiguredProviderIdIsNotSelectable() {
    assertEquals(CloudQesProviderIds.NONE, new UnconfiguredCloudQesProvider().getProviderId());
    assertTrue(!CloudQesProviderIds.SELECTABLE.contains(CloudQesProviderIds.NONE));
  }
}
