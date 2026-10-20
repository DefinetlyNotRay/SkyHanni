# JellyShort 0.3.0

Standalone client-only Fabric mod for Minecraft 26.1–26.1.2, Java 25. Requires Fabric API for your Minecraft version. Does not require SkyHanni.

Put the built JAR and Fabric API into your instance's `mods` folder.

Full-height Jellybeans are now detected automatically from their named helmet items (`magicjellybean1` in the supplied Hypixel dump). The default `shorten` mode retains the bottom two entity tiers. Change this with `/jellyshort height 1` through `8`. No marking is required.

Version 0.3.0 also suppresses the upper sugar cane block models in those columns, keeping the chosen number of actual cane blocks from the cane base. Includes vanilla and optional Sodium render hooks. Visibility changes rebuild affected chunk sections automatically. Block state and hitboxes remain intact. `/jellyshort off` restores both entity pieces and cane meshes. The inspect summary includes the number of selected cane blocks for troubleshooting.

Detection requires ten consecutive vertical tiers in one block column. This matches the supplied full-height model; it is a visual size check, not a measurement of the server's growth counter. Shorter and partially loaded plants remain visible. Automatic filtering affects only armor stands wearing Jellybean-named heads.

`/jellyshort auto` toggles automatic detection. Manual fallback: aim at the soil immediately below the base or the lowest entity piece and run `/jellyshort mark`.

`/jellyshort hide` hides model pieces in marked columns; `/jellyshort off` restores rendering. `/jellyshort shorten` enables shortening again. Aim at the same column and use `/jellyshort unmark` to remove a mark, or `/jellyshort clear` to remove all marks. Marks and settings are session-only; marks clear when the client world changes. Unmark harvested or replaced plants.

Manual marking hides armor stands and block/item display entities in the marked column up to 12 blocks above the chosen base, including unrelated decorations within that column. Automatic detection avoids this broad filter. The mod does not alter blocks, hitboxes, server growth, rewards, or harvesting. In-game verification of this update on Hypixel is still required. `/jellyshort inspect` prints nearby entity types, exact positions, and helmet names in chat.

Build: `gradlew.bat build` (26.1.2); `gradlew.bat build '-Pminecraft_version=26.1'` (26.1, PowerShell). JARs are in `build/libs`. Install only one JellyShort JAR; replace older versions.
