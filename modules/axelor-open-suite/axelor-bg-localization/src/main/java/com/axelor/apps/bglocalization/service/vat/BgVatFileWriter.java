package com.axelor.apps.bglocalization.service.vat;

import com.google.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns preformatted diary lines into an encoded byte stream.
 *
 * <p>Owns the two hard encoding requirements for NAP files: <b>CP1251</b> (Windows-1251) by
 * default, or UTF-8 when explicitly selected, and <b>CRLF</b> line endings. The suite's shared
 * writers ({@code CsvHelper}/{@code FileHelper} in axelor-utils) write in the platform-default
 * charset, so they are deliberately NOT reused here.
 *
 * <p>Note: Java's UTF-8 encoder does not emit a BOM, so the UTF-8 path is BOM-free by construction.
 */
@Singleton
public class BgVatFileWriter {

  public static final String CRLF = "\r\n";

  /** Encoding codes ({@code BgVatExportRequestDto#encoding}). */
  public static final String ENCODING_CP1251 = "cp1251";

  public static final String ENCODING_UTF8 = "utf8";

  private static final String WINDOWS_1251 = "windows-1251";

  /** Resolve the wizard encoding code to a concrete charset. Defaults to Windows-1251. */
  public Charset resolveCharset(String encoding) {
    if (ENCODING_UTF8.equalsIgnoreCase(encoding)) {
      return StandardCharsets.UTF_8;
    }
    // cp1251 (default) and any unknown value fall back to the legally-required Windows-1251.
    return Charset.forName(WINDOWS_1251);
  }

  /**
   * Join the given lines with CRLF and encode them with the resolved charset. No BOM is written.
   *
   * @param lines preformatted diary lines (already laid out by an adapter)
   * @param encoding {@code cp1251} or {@code utf8}
   */
  public byte[] toBytes(List<String> lines, String encoding) throws IOException {
    Charset charset = resolveCharset(encoding);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (OutputStreamWriter writer = new OutputStreamWriter(out, charset)) {
      for (String line : lines) {
        writer.write(line == null ? "" : line);
        writer.write(CRLF);
      }
    }
    return out.toByteArray();
  }

  // TODO WS-2: create a MetaFile from these bytes for download:
  //   MetaFile mf = Beans.get(MetaFiles.class).upload(new ByteArrayInputStream(bytes), fileName);
  //   return via ActionView "html" ->
  // ws/rest/com.axelor.meta.db.MetaFile/{id}/content/download?v={version}
  // TODO WS-3: assert output has NO UTF-8 BOM; verify CP1251 round-trips Cyrillic; verify CRLF
  // endings.
}
