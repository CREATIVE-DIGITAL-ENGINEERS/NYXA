package com.axelor.apps.aiaccounting.posting;

import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_ACCOUNTED;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_DAYBOOK;
import static com.axelor.apps.account.db.repo.MoveRepository.STATUS_NEW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import java.util.ServiceLoader;
import org.hibernate.integrator.spi.Integrator;
import org.junit.jupiter.api.Test;

/** AI cannot post definitively: no entry becomes Accounted while an AI write tool runs. */
class AiDraftOnlyPostingGuardTest {

  @Test
  void aiCannotMakeAnEntryAccounted() {
    for (Integer from : new Integer[] {null, STATUS_NEW, STATUS_DAYBOOK}) {
      assertThrows(
          AiDraftOnlyRefusal.class,
          () ->
              AiExecutionContext.callAsToolWrite(
                  () -> {
                    AiDraftOnlyPostingGuard.check(from, STATUS_ACCOUNTED);
                    return null;
                  }),
          "from " + from);
    }
  }

  @Test
  void aiMayStillWriteDraftsAndDaybook() {
    AiExecutionContext.callAsToolWrite(
        () -> {
          AiDraftOnlyPostingGuard.check(null, STATUS_NEW);
          AiDraftOnlyPostingGuard.check(STATUS_NEW, STATUS_DAYBOOK);
          AiDraftOnlyPostingGuard.check(STATUS_ACCOUNTED, STATUS_ACCOUNTED);
          return null;
        });
  }

  @Test
  void theUserPostsFromTheFormUnchecked() {
    assertDoesNotThrow(() -> AiDraftOnlyPostingGuard.check(STATUS_NEW, STATUS_ACCOUNTED));
  }

  /** A missing or misspelled META-INF/services entry would switch the guard off silently. */
  @Test
  void integratorIsDiscoverableByServiceLoader() {
    boolean found = false;
    for (Integrator integrator : ServiceLoader.load(Integrator.class)) {
      found |= integrator instanceof AiAccountingIntegrator;
    }
    assertTrue(found, "AiAccountingIntegrator is not registered in META-INF/services");
  }
}
