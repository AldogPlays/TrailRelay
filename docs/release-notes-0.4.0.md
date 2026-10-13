# TrailRelay 0.4.0

This release improves map usability and trail context.

- Heading Up corrects compass readings to true north, filters small heading jitter, and uses reliable GPS course while moving. A saved Heading offset supports angled phone mounts without changing GPS course.
- GPS handling rejects stale, duplicate, and implausible fixes. Uncertain fixes can still show your reported location and accuracy circle without steering the follow camera.
- Selected trails show approximate distance from the route and neutral along-route distances to endpoints A and B. Disconnected GPX segments do not imply a connecting route, loop routes share an A/B marker, and your location indicator is never snapped to the trail.
- Zoom buttons, clearer map controls, and refined trail details improve map use. Camera zoom stops at 16 to avoid excessive enlargement beyond the USGS imagery's available detail.
- Map-source failures use a small status indicator with an explanation and retry action, keeping downloaded maps usable without a large error banner.
- The optional Show offline coverage setting displays a grid for the selected Aerial, Hybrid, or Topo mode. Completed packages have filled squares; incomplete packages have dashed outlines showing requested coverage, not confirmed downloaded tiles.
- Saved-route geometry caching and background overlay preparation reduce repeated GPX parsing and map preparation during route loading. Original GPX files remain canonical, and invalid derived cache entries are rebuilt.

Android 8.0 (API 26) or newer. No account or user API key is required. Offline maps cover only the downloaded area and zooms 12–16; download each map mode you need and check it without networking before heading out.

Updates from 0.3.0 use the same application ID and signing identity. Install as an update to retain saved trails and offline packages; uninstalling or clearing app data erases them.
