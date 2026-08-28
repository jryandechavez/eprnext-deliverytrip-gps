app_name = "gps_tracker"
app_title = "GPS Tracker"
app_publisher = "BlueCore Solutions Corp."
app_description = "Receives and stores locations from Bluecore GPS Android devices"
app_email = ""
app_license = "MIT"

required_apps = ["frappe", "erpnext"]

after_install = "gps_tracker.install.after_install"
after_migrate = "gps_tracker.install.after_migrate"

doctype_js = {
    "Delivery Trip": "public/js/delivery_trip.js",
}
