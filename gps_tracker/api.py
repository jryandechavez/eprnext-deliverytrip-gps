import math

import frappe
from frappe import _


def _number(value, label, required=False):
    if value in (None, ""):
        if required:
            frappe.throw(_("{0} is required").format(label))
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        frappe.throw(_("{0} must be a number").format(label))
    if not math.isfinite(number):
        frappe.throw(_("{0} must be finite").format(label))
    return number


def _request_data():
    data = dict(frappe.form_dict or {})
    if getattr(frappe, "request", None) and frappe.request.is_json:
        json_data = frappe.request.get_json(silent=True)
        if isinstance(json_data, dict):
            data.update(json_data)
    data.pop("cmd", None)
    return data


@frappe.whitelist()
def location(**kwargs):
    """Store one authenticated location report from a Bluecore GPS device."""
    data = _request_data()
    data.update(kwargs)

    device_id = str(data.get("device_id") or "").strip()
    if not device_id:
        frappe.throw(_("Device ID is required"))
    if len(device_id) > 140:
        frappe.throw(_("Device ID is too long"))

    latitude = _number(data.get("latitude"), _("Latitude"), required=True)
    longitude = _number(data.get("longitude"), _("Longitude"), required=True)
    if not -90 <= latitude <= 90:
        frappe.throw(_("Latitude must be between -90 and 90"))
    if not -180 <= longitude <= 180:
        frappe.throw(_("Longitude must be between -180 and 180"))

    recorded_at = data.get("recorded_at") or frappe.utils.now()
    try:
        recorded_at = frappe.utils.get_datetime(recorded_at)
    except (TypeError, ValueError):
        frappe.throw(_("Recorded At is not a valid date and time"))

    doc = frappe.get_doc(
        {
            "doctype": "GPS Location",
            "device_id": device_id,
            "latitude": latitude,
            "longitude": longitude,
            "accuracy": _number(data.get("accuracy"), _("Accuracy")),
            "altitude": _number(data.get("altitude"), _("Altitude")),
            "speed": _number(data.get("speed"), _("Speed")),
            "bearing": _number(data.get("bearing"), _("Bearing")),
            "recorded_at": recorded_at,
        }
    )
    # The API method already requires an authenticated ERPNext user. This lets
    # a restricted integration user submit telemetry without Desk access.
    doc.insert(ignore_permissions=True)
    frappe.db.commit()

    return {
        "name": doc.name,
        "device_id": doc.device_id,
        "latitude": doc.latitude,
        "longitude": doc.longitude,
        "recorded_at": doc.recorded_at,
    }
