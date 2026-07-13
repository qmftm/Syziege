package com.syziege.war;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * When capture combat is allowed, based on the real-world clock. Configured
 * under {@code war:} with a timezone plus weekly (day + time range) and
 * specific-date windows. If restriction is disabled, war is always active.
 */
public final class WarSchedule {

    private record Weekly(DayOfWeek day, LocalTime start, LocalTime end) {
    }

    private record Dated(LocalDate date, LocalTime start, LocalTime end) {
    }

    private final boolean restrict;
    private final ZoneId zone;
    private final List<Weekly> weekly = new ArrayList<>();
    private final List<Dated> dated = new ArrayList<>();

    public WarSchedule(ConfigurationSection section, Logger logger) {
        if (section == null) {
            this.restrict = false;
            this.zone = ZoneId.systemDefault();
            return;
        }
        this.restrict = section.getBoolean("enabled", false);
        this.zone = parseZone(section.getString("timezone"), logger);

        for (Map<?, ?> entry : section.getMapList("schedule")) {
            DayOfWeek day = parseDay(str(entry.get("day")));
            LocalTime start = parseTime(str(entry.get("start")));
            LocalTime end = parseTime(str(entry.get("end")));
            if (day == null || start == null || end == null || !start.isBefore(end)) {
                logger.warning("Ignoring invalid war schedule entry: " + entry);
                continue;
            }
            weekly.add(new Weekly(day, start, end));
        }

        for (Map<?, ?> entry : section.getMapList("dates")) {
            LocalDate date = parseDate(str(entry.get("date")));
            LocalTime start = parseTime(str(entry.get("start")));
            LocalTime end = parseTime(str(entry.get("end")));
            if (date == null || start == null || end == null || !start.isBefore(end)) {
                logger.warning("Ignoring invalid war date entry: " + entry);
                continue;
            }
            dated.add(new Dated(date, start, end));
        }
    }

    /** True when capture is allowed right now. */
    public boolean active() {
        if (!restrict) {
            return true;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        for (Dated window : dated) {
            if (window.date().equals(date) && inRange(time, window.start(), window.end())) {
                return true;
            }
        }
        for (Weekly window : weekly) {
            if (now.getDayOfWeek() == window.day() && inRange(time, window.start(), window.end())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a schedule restriction is in effect at all. */
    public boolean restricted() {
        return restrict;
    }

    private static boolean inRange(LocalTime t, LocalTime start, LocalTime end) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    private static ZoneId parseZone(String id, Logger logger) {
        if (id == null || id.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(id.trim());
        } catch (RuntimeException e) {
            logger.warning("Unknown war timezone '" + id + "', using server default");
            return ZoneId.systemDefault();
        }
    }

    private static LocalTime parseTime(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalTime.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static DayOfWeek parseDay(String s) {
        if (s == null) {
            return null;
        }
        String d = s.trim().toUpperCase(Locale.ROOT);
        switch (d) {
            case "1": case "MON": case "MONDAY": case "월": case "월요일": return DayOfWeek.MONDAY;
            case "2": case "TUE": case "TUESDAY": case "화": case "화요일": return DayOfWeek.TUESDAY;
            case "3": case "WED": case "WEDNESDAY": case "수": case "수요일": return DayOfWeek.WEDNESDAY;
            case "4": case "THU": case "THURSDAY": case "목": case "목요일": return DayOfWeek.THURSDAY;
            case "5": case "FRI": case "FRIDAY": case "금": case "금요일": return DayOfWeek.FRIDAY;
            case "6": case "SAT": case "SATURDAY": case "토": case "토요일": return DayOfWeek.SATURDAY;
            case "7": case "SUN": case "SUNDAY": case "일": case "일요일": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
