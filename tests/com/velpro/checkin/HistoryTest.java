package com.velpro.checkin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
public class HistoryTest {
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try { type = Class.forName("com.velpro.checkin.History"); }
        catch (ClassNotFoundException e) { throw new AssertionError("missing persistent History implementation", e); }
        File file = Files.createTempDirectory("checkin-history-test").resolve("history.jsonl").toFile();
        Object h = type.getConstructor(File.class).newInstance(file);
        Method add = type.getMethod("add", String.class, boolean.class, String.class);
        Method recent = type.getMethod("recent", int.class);
        add.invoke(h, "77625", true, "+5000 points");
        add.invoke(h, "AgentRouter", false, "HTTP 429");
        @SuppressWarnings("unchecked") List<String> rows = (List<String>) recent.invoke(h, 30);
        check(rows.size() == 2, "must retain same-day success and failure attempts");
        check(rows.get(0).contains("HTTP 429") && rows.get(0).contains("✗"), "most recent failure missing");
        check(rows.get(1).contains("5000") && rows.get(1).contains("✓"), "reward result missing");
        String raw = Files.readString(file.toPath());
        check(raw.contains("\"date\"") && raw.contains("\"time\""), "dates and times not persisted");
        Object reopened = type.getConstructor(File.class).newInstance(file);
        check(((List<?>) recent.invoke(reopened, 30)).size() == 2, "records lost after app restart");
        Files.writeString(file.toPath(), "corrupt row\n", StandardOpenOption.APPEND);
        check(((List<?>) recent.invoke(reopened, 30)).size() == 2, "corrupt row hides valid records");
        check(((List<?>) recent.invoke(reopened, 1)).size() == 1, "recent limit ignored");
        Files.delete(file.toPath()); Files.delete(file.toPath().getParent());
        System.out.println("PASS HistoryTest: persistence, same-day attempts, failures, ordering, corrupt rows, limit");
    }
}
