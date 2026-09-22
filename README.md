# GeigerGPX

GeigerGPX is an Android application designed to simultaneously record GPS tracks and dose-rate data from a Geiger counter.

Currently, **RADEX RD1008** (РАДЭКС РД1008) and **RADEX RD1224Si** are supported.

No hardware modifications are required; the app records signals directly via the microphone (an external wired microphone is recommended). To use the app, simply enable audio output on your device:
- **RD1008:** Switch to "search" mode, set the threshold to zero, and turn on the sound.
- **RD1224Si:** Enable "quantum sound."

## Key Features

- **Audio pulse detection:** Utilizes three Goertzel filters (tuned to 3276.8 Hz for the RD1008, plus two witness filters) alongside a pulse duration filter for accurate detection.
- **GPS & dose recording:** Records your geographic position and overlays dose-rate data onto the track timeline.
- **GPX export:** Saves tracks in GPX format with custom extensions for dose-rate data. (Standard GPX viewers will display the path but not the dose information.)
- **Dose-aware mapping:** Visualize recorded tracks on a map using color-graded lines or generate heat maps from multiple tracks.
- **Real-time dose rate preview:** Displays a fast, real-time dose rate estimate based on the last 10 detected pulses.
- **Confidence interval calculation:** Automatically calculates confidence intervals (CIs) using χ² quantiles at a 95% confidence level.
- **Long-term measurement mode:** Useful for long-duration sampling in low-background environments.
- **Measurement results export:** Saves detailed measurement results to a separate GPX file using <wpt> tags that include descriptions, coordinates, timestamps, dose rates, and CI data.
- **Dose rate plots:** View dose rate plots for recorded tracks with adjustable averaging windows (fixed-duration or Epanechnikov kernel estimator).
- **Track editing:** Crop the start or end of a track, split tracks, or flag specific points as "bad" to exclude them from map visualizations.
- **Bluetooth audio (experimental):** Supports audio capture via Bluetooth headsets, though stability and quality are currently lower than wired or built-in microphone input.

**Note:** At high dose rates, the dosimeter may not audibly emit every pulse, so phone-side estimates can be biased low.

## Screenshots

<img width="23%" height="auto" alt="Main screen" src="https://github.com/user-attachments/assets/857e9afe-c408-4125-8b4d-5fb64df91b78" />
<img width="23%" height="auto" alt="Tracks screen" src="https://github.com/user-attachments/assets/17ec6f2e-0def-4a57-9455-2fcbe582086c" />
<img width="23%" height="auto" alt="POI screen" src="https://github.com/user-attachments/assets/01c8e0d5-2d57-4c84-8a04-bfa618e45015" />
<img width="23%" height="auto" alt="Map screen" src="https://github.com/user-attachments/assets/34d1996c-4d27-413f-ab8f-ff368b37abf5" />



<img width="23%" height="auto" alt="Plot screen 1" src="https://github.com/user-attachments/assets/158d9525-543d-4c51-9ffb-3bfa0bc3d022" />
<img width="23%" height="auto" alt="Plot screen 2" src="https://github.com/user-attachments/assets/9f5c1eda-be6c-47f5-90a4-3a707a725a6f" />
<img width="23%" height="auto" alt="Plot screen 3" src="https://github.com/user-attachments/assets/8d7abab5-72f8-461e-b1ac-3a85ae6c7a85" />
<img width="23%" height="auto" alt="Edit screen" src="https://github.com/user-attachments/assets/659a5079-af4b-471c-a718-87c796732616" />
