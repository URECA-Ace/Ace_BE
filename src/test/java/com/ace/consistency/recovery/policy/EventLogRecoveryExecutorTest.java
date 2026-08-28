package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

@ExtendWith(MockitoExtension.class)
class EventLogRecoveryExecutorTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponHistoryRepository couponHistoryRepository;

	@Mock
	private CouponEventRepository couponEventRepository;

	private EventLogRecoveryExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new EventLogRecoveryExecutor(couponIssueRepository, couponHistoryRepository, couponEventRepository);
	}

	private CouponEvent existingEvent() {
		return CouponEvent.builder().id(EVENT_ID).build();
	}

	private CouponIssue issue(Long id, CouponIssueStatus status) {
		return CouponIssue.builder().id(id).status(status).build();
	}

	private CouponHistory history(
			Long historyId, Long issueId, CouponIssueStatus from, CouponIssueStatus to, LocalDateTime occurredAt) {
		return CouponHistory.builder()
				.id(historyId)
				.couponIssue(CouponIssue.builder().id(issueId).build())
				.fromStatus(from)
				.toStatus(to)
				.occurredAt(occurredAt)
				.build();
	}

	// ----------------- restoreStateMachine -----------------

	@Test
	@DisplayName("이벤트가 존재하지 않으면 예외를 던지지 않고 FAIL Outcome을 반환한다")
	void restoreStateMachine_이벤트없음_FAIL() {
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.empty());

		RecoveryOutcome outcome = executor.restoreStateMachine(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("eventId=" + EVENT_ID);
	}

	@Test
	@DisplayName("이력 체인이 끊긴 곳이 없으면 아무것도 지우지 않고 SUCCESS를 반환한다")
	void restoreStateMachine_정상체인_SUCCESS() {
		LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
		List<CouponHistory> chain = List.of(
				history(1L, 10L, null, CouponIssueStatus.ISSUED, t0),
				history(2L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, t0.plusMinutes(1)));
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(chain);

		RecoveryOutcome outcome = executor.restoreStateMachine(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getMessage()).contains("체인 붕괴 상태가 아닙니다");
		verify(couponHistoryRepository, never()).deleteAllByIdInBatch(any());
	}

	@Test
	@DisplayName("끊긴 지점 이후 이력을 지우고 status를 끊기기 직전 상태로 되돌린다")
	void restoreStateMachine_회수가능_이력삭제및상태복원() {
		LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
		// 정상: null->ISSUED, ISSUED->USED. 이후 락이 뚫려 fromStatus=ISSUED(실제 직전 상태는 USED)인
		// 스트레이 레코드가 하나 더 남아 체인이 끊긴 상황 — toStatus가 ISSUED라 USED/EXPIRED가 아니므로 자동 복구 대상이다.
		List<CouponHistory> chain = List.of(
				history(1L, 10L, null, CouponIssueStatus.ISSUED, t0),
				history(2L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, t0.plusMinutes(1)),
				history(3L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.ISSUED, t0.plusMinutes(2)));
		CouponIssue lockedIssue = issue(10L, CouponIssueStatus.ISSUED);
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(chain);
		given(couponIssueRepository.findByIdForUpdate(10L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = executor.restoreStateMachine(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		verify(couponHistoryRepository).deleteAllByIdInBatch(List.of(3L));
		assertThat(lockedIssue.getStatus()).isEqualTo(CouponIssueStatus.USED); // 끊기기 직전 이력(2번)의 to_status로 복원
	}

	@Test
	@DisplayName("삭제 범위 안에 USED/EXPIRED 전이가 있으면 회수하지 않고 FAIL로 남긴다")
	void restoreStateMachine_삭제범위에_USED있으면_회수불가() {
		LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
		// 스트레이 레코드 자체가 실사용 사실(USED)을 담고 있는 경우 — 지우면 실제 사용 이력이 사라진다.
		List<CouponHistory> chain = List.of(
				history(1L, 10L, null, CouponIssueStatus.ISSUED, t0),
				history(2L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, t0.plusMinutes(1)),
				history(3L, 10L, null, CouponIssueStatus.USED, t0.plusMinutes(2)));
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(chain);

		RecoveryOutcome outcome = executor.restoreStateMachine(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("관리자 확인이 필요");
		verify(couponHistoryRepository, never()).deleteAllByIdInBatch(any());
		verify(couponIssueRepository, never()).findByIdForUpdate(any());
	}

	@Test
	@DisplayName("최초 이력 자체가 손상되어 있으면(null->ISSUED가 아님) 회수하지 않고 FAIL로 남긴다")
	void restoreStateMachine_최초이력손상_회수불가() {
		LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
		List<CouponHistory> chain = List.of(
				history(1L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, t0)); // 첫 레코드가 null->ISSUED가 아님
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(chain);

		RecoveryOutcome outcome = executor.restoreStateMachine(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		verify(couponHistoryRepository, never()).deleteAllByIdInBatch(any());
	}

	// ----------------- syncTimeHistory -----------------

	@Test
	@DisplayName("이벤트가 존재하지 않으면 예외를 던지지 않고 FAIL Outcome을 반환한다")
	void syncTimeHistory_이벤트없음_FAIL() {
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.empty());

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("eventId=" + EVENT_ID);
	}

	@Test
	@DisplayName("동기화가 필요한 발급 건이 없으면 아무것도 바꾸지 않고 SUCCESS를 반환한다")
	void syncTimeHistory_대상없음_SUCCESS() {
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), any())).willReturn(List.of());

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getMessage()).contains("시간 동기화가 필요한 발급 건이 없습니다");
	}

	@Test
	@DisplayName("USED 건은 history 최신 시각과 1초 넘게 어긋나면 used_at을 history 기준으로 맞춘다")
	void syncTimeHistory_USED건_임계값초과시_usedAt동기화() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
		LocalDateTime historyTime = issuedAt.plusMinutes(1); // 1분 차이 -> 1초 임계값 초과
		CouponIssue candidate = CouponIssue.builder()
				.id(10L).status(CouponIssueStatus.USED).usedAt(issuedAt).build();
		CouponHistory latest = history(1L, 10L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, historyTime);
		CouponIssue lockedIssue = CouponIssue.builder()
				.id(10L).status(CouponIssueStatus.USED).usedAt(issuedAt).build();

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), any())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(10L)).willReturn(List.of(latest));
		given(couponIssueRepository.findByIdForUpdate(10L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(lockedIssue.getUsedAt()).isEqualTo(historyTime);
	}

	@Test
	@DisplayName("최초 발급(ISSUED) 건은 history 최신 시각과 어긋나면 issued_at을 history 기준으로 맞춘다")
	void syncTimeHistory_최초발급건_임계값초과시_issuedAt동기화() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
		LocalDateTime historyTime = issuedAt.plusSeconds(5);
		CouponIssue candidate = CouponIssue.builder()
				.id(11L).status(CouponIssueStatus.ISSUED).issuedAt(issuedAt).build();
		CouponHistory latest = history(2L, 11L, null, CouponIssueStatus.ISSUED, historyTime); // 최초 발급: from_status null
		CouponIssue lockedIssue = CouponIssue.builder()
				.id(11L).status(CouponIssueStatus.ISSUED).issuedAt(issuedAt).build();

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), any())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(11L)).willReturn(List.of(latest));
		given(couponIssueRepository.findByIdForUpdate(11L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(lockedIssue.getIssuedAt()).isEqualTo(historyTime);
	}

	@Test
	@DisplayName("최초 발급이 아닌 재진입(USED -> ISSUED 복원) 건은 자동 복구하지 않고 FAIL로 남긴다")
	void syncTimeHistory_재진입건_회수불가() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
		CouponIssue candidate = CouponIssue.builder()
				.id(12L).status(CouponIssueStatus.ISSUED).issuedAt(issuedAt).build();
		// 재진입: from_status가 null이 아님(USED -> ISSUED로 복원된 이력)
		CouponHistory latest = history(3L, 12L, CouponIssueStatus.USED, CouponIssueStatus.ISSUED, issuedAt.plusMinutes(5));

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), any())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(12L)).willReturn(List.of(latest));

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("재진입");
		verify(couponIssueRepository, never()).findByIdForUpdate(anyLong());
	}

	@Test
	@DisplayName("history와의 시간 차이가 1초 이내면 동기화 대상에서 제외한다")
	void syncTimeHistory_임계값이내면_동기화하지않음() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
		LocalDateTime historyTime = issuedAt.plusNanos(500_000_000L); // 0.5초 차이 -> 허용 오차 이내
		CouponIssue candidate = CouponIssue.builder()
				.id(13L).status(CouponIssueStatus.USED).usedAt(issuedAt).build();
		CouponHistory latest = history(4L, 13L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, historyTime);

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(existingEvent()));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), any())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(13L)).willReturn(List.of(latest));

		RecoveryOutcome outcome = executor.syncTimeHistory(EVENT_ID);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		verify(couponIssueRepository, never()).findByIdForUpdate(anyLong());
	}
}
