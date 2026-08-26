import math
from datetime import timedelta

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


def _recorded_at(value):
    """Return a naive datetime in the ERPNext site's configured timezone."""
    if not value:
        return frappe.utils.now_datetime()
    try:
        timestamp = frappe.utils.get_datetime(value)
    except (TypeError, ValueError):
        frappe.throw(_("Recorded At is not a valid date and time"))
    if timestamp.tzinfo is not None:
        timestamp = frappe.utils.convert_utc_to_system_timezone(timestamp).replace(tzinfo=None)
    return timestamp


def _require_location_read_permission():
    if not frappe.has_permission("GPS Location", ptype="read"):
        frappe.throw(_("You do not have permission to view GPS locations"), frappe.PermissionError)


@frappe.whitelist()
def route_devices():
    """Return device identifiers visible to GPS Location readers."""
    _require_location_read_permission()
    rows = frappe.get_all(
        "GPS Location",
        fields=["device_id"],
        group_by="device_id",
        order_by="device_id asc",
        limit_page_length=0,
    )
    return [row.device_id for row in rows if row.device_id]


@frappe.whitelist()
def daily_route(device_id, date):
    """Return one device's ordered location history for a site-local day."""
    _require_location_read_permission()
    device_id = str(device_id or "").strip()
    if not device_id:
        frappe.throw(_("Device ID is required"))
    try:
        start = frappe.utils.get_datetime(frappe.utils.getdate(date))
    except (TypeError, ValueError):
        frappe.throw(_("A valid date is required"))
    end = start + timedelta(days=1)

    return frappe.get_all(
        "GPS Location",
        filters=[
            ["device_id", "=", device_id],
            ["recorded_at", ">=", start],
            ["recorded_at", "<", end],
        ],
        fields=[
            "name",
            "device_id",
            "latitude",
            "longitude",
            "accuracy",
            "altitude",
            "speed",
            "bearing",
            "recorded_at",
        ],
        order_by="recorded_at asc",
        limit_page_length=10000,
    )


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

    report_id = str(data.get("report_id") or "").strip()
    if len(report_id) > 140:
        frappe.throw(_("Report ID is too long"))
    if report_id:
        existing = frappe.db.get_value(
            "GPS Location",
            {"report_id": report_id},
            ["name", "device_id", "latitude", "longitude", "recorded_at"],
            as_dict=True,
        )
        if existing:
            return existing

    latitude = _number(data.get("latitude"), _("Latitude"), required=True)
    longitude = _number(data.get("longitude"), _("Longitude"), required=True)
    if not -90 <= latitude <= 90:
        frappe.throw(_("Latitude must be between -90 and 90"))
    if not -180 <= longitude <= 180:
        frappe.throw(_("Longitude must be between -180 and 180"))

    recorded_at = _recorded_at(data.get("recorded_at"))

    doc = frappe.get_doc(
        {
            "doctype": "GPS Location",
            "report_id": report_id or None,
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
