(* ::Package:: *)

(* ================================================================
   GoertzelDetector.wl
   Mathematica port of GoertzelDetector.kt for coefficient tuning.

   USAGE:
     1. Set audioFile to your .wav path (mono recommended; if stereo,
        only the first channel is used).
     2. Adjust magThreshold first \[LongDash] it is the primary tuning knob.
     3. Run all cells; a spectrogram with red beep-start markers appears
        at the bottom, followed by a summary table.
   ================================================================ *)

(* ----------------------------------------------------------------
   \[Section] 1  Tunable parameters  (mirror the Kotlin companion object)
   ---------------------------------------------------------------- *)

sampleRate            = 44100;             (* Hz *)
freqMain              = 3276.0;            (* target beep frequency, Hz  \[LongDash] "bin 13" *)
freqLow               = freqMain - 252.0;  (* lower side-band *)
freqHigh              = freqMain + 252.0;  (* upper side-band *)
windowSize            = 175;               (* Goertzel window, samples *)
stepSize              = 32;                (* sliding-window hop, samples *)

(* Primary detection gate \[LongDash] most important knob to tune.
   The Kotlin code receives Int16 samples (\[PlusMinus]32768); AudioData[]
   returns normalised floats (\[PlusMinus]1).  The loader below re-scales to
   Int16 range, so this threshold is directly comparable to the
   Android value. *)
magThreshold          = 5.0*^9;                (* \[LeftArrow] tune me *)
magThreshold          = 5.0*^11;               (* \[LeftArrow] tune me *)

dominanceThreshold    = 2.0;                   (* SILENCE\[RightArrow]BEEP: main > this \[Times] sideEnergy *)
dominanceThresholdEnd = 1.1;                   (* BEEP\[RightArrow]DECAY  : main > this \[Times] sideEnergy *)

oneBeepMin            = 0.025-0.005;           (* valid single-beep min duration, s *)
oneBeepMax            = 0.025+0.005;           (* valid single-beep max duration, s *)

twoBeepMin            = 0.025*2-0.01;          (* upper bound for a double-beep, s  *)
twoBeepMax            = 0.025*2+0.01;          (* upper bound for a double-beep, s  *)

threeBeepMin          = 0.025*3-0.01;          (* upper bound for a double-beep, s  *)
threeBeepMax          = 0.025*3+0.01;          (* upper bound for a double-beep, s  *)

fourBeepMin           = 0.025*4-0.01;          (* upper bound for a double-beep, s  *)
fourBeepMax           = 0.025*4+0.01;          (* upper bound for a double-beep, s  *)

(* Derived \[LongDash] intentionally mirrors  magThresholdEnd = magThreshold / 2f  *)
magThresholdEnd = magThreshold / 2.0;


(* ----------------------------------------------------------------
   \[Section] 2  Goertzel helpers
   ---------------------------------------------------------------- *)

(* Resonator coefficient:  2 cos(2\[Pi] f / fs) *)
goertzelCoeff[freq_] := 2.0 Cos[2.0 Pi freq / sampleRate];

coeffMain = goertzelCoeff[freqMain];
coeffLow  = goertzelCoeff[freqLow];
coeffHigh = goertzelCoeff[freqHigh];

(* Hann window \[LongDash] identical formula to the Kotlin FloatArray initialiser *)
hannWindow = N @ Table[
  0.5 - 0.5 Cos[2.0 Pi i / (windowSize - 1)],
  {i, 0, windowSize - 1}
];

(* Run the Goertzel recurrence on a pre-windowed block and return
   the squared-magnitude energy:  q1\.b2  +  q2\.b2  \[Minus]  q1\[CenterDot]q2\[CenterDot]coeff      *)
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
    {main, low, high}
  ];


(* ----------------------------------------------------------------
   \[Section] 3  State machine  (SILENCE / BEEP / DECAY)
   ---------------------------------------------------------------- *)

(* Processes the full flat sample list and returns an Association:
     "beeps"        \[RightArrow] list of Association per detected beep
     "energies"     \[RightArrow] {main, side} per window (for diagnostics)
     "windowStarts" \[RightArrow] 1-based sample index of each window          *)
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
      low = energies[[k, 2]];
      high = energies[[k, 3]];
      side = (low + high) / 2;

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
            oneBeepMin <= duration <= oneBeepMax,           1,       (* single beep *)            
            duration > twoBeepMin && duration <= twoBeepMax, 2,     (* double beep \[LongDash] kept as 1 matching Kotlin "// 1 for now" *)
            duration > threeBeepMin && duration <= threeBeepMax, 3, (* triple beep \[LongDash] kept as 1 matching Kotlin "// 1 for now" *)
            duration > fourBeepMin && duration <= fourBeepMax, 4,   (* triple beep \[LongDash] kept as 1 matching Kotlin "// 1 for now" *)
            True,                                            0      (* too short or too long *)
          ];
          AppendTo[beeps,
            <|
              "startTime" -> (beepStartSample - 1) / N[sampleRate],  (* convert 1-based idx \[RightArrow] seconds *)
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
   \[Section] 4  Load audio  \[LongDash]  change the path below
   ---------------------------------------------------------------- *)

audioFile = "O:\\Temp\\FastBeeps.wav";    (* \[LeftArrow] SET THIS *)

audio  = Import[audioFile];
audio  = First[AudioSplit[audio, 4]];


fs         = Round[First @ AudioSampleRate[audio]];  (* verify sample rate *)
If[fs =!= sampleRate,
  Print["WARNING: file sample rate (", fs, " Hz) differs from sampleRate (",
        sampleRate, " Hz).  Update sampleRate above or resample the file."]
];

(* Re-scale \[PlusMinus]1 floats \[RightArrow] Int16 range so magThreshold is directly comparable
   to the Android value.  If the file is stereo, take channel 1. *)
rawSamples = N[AudioData[audio][[1]] * 32768.0];

(* ----------------------------------------------------------------
   \[Section] 5  Run detector
   ---------------------------------------------------------------- *)

result       = processAudio[rawSamples];
beeps        = result["beeps"];
energies     = result["energies"];
windowStarts = result["windowStarts"];

Print["Total windows analysed : ", Length[windowStarts]];
Print["Detected beeps         : ", Length[beeps]->Total["type"/.beeps]];



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
  Print["No beeps detected \[LongDash] lower magThreshold and re-run."]
];


(* ----------------------------------------------------------------
   \[Section] 6  Spectrogram with red beep-start markers
   ---------------------------------------------------------------- *)

totalDuration = Length[rawSamples] / N[sampleRate];
freqAxisMax   = 8000;  (* Hz shown on the frequency axis *)

(* Build spectrogram \[LongDash] SampleRate option ensures correct time axis *)
spec = Spectrogram[
  audio,512,171,
  PlotRange         -> {{0, totalDuration}, {0, freqAxisMax}},
  ColorFunction     -> "SolarColors",
  FrameLabel        -> {"Time (s)", "Frequency (Hz)"},
  PlotLabel         -> Style["Spectrogram  |  red lines = detected beep starts", 14],
  ImageSize         -> {500*5*totalDuration,600},
   AspectRatio -> Full,
   PlotRangePadding -> None
];

(* Vertical red lines at each beep start time *)
beepOverlay = If[Length[beeps] > 0,
  Graphics[{
        Green, Table[
      {Line[{{b["startTime"], 2500}, {b["startTime"], 2000}, {b["startTime"]+b["duration"], 2000}, {b["startTime"]+b["duration"], 2500}}],
      Text[b["type"]//ToString,{b["startTime"]+b["duration"]/2, 1800}]},
      {b, beeps}
    ]
  }],
  Graphics[]   (* nothing to draw if no beeps *)
];

Show[spec, beepOverlay]


(* ----------------------------------------------------------------
   \[Section] 7  Optional: energy trace plot (useful for threshold tuning)
   ---------------------------------------------------------------- *)

windowTimestamps = (windowStarts - 1) / N[sampleRate];   (* seconds *)
mainTrace = energies[[All, 1]];
sideTrace1 = energies[[All, 2]];
sideTrace2 = energies[[All, 3]];

llp=ListPlot[
  {
    Transpose[{windowTimestamps, Log10@mainTrace}],
    Transpose[{windowTimestamps, Log10@sideTrace1}],
    Transpose[{windowTimestamps, Log10@sideTrace2}],
    (* Threshold lines as degenerate data sets for the legend *)
    {{0, Log10@magThreshold},       {totalDuration, Log10@magThreshold}},
    {{0, Log10@magThresholdEnd},    {totalDuration, Log10@magThresholdEnd}}
  },
  Joined        -> True,
  PlotStyle     -> {Blue, Gray, Gray, {Red, Dashed}, {Orange, Dashed}},
  ImageSize     -> 500*5*totalDuration,
   AspectRatio -> Full,
  PlotRange     -> {{0,totalDuration},Automatic},
   PlotRangePadding -> None
]

dtplot = mainTrace*2/(sideTrace1+sideTrace2) * (If[#>=0,1,NaN]&)/@(mainTrace-magThresholdEnd);

dtp=ListPlot[
  {
    Transpose[{windowTimestamps, dtplot}],
    (* Threshold lines as degenerate data sets for the legend *)
    {{0, dominanceThreshold},       {totalDuration, dominanceThreshold}},
    {{0, dominanceThresholdEnd},    {totalDuration, dominanceThresholdEnd}}
  },
  Joined        -> True,
  PlotStyle     -> {Blue,{Red, Dashed}, {Orange, Dashed}},
  ImageSize     -> 500*5*totalDuration,
   AspectRatio -> Full,
  PlotRange     -> {{0,totalDuration},{0,5}},
   PlotRangePadding -> None
]



3



  PlotLegends   -> {"main energy", "side energy", "magThreshold", "magThresholdEnd"},
  FrameLabel    -> {"Time (s)", "Goertzel energy"},
  PlotLabel     -> "Energy trace \[LongDash] adjust magThreshold until red dashed line bisects beep peaks",


 500*5*totalDuration/2.5


GraphicsColumn[
 {Show[spec, beepOverlay, beepOverlay, ImagePadding -> {{40, 10}, {Automatic, Automatic}}], 
  Show[llp, ImagePadding -> {{40, 10}, {Automatic, Automatic}}],
  Show[dtp, ImagePadding -> {{40, 10}, {Automatic, Automatic}}]},
ImageSize     -> 500*5*totalDuration/2]



