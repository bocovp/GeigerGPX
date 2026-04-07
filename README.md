# GeigerGPX

GeigerGPX is an Android application for simultaneous recording GPS track and dose-rate information obtained from **RADEX RD1008** (РАДЭКС РД1008) Geiger counter.
No modification to the radiometer is required; signals are recorded via the microphone. Simply switch RD1008 into "search" mode, reset the threshold, and turn on the sound.

## Current capabilities

- **Audio pulse detection:** Pulse detection uses three Goertzel filters (the main tuned at 3276.8 Hz and a pair of witnesses at ±234 Hz) plus a pulse duration filter. The parameters are tailored to RD1008 (not tested on other counters).
- **GPS track recording with dose overlay:** The application records position and superimposes dose rate data on the same track timeline.
- **Export tracks to GPX files:** Tracks are saved in GPX format with custom extensions for dose rate; other GPX applications can open tracks, but will not display the dose rate information.
- **Dose-aware map analysis:** Recorded tracks can be analyzed on a map as either color-graded lines or a heat map built from multiple selected tracks.
- **Fast current-dose preview:** A real-time dose rate estimate is shown (based on the last 10 detected pulses).
- **Сonfidence interval calcualtion:** Confidence intervals (CIs) are computed using χ² quantiles at a 0.95 confidence level.
- **Long-term Measurement mode:** A dedicated measurement workflow for low-background and long-duration sampling.
- **Measurement waypoint export:** Measurement results are written to a separate GPX-file as `<wpt>` tags with description, coordinates, timestamp, dose rate, and CI-related fields.
- **Track dose graphing:** For recorded tracks the application provides a dose-rate plot with a manually adjustable averaging window.
- **Experimental Bluetooth audio input:** Audio capture from Bluetooth headsets is supported as an experimental feature, but it is way less stable and provides lower quality as compared to built-in or wired microphone capture.
- **High-rate limitation warning:** At high dose rates, RD-1008 may not emit all pulses audibly, so phone-side estimates can be biased low.

## Notes

- The application is intended for practical field logging and post-analysis; for high-dose scenarios, rely on instrument-native readings as the primary source.
