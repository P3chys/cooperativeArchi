package io.github.p3chys.cooperativearchi.ui;

import java.nio.file.Path;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jgit.lib.PersonIdent;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Settings. The GitHub token lives in Eclipse secure storage (Windows DPAPI / macOS Keychain), everything else
 * in normal preferences.
 */
public final class Prefs {

    public static final String FOLDER = "folder";
    public static final String NAME = "authorName";
    public static final String EMAIL = "authorEmail";
    public static final String LOGIN = "githubLogin";
    public static final String SYNC_ON_SAVE = "syncOnSave";
    public static final String SYNC_MINUTES = "syncMinutes";
    public static final String ASK_MESSAGE = "askMessage";
    /** Live collaboration: autosave after a short pause, sync on save, look for colleagues' changes every few seconds. */
    public static final String LIVE = "live";
    public static final String LIVE_IDLE_TENTHS = "liveIdleTenths";
    public static final String LIVE_POLL_SECONDS = "livePollSeconds";
    public static final int DEFAULT_LIVE_IDLE_TENTHS = 15;
    public static final int DEFAULT_LIVE_POLL_SECONDS = 3;
    private static final String SECURE_NODE = Activator.ID;
    private static final String TOKEN = "githubToken";

    private Prefs() {
    }

    public static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(Activator.ID);
    }

    public static String get(String key, String def) {
        return node().get(key, def);
    }

    public static boolean flag(String key, boolean def) {
        return node().getBoolean(key, def);
    }

    public static int number(String key, int def) {
        return node().getInt(key, def);
    }

    public static void put(String key, String value) {
        if (value == null) {
            node().remove(key);
        } else {
            node().put(key, value);
        }
        flush();
    }

    public static void put(String key, boolean value) {
        node().putBoolean(key, value);
        flush();
    }

    public static void put(String key, int value) {
        node().putInt(key, value);
        flush();
    }

    private static void flush() {
        try {
            node().flush();
        } catch (BackingStoreException e) {
            Activator.log("Cannot save preferences", e);
        }
    }

    /** Where shared models are kept, one sub folder per model. */
    public static Path folder() {
        String def = Path.of(System.getProperty("user.home"), "Documents", "Archi", "cooperativeArchi").toString();
        return Path.of(get(FOLDER, def));
    }

    public static PersonIdent author() {
        String user = System.getProperty("user.name", "analyst");
        String name = get(NAME, "");
        String email = get(EMAIL, "");
        return new PersonIdent(name.isBlank() ? user : name, email.isBlank() ? user + "@users.noreply.github.com" : email);
    }

    public static String token() {
        try {
            return SecurePreferencesFactory.getDefault().node(SECURE_NODE).get(TOKEN, null);
        } catch (StorageException e) {
            Activator.log("Cannot read the GitHub token from secure storage", e);
            return null;
        }
    }

    public static void setToken(String token) {
        try {
            ISecurePreferences node = SecurePreferencesFactory.getDefault().node(SECURE_NODE);
            if (token == null || token.isBlank()) {
                node.remove(TOKEN);
            } else {
                node.put(TOKEN, token.trim(), true);
            }
            node.flush();
        } catch (Exception e) {
            Activator.log("Cannot store the GitHub token in secure storage", e);
        }
    }
}
