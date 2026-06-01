package dev.sirdexal.events;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Structured logging for SirDexal Events — mirrors ManhuntLog pattern.
 * Writes to both SLF4J (server console) and a file log.
 */
public class EventsLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(SirdexalEvents.MOD_ID);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter fileWriter;

    public static void init() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("sirdexal-events");
            Files.createDirectories(dir);
            Path logFile = dir.resolve("logs.txt");
            fileWriter = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
        } catch (IOException e) {
            LOGGER.error("[SirdexalEvents] Failed to init file logger", e);
        }
    }

    public static void info(String msg, Object... args) {
        String formatted = format(msg, args);
        LOGGER.info(formatted);
        writeFile("INFO", formatted);
    }

    public static void warn(String msg, Object... args) {
        String formatted = format(msg, args);
        LOGGER.warn(formatted);
        writeFile("WARN", formatted);
    }

    public static void error(String msg, Exception e) {
        LOGGER.error(msg, e);
        writeFile("ERROR", msg + " — " + e.toString());
    }

    public static void debug(String msg, Object... args) {
        LOGGER.debug(format(msg, args));
    }

    public static Logger slf4j() {
        return LOGGER;
    }

    public static void close() {
        if (fileWriter != null) {
            fileWriter.flush();
            fileWriter.close();
        }
    }

    private static void writeFile(String level, String msg) {
        if (fileWriter != null) {
            fileWriter.println("[" + LocalDateTime.now().format(FMT) + "] [" + level + "] " + msg);
        }
    }

    private static String format(String template, Object... args) {
        if (args.length == 0) return template;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        for (int i = 0; i < template.length(); i++) {
            if (i + 1 < template.length() && template.charAt(i) == '{' && template.charAt(i + 1) == '}' && argIdx < args.length) {
                sb.append(args[argIdx++]);
                i++; // skip '}'
            } else {
                sb.append(template.charAt(i));
            }
        }
        return sb.toString();
    }
}
