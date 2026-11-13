package com.axelor.apps.aiocr.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Reconstructs ONE logical Azure {@code analyzeResult} from the per-chunk results of a PDF that had
 * to be OCR'd in several small page chunks (an Azure per-request page/size limit). The chunking is
 * an internal OCR <b>transport</b> detail — the stitched result is fed into the exact same Phase A
 * segmentation pipeline as a single-call result, and no upload children are created merely because
 * OCR was chunked.
 *
 * <p>It is a proper merge, not a JSON concatenation:
 *
 * <ul>
 *   <li><b>content</b> — concatenated (with a newline separator between chunks);
 *   <li><b>pages[] / documents[] / tables[] / paragraphs[] / styles[] / figures[] / sections[]</b>
 *       — appended in order;
 *   <li><b>every {@code pageNumber}</b> (page objects + all {@code boundingRegions}) is rebased by
 *       the running page offset;
 *   <li><b>every span {@code {offset,length}}</b> has its {@code offset} rebased into the merged
 *       {@code content};
 *   <li><b>polygons / coordinates are left unchanged</b> — page-local coordinates remain valid;
 *       only page references and global content offsets are rebased.
 * </ul>
 *
 * <p>Offsets are measured in Java UTF-16 code units. Azure's {@code stringIndexType} for these
 * documents is BMP-only (Cyrillic + Latin + digits), so code units == code points == text elements
 * and the rebased offsets stay valid.
 */
public final class AzureAnalyzeResultStitcher {

  private static final String SEPARATOR = "\n";

  private static final String[] MERGE_ARRAYS = {
    "pages", "documents", "tables", "paragraphs", "styles", "figures", "sections", "keyValuePairs"
  };

  private final ObjectMapper mapper;

  public AzureAnalyzeResultStitcher(ObjectMapper mapper) {
    this.mapper = mapper != null ? mapper : new ObjectMapper();
  }

  public AzureAnalyzeResultStitcher() {
    this(new ObjectMapper());
  }

  /** Merge the given per-chunk {@code analyzeResult} JSON strings (in page order) into one. */
  public String stitch(List<String> chunkJsons) {
    ObjectNode merged = mapper.createObjectNode();
    StringBuilder content = new StringBuilder();

    // Prepare the target arrays once.
    for (String name : MERGE_ARRAYS) {
      merged.set(name, mapper.createArrayNode());
    }

    int pageBase = 0;
    boolean metaCopied = false;

    for (int i = 0; i < chunkJsons.size(); i++) {
      JsonNode chunk;
      try {
        chunk = mapper.readTree(chunkJsons.get(i));
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Chunk " + i + " is not valid JSON: " + e.getMessage(), e);
      }
      if (chunk == null || !chunk.isObject()) {
        continue;
      }
      if (!metaCopied) {
        copyScalar(chunk, merged, "apiVersion");
        copyScalar(chunk, merged, "modelId");
        copyScalar(chunk, merged, "stringIndexType");
        copyScalar(chunk, merged, "contentFormat");
        metaCopied = true;
      }

      int offsetBase = content.length(); // UTF-16 code units already accumulated

      // Rebase this chunk's structures (pageNumber += pageBase, span.offset += offsetBase) then
      // append. Polygons are left untouched.
      for (String name : MERGE_ARRAYS) {
        JsonNode arr = chunk.get(name);
        if (arr == null || !arr.isArray()) {
          continue;
        }
        ArrayNode target = (ArrayNode) merged.get(name);
        for (JsonNode el : arr) {
          rebase(el, pageBase, offsetBase);
          target.add(el);
        }
      }

      String chunkContent = chunk.path("content").asText("");
      content.append(chunkContent);
      if (i < chunkJsons.size() - 1) {
        content.append(SEPARATOR);
      }

      pageBase += countPages(chunk);
    }

    merged.put("content", content.toString());
    return merged.toString();
  }

  /** Number of physical pages contributed by a chunk (its {@code pages[]} size). */
  private static int countPages(JsonNode chunk) {
    JsonNode pages = chunk.get("pages");
    if (pages != null && pages.isArray() && pages.size() > 0) {
      return pages.size();
    }
    // Fallback: highest pageNumber seen in boundingRegions (rare — a result without pages[]).
    int[] max = {0};
    scanMaxPageNumber(chunk, max);
    return Math.max(max[0], 1);
  }

  private static void scanMaxPageNumber(JsonNode node, int[] max) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      JsonNode pn = node.get("pageNumber");
      if (pn != null && pn.isInt()) {
        max[0] = Math.max(max[0], pn.asInt());
      }
      node.fields().forEachRemaining(e -> scanMaxPageNumber(e.getValue(), max));
    } else if (node.isArray()) {
      for (JsonNode el : node) {
        scanMaxPageNumber(el, max);
      }
    }
  }

  /**
   * Recursively rebase a node in place: any {@code pageNumber} gains {@code pageBase}; any span
   * object ({@code offset}+{@code length}) has its {@code offset} gain {@code offsetBase}. Polygons
   * and every other value are untouched.
   */
  private static void rebase(JsonNode node, int pageBase, int offsetBase) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      JsonNode pn = obj.get("pageNumber");
      if (pn != null && pn.isNumber()) {
        obj.put("pageNumber", pn.asInt() + pageBase);
      }
      JsonNode off = obj.get("offset");
      JsonNode len = obj.get("length");
      if (off != null && off.isNumber() && len != null && len.isNumber()) {
        obj.put("offset", off.asLong() + offsetBase);
      }
      obj.fields().forEachRemaining(e -> rebase(e.getValue(), pageBase, offsetBase));
    } else if (node.isArray()) {
      for (JsonNode el : node) {
        rebase(el, pageBase, offsetBase);
      }
    }
  }

  private static void copyScalar(JsonNode from, ObjectNode to, String field) {
    JsonNode v = from.get(field);
    if (v != null && !v.isNull()) {
      to.set(field, v);
    }
  }
}
