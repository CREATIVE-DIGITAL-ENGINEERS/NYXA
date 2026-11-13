#!/usr/bin/env python3
"""ДАННИТЕ ЗА ПРОБНИЯ SAF-T — през REST на вдигнато приложение, като admin.

Тестовата е-услуга на НАП „Подаване на ТЕСТОВ SAF-T“ (QA IV.1.1, с. 93). Скриптът сглобява в
ИЗОЛИРАНА база (никога общата `nyxa`, никога база на внедрена среда) едно дружество с BG_COA и два месеца данни:

  09.2026 — такса 629/503 (неразплащателен журнал), продажба 20 % (411/702/4532), покупка
            (401/302/4531), плащане (503/401), протокол по чл. 117 за услуга от ЕС, протокол по
            чл. 79 ал. 1–3;
  10.2026 — само такса 629/503 (нулевите Payments, фактури, Customers/Suppliers).

Материалите от покупката остават НА СКЛАД (наличност 20 бр.): XSD иска поне един Product и една
UOMTableEntry, QA IV.2.6 няма нулева структура за тях, и в месец без фактури генераторът ги взима
само от наличността (SAFT_P_EMPTY / SAFT_UOM_EMPTY иначе). Модулът „Склад“ не е инсталиран, затова
наличността е ред на склада, създаден направо — без складово движение.

Операциите са измислени. Реалните данни — ЕИК, име, адрес и IBAN на дружеството и двамата
български контрагенти (клиент и доставчик) — се подават САМО от командния ред и влизат САМО в
изолираната база; в репото ги няма.

⚠️ Контрагентите са РЕАЛНИ, не измислени: тестовата е-услуга отказва измислени ЕИК с вярна
контролна цифра („некоректен идентификатор“, MF.C.3/MF.S.3) — явно ги сверява с регистъра.
Доставчикът от ЕС остава измислен (FR ДДС номер с верен ключ) — него порталът приема.
⚠️ IBAN на дружеството е задължителен (S.CMH.6/S.BA.1); Axelor иска и BIC (Bank) за сметката.

    python3 -I saft_trial_data.py --eik <ЕИК> --name '<име>' --city '<град>' --zip <пк> \
        --street '<улица>' --iban <IBAN> --bic <BIC> \
        --customer-eik <ЕИК> --customer-name '<име>' --customer-city '<град>' \
        --customer-zip <пк> --customer-street '<улица>' \
        --supplier-eik <ЕИК> --supplier-name '<име>' --supplier-city '<град>' \
        --supplier-zip <пк> --supplier-street '<улица>' \
        [--base-url http://localhost:8080] [--user admin] [--password admin]

Генерирането е отделно: BgSaftTrialFileIntegrationTest (javadoc-ът му има командата).
Шаблонът на контролния списък: контролния списък за пробно подаване.

Само python3 stdlib (urllib), без външни пакети.
"""
import argparse
import base64
import json
import random
import sys
import urllib.error
import urllib.request

MODEL = "com.axelor.apps"


class Rest:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def call(self, method, path, body=None):
        req = urllib.request.Request(
            self.base + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={
                "Authorization": self.auth,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req) as resp:
                out = json.load(resp)
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read()[:2000]!r}")
        if out.get("status", 0) != 0:
            data = out.get("data") or {}
            message = data.get("message") if isinstance(data, dict) else None
            cause = (data.get("causeStack") or "").split("\n")[0] if isinstance(data, dict) else ""
            raise SystemExit(f"{method} {path}: {message or cause or json.dumps(out)[:1500]}")
        return out

    def search(self, model, domain, fields=("id",), context=None, limit=50):
        body = {"fields": list(fields), "limit": limit, "data": {"_domain": domain}}
        if context:
            body["data"]["_domainContext"] = context
        return self.call("POST", f"/ws/rest/{model}/search", body).get("data") or []

    def one(self, model, domain, fields=("id",), context=None):
        rows = self.search(model, domain, fields, context, limit=2)
        if len(rows) != 1:
            raise SystemExit(f"{model} [{domain}] {context}: очакван 1 ред, а са {len(rows)}")
        return rows[0]

    def create(self, model, data):
        return self.call("PUT", f"/ws/rest/{model}", {"data": data})["data"][0]

    def update(self, model, record_id, data):
        current = self.call("GET", f"/ws/rest/{model}/{record_id}")["data"][0]
        data = dict(data, id=record_id, version=current["version"])
        return self.call("POST", f"/ws/rest/{model}/{record_id}", {"data": data})["data"][0]

    def action(self, model, action, context):
        body = {"model": model, "action": action, "data": {"context": dict(context, _model=model)}}
        out = self.call("POST", "/ws/action", body)
        for item in out.get("data") or []:
            if isinstance(item, dict) and (item.get("error") or item.get("alert")):
                raise SystemExit(f"{action}: {json.dumps(item, ensure_ascii=False)[:3000]}")
        return out


# ─────────────── идентификаторите ───────────────


def eik_ok(eik):
    """9-цифрен ЕИК с вярна контролна цифра (тегла 1..8, после 3..10, mod 11) — проверка на входа."""
    if len(eik) != 9 or not eik.isdigit():
        return False
    d = [int(c) for c in eik]
    s = sum(w * x for w, x in zip(range(1, 9), d)) % 11
    if s == 10:
        s = sum(w * x for w, x in zip(range(3, 11), d)) % 11
        if s == 10:
            s = 0
    return s == d[8]


def fr_vat(rng):
    """FR + ключ + SIREN; SIREN по Luhn, ключът = (12 + 3 × (SIREN mod 97)) mod 97."""
    while True:
        digits = [rng.randint(0, 9) for _ in range(8)]
        for check in range(10):
            siren = digits + [check]
            total = 0
            for i, x in enumerate(reversed(siren)):
                y = x * 2 if i % 2 else x
                total += y - 9 if y > 9 else y
            if total % 10 == 0 and siren[0] != 0:
                n = int("".join(map(str, siren)))
                return f"FR{(12 + 3 * (n % 97)) % 97:02d}{n:09d}"


# ─────────────── сглобяването ───────────────

BASE = MODEL + ".base.db."
ACC = MODEL + ".account.db."


def find_or_create(r, model, domain, context, data):
    rows = r.search(model, domain, ("id",), context, limit=2)
    if len(rows) > 1:
        raise SystemExit(f"{model} [{domain}] {context}: повече от един ред")
    return rows[0] if rows else r.create(model, data)


def ref(model, r, domain, context=None):
    return {"id": r.one(model, domain, ("id",), context)["id"]}


class Trial:
    def __init__(self, r, args):
        self.r = r
        self.args = args
        self.rng = random.Random(args.seed)

    def acc(self, code):
        return ref(ACC + "Account", self.r, "self.company.id = :c AND self.code = :code",
                   {"c": self.company, "code": code})

    def journal(self, code):
        return ref(ACC + "Journal", self.r, "self.company.id = :c AND self.code = :code",
                   {"c": self.company, "code": code})

    def tax_line(self, tax):
        return ref(ACC + "TaxLine", self.r, "self.tax.code = :t", {"t": tax})

    def reverse_charge(self, position, from_tax, to_tax):
        """Еквивалентът с обратно начисляване на фискалната позиция (от → към). Axelor пише
        двойката данъци от него ({@code TaxInvoiceLine}): +ДДС по данъка на реда и −ДДС по
        reverseChargeTaxSet, а фактурата излиза без данък."""
        wanted = {ref(ACC + "Tax", self.r, "self.code = :t", {"t": t})["id"]
                  for t in (from_tax, to_tax)}
        for row in self.r.search(ACC + "TaxEquiv", "self.fiscalPosition.code = :fp AND"
                                 " self.reverseCharge = true", ("fromTaxSet", "toTaxSet"),
                                 {"fp": position}):
            ids = {t["id"] for t in row["fromTaxSet"] + row["toTaxSet"]}
            if ids == wanted:
                return {"id": row["id"]}
        raise SystemExit(f"няма еквивалент {position}: {from_tax} → {to_tax}")

    def mode(self, code):
        return ref(ACC + "PaymentMode", self.r, "self.code = :c", {"c": code})

    def unit(self, name):
        return ref(BASE + "Unit", self.r, "self.name = :n", {"n": name})

    def country(self, alpha2):
        return ref(BASE + "Country", self.r, "self.alpha2Code = :a", {"a": alpha2})

    # ── дружеството, BG_COA, финансовата година ──

    def company_setup(self):
        a = self.args
        r = self.r
        city = find_or_create(r, BASE + "City", "self.name = :n", {"n": a.city},
                              {"name": a.city, "country": self.country("BG")})
        partner = find_or_create(
            r, BASE + "Partner", "self.registrationCode = :e", {"e": a.eik},
            {"name": a.name, "partnerTypeSelect": 1, "registrationCode": a.eik,
             "taxNbr": "BG" + a.eik, "fixedPhone": a.phone})
        address = find_or_create(
            r, BASE + "Address", "self.streetName = :s AND self.zip = :z",
            {"s": a.street, "z": a.zip},
            {"streetName": a.street, "zip": a.zip, "city": {"id": city["id"]},
             "country": self.country("BG")})
        eur = ref(BASE + "Currency", r, "self.codeISO = 'EUR'")
        bg = ref(BASE + "Localization", r, "self.name LIKE 'Български%'")
        company = find_or_create(
            r, BASE + "Company", "self.partner.id = :p", {"p": partner["id"]},
            {"name": a.name, "code": a.code, "partner": {"id": partner["id"]}, "currency": eur,
             "address": {"id": address["id"]}, "localization": bg})
        self.company = company["id"]
        config = find_or_create(
            r, ACC + "AccountConfig", "self.company.id = :c", {"c": self.company},
            {"company": {"id": self.company},
             "accountingConfigTemplate": ref(ACC + "AccountingConfigTemplate", r,
                                             "self.code = 'BG_COA'")})
        self.config = config["id"]
        self.situation(partner)
        self.bank_account(partner, a.iban, a.bic)
        if not r.search(ACC + "Account", "self.company.id = :c", ("id",), {"c": self.company}, 1):
            r.action(ACC + "AccountConfig",
                     "action-account-config-method-install-chart-and-generate-fiscal-year-and-period",
                     {"id": self.config, "company": {"id": self.company},
                      "accountingConfigTemplate": config.get("accountingConfigTemplate"),
                      "fiscalYearFromDate": "2026-01-01", "fiscalYearToDate": "2026-12-31",
                      "accountingPeriodDuration": "month"})
        # ДДС протоколите — журналът и сметките, които четат чл. 117 и чл. 79.
        r.update(ACC + "AccountConfig", self.config, {
            "bgVatCorrectionJournal": self.journal("VAT"),
            "bgVatChargedAccount": self.acc("4532"),
            "bgVatDeductibleAccount": self.acc("4531"),
            "bgVatAnnualCorrectionExpenseAccount": self.acc("60909"),
        })
        # ⚠️ BG_COA дава на четирите поредици за фактури (продажби, покупки и известията им) един и
        # същ първи номер, а Axelor проверява уникалността на invoiceId МЕЖДУ тях — втората фактура
        # се отказва („The generated sequence … already exists“). Тук покупките и известията
        # тръгват от различни хиляди; самият шаблон не го решава.
        for name, start in (("Фактура от доставчик", 1001), ("Кредитно известие към клиент", 2001),
                            ("Кредитно известие от доставчик", 3001)):
            seq = r.one(BASE + "Sequence", "self.company.id = :c AND self.name = :n",
                        context={"c": self.company, "n": name})
            for version in r.search(BASE + "SequenceVersion", "self.sequence.id = :s",
                                    ("id", "nextNum"), {"s": seq["id"]}):
                if (version.get("nextNum") or 0) < start:
                    r.update(BASE + "SequenceVersion", version["id"], {"nextNum": start})
        # ⚠️ Журналът PCH на BG_COA пуска типове „ДМА“, „задължения“, „данъци“ и „разходи“, но НЕ
        # „текущ актив“ — покупка на материали по 302 се отказва. Тук типът се добавя; самият
        # шаблон не го решава.
        pch = self.journal("PCH")
        current = r.call("GET", f"/ws/rest/{ACC}Journal/{pch['id']}")["data"][0]
        types = [{"id": t["id"]} for t in current.get("validAccountTypeSet") or []]
        asset = r.one(ACC + "AccountType", "self.technicalTypeSelect = 'currentAsset'")
        if {"id": asset["id"]} not in types:
            r.update(ACC + "Journal", pch["id"], {"validAccountTypeSet": types + [asset]})
        # Печатът на фактурата при осчетоводяване иска BIRT шаблон, който изолираната база няма; за
        # SAF-T е без значение — изключва се САМО в тази база (глобална настройка на AppInvoice).
        app = r.one("com.axelor.studio.db.AppInvoice", "self.id IS NOT NULL")
        r.update("com.axelor.studio.db.AppInvoice", app["id"], {
            "autoGenerateInvoicePrintingFileOnSaleInvoice": False,
            "autoGenerateInvoicePrintingFileOnPurchaseInvoice": False})
        print(f"дружество #{self.company}, AccountConfig #{self.config} — BG_COA, FY2026 по месеци")

    def bank_account(self, partner, iban, bic):
        """Банковата сметка на дружеството — основната; Axelor иска Bank (BIC) за всяка."""
        r = self.r
        iban = iban.replace(" ", "").upper()
        bank = find_or_create(r, BASE + "Bank", "self.code = :c", {"c": bic},
                              {"code": bic, "country": self.country("BG")})
        details = find_or_create(
            r, BASE + "BankDetails", "self.iban = :i", {"i": iban},
            {"iban": iban, "bank": {"id": bank["id"]}, "partner": {"id": partner["id"]},
             "active": True})
        company = r.call("GET", f"/ws/rest/{BASE}Company/{self.company}")["data"][0]
        if (company.get("defaultBankDetails") or {}).get("id") != details["id"]:
            listed = [{"id": d["id"]} for d in company.get("bankDetailsList") or []]
            if {"id": details["id"]} not in listed:
                listed.append({"id": details["id"]})
            r.update(BASE + "Company", self.company,
                     {"defaultBankDetails": {"id": details["id"]}, "bankDetailsList": listed})
        print(f"банкова сметка #{details['id']} на дружеството — основна")

    def situation(self, partner):
        """Счетоводната ситуация на партньора в дружеството — ДДС режим „обща система“ (1)."""
        find_or_create(
            self.r, ACC + "AccountingSituation", "self.partner.id = :p AND self.company.id = :c",
            {"p": partner["id"], "c": self.company},
            {"partner": {"id": partner["id"]}, "company": {"id": self.company},
             "vatSystemSelect": 1})

    # ── контрагенти и продукти ──

    def partner(self, key, name, country, city, zip_code, street, eik=None, vat=None,
                customer=False):
        r = self.r
        found = r.search(BASE + "Partner", "self.partnerSeq = :k OR self.name = :n", ("id",),
                         {"k": key, "n": name}, 2)
        if found:
            self.situation({"id": found[0]["id"]})
            return {"id": found[0]["id"]}
        town = find_or_create(r, BASE + "City", "self.name = :n", {"n": city},
                              {"name": city, "country": self.country(country)})
        address = r.create(BASE + "Address", {"streetName": street, "zip": zip_code,
                                              "city": {"id": town["id"]},
                                              "country": self.country(country)})
        # mainAddress САМО заедно с partnerAddressList: за партньор, който не е контакт или
        # служител, Axelor го преизчислява при всеки запис от адреса по подразбиране за фактуриране
        # (PartnerServiceImpl.updatePartnerAddress → checkDefaultAddress). Без списъка първият
        # update го изчиства и партньорът остава без адрес.
        data = {"name": name, "partnerTypeSelect": 1, "isCustomer": customer,
                "isSupplier": not customer, "mainAddress": {"id": address["id"]},
                "partnerAddressList": [{"address": {"id": address["id"]}, "isDefaultAddr": True,
                                        "isInvoicingAddr": True, "isDeliveryAddr": True}]}
        if eik:
            data["registrationCode"] = eik
        if vat:
            data["taxNbr"] = vat
        p = r.create(BASE + "Partner", data)
        self.situation(p)
        print(f"контрагент #{p['id']} {name} — ЕИК {eik or '—'}, ДДС {vat or '—'}")
        return {"id": p["id"]}

    def product(self, code, name, kind, unit):
        p = find_or_create(self.r, BASE + "Product", "self.code = :c", {"c": code},
                           {"code": code, "name": name, "productTypeSelect": kind,
                            "unit": self.unit(unit), "sellable": True, "purchasable": True})
        return {"id": p["id"]}

    def stock(self, product, unit, qty, price):
        """Наличност на вътрешния склад на дружеството — ред на склада, без складово движение."""
        r = self.r
        location = find_or_create(r, "com.axelor.apps.stock.db.StockLocation",
                                  "self.company.id = :c AND self.name = :n",
                                  {"c": self.company, "n": "TR-WH"},
                                  {"name": "TR-WH", "typeSelect": 1,
                                   "company": {"id": self.company}})
        find_or_create(r, "com.axelor.apps.stock.db.StockLocationLine",
                       "self.stockLocation.id = :l AND self.product.id = :p",
                       {"l": location["id"], "p": product["id"]},
                       {"stockLocation": {"id": location["id"]}, "product": product,
                        "unit": self.unit(unit), "currentQty": qty, "futureQty": qty,
                        "avgPrice": price})
        print(f"наличност: продукт #{product['id']} × {qty} на склад #{location['id']}")

    # ── документите ──

    def invoice(self, ref_no, operation, partner, date, lines, mode, extra=None,
                reverse_charge=None):
        """Фактура: създаване, изчисление, валидиране, осчетоводяване (като бутоните на формата)."""
        r = self.r
        found = r.search(ACC + "Invoice", "self.company.id = :c AND self.supplierInvoiceNb = :n OR"
                         " self.company.id = :c AND self.externalReference = :n", ("id",),
                         {"c": self.company, "n": ref_no}, 2)
        if found:
            return self.finish_invoice(found[0]["id"], ref_no)
        eur = ref(BASE + "Currency", r, "self.codeISO = 'EUR'")
        invoice_lines = []
        for product, name, qty, price, tax, account, credit_type in lines:
            ex = round(qty * price, 2)
            line = {"product": product, "productName": name, "qty": qty, "price": price,
                    "priceDiscounted": price, "exTaxTotal": ex, "companyExTaxTotal": ex,
                    "inTaxTotal": round(ex * 1.2, 2), "companyInTaxTotal": round(ex * 1.2, 2),
                    "account": self.acc(account), "taxLineSet": [self.tax_line(tax)]}
            if credit_type:
                line["bgVatCreditType"] = credit_type
            if reverse_charge:
                position, from_tax = reverse_charge
                line["taxEquiv"] = self.reverse_charge(position, from_tax, tax)
            invoice_lines.append(line)
        data = {"company": {"id": self.company}, "partner": partner,
                "napDocumentTypeSelect": "01", "operationTypeSelect": operation, "currency": eur, "invoiceDate": date,
                "dueDate": date, "paymentMode": self.mode(mode), "externalReference": ref_no,
                "paymentCondition": ref(ACC + "PaymentCondition", r, "self.code = 'NET30'"),
                "address": r.call("GET", f"/ws/rest/{BASE}Partner/{partner['id']}")["data"][0][
                    "mainAddress"],
                "invoiceLineList": invoice_lines}
        if operation == 1:
            data.update({"supplierInvoiceNb": ref_no, "originDate": date})
        if reverse_charge:
            data["fiscalPosition"] = ref(ACC + "FiscalPosition", r, "self.code = :fp",
                                         {"fp": reverse_charge[0]})
        data.update(extra or {})
        inv = r.create(ACC + "Invoice", data)
        return self.finish_invoice(inv["id"], ref_no)

    def finish_invoice(self, invoice_id, ref_no):
        """Довършва фактурата до осчетоводена (статус 3) — и такава, останала от прекъснат пробег."""
        r = self.r
        steps = {1: ("action-invoice-method-compute", "action-invoice-method-validate",
                     "action-invoice-method-ventilate"),
                 2: ("action-invoice-method-ventilate",)}
        status = r.call("GET", f"/ws/rest/{ACC}Invoice/{invoice_id}")["data"][0]["statusSelect"]
        messages = []
        for action in steps.get(status, ()):
            out = r.action(ACC + "Invoice", action, {"id": invoice_id})
            for item in out.get("data") or []:
                if isinstance(item, dict) and item.get("info"):
                    messages.append(item["info"].get("message"))
        status = r.call("GET", f"/ws/rest/{ACC}Invoice/{invoice_id}")["data"][0]["statusSelect"]
        if status != 3:
            raise SystemExit(f"фактура #{invoice_id} {ref_no}: не е осчетоводена (статус {status})"
                             f" — {messages}")
        print(f"фактура #{invoice_id} {ref_no} — осчетоводена")
        return invoice_id

    def move(self, ref_no, journal, date, lines, extra=None, lookup=None):
        """Ръчна статия: създаване и осчетоводяване („Account“ на формата)."""
        r = self.r
        found = r.search(ACC + "Move", "self.company.id = :c AND self.origin = :o AND"
                         " self.journal.code = :j", ("id",),
                         {"c": self.company, "o": lookup or ref_no, "j": journal}, 2)
        if found:
            return found[0]["id"]
        eur = ref(BASE + "Currency", r, "self.codeISO = 'EUR'")
        period = ref(BASE + "Period", r,
                     "self.year.company.id = :c AND self.fromDate <= :d AND self.toDate >= :d",
                     {"c": self.company, "d": date})
        move_lines = []
        for i, (account, debit, credit, partner, label) in enumerate(lines, start=1):
            line = {"account": self.acc(account), "debit": debit, "credit": credit,
                    "date": date, "name": label, "counter": i, "currencyRate": 1,
                    "currencyAmount": debit or credit}
            if partner:
                line["partner"] = partner
            move_lines.append(line)
        data = {"company": {"id": self.company}, "journal": self.journal(journal), "date": date,
                "period": period, "currency": eur, "companyCurrency": eur, "origin": ref_no,
                "originDate": date, "description": ref_no, "technicalOriginSelect": 1,
                "moveLineList": move_lines}
        data.update(extra or {})
        m = r.create(ACC + "Move", data)
        r.action(ACC + "Move", "action-move-method-accounting", {"id": m["id"]})
        print(f"статия #{m['id']} {ref_no} — осчетоводена")
        return m["id"]

    def protocol_117(self, invoice_id, partner, supplier_nb, date, base, tax):
        """Протоколът по чл. 117 за услугата от ЕС — РЪЧЕН, после свързан с фактурата.

        ⚠️ Бутонът „Протокол“ на фактурата (BgVatInvoiceProtocolService) не минава тук:
        статията е с функционален произход 3 „продажба“, който журналът VAT на BG_COA не пуска
        (5,6,7), а и за него Axelor иска данъчен ред на всеки ред по сметка от вид „данък“
        (MoveLineTaxServiceImpl.checkEmptyTaxLines) — 4531/4532 го нямат. Ръчният протокол е поддържаният път: същото дружество,
        контрагент, origin = номерът на фактурата на доставчика и операция SERVICE_EU — после
        бутонът само го свързва (manualProtocolFor)."""
        r = self.r
        self.move(supplier_nb + "-P117", "VAT", date,
                  [("4531", tax, 0, None, "ДДС по чл. 117 — услуга от ЕС"),
                   ("4532", 0, tax, None, "ДДС по чл. 117 — услуга от ЕС")],
                  {"partner": partner, "origin": supplier_nb, "originDate": date,
                   "bgVatMoveVatOperation": "SERVICE_EU", "napDocumentTypeSelect": "09",
                   "bgVatMoveVatRate": 0.20, "bgVatProtocolBase": base},
                  lookup=supplier_nb)
        found = r.search(ACC + "Move", "self.bgVatSourceInvoice.id = :i", ("id", "statusSelect"),
                         {"i": invoice_id}, 2)
        if not found:
            out = r.action(ACC + "Invoice", "action-bg-vat-invoice-protocol-generate",
                           {"id": invoice_id})
            found = r.search(ACC + "Move", "self.bgVatSourceInvoice.id = :i",
                             ("id", "statusSelect"), {"i": invoice_id}, 2)
            if not found:
                raise SystemExit(f"протокол по чл. 117 от фактура #{invoice_id}: не е свързан —"
                                 f" {out.get('data')}")
        print(f"протокол по чл. 117 от фактура #{invoice_id} — статия #{found[0]['id']}")

    # ── двата месеца ──

    def build(self):
        self.company_setup()
        a = self.args
        customer = self.partner("TR-C1", a.customer_name, "BG", a.customer_city, a.customer_zip,
                                a.customer_street, eik=a.customer_eik, customer=True)
        supplier = self.partner("TR-S1", a.supplier_name, "BG", a.supplier_city, a.supplier_zip,
                                a.supplier_street, eik=a.supplier_eik)
        eu = self.partner("TR-S2", "Société d'Essai SARL", "FR", "Paris", "75001",
                          "1 rue d'Essai", vat=fr_vat(self.rng))
        material = self.product("TR-MAT", "Материал за тест", "storable", "Брой")
        service = self.product("TR-SRV", "Консултантска услуга", "service", "Час")
        cloud = self.product("TR-EU", "Облачна услуга от ЕС", "service", "Час")

        # 09.2026
        self.move("TR-FEE-09", "MOM", "2026-09-05",
                  [("629", 5, 0, None, "Банкова такса"), ("503", 0, 5, None, "Банкова такса")])
        self.invoice("TR-INV-S1", 3, customer, "2026-09-10",
                     [(service, "Консултантска услуга", 10, 50, "BG_SRS_20", "702", None)],
                     "INC_WT")
        purchase = self.invoice("TR-INV-P1", 1, supplier, "2026-09-12",
                                [(material, "Материал за тест", 20, 10, "BG_SRP_20", "302", "1")],
                                "OUT_WT")
        self.stock(material, "Брой", 20, 10)
        self.move("TR-PAY-09", "BNK", "2026-09-20",
                  [("401", 240, 0, supplier, "Плащане по TR-INV-P1"),
                   ("503", 0, 240, None, "Плащане по TR-INV-P1")],
                  {"paymentMode": self.mode("OUT_WT"), "functionalOriginSelect": 5})
        service_eu = self.invoice("TR-INV-EU1", 1, eu, "2026-09-15",
                                  [(cloud, "Облачна услуга от ЕС", 1, 100, "BG_EU_S_RCM_P",
                                    "60201", "1")],
                                  "OUT_WT", reverse_charge=("EU_SERV_ACQ", "BG_SRP_20"))
        self.protocol_117(service_eu, eu, "TR-INV-EU1", "2026-09-15", 100, 20)
        self.move("TR-ART79-09", "VAT", "2026-09-25",
                  [("60909", 20, 0, None, "Корекция по чл. 79 ал. 1–3"),
                   ("4532", 0, 20, None, "Корекция по чл. 79 ал. 1–3")],
                  {"bgVatMoveVatOperation": "CORRECTION_79", "napDocumentTypeSelect": "09",
                   "bgVatMoveVatRate": 0.20, "bgVatProtocolBase": 100})
        # 10.2026 — само вътрешна статия
        self.move("TR-FEE-10", "MOM", "2026-10-05",
                  [("629", 5, 0, None, "Банкова такса"), ("503", 0, 5, None, "Банкова такса")])
        del purchase


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--user", default="admin")
    p.add_argument("--password", default="admin")
    p.add_argument("--eik", required=True, help="ЕИК на дружеството (девет цифри)")
    p.add_argument("--name", required=True)
    p.add_argument("--code", default="TRIAL")
    p.add_argument("--city", required=True)
    p.add_argument("--zip", required=True)
    p.add_argument("--street", required=True)
    p.add_argument("--phone", default="+35920000000")
    p.add_argument("--iban", required=True, help="IBAN на дружеството (S.BA.1)")
    p.add_argument("--bic", required=True, help="BIC на банката на IBAN-а (Bank в Axelor)")
    for role in ("customer", "supplier"):
        p.add_argument(f"--{role}-eik", required=True, help="реален ЕИК — НАП сверява с регистъра")
        p.add_argument(f"--{role}-name", required=True)
        p.add_argument(f"--{role}-city", required=True)
        p.add_argument(f"--{role}-zip", required=True)
        p.add_argument(f"--{role}-street", required=True)
    p.add_argument("--seed", type=int, default=20260915,
                   help="за повторимия измислен ДДС номер на доставчика от ЕС")
    args = p.parse_args(argv)
    for flag, eik in (("--eik", args.eik), ("--customer-eik", args.customer_eik),
                      ("--supplier-eik", args.supplier_eik)):
        if not eik_ok(eik):
            raise SystemExit(f"{flag}: девет цифри с вярна контролна цифра")
    Trial(Rest(args.base_url, args.user, args.password), args).build()


if __name__ == "__main__":
    sys.exit(main())
