package com.axelor.apps.aiaccountant.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One blocked invoice must not stop the invoices that are fine.
 *
 * <p>The abort used to be global: any refusal skipped every write left in the response, so «оправи
 * и осчетоводи тези четири» that hit a duplicate number on the second invoice left the third and
 * fourth untouched — and the user had to type «продължи» to get work that had nothing to do with
 * the record that failed. The rule was right about the record that failed and wrong about every
 * other one.
 *
 * <p>No Guice: the runner's registry lookup is overridden with a small stub, which is enough
 * because the behaviour under test is entirely about ordering and abort scope.
 */
class AiToolBatchIsolationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A stand-in for the OCR write tools: WRITE, ordered, and keyed on uploadId. */
  private static AiTool write(String name, int order) {
    return new AiTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return name;
      }

      @Override
      public JsonNode parametersSchema() {
        return MAPPER.createObjectNode();
      }

      @Override
      public AiToolKind kind() {
        return AiToolKind.WRITE;
      }

      @Override
      public AiCapability capability() {
        return AiCapability.SUPPLIER_INVOICE;
      }

      @Override
      public int writeOrder() {
        return order;
      }

      @Override
      public Object execute(JsonNode args) {
        return null;
      }
    };
  }

  /** Company-wide: no single record, so a failure here must still stop everything. */
  private static AiTool untargetedWrite(String name, int order) {
    AiTool delegate = write(name, order);
    return new AiTool() {
      @Override
      public String name() {
        return delegate.name();
      }

      @Override
      public String description() {
        return delegate.description();
      }

      @Override
      public JsonNode parametersSchema() {
        return delegate.parametersSchema();
      }

      @Override
      public AiToolKind kind() {
        return AiToolKind.WRITE;
      }

      @Override
      public AiCapability capability() {
        return AiCapability.ACCOUNTING;
      }

      @Override
      public int writeOrder() {
        return order;
      }

      @Override
      public String targetKey(JsonNode args) {
        return null;
      }

      @Override
      public Object execute(JsonNode args) {
        return null;
      }
    };
  }

  private static final Map<String, AiTool> TOOLS = new LinkedHashMap<>();

  static {
    TOOLS.put("edit_supplier_invoice", write("edit_supplier_invoice", 20));
    TOOLS.put("validate_supplier_invoice", write("validate_supplier_invoice", 30));
    TOOLS.put("ventilate_supplier_invoice", write("ventilate_supplier_invoice", 40));
    TOOLS.put("fix_partner_account", untargetedWrite("fix_partner_account", 3));
  }

  /** Fails the named (tool, uploadId) pairs; everything else succeeds. */
  private static final class Runner extends AiToolBatchRunner {
    private final Set<String> failing;
    final List<String> executed = new ArrayList<>();

    Runner(Set<String> failing) {
      super(null);
      this.failing = failing;
    }

    @Override
    protected AiTool findTool(String toolName) {
      return TOOLS.get(toolName);
    }

    @Override
    protected String dispatch(String name, String arguments) {
      String key = name + "@" + upload(arguments);
      if (failing.contains(key)) {
        return "{\"error\":\"tool_error\",\"blocker\":\"DUPLICATE_INVOICE_NUMBER\","
            + "\"message\":\"дублиран номер\"}";
      }
      executed.add(key);
      return "{\"status\":\"ok\"}";
    }

    private static String upload(String arguments) {
      try {
        return MAPPER.readTree(arguments).path("uploadId").asText("-");
      } catch (Exception e) {
        return "-";
      }
    }
  }

  private static FoundryResponse.FunctionCall call(String name, Integer uploadId) {
    ObjectNode args = MAPPER.createObjectNode();
    if (uploadId != null) {
      args.put("uploadId", uploadId);
    }
    return new FoundryResponse.FunctionCall(name + "-" + uploadId, name, args.toString());
  }

  /** The whole four-invoice workflow the operating procedure asks the model to emit at once. */
  private static List<FoundryResponse.FunctionCall> fourInvoices() {
    List<FoundryResponse.FunctionCall> calls = new ArrayList<>();
    for (int id : new int[] {941, 942, 943, 944}) {
      calls.add(call("edit_supplier_invoice", id));
      calls.add(call("validate_supplier_invoice", id));
      calls.add(call("ventilate_supplier_invoice", id));
    }
    return calls;
  }

  @BeforeEach
  void openTurn() {
    AiTurnToolLog.begin();
  }

  @AfterEach
  void closeTurn() {
    AiTurnToolLog.end();
  }

  /** A, C and D finish; only B stops at the record that actually failed. */
  @Test
  void aBlockedInvoiceDoesNotStopTheIndependentOnes() {
    Runner runner = new Runner(new LinkedHashSet<>(Arrays.asList("validate_supplier_invoice@942")));

    runner.executeAll(fourInvoices(), new ArrayList<>());

    for (int id : new int[] {941, 943, 944}) {
      assertTrue(
          runner.executed.contains("ventilate_supplier_invoice@" + id),
          "invoice " + id + " never failed and must still have been posted");
    }
    assertFalse(
        runner.executed.contains("ventilate_supplier_invoice@942"),
        "the record that failed validation must not be posted");
    assertTrue(runner.executed.contains("edit_supplier_invoice@942"), "its earlier write did run");
  }

  /** Per-record ordering survives: nothing is posted before it is validated. */
  @Test
  void perRecordOrderingIsPreserved() {
    Runner runner = new Runner(new LinkedHashSet<>());

    runner.executeAll(fourInvoices(), new ArrayList<>());

    for (int id : new int[] {941, 942, 943, 944}) {
      int edit = runner.executed.indexOf("edit_supplier_invoice@" + id);
      int validate = runner.executed.indexOf("validate_supplier_invoice@" + id);
      int post = runner.executed.indexOf("ventilate_supplier_invoice@" + id);
      assertTrue(edit < validate && validate < post, "edit→validate→post for " + id);
    }
  }

  /** The blocked record is reported as blocked, with the reason and against its own id. */
  @Test
  void theFailureIsAttributedToItsOwnRecord() {
    Runner runner = new Runner(new LinkedHashSet<>(Arrays.asList("validate_supplier_invoice@942")));

    runner.executeAll(fourInvoices(), new ArrayList<>());

    List<AiTurnToolLog.Entry> failed = AiTurnToolLog.failedWrites();
    assertEquals(2, failed.size(), "one refusal, and one write skipped behind it");
    assertEquals("validate_supplier_invoice", failed.get(0).getName());
    assertEquals("Upload#942", failed.get(0).getTarget());
    assertEquals("дублиран номер", failed.get(0).getReason());
    assertEquals(AiTurnToolLog.Status.SKIPPED, failed.get(1).getStatus());
    assertEquals("Upload#942", failed.get(1).getTarget());

    assertTrue(AiTurnToolLog.writtenTargets().contains("Upload#941"));
    assertFalse(
        AiTurnToolLog.writtenTargets().contains("Upload#942")
            && AiTurnToolLog.writtenTargets().size() == 1);
  }

  /**
   * The refusal's code reaches the ledger, so the workflow never has to re-read its sentence.
   *
   * <p>Both fields already travelled to the model; only the ledger was dropping them, which is why
   * classification fell back to prose even when the tool had said plainly what kind of refusal it
   * was. A skipped call carries its own code — {@code not_executed} — because nothing was attempted
   * on it and it must not be reported as a blocked record.
   */
  @Test
  void theRefusalsCodeIsKeptAlongsideItsMessage() {
    Runner runner = new Runner(new LinkedHashSet<>(Arrays.asList("validate_supplier_invoice@942")));

    runner.executeAll(fourInvoices(), new ArrayList<>());

    List<AiTurnToolLog.Entry> failed = AiTurnToolLog.failedWrites();
    assertEquals("DUPLICATE_INVOICE_NUMBER", failed.get(0).getBlocker());
    assertEquals("not_executed", failed.get(1).getBlocker());
  }

  /** The envelope code is used when a refusal names no business blocker. */
  @Test
  void theEnvelopeCodeIsTheFallbackNotTheMessage() {
    Runner runner = new Runner(new LinkedHashSet<>());

    assertEquals(
        "DUPLICATE_INVOICE_NUMBER",
        runner.blockerOf("{\"error\":\"tool_error\",\"blocker\":\"DUPLICATE_INVOICE_NUMBER\"}"));
    assertEquals("unknown_tool", runner.blockerOf("{\"error\":\"unknown_tool\"}"));
    assertEquals(
        "write_not_allowed",
        runner.blockerOf("{\"error\":\"write_not_allowed\",\"blocker\":\"\"}"),
        "an empty blocker is not a code");
    assertNull(runner.blockerOf("not json at all"));
  }

  /**
   * The conservative half. A failure the server cannot pin to one record — company configuration,
   * shared master data — still stops the whole batch, because independence would then be an
   * assumption rather than something observed.
   */
  @Test
  void anUnattributableFailureStillStopsEverything() {
    List<FoundryResponse.FunctionCall> calls = new ArrayList<>();
    calls.add(call("fix_partner_account", null));
    calls.addAll(fourInvoices());
    Runner runner = new Runner(new LinkedHashSet<>(Arrays.asList("fix_partner_account@-")));

    runner.executeAll(calls, new ArrayList<>());

    assertTrue(runner.executed.isEmpty(), "nothing may run behind a failure of unknown scope");
  }

  /** An exact duplicate inside one response still runs once. */
  @Test
  void anExactDuplicateRunsOnce() {
    Runner runner = new Runner(new LinkedHashSet<>());

    runner.executeAll(
        Arrays.asList(call("edit_supplier_invoice", 941), call("edit_supplier_invoice", 941)),
        new ArrayList<>());

    assertEquals(1, runner.executed.size());
  }

  /** Every call gets an answer, including the skipped ones — an unanswered id strands the chain. */
  @Test
  void everyCallIsAnswered() {
    Runner runner = new Runner(new LinkedHashSet<>(Arrays.asList("validate_supplier_invoice@942")));
    List<FoundryResponse.FunctionCall> calls = fourInvoices();

    assertEquals(calls.size(), runner.executeAll(calls, new ArrayList<>()).size());
  }
}
