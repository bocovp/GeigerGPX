(* ================================================================
   GoertzelDetector.wl
   Mathematica port of GoertzelDetector.kt for coefficient tuning.

   USAGE:
     1. Set audioFile to your .wav path (mono recommended; if stereo,
        only the first channel is used).
     2. Adjust magThreshold first — it is the primary tuning knob.
     3. Run all cells; a spectrogram with red beep-start markers appears
        at the bottom, followed by a summary table.
   ================================================================ *)

(* ----------------------------------------------------------------
   § 1  Tunable parameters  (mirror the Kotlin companion object)
   ---------------------------------------------------------------- *)

sampleRate            = 44100;          (* Hz *)
freqMain              = 3276.0;         (* target beep frequency, Hz  — "bin 13" *)
freqLow               = freqMain - 252.0;          (* lower side-band *)
freqHigh              = freqMain + 252.0;          (* upper side-band *)
windowSize            = 175;            (* Goertzel window, samples *)
stepSize              = 32;             (* sliding-window hop, samples *)

(* Primary detection gate — most important knob to tune.
   The Kotlin code receives Int16 samples (±32768); AudioData[]
   returns normalised floats (±1).  The loader below re-scales to
   Int16 range, so this threshold is directly comparable to the
   Android value. *)
magThreshold          = 5.0*^9;         (* ← tune me *)

dominanceThreshold    = 2.0;            (* SILENCE→BEEP: main > this × sideEnergy *)
dominanceThresholdEnd = 1.1;            (* BEEP→DECAY  : main > this × sideEnergy *)

oneBeepMin            = 0.019;          (* valid single-beep min duration, s *)
oneBeepMax            = 0.035;          (* valid single-beep max duration, s *)
twoBeepMax            = 0.070;          (* upper bound for a double-beep, s  *)

(* Derived — intentionally mirrors  magThresholdEnd = magThreshold / 2f  *)
magThresholdEnd = magThreshold / 2.0;


(* ----------------------------------------------------------------
   § 2  Goertzel helpers
   ---------------------------------------------------------------- *)

(* Resonator coefficient:  2 cos(2π f / fs) *)
goertzelCoeff[freq_] := 2.0 Cos[2.0 Pi freq / sampleRate];

coeffMain = goertzelCoeff[freqMain];
coeffLow  = goertzelCoeff[freqLow];
coeffHigh = goertzelCoeff[freqHigh];

(* Hann window — identical formula to the Kotlin FloatArray initialiser *)
hannWindow = N @ Table[
  0.5 - 0.5 Cos[2.0 Pi i / (windowSize - 1)],
  {i, 0, windowSize - 1}
];

(* Run the Goertzel recurrence on a pre-windowed block and return
   the squared-magnitude energy:  q1²  +  q2²  −  q1·q2·coeff      *)
goertzelEnergy[windowed_List, coeff_Real] :=
  Module[{q1 = 0.0, q2 = 0.0, q0},
    Scan[
      Function[s,
        q0 = coeff q1 - q2 + s;
        q2 = q1;
        q1 = q0
      ],
      windowed
    ];
    q1^2 + q2^2 - q1 q2 coeff
  ];

(* Returns {mainEnergy, sideEnergy} for one window starting at
   position pos (1-based) inside the full sample vector.           *)
computeWindowEnergies[samples_List, pos_Integer] :=
  Module[{w, main, low, high},
    w    = samples[[pos ;; pos + windowSize - 1]] * hannWindow;
    main = goertzelEnergy[w, coeffMain];
    low  = goertzelEnergy[w, coeffLow];
    high = goertzelEnergy[w, coeffHigh];
    {main, (low + high) / 2.0}
  ];


(* ----------------------------------------------------------------
   § 3  State machine  (SILENCE / BEEP / DECAY)
   ---------------------------------------------------------------- *)

(* Processes the full flat sample list and returns an Association:
     "beeps"        → list of Association per detected beep
     "energies"     → {main, side} per window (for diagnostics)
     "windowStarts" → 1-based sample index of each window          *)
processAudio[samples_List] :=
  Module[
    {n, windowStarts, nW, energies,
     state, beepStartSample, currentBeepMaxMain, beeps,
     pos, main, side, detected, detectedWeak, duration, beepType},

    n            = Length[samples];
    windowStarts = Range[1, n - windowSize + 1, stepSize];
    nW           = Length[windowStarts];

    (* Pre-compute all window energies *)
    energies = Table[
      computeWindowEnergies[samples, windowStarts[[k]]],
      {k, nW}
    ];

    (* Initialise state machine *)
    state              = "SILENCE";
    beepStartSample    = 1;
    currentBeepMaxMain = 0.0;
    beeps              = {};

    Do[
      pos  = windowStarts[[k]];
      main = energies[[k, 1]];
      side = energies[[k, 2]];

      (* Mirror the Kotlin guard:  only evaluate gates when above half-threshold *)
      If[main > magThresholdEnd,
        detected     = (main > magThreshold)    && (main > dominanceThreshold    * side),
        detected     = False
      ];
      If[main > magThresholdEnd,
        detectedWeak = (main > magThresholdEnd) && (main > dominanceThresholdEnd * side),
        detectedWeak = False
      ];

      Which[
        (* ---- strong detection ---- *)
        detected,
          Which[
            state == "SILENCE",
              state = "BEEP";
              beepStartSample = pos,
            state == "DECAY",
              state = "BEEP"
            (* state == "BEEP": stay, just update peak below *)
          ];
          If[main > currentBeepMaxMain, currentBeepMaxMain = main],

        (* ---- weak / decay detection ---- *)
        detectedWeak,
          If[state == "BEEP", state = "DECAY"],

        (* ---- no detection while active ---- *)
        state == "BEEP" || state == "DECAY",
          (* Duration measured from beep-start window to current window,
             matching:  (currentWindowGlobalSample - beepStartSample) / sampleRate  *)
          duration = (pos - beepStartSample) / N[sampleRate];
          beepType = Which[
            oneBeepMin <= duration <= oneBeepMax,           1,   (* single beep *)
            duration > oneBeepMax && duration <= twoBeepMax, 1,  (* double beep — kept as 1 matching Kotlin "// 1 for now" *)
            True,                                            0   (* too short or too long *)
          ];
          AppendTo[beeps,
            <|
              "startTime" -> (beepStartSample - 1) / N[sampleRate],  (* convert 1-based idx → seconds *)
              "duration"  -> duration,
              "peak"      -> currentBeepMaxMain,
              "type"      -> beepType
            |>
          ];
          state              = "SILENCE";
          currentBeepMaxMain = 0.0
      ],
      {k, nW}
    ];

    <|"beeps"        -> beeps,
      "energies"     -> energies,
      "windowStarts" -> windowStarts|>
  ];


(* ----------------------------------------------------------------
   § 4  Load audio  —  change the path below
   ---------------------------------------------------------------- *)

audioFile = "/path/to/your/audio.wav";    (* ← SET THIS *)

audio      = Import[audioFile];
fs         = Round[First @ AudioSampleRate[audio]];  (* verify sample rate *)
If[fs =!= sampleRate,
  Print["WARNING: file sample rate (", fs, " Hz) differs from sampleRate (",
        sampleRate, " Hz).  Update sampleRate above or resample the file."]
];

(* Re-scale ±1 floats → Int16 range so magThreshold is directly comparable
   to the Android value.  If the file is stereo, take channel 1. *)
rawSamples = N[AudioData[audio][[1]] * 32768.0];

(* ----------------------------------------------------------------
   § 5  Run detector
   ---------------------------------------------------------------- *)

result       = processAudio[rawSamples];
beeps        = result["beeps"];
energies     = result["energies"];
windowStarts = result["windowStarts"];

Print["Total windows analysed : ", Length[windowStarts]];
Print["Detected beeps          : ", Length[beeps]];

(* Summary table *)
If[Length[beeps] > 0,
  Print[TableForm[
    Table[
      {
        NumberForm[b["startTime"],  {6, 4}],
        NumberForm[b["duration"],   {6, 4}],
        ScientificForm[b["peak"],   3],
        b["type"]
      },
      {b, beeps}
    ],
    TableHeadings -> {None, {"Start (s)", "Duration (s)", "Peak", "Type"}}
  ]],
  Print["No beeps detected — lower magThreshold and re-run."]
];


(* ----------------------------------------------------------------
   § 6  Spectrogram with red beep-start markers
   ---------------------------------------------------------------- *)

totalDuration = Length[rawSamples] / N[sampleRate];
freqAxisMax   = 8000;  (* Hz shown on the frequency axis *)

(* Build spectrogram — SampleRate option ensures correct time axis *)
spec = Spectrogram[
  audio,
  WindowingFunction -> "Hann",
  PlotRange         -> {{0, totalDuration}, {0, freqAxisMax}},
  ColorFunction     -> "SolarColors",
  FrameLabel        -> {"Time (s)", "Frequency (Hz)"},
  PlotLabel         -> Style["Spectrogram  |  red lines = detected beep starts", 14],
  ImageSize         -> {900, 350},
  AspectRatio       -> 1/3
];

(* Vertical red lines at each beep start time *)
beepOverlay = If[Length[beeps] > 0,
  Graphics[{
    Red, Thick, Opacity[0.85],
    Table[
      Line[{{b["startTime"], 0}, {b["startTime"], freqAxisMax}}],
      {b, beeps}
    ]
  }],
  Graphics[]   (* nothing to draw if no beeps *)
];

Show[spec, beepOverlay]


(* ----------------------------------------------------------------
   § 7  Optional: energy trace plot (useful for threshold tuning)
   ---------------------------------------------------------------- *)

windowTimestamps = (windowStarts - 1) / N[sampleRate];   (* seconds *)
mainTrace = energies[[All, 1]];
sideTrace = energies[[All, 2]];

ListLogPlot[
  {
    Transpose[{windowTimestamps, mainTrace}],
    Transpose[{windowTimestamps, sideTrace}],
    (* Threshold lines as degenerate data sets for the legend *)
    {{0, magThreshold},       {totalDuration, magThreshold}},
    {{0, magThresholdEnd},    {totalDuration, magThresholdEnd}}
  },
  Joined        -> True,
  PlotStyle     -> {Blue, Gray, {Red, Dashed}, {Orange, Dashed}},
  PlotLegends   -> {"main energy", "side energy", "magThreshold", "magThresholdEnd"},
  FrameLabel    -> {"Time (s)", "Goertzel energy"},
  PlotLabel     -> "Energy trace — adjust magThreshold until red dashed line bisects beep peaks",
  ImageSize     -> {900, 250},
  AspectRatio   -> 1/4
]
