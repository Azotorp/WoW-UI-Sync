package io.github.azotorp.wowuisync.common;

/** Application and protocol version constants (DESIGN.md §10.3). */
public final class Version {
    /** Human-readable application version. */
    public static final String APP_VERSION = "1.0.0";
    /** Wire protocol version. Bump on any incompatible protocol change. */
    public static final int PROTOCOL_VERSION = 1;
    /** Oldest peer protocol version this build can talk to. */
    public static final int MIN_PROTOCOL_VERSION = 1;
    /** App version that introduced {@link #MIN_PROTOCOL_VERSION}, shown in "please update" messages. */
    public static final String MIN_APP_VERSION = "1.0.0";

    public static final String APP_NAME = "WoW UI Sync";

    public static final String DISCLAIMER =
            "WoW UI Sync is not affiliated with or endorsed by Blizzard Entertainment. "
            + "World of Warcraft and WoW are trademarks of Blizzard Entertainment, Inc.";

    public static final String LICENSE_NOTICE =
            "This program is free software: you can redistribute it and/or modify it under the terms of the "
            + "GNU General Public License as published by the Free Software Foundation, either version 3 of the "
            + "License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY. "
            + "See the LICENSE file or <https://www.gnu.org/licenses/> for details.";

    private Version() {}
}
