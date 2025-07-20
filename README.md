<p align="center">
  <img src="https://github.com/CinemaMod/mcef/assets/30220598/938896d7-2589-49df-8f82-29266c64dfb7" alt="MCEF Logo" style="width:66px;height:66px;">
</p>

# MCEF (Minecraft Chromium Embedded Framework for LiquidBounce)

A lightweight fork of MCEF designed specifically for integration with LiquidBounce. This barebone library provides essential Chromium web browser functionality for Minecraft.

MCEF is based on java-cef (Java Chromium Embedded Framework), which is based on CEF (Chromium Embedded Framework), which is based on Chromium. Originally created by montoyo and rewritten by the CinemaMod Group, this version has been streamlined for LiquidBounce integration.

The library includes a downloader system for retrieving the necessary java-cef & CEF binaries required by the Chromium browser. This requires a connection to https://api.liquidbounce.net/, as well as Cloudflare Storage.

Current Chromium version: `122.0.6261.112`

## Supported Platforms
- Windows 10/11 (x86_64, arm64)*
- macOS 11 or greater (Intel, Apple Silicon)
- GNU Linux glibc 2.31 or greater (x86_64, arm64)**

*Note: Some antivirus software may prevent MCEF from initializing. You may need to disable your antivirus or whitelist the mod files for proper functionality.

**This library will not work on Android.

## For Modders
MCEF is LGPL, as long as your project doesn't modify or include MCEF source code, you can choose a different license. See the full license in the LICENSE file.

### Using MCEF in Your Project
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```
```gradle
dependencies {
    modImplementation 'com.github.CCBlueX:mcef:3.1.0-1.21.4'
}
```

### Building & Modifying MCEF
After cloning this repo, you will need to clone the java-cef git submodule using the provided gradle task: `./gradlew cloneJcef`.

## Fork Hierarchy
- [CCBlueX/mcef](https://github.com/CCBlueX/mcef) - Current LiquidBounce-optimized version
- [CinemaMod/mcef](https://github.com/CinemaMod/mcef)
- [montoyo/mcef](https://github.com/montoyo/mcef)

