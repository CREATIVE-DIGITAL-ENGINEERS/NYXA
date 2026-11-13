package com.axelor.apps.nyxasecurity.gate;

import java.util.Set;

/**
 * Which roles grant each gated capability. Roles are the per-area Keycloak role names. Write
 * implies read, and all.read / all.write cover every area and sub-area, so each set lists every
 * role that carries the capability.
 */
public final class Capabilities {

  private Capabilities() {}

  /**
   * НАП: VAT returns, ledgers, annual corrections, SAF-T, submissions and the income filings —
   * prepare, generate, submit and record the outcome. The monthly SAF-T file has no approval step
   * (decision 20.10.2026).
   */
  public static final Set<String> NAP_WRITE =
      Set.of("accounting.nap.write", "all.write", "client.admin");

  /** Payroll read-outs with personal data: payroll ledger, contribution preview. */
  public static final Set<String> PAYROLL_READ =
      Set.of("hr.payroll.read", "hr.payroll.write", "all.read", "all.write", "client.admin");

  /**
   * Run payroll and file its declarations: pay slips, compensations, SEPA file, Microinvest import,
   * D1, D6, art. 73(6), the employment register record (ETZ, art. 62(3) КТ), applying an arrived
   * sick note (it creates a corrective pay slip).
   */
  public static final Set<String> PAYROLL_WRITE =
      Set.of("hr.payroll.write", "all.write", "client.admin");

  /** Accounting read-outs that build a file without changing anything: БНБ СПБ-6А/6Б workbook. */
  public static final Set<String> ACCOUNTING_READ =
      Set.of("accounting.read", "accounting.write", "all.read", "all.write", "client.admin");

  /** Accounting: compute, lock and unlock the НСИ annual report (ГОД) helper. */
  public static final Set<String> ACCOUNTING_WRITE =
      Set.of("accounting.write", "all.write", "client.admin");

  /** НОИ: App. 9 declarations and their export. */
  public static final Set<String> NOI_WRITE = Set.of("hr.noi.write", "all.write", "client.admin");

  /** AI Accountant chat and its read-only tools. */
  public static final Set<String> AI_READ =
      Set.of("accounting.ai.read", "accounting.ai.write", "all.read", "all.write", "client.admin");

  /** AI Accountant tools that create or change records (drafts). */
  public static final Set<String> AI_WRITE =
      Set.of("accounting.ai.write", "all.write", "client.admin");

  /**
   * Client administration: closing periods (G2), assigning companies to users (D10), accounting
   * configuration.
   */
  public static final Set<String> CLIENT_ADMIN = Set.of("client.admin");
}
