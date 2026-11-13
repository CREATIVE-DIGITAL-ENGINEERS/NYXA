package com.axelor.apps.napsubmissions.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.napsubmissions.common.config.EnvNapSecretProvider;
import com.axelor.apps.napsubmissions.common.config.NapSecretProvider;
import com.axelor.apps.napsubmissions.common.immutability.NapArtifactRequestGuard;
import com.axelor.apps.napsubmissions.common.immutability.NapSubmissionRequestGuard;
import com.axelor.apps.napsubmissions.common.immutability.SaftReportRequestGuard;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProvider;
import com.axelor.apps.napsubmissions.common.signing.CloudQesProviderSelector;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.EvrotrustTransport;
import com.axelor.apps.napsubmissions.common.signing.evrotrust.HttpEvrotrustTransport;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionClient;
import com.axelor.apps.napsubmissions.common.submission.UnsupportedNapApiSubmissionClient;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactManagementRepository;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileManagementRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionManagementRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiClient;
import com.axelor.apps.napsubmissions.services.declarations.common.NraDeclarationsApiClient;
import com.google.inject.Singleton;

/**
 * Guice bindings for the НАП submissions module.
 *
 * <ul>
 *   <li>The generic {@link NapSubmissionClient} stays "unsupported" — services without their own
 *       API (e.g. SAF-T) report "not configured" only when called; startup is unaffected.
 *   <li>{@link DeclarationsApiClient} binds to the real {@link NraDeclarationsApiClient}. It
 *       self-guards on configuration at call time (feature flag, endpoints, resolvable API key), so
 *       an unconfigured environment simply yields a structured CONFIG error rather than failing to
 *       start.
 *   <li>Secrets resolve through {@link NapSecretProvider} from a non-DB source (env/JVM property);
 *       swap the binding for a key-vault provider later.
 * </ul>
 */
public class NapSubmissionsModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(NapSubmissionClient.class).to(UnsupportedNapApiSubmissionClient.class);
    bind(DeclarationsApiClient.class).to(NraDeclarationsApiClient.class);
    bind(NapSecretProvider.class).to(EnvNapSecretProvider.class);
    // Cloud-QES signing: the active provider is chosen by configuration at resolution time. With no
    // configuration it resolves to the fail-closed UnconfiguredCloudQesProvider, so the mock is
    // never
    // active by default (and never in production unless 'mock' is explicitly selected).
    bind(CloudQesProvider.class).toProvider(CloudQesProviderSelector.class).in(Singleton.class);
    // Evrotrust vendor API transport (HMAC/HTTP). The provider itself is chosen by configuration in
    // CloudQesProviderSelector; this only supplies the network seam it talks through.
    bind(EvrotrustTransport.class).to(HttpEvrotrustTransport.class);
    // ⚠️ Сървърен „локален КЕП адаптер" НЯМА и не може да има: USB токенът е на машината на
    // потребителя. Подписването с USB КЕП минава през браузъра (NapBrowserSigningRestController +
    // BISS), затова предишният LocalQesComponent/UnconfiguredLocalQesComponent — който винаги
    // отказваше — е премахнат заедно с бутона, който водеше към него.

    // Server-side immutability guards (the enforcement point behind the readonly UI): a finalized
    // artifact's bytes/hash, a signed submission's identity, and a signed file's payload/signature/
    // certificate snapshot cannot be mutated once persisted.
    bind(NapArtifactRepository.class).to(NapArtifactManagementRepository.class);
    // NapArtifact.company не се пише през /ws/rest (решение от 17.10.2026) — виж класа.
    bind(NapArtifactRequestGuard.class);
    bind(NapSubmissionRepository.class).to(NapSubmissionManagementRepository.class);
    bind(NapSubmissionFileRepository.class).to(NapSubmissionFileManagementRepository.class);
    // Подаденото подаване: масовото обновяване и триенето през /ws/rest се отказват още в заявката
    // (решение от 18.10.2026) — виж класа; промяната по всеки друг път я спира Hibernate
    // слушателят.
    bind(NapSubmissionRequestGuard.class);
    bind(SaftReportRequestGuard.class);
  }
}
