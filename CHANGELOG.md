# Our Music 1.0.8

Opening the full player smoothly fades the navigation page and playback controls while preserving the app background beneath the glass. A selected background photo stays visible. When no photo is selected, the current song's artwork becomes the player background and fades in with the page.

# Our Music 1.0.7

Exported lyrics flow into place before each saved cue and become fully highlighted at the cue time. Smooth motion and fading replace the delayed settling animation. Wrapped lines, closely spaced cues and seeking use the same song timeline in the live preview and saved video.

The video editor adds an optional Music progress bar. Its animated playhead, elapsed time and remaining time follow the song in the preview and export. The setting is remembered, and lyrics leave space for the bar when it is enabled.

# Our Music 1.0.6

Export music video turns a complete offline song into a 60 FPS MP4 with its audio, visualization and synchronized lyrics. Landscape 16:9 and portrait 9:16 layouts support 720p and 1080p. Moving Aurora, Midnight and Sunset backgrounds, album artwork and a custom photo are available. A live preview follows the song using the same renderer as export. Export continues in the background with progress and cancellation controls, then saves to Movies/OurMusic for opening or sharing.

Spectrum sphere adds a luminous audio reactive circular spectrum with animated inner filaments and particles to playback and video export. Video styles also include the frequency spectrum, waveform and digital oscilloscope. Neon, Soft and Prism presets, accent palettes, glow, response intensity, visualization size, lyrics size, background dimming and motion can be adjusted. Lyrics follow saved cue timing and the user's playback adjustment; unavailable timing is reported before export.

Playback progress updates independently of the full player, and audio plots keep their drawing in separate display lists. Glass surfaces reuse unchanged decoration and avoid redundant buffers while preserving the material effects and nested captures.

Video rendering uses a GPU canvas on supported devices to avoid drawing and copying full resolution CPU bitmaps for every frame. Every completed export keeps a fixed 60 FPS timeline, with its frame count and timestamps checked before saving. Slower or throttled rendering takes longer without skipping frames. Audio encoder delay is measured and corrected so the saved audio remains aligned with the visualization and lyrics.

# Our Music 1.0.5

Player options adds an oscilloscope with a virtual instrument screen. Its stereo traces preserve native decoded samples against an adjustable time and amplitude grid. Controls include trigger source, slope and level, Auto and Normal acquisition, Hold, an expanded screen and measurement cursors. Sampled peak, RMS and peak to peak readings use the displayed audio. Digital full scale is the default amplitude unit; voltage labels require an explicit user calibration and do not measure the physical output.

The spectrum, waveform and oscilloscope use overlapping audio analysis at 60 updates per second and presentation aligned with display frames. Audio frames stay available across decoder bursts, and changing traces redraw without recomposing the player controls.

Lyrics style can be set to Default or Animated smooth. The smooth style adds gentle emphasis and flowing movement without changing line wrapping. You can browse lyrics freely and return to the current line with Follow.

Slider thumbs and filled tracks now follow the finger immediately during a drag. Glass buttons share a spring press response, including brief taps, and playback controls use the same treatment.

Glass captures reuse their current frame instead of drawing the same content twice. Nested glass, refraction, blur and highlights retain their appearance. The music notification uses the app accent where supported; Android and the device system control its outer surface.

Returning to the app clears stale playback service bindings before visualization subscriptions resume.

# Our Music 1.0.4

Player options includes Ambient mode, an in app Car mode with larger controls, a live frequency spectrum and an actual stereo audio waveform. Visuals follow decoded song audio and stop when playback pauses. Ambient colors come from the current artwork.

Streamed music cache can be cleared from Player options while keeping downloaded songs, your library and background photos.

Streaming and downloads use matching client headers and validate fresh audio links before opening them. Expired or rejected links are refreshed once, and incomplete cached audio no longer truncates playback. When the service requires verification or restricts a track, the app shows its reason instead of a generic playback error.

# Our Music 1.0.3

Playback, volume and background adjustment sliders use animated glass capsules. Touching a control expands its thumb and reveals live refraction; movement responds to drag speed and settles smoothly on release.

Switches use the same motion and glass treatment throughout Settings, Updates and the background editor. Sliders and switches support keyboard input, accessibility actions and right to left layouts.

Text uses clean lettering with consistent foreground colors. A continuous material softens busy background photos enough for readable text, replacing glyph outlines while preserving the photo and live glass effect.

# Our Music 1.0.2

Back closes the full player before navigating away from the current page. Album artwork, lyrics and queue views now block touches and scrolling on the page behind them, including while the player closes.

Song menus and the background editor keep their own Back behavior. Background controls are excluded from keyboard and accessibility focus while a modal is open.

Search results have more space between the search field and the Songs, Albums, Artists and other type buttons.

# Our Music 1.0.1

Our Music now uses its own application package and signing certificate, with a standalone source repository.

Text and icons adapt to the photo behind their position. The glass navigation indicator moves smoothly between tabs, and shared controls use a consistent visual style.

Songs can be downloaded into permanent private storage for offline listening. The Downloaded library shows progress, retries, cancellation and removal. Saved audio bypasses network resolution during playback.

Timed lyrics follow the song, highlight the current line and support tapping a line to seek. Timing can be adjusted for each song. Saved lyrics remain available offline, with plain lyrics shown when timing is unavailable.

Settings includes automatic update checks, release notes, verified APK downloads and installation through Android. Background photos can be moved and scaled with a live glass preview.
