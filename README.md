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
- **Dose rate plots:** For recorded tracks the application provides a dose rate plot with a manually adjustable averaging window (fixed-duration window or Epanechnikov kernel).
- **Experimental Bluetooth audio input:** Audio capture from Bluetooth headsets is supported as an experimental feature, but it is way less stable and provides lower quality as compared to built-in microphone capture.
- **High-rate limitation warning:** At high dose rates, RD-1008 may not emit all pulses audibly, so phone-side estimates can be biased low.

## Screenshots

<img width="20%" height="auto" alt="Main screen" src="https://github.com/user-attachments/assets/4768d1fd-f57b-4a70-ab51-6ad4ad73ccc7" />
<img width="20%" height="auto" alt="Tracks screen" src="https://github.com/user-attachments/assets/42c71a24-cc2c-4b0e-985d-4695ceebe921" />
<img width="20%" height="auto" alt="POI screen" src="https://github.com/user-attachments/assets/01c8e0d5-2d57-4c84-8a04-bfa618e45015" />
<img width="20%" height="auto" alt="Map screen" src="https://github.com/user-attachments/assets/b09c2f03-38d1-4657-9318-e728685b5a1e" />

<img width="20%" height="auto" alt="Plot screen 1" src="https://github.com/user-attachments/assets/83d19cf3-1dea-4a6c-abd1-3d1e24fd41b7" />
<img width="20%" height="auto" alt="Plot screen 2" src="https://github.com/user-attachments/assets/640684d9-241f-426b-9224-02f6dd5f1c0c" />
<img width="20%" height="auto" alt="Plot screen 3" src="https://github.com/user-attachments/assets/4cbd4244-acf5-46a8-b5e2-40a1396216f9" />
