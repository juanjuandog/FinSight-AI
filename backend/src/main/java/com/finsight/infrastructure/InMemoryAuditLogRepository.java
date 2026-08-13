package com.finsight.infrastructure;

import com.finsight.domain.model.AuditLogEntry;
import com.finsight.domain.repository.AuditLogRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!postgres")
public class InMemoryAuditLogRepository implements AuditLogRepository {
    private static final int MAX_ENTRIES = 1000;
    private final AtomicLong sequence = new AtomicLong();
    private final List<AuditLogEntry> entries = Collections.synchronizedList(new ArrayList<>());

    @Override
    public AuditLogEntry save(AuditLogEntry entry) {
        AuditLogEntry stored = new AuditLogEntry(
                sequence.incrementAndGet(),
                entry.eventType(),
                entry.actor(),
                entry.clientKey(),
                entry.resource(),
                entry.detail(),
                entry.status(),
                entry.createdAt()
        );
        synchronized (entries) {
            entries.add(0, stored);
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }
        }
        return stored;
    }

    @Override
    public List<AuditLogEntry> findRecentByEventType(String eventType, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        synchronized (entries) {
            return entries.stream()
                    .filter(entry -> eventType.equals(entry.eventType()))
                    .limit(bounded)
                    .toList();
        }
    }

    @Override
    public List<AuditLogEntry> findRecentByActor(String actor, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        synchronized (entries) {
            return entries.stream()
                    .filter(entry -> actor.equals(entry.actor()))
                    .limit(bounded)
                    .toList();
        }
    }
}
