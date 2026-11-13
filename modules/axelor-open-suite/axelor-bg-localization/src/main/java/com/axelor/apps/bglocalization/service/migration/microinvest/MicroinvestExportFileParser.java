package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

/**
 * Reads the JSON export produced by the Microinvest export tool. Its property names are written to
 * match {@link MicroinvestSourceModels}'s Java field names exactly, so no naming-strategy
 * translation is needed. There is no live connection to Microinvest anywhere in this module; the
 * export step runs externally, once, against the read-only SQL Server source.
 */
final class MicroinvestExportFileParser {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private MicroinvestExportFileParser() {}

  static MicroinvestSourceModels.ExportRoot parse(byte[] jsonContent) throws IOException {
    return MAPPER.readValue(jsonContent, MicroinvestSourceModels.ExportRoot.class);
  }
}
