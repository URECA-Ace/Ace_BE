package com.ace.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class DummyDataGenerator implements CommandLineRunner {

	private final JdbcTemplate jdbcTemplate;

	private static final int USER_COUNT = 1_000_000;
	private static final int EVENT_COUNT = 30;
	private static final int ISSUE_PER_EVENT = 100_000; // 30 * 100,000 = 3,000,000
	private static final int BATCH_SIZE = 2000;

	private static final String[] STATUSES = {"ISSUED", "ISSUED", "ISSUED", "USED", "USED", "CANCELED", "EXPIRED"};
	// 비율: ISSUED 3/7, USED 2/7, CANCELED 1/7, EXPIRED 1/7 정도로 대충 분산

	@Override
	public void run(String... args) {
		if (!"true".equals(System.getProperty("generate.dummy"))) return;

		Long couponId = generateCoupon();
		Map<Long, Integer> perUserLimitByEvent = generateCouponEvents(couponId);
		generateUsers();
		generateCouponIssues(perUserLimitByEvent);
	}

	// 1. 쿠폰 1건
	private Long generateCoupon() {
		String sql = "INSERT INTO coupon (coupon_name, type, value, valid_hours, created_at) VALUES (?, ?, ?, ?, ?)";
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(con -> {
			PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, "데이터 무제한 제공");
			ps.setString(2, "5gdata");
			ps.setLong(3, 0L);
			ps.setInt(4, 24);
			ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
			return ps;
		}, keyHolder);
		Long couponId = keyHolder.getKey().longValue();
		log.info("coupon 생성 완료: id={}", couponId);
		return couponId;
	}

	// 2. 쿠폰 이벤트 30회차
	private Map<Long, Integer> generateCouponEvents(Long couponId) {
		String sql = """
            INSERT INTO coupon_event
            (coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

		Map<Long, Integer> perUserLimitByEvent = new LinkedHashMap<>();
		LocalDateTime now = LocalDateTime.now();

		for (int round = 1; round <= EVENT_COUNT; round++) {
			LocalDateTime openAt = now.minusDays(EVENT_COUNT - round); // 과거 회차부터 오늘까지
			LocalDateTime closeAt = openAt.plusHours(24);

			KeyHolder keyHolder = new GeneratedKeyHolder();
			int finalRound = round;
			int perUserLimit = 1;
			jdbcTemplate.update(con -> {
				PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
				ps.setLong(1, couponId);
				ps.setInt(2, finalRound);
				ps.setTimestamp(3, Timestamp.valueOf(openAt));
				ps.setTimestamp(4, Timestamp.valueOf(closeAt));
				ps.setInt(5, ISSUE_PER_EVENT);       // total_stock
				ps.setInt(6, 0);                     // remaining_stock (전부 소진됐다고 가정)
				ps.setInt(7, ISSUE_PER_EVENT);        // issued_quantity
				ps.setInt(8, perUserLimit);                      // per_user_limit
				ps.setString(9, "CLOSED");            // 이미 종료된 회차로 가정
				ps.setTimestamp(10, Timestamp.valueOf(now));
				ps.setTimestamp(11, Timestamp.valueOf(now));
				return ps;
			}, keyHolder);

			Long eventId = keyHolder.getKey().longValue();
			perUserLimitByEvent.put(eventId, perUserLimit);
		}
		log.info("coupon_event {}건 생성 완료", perUserLimitByEvent.size());
		return perUserLimitByEvent;
	}

	// 3. 유저 100만
	private void generateUsers() {
		String sql = "INSERT INTO user (email, name, phone, created_at) VALUES (?, ?, ?, ?)";
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		Faker faker = new Faker(new Locale("ko"));

		for (int i = 1; i <= USER_COUNT; i++) {
			batch.add(new Object[]{
					"user" + i + "@test.com",
					faker.name().fullName(),
					faker.phoneNumber().cellPhone(),
					Timestamp.valueOf(LocalDateTime.now())
			});
			if (i % BATCH_SIZE == 0) {
				jdbcTemplate.batchUpdate(sql, batch);
				batch.clear();
				if (i % 100_000 == 0) log.info("user {}/{}", i, USER_COUNT);
			}
		}
		if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
		log.info("user 생성 완료");
	}

	// 4. 쿠폰 발급 이력 300만
	private void generateCouponIssues(Map<Long, Integer> perUserLimitByEvent) {
		String sql = """
           INSERT INTO coupon_issue
           (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, used_at, canceled_at, created_at, message_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           """;

		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

		long seed = 20260813L;
		Random random = new Random(seed);
		int total = 0;

		for (Map.Entry<Long, Integer> entry : perUserLimitByEvent.entrySet()) {
			Long eventId = entry.getKey();
			int perUserLimit = entry.getValue();

			List<Long> userPool = buildUserSlotPool(ISSUE_PER_EVENT, perUserLimit, USER_COUNT, random);
			Collections.shuffle(userPool, random);

			for (int seq = 1; seq <= ISSUE_PER_EVENT; seq++) {
				long userId = userPool.get(seq - 1);
				String status = STATUSES[random.nextInt(STATUSES.length)];

				LocalDateTime issuedAt = LocalDateTime.now().minusDays(random.nextInt(30));
				LocalDateTime validFrom = issuedAt;
				LocalDateTime validTo = issuedAt.plusHours(24);

				LocalDateTime usedAt = "USED".equals(status)
						? issuedAt.plusHours(random.nextInt(20) + 1) : null;
				LocalDateTime canceledAt = "CANCELED".equals(status)
						? issuedAt.plusHours(random.nextInt(5) + 1) : null;

				batch.add(new Object[]{
						eventId, userId, seq, UUID.randomUUID().toString(), status,
						Timestamp.valueOf(issuedAt), Timestamp.valueOf(validFrom), Timestamp.valueOf(validTo),
						usedAt != null ? Timestamp.valueOf(usedAt) : null,
						canceledAt != null ? Timestamp.valueOf(canceledAt) : null,
						Timestamp.valueOf(issuedAt), UUID.randomUUID().toString()
				});

				total++;
				if (batch.size() == BATCH_SIZE) {
					jdbcTemplate.batchUpdate(sql, batch);
					batch.clear();
					if (total % 500_000 == 0) log.info("coupon_issue {}/{}", total, EVENT_COUNT * ISSUE_PER_EVENT);
				}
			}
		}
		if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
		log.info("coupon_issue 생성 완료: 총 {}건", total);
	}

	/**
	 * requiredCount 만큼의 슬롯을, 각 user가 최대 perUserLimit번만 차지하도록 채운 pool 생성.
	 * userCount * perUserLimit < requiredCount 이면 예외 (물리적으로 불가능한 조건).
	 */
	private List<Long> buildUserSlotPool(int requiredCount, int perUserLimit, int userCount, Random random) {
		long maxPossible = (long) userCount * perUserLimit;
		if (requiredCount > maxPossible) {
			throw new IllegalStateException(
					"요청 발급 건수(%d)가 perUserLimit(%d) 제약 하에서 만들 수 있는 최대치(%d)를 초과합니다."
							.formatted(requiredCount, perUserLimit, maxPossible));
		}

		// distinct user id를 필요한 만큼만 미리 셔플된 순서로 뽑기
		List<Long> allUserIds = new ArrayList<>(userCount);
		for (long i = 1; i <= userCount; i++) allUserIds.add(i);
		Collections.shuffle(allUserIds, random);

		List<Long> pool = new ArrayList<>(requiredCount);
		int idx = 0;
		outer:
		for (int round = 0; round < perUserLimit; round++) {
			for (int i = 0; i < userCount; i++) {
				pool.add(allUserIds.get(i));
				idx++;
				if (idx == requiredCount) break outer;
			}
		}
		return pool;
	}
}