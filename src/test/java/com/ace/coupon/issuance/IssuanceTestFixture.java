package com.ace.coupon.issuance;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

// 정확성 검증용 회차 준비와 집계
public final class IssuanceTestFixture {

	public static final int REQUESTS = Integer.getInteger("issuance.requests", 20_000);
	public static final int STOCK = Integer.getInteger("issuance.stock", 10_000);
	public static final int THREADS = Integer.getInteger("issuance.threads", 100);

	private IssuanceTestFixture() {
	}

	// userId=1..count 로 발급을 시도하므로, 로컬 user 테이블에 그만큼 없으면 부족한 만큼 채워둔다
	public static void ensureUsers(JdbcTemplate jdbc, int count) {
		Long maxUserId = jdbc.queryForObject("SELECT COALESCE(MAX(user_id), 0) FROM user", Long.class);
		if (maxUserId >= count) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		List<Object[]> batch = new java.util.ArrayList<>();
		for (long i = maxUserId + 1; i <= count; i++) {
			batch.add(new Object[]{"user" + i + "@test.com", "테스트유저" + i, "010-0000-0000", now});
		}
		jdbc.batchUpdate(
				"INSERT INTO user (email, name, phone, created_at) VALUES (?, ?, ?, ?)", batch);
	}

	public static long createEvent(JdbcTemplate jdbc, int stock) {
		LocalDateTime now = LocalDateTime.now();
		jdbc.update("""
				INSERT INTO coupon (coupon_name, type, value, valid_hours, created_at)
				VALUES ('정확성 검증', 'FIXED', 1000, 168, ?)
				""", now);
		long couponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
				INSERT INTO coupon_event
				    (coupon_id, round, open_at, close_at, total_stock, remaining_stock,
				     issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 0, 1, 'OPEN', ?, ?)
				""",
				couponId, 9000 + (int) (System.nanoTime() % 900),
				now.minusMinutes(1), now.plusHours(1), stock, stock, now, now);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	public static void deleteEvent(JdbcTemplate jdbc, long eventId) {
		jdbc.update("""
				DELETE h FROM coupon_history h JOIN coupon_issue i ON i.issue_id = h.issue_id
				WHERE i.event_id = ?
				""", eventId);
		jdbc.update("DELETE FROM coupon_issue WHERE event_id = ?", eventId);
		jdbc.update("DELETE FROM issue_failure_log WHERE event_id = ?", eventId);
		Long couponId = jdbc.queryForObject(
				"SELECT coupon_id FROM coupon_event WHERE event_id = ?", Long.class, eventId);
		jdbc.update("DELETE FROM coupon_event WHERE event_id = ?", eventId);
		jdbc.update("DELETE FROM coupon WHERE coupon_id = ?", couponId);
	}

	// 전 작업을 동시에 출발
	public static <T> List<T> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = tasks.stream()
					.map(task -> pool.submit(() -> {
						start.await();
						return task.call();
					}))
					.toList();
			start.countDown();

			List<T> results = new java.util.ArrayList<>(futures.size());
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	public static Map<String, Integer> tally(List<String> outcomes) {
		Map<String, Integer> counts = new java.util.HashMap<>();
		outcomes.forEach(outcome -> counts.merge(outcome, 1, Integer::sum));
		return counts;
	}

	static UUID key() {
		return UUID.randomUUID();
	}

	static Instant now() {
		return Instant.now();
	}

	static AtomicInteger counter() {
		return new AtomicInteger();
	}
}
