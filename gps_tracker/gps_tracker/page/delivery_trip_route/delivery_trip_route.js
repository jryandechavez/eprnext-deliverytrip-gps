frappe.pages["delivery-trip-route"].on_page_load = wrapper => {
    wrapper.bluecore_route = new DeliveryTripRoute(wrapper);
};

frappe.pages["delivery-trip-route"].on_page_show = wrapper => {
    const view = wrapper.bluecore_route;
    const trip = frappe.utils.get_url_arg("delivery_trip") || frappe.route_options?.delivery_trip;
    if (view?.assets_ready && trip && view.trip.get_value() !== trip) view.trip.set_value(trip);
};

class DeliveryTripRoute {
    constructor(wrapper) {
        this.page = frappe.ui.make_app_page({parent: wrapper, title: __("Delivery Trip Route"), single_column: true});
        this.trip = this.page.add_field({fieldname:"delivery_trip",label:__("Delivery Trip"),fieldtype:"Link",options:"Delivery Trip",reqd:1,change:()=>this.load()});
        this.page.set_primary_action(__("Refresh"),()=>this.load(),"refresh");
        this.$root=$(this.page.body).append(`<div class="bc-trip-summary"></div><div class="bc-trip-layout"><div class="bc-trip-map"></div><div class="bc-trip-stops"></div></div>`);
        this.$map=this.$root.find(".bc-trip-map"); this.$stops=this.$root.find(".bc-trip-stops");
        frappe.require(["/assets/frappe/js/lib/leaflet/leaflet.css","/assets/frappe/js/lib/leaflet/leaflet.js"], () => {
            this.assets_ready = true;
            const initial = frappe.utils.get_url_arg("delivery_trip") || frappe.route_options?.delivery_trip;
            if (initial) {
                if (this.trip.get_value() !== initial) this.trip.set_value(initial);
                else this.load();
            }
            frappe.route_options = null;
        });
    }
    async load() {
        const name=this.trip.get_value(); if(!name||!this.assets_ready||typeof L==="undefined") return;
        try { const r=await frappe.call({method:"gps_tracker.api.delivery_trip_route",args:{delivery_trip:name},freeze:true,freeze_message:__("Loading trip route…")}); this.data=r.message; await this.render(); }
        catch(e){this.$map.html(`<div class="p-5 text-muted">${__("Unable to load this Delivery Trip.")}</div>`);}
    }
    async render() {
        if(this.map)this.map.remove(); this.$map.empty();
        const d=this.data, valid=p=>p&&Number.isFinite(Number(p.latitude))&&Number.isFinite(Number(p.longitude));
        const planned=[d.warehouse,...d.stops].filter(valid), delivery=d.locations.filter(p=>p.route_phase!=="Return"&&valid(p)), returned=d.locations.filter(p=>p.route_phase==="Return"&&valid(p));
        this.map=L.map(this.$map[0]); L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19,attribution:"&copy; OpenStreetMap contributors"}).addTo(this.map);
        if(d.warehouse&&valid(d.warehouse))this.marker(d.warehouse,"W","bc-warehouse",__("Starting Warehouse: {0}",[d.warehouse.warehouse_name||d.warehouse.name]));
        d.stops.filter(valid).forEach((s,i)=>this.marker(s,String(i+1),"bc-stop-marker",`${s.customer||""}<br>${s.delivery_note||""}`));
        d.events.filter(valid).forEach(e=>this.marker(e,"✓","bc-event-marker",`${e.event_type}<br>${e.delivery_note||""}<br>${frappe.datetime.str_to_user(e.recorded_at)}`));
        const plannedLine=await this.planned_route(planned);
        if(plannedLine.length)L.polyline(plannedLine,{color:"#2563eb",weight:5,opacity:.7,dashArray:"8 8"}).addTo(this.map);
        if(delivery.length)L.polyline(delivery.map(p=>[p.latitude,p.longitude]),{color:"#16a34a",weight:5}).addTo(this.map);
        if(returned.length)L.polyline(returned.map(p=>[p.latitude,p.longitude]),{color:"#7c3aed",weight:5}).addTo(this.map);
        const all=[...plannedLine,...delivery.map(p=>[p.latitude,p.longitude]),...returned.map(p=>[p.latitude,p.longitude])]; if(all.length)this.map.fitBounds(L.latLngBounds(all).pad(.1));
        this.$root.find(".bc-trip-summary").html(this.cards([[__("Status"),d.trip.tracking_status||d.trip.status],[__("Driver"),d.trip.driver_name||"—"],[__("Device"),d.trip.device_id||"—"],[__("Stops"),d.stops.length],[__("GPS Points"),d.locations.length]]));
        this.$stops.html(`<div class="bc-stop"><b>${__("Stops and Delivery Notes")}</b><div class="bc-stop-meta">${__("Blue dashed: planned · Green: actual delivery · Purple: return")}</div></div>`+d.stops.map((s,i)=>`<div class="bc-stop"><div class="bc-stop-title">${i+1}. ${frappe.utils.escape_html(s.customer||__("Customer"))}</div><div>${s.delivery_note?`<a href="/app/delivery-note/${encodeURIComponent(s.delivery_note)}">${frappe.utils.escape_html(s.delivery_note)}</a>`:"—"} <span class="bc-badge">${frappe.utils.escape_html(s.delivery_status)}</span></div><div class="bc-stop-meta">${frappe.utils.escape_html(s.customer_address||s.address||"")}</div><button class="btn btn-xs btn-default mt-2 bc-share" data-stop="${frappe.utils.escape_html(s.name)}">${__("Create Customer Tracking Link")}</button></div>`).join(""));
        this.$stops.find(".bc-share").on("click",async e=>{const stop=$(e.currentTarget).data("stop");const r=await frappe.call("gps_tracker.api.issue_public_tracking_link",{delivery_trip:d.trip.name,delivery_stop:stop});frappe.msgprint({title:__("Customer Tracking Link"),message:`<input class="form-control" readonly value="${frappe.utils.escape_html(r.message)}" onclick="this.select()">`,indicator:"green"});});
        setTimeout(()=>this.map.invalidateSize(),0);
    }
    async planned_route(points){
        if(points.length<2)return points.map(p=>[p.latitude,p.longitude]);
        const coords=points.map(p=>`${p.longitude},${p.latitude}`).join(";");
        try{const r=await fetch(`https://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`);const j=await r.json();return j.routes[0].geometry.coordinates.map(c=>[c[1],c[0]]);}catch(e){frappe.show_alert({message:__("Road routing unavailable; showing straight planned line."),indicator:"orange"});return points.map(p=>[p.latitude,p.longitude]);}
    }
    marker(p,label,klass,title){return L.marker([p.latitude,p.longitude],{icon:L.divIcon({className:"",html:`<div class="bc-marker ${klass}">${label}</div>`,iconSize:[28,28],iconAnchor:[14,14]})}).addTo(this.map).bindPopup(title);}
    cards(items){return items.map(([k,v])=>`<div class="bc-trip-card"><small>${k}</small><strong>${frappe.utils.escape_html(String(v))}</strong></div>`).join("");}
}
