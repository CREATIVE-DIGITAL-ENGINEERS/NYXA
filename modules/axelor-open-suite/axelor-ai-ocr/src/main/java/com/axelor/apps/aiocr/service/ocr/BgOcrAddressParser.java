package com.axelor.apps.aiocr.service.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for a Bulgarian free-text supplier address (e.g. "България, гр. София, П.К. 1582,
 * жк. Дружба, №2"). Pure/dependency-free and deliberately conservative: it only extracts a value
 * when an explicit Bulgarian marker is present, so arbitrary words/numbers (or Latin junk) are
 * never treated as city/postcode/street. In BG invoices "П.К." is the POSTAL code (not a PO box).
 */
public final class BgOcrAddressParser {

  private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  // Postal code ONLY after an explicit П.К./ПК marker, exactly 4 digits (BG postcode).
  private static final Pattern ZIP = Pattern.compile("(?:П\\.?\\s*К\\.?|ПК)\\s*(\\d{4})(?!\\d)", F);
  // City only after a standalone гр./град/с./село marker + space + a Cyrillic-capitalised name.
  private static final Pattern CITY =
      Pattern.compile(
          "(?:^|[\\s,(])(?:гр\\.|град|с\\.|село)\\s+([А-ЯЁ][А-Яа-яЁё]+(?:[ -][А-ЯЁ][А-Яа-яЁё]+)*)",
          F);
  // A whole-string single Cyrillic word (e.g. "София") or the token after a trailing label colon.
  private static final Pattern SINGLE_CYR = Pattern.compile("^[А-ЯЁ][А-Яа-яЁё-]{1,29}$");
  private static final Pattern NUMBER = Pattern.compile("№\\s*(\\d+[А-Яа-яA-Za-z]?)", F);
  // The street runs from the first street keyword to the END of the (collapsed) line, so the whole
  // address tail is kept: "жк. Дружба, №2, БЛ. 410, ВХ.Б, ЕТ.7, АП.51" — not truncated at the first
  // comma. parse() collapses newlines to spaces first, so "." never needs to cross a real line
  // break.
  private static final Pattern STREET =
      Pattern.compile("((?:ж\\.?\\s*к\\.?|жк|ул\\.|бул\\.|кв\\.)\\s*.+)$", F);
  // An explicit settlement marker ANYWHERE on the line ("България, гр. София" → "София"),
  // tolerating
  // a glued or spaced short form ("гр.София"/"гр. София") and requiring a space after the long
  // forms.
  // This lets a marked city win over an unmarked leading segment (e.g. the country name).
  private static final Pattern CITY_MARKER =
      Pattern.compile(
          "(?:^|[\\s,(])(?:гр\\.\\s*|град\\s+|с\\.\\s*|село\\s+)"
              + "([А-ЯЁ][А-Яа-яЁё]+(?:[ -][А-ЯЁ][А-Яа-яЁё]+)*)",
          F);
  private static final Pattern BG =
      Pattern.compile(
          "БЪЛГАР|BULGAR|\\bBG\\b|\\bBGR\\b", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
  private static final Pattern UK =
      Pattern.compile("UNITED[ -]?KINGDOM|\\bGB\\b|\\bGBR\\b", Pattern.CASE_INSENSITIVE);

  private BgOcrAddressParser() {}

  /** Structured result; {@link #usable()} means a real (Cyrillic) city name was found. */
  public static final class Parsed {
    public String cityName;
    public String zip;
    public String street;
    public String number;
    public String countryIso; // "BG" default for the BG OCR flow; explicit foreign ISO otherwise.

    public boolean usable() {
      return cityName != null && !cityName.isEmpty();
    }
  }

  /** Which action the intake should take given the count of supplier-owned address matches. */
  public enum Decision {
    SELECT,
    WARN_MULTIPLE,
    CREATE
  }

  public static Decision decide(int matchCount) {
    if (matchCount == 1) {
      return Decision.SELECT;
    }
    return matchCount > 1 ? Decision.WARN_MULTIPLE : Decision.CREATE;
  }

  /**
   * Build a supplier {@link Parsed} from Azure's structured hints plus the raw (multi-line)
   * VendorAddress text. BG invoices typically put the city on the first line and the street on the
   * next; Azure's {@code valueAddress} (road / houseNumber / city / postalCode) is preferred where
   * present. Any argument may be null. The postcode may legitimately be absent (the caller then
   * takes it from the resolved City).
   */
  public static Parsed buildSupplier(
      String content, String azCity, String azStreet, String azNumber, String azZip) {
    Parsed p = new Parsed();
    p.countryIso = countryIso(content);
    // Invoices sometimes print several addresses (HQ + office). Restrict text parsing to the FIRST
    // address block so we never blend two addresses together.
    String firstBlock = firstAddressBlock(content);
    Parsed marker = parse(firstBlock); // marker-based fallback, first address only

    String city = blank(azCity) ? null : azCity.trim();
    if (city == null) {
      city = cityFromBlock(firstBlock);
    }
    if (city == null) {
      city = marker.cityName;
    }
    p.cityName = city;

    p.zip = blank(azZip) ? marker.zip : azZip.trim();
    p.number = blank(azNumber) ? marker.number : azNumber.trim();

    // Street precision order: (1) from an explicit street keyword (ул./бул./жк/кв) to the end of
    // the
    // first block — captures the full "жк. Дружба №2, бл. 410, вх. Б, ет. 7, ап. 51"; (2) the block
    // with the city line stripped (city-on-line-1 / street-on-line-2, no keyword); (3) Azure's
    // structured road + number as a last resort. Never leaves country/city/postcode as the street.
    String street = marker.street;
    if (blank(street)) {
      street = streetFromBlock(firstBlock, p.cityName);
    }
    if (blank(street)) {
      street = joinNonBlank(azStreet, azNumber);
    }
    p.street = blank(street) ? null : street;
    return p;
  }

  /**
   * Extract a city name from one line: an explicit "гр.София"/"гр. София"/"с. Драганово" marker
   * (which wins even when it follows another segment, e.g. "България, гр. София"), a bare name, or
   * the first comma segment. A country name ("България"/"Bulgaria"/"BG") is NEVER accepted as a
   * city — a leading country segment is skipped so the real city after it is used.
   */
  static String cityFromLine(String line) {
    if (line == null) {
      return null;
    }
    String t = line.trim();
    if (t.isEmpty()) {
      return null;
    }
    // 1) An explicit settlement marker anywhere on the line wins over an unmarked leading segment.
    Matcher mk = CITY_MARKER.matcher(t);
    if (mk.find()) {
      String c = mk.group(1).trim();
      if (!isCountry(c)) {
        return c;
      }
    }
    // 2) A bare Cyrillic city name on its own line (no digits, no comma), unless it is a country.
    if (t.matches("[А-ЯЁ][А-Яа-яЁё .-]{1,40}")
        && !t.matches(".*\\d.*")
        && !t.contains(",")
        && !isCountry(t)) {
      return t;
    }
    // 3) The first comma-separated lone-Cyrillic segment that is not a country — so a leading
    // country ("България, София, …") is skipped and "София" is taken; "София, жк. …" works
    // directly.
    for (String seg : t.split(",")) {
      String head = seg.trim();
      if (head.isEmpty() || isCountry(head)) {
        continue;
      }
      return SINGLE_CYR.matcher(head).matches() ? head : null;
    }
    return null;
  }

  /**
   * A country name (Bulgaria in any spelling) is never a settlement. Used to reject "България" /
   * "Република България" / "Bulgaria" / "Republic of Bulgaria" / "BG" when they appear as the
   * leading address segment before the real city.
   */
  static boolean isCountry(String s) {
    if (s == null) {
      return false;
    }
    String n = norm(s).replaceAll("[.\\s]+$", "");
    return n.equals("bg")
        || n.equals("bgr")
        || n.endsWith("българия") // covers "република българия", "р. българия"
        || n.endsWith("bulgaria"); // covers "republic of bulgaria"
  }

  /**
   * The lines of the FIRST address block: from the first line that carries a city up to (but not
   * including) the next line that starts another city. Returns the whole content when no city line
   * is found (so the caller's marker/Azure fallbacks still run).
   */
  static String firstAddressBlock(String content) {
    if (content == null) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    for (String l : content.split("\\r?\\n")) {
      String t = l.trim();
      if (!t.isEmpty()) {
        lines.add(t);
      }
    }
    if (lines.isEmpty()) {
      return content;
    }
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (cityFromLine(lines.get(i)) != null) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return content;
    }
    StringBuilder sb = new StringBuilder(lines.get(start));
    for (int i = start + 1; i < lines.size(); i++) {
      if (cityFromLine(lines.get(i)) != null) {
        break; // a second address starts here — stop.
      }
      sb.append("\n").append(lines.get(i));
    }
    return sb.toString();
  }

  /**
   * The first supplier address LINE in a segment's raw OCR text: the first line that carries an
   * EXPLICIT settlement marker ("гр."/"град"/"с."/"село") followed by a real (non-country) city —
   * on a fiscal receipt this is the supplier block printed at the top, above the customer. Only the
   * explicit marker is accepted (not the loose bare-name heuristic), so junk all-caps lines such as
   * "ФИСКАЛЕН БОН" / "ОРИГИНАЛ" are never mistaken for a city. Returns the WHOLE line (city +
   * street on one line, as receipts print it) for the address builder, or {@code null} when no such
   * line exists. Reads only the text it is given — a split child holds only its own slice, so it
   * can never borrow another invoice's/page's address.
   */
  public static String firstSupplierAddressLine(String content) {
    if (content == null) {
      return null;
    }
    for (String line : content.split("\\r?\\n")) {
      String t = line.trim();
      Matcher m = CITY_MARKER.matcher(t);
      if (m.find() && !isCountry(m.group(1))) {
        // Receipts print the city and street on ONE line ("гр. София ул. Лучезар Станчев 3"). Put
        // the street on its own line so the (case-insensitive) city capture does not swallow the
        // leading street keyword and yield "София ул" — the builder then reads city + street
        // cleanly.
        return t.replaceFirst("(?iu)\\s+((?:ж\\.?\\s*к\\.?|жк|ул\\.|бул\\.|кв\\.))", "\n$1");
      }
    }
    return null;
  }

  /** The city of the first address block (its first line). */
  static String cityFromBlock(String block) {
    if (block == null) {
      return null;
    }
    for (String line : block.split("\\r?\\n")) {
      String c = cityFromLine(line);
      if (c != null) {
        return c;
      }
    }
    return null;
  }

  /**
   * The street portion of the first address block: the block text with the city marker + name
   * stripped from the line that carried the city. Null when empty.
   */
  static String streetFromBlock(String block, String city) {
    if (block == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    boolean strippedCity = false;
    for (String line : block.split("\\r?\\n")) {
      String t = line.trim();
      if (t.isEmpty()) {
        continue;
      }
      if (!strippedCity && city != null && cityFromLine(t) != null) {
        // Drop the "гр./с." marker and the city name; keep any street that trailed it on the line.
        String stripped = t.replaceFirst("(?iu)^(?:гр\\.\\s*|с\\.\\s*|град\\s+|село\\s+)", "");
        if (stripped.regionMatches(true, 0, city, 0, city.length())) {
          stripped = stripped.substring(city.length());
        }
        stripped = stripped.replaceFirst("^[\\s,;.-]+", "").trim();
        strippedCity = true;
        t = stripped;
      }
      if (!t.isEmpty()) {
        if (sb.length() > 0) {
          sb.append(" ");
        }
        sb.append(t);
      }
    }
    String r = sb.toString().replaceAll("\\s+", " ").trim();
    return r.isEmpty() ? null : r;
  }

  private static boolean blank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String joinNonBlank(String a, String b) {
    String s = ((blank(a) ? "" : a.trim()) + " " + (blank(b) ? "" : b.trim())).trim();
    return s.isEmpty() ? null : s;
  }

  public static Parsed parse(String raw) {
    Parsed p = new Parsed();
    p.countryIso = countryIso(raw);
    if (raw == null) {
      return p;
    }
    String s = raw.replaceAll("\\s+", " ").trim();
    if (s.isEmpty()) {
      return p;
    }

    Matcher zm = ZIP.matcher(s);
    if (zm.find()) {
      p.zip = zm.group(1);
    }

    Matcher cm = CITY.matcher(s);
    if (cm.find()) {
      p.cityName = cm.group(1).trim();
    } else {
      // No marker: accept only a single plain Cyrillic word (whole string, or after a label colon).
      String candidate = s.contains(":") ? s.substring(s.lastIndexOf(':') + 1).trim() : s;
      if (SINGLE_CYR.matcher(candidate).matches()) {
        p.cityName = candidate;
      }
    }

    Matcher nm = NUMBER.matcher(s);
    if (nm.find()) {
      p.number = nm.group(1).trim();
    }

    Matcher sm = STREET.matcher(s);
    if (sm.find()) {
      p.street = sm.group(1).replaceAll("\\s+", " ").trim();
    }
    return p;
  }

  // City after a "Град"/"гр."/"град" label (BG invoices often put the city in a separate labeled
  // field, NOT inside the vendor address line, e.g. "Град\nСофия"). Colon/whitespace/newline
  // between
  // the label and the Cyrillic city name are all tolerated.
  private static final Pattern CITY_LABEL =
      Pattern.compile(
          "(?:^|[\\s,(])(?:Град|град|гр\\.)\\s*:?\\s*([А-ЯЁ][А-Яа-яЁё-]+(?:[ -][А-ЯЁ][А-Яа-яЁё-]+)*)",
          F);

  /**
   * Fallback city extraction from the WHOLE OCR text via "Град/гр. &lt;City&gt;" labels — used only
   * when the vendor address block itself carries no city. Returns the city ONLY when the document
   * mentions exactly ONE distinct city (so a vendor/customer city pair with different cities is
   * left ambiguous and never guessed); otherwise null.
   */
  public static String singleLabeledCity(String content) {
    if (content == null) {
      return null;
    }
    Matcher m = CITY_LABEL.matcher(content);
    String found = null;
    while (m.find()) {
      String city = m.group(1).trim();
      if (found == null) {
        found = city;
      } else if (!norm(found).equals(norm(city))) {
        return null; // more than one distinct city — ambiguous, do not guess
      }
    }
    return found;
  }

  // A postcode and a settlement written together with no label at all, in either order:
  // "1000 София", "София 1000", "гр. София 1000". This is how a person types an address and how
  // most invoice headers print one, so it has to be read without a П.К. marker.
  private static final Pattern BARE_ZIP_FIRST =
      Pattern.compile(
          "^(\\d{4})\\s*,?\\s*(?:гр\\.\\s*|град\\s+|с\\.\\s*|село\\s+)?"
              + "([А-ЯЁ][А-Яа-яЁё-]+(?:[ -][А-ЯЁ][А-Яа-яЁё-]+)*)$",
          F);
  private static final Pattern BARE_CITY_FIRST =
      Pattern.compile(
          "^(?:гр\\.\\s*|град\\s+|с\\.\\s*|село\\s+)?"
              + "([А-ЯЁ][А-Яа-яЁё-]+(?:[ -][А-ЯЁ][А-Яа-яЁё-]+)*)\\s*,?\\s*(\\d{4})$",
          F);

  /**
   * Postcode and settlement from an unlabelled segment such as {@code "1000 София"}.
   *
   * <p>{@link #ZIP} only accepts a postcode after an explicit {@code П.К.} marker and {@link #CITY}
   * only a settlement after {@code гр./град/с./село}. Both are right for scanning a whole document,
   * where a bare four-digit number could be anything. They are wrong for an address somebody has
   * <em>typed</em> — "1000 София, ул. Раковски 12" is the ordinary written form and carries neither
   * marker, so it yielded no city and no postcode and the address was refused.
   *
   * <p>Deliberately anchored per comma-separated segment: the whole segment must be exactly a
   * postcode and a Cyrillic name, in either order. That is narrow enough that a street number or a
   * document reference cannot be mistaken for a postcode, and it never invents the half that is
   * absent — a segment of "София" alone yields the city with no zip, which is then taken from the
   * settlement dataset.
   *
   * @return a {@link Parsed} carrying only {@code cityName} and/or {@code zip}; never null
   */
  public static Parsed bareZipCity(String raw) {
    Parsed p = new Parsed();
    if (raw == null || raw.trim().isEmpty()) {
      return p;
    }
    for (String segment : raw.replaceAll("\\s+", " ").split(",")) {
      String s = segment.trim();
      if (s.isEmpty()) {
        continue;
      }
      Matcher zipFirst = BARE_ZIP_FIRST.matcher(s);
      if (zipFirst.matches()) {
        p.zip = zipFirst.group(1);
        p.cityName = zipFirst.group(2).trim();
        return p;
      }
      Matcher cityFirst = BARE_CITY_FIRST.matcher(s);
      if (cityFirst.matches()) {
        p.cityName = cityFirst.group(1).trim();
        p.zip = cityFirst.group(2);
        return p;
      }
    }
    // No postcode anywhere: accept a segment that is nothing but a settlement name, which is the
    // "София, ул. X №5" shape once the street segment is set aside.
    for (String segment : raw.replaceAll("\\s+", " ").split(",")) {
      String s = segment.trim();
      Matcher single = BARE_CITY_FIRST.matcher(s + " 0000");
      if (SINGLE_CYR.matcher(s).matches() || single.matches()) {
        p.cityName = s.replaceFirst("(?iu)^(?:гр\\.\\s*|град\\s+|с\\.\\s*|село\\s+)", "").trim();
        return p;
      }
    }
    return p;
  }

  /**
   * @return the ISO-2 country code the address explicitly indicates; "BG" for the default BG OCR
   *     flow when nothing else is stated. Never guesses an arbitrary country.
   */
  public static String countryIso(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return "BG";
    }
    if (BG.matcher(raw).find()) {
      return "BG";
    }
    if (UK.matcher(raw).find()) {
      return "GB";
    }
    return "BG";
  }

  /** Normalize a comparison token: trim, collapse spaces, lower-case; null → "". */
  public static String norm(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase();
  }

  /** True when a candidate address's fields match the parsed OCR address (normalized). */
  public static boolean matches(
      Parsed p, String cityName, String zip, String street, String number) {
    return norm(cityName).equals(norm(p.cityName))
        && norm(zip).equals(norm(p.zip))
        && norm(street).equals(norm(p.street))
        && norm(number).equals(norm(p.number));
  }
}
