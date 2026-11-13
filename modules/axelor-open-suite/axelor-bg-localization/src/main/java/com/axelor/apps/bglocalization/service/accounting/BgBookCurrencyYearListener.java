package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Year;
import java.util.Objects;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PostUpdate;
import javax.persistence.PreUpdate;

/** Freezes a fiscal year's book currency once a non-null value has been persisted. */
public class BgBookCurrencyYearListener {

  @PostLoad
  @PostPersist
  @PostUpdate
  public void remember(Year year) {
    year.setBgBookCurrencySnapshotId(id(year.getBgBookCurrency()));
  }

  @PreUpdate
  public void rejectCurrencyChange(Year year) {
    Long persisted = year.getBgBookCurrencySnapshotId();
    Long current = id(year.getBgBookCurrency());
    if (persisted != 0L && !Objects.equals(persisted, current)) {
      throw new IllegalStateException(
          "Счетоводната валута на годината е фиксирана и не може да се променя.");
    }
  }

  private static Long id(Currency currency) {
    return currency == null || currency.getId() == null ? 0L : currency.getId();
  }
}
