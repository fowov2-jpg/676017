# Transport scope

«ВремяХодом» plans pedestrian access/egress and public-transport journeys. Personal micromobility is intentionally outside the routing graph.

## Included routing modes

- WALK — pedestrian routing over the prepared OSM walking graph.
- BUS — scheduled city bus routes.
- TRAM — scheduled city tram routes, including tram-diameter services when present in the official timetable feed.
- METRO — Moscow Metro.
- MCC — Moscow Central Circle.
- MCD — currently enabled only for MCD-3 stops covered by the bundled published MTPPK timetable.
- TRAIN — currently enabled only for published MTPPK commuter trips whose stops have verified coordinates.

## Explicitly excluded

- bicycles and bike sharing;
- scooters/e-scooters and kick-scooter sharing;
- private cars, taxis and car sharing;
- river transport.

## Realtime truthfulness

The current public build does **not** have a configured third-party-accessible official vehicle-position feed for Moscow surface transport. The UI therefore treats BUS/TRAM departure data as schedule/model data and never synthesizes vehicle coordinates.

The realtime integration boundary is `RealtimeTransitSource`. A future source may be enabled only when its access terms, authentication, freshness and data semantics are verified. GPS from the passenger device is used only to locate the passenger along an active route; it is never presented as realtime vehicle telemetry.

## Metro operating constraints

- Station access and interchange availability is treated as approximately 05:30–01:00 Moscow time.
- Published first-train departures vary by station (roughly 05:28–06:05), so early-morning exact routing must not pretend that every station has a train immediately at 05:30.
- A published 90-second interval is a peak-period value, not a universal all-day headway. Until a structured line/time-of-day feed is available, metro waits remain modeled with explicit uncertainty rather than treated as exact timetable departures.

## Data-quality rule

A mode is not considered supported merely because its topology is known. A route can participate in fastest-path search only when the runtime contains enough real operational data to avoid inventing departures or waits. If a timetable/feed is missing, the mode remains unavailable rather than being represented with fabricated timing.

The bundled rail timetable is effective from 2026-04-27 and covers the verified Moscow Passenger–Zelenograd-Kryukovo corridor. It contains planned departures, not live cancellations or operational changes. Other MCD corridors and commuter stations remain unavailable until equivalent audited timetable coverage is integrated.

## Coverage presentation

Unsupported MCD corridors or railway directions are a data-coverage limitation, not a routing success with guessed timing. Product UI and QA must keep this distinction visible. Expanding coverage requires an audited timetable/feed with station coordinates and service dates; topology alone is insufficient.

## Integration goal

The final route search should compose every included public mode with WALK transfers in one time-dependent graph. Transfers must use the real pedestrian graph wherever available, and each subsequent transit leg must start from the actual arrival time of the previous leg.
