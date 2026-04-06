# GeigerGPX

GeigerGPX is an Android application for simultaneous recording GPS track and dose-rate information obtained from **RADEX RD-1008** Geiger counter.
No modification to the radiometer is required; signals are recorded via the microphone. Simply enter "search" mode, reset the threshold, and turn on the sound.

## Current capabilities

- **Audio pulse detection:** Pulse detection uses three Goertzel filters (the main tuned at 3276.8 Hz and a pair of witnesses at ±234 Hz) plus a pulse-duration filter, with parameters tailored to RD-1008 and not validated on other counters.
- **GPS track recording with dose overlay:** The application records position and superimposes dose-rate data on the same track timeline.
- **Export tracks to GPX files:** Tracks are saved as GPX with custom radiation extensions; other GPX applications can open tracks, but do not display dose-rate extensions.
- **Dose-aware map analysis:** Recorded tracks can be analyzed on the map as either color-graded lines or a cumulative heat map built from multiple selected tracks.
- **Fast current-dose preview:** A quick current dose-rate estimate is shown using statistics from the last 10 detected pulses.
- **Сonfidence interval calcualtion:** Confidence intervals (CIs) are computed using Chi-square quantiles at a 0.95 confidence level.
- **Long-term Measurement mode:** A dedicated measurement workflow supports low-background and long-duration sampling.
- **Measurement waypoint export:** Measurement results are additionally written to a separate GPX file as `<wpt>` entries with description, coordinates, timestamp, dose rate, and CI-related fields.
- **Track dose graphing:** The application provides a dose-rate plot for recorded tracks with a manually adjustable averaging window.
- **Experimental Bluetooth audio input:** Audio capture from Bluetooth headsets is supported as an experimental feature, but it is less stable and usually lower quality than built-in or wired microphone capture.
- **High-rate limitation warning:** At high dose rates, RD-1008 may not emit all pulses audibly, so phone-side estimates can be biased low.

## Notes

- The application is intended for practical field logging and post-analysis; for high-dose scenarios, rely on instrument-native readings as the primary source.
