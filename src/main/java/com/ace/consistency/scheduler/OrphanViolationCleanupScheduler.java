package com.ace.consistency.scheduler;

import com.ace.consistency.repository.VerificationViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ALL 스코프 배치 Step이 CheckResultAccumulatorWriter로 verification_violation 행을
 * stepExecutionId로 임시 태깅해둔 뒤, ConsistencyStepCompletionListener(afterStep)까지
 * 도달하지 못하고 비정상 종료되면(예: 프로세스 강제 종료) 그 행들이 verification_result와
 * 연결되지도, 삭제되지도 못한 채 고아로 남을 수 있다. 이 스케줄러는 그런 고아 행을 주기적으로
 * 정리하는 안전망이다.
 *
 * orphan-threshold-minutes는 행의 생성 시각이 아니라, 그 행을 태깅한 Step이 마지막으로
 * 진행(청크 커밋)한 시각 기준의 "최대 무진행 허용 시간"이다 — 즉 청크 하나를 처리하는 데
 * 걸릴 수 있는 최대 시간으로 해석해야 한다. Step이 아직 살아서 계속 청크를 커밋 중이면
 * 총 소요 시간과 무관하게 삭제 대상에서 제외되고, 진행이 이 시간만큼 멈춘 경우에만
 * (비정상 종료로 간주해) 정리 대상이 된다. 자세한 근거는
 * {@link VerificationViolationRepository#deleteOrphansStaleSince} 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "consistency.violation-cleanup",
		name = "enabled",
		havingValue = "true")
public class OrphanViolationCleanupScheduler {

	private final VerificationViolationRepository violationRepository;

	@Value("${consistency.violation-cleanup.orphan-threshold-minutes}")
	private long orphanThresholdMinutes;

	@Scheduled(
			initialDelayString = "${consistency.violation-cleanup.fixed-delay-ms}",
			fixedDelayString = "${consistency.violation-cleanup.fixed-delay-ms}")
	public void run() {
		LocalDateTime threshold = LocalDateTime.now().minusMinutes(orphanThresholdMinutes);
		int deleted = violationRepository.deleteOrphansStaleSince(threshold);
		if (deleted > 0) {
			log.warn("verification_violation 고아 행 {}건을 정리했습니다. threshold={}", deleted, threshold);
		}
	}
}
