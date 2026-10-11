<p align="center">
  <img src="https://github.com/CinemaMod/mcef/assets/30220598/938896d7-2589-49df-8f82-29266c64dfb7" alt="MCEF Logo" style="width:66px;height:66px;">
</p>

# MCEF (Minecraft Chromium Embedded Framework)
MCEF is a mod and library for adding the Chromium web browser into Minecraft.

MCEF is based on java-cef (Java Chromium Embedded Framework), which is based on CEF (Chromium Embedded Framework), which is based on Chromium. It was originally created by montoyo. It was rewritten and currently maintained by the CinemaMod Group.

MCEF contains a downloader system for downloading the java-cef & CEF binaries required by the Chromium browser. This requires a connection to https://mcef-download.cinemamod.com.

Downloaded java-cef archives are checked against the SHA-256 value served by that HTTPS endpoint. After installation, MCEF records and validates the length and SHA-256 of every installed file so missing or modified files are repaired. This local integrity manifest detects later damage; it is not an additional source of authenticity.

The `skip-download` setting is an explicit offline/manual mode. It only starts with an installation that already has a valid MCEF integrity manifest for the current platform and java-cef commit. To migrate a manual installation safely, disable `skip-download` once so MCEF downloads and verifies the archive and creates the manifest. A retained local `<platform>.tar.gz` may only be used by tooling after its digest is checked against the trusted HTTPS checksum; never hash an arbitrary installed directory and label it archive-authenticated.

The checksum endpoint and every checksum redirect must remain HTTPS. Redirect loops and HTTPS-to-HTTP downgrade attempts are rejected. Custom mirrors therefore need a valid HTTPS checksum endpoint even if the archive itself is hosted separately.

Discussion: https://discord.gg/rNrh5kW8Ty

Current Chromium version: `116.0.5845.190`

## Supported Platforms
- Windows 10/11 (x86_64, arm64)*
- macOS 11 or greater (Intel, Apple Silicon)
- GNU Linux glibc 2.31 or greater (x86_64, arm64)**

*Some antivirus software may prevent MCEF from initializing. You may have to disable your antivirus or whitelist the mod files for MCEF to work properly.

**This mod will not work on Android.

## For Players
This is the source code for MCEF.

Download the mod for Fabric or Forge on either:
- CurseForge: https://www.curseforge.com/minecraft/mc-mods/mcef
- Modrinth: https://modrinth.com/mod/mcef

## For Modders
MCEF is LGPL, as long as your project doesn't modify or include MCEF source code, you can choose a different license. Read the full license in the LICENSE file in this directory.

### Using MCEF in Your Project
```
repositories {
    maven {
        url = uri('https://mcef-download.cinemamod.com/repositories/releases')
    }
    // Optional for snapshot versions
    maven {
        url = uri('https://mcef-download.cinemamod.com/repositories/snapshots')
    }
}
```
#### Fabric
```
dependencies {
    modCompileOnly 'com.cinemamod:mcef:2.1.6-1.20.1'
    modRuntimeOnly 'com.cinemamod:mcef-fabric:2.1.6-1.20.1'
}
```
See the [mcef-fabric-example-mod](https://github.com/CinemaMod/mcef-fabric-example-mod) for a complete example Fabric project.

#### NeoForge
```
dependencies {
    compileOnly fg.deobf('com.cinemamod:mcef:2.1.6-1.20.1')
    runtimeOnly fg.deobf('com.cinemamod:mcef-forge:2.1.6-1.20.1')
}
```
### Building & Modifying MCEF
After cloning this repo, you will need to clone the java-cef git submodule. There is a gradle task for this: `./gradlew cloneJcef`.

To run the Fabric client: `./gradlew fabricClient`
To run the Forge client: `./gradlew forgeClient`

In-game, there is a demo browser if you press F10 after you're loaded into a world (the demo browser only exists when you're running from a development environment).
