#!/usr/bin/env python3
"""Regenerate DogRouter invoices from a backup, without the phone.

Reads a DogRouter ``backup.json`` (the one inside the URSSAF .zip, or a plain
backup export) and writes one print-ready HTML file per issued invoice, plus an
``index.html``. Each invoice is rebuilt from its frozen render snapshot
(``renderJson``) so it is identical to what the app produced; invoices issued
before snapshots existed are reconstructed from the linked services + issuer.

Open the HTML files in a browser and "Print → Save as PDF". If a Chrome/Chromium
(or Edge/Brave) browser is found, a .pdf is written alongside each HTML
automatically via headless printing.

Usage:
    python3 tools/regenerate_invoices.py path/to/backup.json [output_dir]

Stdlib only. Note: the data may contain real client PII — keep the output local.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import date


def euros(cents):
    sign = "-" if cents < 0 else ""
    a = abs(int(cents))
    return f"{sign}{a // 100},{a % 100:02d} €"


def fr_date(iso):
    if not iso:
        return ""
    try:
        d = date.fromisoformat(iso)
        return d.strftime("%d/%m/%Y")
    except ValueError:
        return iso


def esc(s):
    return (
        str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    )


def lines_html(lines):
    out = []
    for ln in lines:
        out.append(
            "<tr><td>{}</td><td>{}</td><td class='amt'>{}</td></tr>".format(
                esc(fr_date(ln.get("date", ""))),
                esc(ln.get("description", "")),
                esc(euros(ln.get("amountCents", 0))),
            )
        )
    return "\n".join(out)


def multiline(s):
    return "<br>".join(esc(part) for part in str(s).split("\n") if part.strip())


def render_html(r):
    issuer = r.get("issuer", {})
    is_credit = r.get("isCreditNote", False)
    acquitted = r.get("acquitted", False)
    title = (
        "FACTURE D'AVOIR" if is_credit else ("FACTURE ACQUITTÉE" if acquitted else "FACTURE")
    )
    watermark = (
        "<div class='watermark'>TEST</div>" if r.get("isTest", False) else ""
    )
    acquitted_line = ""
    if acquitted and r.get("acquittedDate"):
        acquitted_line = f"<p>Facture acquittée le {esc(fr_date(r['acquittedDate']))}.</p>"
    issuer_lines = []
    if issuer.get("address"):
        issuer_lines.append(multiline(issuer["address"]))
    if issuer.get("siret"):
        issuer_lines.append("SIRET : " + esc(issuer["siret"]))
    if issuer.get("email"):
        issuer_lines.append(esc(issuer["email"]))
    if issuer.get("phone"):
        issuer_lines.append(esc(issuer["phone"]))
    return f"""<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8">
<title>{esc(r.get('number',''))}</title>
<style>
@page {{ size: A4; margin: 20mm; }}
body {{ font-family: sans-serif; color: #000; font-size: 12px; position: relative; }}
.head {{ display: flex; justify-content: space-between; }}
.issuer .name {{ font-weight: bold; font-size: 14px; }}
.title {{ text-align: right; }}
.title h1 {{ font-size: 20px; margin: 0; }}
table {{ width: 100%; border-collapse: collapse; margin-top: 24px; }}
th, td {{ text-align: left; padding: 6px 4px; border-bottom: 1px solid #ccc; }}
.amt {{ text-align: right; }}
.total {{ text-align: right; font-weight: bold; font-size: 14px; margin-top: 12px; }}
.legal {{ position: fixed; bottom: 0; left: 0; right: 0; color: #555; font-size: 10px; }}
.watermark {{ position: fixed; top: 40%; left: 0; right: 0; text-align: center;
  font-size: 120px; color: rgba(200,0,0,0.15); transform: rotate(-35deg); font-weight: bold; }}
</style></head><body>
{watermark}
<div class="head">
  <div class="issuer">
    <div class="name">{esc(issuer.get('name','—'))}</div>
    <div>{'<br>'.join(issuer_lines)}</div>
  </div>
  <div class="title">
    <h1>{title}</h1>
    <div>N° {esc(r.get('number',''))}</div>
    <div>Date : {esc(fr_date(r.get('date','')))}</div>
  </div>
</div>
<p><strong>Facturé à :</strong><br>{esc(r.get('ownerName',''))}<br>{multiline(r.get('ownerAddress',''))}</p>
<table>
  <thead><tr><th>Date</th><th>Désignation</th><th class="amt">Montant</th></tr></thead>
  <tbody>
{lines_html(r.get('lines', []))}
  </tbody>
</table>
<div class="total">Total : {esc(euros(r.get('totalCents',0)))}</div>
{acquitted_line}
<div class="legal">{multiline(issuer.get('legalMentions',''))}</div>
</body></html>"""


def find_chrome():
    """Locate a Chromium-based browser for headless PDF printing, or None."""
    for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                 "brave-browser", "microsoft-edge", "chrome"):
        path = shutil.which(name)
        if path:
            return path
    for path in (
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    ):
        if os.path.exists(path):
            return path
    return None


def html_to_pdf(chrome, html_path, pdf_path, profile_dir):
    subprocess.run(
        [
            chrome, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
            f"--user-data-dir={profile_dir}",
            f"--print-to-pdf={pdf_path}",
            "file://" + os.path.abspath(html_path),
        ],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def reconstruct(invoice, services, owners_by_id, issuer):
    owner = owners_by_id.get(invoice.get("ownerId"))
    owner_name = ""
    owner_addr = ""
    if owner:
        owner_name = f"{owner.get('firstName','')} {owner.get('lastName','')}".strip()
        owner_addr = owner.get("billingAddress", "")
    lines = [
        {"date": s.get("date"), "description": s.get("description"), "amountCents": s.get("amountCents", 0)}
        for s in services
        if s.get("invoiceNumber") == invoice.get("number")
    ]
    return {
        "issuer": issuer,
        "ownerName": owner_name,
        "ownerAddress": owner_addr,
        "number": invoice.get("number"),
        "date": invoice.get("date"),
        "lines": lines,
        "totalCents": invoice.get("totalCents", 0),
        "acquitted": invoice.get("acquitted", False),
        "acquittedDate": invoice.get("acquittedDate"),
        "isTest": invoice.get("isTest", False),
        "isCreditNote": invoice.get("kind") == "AVOIR",
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    backup_path = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "invoices_out"
    with open(backup_path, encoding="utf-8") as f:
        backup = json.load(f)

    invoices = backup.get("invoices", [])
    services = backup.get("services", [])
    owners_by_id = {o["id"]: o for o in backup.get("owners", [])}
    issuer = backup.get("settings", {}).get("issuer", {})

    os.makedirs(out_dir, exist_ok=True)
    chrome = find_chrome()
    profile_dir = os.path.join(out_dir, ".chrome-profile")
    index = ["<!DOCTYPE html><meta charset='utf-8'><h1>Invoices</h1><ul>"]
    count = 0
    for inv in invoices:
        snapshot = inv.get("renderJson") or ""
        render = json.loads(snapshot) if snapshot.strip() else reconstruct(inv, services, owners_by_id, issuer)
        html = render_html(render)
        safe = re.sub(r"[^A-Za-z0-9_-]", "_", inv.get("number", "invoice"))
        html_path = os.path.join(out_dir, safe + ".html")
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html)
        index.append(f"<li><a href='{safe}.html'>{esc(inv.get('number',''))}</a></li>")
        count += 1
        if chrome:
            html_to_pdf(chrome, html_path, os.path.join(out_dir, safe + ".pdf"), profile_dir)

    index.append("</ul>")
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write("\n".join(index))

    print(f"Wrote {count} invoice(s) to {out_dir}/")
    if chrome:
        print(f"PDFs generated with {os.path.basename(chrome)}.")
    else:
        print("No Chrome/Chromium found — open index.html in a browser and "
              "Print → Save as PDF.")


if __name__ == "__main__":
    main()
