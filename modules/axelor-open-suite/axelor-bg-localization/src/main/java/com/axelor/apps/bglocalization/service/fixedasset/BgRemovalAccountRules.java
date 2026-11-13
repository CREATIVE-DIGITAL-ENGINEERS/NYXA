package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.util.List;

/**
 * Коя сметка носи стойността на актива при отписване — и кога тя не е разходна.
 *
 * <p><b>Възстановимата липса не е разход, а вземане.</b> Когато има отговорно лице, стойността не
 * напуска предприятието — тя сменя формата си от актив във вземане от това лице. Осчетоводена на
 * 60905 „Разходи за липси и брак на активи“, тя намалява финансовия резултат с нещо, което ще се
 * събере.
 *
 * <p>⚠️ Номерът „442 Вземания по липси и начети“ е счетоводна КОНВЕНЦИЯ и ИЗБОР НА СЪСТАВИТЕЛЯ, не
 * законово изискване — задължителен национален сметкоплан няма от 2002 г., сметкопланът е на
 * предприятието. Затова номерът не е закован в кода НИКЪДЕ: той стои в началните данни, на колоната
 * {@code AssetDisposalReason.bgRemovalAccountCode}, откъдето съставителят може да го смени, а
 * {@code BgAssetRemovalAccount} го припокрива за отделно дружество.
 *
 * <p>Редът на резолюция е с ТРИ стъпала и всяко следващо е по-общо от предното: ред в {@code
 * BgAssetRemovalAccount} (една фирма, една причина) → кодът на причината, потърсен в чарта на ТАЗИ
 * фирма → нищо, тоест сметката остава онази, която базата е избрала (60905). Последното стъпало е
 * нормален изход, не грешка: то е видимо в статията.
 *
 * <p>⚠️ И ДДС третирането НЕ се пипа оттук. Този слой сменя само дебитната сметка; дали се дължи
 * корекция по чл. 79 ЗДДС решава причината, и за липсите тя остава „подлежи“ независимо дали са
 * възстановими. Наличието на отговорно лице не е доказване по чл. 80 ал. 2 т. 1 б. „г“ — то
 * по-скоро сочи обратното.
 */
public final class BgRemovalAccountRules {

  private BgRemovalAccountRules() {}

  /**
   * Филтърът за ФИРМЕНОТО ПРИПОКРИВАНЕ — един ред за една фирма и една причина.
   *
   * <p>Стои като константа, а не вътре в услугата, за да е проверим: {@code BgAssetRemovalAccount}
   * има unique-constraint по {@code (company, assetDisposalReason)}, тъй че търсене без фирма би
   * върнало ред на чуждо дружество.
   */
  public static final String OVERRIDE_FILTER =
      "self.company = :company AND self.assetDisposalReason = :reason";

  /**
   * Филтърът за сметката ПО ПОДРАЗБИРАНЕ — по код, но задължително в чарта на СВОЯТА фирма.
   *
   * <p>⚠️ {@code Account} е уникален по {@code code + company}: един и същ код „442“ съществува
   * отделно за всяко дружество. Търсене само по код с {@code fetchOne()} връща произволната от тях
   * — точно това прави базовият резолвър на справките ({@code
   * ImportAccountingReportConfigLine#setAccounts}) и затова филтърът тук е закован като константа,
   * а тестът го чете.
   *
   * <p>⚠️ И само АКТИВНА сметка: деактивирана сметка на ред от статия поваля валидирането с {@code
   * INACTIVE_ACCOUNT_FOUND}. Подразбирането трябва да отстъпи към 60905, а не да спре отписване.
   */
  public static final String DEFAULT_ACCOUNT_FILTER =
      "self.code = :code AND self.company = :company AND self.statusSelect = :statusSelect";

  /**
   * Кой от двата кандидата печели: изричният ред на фирмата, иначе подразбирането по код.
   *
   * <p>{@code null} и от двата значи „остави както е“ — сметката остава онази, която базата е
   * избрала (60905 за липси). Това е нормалният изход за осемте причини без подразбиране.
   */
  public static Account removalAccount(Account override, Account byCode) {
    return override != null ? override : byCode;
  }

  /**
   * Кодът на сметката по подразбиране, обявен от причината — или {@code null}, когато няма такъв.
   *
   * <p><b>Код, а не връзка, и това не е мързел.</b> {@code AssetDisposalReason} няма компания, а
   * {@code Account} е company-scoped (уникалността му е по {@code code + company}). Връзка оттам би
   * вързала една фирмена сметка към причина, която важи за всичките — тоест при второ дружество
   * статията щеше да сочи чужда сметка. Затова тук стои кодът, а резолюцията се прави в чарта на
   * фирмата, която извежда актива.
   *
   * <p>Празен и само-бели-интервали код се четат като „няма“: колоната е попълнена на ръка и
   * разликата между {@code ""} и {@code " "} не бива да мени поведението.
   */
  public static String defaultAccountCode(AssetDisposalReason reason) {
    if (reason == null) {
      return null;
    }
    String code = reason.getBgRemovalAccountCode();
    if (code == null) {
      return null;
    }
    code = code.trim();
    return code.isEmpty() ? null : code;
  }

  /**
   * Сметката, която БАЗАТА би избрала за стойността на актива — огледало на нейния избор.
   *
   * <p>{@code FixedAssetLineMoveServiceImpl.generateDisposalMove} решава вътре в себе си, в 156
   * реда без шев: продажба и частична продажба → {@code realisedAssetsValueAccount}, всичко
   * останало → {@code approExtraordDepreciationExpenseAccount}. Огледалото е нужно, за да се
   * РАЗПОЗНАЕ редът, който трябва да се пренасочи — не за да се вземе решението наново.
   *
   * <p>⚠️ Ако базата някой ден промени избора си, това огледало ще се размине с нея и редът няма да
   * бъде намерен — тогава пренасочване просто не се случва и поведението се връща към базовото.
   * Тихо, но безопасно: сметката остава онази, която базата е избрала, а не произволна.
   */
  public static Account baseChargeAccount(FixedAssetCategory category, int transferredReason) {
    if (category == null) {
      return null;
    }
    if (transferredReason == FixedAssetRepository.TRANSFERED_REASON_CESSION
        || transferredReason == FixedAssetRepository.TRANSFERED_REASON_PARTIAL_CESSION) {
      return category.getRealisedAssetsValueAccount();
    }
    return category.getApproExtraordDepreciationExpenseAccount();
  }

  /**
   * Сметката, в която активът се отписва: разрешената за причината ({@code removal} — фирмено
   * припокриване или кодът от причината), иначе базовата за вида отписване.
   *
   * <p>Това е и сметката на протокола по чл. 79 ЗДДС. Тя НЕ се чете от статията: при напълно
   * амортизиран актив редът за балансовата стойност липсва и първият дебитен ред е 241/242.
   */
  public static Account chargeAccount(
      Account removal, FixedAssetCategory category, int transferredReason) {
    return removal != null ? removal : baseChargeAccount(category, transferredReason);
  }

  /**
   * Редът в статията, който носи стойността на актива и подлежи на пренасочване.
   *
   * <p>Търси се ДЕБИТЕН ред по очакваната сметка. Кредитните редове са отписването на самия актив
   * (Кт 20x) и натрупаната амортизация — те не се пипат, а и биха съвпаднали по сметка само при
   * абсурдна конфигурация.
   *
   * <p>⚠️ Връща {@code null}, когато редът не е намерен, и това е нормален изход, не грешка: при
   * напълно амортизиран актив стойността е нула и базата изобщо не създава такъв ред ({@code
   * chargeAmount.signum() != 0}). Тогава няма какво да се пренасочва.
   */
  public static MoveLine chargeLineToRepoint(List<MoveLine> moveLines, Account expected) {
    if (moveLines == null || expected == null) {
      return null;
    }
    for (MoveLine line : moveLines) {
      if (line == null || line.getAccount() == null) {
        continue;
      }
      // Генерираният getter на BigDecimal връща ZERO за незададена колона, никога null —
      // проверка за null е мъртво условие (виж BgDefaultedGetterTest).
      boolean debit = line.getDebit().signum() > 0;
      if (debit && expected.equals(line.getAccount())) {
        return line;
      }
    }
    return null;
  }

  /**
   * Трябва ли изобщо пренасочване.
   *
   * <p>Липсващ мапинг значи „остави както е“ — поведението се връща към базовото (60905) и това е
   * видимо в статията, вместо да падне някъде тихо. Мапинг към същата сметка също не е промяна.
   */
  public static boolean shouldRepoint(Account mapped, Account current) {
    return mapped != null && current != null && !mapped.equals(current);
  }
}
