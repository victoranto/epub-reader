# EPUB Audiobook (Android)

A small Android app that turns an EPUB into an audiobook using the phone's own text-to-speech voices (free and offline).

## Features
- **Open EPUB**: with the button, or via "Open with…" from a file manager.
- **Listen**: reads the book chapter by chapter, with pause, previous/next and speed control (0.5x–2x). Tap a chapter to start from there.
- **Export**: creates one `.m4a` per chapter (`01 - Title.m4a`, …) in a folder named after the book, inside the folder you pick. Play them later in any audio player.

The voice language comes from the EPUB metadata (`dc:language`). For the best quality, go to Settings → System → Languages → Text-to-speech output, choose "Speech Services by Google" and download the high-quality voice for your language.

## Building
- GitHub Actions builds a debug APK on every push (*Actions* tab → latest run → `epub-audiolibro-apk` artifact).
- Or open the folder in Android Studio and press Run.

## Limitations (v0.1)
- Export runs while the app is open (the screen is kept on). Long books can take a while.
- Images and footnotes are ignored.
