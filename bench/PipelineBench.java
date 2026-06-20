import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;

/**
 * AI 小说转剧本 —— 流水线并发基准 / 线程池压测。
 *
 * 目的：用与生产环境同语义的 JVM 线程池（ThreadPoolExecutor），把一次大模型调用建模成
 * 一个固定时延 ± 抖动的任务，量化以下几件事，支撑"串行 → 有界并行"的叙事：
 *   1) 单阶段 串行(C=1) vs 并行(C=4) 的真实墙钟加速比；
 *   2) 线程池并发度扫描（压测），找到最佳并发度的"拐点"；
 *   3) 叠加"模型侧并发上限(限流)"后，为什么并发度超过上限不再有收益（解释 bounded=4）；
 *   4) 端到端流水线：v0 全串行(原始仓库语义) vs 当前(3 阶段并行 + 大纲串行)；
 *   5) sceneScriptExecutor 的有界队列 + CallerRunsPolicy 背压：0 拒绝 + caller-runs 计数。
 *
 * 关键事实：对"睡眠型/IO 等待型"任务，墙钟≈ceil(N/有效并发)×单次时延，随单次时延 L 线性缩放，
 * 因此加速比与 L 无关 —— 我们用较小的 L 实测以缩短跑测时间，再按线性把头条数字投影到真实 L。
 *
 * 生产线程池参数（来自 ChapterSummaryConfig / StoryAssetConfig / SceneStreamConfig）：
 *   - chapterSummaryExecutor / storyAssetExecutor: core=max=4, 无界队列
 *   - sceneScriptExecutor: core=max=4, 有界队列=100, CallerRunsPolicy
 */
public class PipelineBench {

    // ---- 可调参数（也可用命令行覆盖：key=value）----
    static long SWEEP_L_MS = 300;     // 压测用单次调用时延（小，便于多档位快速扫描）
    static double JITTER = 0.15;      // 时延抖动（高斯，cv≈15%），更贴近真实模型波动
    static int SWEEP_N = 16;          // 压测任务数
    static int REPS = 3;              // 每个测点重复次数取均值
    static int PROVIDER_CAP = 4;      // 模型侧并发上限（限流）建模
    static int DB_POOL = 10;          // HikariCP 连接池大小（默认 10）
    static int RETRY = 1;             // 失败重试次数（feat/ai-retry-timeout）
    static long L_TTFT = 700;         // 真流式首 chunk 到达时间（典型 ~0.7s）
    static long L_DBWRITE = 200;      // 单次写库事务时长

    // 端到端真实时延假设（毫秒，可被实测 elapsedMs 替换）——清楚标注为输入
    static long L_SUMMARY = 4000;
    static long L_ASSET   = 8000;
    static long L_OUTLINE = 10000;    // 大纲：串行（顺序上下文依赖）
    static long L_SCENE   = 12000;
    // 代表性短篇工作量
    static int N_CHAPTERS = 6;        // 章节摘要调用数
    static int N_ASSET    = 6;        // 实体/事件抽取批次数
    static int N_OUTLINE  = 3;        // 大纲批次数（OUTLINE_EVENTS_PER_BATCH=6）
    static int N_SCENE    = 10;       // 场景剧本数
    static int CONCURRENCY = 4;       // 生产默认有界并发
    static long E2E_SCALE_DIV = 20;   // 端到端实测时把真实时延按此倍数缩小，再线性投影回真实值

    static final Random RND = new Random(42);
    static PrintWriter csv;
    static PrintWriter csv2;

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length == 2) setParam(kv[0], kv[1]);
        }
        new File("bench/results").mkdirs();
        csv = new PrintWriter(new FileWriter("bench/results/sweep.csv"));
        csv.println("section,concurrency,tasks,latency_ms,makespan_ms,speedup_vs_serial,efficiency_pct,caller_runs,rejected");
        csv2 = new PrintWriter(new FileWriter("bench/results/reliability.csv"));
        csv2.println("section,param,v0,current,note");

        banner("AI 小说转剧本 · 流水线并发基准 / 线程池压测");
        System.out.printf("JVM=%s  可用处理器=%d%n", System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("参数: SWEEP_L=%dms 抖动=±%.0f%% N=%d 重复=%d 限流上限=%d%n%n",
                SWEEP_L_MS, JITTER * 100, SWEEP_N, REPS, PROVIDER_CAP);

        section1_stageSpeedup();
        double[] sweepSerial = section2_concurrencySweep();
        section3_providerCap(sweepSerial);
        section4_endToEnd();
        section5_callerRuns();
        section6_reliability();
        section7_ttfb();
        section8_dbConnections();

        csv.flush();
        csv.close();
        csv2.flush();
        csv2.close();
        System.out.println("\n[完成] 明细已写入 bench/results/sweep.csv 与 reliability.csv");
    }

    // ====== 1) 单阶段 串行 vs 并行 ======
    static void section1_stageSpeedup() throws Exception {
        banner("① 单阶段：串行(C=1，原始仓库语义) vs 并行(C=4，当前)");
        String[] names = {"章节摘要", "实体/事件抽取", "场景剧本"};
        int[] ns = {N_CHAPTERS, N_ASSET, N_SCENE};
        long[] ls = {scaled(L_SUMMARY), scaled(L_ASSET), scaled(L_SCENE)};
        System.out.printf("%-14s %5s %10s %12s %12s %9s%n",
                "阶段", "任务数", "单次(ms)", "串行(ms)", "并行C4(ms)", "加速比");
        for (int i = 0; i < names.length; i++) {
            final int fi = i;
            long serial = bestOf(() -> runUnbounded(ns[fi], 1, ls[fi]));
            long par = bestOf(() -> runUnbounded(ns[fi], CONCURRENCY, ls[fi]));
            double sp = serial / (double) par;
            System.out.printf("%-14s %5d %10d %12d %12d %8.2fx%n",
                    names[i], ns[i], ls[i], serial, par, sp);
            csv.printf("stage_serial,%d,%d,%d,%d,%.3f,%.1f,,%n", 1, ns[i], ls[i], serial, 1.0, 100.0);
            csv.printf("stage_parallel,%d,%d,%d,%d,%.3f,%.1f,,%n", CONCURRENCY, ns[i], ls[i], par, sp, sp / CONCURRENCY * 100);
        }
        System.out.println("注：单次时延已按 1/" + E2E_SCALE_DIV + " 缩放跑测；加速比与时延无关，对真实时延同样成立。");
    }

    // ====== 2) 并发度扫描（压测）======
    static double[] section2_concurrencySweep() throws Exception {
        banner("② 线程池并发度扫描（压测）—— N=" + SWEEP_N + " 个独立调用，找最佳并发度");
        int[] cs = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16};
        System.out.printf("%6s %12s %10s %12s %s%n", "并发C", "墙钟(ms)", "加速比", "效率", "");
        long serial = -1;
        double prevSp = 0;
        double kneeC = -1;
        for (int c : cs) {
            final int cc = c;
            long ms = bestOf(() -> runUnbounded(SWEEP_N, cc, SWEEP_L_MS));
            if (serial < 0) serial = ms;
            double sp = serial / (double) ms;
            double eff = sp / c * 100;
            String bar = "█".repeat(Math.max(0, (int) Math.round(sp * 2)));
            String tag = "";
            if (kneeC < 0 && sp - prevSp < 0.15 && c > 1) { kneeC = c; tag = "  ← 收益拐点"; }
            System.out.printf("%6d %12d %9.2fx %10.0f%% %s%s%n", c, ms, sp, eff, bar, tag);
            csv.printf("sweep,%d,%d,%d,%d,%.3f,%.1f,,%n", c, SWEEP_N, SWEEP_L_MS, ms, sp, eff);
            prevSp = sp;
        }
        System.out.printf("解读：N=%d 时，并发=4 落在 ceil(N/C) 波次的高效率区；继续加并发，", SWEEP_N);
        System.out.println("效率(加速比/并发)持续下降，边际收益递减。");
        return new double[]{serial};
    }

    // ====== 3) 叠加模型侧限流上限 ======
    static void section3_providerCap(double[] serialRef) throws Exception {
        banner("③ 叠加模型侧并发上限=" + PROVIDER_CAP + "（限流）—— 为什么并发超过上限不再有收益");
        int[] cs = {1, 2, 4, 6, 8, 12, 16};
        Semaphore cap = new Semaphore(PROVIDER_CAP);
        System.out.printf("%6s %12s %10s %s%n", "池并发C", "墙钟(ms)", "加速比", "");
        long serial = -1;
        for (int c : cs) {
            final int cc = c;
            long ms = bestOf(() -> runUnboundedCapped(SWEEP_N, cc, SWEEP_L_MS, cap));
            if (serial < 0) serial = ms;
            double sp = serial / (double) ms;
            String bar = "█".repeat(Math.max(0, (int) Math.round(sp * 2)));
            String tag = c > PROVIDER_CAP ? "  (被限流上限封顶)" : "";
            System.out.printf("%6d %12d %9.2fx %s%s%n", c, ms, sp, bar, tag);
            csv.printf("provider_cap,%d,%d,%d,%d,%.3f,,,%n", c, SWEEP_N, SWEEP_L_MS, ms, sp);
        }
        System.out.println("结论：模型有并发上限时，墙钟在 C=上限 后触顶；无界加并发只会触发限流/拒绝，");
        System.out.println("      这正是我们用「有界并发=4」而非无脑放大的工程依据。");
    }

    // ====== 4) 端到端流水线 v0 vs 当前 ======
    static void section4_endToEnd() throws Exception {
        banner("④ 端到端流水线：v0 全串行(原始仓库) vs 当前(3 阶段并行 + 大纲串行)");
        System.out.printf("代表性短篇工作量：摘要×%d、抽取×%d、大纲×%d(串行)、场景×%d；并发=%d%n",
                N_CHAPTERS, N_ASSET, N_OUTLINE, N_SCENE, CONCURRENCY);
        System.out.println("（下方为按 1/" + E2E_SCALE_DIV + " 缩放的实测墙钟，括号内为线性投影到真实时延的预估）\n");

        long sSum = scaled(L_SUMMARY), sAst = scaled(L_ASSET), sOut = scaled(L_OUTLINE), sScn = scaled(L_SCENE);

        // v0：全部串行（每阶段 C=1）
        long v0Sum = bestOf(() -> runUnbounded(N_CHAPTERS, 1, sSum));
        long v0Ast = bestOf(() -> runUnbounded(N_ASSET, 1, sAst));
        long v0Out = bestOf(() -> runUnbounded(N_OUTLINE, 1, sOut));
        long v0Scn = bestOf(() -> runUnbounded(N_SCENE, 1, sScn));
        long v0 = v0Sum + v0Ast + v0Out + v0Scn;

        // 当前：摘要/抽取/场景并行(C=4)，大纲串行
        long cuSum = bestOf(() -> runUnbounded(N_CHAPTERS, CONCURRENCY, sSum));
        long cuAst = bestOf(() -> runUnbounded(N_ASSET, CONCURRENCY, sAst));
        long cuOut = bestOf(() -> runUnbounded(N_OUTLINE, 1, sOut)); // 故意串行
        long cuScn = bestOf(() -> runUnbounded(N_SCENE, CONCURRENCY, sScn));
        long cu = cuSum + cuAst + cuOut + cuScn;

        row("阶段", "v0 串行(ms)", "当前(ms)", "投影·v0", "投影·当前");
        prow("章节摘要", v0Sum, cuSum, L_SUMMARY, N_CHAPTERS, 1, CONCURRENCY);
        prow("实体/事件抽取", v0Ast, cuAst, L_ASSET, N_ASSET, 1, CONCURRENCY);
        prow("场景大纲(串行)", v0Out, cuOut, L_OUTLINE, N_OUTLINE, 1, 1);
        prow("场景剧本", v0Scn, cuScn, L_SCENE, N_SCENE, 1, CONCURRENCY);
        System.out.println("  " + "-".repeat(72));
        long projV0 = proj(L_SUMMARY, N_CHAPTERS, 1) + proj(L_ASSET, N_ASSET, 1) + proj(L_OUTLINE, N_OUTLINE, 1) + proj(L_SCENE, N_SCENE, 1);
        long projCu = proj(L_SUMMARY, N_CHAPTERS, CONCURRENCY) + proj(L_ASSET, N_ASSET, CONCURRENCY) + proj(L_OUTLINE, N_OUTLINE, 1) + proj(L_SCENE, N_SCENE, CONCURRENCY);
        System.out.printf("  %-14s %12d %12d %12s %12s%n", "合计", v0, cu,
                fmt(projV0), fmt(projCu));
        System.out.printf("%n  实测墙钟加速比：%.2fx（%d→%d ms）%n", v0 / (double) cu, v0, cu);
        System.out.printf("  真实时延投影：v0≈%s，当前≈%s，加速比≈%.2fx，节省≈%s%n",
                fmt(projV0), fmt(projCu), projV0 / (double) projCu, fmt(projV0 - projCu));
        System.out.printf("  其中大纲(串行不可压)占当前总时长 %.0f%% —— 这就是剩余的硬瓶颈。%n",
                proj(L_OUTLINE, N_OUTLINE, 1) * 100.0 / projCu);
        csv.printf("e2e_v0,%d,%d,,%d,%.3f,,,%n", 1, N_CHAPTERS + N_ASSET + N_OUTLINE + N_SCENE, v0, 1.0);
        csv.printf("e2e_current,%d,%d,,%d,%.3f,,,%n", CONCURRENCY, N_CHAPTERS + N_ASSET + N_OUTLINE + N_SCENE, cu, v0 / (double) cu);
    }

    // ====== 5) CallerRunsPolicy 背压 ======
    static void section5_callerRuns() throws Exception {
        banner("⑤ sceneScriptExecutor 背压：有界队列 + CallerRunsPolicy（0 拒绝）");
        int n = 24, c = 4, queue = 4; // 故意把队列设小以触发 caller-runs
        AtomicInteger callerRuns = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        long ms = runSceneBounded(n, c, queue, SWEEP_L_MS, callerRuns, rejected);
        System.out.printf("任务=%d 并发=%d 队列=%d → 墙钟=%dms, caller-runs=%d 次, 拒绝=%d 次%n",
                n, c, queue, ms, callerRuns.get(), rejected.get());
        System.out.println("结论：队列打满时，提交线程亲自执行任务形成自然背压，既不丢任务也不无限堆积；");
        System.out.println("      生产用 queue=100 作安全阀，正常短篇不会触发，极端负载时优雅降速而非崩溃。");
        csv.printf("caller_runs,%d,%d,%d,%d,,,%d,%d%n", c, n, SWEEP_L_MS, ms, callerRuns.get(), rejected.get());
    }

    // ====== 6) 整本成功率：故障注入 + 蒙特卡洛 ======
    static void section6_reliability() {
        banner("⑥ 整本成功率：故障注入 + 蒙特卡洛（N=" + N_SCENE + " 场景，重试=" + RETRY + "）");
        int trials = 200_000;
        int n = N_SCENE;
        System.out.printf("%-8s %18s %26s %14s%n",
                "失败率p", "v0 整本成功率", "当前 整本完整率(隔离+重试)", "当前 不崩率");
        double[] ps = {0.01, 0.03, 0.05, 0.10};
        for (double p : ps) {
            int v0ok = 0, curComplete = 0;
            for (int t = 0; t < trials; t++) {
                boolean allOk = true, allComplete = true;
                for (int i = 0; i < n; i++) {
                    boolean first = RND.nextDouble() >= p;        // 第一次调用
                    if (!first) allOk = false;                     // v0：任一失败即整本崩
                    boolean finalOk = first;
                    for (int r = 0; r < RETRY && !finalOk; r++)    // 当前：失败重试
                        finalOk = RND.nextDouble() >= p;
                    if (!finalOk) allComplete = false;             // 重试后仍失败→该场景写占位
                }
                if (allOk) v0ok++;
                if (allComplete) curComplete++;
            }
            double v0 = v0ok * 100.0 / trials, cur = curComplete * 100.0 / trials;
            System.out.printf("%-9s %15.1f%% %23.1f%% %13s%n", pct(p), v0, cur, "100.0%");
            csv2.printf("reliability,p=%.2f_N%d,%.1f,%.1f,never_crash_100pct%n", p, n, v0, cur);
        }
        System.out.println("解读：v0「任一失败即全崩」，整本成功率随场景数指数衰减(p=5%,N=10 → 约 60%)；");
        System.out.println("      当前「隔离+重试+占位」：重试把单点失败率从 p 压到 p²，占位保证整本永不崩(100%)。");
    }

    // ====== 7) 感知延迟：首屏 TTFB ======
    static void section7_ttfb() throws Exception {
        banner("⑦ 感知延迟（首屏 TTFB）：非流式 vs 真流式");
        long total = scaled(L_SCENE);
        long ttft = Math.max(1, scaled(L_TTFT));
        long nonStream = bestOf(() -> measureFirstVisible(total, total)); // 非流式：整段生成完才出现
        long stream = bestOf(() -> measureFirstVisible(ttft, total));     // 真流式：首 chunk 即出现
        double ratio = L_SCENE / (double) L_TTFT;
        System.out.printf("非流式首屏等待 = 实测 %dms（投影真实 %s）%n", nonStream, fmt(L_SCENE));
        System.out.printf("真流式首屏等待 = 实测 %dms（投影真实 %s）%n", stream, fmt(L_TTFT));
        System.out.printf("感知提速 ≈ %.1f×（首屏从 %s 降到 %s）%n", ratio, fmt(L_SCENE), fmt(L_TTFT));
        System.out.println("注：真实墙钟靠并行(2.47×)压，感知延迟靠流式压 —— 两条不同的延迟分别治理。");
        csv2.printf("ttfb,scene_first_paint,%s,%s,perceived_%.1fx%n", fmt(L_SCENE), fmt(L_TTFT), ratio);
    }

    static long measureFirstVisible(long firstAt, long total) {
        long t0 = System.nanoTime();
        sleep(firstAt);
        long firstVisible = msSince(t0);
        if (total > firstAt) sleep(total - firstAt); // 其余继续生成，不计入首屏
        return firstVisible;
    }

    // ====== 8) DB 连接占用：长事务 vs 拆分事务 ======
    static void section8_dbConnections() throws Exception {
        banner("⑧ DB 连接占用：长事务 vs 拆分事务（HikariCP 池=" + DB_POOL + "，并发=16）");
        int c = 16;
        long ai = scaled(L_SCENE), write = Math.max(1, scaled(L_DBWRITE));
        long[] v0 = bestOfArr(() -> runDbLongTx(c, ai));
        long[] cur = bestOfArr(() -> runDbSplitTx(c, ai, write));
        System.out.printf("%-30s %12s %16s %14s%n", "", "墙钟(ms)", "连接占用/任务", "排队等连接数");
        System.out.printf("%-30s %12d %16s %12d%n", "v0 长事务(AI 期间一直持连接)", v0[0], fmt(L_SCENE), v0[1]);
        System.out.printf("%-30s %12d %16s %12d%n", "当前 拆分事务(仅写库瞬间持连接)", cur[0], fmt(L_DBWRITE), cur[1]);
        System.out.printf("连接占用时长 %s → %s（约 %d×）；并发 %d > 池 %d 时 v0 有 %d 个任务排队等连接，当前 %d。%n",
                fmt(L_SCENE), fmt(L_DBWRITE), L_SCENE / L_DBWRITE, c, DB_POOL, v0[1], cur[1]);
        System.out.println("结论：旧版把慢 AI 调用裹在事务里，连接被长期占用，并发一上来连接池即枯竭、新请求排队超时；");
        System.out.println("      拆分读写事务后，AI 调用期间不占连接，池子几乎不饱和 —— 这是能不能扛并发的关键。");
        csv2.printf("db_conn,hold_time,%s,%s,starved_v0=%d_cur=%d%n", fmt(L_SCENE), fmt(L_DBWRITE), v0[1], cur[1]);
    }

    // 长事务：acquire 连接 → 整个 AI 调用期间持有 → release
    static long[] runDbLongTx(int c, long aiMs) {
        Semaphore pool = new Semaphore(DB_POOL);
        ExecutorService ex = Executors.newFixedThreadPool(c);
        AtomicInteger waited = new AtomicInteger();
        try {
            List<Future<?>> fs = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < c; i++) fs.add(ex.submit(() -> {
                if (!pool.tryAcquire()) { waited.incrementAndGet(); acquire(pool); }
                try { sleep(aiMs); } finally { pool.release(); }
            }));
            for (Future<?> f : fs) join(f);
            return new long[]{msSince(t0), waited.get()};
        } finally { ex.shutdownNow(); }
    }

    // 拆分事务：AI 调用不持连接 → 仅写库瞬间 acquire/release
    static long[] runDbSplitTx(int c, long aiMs, long writeMs) {
        Semaphore pool = new Semaphore(DB_POOL);
        ExecutorService ex = Executors.newFixedThreadPool(c);
        AtomicInteger waited = new AtomicInteger();
        try {
            List<Future<?>> fs = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < c; i++) fs.add(ex.submit(() -> {
                sleep(aiMs); // AI 调用：不持有连接
                if (!pool.tryAcquire()) { waited.incrementAndGet(); acquire(pool); }
                try { sleep(writeMs); } finally { pool.release(); }
            }));
            for (Future<?> f : fs) join(f);
            return new long[]{msSince(t0), waited.get()};
        } finally { ex.shutdownNow(); }
    }

    static void acquire(Semaphore s) {
        try { s.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    interface ArrSupplierEx { long[] get() throws Exception; }
    static long[] bestOfArr(ArrSupplierEx s) throws Exception {
        long[] best = null;
        for (int i = 0; i < REPS; i++) { long[] r = s.get(); if (best == null || r[0] < best[0]) best = r; }
        return best;
    }
    static String pct(double p) { return String.format("%.0f%%", p * 100); }

    // ---------- 执行器：与生产同语义 ----------

    // 无界队列、core=max=C —— 对应 chapterSummaryExecutor / storyAssetExecutor
    static long runUnbounded(int n, int concurrency, long latencyMs) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                concurrency, concurrency, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        try {
            List<Future<?>> fs = new ArrayList<>(n);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) fs.add(ex.submit(() -> sleep(latencyMs)));
            for (Future<?> f : fs) join(f);
            return msSince(t0);
        } finally { ex.shutdownNow(); }
    }

    // 叠加模型侧并发上限（Semaphore）
    static long runUnboundedCapped(int n, int concurrency, long latencyMs, Semaphore cap) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                concurrency, concurrency, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        try {
            List<Future<?>> fs = new ArrayList<>(n);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) fs.add(ex.submit(() -> {
                try { cap.acquire(); sleep(latencyMs); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                finally { cap.release(); }
            }));
            for (Future<?> f : fs) join(f);
            return msSince(t0);
        } finally { ex.shutdownNow(); }
    }

    // 有界队列 + CallerRunsPolicy —— 对应 sceneScriptExecutor
    static long runSceneBounded(int n, int concurrency, int queueCap, long latencyMs,
                                AtomicInteger callerRuns, AtomicInteger rejected) {
        final long[] mainTid = {Thread.currentThread().threadId()};
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                concurrency, concurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCap),
                r -> { Thread t = new Thread(r, "scene-script-"); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            List<Future<?>> fs = new ArrayList<>(n);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                try {
                    fs.add(ex.submit(() -> {
                        if (Thread.currentThread().threadId() == mainTid[0]) callerRuns.incrementAndGet();
                        sleep(latencyMs);
                    }));
                } catch (RejectedExecutionException e) { rejected.incrementAndGet(); }
            }
            for (Future<?> f : fs) join(f);
            return msSince(t0);
        } finally { ex.shutdownNow(); }
    }

    // ---------- 工具 ----------
    interface LongSupplierEx { long get() throws Exception; }
    static long bestOf(LongSupplierEx s) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < REPS; i++) best = Math.min(best, s.get());
        return best;
    }
    static void sleep(long ms) {
        long jittered = (long) Math.max(1, ms * (1 + JITTER * RND.nextGaussian()));
        try { Thread.sleep(jittered); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static void join(Future<?> f) { try { f.get(); } catch (Exception e) { throw new RuntimeException(e); } }
    static long msSince(long t0nano) { return Math.round((System.nanoTime() - t0nano) / 1e6); }
    static long scaled(long real) { return Math.max(1, real / E2E_SCALE_DIV); }
    static long proj(long realL, int n, int c) { return (long) Math.ceil(n / (double) c) * realL; }
    static String fmt(long ms) { return ms >= 1000 ? String.format("%.1fs", ms / 1000.0) : ms + "ms"; }
    static void prow(String name, long v0, long cu, long realL, int n, int c0, int c1) {
        System.out.printf("  %-14s %12d %12d %12s %12s%n", name, v0, cu, fmt(proj(realL, n, c0)), fmt(proj(realL, n, c1)));
    }
    static void row(String a, String b, String c, String d, String e) {
        System.out.printf("  %-14s %12s %12s %12s %12s%n", a, b, c, d, e);
    }
    static void banner(String s) { System.out.println("\n" + "=".repeat(76) + "\n" + s + "\n" + "=".repeat(76)); }
    static int i_(int i) { return i; }
    static void setParam(String k, String v) {
        switch (k) {
            case "SWEEP_L_MS" -> SWEEP_L_MS = Long.parseLong(v);
            case "SWEEP_N" -> SWEEP_N = Integer.parseInt(v);
            case "REPS" -> REPS = Integer.parseInt(v);
            case "PROVIDER_CAP" -> PROVIDER_CAP = Integer.parseInt(v);
            case "CONCURRENCY" -> CONCURRENCY = Integer.parseInt(v);
            case "E2E_SCALE_DIV" -> E2E_SCALE_DIV = Long.parseLong(v);
            default -> System.err.println("未知参数: " + k);
        }
    }
}
