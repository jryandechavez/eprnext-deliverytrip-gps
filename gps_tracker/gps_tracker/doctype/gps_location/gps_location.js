frappe.ui.form.on("GPS Location", {
    refresh(frm) {
        if (frm.doc.latitude == null || frm.doc.longitude == null) return;
        frm.add_custom_button(__("Open in Map"), () => {
            const lat = encodeURIComponent(frm.doc.latitude);
            const lon = encodeURIComponent(frm.doc.longitude);
            window.open(`https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=17/${lat}/${lon}`, "_blank");
        });
    },
});
