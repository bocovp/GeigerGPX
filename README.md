This is Android app written in Kotlin with the following functions.

1. The basic function is logging GPS track.
One can start recoording a track and finish it.
The finished track is saved as a gpx file.
When track is being recorded, a statistics appears on the screen: track duration, number of points saved, distance travelled, cps etc.

2. The main feature: When the track is recorded the app also records the dose rate. The assumed used Geiger counter does not have a digital interface it only produces a sound (beep) with known frequency and duration.
So this sound is recorded with phone's mic and processed to detect each beep.
The dose rate and raw measurement data are written for each GPX point inside GPX extensions using the custom `rad` namespace. Optionally, the dose rate can also be duplicated into the elevation tag for compatibility.

3. Since there is GPS spoofing/jamming possible, a part of the track where the speed is too high is automatically removed.

4. There is an options menu, where following options are defined:
- max speed to detect gps spoofing/jamming
- distance (in meters) between points written in the gpx file
- whether to also save dose rate in the elevation tag.
- coundd to dose rate coefficeint.
- folder for saveing gpx file selection

The app should be compatible with android version 12.


Some notes:

- Tracking should continue when the screen is off or app is in background. Sound recoding should also continue in these cases.

- It is OK if we still read GPS more frequently (e.g. every second) but only write points when distance threshold is exceeded and speed below max-speed filter.

- When spoofing is detected, we should skip writing the points until the speed falls below limit. Then we resume writing points.

- Beeps will be quite loud but background noise will be present. The hardcoded frequency of the beep sound is taken into account in audio processing.

