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
        this.page.add_inner_button(__("Populate Coordinates"),()=>this.populate_coordinates(),__("Route Tools"));
        this.page.add_inner_button(__("Planned Route History"),()=>this.open_history("GPS Route Plan"),__("Route History"));
        this.page.add_inner_button(__("Actual GPS History"),()=>this.open_history("GPS Location"),__("Route History"));
        this.page.add_inner_button(__("Create / Copy Public Link"),()=>this.create_public_link(),__("Share Route"));
        this.page.add_inner_button(__("Email Public Link"),()=>this.email_public_link(),__("Share Route"));
        this.page.add_inner_button(__("Revoke Public Link"),()=>this.revoke_public_link(),__("Share Route"));
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
        const d=this.data, valid=p=>p&&Number.isFinite(Number(p.latitude))&&Number.isFinite(Number(p.longitude))&&(Math.abs(Number(p.latitude))>0.0001||Math.abs(Number(p.longitude))>0.0001);
        const planned=this.prioritize_points([d.warehouse,...d.stops].filter(valid)), delivery=d.locations.filter(p=>p.route_phase!=="Return"&&valid(p)), returned=d.locations.filter(p=>p.route_phase==="Return"&&valid(p));
        const missing=(d.warehouse&&!valid(d.warehouse)?1:0)+d.stops.filter(s=>!valid(s)).length;
        if(missing)frappe.show_alert({message:__("{0} route locations need coordinates. Use Route Tools → Populate Coordinates.",[missing]),indicator:"orange"},8);
        this.map=L.map(this.$map[0]); L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19,attribution:"&copy; OpenStreetMap contributors"}).addTo(this.map);
        if(d.warehouse&&valid(d.warehouse))this.marker(d.warehouse,"W","bc-warehouse",__("Starting Warehouse: {0}",[d.warehouse.warehouse_name||d.warehouse.name]));
        d.stops.filter(valid).forEach((s,i)=>this.marker(s,String(i+1),"bc-stop-marker",`${s.customer||""}<br>${s.delivery_note||""}`));
        d.events.filter(valid).forEach(e=>this.marker(e,"✓","bc-event-marker",`${e.event_type}<br>${e.delivery_note||""}<br>${frappe.datetime.str_to_user(e.recorded_at)}`));
        const plannedLine=await this.planned_route(planned);
        if(plannedLine.length>1)frappe.call({method:"gps_tracker.api.save_planned_route",args:{delivery_trip:d.trip.name,waypoints:planned.map(p=>({latitude:p.latitude,longitude:p.longitude,delivery_note:p.delivery_note||""})),geometry:plannedLine}}).catch(()=>{});
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
    prioritize_points(points){
        if(points.length<3)return points;
        const unique=[];
        points.forEach(p=>{const key=`${Number(p.latitude).toFixed(5)},${Number(p.longitude).toFixed(5)}`,existing=unique.find(x=>x.key===key);if(existing){if(p.delivery_note)existing.stops.push(p)}else unique.push({key,point:p,stops:p.delivery_note?[p]:[]})});
        const ordered=[unique[0]],remaining=unique.slice(1),now=new Date(),clock=now.getHours()*60+now.getMinutes(),minutes=value=>{if(!value)return null;const p=String(value).split(":");return Number(p[0])*60+Number(p[1])},distance=(a,b)=>{const rad=Math.PI/180,dlat=(Number(b.latitude)-Number(a.latitude))*rad,dlon=(Number(b.longitude)-Number(a.longitude))*rad,x=Math.pow(Math.sin(dlat/2),2)+Math.cos(Number(a.latitude)*rad)*Math.cos(Number(b.latitude)*rad)*Math.pow(Math.sin(dlon/2),2);return 12742*Math.asin(Math.sqrt(x))};
        while(remaining.length){const current=ordered[ordered.length-1].point;remaining.sort((a,b)=>{const da=distance(current,a.point),db=distance(current,b.point),endsA=a.stops.map(s=>minutes(s.delivery_window_end)).filter(v=>v!==null),endsB=b.stops.map(s=>minutes(s.delivery_window_end)).filter(v=>v!==null),ea=endsA.length?Math.min(...endsA):Infinity,eb=endsB.length?Math.min(...endsB):Infinity,ua=ea-clock-da*1.5,ub=eb-clock-db*1.5;if(ua<=45||ub<=45){if(ua!==ub)return ua-ub}const startsA=a.stops.map(s=>minutes(s.delivery_window_start)).filter(v=>v!==null),startsB=b.stops.map(s=>minutes(s.delivery_window_start)).filter(v=>v!==null),sa=startsA.length?Math.min(...startsA):Infinity,sb=startsB.length?Math.min(...startsB):Infinity;if(sa<=clock+60&&!(sb<=clock+60))return -1;if(sb<=clock+60&&!(sa<=clock+60))return 1;return da-db});ordered.push(remaining.shift())}
        return ordered.map(x=>x.point);
    }
    async populate_coordinates(){const name=this.trip.get_value();if(!name)return;const r=await frappe.call({method:"gps_tracker.api.populate_delivery_trip_coordinates",args:{delivery_trip:name},freeze:true,freeze_message:__("Populating route coordinates…")});const result=r.message||{};frappe.msgprint({title:__("Coordinate Results"),message:__("Updated: {0}<br>Unresolved: {1}",[(result.updated||[]).length,(result.unresolved||[]).length]),indicator:(result.unresolved||[]).length?"orange":"green"});await this.load();}
    async create_public_link(){const name=this.trip.get_value();if(!name){frappe.msgprint(__("Select a Delivery Trip first."));return;}const r=await frappe.call("gps_tracker.api.issue_public_trip_route_link",{delivery_trip:name}),value=r.message;frappe.msgprint({title:__("24-Hour Public Route Link"),message:`<p>${__("Expires at")}: ${frappe.datetime.str_to_user(value.expires_at)}</p><input class="form-control" readonly value="${frappe.utils.escape_html(value.url)}" onclick="this.select()"><p class="text-muted mt-2">${__("Select the link and copy it to share.")}</p>`,indicator:"green"});}
    email_public_link(){const name=this.trip.get_value();if(!name){frappe.msgprint(__("Select a Delivery Trip first."));return;}const dialog=new frappe.ui.Dialog({title:__("Email 24-Hour Public Route"),fields:[{fieldname:"recipients",label:__("Recipient Emails"),fieldtype:"Data",description:__("Separate multiple addresses with commas."),reqd:1}],primary_action_label:__("Send Email"),primary_action:async values=>{await frappe.call({method:"gps_tracker.api.email_public_trip_route_link",args:{delivery_trip:name,recipients:values.recipients},freeze:true,freeze_message:__("Sending route link…")});dialog.hide();frappe.show_alert({message:__("Public route link emailed successfully"),indicator:"green"});}});dialog.show();}
    async revoke_public_link(){const name=this.trip.get_value();if(!name)return;await frappe.call("gps_tracker.api.revoke_public_trip_route_link",{delivery_trip:name});frappe.show_alert({message:__("Public route link revoked"),indicator:"green"});}
    open_history(doctype){const name=this.trip.get_value();if(!name){frappe.msgprint(__("Select a Delivery Trip first."));return;}frappe.route_options={delivery_trip:name};frappe.set_route("List",doctype);}
    marker(p,label,klass,title){return L.marker([p.latitude,p.longitude],{icon:L.divIcon({className:"",html:`<div class="bc-marker ${klass}">${label}</div>`,iconSize:[28,28],iconAnchor:[14,14]})}).addTo(this.map).bindPopup(title);}
    cards(items){return items.map(([k,v])=>`<div class="bc-trip-card"><small>${k}</small><strong>${frappe.utils.escape_html(String(v))}</strong></div>`).join("");}
}
