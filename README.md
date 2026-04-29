# GeigerGPX

GeigerGPX is an Android application for simultaneous recording GPS track and dose-rate information obtained from **RADEX RD1008** (РАДЭКС РД1008) Geiger counter.
No modification to the radiometer is required; signals are recorded via the microphone. Simply switch RD1008 into "search" mode, reset the threshold, and turn on the sound.

## Current capabilities

- **Audio pulse detection:** Pulse detection uses three Goertzel filters (the main tuned at 3276.8 Hz and a pair of witnesses at ±234 Hz) plus a pulse duration filter. The parameters are tailored to RD1008 (not tested on other counters).
- **GPS track recording with dose overlay:** The application records position and superimposes dose rate data on the same track timeline.
- **Export tracks to GPX files:** Tracks are saved in GPX format with custom extensions for dose rate; other GPX applications can open tracks, but will not display the dose rate information.
- **Dose-aware map analysis:** Recorded tracks can be analyzed on a map as either color-graded lines or a heat map built from multiple selected tracks.
- **Fast current-dose preview:** A real-time dose rate estimate is shown (based on the last 10 detected pulses).
- **Confidence interval calculation:** Confidence intervals (CIs) are computed using χ² quantiles at a 0.95 confidence level.
- **Long-term Measurement mode:** A dedicated measurement workflow for low-background and long-duration sampling.
- **Measurement results export:** Measurement results are written to a separate GPX-file with `<wpt>` tags containing description, coordinates, timestamp, dose rate, and CI-related fields.
- **Dose rate plots:** For recorded tracks the application provides a dose rate plot with a manually adjustable averaging window (fixed-duration window or Epanechnikov kernel estimator).
- **Track editing:** One can crop beginning/end of the track, cut track into two, or mark some points as "bad" so they aren't shown on the map.
- **Experimental Bluetooth audio input:** Audio capture from Bluetooth headsets is supported as an experimental feature, but it is way less stable and provides lower quality as compared to built-in microphone capture.
- **High-rate limitation warning:** At high dose rates, RD-1008 may not emit all pulses audibly, so phone-side estimates can be biased low.

## Screenshots

<img width="23%" height="auto" alt="Main screen" src="https://github.com/user-attachments/assets/4768d1fd-f57b-4a70-ab51-6ad4ad73ccc7" />
<img width="23%" height="auto" alt="Tracks screen" src="https://github.com/user-attachments/assets/17ec6f2e-0def-4a57-9455-2fcbe582086c" />
<img width="23%" height="auto" alt="POI screen" src="https://github.com/user-attachments/assets/01c8e0d5-2d57-4c84-8a04-bfa618e45015" />
<img width="23%" height="auto" alt="Map screen" src="https://github.com/user-attachments/assets/34d1996c-4d27-413f-ab8f-ff368b37abf5" />

<img width="23%" height="auto" alt="Plot screen 1" src="https://github.com/user-attachments/assets/158d9525-543d-4c51-9ffb-3bfa0bc3d022" />
<img width="23%" height="auto" alt="Plot screen 2" src="https://github.com/user-attachments/assets/9f5c1eda-be6c-47f5-90a4-3a707a725a6f" />
<img width="23%" height="auto" alt="Plot screen 3" src="https://github.com/user-attachments/assets/8d7abab5-72f8-461e-b1ac-3a85ae6c7a85" />
<img width="23%" height="auto" alt="Edit screen" src="https://github.com/user-attachments/assets/2514316b-203d-4ce6-b0d6-0cc43a89eb1d" />

