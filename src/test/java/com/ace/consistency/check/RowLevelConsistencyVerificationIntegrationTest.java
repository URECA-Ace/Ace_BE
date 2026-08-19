package com.ace.consistency.check;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@Testcontainers
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create")
class RowLevelConsistencyVerificationIntegrationTest {

	@Container
	@ServiceConnection
	static final MySQLContainer MYSQL =
			new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private CouponIssueStructuralConsistencyCheck issueStructuralCheck;

	@Autowired
	private CouponHistoryStructuralConsistencyCheck historyStructuralCheck;

	@Autowired
	private VerificationResultRepository resultRepository;

	@Autowired
	private CouponEventRepository eventRepository;

	@Autowired
	private CouponRepository couponRepository;

	@Test
	void 구조_검증은_공통_Runner를_통해_실행되고_결과가_저장된다() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 18, 10, 0);
		Coupon coupon = couponRepository.save(Coupon.builder()
				.couponName("row-level-test-coupon")
				.type("FIXED")
				.value(1_000L)
				.validHours(24)
				.createdAt(now)
				.build());
		Long eventId = eventRepository.save(CouponEvent.builder()
				.coupon(coupon)
				.round(1)
				.openAt(now)
				.closeAt(now.plusHours(1))
				.totalStock(10)
				.remainingStock(10)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.createdAt(now)
				.updatedAt(now)
				.build()).getId();
		long beforeCount = resultRepository.count();

		List<ConsistencyCheck> checks = List.of(issueStructuralCheck, historyStructuralCheck);
		List<VerificationResult> results = runner.run(
				checks, Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(VerificationResult::isPass);
		assertThat(resultRepository.count()).isEqualTo(beforeCount + results.size());
	}
}
