# PL Aguila GPS + PTT deployment

This relay is the PL Aguila-specific real-time Push-to-Talk component. It is
deployed independently from ERPNext while authenticating each WebSocket client
against the user's `soa.pl-aguila.com` ERPNext session.

- Service: `pl-aguila-ptt.service`
- Relay directory: `/opt/pl-aguila-ptt`
- Local listener: `127.0.0.1:8765`
- Public WebSocket: `wss://soa.pl-aguila.com/ptt`
- Health endpoint: `https://soa.pl-aguila.com/ptt-health`
- ERPNext authentication: `https://soa.pl-aguila.com`
- Audio storage: none; frames are relayed in memory only

The Android application, ERPNext `gps_tracker` app, and this relay form the PL
Aguila delivery package. Do not copy Tic & Terry APNs credentials or device
registries into this deployment. A PL Aguila Apple bundle and APNs key can be
configured later if an iOS app is commissioned.
