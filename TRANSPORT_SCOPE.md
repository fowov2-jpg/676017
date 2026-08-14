# Transport scope

Human Router plans pedestrian access/egress and public-transport journeys. Personal micromobility is intentionally outside the routing graph.

## Included routing modes

- WALK — pedestrian routing over the prepared OSM walking graph.
- BUS — scheduled city bus routes.
- TRAM — scheduled city tram routes, including tram-diameter services when present in the official timetable feed.
- METRO — Moscow Metro.
- MCC — Moscow Central Circle.
- MCD — Moscow Central Diameters; must only be enabled when timetable/headway data is backed by a real source.
- TRAIN — suburban/commuter rail outside the MCD abstraction; must use real timetable data.
- RIVER — regular urban river transport; leisure/cruise services are not part of normal commuter routing.

## Explicitly excluded

- bicycles and bike sharing;
- scooters/e-scooters and kick-scooter sharing;
- private cars, taxis and car sharing;
- leisure river cruises and sightseeing routes.

## Data-quality rule

A mode is not considered supported merely because its topology is known. A route can participate in fastest-path search only when the runtime contains enough real operational data to avoid inventing departures or waits. If a timetable/feed is missing, the mode remains unavailable rather than being represented with fabricated timing.

## Integration goal

The final route search should compose every included public mode with WALK transfers in one time-dependent graph. Transfers must use the real pedestrian graph wherever available, and each subsequent transit leg must start from the actual arrival time of the previous leg.
