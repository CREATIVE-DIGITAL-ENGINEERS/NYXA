package com.axelor.apps.base.syncContact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.FunctionRepository;
import com.axelor.apps.base.service.user.UserService;
import com.google.api.services.people.v1.model.Organization;
import com.google.api.services.people.v1.model.Person;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A Google contact is linked only to the user's active company, never to another one by name. */
class SyncContactCompanyTest {

  private CompanyRepository companyRepo;
  private UserService userService;
  private SyncContactService service;
  private Company active;
  private Company other;

  @BeforeEach
  void prepare() {
    companyRepo = mock(CompanyRepository.class);
    userService = mock(UserService.class);
    // Real methods without the constructor: a field initializer reads the application's date.
    service = mock(SyncContactService.class, CALLS_REAL_METHODS);
    inject("companyRepo", companyRepo);
    inject("userService", userService);
    inject("functionRepo", mock(FunctionRepository.class));
    // The unknown-company note goes through I18n, which needs the application.
    doNothing().when(service).updateDescription(any(), any(), anyString());

    active = company(1L, "Active company");
    other = company(2L, "Other company");
    when(userService.getUserActiveCompany()).thenReturn(active);
  }

  @Test
  void contactOfAnotherCompanyIsNotLinkedToIt() {
    when(companyRepo.findByName("Other company")).thenReturn(other);
    Partner contact = new Partner();

    service.importCompany(person("Other company"), contact, true);

    Assertions.assertTrue(
        contact.getCompanySet() == null || contact.getCompanySet().isEmpty(),
        "linked to a company the user is not working in");
    Assertions.assertNull(contact.getMainPartner());
    Assertions.assertTrue(
        other.getPartner().getContactPartnerSet() == null
            || other.getPartner().getContactPartnerSet().isEmpty(),
        "the other company's partner was changed");
  }

  @Test
  void contactOfTheActiveCompanyIsLinked() {
    when(companyRepo.findByName("Active company")).thenReturn(active);
    Partner contact = new Partner();

    service.importCompany(person("Active company"), contact, true);

    Assertions.assertTrue(contact.getCompanySet().contains(active));
    Assertions.assertEquals(active.getPartner(), contact.getMainPartner());
  }

  private void inject(String field, Object value) {
    try {
      Field f = SyncContactService.class.getDeclaredField(field);
      f.setAccessible(true);
      f.set(service, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Company company(Long id, String name) {
    Company company = new Company(name, name);
    company.setId(id);
    Partner partner = new Partner();
    partner.setId(id + 100);
    company.setPartner(partner);
    return company;
  }

  private static Person person(String organization) {
    Person person = new Person();
    person.setOrganizations(List.of(new Organization().setName(organization)));
    return person;
  }
}
