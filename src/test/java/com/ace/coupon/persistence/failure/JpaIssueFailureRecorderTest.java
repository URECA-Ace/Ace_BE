package com.ace.coupon.persistence.failure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.IssueFailureLogRepository;

// MySQL 완전 단절 중 실패 기록 자체가 실패하는 시나리오(2번): repository.save() 가 죽어도
// record() 는 이를 삼켜서 상위(IssuePersistenceCoordinator)로 전파하지 않는다 - 즉 DLQ 에는
// 아무 흔적도 남지 않는다.
@ExtendWith(MockitoExtension.class)
class JpaIssueFailureRecorderTest {

	@Mock
	private IssueFailureLogRepository repository;

	private JpaIssueFailureRecorder recorder;

	private final IssueFailure failure = new IssueFailure(
			7L, 1L, "req-1", 3L, IssueFailureStage.COMPENSATE, "SKIPPED_UNVERIFIED",
			"저장 실패", "11111111-2222-3333-4444-555555555555", Instant.now());

	@Test
	@DisplayName("저장 시도 자체가 DB 장애로 실패해도 예외를 삼키고 조용히 끝난다 - DLQ 에는 남지 않는다")
	void swallowsRepositoryFailureInsteadOfPropagating() {
		recorder = new JpaIssueFailureRecorder(repository, new CouponIssueRedisProperties(null, null));
		given(repository.save(any(IssueFailureLog.class)))
				.willThrow(new org.springframework.dao.DataAccessResourceFailureException("MySQL 연결 끊김"));

		assertThatCode(() -> recorder.record(failure)).doesNotThrowAnyException();

		verify(repository).save(any(IssueFailureLog.class));
	}
}
