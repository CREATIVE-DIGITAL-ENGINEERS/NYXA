package com.axelor.apps.aiaccountant.route;

import com.axelor.apps.aiaccountant.prompt.AiTask;

/**
 * Whether a maintenance instruction is about making a record or about changing one.
 *
 * <h2>The gap this fills</h2>
 *
 * {@link AiTask#MAINTAIN_SUPPLIER} is one task for both — «създай доставчик с ЕИК …» and «смени
 * IBAN-а на доставчика» route identically — because the playbook, the capabilities and the domain
 * guide they need are identical. The authorised TARGET is not: a create must be frozen on {@code
 * PartnerCreate:<company>:<eik>}, the synthetic identity {@code manage_supplier(create)} declares
 * and {@code SupplierMaintenanceService} refuses to duplicate, while an update must be frozen on
 * the existing {@code Partner#id}. Freeze the wrong one and the correct instruction is refused.
 *
 * <p>That difference was being read off a list of Bulgarian and English verbs — «създ», «направи»,
 * «нов », "create", "add supplier" — in {@code SupplierMessageTargetResolver}. The usual two
 * failures followed: a phrasing nobody listed («регистрирай нов контрагент …» is covered, «заведи
 * доставчик …» is not) silently became an update and was refused, and the list had to be kept in
 * step by hand with {@code AiTaskResolver}'s own, separate verb list — which it already drifted
 * from once, costing exactly this authorisation.
 *
 * <p>So the reading moves to where every other reading of the user's sentence already is: the
 * router states it, the server acts on it, and no verb anywhere decides an authorisation. See
 * {@link AiSetSelection}, which exists for the same reason on the list path.
 *
 * <h2>Absent is a distinct state</h2>
 *
 * Null on {@link AiRoute} means nothing stated it — the deterministic adapter route, used when the
 * router is off or did not answer. It is deliberately NOT read as {@link #CREATE}: the conservative
 * reading of an unexplained maintenance instruction is that it is about a record that already
 * exists, which authorises a change to one named record rather than minting the authority to add a
 * new one. {@code SupplierMessageTargetResolver} documents what that costs.
 */
public enum AiWriteOperation {

  /** The instruction asks for a record that does not exist yet to be made. */
  CREATE,

  /** The instruction asks for an existing record to be changed. */
  UPDATE
}
