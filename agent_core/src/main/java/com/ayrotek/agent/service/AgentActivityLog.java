package com.ayrotek.agent.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ajana gelen yayın ve sorgu isteklerinin son kayıtları; yalnızca yerel panelde görünür. Değer veya sonuç tutulmaz. */
@Service
public class AgentActivityLog {

    private static final int LIMIT = 200;

    public record Entry(Instant at, String kind, String jobId, String table, String column, String operation, boolean ok, String message) {}

    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void record(String kind, String jobId, String table, String column, String operation, boolean ok, String message) {
        entries.addFirst(new Entry(Instant.now(), kind, jobId, table, column, operation, ok, message));
        while (entries.size() > LIMIT) {
            entries.removeLast();
        }
    }

    public synchronized List<Entry> recent() {
        return new ArrayList<>(entries);
    }
}
