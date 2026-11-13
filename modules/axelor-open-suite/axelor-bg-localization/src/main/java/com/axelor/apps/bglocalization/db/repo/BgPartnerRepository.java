package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.account.service.accountingsituation.AccountingSituationCheckService;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationInitService;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.hr.db.repo.PartnerHRRepository;
import com.axelor.studio.app.service.AppService;
import com.google.inject.Inject;

/**
 * Опашката на веригата за партньора (Base → Account → HR → тук).
 *
 * <p>Само едно нещо: <b>копието не взема дружеството на оригинала</b>. Новият партньор е на
 * активното дружество на този, който го създава (BgPartnerCompany); копие, което носи чуждото
 * дружество, би се отказало при записа (BgPartnerRequestGuard) или би сложило партньора в
 * дружество, в което потребителят не работи. Празното го попълва BgPartnerCompanyListener. Номерът
 * вече се нулира от базата ({@code PartnerBaseRepository.copy}).
 */
public class BgPartnerRepository extends PartnerHRRepository {

  @Inject
  public BgPartnerRepository(
      AppService appService,
      AccountingSituationInitService accountingSituationInitService,
      AccountingSituationCheckService accountingSituationCheckService) {
    super(appService, accountingSituationInitService, accountingSituationCheckService);
  }

  @Override
  public Partner copy(Partner partner, boolean deep) {
    Partner copy = super.copy(partner, deep);
    copy.setCompany(null);
    // Системният партньор се ражда само с нов потребител — копието е обикновен партньор.
    copy.setNxSystem(false);
    return copy;
  }
}
