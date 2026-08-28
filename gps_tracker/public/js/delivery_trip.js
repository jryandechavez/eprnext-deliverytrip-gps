frappe.ui.form.on("Delivery Trip", {
    refresh(frm) {
        if (frm.is_new()) return;
        frm.add_custom_button(__("View Proposed & Actual Route"), () => {
            window.location.assign(`/app/delivery-trip-route?delivery_trip=${encodeURIComponent(frm.doc.name)}`);
        }, __("GPS Tracking"));

        frm.add_custom_button(__("Copy Trip Scan Code"), async () => {
            const code = `BLUECORE-TRIP:${frm.doc.name}`;
            await frappe.utils.copy_to_clipboard(code);
            frappe.show_alert({ message: __("Trip scan code copied"), indicator: "green" });
        }, __("GPS Tracking"));
        frm.add_custom_button(__("Create 24-Hour Public Link"), async () => {
            const response = await frappe.call("gps_tracker.api.issue_public_trip_route_link", {delivery_trip: frm.doc.name});
            const value = response.message;
            frappe.msgprint({title: __("24-Hour Public Route Link"), message: `<p>${__("Expires at")}: ${frappe.datetime.str_to_user(value.expires_at)}</p><input class="form-control" readonly value="${frappe.utils.escape_html(value.url)}" onclick="this.select()">`, indicator: "green"});
            frm.reload_doc();
        }, __("GPS Tracking"));
        if (frm.doc.gps_public_route_enabled) frm.add_custom_button(__("Revoke Public Link"), async () => {
            await frappe.call("gps_tracker.api.revoke_public_trip_route_link", {delivery_trip: frm.doc.name});
            frappe.show_alert({message: __("Public route link revoked"), indicator: "green"}); frm.reload_doc();
        }, __("GPS Tracking"));
    },
});
