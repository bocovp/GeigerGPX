# GeigerGPX

GeigerGPX is an Android app for GPS track recording with radiation dose-rate estimation from Geiger counter beeps, designed and tuned for the **RADEX RD-1008**.

## Current capabilities

- **RD-1008-focused audio detection:** Pulse detection uses three Goertzel filters (3276.8 Hz center and side peaks at ±234 Hz) plus a pulse-duration filter, with parameters tailored to RD-1008 and not yet validated on other counters.
- **GPS track recording with dose overlay:** The app records position and superimposes dose-rate data on the same track timeline.
- **GPX export with custom radiation data:** Tracks are saved as GPX with custom radiation extensions; other GPX apps can open tracks, but usually do not display dose-rate extensions.
- **Dose-aware map analysis:** Recorded tracks can be analyzed on the map as either color-graded lines or a cumulative heat map built from multiple selected tracks.
- **Fast current-dose preview:** A quick current dose-rate estimate is shown using statistics from the last 10 detected pulses.
- **Statistical confidence intervals:** Confidence intervals are computed using Chi-square quantiles at a 0.95 confidence level.
- **Long-term Measurement mode:** A dedicated measurement workflow supports low-background and long-duration sampling.
- **Measurement waypoint export:** Measurement results are additionally written to a separate GPX file as `<wpt>` entries with description, coordinates, timestamp, dose rate, and interval-reconstruction fields.
- **Track dose graphing:** The app provides a dose-rate graph for recorded tracks with a manually selectable averaging window.
- **Experimental Bluetooth audio input:** Audio capture from Bluetooth headsets is supported as an experimental feature, but it is less stable and usually lower quality than built-in microphone capture.
- **No explicit beta/noise mode:** The processing pipeline continuously accumulates statistics from GPS and audio streams without a dedicated beta/background-noise mode toggle.
- **High-rate limitation warning:** At high dose rates, RD-1008 may not emit all pulses audibly, so phone-side estimates can be biased low.

## Notes

- The app is intended for practical field logging and post-analysis; for high-dose scenarios, rely on instrument-native readings as the primary source.
