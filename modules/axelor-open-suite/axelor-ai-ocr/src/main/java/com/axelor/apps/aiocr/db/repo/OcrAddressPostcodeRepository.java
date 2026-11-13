package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.repo.AddressBaseRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;

/**
 * Server-side counterpart of {@link com.axelor.apps.aiocr.web.BgAddressPostcodeController}: the UI
 * validation alone would not stop an invalid postcode coming in through the REST API, a bulk import
 * or any other path that calls {@code AddressRepository.save} directly. Extends the tail of the
 * Address repository chain (nothing else binds {@link AddressBaseRepository}), so this runs for
 * every Address save regardless of caller.
 *
 * <p>Only enforced when the City+zip pair on this save is actually NEW or CHANGED from what is
 * already persisted. An existing Address whose zip predates this dataset (or predates the city
 * being corrected) can still be saved for an unrelated edit (e.g. a street name fix) without being
 * forced to also fix a historical postcode mismatch it never triggered a validation for before.
 */
public class OcrAddressPostcodeRepository extends AddressBaseRepository {

  @Override
  public Address save(Address entity) {
    try {
      validatePostcodeIfChanged(entity);
    } catch (AxelorException e) {
      TraceBackService.traceExceptionFromSaveMethod(e);
      throw new PersistenceException(e.getMessage(), e);
    }
    return super.save(entity);
  }

  protected void validatePostcodeIfChanged(Address entity) throws AxelorException {
    City city = entity.getCity();
    String zip = entity.getZip();
    if (city == null || zip == null || zip.trim().isEmpty()) {
      return;
    }
    zip = zip.trim();

    if (entity.getId() != null && !isChanged(entity.getId(), city, zip)) {
      return;
    }

    if (!Beans.get(BgCityPostcodeService.class).isZipValid(city, zip)) {
      throw new AxelorException(
          entity,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          String.format(I18n.get("Invalid postcode for %s."), city.getName()));
    }
  }

  /**
   * Reads the currently-persisted city/zip in a fresh EntityManager, same technique as {@link
   * AddressBaseRepository#save} uses to read the pre-save state.
   */
  private boolean isChanged(Long id, City city, String zip) {
    EntityManager em = JPA.em().getEntityManagerFactory().createEntityManager();
    try {
      Address existing = em.find(Address.class, id);
      return existing == null
          || existing.getCity() == null
          || !city.getId().equals(existing.getCity().getId())
          || !zip.equals(existing.getZip());
    } finally {
      em.close();
    }
  }
}
