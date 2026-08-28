import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields


CUSTOM_FIELDS = {
    "Delivery Trip": [
        {"fieldname": "gps_tracking_section", "label": "Bluecore GPS Tracking", "fieldtype": "Section Break", "insert_after": "status"},
        {"fieldname": "gps_device_id", "label": "GPS Device ID", "fieldtype": "Data", "insert_after": "gps_tracking_section", "in_list_view": 1},
        {"fieldname": "starting_warehouse", "label": "Starting Warehouse", "fieldtype": "Link", "options": "Warehouse", "fetch_from": "company.gps_default_delivery_warehouse", "insert_after": "gps_device_id"},
        {"fieldname": "starting_warehouse_address", "label": "Starting Warehouse Address", "fieldtype": "Small Text", "fetch_from": "company.gps_delivery_warehouse_address", "read_only": 1, "insert_after": "starting_warehouse"},
        {"fieldname": "gps_proposed_route", "label": "Proposed Route (Start to Finish)", "fieldtype": "Small Text", "read_only": 1, "insert_after": "starting_warehouse_address"},
        {"fieldname": "gps_tracking_status", "label": "GPS Tracking Status", "fieldtype": "Select", "options": "Not Started\nDelivery In Progress\nDelivery Completed\nReturning to Warehouse\nReturned to Warehouse", "default": "Not Started", "insert_after": "gps_proposed_route"},
        {"fieldname": "gps_times_column", "fieldtype": "Column Break", "insert_after": "gps_tracking_status"},
        {"fieldname": "gps_trip_started_at", "label": "Trip Started At", "fieldtype": "Datetime", "insert_after": "gps_times_column"},
        {"fieldname": "gps_trip_completed_at", "label": "Last Delivery Completed At", "fieldtype": "Datetime", "insert_after": "gps_trip_started_at"},
        {"fieldname": "gps_return_started_at", "label": "Return Started At", "fieldtype": "Datetime", "insert_after": "gps_trip_completed_at"},
        {"fieldname": "gps_returned_at", "label": "Returned to Warehouse At", "fieldtype": "Datetime", "insert_after": "gps_return_started_at"},
        {"fieldname": "gps_public_route_section", "label": "24-Hour Public Route", "fieldtype": "Section Break", "insert_after": "gps_returned_at"},
        {"fieldname": "gps_public_route_token", "label": "Public Route Token", "fieldtype": "Data", "unique": 1, "hidden": 1, "no_copy": 1, "insert_after": "gps_public_route_section"},
        {"fieldname": "gps_public_route_expires_at", "label": "Public Route Expires At", "fieldtype": "Datetime", "read_only": 1, "insert_after": "gps_public_route_token"},
        {"fieldname": "gps_public_route_enabled", "label": "Public Route Enabled", "fieldtype": "Check", "read_only": 1, "default": "0", "insert_after": "gps_public_route_expires_at"},
    ],
    "Delivery Stop": [
        {"fieldname": "gps_delivery_section", "label": "Delivery Tracking", "fieldtype": "Section Break", "insert_after": "details"},
        {"fieldname": "gps_delivery_status", "label": "Delivery Status", "fieldtype": "Select", "options": "Pending\nArrived\nDelivery Started\nCompleted\nFailed\nSkipped", "default": "Pending", "insert_after": "gps_delivery_section"},
        {"fieldname": "gps_delivery_window_start", "label": "Delivery Window Start", "fieldtype": "Time", "insert_after": "gps_delivery_status", "fetch_from": "address.gps_delivery_window_start", "fetch_if_empty": 1, "description": "Fetched from the Customer Address; may be adjusted for this trip."},
        {"fieldname": "gps_delivery_window_end", "label": "Delivery Window End", "fieldtype": "Time", "insert_after": "gps_delivery_window_start", "fetch_from": "address.gps_delivery_window_end", "fetch_if_empty": 1, "description": "Fetched from the Customer Address; may be adjusted for this trip."},
        {"fieldname": "gps_arrived_at", "label": "Arrived At", "fieldtype": "Datetime", "insert_after": "gps_delivery_window_end"},
        {"fieldname": "gps_delivery_started_at", "label": "Delivery Started At", "fieldtype": "Datetime", "insert_after": "gps_arrived_at"},
        {"fieldname": "gps_delivery_completed_at", "label": "Delivery Completed At", "fieldtype": "Datetime", "insert_after": "gps_delivery_started_at"},
        {"fieldname": "gps_event_column", "fieldtype": "Column Break", "insert_after": "gps_delivery_completed_at"},
        {"fieldname": "gps_start_latitude", "label": "Start Latitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_event_column"},
        {"fieldname": "gps_start_longitude", "label": "Start Longitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_start_latitude"},
        {"fieldname": "gps_completion_latitude", "label": "Completion Latitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_start_longitude"},
        {"fieldname": "gps_completion_longitude", "label": "Completion Longitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_completion_latitude"},
        {"fieldname": "gps_delivery_remarks", "label": "Delivery Remarks", "fieldtype": "Small Text", "insert_after": "gps_completion_longitude"},
        {"fieldname": "gps_public_tracking_token", "label": "Public Tracking Token", "fieldtype": "Data", "unique": 1, "hidden": 1, "no_copy": 1, "insert_after": "gps_delivery_remarks"},
        {"fieldname": "gps_public_tracking_enabled", "label": "Enable Customer Tracking", "fieldtype": "Check", "default": "0", "insert_after": "gps_public_tracking_token"},
    ],
    "Warehouse": [
        {"fieldname": "gps_coordinates_section", "label": "GPS Coordinates", "fieldtype": "Section Break", "insert_after": "disabled"},
        {"fieldname": "gps_latitude", "label": "Latitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_coordinates_section"},
        {"fieldname": "gps_longitude", "label": "Longitude", "fieldtype": "Float", "precision": "7", "insert_after": "gps_latitude"},
    ],
    "Address": [
        {"fieldname": "gps_delivery_window_section", "label": "Delivery Time Slot", "fieldtype": "Section Break", "insert_after": "is_your_company_address", "description": "Default receiving time window used when this address is added to a Delivery Trip."},
        {"fieldname": "gps_delivery_window_start", "label": "Delivery Window Start", "fieldtype": "Time", "insert_after": "gps_delivery_window_section"},
        {"fieldname": "gps_delivery_window_end", "label": "Delivery Window End", "fieldtype": "Time", "insert_after": "gps_delivery_window_start"},
    ],
    "Driver": [
        {"fieldname": "gps_user", "label": "GPS Mobile User", "fieldtype": "Link", "options": "User", "unique": 1, "insert_after": "employee", "description": "ERPNext user account used by this driver in the Bluecore GPS app."},
    ],
    "Company": [
        {"fieldname": "gps_delivery_defaults_section", "label": "Delivery GPS Defaults", "fieldtype": "Section Break", "insert_after": "default_holiday_list"},
        {"fieldname": "gps_default_delivery_warehouse", "label": "Default Delivery Warehouse", "fieldtype": "Link", "options": "Warehouse", "insert_after": "gps_delivery_defaults_section", "description": "Default starting warehouse for Delivery Trips."},
        {"fieldname": "gps_delivery_warehouse_address", "label": "Warehouse Address", "fieldtype": "Small Text", "read_only": 1, "insert_after": "gps_default_delivery_warehouse"},
    ],
    "GPS Location": [
        {"fieldname": "delivery_trip", "label": "Delivery Trip", "fieldtype": "Link", "options": "Delivery Trip", "insert_after": "device_id", "in_list_view": 1},
        {"fieldname": "route_phase", "label": "Route Phase", "fieldtype": "Select", "options": "Delivery\nReturn", "insert_after": "delivery_trip"},
    ],
}


def after_install():
    create_custom_fields(CUSTOM_FIELDS, update=True)


def after_migrate():
    create_custom_fields(CUSTOM_FIELDS, update=True)
    from gps_tracker.events import _warehouse_address
    for company in frappe.get_all("Company", fields=["name", "gps_default_delivery_warehouse"]):
        if company.gps_default_delivery_warehouse:
            frappe.db.set_value("Company", company.name, "gps_delivery_warehouse_address",
                _warehouse_address(company.gps_default_delivery_warehouse), update_modified=False)
