/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.earasoft.prolly.rdf4j.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 *
 *
 * <h3>Reusable CPU flame-graph profiler — JDK-native, zero external dependency.</h3>
 *
 * <p>The CPU-frame counterpart to {@link TriejoinAllocSiteProfileTest}'s allocation-site profile,
 * and Phase 1 Step 5 (CPU variant) of {@code plans/benchmarking-and-bottleneck-methodology.md} (the
 * L2-CPU rung of the drill-down ladder). Runs any {@link Runnable} under JFR {@code
 * jdk.ExecutionSample}, folds the on-CPU Java stacks, and writes to {@code target/flames/}:
 *
 * <ul>
 *   <li>{@code <name>.collapsed.txt} — Brendan-Gregg folded stacks (the universal interchange
 *       format; drag into <a href="https://speedscope.app">speedscope.app</a>, or pipe to {@code
 *       flamegraph.pl} / {@code inferno-flamegraph});
 *   <li>{@code <name>.svg} — a self-contained static flame graph (root at the bottom, width ∝
 *       samples, {@code <title>} hover tooltips, no JavaScript) — viewable in any browser with no
 *       extra tooling.
 * </ul>
 *
 * <p>and returns the top self-CPU frames, ranked — the actionable bottleneck table.
 *
 * <h4>Warts (read before trusting a number)</h4>
 *
 * <ul>
 *   <li><b>Native/JNI time is INVISIBLE.</b> {@code jdk.ExecutionSample} samples threads executing
 *       <i>Java</i>; a thread inside a JNI call (e.g. RocksDB native) is not sampled, so its CPU is
 *       absent from this graph. For native-inclusive CPU — e.g. testing the flatsail "JNI crossing
 *       per key" hypothesis — use <b>async-profiler</b>, which is perf-based and sees native
 *       frames: {@code JmhRunner -prof "async:output=flamegraph;event=cpu"} (an opt-in native
 *       {@code .so}; JmhRunner already passes the flag through). async-profiler also has <b>no
 *       safepoint bias</b>.
 *   <li><b>Safepoint bias.</b> ExecutionSample stacks are captured at safepoints — trust the
 *       <i>method-level</i> ranking, not sub-method line attribution.
 * </ul>
 */
public final class CpuFlameProfiler {

    /**
     * {@code self} = samples with this frame on top (CPU spent <i>in</i> it); {@code total} =
     * inclusive.
     */
    public record FrameCost(String frame, long selfSamples, long totalSamples) {}

    public record Result(Path collapsed, Path svg, long samples, List<FrameCost> topSelf) {}

    /**
     * Profile {@code work}: warm it (JIT) for {@code warmup}, then sample {@code
     * jdk.ExecutionSample} at 1 ms while running it for {@code measure}. Writes the artifacts and
     * returns the ranked self-CPU frames.
     */
    public static Result profile(String name, Duration warmup, Duration measure, Runnable work)
            throws IOException {
        long warmEnd = System.nanoTime() + warmup.toNanos();
        while (System.nanoTime() < warmEnd) work.run();

        Path jfr = Files.createTempFile(name + "-cpu", ".jfr");
        try (Recording rec = new Recording()) {
            rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            rec.start();
            long measEnd = System.nanoTime() + measure.toNanos();
            while (System.nanoTime() < measEnd) work.run();
            rec.stop();
            rec.dump(jfr);
        }

        Map<String, Long> folded = new HashMap<>(); // "root;…;leaf" -> sample count
        long total = 0;
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) continue;
                List<RecordedFrame> frames = st.getFrames();
                StringBuilder sb = new StringBuilder();
                for (int i = frames.size() - 1;
                        i >= 0;
                        i--) { // JFR is leaf-first → reverse to root..leaf
                    RecordedFrame f = frames.get(i);
                    if (f.getMethod() == null) continue;
                    if (sb.length() > 0) sb.append(';');
                    sb.append(frameLabel(f));
                }
                if (sb.length() == 0) continue;
                folded.merge(sb.toString(), 1L, Long::sum);
                total++;
            }
        }
        Files.deleteIfExists(jfr);

        Path outDir = Path.of(System.getProperty("user.dir"), "target", "flames");
        Files.createDirectories(outDir);

        Path collapsed = outDir.resolve(name + ".collapsed.txt");
        try (BufferedWriter w = Files.newBufferedWriter(collapsed)) {
            for (var en : new java.util.TreeMap<>(folded).entrySet()) {
                w.write(en.getKey() + " " + en.getValue());
                w.newLine();
            }
        }

        Path svg = outDir.resolve(name + ".svg");
        Files.writeString(svg, renderSvg(name, buildTree(folded), total));

        return new Result(collapsed, svg, total, rankSelf(folded));
    }

    // ---- folding / ranking -------------------------------------------------

    private static String frameLabel(RecordedFrame f) {
        var m = f.getMethod();
        String type = (m.getType() != null) ? m.getType().getName() : "?";
        int dot = type.lastIndexOf('.');
        return (dot < 0 ? type : type.substring(dot + 1)) + "." + m.getName();
    }

    private static List<FrameCost> rankSelf(Map<String, Long> folded) {
        Map<String, long[]> perFrame = new HashMap<>(); // frame -> {self, total}
        for (var en : folded.entrySet()) {
            String[] fr = en.getKey().split(";");
            long c = en.getValue();
            perFrame.computeIfAbsent(fr[fr.length - 1], k -> new long[2])[0] += c; // self = leaf
            Set<String> distinct = new HashSet<>(List.of(fr)); // inclusive, dedup recursion
            for (String f : distinct) perFrame.computeIfAbsent(f, k -> new long[2])[1] += c;
        }
        return perFrame.entrySet().stream()
                .map(en -> new FrameCost(en.getKey(), en.getValue()[0], en.getValue()[1]))
                .sorted((a, b) -> Long.compare(b.selfSamples(), a.selfSamples()))
                .limit(25)
                .toList();
    }

    // ---- flame tree + SVG --------------------------------------------------

    private static final class Node {
        final String frame;
        long total;
        final Map<String, Node> kids = new LinkedHashMap<>();

        Node(String f) {
            this.frame = f;
        }

        Node kid(String f) {
            return kids.computeIfAbsent(f, Node::new);
        }
    }

    private static Node buildTree(Map<String, Long> folded) {
        Node root = new Node("all");
        for (var en : folded.entrySet()) {
            String[] fr = en.getKey().split(";");
            long c = en.getValue();
            root.total += c;
            Node cur = root;
            for (String f : fr) {
                cur = cur.kid(f);
                cur.total += c;
            }
        }
        return root;
    }

    private static int maxDepth(Node n, int d) {
        int m = d;
        for (Node k : n.kids.values()) m = Math.max(m, maxDepth(k, d + 1));
        return m;
    }

    private static String renderSvg(String title, Node root, long total) {
        if (total == 0) {
            return "<svg xmlns='http://www.w3.org/2000/svg' width='600' height='40'>"
                    + "<text x='8' y='24' font-family='monospace'>no CPU samples captured for "
                    + esc(title)
                    + "</text></svg>";
        }
        int width = 1200, rowH = 16, pad = 22;
        int depth = maxDepth(root, 0);
        int height = pad + 18 + (depth + 1) * rowH + 8;
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='")
                .append(width + 2 * pad)
                .append("' height='")
                .append(height)
                .append("' font-family='monospace' font-size='11'>")
                .append("<rect width='100%' height='100%' fill='#fafafa'/>")
                .append("<text x='")
                .append(pad)
                .append("' y='15' font-size='13'>CPU flame — ")
                .append(esc(title))
                .append("  (")
                .append(total)
                .append(" samples · JFR ExecutionSample · native/JNI time NOT shown)</text>");
        layout(sb, root, pad, width, 0, depth, rowH, pad + 14, total);
        sb.append("</svg>");
        return sb.toString();
    }

    private static void layout(
            StringBuilder sb,
            Node n,
            double x,
            double w,
            int d,
            int maxDepth,
            int rowH,
            int top,
            long total) {
        if (w >= 0.2) {
            double y = top + (double) (maxDepth - d) * rowH;
            String fill = n.frame.equals("all") ? "#dddddd" : flameColor(n.frame);
            sb.append("<rect x='")
                    .append(fmt(x))
                    .append("' y='")
                    .append(fmt(y))
                    .append("' width='")
                    .append(fmt(w))
                    .append("' height='")
                    .append(rowH - 1)
                    .append("' fill='")
                    .append(fill)
                    .append("' stroke='#ffffff' stroke-width='0.4'>")
                    .append("<title>")
                    .append(esc(n.frame))
                    .append("  ")
                    .append(n.total)
                    .append(" smp (")
                    .append(String.format("%.1f", 100.0 * n.total / total))
                    .append("%)</title></rect>");
            if (w > 34) {
                sb.append("<text x='")
                        .append(fmt(x + 2))
                        .append("' y='")
                        .append(fmt(y + rowH - 4))
                        .append("'>")
                        .append(esc(clip(n.frame, w)))
                        .append("</text>");
            }
        }
        double cx = x;
        List<Node> kids = new ArrayList<>(n.kids.values());
        kids.sort(Comparator.comparing(k -> k.frame));
        for (Node k : kids) {
            double kw = w * ((double) k.total / n.total);
            layout(sb, k, cx, kw, d + 1, maxDepth, rowH, top, total);
            cx += kw;
        }
    }

    private static String flameColor(String f) {
        int h = Math.abs(f.hashCode());
        int r = 205 + h % 50, g = 30 + (h / 50) % 200, b = (h / 100) % 55;
        return String.format("#%02x%02x%02x", r & 0xff, g & 0xff, b & 0xff);
    }

    private static String clip(String s, double w) {
        int max = (int) (w / 6.6);
        return s.length() <= max ? s : (max <= 1 ? "" : s.substring(0, max - 1) + "…");
    }

    private static String fmt(double v) {
        return (v == Math.rint(v)) ? Long.toString((long) v) : String.format("%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private CpuFlameProfiler() {}
}
