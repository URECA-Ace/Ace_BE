package com.ace.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IssueFailureLogRepositoryTest {

	// 되살릴 수 있는 확정 실패
	private static final Set<String> RETRYABLE = Set.of("CALL_FAILED", "INTERNAL_WRITE_ERROR");

	@Autowired
	private IssueFailureLogRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("재처리 대상은 미해소이면서 되살릴 수 있는 확정 실패만 반환한다")
	void findsOnlyUnresolvedRetryableConfirmFailures() {
		long eventId = uniqueEventId();
		IssueFailureLog callFailed = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		IssueFailureLog writeError = persist(eventId, IssueFailureStage.CONFIRM, "INTERNAL_WRITE_ERROR", null);
		IssueFailureLog notRetryable = persist(eventId, IssueFailureStage.CONFIRM, "REQUEST_NOT_FOUND", null);
		IssueFailureLog resolved = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", LocalDateTime.now());
		IssueFailureLog otherStage = persist(eventId, IssueFailureStage.DB_INSERT, "CALL_FAILED", null);
		entityManager.flush();

		List<Long> found = idsOf(repository.findRetryTargets(
				IssueFailureStage.CONFIRM, RETRYABLE, PageRequest.of(0, 100)));

		assertThat(found).contains(callFailed.getId(), writeError.getId());
		assertThat(found).doesNotContain(
				notRetryable.getId(), resolved.getId(), otherStage.getId());
	}

	@Test
	@DisplayName("한 번도 시도하지 않은 건이 먼저, 그다음 오래 방치된 건이 온다")
	void ordersByLastAttemptAscending() {
		// 앞쪽 건이 계속 실패해도 시도 시각이 갱신되며 뒤로 밀려 다른 건이 차례를 얻는다
		long eventId = uniqueEventId();
		LocalDateTime now = LocalDateTime.now();
		IssueFailureLog recentlyTried = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		recentlyTried.recordAttempt(now);
		IssueFailureLog longAgo = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		longAgo.recordAttempt(now.minusHours(1));
		IssueFailureLog neverTried = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		entityManager.flush();
		entityManager.clear();

		List<Long> found = idsOf(repository.findRetryTargets(
				IssueFailureStage.CONFIRM, RETRYABLE, PageRequest.of(0, 100)));

		assertThat(found).containsSubsequence(
				neverTried.getId(), longAgo.getId(), recentlyTried.getId());
	}

	@Test
	@DisplayName("해소 표시를 하면 재처리 대상에서 빠진다")
	void excludesResolvedRecord() {
		long eventId = uniqueEventId();
		IssueFailureLog failure = persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		entityManager.flush();

		failure.resolve(LocalDateTime.now());
		entityManager.flush();
		entityManager.clear();

		List<Long> found = idsOf(repository.findRetryTargets(
				IssueFailureStage.CONFIRM, RETRYABLE, PageRequest.of(0, 100)));

		assertThat(found).doesNotContain(failure.getId());
	}

	@Test
	@DisplayName("되살릴 수 없는 미해소 건수를 센다")
	void countsUnrecoverableFailures() {
		long eventId = uniqueEventId();
		persist(eventId, IssueFailureStage.CONFIRM, "REQUEST_NOT_FOUND", null);
		persist(eventId, IssueFailureStage.CONFIRM, "CORRUPTED_STATE", null);
		persist(eventId, IssueFailureStage.CONFIRM, "CALL_FAILED", null);
		persist(eventId, IssueFailureStage.CONFIRM, "REQUEST_NOT_FOUND", LocalDateTime.now());
		entityManager.flush();

		long before = repository.countUnrecoverable(IssueFailureStage.CONFIRM, RETRYABLE);

		persist(eventId, IssueFailureStage.CONFIRM, "INVALID_ARGUMENT", null);
		entityManager.flush();

		assertThat(repository.countUnrecoverable(IssueFailureStage.CONFIRM, RETRYABLE))
				.isEqualTo(before + 1);
	}

	@Test
	@DisplayName("미해소 확정 실패가 남은 회차를 드러낸다")
	void findsEventIdsBlockedByUnresolvedFailures() {
		long blocked = uniqueEventId();
		long cleared = uniqueEventId();
		persist(blocked, IssueFailureStage.CONFIRM, "REQUEST_NOT_FOUND", null);
		persist(cleared, IssueFailureStage.CONFIRM, "CALL_FAILED", LocalDateTime.now());
		entityManager.flush();

		List<Long> blockedEventIds = repository.findBlockedEventIds(
				IssueFailureStage.CONFIRM, PageRequest.of(0, 100));

		// 되살릴 수 없는 건도 회차를 막으므로 재시도 가능 여부와 무관하게 잡혀야 한다
		assertThat(blockedEventIds).contains(blocked);
		assertThat(blockedEventIds).doesNotContain(cleared);
	}

	private long uniqueEventId() {
		return java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000L, 999_999_999L);
	}

	private List<Long> idsOf(List<IssueFailureLog> failures) {
		return failures.stream().map(IssueFailureLog::getId).toList();
	}

	private IssueFailureLog persist(
			long eventId, IssueFailureStage stage, String confirmResult, LocalDateTime resolvedAt) {
		IssueFailureLog failure = IssueFailureLog.builder()
				.eventId(eventId)
				.userId(1L)
				.requestId(UUID.randomUUID().toString())
				.issueSequence(1L)
				.failureStage(stage)
				.compensationResult(confirmResult)
				.errorMessage("테스트")
				.incidentId(UUID.randomUUID().toString())
				.occurredAt(LocalDateTime.now())
				.resolvedAt(resolvedAt)
				.build();
		entityManager.persist(failure);
		return failure;
	}
}
