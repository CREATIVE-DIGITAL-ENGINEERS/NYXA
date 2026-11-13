package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiMessageTargetResolver;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.apps.aiocr.service.supplier.SupplierMaintenanceService;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The supplier a supplier-maintenance message names, when no supplier is open on screen.
 *
 * <p>Purchases → Suppliers only: {@code isSupplier}, not a contact, not archived, in the active
 * company's scope, and never a company's own partner. Two keys, both from the user's own words:
 *
 * <ul>
 *   <li><b>ЕИК / VAT number</b> — exact. An existing supplier answers {@code Partner#id}; for a
 *       create instruction with a labelled ЕИК, {@code PartnerCreate:<company>:<eik>} — the same
 *       synthetic target {@code manage_supplier(create)} declares, whether or not somebody already
 *       holds the code, so a duplicate reaches {@code SupplierMaintenanceService}'s own check and
 *       is refused there, accurately and terminally.
 *   <li><b>Name</b> — only when no code was given, and only when exactly one supplier's name (legal
 *       form and quotes ignored) appears in the message as whole words. Two candidates is nothing.
 * </ul>
 *
 * <h2>Create or update is read from the route, never from the verbs</h2>
 *
 * {@code MAINTAIN_SUPPLIER} is one task for both, so something has to say which. It used to be a
 * hand-kept list of Bulgarian and English stems — «създ», «направи», «нов », "create", "add
 * supplier". That list is a magic-phrase interface: «заведи доставчик с ЕИК …» is ordinary
 * Bulgarian for a create and was not on it, so the instruction silently became an update, resolved
 * no target, and was refused with a message about a record the user had named plainly. It also had
 * to be kept in step by hand with {@code AiTaskResolver}'s own, separate verb list, and had already
 * drifted from it once.
 *
 * <p>{@link AiWriteOperation} on the route carries it instead: the router reads the sentence once,
 * in whatever words it was written, and the server acts on the structured answer. Nothing here
 * reads the message for intent — only for the ЕИК and the name, which are concrete values that
 * deterministic parsing is exactly right for.
 *
 * <p><b>Unstated is read as UPDATE, deliberately.</b> With no router answer (the deterministic
 * fallback route) the conservative reading of a maintenance instruction is that it is about a
 * supplier that already exists: that authorises a change to one named record, where reading it as
 * CREATE would mint the authority to add a partner out of a sentence nobody classified. The cost is
 * that a creation typed while the router is unavailable resolves no target and is refused, and the
 * user is told so — a refusal, never a wrong write.
 */
@Singleton
public class SupplierMessageTargetResolver implements AiMessageTargetResolver {

  private static final int MAX_REFS = 5;
  private static final int MIN_NAME_LENGTH = 3;

  /** A 9- or 13-digit code, optionally BG-prefixed, not part of a longer number or an IBAN. */
  private static final Pattern CODE =
      Pattern.compile(
          "(?<![\\p{L}\\d])(?:BG\\s?)?(\\d{9}|\\d{13})(?!\\d)", Pattern.CASE_INSENSITIVE);

  /** A message that is nothing but the code — the answer to "what is the supplier's ЕИК?". */
  private static final Pattern BARE_CODE =
      Pattern.compile("\\s*(?:BG\\s?)?(\\d{9}|\\d{13})\\s*[.!]?\\s*", Pattern.CASE_INSENSITIVE);

  /** How many of the newest needs-review uploads are read for a missing supplier. */
  private static final int MAX_PENDING_UPLOADS = 30;

  private static final Pattern LEGAL_FORM =
      Pattern.compile(
          "(?<![\\p{L}\\d])(еоод|оод|еад|ад|ет|сд|кд|eood|ood|ead|ad|et|ltd|gmbh|llc|inc|ag|sa|srl)"
              + "(?![\\p{L}\\d])");

  @Override
  public List<String> resolve(AiDomain domain, String message) {
    // No route to read, so the operation is unstated — see resolve(AiRoute, String).
    return resolve(domain == null ? null : new AiRoute(domain, null, null, null, null), message);
  }

  @Override
  public List<String> resolve(AiRoute route, String message) {
    AiDomain domain = route == null ? null : route.domain();
    if (domain != AiDomain.PARTNER || message == null || message.trim().isEmpty()) {
      return Collections.emptyList();
    }
    Long companyId = companyId();
    if (companyId == null) {
      return Collections.emptyList();
    }
    Set<String> refs = new LinkedHashSet<>();
    boolean anyCode = false;
    boolean create = route.operation() == AiWriteOperation.CREATE;
    Matcher m = CODE.matcher(message);
    while (m.find() && refs.size() < MAX_REFS) {
      String eik = SupplierEik.canonical(m.group(1));
      if (eik == null) {
        continue;
      }
      List<Partner> holders = holders(eik, companyId);
      if (!holders.isEmpty()) {
        // A code that names a record is the answer, even when that record may not be changed here:
        // falling through to the name would aim the instruction at a different supplier.
        anyCode = true;
        if (create) {
          // The user asked to CREATE, and this ЕИК already belongs to somebody — authorise the
          // create attempt itself, never a target on an EXISTING partner. Freezing the existing
          // partner's own ref here (an earlier reading) let a correctly-refused duplicate create
          // leave that ref sitting in the job authorised but untouched — nothing this turn would
          // ever complete or block it — so the job never settled and a later, unrelated explicit
          // instruction inherited it instead of its own target. This way the create attempt reaches
          // SupplierMaintenanceService's own duplicate check, whose refusal is both accurate and
          // terminal: the only target in the job becomes this one, blocked, and the job settles.
          // How many partners hold the code does not change that — two holders used to authorise
          // nothing at all, so the user was told the instruction had no target instead of being
          // told the ЕИК is already taken. A company's own identity still authorises nothing.
          if (holders.stream().noneMatch(this::isCompanyIdentity)) {
            refs.add("PartnerCreate:" + companyId + ":" + eik);
          }
        } else if (holders.size() == 1) {
          Partner partner = holders.get(0);
          if (!isCompanyIdentity(partner) && isExternalSupplier(partner)) {
            refs.add("Partner#" + partner.getId());
          }
        }
      } else if (create && (labelled(message, m.start()) || BARE_CODE.matcher(message).matches())) {
        anyCode = true;
        refs.add("PartnerCreate:" + companyId + ":" + eik);
      }
    }
    if (anyCode) {
      return new ArrayList<>(refs);
    }
    Long byName = uniqueByName(message, companyId);
    return byName == null
        ? Collections.emptyList()
        : Collections.singletonList("Partner#" + byName);
  }

  /**
   * «Създай липсващия доставчик» — a create that names no ЕИК. The supplier is then the one the
   * server's own OCR evidence names: the upload or OCR invoice open on screen, or else the uploads
   * waiting in needs-review on a supplier nobody has created yet — the one whose printed name the
   * message contains. Without either, a pending upload is never a target — not even the only one:
   * the assistant is told to ask for the ЕИК instead of guessing.
   *
   * <p>The ЕИК always comes from the stored extraction ({@link
   * UploadSupplierIdentityRelationship#documentSupplierEik}), never from the model. A supplier that
   * already exists still yields its {@code PartnerCreate:} target, so the create reaches the
   * service's own duplicate check and comes back with the existing supplier's id.
   */
  @Override
  public List<String> resolveFromContext(AiRoute route, String message, PageContext page) {
    if (route == null
        || route.domain() != AiDomain.PARTNER
        || route.operation() != AiWriteOperation.CREATE) {
      return Collections.emptyList();
    }
    Long companyId = companyId();
    if (companyId == null) {
      return Collections.emptyList();
    }
    if (page != null && page.hasRecord()) {
      String model = page.getModel() == null ? "" : page.getModel();
      String kind = model.substring(model.lastIndexOf('.') + 1);
      if ("AiOcrUpload".equals(kind) || "Invoice".equals(kind)) {
        String eik = documentEik(kind + "#" + page.getRecordId(), companyId);
        if (eik != null) {
          return createTarget(eik, companyId);
        }
        // A document is open and does not name a supplier: that is the answer, not a reason to
        // look at some other upload.
        return Collections.emptyList();
      }
    }
    Map<String, String> missing = missingSuppliers(companyId);
    if (missing.isEmpty()) {
      return Collections.emptyList();
    }
    if (message != null) {
      String text = " " + normalise(message) + " ";
      List<String> named = new ArrayList<>();
      for (Map.Entry<String, String> e : missing.entrySet()) {
        String name = e.getValue() == null ? null : normalise(e.getValue());
        if (name != null && name.length() >= MIN_NAME_LENGTH && text.contains(" " + name + " ")) {
          named.add(e.getKey());
        }
      }
      if (named.size() == 1) {
        return createTarget(named.get(0), companyId);
      }
    }
    // Nothing on screen and no single supplier named by the user: a pending upload somewhere in
    // the company is not a target of a vague request, even when it is the only one.
    return Collections.emptyList();
  }

  private List<String> createTarget(String eik, Long companyId) {
    List<Partner> holders = holders(eik, companyId);
    if (holders.stream().anyMatch(this::isCompanyIdentity)) {
      return Collections.emptyList();
    }
    return Collections.singletonList("PartnerCreate:" + companyId + ":" + eik);
  }

  /**
   * The supplier ЕИК the document behind an upload / OCR invoice ref attests to, or null — also
   * when the document belongs to another company than the one the create is for.
   */
  protected String documentEik(String documentRef, Long companyId) {
    UploadSupplierIdentityRelationship docs = Beans.get(UploadSupplierIdentityRelationship.class);
    AiOcrUpload upload = docs.resolveUpload(documentRef);
    if (upload == null
        || upload.getCompany() == null
        || !companyId.equals(upload.getCompany().getId())) {
      return null;
    }
    try {
      return UploadSupplierIdentityRelationship.documentSupplierEik(docs.extraction(upload));
    } catch (RuntimeException notStored) {
      return null;
    }
  }

  /**
   * ЕИК → printed supplier name, for the newest needs-review uploads of the company whose document
   * names a supplier that no partner of this company holds yet.
   */
  protected Map<String, String> missingSuppliers(Long companyId) {
    List<AiOcrUpload> uploads =
        JPA.em()
            .createQuery(
                "SELECT self FROM AiOcrUpload self WHERE self.company.id = :company"
                    + " AND self.statusSelect = :status ORDER BY self.id DESC",
                AiOcrUpload.class)
            .setParameter("company", companyId)
            .setParameter("status", AiOcrUploadStatus.NEEDS_REVIEW)
            .setMaxResults(MAX_PENDING_UPLOADS)
            .getResultList();
    UploadSupplierIdentityRelationship docs = Beans.get(UploadSupplierIdentityRelationship.class);
    Map<String, String> out = new LinkedHashMap<>();
    for (AiOcrUpload upload : uploads) {
      OcrExtractResult result;
      try {
        result = docs.extraction(upload);
      } catch (RuntimeException notStored) {
        continue;
      }
      String eik = UploadSupplierIdentityRelationship.documentSupplierEik(result);
      if (eik == null || out.containsKey(eik) || !holders(eik, companyId).isEmpty()) {
        continue;
      }
      out.put(eik, result.getSupplierName());
    }
    return out;
  }

  /** The one Purchases → Suppliers record whose name the message contains, or null. */
  protected Long uniqueByName(String message, Long companyId) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT self.id, self.name FROM Partner self"
                    + " WHERE self.isSupplier = true"
                    + " AND (self.isContact IS NULL OR self.isContact = false)"
                    + " AND (self.archived IS NULL OR self.archived = false)"
                    + " AND "
                    + AiCompanyScope.partnerFilter("self")
                    + " AND NOT EXISTS (SELECT 1 FROM Company c WHERE c.partner = self)",
                Object[].class)
            .setParameter(AiCompanyScope.COMPANY_PARAM, companyId)
            .setMaxResults(10000)
            .getResultList();
    return pickByName(message, rows);
  }

  /**
   * The one record whose normalised name appears in the message as whole words.
   *
   * <p>A name that is only part of a longer matched name — «Алфа» inside «Алфа Трейд» — yields to
   * the longer one: the message names the longer supplier exactly. Two different records left over
   * (or two with the same name) is no answer.
   *
   * @param rows {@code [id, name]} pairs
   */
  static Long pickByName(String message, List<Object[]> rows) {
    String text = " " + normalise(message) + " ";
    Map<Long, String> matched = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String name = row[1] == null ? null : normalise(row[1].toString());
      if (name != null && name.length() >= MIN_NAME_LENGTH && text.contains(" " + name + " ")) {
        matched.put(((Number) row[0]).longValue(), name);
      }
    }
    Long found = null;
    for (Map.Entry<Long, String> e : matched.entrySet()) {
      boolean partOfLonger =
          matched.values().stream()
              .anyMatch(
                  other ->
                      other.length() > e.getValue().length()
                          && (" " + other + " ").contains(" " + e.getValue() + " "));
      if (partOfLonger) {
        continue;
      }
      if (found != null) {
        return null;
      }
      found = e.getKey();
    }
    return found;
  }

  /** Partners, in the active company's scope, holding the ЕИК as registration number or VAT. */
  protected List<Partner> holders(String eik, Long companyId) {
    return SupplierEik.matchForCompany(Beans.get(PartnerRepository.class), eik, companyId).fetch(2);
  }

  /** The active company, or null outside one. A seam so a test need not run inside a real scope. */
  protected Long companyId() {
    return AiCompanyScope.companyIdOrNull();
  }

  /**
   * Whether the partner IS a company's own legal identity, never a supplier to point a write at.
   */
  protected boolean isCompanyIdentity(Partner partner) {
    return Beans.get(SupplierMaintenanceService.class).isCompanyIdentity(partner);
  }

  /** A Purchases → Suppliers record: a supplier, and not a contact person. */
  private static boolean isExternalSupplier(Partner partner) {
    return Boolean.TRUE.equals(partner.getIsSupplier())
        && !Boolean.TRUE.equals(partner.getIsContact());
  }

  /** Lower case, no quotes, no legal form, single spaces between letters and digits. */
  static String normalise(String value) {
    String s = value.toLowerCase(Locale.ROOT);
    s = LEGAL_FORM.matcher(s).replaceAll(" ");
    s = s.replaceAll("[^\\p{L}\\d]+", " ");
    return s.trim();
  }

  /** Whether the code is introduced as an ЕИК / Булстат / VAT number, not a phone or an amount. */
  private static boolean labelled(String message, int start) {
    String before = message.substring(Math.max(0, start - 20), start).toLowerCase(Locale.ROOT);
    return before.contains("еик")
        || before.contains("булстат")
        || before.contains("ддс")
        || before.contains("eik")
        || before.contains("uic")
        || before.contains("vat");
  }
}
