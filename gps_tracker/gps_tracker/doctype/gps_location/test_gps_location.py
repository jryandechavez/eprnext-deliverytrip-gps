import frappe
from frappe.tests.utils import FrappeTestCase

from gps_tracker.api import _recorded_at


class TestGPSLocation(FrappeTestCase):
    def test_android_offset_timestamp_becomes_database_safe(self):
        timestamp = _recorded_at("2026-08-26T13:19:00+08:00")

        self.assertIsNone(timestamp.tzinfo)
        self.assertEqual(
            timestamp,
            frappe.utils.convert_utc_to_system_timezone(
                frappe.utils.get_datetime("2026-08-26T13:19:00+08:00")
            ).replace(tzinfo=None),
        )
