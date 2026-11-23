package kr.tvqt.uploader;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

final class Plan {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 23);
    static final LocalTime OPEN = LocalTime.of(6, 0);
    static final LocalTime RELEASE = LocalTime.of(7, 10);
    static final long LEASE = 150_000L;
    static final long TABLET_ALIVE = 120_000L;
    static final long GRACE = 45_000L;
    static final String[] KINDS = {"qt", "main", "short"};

    static int firstRow(LocalDate day) {
        return Math.toIntExact(2 + 3 * ChronoUnit.DAYS.between(FIRST_DAY, day));
    }
    static boolean scheduled(LocalDate day, String kind) {
        if ("qt".equals(kind)) return true;
        if (!Arrays.asList("main","short").contains(kind)) return false;
        switch(day.getDayOfWeek()) {
            case MONDAY: case TUESDAY: case THURSDAY: case SATURDAY: return true;
            default: return false;
        }
    }
    static Instant release(LocalDate day) {
        return day.atTime(RELEASE).atZone(SEOUL).toInstant();
    }
    static boolean inWindow(Instant now) {
        LocalTime kst = now.atZone(SEOUL).toLocalTime();
        return !kst.isBefore(OPEN) && kst.isBefore(RELEASE);
    }
    static long nextDelay(Instant now) {
        LocalTime time = now.atZone(SEOUL).toLocalTime();
        if (inWindow(now) || (!time.isBefore(RELEASE) && time.isBefore(RELEASE.plusMinutes(15)))) return 45_000L;
        ZonedDateTime here = now.atZone(SEOUL);
        LocalDate next = here.toLocalDate();
        if (!here.toLocalTime().isBefore(OPEN)) next = next.plusDays(1);
        return Math.max(1000L,Duration.between(now,next.atTime(OPEN).atZone(SEOUL)).toMillis());
    }
    static boolean phoneMayTake(long now, long lastTablet, long firstSeen, boolean yielded) {
        return yielded || ((lastTablet <= 0 || now-lastTablet > TABLET_ALIVE || lastTablet-now > TABLET_ALIVE)
                && now-firstSeen >= GRACE);
    }
}
