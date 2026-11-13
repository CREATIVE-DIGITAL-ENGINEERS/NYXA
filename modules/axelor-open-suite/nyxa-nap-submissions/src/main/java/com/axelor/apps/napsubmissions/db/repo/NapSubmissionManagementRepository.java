package com.axelor.apps.napsubmissions.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityGuard;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.db.JPA;
import java.util.List;
import javax.persistence.FlushModeType;

/**
 * Server-side immutability guard for {@link NapSubmission}: once a submission is locked (SIGNED or
 * any later transport state), its taxpayer / period / document-type identity and its recorded QES
 * signer profile can no longer change. A correction must create a NEW submission rather than mutate
 * the signed one.
 */
public class NapSubmissionManagementRepository extends NapSubmissionRepository {

  @Override
  public NapSubmission save(NapSubmission entity) {
    if (entity.getId() != null) {
      NapSubmission committed = loadCommitted(entity.getId());
      RegulatoryImmutabilityGuard.checkSubmission(committed, entity);
      RegulatoryImmutabilityGuard.checkFilingIdentity(committed, entity);
    }
    return super.save(entity);
  }

  protected NapSubmission loadCommitted(Long id) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.submissionStatusSelect, s.serviceCodeSelect, s.serviceDocumentTypeSelect,"
                    + " s.taxpayerPin, s.taxpayerPinTypeSelect, comp.id, per.id, prof.id,"
                    + " s.relatedModel, s.relatedRecordId"
                    + " FROM NapSubmission s LEFT JOIN s.company comp LEFT JOIN s.period per"
                    + " LEFT JOIN s.qesSignerProfile prof"
                    + " WHERE s.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = rows.get(0);
    NapSubmission original = new NapSubmission();
    original.setSubmissionStatusSelect((String) row[0]);
    original.setServiceCodeSelect((String) row[1]);
    original.setServiceDocumentTypeSelect((String) row[2]);
    original.setTaxpayerPin((String) row[3]);
    original.setTaxpayerPinTypeSelect((String) row[4]);
    if (row[5] != null) {
      Company company = new Company();
      company.setId((Long) row[5]);
      original.setCompany(company);
    }
    if (row[6] != null) {
      Period period = new Period();
      period.setId((Long) row[6]);
      original.setPeriod(period);
    }
    if (row[7] != null) {
      UserQesProfile profile = new UserQesProfile();
      profile.setId((Long) row[7]);
      original.setQesSignerProfile(profile);
    }
    original.setRelatedModel((String) row[8]);
    original.setRelatedRecordId((Long) row[9]);
    return original;
  }
}
