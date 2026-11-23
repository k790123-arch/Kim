package kr.tvqt.uploader;

import static org.junit.Assert.*;
import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;

public class PlanTest {
    @Test public void weekdaysAndIndependentSlots() {
        boolean[] extra={true,true,false,true,false,true,false};
        int count=0;
        for (int i=0;i<7;i++) {
            LocalDate date=Plan.FIRST_DAY.plusDays(i);
            assertEquals(2+i*3,Plan.firstRow(date));
            assertTrue(Plan.scheduled(date,"qt"));
            assertEquals(extra[i],Plan.scheduled(date,"main"));
            assertEquals(extra[i],Plan.scheduled(date,"short"));
            count+=1+(extra[i]?2:0);
        }
        assertEquals(15,count);
    }
    @Test public void publicationIsSeoulTimeEvenOnThePreviousUtcDay() {
        assertEquals(Instant.parse("2026-11-23T22:10:00Z"),Plan.release(LocalDate.of(2026,11,24)));
        assertFalse(Plan.inWindow(Instant.parse("2026-11-23T20:59:59Z")));
        assertTrue(Plan.inWindow(Instant.parse("2026-11-23T21:00:00Z")));
        assertTrue(Plan.inWindow(Instant.parse("2026-11-23T22:09:59Z")));
        assertFalse(Plan.inWindow(Instant.parse("2026-11-23T22:10:00Z")));
        assertEquals(60000L,Plan.nextDelay(Instant.parse("2026-11-23T20:59:00Z")));
        assertEquals(45_000L,Plan.nextDelay(Instant.parse("2026-11-23T22:11:00Z")));
        assertEquals(22*60*60*1000L,Plan.nextDelay(Instant.parse("2026-11-23T23:00:00Z")));
    }
    @Test public void tabletFirstUnlessOfflineOrItRelinquishes() {
        long now=1_000_000L;
        assertFalse(Plan.phoneMayTake(now,now-10_000L,now-90_000L,false));
        assertFalse(Plan.phoneMayTake(now,0,now-30_000L,false));
        assertTrue(Plan.phoneMayTake(now,0,now-45_000L,false));
        assertTrue(Plan.phoneMayTake(now,now-120_001L,now-90_000L,false));
        assertTrue(Plan.phoneMayTake(now,now,now,true));
    }
}
