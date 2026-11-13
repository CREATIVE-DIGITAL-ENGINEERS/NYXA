package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.PinType;
import org.junit.jupiter.api.Test;

/**
 * Видът на идентификатора на ЗЛ по договора на НАП се чете от правната форма на партньора, не се
 * гадае по ЕИК-а. Полето е readonly на екрана, тоест ако закачането не го попълни, транспортът
 * отказва подаването с „taxpayerPinType is required“.
 */
class BgD1SubmissionTaxpayerPinTypeTest {

  private static Partner partner(Integer typeSelect) {
    Partner partner = new Partner();
    partner.setPartnerTypeSelect(typeSelect);
    return partner;
  }

  @Test
  void legalEntityEmployerFilesAsBusBulstat() {
    assertEquals(
        PinType.BUS_BULSTAT.name(),
        BgD1SubmissionService.taxpayerPinType(partner(PartnerRepository.PARTNER_TYPE_COMPANY)));
  }

  @Test
  void individualEmployerFilesAsIndBulstat() {
    assertEquals(
        PinType.IND_BULSTAT.name(),
        BgD1SubmissionService.taxpayerPinType(partner(PartnerRepository.PARTNER_TYPE_INDIVIDUAL)));
  }

  @Test
  void anUnsetPartnerTypeFallsBackToTheLegalEntityValue() {
    assertEquals(PinType.BUS_BULSTAT.name(), BgD1SubmissionService.taxpayerPinType(partner(null)));
  }
}
