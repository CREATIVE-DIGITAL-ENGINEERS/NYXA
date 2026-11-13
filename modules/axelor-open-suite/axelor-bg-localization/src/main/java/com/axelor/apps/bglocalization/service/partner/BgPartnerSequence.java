package com.axelor.apps.bglocalization.service.partner;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.SequenceRepository;
import java.time.LocalDate;
import java.util.Collection;

/**
 * Поредицата за номерата на партньорите на едно дружество. Чиста функция: строи и смята, не
 * записва.
 *
 * <p><b>Форматът</b> е този на общата поредица от axelor-base ({@code base_sequence.csv}, importId
 * 1): префикс {@code P}, 4 цифри, без суфикс, без нулиране. Ако в базата има обща поредица,
 * форматът се взема от нея ({@link #newFor}) — заварените номера и новите изглеждат еднакво.
 * Броячът НЕ се взема оттам: всяко дружество брои само.
 *
 * <p><b>Откъде продължава</b> ({@link #nextNum}): над най-големия номер, който дружеството може да
 * срещне — своите партньори, заварените без дружество (миграцията ще ги разпредели, а не знаем още
 * към кого) и докъдето е стигнал общият брояч (номер на изтрит партньор може да стои в документ или
 * в SAF-T). Така нов номер никога не съвпада със заварен. Заварените номера не се пренареждат.
 *
 * <p>Единствената версия започва на 01.01.2000, както при {@code BgVatProtocolSequence}: поредица
 * без нулиране има ЕДНА отворена версия.
 */
public final class BgPartnerSequence {

  public static final String CODE = SequenceRepository.PARTNER;
  public static final String NAME = "Партньори";
  public static final String DEFAULT_PREFIX = "P";
  public static final int DEFAULT_PADDING = 4;
  public static final LocalDate VERSION_START = LocalDate.of(2000, 1, 1);

  private BgPartnerSequence() {}

  /** {@code importId} на поредицата на дружеството — за да се намира по него при внос. */
  public static String importIdFor(Company company) {
    return company.getCode() == null ? null : company.getCode() + "-" + CODE;
  }

  /**
   * Нова поредица за {@code company}, която ще даде {@code nextNum} като следващ номер.
   *
   * @param template общата поредица (company празно) — само за формата; {@code null} → P + 4 цифри
   */
  public static Sequence newFor(Company company, Sequence template, long nextNum) {
    Sequence sequence = new Sequence();
    sequence.setCompany(company);
    sequence.setImportId(importIdFor(company));
    sequence.setName(NAME);
    sequence.setCodeSelect(CODE);
    if (template != null) {
      sequence.setPrefixe(template.getPrefixe());
      sequence.setSuffixe(template.getSuffixe());
      sequence.setPadding(template.getPadding() > 0 ? template.getPadding() : DEFAULT_PADDING);
    } else {
      sequence.setPrefixe(DEFAULT_PREFIX);
      sequence.setSuffixe(null);
      sequence.setPadding(DEFAULT_PADDING);
    }
    sequence.setToBeAdded(1);
    sequence.setYearlyResetOk(false);
    sequence.setMonthlyResetOk(false);
    SequenceVersion version = new SequenceVersion();
    version.setStartDate(VERSION_START);
    version.setNextNum(nextNum);
    sequence.addSequenceVersionListItem(version);
    return sequence;
  }

  /**
   * Следващият номер на новата поредица: 1 над най-голямото от числата в {@code codes} (само кодове
   * във формата префикс + цифри + суфикс) и от {@code counterNext - 1}.
   *
   * @param codes номерата, които дружеството може да срещне (свои и заварени без дружество)
   * @param counterNext следващият номер на общия брояч; 0 или по-малко — няма такъв
   */
  public static long nextNum(
      String prefix, String suffix, Collection<String> codes, long counterNext) {
    long max = Math.max(0, counterNext - 1);
    for (String code : codes) {
      Long number = numberOf(prefix, suffix, code);
      if (number != null && number > max) {
        max = number;
      }
    }
    return max + 1;
  }

  /** Числото в {@code code}, ако е точно префикс + цифри + суфикс; иначе {@code null}. */
  static Long numberOf(String prefix, String suffix, String code) {
    if (code == null) {
      return null;
    }
    String p = prefix == null ? "" : prefix;
    String s = suffix == null ? "" : suffix;
    String trimmed = code.trim();
    if (trimmed.length() <= p.length() + s.length()
        || !trimmed.startsWith(p)
        || !trimmed.endsWith(s)) {
      return null;
    }
    String digits = trimmed.substring(p.length(), trimmed.length() - s.length());
    // До 18 цифри — да не прелее long; по-дълъг „номер“ не е от брояч.
    if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
      return null;
    }
    return Long.parseLong(digits);
  }
}
