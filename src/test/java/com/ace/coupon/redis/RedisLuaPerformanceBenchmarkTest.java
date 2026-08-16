package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;

@Tag("redis-benchmark")
class RedisLuaPerformanceBenchmarkTest {

	private static final String BASELINE_SCRIPT = "scripts/coupon-issue-before-optimization.lua";
	private static final String OPTIMIZED_SCRIPT = "scripts/coupon-issue.lua";
	private static final AtomicLong CAMPAIGN_ID =
			new AtomicLong(8_100_000_000_000_000L + System.currentTimeMillis());

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static CampaignRedisInitializer initializer;
	private static RedisClient redisClient;

	@BeforeAll
	static void setUpRedis() {
		String host = System.getProperty("redis.host", "localhost");
		int port = Integer.parseInt(System.getProperty("redis.port", "6380"));
		redisClient = RedisClient.create(RedisURI.Builder.redis(host).withPort(port).build());
		connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();

		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		CouponIssueRedisProperties properties =
				new CouponIssueRedisProperties(Duration.ofMinutes(10), ZoneId.of("Asia/Seoul"));
		initializer = new CampaignRedisInitializer(
				redisTemplate,
				script("scripts/coupon-campaign-initialize.lua", Long.class),
				properties);
	}

	@AfterAll
	static void closeRedis() {
		connectionFactory.destroy();
		redisClient.shutdown();
	}

	@Test
	@Timeout(300)
	@DisplayName("동일 조건에서 최적화 전후 Lua 처리량과 지연시간을 비교한다")
	void comparesBeforeAndAfterOptimization() throws Exception {
		String mode = System.getProperty("benchmark.mode", "compare");
		int totalRequests = Integer.parseInt(System.getProperty("benchmark.requests", "20000"));
		int workers = Integer.parseInt(System.getProperty("benchmark.workers", "128"));
		validateConfiguration(totalRequests, workers);

		LuaVariant baselineScript = variant(BASELINE_SCRIPT);
		LuaVariant optimizedScript = variant(OPTIMIZED_SCRIPT);

		BenchmarkResult baseline;
		BenchmarkResult optimized = null;
		if ("baseline".equalsIgnoreCase(mode)) {
			warmUp(baselineScript, workers);
			baseline = benchmark("BEFORE", baselineScript, totalRequests, workers);
		} else if ("optimized".equalsIgnoreCase(mode)) {
			warmUp(optimizedScript, workers);
			baseline = benchmark("AFTER", optimizedScript, totalRequests, workers);
		} else {
			warmUp(baselineScript, workers);
			warmUp(optimizedScript, workers);
			int roundRequests = totalRequests / 4;
			int roundStock = roundRequests / 2;
			List<RoundResult> baselineRounds = new ArrayList<>(4);
			List<RoundResult> optimizedRounds = new ArrayList<>(4);

			// ABBA-BAAB 순서 기반 실행 위치 편향 상쇄
			baselineRounds.add(runRound(baselineScript, roundRequests, roundStock, workers));
			optimizedRounds.add(runRound(optimizedScript, roundRequests, roundStock, workers));
			optimizedRounds.add(runRound(optimizedScript, roundRequests, roundStock, workers));
			baselineRounds.add(runRound(baselineScript, roundRequests, roundStock, workers));
			optimizedRounds.add(runRound(optimizedScript, roundRequests, roundStock, workers));
			baselineRounds.add(runRound(baselineScript, roundRequests, roundStock, workers));
			baselineRounds.add(runRound(baselineScript, roundRequests, roundStock, workers));
			optimizedRounds.add(runRound(optimizedScript, roundRequests, roundStock, workers));

			baseline = BenchmarkResult.merge(
					"BEFORE", workers, baselineRounds.toArray(RoundResult[]::new));
			optimized = BenchmarkResult.merge(
					"AFTER", workers, optimizedRounds.toArray(RoundResult[]::new));
		}

		printResults(baseline, optimized);
		writeReports(baseline, optimized);
	}

	private BenchmarkResult benchmark(
			String label,
			LuaVariant issueScript,
			int totalRequests,
			int workers) throws Exception {
		RoundResult result = runRound(issueScript, totalRequests, totalRequests / 2, workers);
		return BenchmarkResult.merge(label, workers, result);
	}

	private void warmUp(LuaVariant issueScript, int workers) throws Exception {
		runRound(issueScript, 1_000, 500, Math.min(workers, 64));
	}

	private RoundResult runRound(
			LuaVariant issueScript,
			int requestCount,
			int stock,
			int workers) throws Exception {
		long campaignId = CAMPAIGN_ID.incrementAndGet();
		Instant now = Instant.now();
		assertThat(initializer.initialize(
				campaignId, stock, now.minusSeconds(60), now.plusSeconds(600)))
				.isEqualTo(CampaignInitializationResult.INITIALIZED);

		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>(workers);
		long[] latencies = new long[requestCount];
		LongAdder accepted = new LongAdder();
		LongAdder soldOut = new LongAdder();
		List<UUID> requestIds = new ArrayList<>(requestCount);
		for (int index = 0; index < requestCount; index++) {
			requestIds.add(UUID.randomUUID());
		}

		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		String[] scriptKeys = {
				keys.metadata(),
				keys.stock(),
				keys.sequence(),
				keys.bitmap(1L).key(),
				keys.requests(),
				keys.issueStream()
		};
		List<StatefulRedisConnection<String, String>> connections = new ArrayList<>(workers);
		for (int index = 0; index < workers; index++) {
			connections.add(redisClient.connect());
		}

		long elapsed;
		try (var executor = Executors.newFixedThreadPool(workers)) {
			for (int workerIndex = 0; workerIndex < workers; workerIndex++) {
				int assignedWorker = workerIndex;
				futures.add(executor.submit(() -> {
					var commands = connections.get(assignedWorker).sync();
					start.await();
					for (int sampleIndex = assignedWorker;
							sampleIndex < requestCount;
							sampleIndex += workers) {
						String userId = String.valueOf(sampleIndex + 1L);
						long startedAt = System.nanoTime();
						List<?> result = commands.evalsha(
								issueScript.sha(),
								ScriptOutputType.MULTI,
								scriptKeys,
								userId,
								String.valueOf(sampleIndex),
								requestIds.get(sampleIndex).toString(),
								String.valueOf(campaignId),
								"0");
						latencies[sampleIndex] = System.nanoTime() - startedAt;
						CouponIssueLuaCode code = CouponIssueLuaCode.from(resultCode(result));
						if (code == CouponIssueLuaCode.ACCEPTED) {
							accepted.increment();
						} else if (code == CouponIssueLuaCode.SOLD_OUT) {
							soldOut.increment();
						}
					}
					return null;
				}));
			}
			long startedAt = System.nanoTime();
			start.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
			elapsed = System.nanoTime() - startedAt;
		} finally {
			connections.forEach(StatefulRedisConnection::close);
		}

		assertThat(accepted.sum()).isEqualTo(stock);
		assertThat(soldOut.sum()).isEqualTo(requestCount - stock);
		assertThat(redisTemplate.opsForValue().get(keys.stock())).isEqualTo("0");
		assertThat(redisTemplate.opsForValue().get(keys.sequence())).isEqualTo(String.valueOf(stock));
		assertThat(bitCount(keys.bitmap(1L).key())).isEqualTo((long) stock);
		assertThat(redisTemplate.opsForStream().size(keys.issueStream())).isEqualTo((long) stock);
		deleteCampaign(campaignId);

		return new RoundResult(
				requestCount,
				stock,
				accepted.sum(),
				soldOut.sum(),
				elapsed,
				latencies);
	}

	private void printResults(BenchmarkResult baseline, BenchmarkResult optimized) {
		System.out.println();
		System.out.println("REDIS LUA BEFORE / AFTER BENCHMARK");
		System.out.println("-------------------------------------------------------------------------------------------");
		System.out.printf(Locale.US,
				"%-8s %10s %10s %12s %10s %10s %10s %10s%n",
				"VERSION", "REQUESTS", "SUCCESS", "THROUGHPUT", "P50(ms)", "P95(ms)", "P99(ms)", "MAX(ms)");
		printResultRow(baseline);
		if (optimized != null) {
			printResultRow(optimized);
			System.out.println("-------------------------------------------------------------------------------------------");
			System.out.printf(Locale.US, "처리량 개선: %.2f%%%n", throughputImprovement(baseline, optimized));
			System.out.printf(Locale.US, "P95 지연 감소: %.2f%%%n", latencyReduction(baseline.p95Millis(), optimized.p95Millis()));
			System.out.printf(Locale.US, "P99 지연 감소: %.2f%%%n", latencyReduction(baseline.p99Millis(), optimized.p99Millis()));
		}
		System.out.println("-------------------------------------------------------------------------------------------");
		System.out.println();
	}

	private void printResultRow(BenchmarkResult result) {
		System.out.printf(Locale.US,
				"%-8s %10d %10d %12.2f %10.2f %10.2f %10.2f %10.2f%n",
				result.label(),
				result.requests(),
				result.accepted(),
				result.throughput(),
				result.p50Millis(),
				result.p95Millis(),
				result.p99Millis(),
				result.maxMillis());
	}

	private void writeReports(BenchmarkResult baseline, BenchmarkResult optimized) throws IOException {
		Path reportDirectory = Path.of(System.getProperty(
				"benchmark.report.directory", "build/reports/redis-lua-benchmark"));
		Files.createDirectories(reportDirectory);
		Files.writeString(
				reportDirectory.resolve("results.csv"),
				csv(baseline, optimized),
				StandardCharsets.UTF_8);
		Files.writeString(
				reportDirectory.resolve("index.html"),
				html(baseline, optimized),
				StandardCharsets.UTF_8);
		if (optimized != null) {
			writeVariantReport(reportDirectory.resolve("before"), baseline);
			writeVariantReport(reportDirectory.resolve("after"), optimized);
		}
	}

	private void writeVariantReport(Path reportDirectory, BenchmarkResult result) throws IOException {
		Files.createDirectories(reportDirectory);
		Files.writeString(
				reportDirectory.resolve("results.csv"),
				csv(result, null),
				StandardCharsets.UTF_8);
		Files.writeString(
				reportDirectory.resolve("index.html"),
				html(result, null),
				StandardCharsets.UTF_8);
	}

	private String csv(BenchmarkResult baseline, BenchmarkResult optimized) {
		StringBuilder csv = new StringBuilder("version,requests,workers,accepted,sold_out,seconds,throughput,p50_ms,p95_ms,p99_ms,max_ms\n");
		csv.append(baseline.csvRow());
		if (optimized != null) {
			csv.append(optimized.csvRow());
		}
		return csv.toString();
	}

	private String html(BenchmarkResult baseline, BenchmarkResult optimized) {
		String comparison = optimized == null
				? "<div class=\"notice\">Baseline 단독 측정 결과</div>"
				: String.format(Locale.US, """
						<div class="improvement-grid">
						  <div class="improvement"><strong>+%.2f%%</strong><span>처리량</span></div>
						  <div class="improvement"><strong>%.2f%%</strong><span>P95 지연 감소</span></div>
						  <div class="improvement"><strong>%.2f%%</strong><span>P99 지연 감소</span></div>
						</div>
						""",
						throughputImprovement(baseline, optimized),
						latencyReduction(baseline.p95Millis(), optimized.p95Millis()),
						latencyReduction(baseline.p99Millis(), optimized.p99Millis()));
		String optimizedCard = optimized == null ? "" : resultCard(optimized, "after");
		String optimizedRow = optimized == null ? "" : optimized.tableRow();

		return String.format(Locale.US, """
				<!doctype html>
				<html lang="ko">
				<head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>Redis Lua 성능 비교</title>
				<style>
				:root{color-scheme:dark;--bg:#08111f;--panel:#111d2e;--line:#26364e;--text:#edf4ff;--muted:#9fb0c8;--before:#f59e0b;--after:#22c55e}
				*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#152744 0,var(--bg) 48%%);color:var(--text);font-family:"Pretendard","Noto Sans KR",sans-serif}
				main{max-width:1180px;margin:0 auto;padding:52px 42px 64px}h1{font-size:42px;margin:0 0 10px;letter-spacing:-1.5px}p{color:var(--muted);font-size:17px;margin:0}.meta{margin-top:12px;font-size:14px}.cards{display:grid;grid-template-columns:1fr 1fr;gap:22px;margin-top:36px}.card{background:rgba(17,29,46,.94);border:1px solid var(--line);border-radius:18px;padding:26px}.card.before{border-top:5px solid var(--before)}.card.after{border-top:5px solid var(--after)}.label{font-size:14px;font-weight:800;letter-spacing:1.5px}.before .label{color:var(--before)}.after .label{color:var(--after)}.throughput{font-size:43px;font-weight:900;margin:12px 0 5px}.unit{font-size:16px;color:var(--muted);margin-left:5px}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:22px}.metric{background:#0b1627;border-radius:12px;padding:14px}.metric strong{display:block;font-size:20px}.metric span{color:var(--muted);font-size:12px}.improvement-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:24px 0}.improvement{background:linear-gradient(135deg,#123725,#0e261c);border:1px solid #24633f;border-radius:15px;padding:20px;text-align:center}.improvement strong{font-size:31px;color:#65e394;display:block}.improvement span{color:#b9d8c4}.notice{margin:24px 0;padding:18px;background:#31230c;border-radius:12px;color:#ffd88b}table{width:100%%;border-collapse:collapse;background:var(--panel);border-radius:16px;overflow:hidden;margin-top:20px}th,td{padding:15px 14px;text-align:right;border-bottom:1px solid var(--line)}th{color:var(--muted);font-size:13px}th:first-child,td:first-child{text-align:left}.method{margin-top:30px;background:rgba(17,29,46,.82);border:1px solid var(--line);border-radius:16px;padding:22px}.method h2{margin:0 0 13px}.method li{color:var(--muted);margin:7px 0}.pass{color:#65e394;font-weight:800}@media(max-width:760px){main{padding:30px 18px}.cards{grid-template-columns:1fr}.improvement-grid{grid-template-columns:1fr}h1{font-size:32px}}
				</style>
				</head>
				<body><main>
				<h1>Redis Lua 최적화 전후 성능 비교</h1>
				<p>동일 benchmark Redis · 동일 요청 수 · 동일 연결 수 · 동일 정합성 검증</p>
				<p class="meta">Lua 실행 비용 격리용 AOF/RDB 비활성 프로필</p>
				<p class="meta">측정 시각 %s · Java %s</p>
				<div class="cards">%s%s</div>
				%s
				<table><thead><tr><th>버전</th><th>요청</th><th>승인</th><th>소진</th><th>처리량(req/s)</th><th>P50(ms)</th><th>P95(ms)</th><th>P99(ms)</th><th>최대(ms)</th></tr></thead>
				<tbody>%s%s</tbody></table>
				<section class="method"><h2>측정 조건과 정합성 검증</h2><ul>
				<li>총 %,d건 요청, 재고 %,d장, 물리 Redis 연결 %d개</li>
				<li>Lua SHA 사전 로드 후 EVALSHA 호출 기준</li>
				<li>순서 편향 완화용 ABBA-BAAB 교차 실행</li>
				<li>각 버전 측정 전 1,000건 warm-up</li>
				<li class="pass">승인 %,d건 · 재고 소진 %,d건 · 초과 발급 0건</li>
				<li class="pass">최종 재고 0 · Bitmap/순번/Stream 승인 수 일치</li>
				</ul></section>
				</main></body></html>
				""",
				OffsetDateTime.now(),
				System.getProperty("java.version"),
				resultCard(baseline, "before"),
				optimizedCard,
				comparison,
				baseline.tableRow(),
				optimizedRow,
				baseline.requests(),
				baseline.requests() / 2,
				baseline.workers(),
				baseline.accepted(),
				baseline.soldOut());
	}

	private String resultCard(BenchmarkResult result, String cssClass) {
		return String.format(Locale.US, """
				<section class="card %s"><div class="label">%s</div>
				<div class="throughput">%,.2f<span class="unit">req/s</span></div>
				<div class="metrics">
				<div class="metric"><strong>%.2f ms</strong><span>P50</span></div>
				<div class="metric"><strong>%.2f ms</strong><span>P95</span></div>
				<div class="metric"><strong>%.2f ms</strong><span>P99</span></div>
				</div></section>
				""", cssClass, result.label(), result.throughput(),
				result.p50Millis(), result.p95Millis(), result.p99Millis());
	}

	private double throughputImprovement(BenchmarkResult baseline, BenchmarkResult optimized) {
		return ((optimized.throughput() / baseline.throughput()) - 1) * 100;
	}

	private double latencyReduction(double baseline, double optimized) {
		return (1 - (optimized / baseline)) * 100;
	}

	private Long bitCount(String key) {
		byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
		return redisTemplate.execute((RedisCallback<Long>) connection ->
				connection.stringCommands().bitCount(rawKey));
	}

	private void deleteCampaign(long campaignId) {
		Set<String> keys = redisTemplate.keys("coupon:{campaign:" + campaignId + "}:*");
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	private void validateConfiguration(int requests, int workers) {
		if (requests < 4 || requests % 4 != 0) {
			throw new IllegalArgumentException("benchmark.requests는 4의 배수여야 합니다.");
		}
		if (workers <= 0) {
			throw new IllegalArgumentException("benchmark.workers는 양수여야 합니다.");
		}
	}

	private long resultCode(List<?> result) {
		if (result == null || result.size() != 4) {
			throw new IllegalStateException("Lua 벤치마크 결과 형식 오류");
		}
		Object code = result.get(0);
		return code instanceof Number number
				? number.longValue()
				: Long.parseLong(String.valueOf(code));
	}

	private static LuaVariant variant(String location) throws IOException {
		ClassPathResource resource = new ClassPathResource(location);
		String source;
		try (var input = resource.getInputStream()) {
			source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
			return new LuaVariant(connection.sync().scriptLoad(source));
		}
	}

	private static <T> RedisScript<T> script(String location, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(location));
		script.setResultType(resultType);
		return script;
	}

	private record RoundResult(
			int requests,
			int stock,
			long accepted,
			long soldOut,
			long elapsedNanos,
			long[] latencies) {
	}

	private record LuaVariant(String sha) {
	}

	private record BenchmarkResult(
			String label,
			int requests,
			int workers,
			long accepted,
			long soldOut,
			double seconds,
			double throughput,
			double p50Millis,
			double p95Millis,
			double p99Millis,
			double maxMillis) {

		static BenchmarkResult merge(String label, int workers, RoundResult... rounds) {
			int requests = Arrays.stream(rounds).mapToInt(RoundResult::requests).sum();
			long accepted = Arrays.stream(rounds).mapToLong(RoundResult::accepted).sum();
			long soldOut = Arrays.stream(rounds).mapToLong(RoundResult::soldOut).sum();
			long elapsed = Arrays.stream(rounds).mapToLong(RoundResult::elapsedNanos).sum();
			long[] latencies = Arrays.stream(rounds)
					.flatMapToLong(round -> Arrays.stream(round.latencies()))
					.sorted()
					.toArray();
			double seconds = elapsed / 1_000_000_000.0;
			return new BenchmarkResult(
					label,
					requests,
					workers,
					accepted,
					soldOut,
					seconds,
					requests / seconds,
					percentile(latencies, 0.50),
					percentile(latencies, 0.95),
					percentile(latencies, 0.99),
					latencies[latencies.length - 1] / 1_000_000.0);
		}

		private static double percentile(long[] sorted, double percentile) {
			int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1);
			return sorted[index] / 1_000_000.0;
		}

		String csvRow() {
			return String.format(Locale.US,
					"%s,%d,%d,%d,%d,%.4f,%.2f,%.3f,%.3f,%.3f,%.3f%n",
					label, requests, workers, accepted, soldOut, seconds, throughput,
					p50Millis, p95Millis, p99Millis, maxMillis);
		}

		String tableRow() {
			return String.format(Locale.US,
					"<tr><td>%s</td><td>%,d</td><td>%,d</td><td>%,d</td><td>%,.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td></tr>",
					label, requests, accepted, soldOut, throughput,
					p50Millis, p95Millis, p99Millis, maxMillis);
		}
	}
}
