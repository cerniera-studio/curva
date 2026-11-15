# Curva

Arsenal at a glance on the Galaxy Z Fold 8 Ultra: kick-off in UK time and where it's on, then each match as
one curve that bends with every goal. Built on Cerniera's [Kit](https://github.com/cerniera-studio/kit) to
the Bellezza Necessaria mandate.

- Data from [football-data.org](https://www.football-data.org), with your own key entered in settings (never
  stored in this repo). The free plan gives fixtures and results with scores arriving late; live scores and
  goal times need a paid plan, and without goal times the curve runs dashed through the half-time and
  full-time scores.
- Every shape of the Fold: the upright cover is one column; the turned cover and the main screen are two
  panes, the chosen match's curve filling the second.
- A demo mode with invented fixtures shows the shapes before a key is added (and is what the emulator uses).

GitHub Actions builds the APK on every push, runs the curve's tests, opens it on an emulator in the four
shapes, and publishes each build as a release with `Curva.apk` (branch builds as pre-releases).
