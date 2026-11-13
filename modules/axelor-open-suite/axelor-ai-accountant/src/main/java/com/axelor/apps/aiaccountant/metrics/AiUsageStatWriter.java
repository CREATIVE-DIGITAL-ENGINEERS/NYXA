package com.axelor.apps.aiaccountant.metrics;

import com.axelor.apps.aiaccountant.db.AiUsageStat;
import com.axelor.apps.aiaccountant.db.repo.AiUsageStatRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Writes one measured request into {@link AiUsageStat}, in its own transaction.
 *
 * <h2>Why this is a separate, transactional method</h2>
 *
 * {@code AiRequestMetrics} fires in the middle of whatever the caller was doing — a chat turn, an
 * OCR intake pass, a post-draft repair — and that caller usually has a business transaction open.
 * Saving from inside it would make a metric able to mark the caller's work rollback-only, which is
 * the one thing a measurement must never do. So the recorder calls this on a thread of its own and
 * the write gets a transaction of its own; the same shape {@code BgOcrIntakeService.markFailed}
 * uses for the same reason.
 *
 * <p>The labels are resolved here rather than by the producer so that measuring costs no query on
 * the thread the user is waiting on. They are stored, rather than joined to later, because the
 * point of holding an id instead of a foreign key is that the user or company may legitimately be
 * deleted — and a chart should still be able to name them.
 */
@Singleton
public class AiUsageStatWriter {

  /** Both label columns are 255; a defensive bound, since a display name is user-supplied text. */
  protected static final int LABEL_MAX = 255;

  @Transactional
  public void write(AiUsageSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    AiUsageStat stat = new AiUsageStat();
    stat.setRequestDateTime(snapshot.getRequestDateTime());
    stat.setUserId(snapshot.getUserId());
    stat.setCompanyId(snapshot.getCompanyId());
    stat.setUserLabel(userLabel(snapshot.getUserId()));
    stat.setCompanyLabel(companyLabel(snapshot.getCompanyId()));
    stat.setCaller(snapshot.getCaller());
    stat.setAgentRole(snapshot.getAgentRole());
    stat.setOutcome(snapshot.getOutcome());
    stat.setChained(snapshot.isChained());
    stat.setModel(snapshot.getModel());
    stat.setAgentVersion(snapshot.getAgentVersion());
    stat.setDurationMs(snapshot.getDurationMs());
    stat.setInputTokens(snapshot.getInputTokens());
    stat.setCachedInputTokens(snapshot.getCachedInputTokens());
    stat.setOutputTokens(snapshot.getOutputTokens());
    stat.setReasoningTokens(snapshot.getReasoningTokens());
    stat.setTotalTokens(snapshot.getTotalTokens());
    repo().save(stat);
  }

  /** Null rather than a placeholder: a system-scoped request genuinely has no user. */
  protected String userLabel(Long userId) {
    if (userId == null || userId <= 0) {
      return null;
    }
    try {
      User user = Beans.get(UserRepository.class).find(userId);
      if (user == null) {
        return null;
      }
      return trim(user.getName() != null ? user.getName() : user.getCode());
    } catch (RuntimeException e) {
      // A label is a convenience. Failing to read one must not cost the measurement.
      return null;
    }
  }

  protected String companyLabel(Long companyId) {
    if (companyId == null || companyId <= 0) {
      return null;
    }
    try {
      Company company = Beans.get(CompanyRepository.class).find(companyId);
      if (company == null) {
        return null;
      }
      return trim(company.getName() != null ? company.getName() : company.getCode());
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected AiUsageStatRepository repo() {
    return Beans.get(AiUsageStatRepository.class);
  }

  protected static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.length() <= LABEL_MAX ? t : t.substring(0, LABEL_MAX);
  }
}
