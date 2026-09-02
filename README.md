# KisshKitty

A modern SSH client for Android with Kitty image protocol support.

## Features

- **Full SSH Support**: Password and public key authentication
- **Kitty Image Protocol**: Display images inline in the terminal
- **Modern UI**: Built with Jetpack Compose and Material 3
- **Multiple Hosts**: Save and manage multiple SSH connections
- **Terminal Emulation**: Full VT100/ANSI support with 256 colors
- **Special Keys**: Quick access to common terminal keys (Tab, Ctrl+C, arrows)

## Architecture

- **Kotlin** - Primary language
- **Jetpack Compose** - Declarative UI
- **sshj** - SSH protocol implementation
- **Room** - Local database for host storage
- **Hilt** - Dependency injection
- **Coroutines** - Asynchronous operations

## Modules

- `:app` - Main application module
- `:core:ssh` - SSH connection management
- `:core:terminal` - Terminal emulation
- `:core:kitty` - Kitty image protocol parser and renderer

## Building

```bash
# Clone the repository
git clone https://github.com/darekkasan/android-ssh-kitty.git
cd android-ssh-kitty

# Build the APK
./gradlew assembleDebug
```

## Kitty Image Protocol

This app implements the Kitty graphics protocol for displaying images in the terminal. The protocol uses APC (Application Programming Command) escape sequences to transmit image data.

### Supported Features

- Image transmission (RGB, RGBA, PNG formats)
- Chunked transfer for large images
- Image display and placement
- Image deletion

### Usage

On the remote server, use tools that support Kitty graphics:

```bash
# Using chafa with Kitty protocol
chafa --format=kitty image.jpg

# Using imgcat (if installed)
imgcat image.png
```

## License

MIT License
