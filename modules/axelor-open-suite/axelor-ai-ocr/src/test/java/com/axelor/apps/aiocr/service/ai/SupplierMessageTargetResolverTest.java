package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.base.db.Partner;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierMessageTargetResolverTest {

  private static final Long COMPANY_ID = 5L;

  /**
   * A MAINTAIN_SUPPLIER route carrying the structured operation the router read out of the
   * sentence. The verbs in the messages below are now ordinary words that nothing matches against —
   * the tests state the intent the same way the live router does.
   */
  private static AiRoute route(AiWriteOperation operation) {
    return new AiRoute(
        AiDomain.PARTNER,
        AiTask.MAINTAIN_SUPPLIER,
        null,
        AiScope.UNSCOPED,
        AiExplicitness.EXPLICIT,
        null,
        null,
        null,
        operation);
  }

  /**
   * A resolver running with no Guice injector and no database: {@code companyId}, {@code holders}
   * and {@code isCompanyIdentity} are the three seams {@code resolve} reads instead of {@code
   * AiCompanyScope}/{@code SupplierMaintenanceService}/{@code PartnerRepository}, and {@code
   * uniqueByName} is overridden to never touch JPA — these tests are about the ЕИК path, not the
   * name fallback, which {@link #aNameContainedInALongerMatchedNameYieldsToTheLongerOne} and its
   * neighbours already cover directly.
   */
  private static SupplierMessageTargetResolver resolverWith(List<Partner> holders) {
    return new SupplierMessageTargetResolver() {
      @Override
      protected Long companyId() {
        return COMPANY_ID;
      }

      @Override
      protected List<Partner> holders(String eik, Long companyId) {
        assertEquals(COMPANY_ID, companyId);
        return holders;
      }

      @Override
      protected boolean isCompanyIdentity(Partner partner) {
        return false;
      }

      @Override
      protected Long uniqueByName(String message, Long companyId) {
        return null;
      }
    };
  }

  private static Partner externalSupplier(long id) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setIsSupplier(true);
    return partner;
  }

  @Test
  void aCreateRequestPhrasedAsНаправиResolvesToTheSyntheticCreateTarget() {
    SupplierMessageTargetResolver resolver = resolverWith(Collections.emptyList());

    List<String> refs =
        resolver.resolve(
            route(AiWriteOperation.CREATE),
            "Направи доставчик с ЕИК 831642181 телефон +359 888 123 456 и име TestD");

    assertEquals(Collections.singletonList("PartnerCreate:5:831642181"), refs);
  }

  /**
   * A complete, explicit create naming name, ЕИК, phone AND the active company by name must still
   * resolve to exactly one target — the mention of the active company's own name must never be read
   * as naming a record (it names no code, so {@code anyCode} is never set by it), and must never
   * leak into the name-fallback path either, because the ЕИК match already sets {@code anyCode =
   * true} first and that path is never reached at all once it has. The ЕИК here belongs only to a
   * DIFFERENT company's own supplier in the real data this reproduces; {@code holders()} is
   * company-scoped and correctly answers empty for it.
   */
  @Test
  void aCompleteExplicitRequestNamingTheActiveCompanyItselfStillResolvesToOneCreateTarget() {
    SupplierMessageTargetResolver resolver = resolverWith(Collections.emptyList());

    List<String> refs =
        resolver.resolve(
            route(AiWriteOperation.CREATE),
            "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA. Трябва"
                + " ми да е свързан с Тест Фиш България ООД - активната фирма.");

    assertEquals(Collections.singletonList("PartnerCreate:5:130159785"), refs);
  }

  /**
   * A create request naming an ЕИК that already belongs to somebody — supplier or not — must still
   * authorise the CREATE attempt itself, at the exact synthetic target {@code manage_supplier}'s
   * own {@code targetKey} produces for it. Freezing the EXISTING partner's own ref here instead
   * (the previous behaviour) left that ref authorised but untouched whenever the create was
   * correctly refused as a duplicate: nothing in the turn would ever complete or block it, so the
   * job never settled and a later, unrelated explicit instruction inherited its stale target
   * instead of its own. Resolving the create target lets the attempt reach {@code
   * SupplierMaintenanceService}'s own duplicate check, whose refusal is both accurate and terminal.
   */
  @Test
  void aCreateRequestForAnEikAnExistingExternalSupplierAlreadyHoldsAuthorisesTheCreateAttempt() {
    SupplierMessageTargetResolver resolver =
        resolverWith(Collections.singletonList(externalSupplier(77)));

    List<String> refs =
        resolver.resolve(
            route(AiWriteOperation.CREATE), "Направи доставчик с ЕИК 831642181, адрес София");

    assertEquals(Collections.singletonList("PartnerCreate:5:831642181"), refs);
  }

  /**
   * The same, for an ЕИК that already belongs to a Partner nobody ever flagged as a supplier —
   * {@code find_supplier} would not return it, so the only way the duplicate is ever caught is by
   * authorising the create attempt and letting the service's own registration-code check answer.
   */
  @Test
  void aCreateRequestForAnEikAnExistingNonSupplierPartnerAlreadyHoldsAuthorisesTheCreateAttempt() {
    Partner nonSupplier = new Partner();
    nonSupplier.setId(83L);
    SupplierMessageTargetResolver resolver = resolverWith(Collections.singletonList(nonSupplier));

    List<String> refs =
        resolver.resolve(route(AiWriteOperation.CREATE), "Направи доставчик с ЕИК 831642181");

    assertEquals(Collections.singletonList("PartnerCreate:5:831642181"), refs);
  }

  /**
   * Two holders make an UPDATE ambiguous — which one? — so it names nothing. A CREATE is aimed at
   * neither: it still authorises only the create attempt, so the service's duplicate check reports
   * the real blocker instead of the turn being refused as having no target at all.
   */
  @Test
  void twoHoldersOfTheSameEikAuthoriseOnlyTheCreateAttemptAndNeverAnUpdate() {
    SupplierMessageTargetResolver resolver =
        resolverWith(Arrays.asList(externalSupplier(1), externalSupplier(2)));

    assertEquals(
        Collections.singletonList("PartnerCreate:5:831642181"),
        resolver.resolve(route(AiWriteOperation.CREATE), "Направи доставчик с ЕИК 831642181"));
    assertTrue(
        resolver
            .resolve(route(AiWriteOperation.UPDATE), "смени IBAN-а на доставчик с ЕИК 831642181")
            .isEmpty());
  }

  @Test
  void aCreateInstructionWithNoEikAtAllAuthorisesNothing() {
    SupplierMessageTargetResolver resolver = resolverWith(Collections.emptyList());

    List<String> refs =
        resolver.resolve(route(AiWriteOperation.CREATE), "Направи доставчик на име TestD");

    assertTrue(refs.isEmpty());
  }

  @Test
  void anUnlabelledNineDigitNumberOnACreateRequestIsNotReadAsAnEik() {
    // «831642181» with nothing around it saying it is an ЕИК/БУЛСТАТ/ДДС could just as well be an
    // amount or a phone number; only a labelled code may seed a create target.
    SupplierMessageTargetResolver resolver = resolverWith(Collections.emptyList());

    List<String> refs =
        resolver.resolve(route(AiWriteOperation.CREATE), "Направи доставчик 831642181 име TestD");

    assertTrue(refs.isEmpty());
  }

  /**
   * The failure the verb list produced: «заведи» is ordinary Bulgarian for setting a supplier up
   * and was on nobody's list, so a complete, explicit creation silently became an update, resolved
   * no target and was refused. Nothing reads the verb now — the router states the operation.
   */
  @Test
  void aCreateInAnyWordingResolvesToTheCreateTargetWithNoVerbList() {
    SupplierMessageTargetResolver resolver = resolverWith(Collections.emptyList());

    assertEquals(
        Collections.singletonList("PartnerCreate:5:831642181"),
        resolver.resolve(
            route(AiWriteOperation.CREATE), "заведи контрагент с ЕИК 831642181, адрес София"));
    assertEquals(
        Collections.singletonList("PartnerCreate:5:831642181"),
        resolver.resolve(
            route(AiWriteOperation.CREATE),
            "трябва ми доставчик с ЕИК 831642181 за утрешната фактура"));
  }

  /** An update names the supplier that already holds the ЕИК, never a creation. */
  @Test
  void anUpdateResolvesToTheExistingSupplierEvenWhenTheWordsLookLikeACreate() {
    SupplierMessageTargetResolver resolver =
        resolverWith(Collections.singletonList(externalSupplier(77)));

    assertEquals(
        Collections.singletonList("Partner#77"),
        resolver.resolve(
            route(AiWriteOperation.UPDATE),
            "направи така, че доставчик с ЕИК 831642181 да е с IBAN BG80BNBG96611020345678"));
  }

  /**
   * Nothing stated the operation — the deterministic fallback route, used when the router is off or
   * did not answer. Read as UPDATE: an existing supplier is named, a creation is not authorised.
   * See the class javadoc for why that direction is the safe one.
   */
  @Test
  void anUnstatedOperationIsReadAsUpdateAndNeverMintsACreateTarget() {
    assertEquals(
        Collections.singletonList("Partner#77"),
        resolverWith(Collections.singletonList(externalSupplier(77)))
            .resolve(route(null), "Направи доставчик с ЕИК 831642181"));
    assertTrue(
        resolverWith(Collections.emptyList())
            .resolve(route(null), "Направи доставчик с ЕИК 831642181")
            .isEmpty());
  }

  /** The plain-domain overload keeps working for every existing caller, as unstated. */
  @Test
  void theDomainOnlyOverloadStillResolvesAnExistingSupplier() {
    assertEquals(
        Collections.singletonList("Partner#77"),
        resolverWith(Collections.singletonList(externalSupplier(77)))
            .resolve(AiDomain.PARTNER, "смени IBAN-а на доставчик с ЕИК 831642181"));
  }

  private static final List<Object[]> SUPPLIERS =
      Arrays.asList(
          new Object[] {1L, "Лидл България ЕООД"},
          new Object[] {2L, "Алфа ООД"},
          new Object[] {3L, "„Алфа Трейд“ ЕООД"},
          new Object[] {4L, "Бета ЕАД"},
          new Object[] {5L, "Бета АД"});

  @Test
  void normaliseIgnoresCaseQuotesPunctuationAndLegalForm() {
    assertEquals("алфа трейд", SupplierMessageTargetResolver.normalise("„АЛФА-Трейд“, ЕООД"));
    assertEquals("alpha", SupplierMessageTargetResolver.normalise("\"Alpha\" Ltd."));
  }

  @Test
  void aNameIgnoringLegalFormAndPunctuationPicksTheOneSupplier() {
    assertEquals(
        1L,
        SupplierMessageTargetResolver.pickByName(
            "смени телефона на доставчик „ЛИДЛ, България“ на 0888123456", SUPPLIERS));
  }

  @Test
  void aNameContainedInALongerMatchedNameYieldsToTheLongerOne() {
    assertEquals(
        3L,
        SupplierMessageTargetResolver.pickByName(
            "обнови адреса на доставчик Алфа Трейд ЕООД", SUPPLIERS));
    assertEquals(
        2L,
        SupplierMessageTargetResolver.pickByName("обнови адреса на доставчик Алфа ООД", SUPPLIERS));
  }

  @Test
  void twoSuppliersWithTheSameNormalisedNameAreNeverGuessed() {
    assertNull(
        SupplierMessageTargetResolver.pickByName("обнови адреса на доставчик Бета", SUPPLIERS));
  }

  /** A resolver whose needs-review uploads wait on the given missing suppliers (ЕИК → name). */
  private static SupplierMessageTargetResolver withMissing(java.util.Map<String, String> missing) {
    return new SupplierMessageTargetResolver() {
      @Override
      protected Long companyId() {
        return COMPANY_ID;
      }

      @Override
      protected List<Partner> holders(String eik, Long companyId) {
        return Collections.emptyList();
      }

      @Override
      protected boolean isCompanyIdentity(Partner partner) {
        return false;
      }

      @Override
      protected java.util.Map<String, String> missingSuppliers(Long companyId) {
        return missing;
      }
    };
  }

  @Test
  void aLoneNeedsReviewUploadIsNeverTheTargetOfAVagueCreate() {
    java.util.Map<String, String> missing = new java.util.LinkedHashMap<>();
    missing.put("130159785", "SAP BULGARIA ЕООД");
    assertTrue(
        withMissing(missing)
            .resolveFromContext(route(AiWriteOperation.CREATE), "създай липсващия доставчик", null)
            .isEmpty());
    // Nor is it for an update, or when the router did not say CREATE at all.
    assertTrue(
        withMissing(missing)
            .resolveFromContext(route(AiWriteOperation.UPDATE), "оправи доставчика", null)
            .isEmpty());
    assertTrue(
        withMissing(missing)
            .resolveFromContext(route(null), "създай липсващия доставчик", null)
            .isEmpty());
  }

  @Test
  void pendingSuppliersAreMatchedOnlyByAnUnambiguousNameInTheMessage() {
    java.util.Map<String, String> missing = new java.util.LinkedHashMap<>();
    missing.put("130159785", "SAP BULGARIA ЕООД");
    missing.put("831642181", "Алфа Трейд ООД");
    SupplierMessageTargetResolver resolver = withMissing(missing);
    assertEquals(
        Collections.singletonList("PartnerCreate:5:831642181"),
        resolver.resolveFromContext(
            route(AiWriteOperation.CREATE), "създай доставчика Алфа Трейд", null));
    assertTrue(
        resolver
            .resolveFromContext(route(AiWriteOperation.CREATE), "създай липсващия доставчик", null)
            .isEmpty());
  }

  @Test
  void aReplyThatIsOnlyTheEikAuthorisesItsCreate() {
    assertEquals(
        Collections.singletonList("PartnerCreate:5:130159785"),
        resolverWith(Collections.emptyList())
            .resolve(route(AiWriteOperation.CREATE), " 130159785 "));
  }

  @Test
  void aPartialNameOrNoNameSelectsNothing() {
    assertNull(
        SupplierMessageTargetResolver.pickByName("обнови адреса на доставчик Лидл", SUPPLIERS));
    assertNull(
        SupplierMessageTargetResolver.pickByName("обнови адреса на доставчик Гама", SUPPLIERS));
  }
}
