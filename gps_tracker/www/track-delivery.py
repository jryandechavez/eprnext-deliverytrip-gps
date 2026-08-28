import frappe
from urllib.parse import quote


def get_context(context):
    if frappe.session.user == "Guest":
        target = frappe.request.full_path if getattr(frappe, "request", None) else "/track-delivery"
        frappe.local.flags.redirect_location = "/login?redirect-to=" + quote(target, safe="")
        raise frappe.Redirect
    context.no_cache = 1
    context.title = "Track Your Delivery"
    context.csrf_token = frappe.sessions.get_csrf_token()
    return context
