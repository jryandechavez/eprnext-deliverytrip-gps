frappe.pages["daily-route-map"].on_page_load = function (wrapper) {
    new BluecoreDailyRoute(wrapper);
};

class BluecoreDailyRoute {
    constructor(wrapper) {
        this.page = frappe.ui.make_app_page({
            parent: wrapper,
            title: __("Daily Route Map"),
            single_column: true,
        });
        this.points = [];
        this.setup_controls();
        this.setup_body();
        this.load_map_assets();
    }

    setup_controls() {
        this.device = this.page.add_field({
            fieldname: "device_id",
            label: __("Device"),
            fieldtype: "Select",
            options: [],
            change: () => this.refresh_route(),
        });
        this.date = this.page.add_field({
            fieldname: "date",
            label: __("Date"),
            fieldtype: "Date",
            default: frappe.datetime.get_today(),
            reqd: 1,
            change: () => this.refresh_route(),
        });
        this.page.set_primary_action(__("Refresh"), () => this.refresh_route(), "refresh");
    }

    setup_body() {
        this.$body = $(this.page.body).append(`
            <div class="bluecore-route-summary">
                ${this.stat("points", __("GPS Points"))}
                ${this.stat("distance", __("Distance"))}
                ${this.stat("duration", __("Tracked Time"))}
                ${this.stat("stops", __("Stops"))}
                ${this.stat("accuracy", __("Average Accuracy"))}
            </div>
            <div class="bluecore-route-map">
                <div class="bluecore-route-empty">${__("Select a device and date to view its delivery route.")}</div>
            </div>
            <div class="bluecore-route-legend">
                ${__("Green: start · Red: end · Orange: stop of 10 minutes or longer")}
            </div>
        `);
        this.$map = this.$body.find(".bluecore-route-map");
    }

    stat(key, label) {
        return `<div class="bluecore-route-stat"><div class="bluecore-route-stat-label">${label}</div><div class="bluecore-route-stat-value" data-stat="${key}">—</div></div>`;
    }

    load_map_assets() {
        frappe.require(
            [
                "/assets/frappe/js/lib/leaflet/leaflet.css",
                "/assets/frappe/js/lib/leaflet/leaflet.js",
            ],
            () => this.load_devices()
        );
    }

    async load_devices() {
        try {
            const response = await frappe.call("gps_tracker.api.route_devices");
            const devices = response.message || [];
            this.device.df.options = ["", ...devices];
            this.device.refresh();
            if (devices.length === 1) {
                this.device.set_value(devices[0]);
            }
        } catch (error) {
            this.show_empty(__("Unable to load devices. Check GPS Location read permission."));
        }
    }

    async refresh_route() {
        const device_id = this.device.get_value();
        const date = this.date.get_value();
        if (!device_id || !date || typeof L === "undefined") return;

        this.page.set_indicator(__("Loading"), "orange");
        try {
            const response = await frappe.call({
                method: "gps_tracker.api.daily_route",
                args: { device_id, date },
                freeze: true,
                freeze_message: __("Loading delivery route…"),
            });
            this.points = (response.message || []).filter(
                (point) => Number.isFinite(Number(point.latitude)) && Number.isFinite(Number(point.longitude))
            );
            this.render();
            this.page.set_indicator(__("Updated"), "green");
        } catch (error) {
            this.page.clear_indicator();
            this.show_empty(__("Unable to load this route."));
        }
    }

    render() {
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
        this.$map.empty();
        if (!this.points.length) {
            this.update_stats({ points: 0, distance: "0 km", duration: "—", stops: 0, accuracy: "—" });
            this.show_empty(__("No GPS reports were recorded for this device on the selected date."));
            return;
        }

        this.map = L.map(this.$map.get(0), { preferCanvas: true });
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            maxZoom: 19,
            attribution: "&copy; OpenStreetMap contributors",
        }).addTo(this.map);

        const latlngs = this.points.map((point) => [Number(point.latitude), Number(point.longitude)]);
        const route = L.polyline(latlngs, { color: "#2563eb", opacity: 0.9, weight: 5 }).addTo(this.map);
        this.map.fitBounds(route.getBounds().pad(0.12), { maxZoom: 17 });

        this.add_endpoint(this.points[0], "start", "S", __("Route started"));
        if (this.points.length > 1) this.add_endpoint(this.points.at(-1), "end", "E", __("Route ended"));

        const stops = this.find_stops();
        stops.forEach((stop) => this.add_stop(stop));
        this.points.forEach((point, index) => this.add_point(point, index));

        const distance = this.total_distance();
        const first = frappe.datetime.str_to_obj(this.points[0].recorded_at);
        const last = frappe.datetime.str_to_obj(this.points.at(-1).recorded_at);
        const duration_minutes = Math.max(0, Math.round((last - first) / 60000));
        const accuracies = this.points.map((p) => Number(p.accuracy)).filter(Number.isFinite);
        const average_accuracy = accuracies.length
            ? `${Math.round(accuracies.reduce((a, b) => a + b, 0) / accuracies.length)} m`
            : "—";

        this.update_stats({
            points: this.points.length,
            distance: `${distance.toFixed(2)} km`,
            duration: this.format_duration(duration_minutes),
            stops: stops.length,
            accuracy: average_accuracy,
        });
        setTimeout(() => this.map.invalidateSize(), 0);
    }

    add_endpoint(point, kind, label, title) {
        const icon = L.divIcon({
            className: "",
            html: `<div class="bluecore-route-${kind}">${label}</div>`,
            iconSize: [28, 28],
            iconAnchor: [14, 14],
        });
        L.marker([point.latitude, point.longitude], { icon })
            .addTo(this.map)
            .bindPopup(this.popup(title, point));
    }

    add_point(point, index) {
        L.circleMarker([point.latitude, point.longitude], {
            radius: 4,
            color: "#1d4ed8",
            fillColor: "#ffffff",
            fillOpacity: 1,
            weight: 2,
        })
            .addTo(this.map)
            .bindPopup(this.popup(__("Point {0}", [index + 1]), point));
    }

    add_stop(stop) {
        const icon = L.divIcon({
            className: "",
            html: `<div class="bluecore-route-stop">P</div>`,
            iconSize: [28, 28],
            iconAnchor: [14, 14],
        });
        L.marker([stop.point.latitude, stop.point.longitude], { icon })
            .addTo(this.map)
            .bindPopup(this.popup(__("Stopped for {0}", [this.format_duration(stop.minutes)]), stop.point));
    }

    popup(title, point) {
        const time = frappe.datetime.str_to_user(point.recorded_at);
        const accuracy = point.accuracy == null ? "—" : `${Math.round(point.accuracy)} m`;
        const speed = point.speed == null ? "—" : `${(Number(point.speed) * 3.6).toFixed(1)} km/h`;
        return `<strong>${frappe.utils.escape_html(title)}</strong><br>${time}<br>${__("Accuracy")}: ${accuracy}<br>${__("Speed")}: ${speed}`;
    }

    find_stops() {
        const stops = [];
        let anchor = 0;
        for (let index = 1; index < this.points.length; index += 1) {
            if (this.distance_between(this.points[anchor], this.points[index]) > 0.075) {
                const minutes = this.minutes_between(this.points[anchor], this.points[index - 1]);
                if (minutes >= 10) stops.push({ point: this.points[anchor], minutes });
                anchor = index;
            }
        }
        const final_minutes = this.minutes_between(this.points[anchor], this.points.at(-1));
        if (final_minutes >= 10) stops.push({ point: this.points[anchor], minutes: final_minutes });
        return stops;
    }

    total_distance() {
        return this.points.slice(1).reduce(
            (total, point, index) => total + this.distance_between(this.points[index], point),
            0
        );
    }

    distance_between(a, b) {
        const radius = 6371;
        const lat1 = this.radians(Number(a.latitude));
        const lat2 = this.radians(Number(b.latitude));
        const delta_lat = this.radians(Number(b.latitude) - Number(a.latitude));
        const delta_lon = this.radians(Number(b.longitude) - Number(a.longitude));
        const value =
            Math.sin(delta_lat / 2) ** 2 +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(delta_lon / 2) ** 2;
        return radius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }

    minutes_between(a, b) {
        return Math.max(
            0,
            Math.round(
                (frappe.datetime.str_to_obj(b.recorded_at) - frappe.datetime.str_to_obj(a.recorded_at)) / 60000
            )
        );
    }

    radians(degrees) {
        return (degrees * Math.PI) / 180;
    }

    format_duration(minutes) {
        if (minutes < 60) return __("{0} min", [minutes]);
        const hours = Math.floor(minutes / 60);
        const remainder = minutes % 60;
        return remainder ? __("{0}h {1}m", [hours, remainder]) : __("{0}h", [hours]);
    }

    update_stats(values) {
        Object.entries(values).forEach(([key, value]) => {
            this.$body.find(`[data-stat="${key}"]`).text(value);
        });
    }

    show_empty(message) {
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
        this.$map.html(`<div class="bluecore-route-empty">${frappe.utils.escape_html(message)}</div>`);
    }
}
