package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.auth.db.User;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Записва системния партньор на нов потребител ({@link BgSystemPartner}) през веригата на
 * репозиторито (пълното име, адресите), вътре в пътя „нов потребител“ — единственото място, където
 * {@link BgPartnerCompanyListener} пуска партньор без дружество.
 */
@Singleton
public class BgSystemPartnerService {

  protected final PartnerRepository partnerRepo;

  @Inject
  public BgSystemPartnerService(PartnerRepository partnerRepo) {
    this.partnerRepo = partnerRepo;
  }

  @Transactional(rollbackOn = Exception.class)
  public Partner createFor(User user) {
    return BgSystemPartner.creating(() -> partnerRepo.save(BgSystemPartner.newFor(user)));
  }
}
