package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that even if a bad coordinate somehow reached {@code BgCityCentroidService.persist()} --
 * bypassing {@link BgCoordinateNormalizer} entirely, simulating a regression in that guard -- the
 * underlying {@code @Transactional} flush fails atomically and leaves NO partial state, not a City
 * row with only one of latit/longit set.
 *
 * <p>This test's own persistence unit runs with {@code javax.persistence.validation.mode=none} (see
 * {@code src/test/resources/META-INF/persistence.xml} -- shared by every test in this module, not
 * changed here), AND the generated {@code City}/{@code Address} entities carry no
 * {@code @Column(precision=,scale=)} at all (confirmed directly in the generated sources under
 * {@code build/src-gen}), so HSQLDB stores {@code latit}/{@code longit} as its generic {@code
 * NUMERIC(19,2)} default (confirmed via {@code DatabaseMetaData.getColumns} against the live
 * schema) regardless of the {@code precision="9" scale="6"} declared in {@code City.xml} -- see
 * {@link BgGeocodeTestFixtures#assertCloseTo} for the same finding. So the exact Bean-Validation
 * "numeric value out of bounds" error seen in production cannot be reproduced here by
 * fractional-digit count alone (HSQLDB just rounds those, matching {@code NUMERIC(19,2)}). An
 * integer part wider than {@code NUMERIC(19,2)} can actually hold (17 integer digits) overflows the
 * column itself, which HSQLDB refuses at the JDBC level regardless of Bean Validation being on or
 * off -- a strictly harder failure to reach than the one this guard is built to prevent, used here
 * only to prove the underlying transaction is atomic.
 *
 * <p>Real Guice-managed {@link BgCityCentroidService} (see {@link BgGeocodePrecisionTestModule}),
 * not a hand-built one: a plain {@code new BgCityCentroidService(...)} bypasses Guice AOP entirely,
 * which would make this rollback proof meaningless (no {@code @Transactional} interceptor would
 * ever run). Its own class (not sharing a class with the other precision tests): see {@link
 * BgAddressHighPrecisionPersistenceTest}'s javadoc for why.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgGeocodePrecisionTestModule.class)
@Tag("integration")
class BgCityCentroidPersistenceRollbackTest extends JpaSupport {

  @Test
  void aPersistenceFailureInCityCentroidPersist_rollsBackWithoutPartialState() {
    Long[] cityId = new Long[1];
    JPA.runInTransaction(
        () -> {
          Country bulgaria = BgGeocodeTestFixtures.setUpBulgaria();
          City sofia = new City();
          sofia.setName("София");
          sofia.setCountry(bulgaria);
          Beans.get(CityRepository.class).save(sofia);
          cityId[0] = sofia.getId();
        });

    City managedCity = Beans.get(CityRepository.class).find(cityId[0]);
    BgCityCentroidService service = Beans.get(BgCityCentroidService.class);
    // 20 integer digits: overflows NUMERIC(19,2) (17 integer digits max) at the JDBC level,
    // independent of whether Bean Validation is configured.
    BigDecimal overflowsTheColumn = new BigDecimal("12345678901234567890.12");

    assertThrows(
        RuntimeException.class,
        () -> service.persist(managedCity, overflowsTheColumn, overflowsTheColumn));

    JPA.clear();

    City reloaded = Beans.get(CityRepository.class).find(cityId[0]);
    assertNull(reloaded.getLatit(), "a failed flush must not leave a partially-set coordinate");
    assertNull(reloaded.getLongit(), "a failed flush must not leave a partially-set coordinate");
  }
}
