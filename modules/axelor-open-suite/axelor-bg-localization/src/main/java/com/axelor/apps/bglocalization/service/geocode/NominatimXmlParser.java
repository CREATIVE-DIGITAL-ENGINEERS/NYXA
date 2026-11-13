package com.axelor.apps.bglocalization.service.geocode;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses a Nominatim {@code format=xml&addressdetails=true} response into the first {@code <place>}
 * result, mirroring what the stock {@code MapService.getMapOsm} picks ({@code
 * childNodes().next()}). Pure/no I/O -- unit-tested against canned response text, never a live
 * call.
 */
public final class NominatimXmlParser {

  private NominatimXmlParser() {}

  public static Optional<NominatimPlace> parseFirstResult(String xml) {
    if (xml == null || xml.trim().isEmpty()) {
      return Optional.empty();
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Response comes from a third-party network service: never resolve external entities/DTDs.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

      NodeList places = doc.getElementsByTagName("place");
      if (places.getLength() == 0) {
        return Optional.empty();
      }
      Element place = (Element) places.item(0);

      String latStr = place.getAttribute("lat");
      String lonStr = place.getAttribute("lon");
      if (latStr.isEmpty() || lonStr.isEmpty()) {
        return Optional.empty();
      }
      BigDecimal lat = new BigDecimal(latStr);
      BigDecimal lon = new BigDecimal(lonStr);

      String countryCode = childText(place, "country_code");
      String settlement =
          firstNonBlank(
              childText(place, "city"), childText(place, "town"), childText(place, "village"));
      String postcode = childText(place, "postcode");
      boolean hasHouseNumber = !childText(place, "house_number").isEmpty();
      boolean hasRoad = !childText(place, "road").isEmpty();
      String county = childText(place, "county");
      String stateDistrict = childText(place, "state_district");
      String state = childText(place, "state");

      return Optional.of(
          new NominatimPlace(
              lat,
              lon,
              countryCode,
              settlement,
              postcode,
              hasHouseNumber,
              hasRoad,
              county,
              stateDistrict,
              state));
    } catch (Exception e) {
      // A malformed/unexpected response is treated as "no usable result", never as a crash.
      return Optional.empty();
    }
  }

  private static String childText(Element parent, String tagName) {
    NodeList nodes = parent.getElementsByTagName(tagName);
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getParentNode() == parent && node.getTextContent() != null) {
        return node.getTextContent().trim();
      }
    }
    return "";
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.trim().isEmpty()) {
        return v.trim();
      }
    }
    return "";
  }
}
