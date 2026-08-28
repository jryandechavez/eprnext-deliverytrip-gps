import math
import secrets
import time
import requests
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


def _trip_permission(name, ptype="read"):
    if not frappe.has_permission("Delivery Trip", ptype=ptype, doc=name):
        frappe.throw(_("You do not have permission for Delivery Trip {0}").format(name), frappe.PermissionError)


def _mobile_driver(driver=None):
    """Resolve and validate the Driver linked to the authenticated ERPNext user."""
    linked = frappe.db.get_value("Driver", {"gps_user": frappe.session.user}, "name")
    requested = str(driver or "").strip()
    if linked and requested and linked != requested and "System Manager" not in frappe.get_roles():
        frappe.throw(_("The selected Driver is not linked to your user account"), frappe.PermissionError)
    return linked or requested


@frappe.whitelist()
def available_delivery_trips(device_id=None, driver=None, search=None):
    """Trips a driver can select or resolve after scanning a QR code."""
    filters = {"docstatus": ["<", 2]}
    if device_id:
        filters["gps_device_id"] = ["in", ["", device_id]]
    driver = _mobile_driver(driver)
    if driver:
        filters["driver"] = driver
    or_filters = None
    if search:
        token = str(search).replace("BLUECORE-TRIP:", "").strip()
        or_filters = {"name": ["like", f"%{token}%"], "driver_name": ["like", f"%{token}%"]}
    getter = frappe.get_all if driver else frappe.get_list
    return getter(
        "Delivery Trip", filters=filters, or_filters=or_filters,
        fields=["name", "company", "driver", "driver_name", "vehicle", "departure_time", "status", "gps_device_id", "gps_tracking_status"],
        order_by="departure_time desc", limit_page_length=50,
    )


@frappe.whitelist()
def delivery_trip_route(delivery_trip, driver=None, device_id=None):
    driver = _mobile_driver(driver)
    if not driver:
        _trip_permission(delivery_trip)
    trip = frappe.get_doc("Delivery Trip", delivery_trip)
    if driver and trip.driver != driver:
        frappe.throw(_("This Delivery Trip is assigned to another driver"), frappe.PermissionError)
    if device_id and trip.get("gps_device_id") and trip.gps_device_id != str(device_id).strip():
        frappe.throw(_("This Delivery Trip is assigned to another device"), frappe.PermissionError)
    warehouse = None
    if trip.get("starting_warehouse"):
        warehouse = frappe.db.get_value(
            "Warehouse", trip.starting_warehouse,
            ["name", "warehouse_name", "address_line_1", "address_line_2", "city", "state", "pin", "gps_latitude", "gps_longitude"], as_dict=True,
        )
        if warehouse:
            warehouse["latitude"] = warehouse.get("gps_latitude")
            warehouse["longitude"] = warehouse.get("gps_longitude")
    stops = []
    for row in trip.delivery_stops:
        stops.append({
            "name": row.name, "idx": row.idx, "customer": row.customer,
            "address": row.address, "customer_address": row.customer_address,
            "delivery_note": row.delivery_note, "latitude": row.lat, "longitude": row.lng,
            "estimated_arrival": row.estimated_arrival, "visited": row.visited,
            "delivery_window_start": row.get("gps_delivery_window_start"),
            "delivery_window_end": row.get("gps_delivery_window_end"),
            "delivery_status": row.get("gps_delivery_status") or "Pending",
            "arrived_at": row.get("gps_arrived_at"), "delivery_started_at": row.get("gps_delivery_started_at"),
            "delivery_completed_at": row.get("gps_delivery_completed_at"),
        })
    locations = frappe.get_all(
        "GPS Location", filters={"delivery_trip": delivery_trip},
        fields=["name", "device_id", "latitude", "longitude", "accuracy", "speed", "bearing", "recorded_at", "route_phase"],
        order_by="recorded_at asc", limit_page_length=20000,
    )
    events = frappe.get_all(
        "GPS Delivery Event", filters={"delivery_trip": delivery_trip},
        fields=["name", "delivery_stop", "delivery_note", "customer", "device_id", "event_type", "recorded_at", "latitude", "longitude", "accuracy", "remarks"],
        order_by="recorded_at asc", limit_page_length=1000,
    )
    return {
        "trip": {"name": trip.name, "company": trip.company, "driver": trip.driver, "driver_name": trip.driver_name,
                 "vehicle": trip.vehicle, "departure_time": trip.departure_time, "status": trip.status,
                 "device_id": trip.get("gps_device_id"), "tracking_status": trip.get("gps_tracking_status"),
                 "starting_warehouse": trip.get("starting_warehouse")},
        "warehouse": warehouse, "stops": stops, "locations": locations, "events": events,
    }


def _geocode_text(text):
    query = frappe.utils.strip_html(text or "").replace("\n", ", ").strip(" ,")
    if not query:
        return None
    response = requests.get("https://nominatim.openstreetmap.org/search", params={"q": query, "format": "jsonv2", "limit": 1, "countrycodes": "ph"}, headers={"User-Agent": "Bluecore-ERPNext-Delivery-GPS/1.0"}, timeout=20)
    response.raise_for_status()
    rows = response.json()
    if rows:
        return (float(rows[0]["lat"]), float(rows[0]["lon"]))
    upper = query.upper()
    landmark_points = {
        "EASTWOOD": (14.6110189, 121.0798088),
        "SHANGRI": (14.5814308, 121.0551446),
        "ARCOVIA": (14.5773636, 121.0757816),
        "ESTANCIA": (14.5754230, 121.0630400),
        "OPUS": (14.5929242, 121.0810085),
    }
    return next((point for landmark, point in landmark_points.items() if landmark in upper), None)


@frappe.whitelist()
def populate_delivery_trip_coordinates(delivery_trip):
    _trip_permission(delivery_trip, "write")
    trip = frappe.get_doc("Delivery Trip", delivery_trip)
    updated, unresolved = [], []
    warehouse = frappe.get_doc("Warehouse", trip.starting_warehouse) if trip.get("starting_warehouse") else None
    if not warehouse:
        unresolved.append(_("Starting Warehouse is not configured"))
    elif not warehouse.get("gps_latitude") or not warehouse.get("gps_longitude"):
        address = ", ".join(filter(None, [warehouse.address_line_1, warehouse.address_line_2, warehouse.city, warehouse.state, warehouse.pin, "Philippines"]))
        point = _geocode_text(address)
        if point:
            warehouse.db_set({"gps_latitude": point[0], "gps_longitude": point[1]}, update_modified=False); updated.append(warehouse.name)
        else: unresolved.append(warehouse.name)
        time.sleep(1.05)
    for stop in trip.delivery_stops:
        if stop.lat and stop.lng: continue
        point = _geocode_text(stop.customer_address)
        if point:
            frappe.db.set_value("Delivery Stop", stop.name, {"lat": point[0], "lng": point[1]}, update_modified=False); updated.append(stop.delivery_note or stop.customer or stop.name)
        else: unresolved.append(stop.delivery_note or stop.customer or stop.name)
        time.sleep(1.05)
    frappe.db.commit()
    return {"updated": updated, "unresolved": unresolved}


EVENT_RULES = {
    "Trip Started": ("gps_tracking_status", "Delivery In Progress", "gps_trip_started_at"),
    "Trip Completed": ("gps_tracking_status", "Delivery Completed", "gps_trip_completed_at"),
    "Return Started": ("gps_tracking_status", "Returning to Warehouse", "gps_return_started_at"),
    "Returned to Warehouse": ("gps_tracking_status", "Returned to Warehouse", "gps_returned_at"),
}

STOP_RULES = {
    "Arrived": ("Arrived", "gps_arrived_at"),
    "Delivery Started": ("Delivery Started", "gps_delivery_started_at"),
    "Delivery Completed": ("Completed", "gps_delivery_completed_at"),
    "Delivery Failed": ("Failed", None),
    "Delivery Skipped": ("Skipped", None),
}


@frappe.whitelist()
def delivery_event(**kwargs):
    """Record an idempotent mobile event and update its Delivery Trip/stop."""
    data = _request_data(); data.update(kwargs)
    trip_name = str(data.get("delivery_trip") or "").strip()
    event_type = str(data.get("event_type") or "").strip()
    device_id = str(data.get("device_id") or "").strip()
    event_id = str(data.get("event_id") or "").strip()
    if not trip_name or not event_type or not device_id:
        frappe.throw(_("Delivery Trip, Event Type, and Device ID are required"))
    if event_type not in set(EVENT_RULES) | set(STOP_RULES):
        frappe.throw(_("Unsupported event type"))
    existing = event_id and frappe.db.get_value("GPS Delivery Event", {"event_id": event_id}, "name")
    if existing:
        return frappe.get_doc("GPS Delivery Event", existing).as_dict()
    trip = frappe.get_doc("Delivery Trip", trip_name)
    linked_driver = _mobile_driver(data.get("driver"))
    if linked_driver:
        if trip.driver != linked_driver:
            frappe.throw(_("This Delivery Trip is assigned to another driver"), frappe.PermissionError)
    else:
        _trip_permission(trip_name, "write")
    if trip.get("gps_device_id") and trip.gps_device_id != device_id:
        frappe.throw(_("This Delivery Trip is assigned to another device"))
    if not trip.get("gps_device_id"):
        trip.db_set("gps_device_id", device_id, update_modified=False)
    latitude = _number(data.get("latitude"), _("Latitude"))
    longitude = _number(data.get("longitude"), _("Longitude"))
    if (latitude is None) != (longitude is None):
        frappe.throw(_("Latitude and Longitude must be provided together"))
    timestamp = _recorded_at(data.get("recorded_at"))
    stop = None
    stop_name = str(data.get("delivery_stop") or "").strip()
    if event_type in STOP_RULES:
        stop = next((row for row in trip.delivery_stops if row.name == stop_name), None)
        if not stop:
            frappe.throw(_("A valid Delivery Stop row is required"))
        status, time_field = STOP_RULES[event_type]
        frappe.db.set_value("Delivery Stop", stop.name, "gps_delivery_status", status, update_modified=False)
        if time_field:
            frappe.db.set_value("Delivery Stop", stop.name, time_field, timestamp, update_modified=False)
        if event_type == "Delivery Started" and latitude is not None:
            frappe.db.set_value("Delivery Stop", stop.name, {"gps_start_latitude": latitude, "gps_start_longitude": longitude}, update_modified=False)
        elif event_type == "Delivery Completed":
            values = {"visited": 1}
            if latitude is not None:
                values.update({"gps_completion_latitude": latitude, "gps_completion_longitude": longitude})
            frappe.db.set_value("Delivery Stop", stop.name, values, update_modified=False)
    if event_type in EVENT_RULES:
        status_field, status, time_field = EVENT_RULES[event_type]
        frappe.db.set_value("Delivery Trip", trip.name, {status_field: status, time_field: timestamp}, update_modified=False)
    doc = frappe.get_doc({
        "doctype": "GPS Delivery Event", "event_id": event_id or frappe.generate_hash(length=20),
        "delivery_trip": trip.name, "delivery_stop": stop.name if stop else None,
        "delivery_note": stop.delivery_note if stop else None, "customer": stop.customer if stop else None,
        "device_id": device_id, "event_type": event_type, "recorded_at": timestamp,
        "latitude": latitude, "longitude": longitude, "accuracy": _number(data.get("accuracy"), _("Accuracy")),
        "remarks": data.get("remarks"),
    }).insert(ignore_permissions=True)
    frappe.db.commit()
    return doc.as_dict()


@frappe.whitelist()
def issue_public_tracking_link(delivery_trip, delivery_stop):
    """Create/rotate a recipient-specific tracking link for one Delivery Note."""
    _trip_permission(delivery_trip, "write")
    trip = frappe.get_doc("Delivery Trip", delivery_trip)
    stop = next((row for row in trip.delivery_stops if row.name == delivery_stop), None)
    if not stop:
        frappe.throw(_("Delivery Stop does not belong to this trip"))
    token = secrets.token_urlsafe(32)
    frappe.db.set_value("Delivery Stop", stop.name, {
        "gps_public_tracking_token": token, "gps_public_tracking_enabled": 1,
    }, update_modified=False)
    frappe.db.commit()
    return frappe.utils.get_url(f"/track-delivery?token={token}")


@frappe.whitelist()
def issue_public_trip_route_link(delivery_trip):
    """Create an independent route link without invalidating earlier links."""
    _trip_permission(delivery_trip, "write")
    token = secrets.token_urlsafe(32)
    expires = frappe.utils.add_to_date(frappe.utils.now_datetime(), hours=24, as_datetime=True)
    link = frappe.get_doc({
        "doctype": "GPS Public Route Link", "delivery_trip": delivery_trip,
        "token": token, "expires_at": expires, "enabled": 1,
    }).insert(ignore_permissions=True)
    frappe.db.commit()
    return {"name": link.name, "url": frappe.utils.get_url(f"/delivery-trip-public?token={token}"), "expires_at": expires}


@frappe.whitelist()
def revoke_public_trip_route_link(delivery_trip):
    _trip_permission(delivery_trip, "write")
    for name in frappe.get_all("GPS Public Route Link", filters={"delivery_trip": delivery_trip, "enabled": 1}, pluck="name"):
        frappe.db.set_value("GPS Public Route Link", name, "enabled", 0, update_modified=False)
    frappe.db.set_value("Delivery Trip", delivery_trip, "gps_public_route_enabled", 0, update_modified=False)
    frappe.db.commit()


@frappe.whitelist()
def email_public_trip_route_link(delivery_trip, recipients):
    _trip_permission(delivery_trip, "write")
    recipients = [item.strip() for item in str(recipients or "").replace(";", ",").split(",") if item.strip()]
    if not recipients:
        frappe.throw(_("At least one recipient email is required"))
    for email in recipients:
        if not frappe.utils.validate_email_address(email):
            frappe.throw(_("Invalid email address: {0}").format(email))
    result = issue_public_trip_route_link(delivery_trip)
    frappe.sendmail(
        recipients=recipients,
        subject=_("Live Delivery Route for {0}").format(delivery_trip),
        message=_("A live delivery route has been shared with you.<br><br><a href='{0}'>View Live Delivery Route</a><br><br>This secure link expires at {1}.").format(result["url"], result["expires_at"]),
        now=True,
    )
    return {"recipients": recipients, **result}


@frappe.whitelist(allow_guest=True)
def public_trip_route_status(token):
    token = str(token or "").strip()
    if len(token) < 32:
        frappe.throw(_("Invalid route link"), frappe.PermissionError)
    link = frappe.db.get_value("GPS Public Route Link", {"token": token, "enabled": 1},
                               ["delivery_trip", "expires_at"], as_dict=True)
    if link:
        trip_name, expires_at = link.delivery_trip, link.expires_at
    else:
        legacy = frappe.db.get_value("Delivery Trip", {"gps_public_route_token": token, "gps_public_route_enabled": 1},
                                     ["name", "gps_public_route_expires_at"], as_dict=True)
        trip_name = legacy.name if legacy else None
        expires_at = legacy.gps_public_route_expires_at if legacy else None
    if not trip_name:
        frappe.throw(_("This route link is invalid or disabled"), frappe.PermissionError)
    trip = frappe.get_doc("Delivery Trip", trip_name)
    if not expires_at or frappe.utils.get_datetime(expires_at) <= frappe.utils.now_datetime():
        return {"ended": True, "message": _("This live delivery has ended."), "expired_at": expires_at}
    warehouse = None
    if trip.starting_warehouse:
        warehouse = frappe.db.get_value("Warehouse", trip.starting_warehouse, ["warehouse_name", "gps_latitude", "gps_longitude"], as_dict=True)
        if warehouse:
            warehouse.latitude, warehouse.longitude = warehouse.gps_latitude, warehouse.gps_longitude
    note_names = [row.delivery_note for row in trip.delivery_stops if row.delivery_note]
    note_details = {row.name: row for row in frappe.get_all(
        "Delivery Note", filters={"name": ["in", note_names]},
        fields=["name", "customer", "customer_name", "address_display"], limit_page_length=0,
    )} if note_names else {}
    stops = []
    for row in trip.delivery_stops:
        note = note_details.get(row.delivery_note, {})
        stops.append({"number": row.idx, "delivery_note": row.delivery_note,
                      "customer": row.customer or note.get("customer_name") or note.get("customer"),
                      "address": row.customer_address or row.address or note.get("address_display"),
                      "latitude": row.lat, "longitude": row.lng,
                      "delivery_window_start": row.get("gps_delivery_window_start"),
                      "delivery_window_end": row.get("gps_delivery_window_end"),
                      "status": row.get("gps_delivery_status") or "Pending",
                      "completed_at": row.get("gps_delivery_completed_at")})
    locations = frappe.get_all("GPS Location", filters={"delivery_trip": trip.name},
        fields=["latitude", "longitude", "recorded_at", "route_phase"], order_by="recorded_at asc", limit_page_length=20000)
    return {"company": trip.company, "driver": trip.driver_name or trip.driver, "vehicle": trip.vehicle,
            "status": trip.gps_tracking_status, "expires_at": expires_at,
            "warehouse": warehouse, "stops": stops, "locations": locations}


def _customer_can_track(customer, trip_name):
    if frappe.session.user == "Guest":
        return False
    if "System Manager" in frappe.get_roles() or frappe.has_permission("Delivery Trip", doc=trip_name):
        return True
    return bool(frappe.db.sql("""
        select 1
          from `tabDynamic Link` dl
          join `tabContact` c on c.name = dl.parent and dl.parenttype = 'Contact'
         where dl.link_doctype = 'Customer' and dl.link_name = %s
           and (c.user = %s or lower(c.email_id) = lower(%s))
         limit 1
    """, (customer, frappe.session.user, frappe.session.user)))


@frappe.whitelist()
def public_delivery_status(token):
    """Recipient-safe portal payload; requires a Contact linked to the Customer."""
    token = str(token or "").strip()
    if len(token) < 32:
        frappe.throw(_("Invalid tracking link"), frappe.PermissionError)
    stop_name = frappe.db.get_value("Delivery Stop", {
        "gps_public_tracking_token": token, "gps_public_tracking_enabled": 1,
    }, "name")
    if not stop_name:
        frappe.throw(_("This tracking link is invalid or no longer active"), frappe.PermissionError)
    stop = frappe.db.get_value("Delivery Stop", stop_name, [
        "parent", "customer", "delivery_note", "lat", "lng", "estimated_arrival",
        "gps_delivery_status", "gps_arrived_at", "gps_delivery_started_at", "gps_delivery_completed_at",
    ], as_dict=True)
    if not _customer_can_track(stop.customer, stop.parent):
        frappe.throw(_("You are not authorized to track this delivery"), frappe.PermissionError)
    trip = frappe.db.get_value("Delivery Trip", stop.parent, [
        "company", "driver_name", "vehicle", "gps_device_id", "gps_tracking_status",
    ], as_dict=True)
    latest = None
    if trip and trip.gps_device_id:
        latest = frappe.get_all("GPS Location", filters={"delivery_trip": stop.parent, "device_id": trip.gps_device_id},
            fields=["latitude", "longitude", "accuracy", "recorded_at", "route_phase"], order_by="recorded_at desc", limit=1)
        latest = latest[0] if latest else None
    return {
        "company": trip.company, "delivery_note": stop.delivery_note,
        "status": stop.gps_delivery_status or "Pending", "trip_status": trip.gps_tracking_status,
        "estimated_arrival": stop.estimated_arrival, "destination": {"latitude": stop.lat, "longitude": stop.lng},
        "vehicle": {"latitude": latest.latitude, "longitude": latest.longitude, "accuracy": latest.accuracy,
                    "recorded_at": latest.recorded_at} if latest else None,
        "arrived_at": stop.gps_arrived_at, "delivery_started_at": stop.gps_delivery_started_at,
        "delivery_completed_at": stop.gps_delivery_completed_at,
    }


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

    delivery_trip = str(data.get("delivery_trip") or "").strip()
    linked_driver = _mobile_driver()
    if delivery_trip and linked_driver:
        assigned_driver = frappe.db.get_value("Delivery Trip", delivery_trip, "driver")
        if assigned_driver != linked_driver:
            frappe.throw(_("This Delivery Trip is assigned to another driver"), frappe.PermissionError)

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
            "delivery_trip": delivery_trip or None,
            "route_phase": str(data.get("route_phase") or "").strip() or None,
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
