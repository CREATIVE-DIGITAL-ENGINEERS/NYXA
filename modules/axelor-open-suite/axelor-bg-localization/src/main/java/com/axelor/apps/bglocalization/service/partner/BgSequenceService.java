package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.db.repo.SequenceVersionRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.administration.SequenceVersionGeneratorService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.db.Model;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * НОМЕРЪТ НА ПАРТНЬОРА — от поредицата на НЕГОВОТО дружество, без обща и без резервна.
 *
 * <p>Всички пътища на AOS искат номера без дружество: {@code getSequenceNumber("partner",
 * Partner.class, "partnerSeq", partner)} — {@code PartnerServiceImpl.onSave}, {@code
 * PartnerController.setPartnerSequence}, {@code SyncContactService}, {@code ImportPartner}, {@code
 * LeadServiceImpl}. Базата тогава взема {@code findByCodeSelect("partner")} — ПЪРВАТА поредица с
 * този код, на което и дружество да е. Тук кодът {@code partner} отива към поредицата на
 * дружеството на партньора, без да се пипа нито един от тези пътища.
 *
 * <p><b>Дружеството</b>: вече сложеното на записа, иначе — за нов партньор — изричното на контекста
 * или активното на потребителя ({@link BgPartnerCompany#resolve}); то се записва и на партньора, за
 * да са номерът и дружеството едно решение. Заварен партньор без дружество не получава номер
 * (разпределя ги миграцията). Липсва ли поредица — ясен отказ, без резерва.
 *
 * <p><b>Проверката за вече даден номер</b> ({@code checkExistingSequenceOnGeneration}) търси в
 * дружеството по {@code company}, а не по {@code companySet}: базата взема първото поле към
 * Company, а при два вида поле изборът е случаен.
 *
 * <p>Всеки друг код минава през базата непроменен (вкл. ТРЗ — те са по дружество в {@code
 * BgTrzSequenceService}).
 */
@Singleton
public class BgSequenceService extends SequenceService {

  @Inject
  public BgSequenceService(
      SequenceVersionRepository sequenceVersionRepository,
      AppBaseService appBaseService,
      SequenceRepository sequenceRepo,
      SequenceVersionGeneratorService sequenceVersionGeneratorService) {
    super(sequenceVersionRepository, appBaseService, sequenceRepo, sequenceVersionGeneratorService);
  }

  @Override
  @SuppressWarnings("rawtypes")
  public String getSequenceNumber(
      String code, Company company, Class objectClass, String fieldName, Model model)
      throws AxelorException {
    if (!isPartnerNumber(code, model)) {
      return super.getSequenceNumber(code, company, objectClass, fieldName, model);
    }
    Partner partner = (Partner) model;
    Company owner = partnerCompany(partner, company);
    Sequence sequence = sequenceRepo.find(SequenceRepository.PARTNER, owner);
    if (sequence == null) {
      throw new AxelorException(
          partner,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          BgPartnerCompany.noSequence(owner));
    }
    return getSequenceNumber(
        sequence, appBaseService.getTodayDate(owner), objectClass, fieldName, model);
  }

  /** Само кодът {@code partner} за партньор; всичко друго е на базата. */
  static boolean isPartnerNumber(String code, Object model) {
    return SequenceRepository.PARTNER.equals(code) && model instanceof Partner;
  }

  /**
   * Дружеството, чиято поредица дава номера. Подаденото от извикващия е допустимо само ако е същото
   * като на партньора — номер от чужда поредица би сложил партньора в едно дружество с номер от
   * друго.
   */
  protected Company partnerCompany(Partner partner, Company requested) throws AxelorException {
    Company owner = partner.getCompany();
    if (owner == null) {
      if (partner.getId() != null) {
        throw new AxelorException(
            partner,
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            BgPartnerCompany.legacyWithoutCompany(partner.getId()));
      }
      owner = requested != null ? requested : BgPartnerCompany.resolve(partner);
      if (owner == null) {
        throw new AxelorException(
            partner, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, BgPartnerCompany.NO_COMPANY);
      }
      partner.setCompany(owner);
    } else if (requested != null && !BgPartnerCompany.same(owner, requested)) {
      throw new AxelorException(
          partner,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          BgPartnerCompany.foreignSequence(owner, requested));
    }
    return owner;
  }

  @Override
  @SuppressWarnings("rawtypes")
  protected String computeCompanyQuery(Class objectClass) {
    if (objectClass != null && Partner.class.isAssignableFrom(objectClass)) {
      return " AND self.company = :company";
    }
    return super.computeCompanyQuery(objectClass);
  }
}
