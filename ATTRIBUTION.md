# Our Music source attribution

Our Music is an independent derivative application. Its application ID is
`net.omusic.unreliablecode`.

- Music player foundation: [Music You](https://github.com/DanielSevillano/music-you),
  commit `f91e594c2cf1da4000058b959e76432950066ea5`, licensed under GNU GPL 3.0.
  The original license is retained in `LICENSE`.
- Glass renderer: [AndroidLiquidGlass](https://github.com/unreliablecode/AndroidLiquidGlass),
  commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`, licensed under Apache 2.0.
  The original license is retained in `backdrop/LICENSE`; adaptation details are
  documented in `backdrop/UPSTREAM.md`.
  Our Music also adapts the catalog's LiquidSlider, LiquidToggle and damped drag
  animation for its player and settings controls, with Android input and
  accessibility support.
- Request serialization and locale corrections follow the changes proposed in
  [Music You pull request 112](https://github.com/DanielSevillano/music-you/pull/112).

Apple Music is a visual reference. Our Music is not affiliated with Apple,
YouTube, or Google. Existing third-party library licenses continue to apply.
