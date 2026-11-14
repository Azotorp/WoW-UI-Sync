package io.github.azotorp.wowuisync.common.profile;
import java.util.*;
/** Directory identifiers are storage identities, never expansion display labels. */
public final class Profiles {
    public static final Map<String,String> DISPLAY = Map.ofEntries(
        Map.entry("forever","WoW Forever"),Map.entry("retail","Retail"),Map.entry("classic","Progression Classic"),
        Map.entry("anniversary","Classic Anniversary"),Map.entry("classic_era","Classic Era / Hardcore / SoD"),
        Map.entry("classic_titan","Classic Titan Reforged"),Map.entry("ptr","Retail PTR"),Map.entry("xptr","Retail PTR 2"),
        Map.entry("beta","Retail Beta"),Map.entry("classic_ptr","Classic PTR"),Map.entry("classic_era_ptr","Classic Era PTR"),
        Map.entry("classic_beta","Classic Beta"));
    public static final Set<String> ENABLED = DISPLAY.keySet();
    public static final List<String> WOW_EXECUTABLE_NAMES = List.of("Wow.exe","WowClassic.exe","WowClassicT.exe","WowT.exe","WowB.exe","World of Warcraft","World of Warcraft Classic");
    public static String profileIdFromFolder(String folder) { return folder.replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT); }
    public static boolean isValidProfileId(String id) { return id!=null && id.matches("[a-z0-9_]{1,40}"); }
    public static boolean isEnabled(String id) { return id!=null && ENABLED.contains(id); }
    public static String displayName(String id) { return DISPLAY.getOrDefault(id,id==null ? "Unknown" : id.replace('_',' ')); }
    private Profiles() {}
}
