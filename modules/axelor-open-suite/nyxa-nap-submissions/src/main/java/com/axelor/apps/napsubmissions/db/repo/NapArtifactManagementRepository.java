package com.axelor.apps.napsubmissions.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityGuard;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaFile;
import java.util.List;
import javax.persistence.FlushModeType;

/**
 * Server-side immutability guard for {@link NapArtifact}: once a row is finalized, its bytes, hash,
 * length, finalized flag and stored file can never change; its company never changes at all (REST,
 * UI, services — admin included). This is the enforcement point behind the readonly UI fields — a
 * readonly form cannot stop a service or an import from mutating the row.
 *
 * <p>The committed values are read with {@link FlushModeType#COMMIT} so the query does NOT flush
 * the dirty entity first; it returns what the database holds, which is then compared against the
 * incoming entity before {@code super.save} writes it.
 */
public class NapArtifactManagementRepository extends NapArtifactRepository {

  @Override
  public NapArtifact save(NapArtifact entity) {
    if (entity.getId() != null) {
      RegulatoryImmutabilityGuard.checkArtifact(loadCommitted(entity.getId()), entity);
    }
    return super.save(entity);
  }

  protected NapArtifact loadCommitted(Long id) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT a.sha256, a.byteLength, a.finalized, mf.id, co.id"
                    + " FROM NapArtifact a LEFT JOIN a.metaFile mf LEFT JOIN a.company co"
                    + " WHERE a.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = rows.get(0);
    NapArtifact original = new NapArtifact();
    original.setSha256((String) row[0]);
    original.setByteLength((Long) row[1]);
    original.setFinalized((Boolean) row[2]);
    if (row[3] != null) {
      MetaFile metaFile = new MetaFile();
      metaFile.setId((Long) row[3]);
      original.setMetaFile(metaFile);
    }
    if (row[4] != null) {
      Company company = new Company();
      company.setId((Long) row[4]);
      original.setCompany(company);
    }
    return original;
  }
}
