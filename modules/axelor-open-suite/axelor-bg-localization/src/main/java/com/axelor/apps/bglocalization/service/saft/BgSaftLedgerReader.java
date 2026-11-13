package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.bglocalization.db.BgSafAccountMapping;
import com.axelor.apps.bglocalization.db.repo.BgSafAccountMappingRepository;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * ЕДИНСТВЕНОТО МЯСТО, КОЕТО ЧЕТЕ БАЗАТА ЗА GeneralLedgerAccounts, Customers И Suppliers — всичко
 * надолу работи върху стойности.
 *
 * <p><b>⚠️ САЛДАТА СЕ СМЯТАТ В БАЗАТА, НЕ В ПАМЕТТА.</b> Две заявки {@code GROUP BY} сметка ({@link
 * BgSaftMoveScope#OPENING_BY_ACCOUNT} и {@link BgSaftMoveScope#TURNOVER_BY_ACCOUNT}) връщат по ред
 * на сметка — стотици реда, без нито едно {@code MoveLine} в паметта. Затова тук няма четене на
 * парчета и {@code JPA.clear()}: те са нужни на износ, който зарежда всеки ред като обект (FEC, а
 * при SAF-T — G3), не на сума. Контрагентите са същото с {@code GROUP BY} сметка и партньор,
 * ограничено до сметките на клиенти и доставчици.
 *
 * <p>Мапингът се чете с ЕДНА заявка на генериране: таблицата не зависи от дружеството (457 реда), а
 * кеш между генериранията би скрил редакция, направена през изгледа.
 */
@Singleton
public class BgSaftLedgerReader {

  /**
   * Партньорите на Customers/Suppliers — една проекция със стойности, без entity: основният адрес,
   * населеното място и държавата идват с LEFT JOIN, вместо по заявка на партньор.
   */
  static final String PARTNERS =
      "SELECT p.id, p.partnerSeq, p.partnerTypeSelect, p.name, p.firstName, p.bgMiddleName,"
          + " p.registrationCode, p.taxNbr, p.bgIdentifierType,"
          + " a.streetName, a.addressL4, a.buildingNumber, a.zip, ci.name, a.townName,"
          + " co.alpha2Code"
          + " FROM Partner p LEFT JOIN p.mainAddress a LEFT JOIN a.city ci LEFT JOIN a.country co"
          + " WHERE p.id IN (:partnerIds)";

  /** Партньорите се четат на парчета — списъкът в IN е параметър на заявката. */
  private static final int PARTNER_CHUNK = 1000;

  protected final AccountRepository accountRepo;
  protected final BgSafAccountMappingRepository mappingRepo;

  @Inject
  public BgSaftLedgerReader(
      AccountRepository accountRepo, BgSafAccountMappingRepository mappingRepo) {
    this.accountRepo = accountRepo;
    this.mappingRepo = mappingRepo;
  }

  /** Сметкопланът на дружеството, включително архивираните — по тях може да има салдо. */
  public List<BgSaftGlAccountsAssembler.AccountRow> chart(Long companyId) {
    List<BgSaftGlAccountsAssembler.AccountRow> rows = new ArrayList<>();
    for (Account account :
        accountRepo
            .all()
            .filter("self.company.id = :companyId")
            .bind("companyId", companyId)
            .fetch()) {
      Account parent = account.getParentAccount();
      rows.add(
          new BgSaftGlAccountsAssembler.AccountRow(
              account.getId(),
              account.getCode(),
              account.getName(),
              account.getBgApSelect(),
              parent == null ? null : parent.getId()));
    }
    return rows;
  }

  /** {@code BgSafAccountMapping} по код на сметката. */
  public Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings() {
    Map<String, BgSaftGlAccountsAssembler.MappingRow> map = new HashMap<>();
    for (BgSafAccountMapping mapping : mappingRepo.all().fetch()) {
      String code = mapping.getAccountCode();
      if (code == null || code.trim().isEmpty()) {
        continue;
      }
      map.put(
          code.trim(),
          new BgSaftGlAccountsAssembler.MappingRow(
              mapping.getNraCode(), Boolean.TRUE.equals(mapping.getIsExcluded())));
    }
    return map;
  }

  /** Началното салдо и оборотът по сметка, незакръглени. */
  public Map<Long, BgSaftBalance> balancesByAccount(BgSaftMoveScope scope) {
    Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();
    for (Object[] row : rows(BgSaftMoveScope.OPENING_BY_ACCOUNT, scope)) {
      balances.merge(id(row[0]), BgSaftBalance.opening(decimal(row[1])), BgSaftBalance::plus);
    }
    for (Object[] row : rows(BgSaftMoveScope.TURNOVER_BY_ACCOUNT, scope)) {
      balances.merge(
          id(row[0]),
          BgSaftBalance.turnover(decimal(row[1]), decimal(row[2])),
          BgSaftBalance::plus);
    }
    return balances;
  }

  /**
   * Началното салдо и оборотът по (сметка, контрагент), незакръглени — само по подадените сметки
   * (клиентите и доставчиците). Ред без контрагент идва с {@code partnerId = null}.
   */
  public Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balancesByAccountAndPartner(
      BgSaftMoveScope scope, Collection<Long> accountIds) {
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balances = new LinkedHashMap<>();
    if (accountIds.isEmpty()) {
      return balances;
    }
    Map<String, Object> extra = Collections.singletonMap("accountIds", new ArrayList<>(accountIds));
    for (Object[] row : rows(BgSaftMoveScope.OPENING_BY_ACCOUNT_AND_PARTNER, scope, extra)) {
      balances.merge(
          new BgSaftPartiesAssembler.Key(id(row[0]), nullableId(row[1])),
          BgSaftBalance.opening(decimal(row[2])),
          BgSaftBalance::plus);
    }
    for (Object[] row : rows(BgSaftMoveScope.TURNOVER_BY_ACCOUNT_AND_PARTNER, scope, extra)) {
      balances.merge(
          new BgSaftPartiesAssembler.Key(id(row[0]), nullableId(row[1])),
          BgSaftBalance.turnover(decimal(row[2]), decimal(row[3])),
          BgSaftBalance::plus);
    }
    return balances;
  }

  /** Партньорите по id — полетата, от които се строят идентификаторът, името и адресът. */
  public Map<Long, BgSaftPartiesAssembler.PartyRow> partners(Collection<Long> partnerIds) {
    Map<Long, BgSaftPartiesAssembler.PartyRow> partners = new HashMap<>();
    List<Long> ids = new ArrayList<>(partnerIds);
    for (int from = 0; from < ids.size(); from += PARTNER_CHUNK) {
      TypedQuery<Object[]> query = JPA.em().createQuery(PARTNERS, Object[].class);
      query.setParameter(
          "partnerIds", ids.subList(from, Math.min(ids.size(), from + PARTNER_CHUNK)));
      for (Object[] r : query.getResultList()) {
        Long id = id(r[0]);
        partners.put(
            id,
            new BgSaftPartiesAssembler.PartyRow(id)
                .partnerSeq((String) r[1])
                .partnerTypeSelect(r[2] == null ? null : ((Number) r[2]).intValue())
                .name((String) r[3])
                .firstName((String) r[4])
                .middleName((String) r[5])
                .registrationCode((String) r[6])
                .taxNbr((String) r[7])
                .identifierType((String) r[8])
                // Улицата и населеното място — по реда на Header-а: полето, после свободният текст.
                .street(firstNonBlank((String) r[9], (String) r[10]))
                .number((String) r[11])
                .postalCode((String) r[12])
                .city(firstNonBlank((String) r[13], (String) r[14]))
                .country((String) r[15]));
      }
    }
    return partners;
  }

  /** Има ли осчетоводени статии преди началото на финансовата година. */
  public boolean postedBeforeYear(BgSaftMoveScope scope) {
    return count(BgSaftMoveScope.POSTED_BEFORE_YEAR, scope) > 0;
  }

  /** Има ли откриваща статия във финансовата година. */
  public boolean openingMoveInYear(BgSaftMoveScope scope) {
    return count(BgSaftMoveScope.OPENING_MOVES_IN_YEAR, scope) > 0;
  }

  /** Неосчетоводените статии с дата в периода (G6a). */
  public List<BgSaftEnvironmentCheck.MoveRow> unpostedMoves(BgSaftMoveScope scope) {
    return moveRows(rows(BgSaftMoveScope.UNPOSTED_MOVES, scope));
  }

  /** Статиите на периода, създадени след {@code since} (G6a). */
  public List<BgSaftEnvironmentCheck.MoveRow> movesCreatedAfter(
      BgSaftMoveScope scope, LocalDateTime since) {
    return moveRows(
        rows(
            BgSaftMoveScope.MOVES_CREATED_AFTER,
            scope,
            Collections.singletonMap("createdAfter", since)));
  }

  private static List<BgSaftEnvironmentCheck.MoveRow> moveRows(List<Object[]> rows) {
    List<BgSaftEnvironmentCheck.MoveRow> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(
          new BgSaftEnvironmentCheck.MoveRow(
              (Long) r[0], (String) r[1], r[2] == null ? null : ((Number) r[2]).intValue()));
    }
    return out;
  }

  // ─────────────── изпълнението ───────────────

  private List<Object[]> rows(String jpql, BgSaftMoveScope scope) {
    return rows(jpql, scope, Collections.emptyMap());
  }

  private List<Object[]> rows(String jpql, BgSaftMoveScope scope, Map<String, Object> extra) {
    TypedQuery<Object[]> query = JPA.em().createQuery(jpql, Object[].class);
    scope.parameters(jpql, extra).forEach(query::setParameter);
    return query.getResultList();
  }

  private long count(String jpql, BgSaftMoveScope scope) {
    TypedQuery<Long> query = JPA.em().createQuery(jpql, Long.class);
    scope.parameters(jpql).forEach(query::setParameter);
    Long count = query.getSingleResult();
    return count == null ? 0 : count;
  }

  private static Long id(Object value) {
    return ((Number) value).longValue();
  }

  private static Long nullableId(Object value) {
    return value == null ? null : id(value);
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.trim().isEmpty() ? first : second;
  }

  private static BigDecimal decimal(Object value) {
    if (value == null || value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(value.toString());
  }
}
