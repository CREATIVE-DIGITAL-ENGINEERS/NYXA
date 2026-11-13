package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.db.AiTurnMemory;
import com.axelor.apps.aiaccountant.db.repo.AiTurnMemoryRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's own copy of the visible conversation in one chat panel — every user message and
 * assistant reply exchanged so far, kept in order until the user clears it.
 *
 * <h2>Two different things read this, at two different depths</h2>
 *
 * A bare affirmation — «да», «направи го», «продължи», "same but the date +1 day" — means nothing
 * on its own; it means something only next to whatever the assistant just proposed. That is {@link
 * #load}: the LAST exchange only, and only while it is still recent — see {@link #MAX_AGE} — which
 * is what {@code AiRouteSelector} checks a claimed continuation against.
 *
 * <p>The Router Agent needs more than that: resolving what "it", "the same one" or an unfamiliar
 * word refers to routinely depends on more than one turn back. That is {@link #loadHistory} — the
 * complete list, oldest first, with no age filter and no fixed window. See {@code
 * AiRouterContract}, which is the only caller.
 *
 * <h2>What is never done here</h2>
 *
 * The list is appended to, never rewritten and never trimmed by this class. There is no 3, 10 or 30
 * message window: the user, not a timer or a counter, decides when a conversation is over, by using
 * the "Clear context" action — which starts a fresh {@code conversationId} and simply leaves this
 * row behind, unread. Whatever a caller may not be able to fit in one request is bounded at the
 * point that builds that request, explicitly, never by silently dropping rows here.
 *
 * <h2>What it authorises</h2>
 *
 * Nothing, by itself. {@code AiRouteSelector} still checks the record on screen against the last
 * exchange's {@code recordRef} before a continuation is accepted, and every write still runs the
 * full {@code AiWriteAuthorisation} chain exactly as an explicit instruction would.
 */
@Singleton
public class AiTurnMemoryStore {

  private static final Logger LOG = LoggerFactory.getLogger(AiTurnMemoryStore.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * How long the LAST exchange is still eligible to be continued by a bare affirmation.
   *
   * <p>Only bounds {@link #load}. It says nothing about how long the row, or the history inside it,
   * is kept — that is until the user clears the conversation, unconditionally.
   */
  private static final Duration MAX_AGE = Duration.ofHours(2);

  /** One exchange: what the user said, what the assistant answered, and when. */
  public static final class Reading {

    private final String userMessage;
    private final String assistantReply;
    private final String recordRef;

    public Reading(String userMessage, String assistantReply, String recordRef) {
      this.userMessage = userMessage;
      this.assistantReply = assistantReply;
      this.recordRef = recordRef;
    }

    public String userMessage() {
      return userMessage;
    }

    public String assistantReply() {
      return assistantReply;
    }

    /** The record the page had open when the reply was produced, or null when none was. */
    public String recordRef() {
      return recordRef;
    }

    /** False for an entry whose reply was never recorded, or was blank. */
    public boolean hasOffer() {
      return assistantReply != null && !assistantReply.trim().isEmpty();
    }
  }

  /**
   * The last exchange for this user, company and conversation, or null when there is none usable.
   *
   * <p>Never throws: a store that cannot be read leaves a continuation unrecognised, which is the
   * same outcome as if this exchange had never been said — a fresh reading, not a failed turn.
   */
  public Reading load(Long userId, Long companyId, String conversationId) {
    AiTurnMemory row = find(userId, companyId, conversationId);
    if (row == null) {
      return null;
    }
    LocalDateTime taken = row.getLastTurnDateTime();
    if (taken == null || taken.isBefore(LocalDateTime.now().minus(MAX_AGE))) {
      return null;
    }
    List<Reading> all = parse(row.getHistoryJson());
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }

  /**
   * The complete stored conversation for this user, company and conversation, oldest first.
   *
   * <p>Never age-filtered and never windowed — see the class documentation. Never throws: a store
   * that cannot be read is read as an empty conversation, which is the same starting point a first
   * message has.
   */
  public List<Reading> loadHistory(Long userId, Long companyId, String conversationId) {
    try {
      AiTurnMemory row = find(userId, companyId, conversationId);
      return row == null ? Collections.emptyList() : parse(row.getHistoryJson());
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the conversation history: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /** The row for this user, company and conversation, or null. */
  protected AiTurnMemory find(Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return null;
    }
    try {
      return Beans.get(AiTurnMemoryRepository.class)
          .all()
          .filter(
              "self.user.id = :userId AND self.company.id = :companyId"
                  + " AND self.conversationId = :conversationId")
          .bind("userId", userId)
          .bind("companyId", companyId)
          .bind("conversationId", conversationId)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not look up the turn memory: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Appends this turn's own words and reply to the stored conversation.
   *
   * <p>Best-effort, like its neighbours: a turn whose exchange could not be written leaves nothing
   * for a later turn to read back, which costs a fresh reading rather than the turn itself.
   */
  public boolean record(
      Long userId,
      Long companyId,
      String conversationId,
      String userMessage,
      String assistantReply,
      String recordRef) {
    if (userId == null || companyId == null || conversationId == null) {
      return false;
    }
    AiTurnMemoryStore proxied = Beans.get(AiTurnMemoryStore.class);
    try {
      return proxied.persist(
          userId, companyId, conversationId, userMessage, assistantReply, recordRef);
    } catch (RuntimeException e) {
      // The ordinary outcome when two turns of one panel finish at once. One retry, against the
      // row as it now stands — losing the race here costs which of two exchanges is appended
      // first, never correctness: whichever lands, a later affirmation is checked against the
      // record on screen before it is trusted, and the Router reads whatever order the rows
      // committed in.
      LOG.debug("AI Accountant: retrying the turn memory write after {}", e.getMessage());
      try {
        return proxied.persist(
            userId, companyId, conversationId, userMessage, assistantReply, recordRef);
      } catch (RuntimeException retry) {
        LOG.debug("AI Accountant: could not record the turn memory: {}", retry.getMessage());
        return false;
      }
    }
  }

  @Transactional(rollbackOn = Exception.class)
  protected boolean persist(
      Long userId,
      Long companyId,
      String conversationId,
      String userMessage,
      String assistantReply,
      String recordRef) {
    AiTurnMemoryRepository repo = Beans.get(AiTurnMemoryRepository.class);
    AiTurnMemory row = find(userId, companyId, conversationId);
    List<Reading> history;
    if (row == null) {
      row = new AiTurnMemory();
      row.setUser(Beans.get(UserRepository.class).find(userId));
      row.setCompany(Beans.get(CompanyRepository.class).find(companyId));
      row.setConversationId(conversationId);
      history = new ArrayList<>();
    } else {
      history = new ArrayList<>(parse(row.getHistoryJson()));
    }
    // Appended, never rewritten, and never cut down to a fixed length: this is the whole of what
    // "keep the complete conversation" means at the storage layer. Nothing here decides how much
    // of it a caller may use — that bound belongs to whichever request actually has a size limit
    // to respect (see AiRouterContract#firstKept), not to the row this class keeps.
    history.add(new Reading(trimmed(userMessage), trimmed(assistantReply), recordRef));
    row.setHistoryJson(serialize(history));
    row.setLastTurnDateTime(LocalDateTime.now());
    repo.save(row);
    return true;
  }

  private static String trimmed(String value) {
    return value == null ? null : value.trim();
  }

  /**
   * Reads the stored array, oldest first. An unreadable or empty value is an empty conversation.
   */
  static List<Reading> parse(String json) {
    if (json == null || json.trim().isEmpty()) {
      return Collections.emptyList();
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      if (!root.isArray()) {
        return Collections.emptyList();
      }
      List<Reading> out = new ArrayList<>();
      for (JsonNode entry : root) {
        out.add(
            new Reading(
                textOrNull(entry, "userMessage"),
                textOrNull(entry, "assistantReply"),
                textOrNull(entry, "recordRef")));
      }
      return out;
    } catch (Exception e) {
      LOG.debug("AI Accountant: could not parse the stored conversation: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  static String serialize(List<Reading> history) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Reading entry : history) {
      ObjectNode node = array.addObject();
      putOrNull(node, "userMessage", entry.userMessage());
      putOrNull(node, "assistantReply", entry.assistantReply());
      putOrNull(node, "recordRef", entry.recordRef());
    }
    return array.toString();
  }

  private static void putOrNull(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static String textOrNull(JsonNode entry, String field) {
    JsonNode value = entry.get(field);
    return value == null || value.isNull() ? null : value.asText(null);
  }
}
