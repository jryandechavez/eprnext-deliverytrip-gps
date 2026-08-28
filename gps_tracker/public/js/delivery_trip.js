frappe.ui.form.on("Delivery Trip", {
    refresh(frm) {
        if (frm.is_new()) return;
        frm.add_custom_button(__("View Live GPS Route"), () => {
            window.location.assign(`/app/delivery-trip-route?delivery_trip=${encodeURIComponent(frm.doc.name)}`);
        }, __("GPS Tracking"));

        frm.add_custom_button(__("Copy Trip Scan Code"), async () => {
            const code = `BLUECORE-TRIP:${frm.doc.name}`;
            await frappe.utils.copy_to_clipboard(code);
            frappe.show_alert({ message: __("Trip scan code copied"), indicator: "green" });
        }, __("GPS Tracking"));
    },
});
