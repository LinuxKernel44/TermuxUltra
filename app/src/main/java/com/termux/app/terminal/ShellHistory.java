package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the command history files of bash and zsh from the Termux home directory. */
public class ShellHistory {

    /** A command found in a history file. */
    public static class Entry {
        public final String command;
        /** The shell whose history file contains the command, "bash" or "zsh". */
        public final String shell;

        Entry(String command, String shell) {
            this.command = command;
            this.shell = shell;
        }
    }

    private static final int MAX_ENTRIES_PER_FILE = 50000;

    /**
     * Read the history of all supported shells. Blocking, call it from a background thread.
     *
     * @return The commands, most recent first, without duplicates.
     */
    @NonNull
    public static List<Entry> load() {
        List<Entry> bash = read(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".bash_history"), "bash", false);
        List<Entry> zsh = read(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".zsh_history"), "zsh", true);

        // Each list is oldest first, keep the most recent occurrence of each command.
        List<Entry> merged = new ArrayList<>(bash.size() + zsh.size());
        merged.addAll(bash);
        merged.addAll(zsh);
        Collections.reverse(merged);

        Set<String> seen = new HashSet<>();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : merged) {
            if (seen.add(entry.command)) result.add(entry);
        }
        return result;
    }

    private static List<Entry> read(File file, String shell, boolean zsh) {
        List<Entry> entries = new ArrayList<>();
        if (!file.isFile() || !file.canRead()) return entries;

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder command = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (command == null) {
                    if (zsh) {
                        // Extended history format: ": 1700000000:0;command"
                        if (line.startsWith(": ")) {
                            int separator = line.indexOf(';');
                            if (separator > 0) line = line.substring(separator + 1);
                        }
                    } else if (line.startsWith("#") && line.length() > 1 && Character.isDigit(line.charAt(1))) {
                        continue; // Bash timestamp line, when HISTTIMEFORMAT is set.
                    }
                    command = new StringBuilder();
                } else {
                    command.append('\n');
                }

                // zsh stores multi-line commands with a backslash at the end of each continued line.
                if (zsh && line.endsWith("\\")) {
                    command.append(line, 0, line.length() - 1);
                    continue;
                }
                command.append(line);

                String text = command.toString().trim();
                command = null;
                if (!text.isEmpty()) entries.add(new Entry(text, shell));
            }
            if (command != null) {
                String text = command.toString().trim();
                if (!text.isEmpty()) entries.add(new Entry(text, shell));
            }
        } catch (IOException ignored) {
            // Unreadable history file, return what was read.
        }

        if (entries.size() > MAX_ENTRIES_PER_FILE)
            return new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES_PER_FILE, entries.size()));
        return entries;
    }

}
