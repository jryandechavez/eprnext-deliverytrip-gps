import frappe


def _warehouse_address(warehouse):
    if not warehouse:
        return ""
    values = frappe.db.get_value(
        "Warehouse", warehouse,
        ["address_line_1", "address_line_2", "city", "state", "pin"], as_dict=True,
    ) or {}
    return ", ".join(str(values.get(field)).strip() for field in
        ("address_line_1", "address_line_2", "city", "state", "pin") if values.get(field))


def set_company_warehouse_address(doc, method=None):
    doc.gps_delivery_warehouse_address = _warehouse_address(doc.get("gps_default_delivery_warehouse"))


def set_delivery_trip_start(doc, method=None):
    if not doc.get("starting_warehouse") and doc.company:
        doc.starting_warehouse = frappe.db.get_value("Company", doc.company, "gps_default_delivery_warehouse")
    doc.starting_warehouse_address = _warehouse_address(doc.get("starting_warehouse"))
    route = [doc.get("starting_warehouse") or "Starting Warehouse"]
    for stop in doc.get("delivery_stops") or []:
        if stop.address and (not stop.get("gps_delivery_window_start") or not stop.get("gps_delivery_window_end")):
            window = frappe.db.get_value(
                "Address", stop.address,
                ["gps_delivery_window_start", "gps_delivery_window_end"], as_dict=True,
            ) or {}
            if not stop.get("gps_delivery_window_start"):
                stop.gps_delivery_window_start = window.get("gps_delivery_window_start")
            if not stop.get("gps_delivery_window_end"):
                stop.gps_delivery_window_end = window.get("gps_delivery_window_end")
        label = stop.customer or stop.address or "Delivery Stop"
        if stop.delivery_note:
            label += f" ({stop.delivery_note})"
        route.append(label)
    doc.gps_proposed_route = " → ".join(route)
