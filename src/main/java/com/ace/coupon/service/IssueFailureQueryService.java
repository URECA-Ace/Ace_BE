package com.ace.coupon.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.IssueFailureActionResponse;
import com.ace.coupon.dto.response.IssueFailureDetailResponse;
import com.ace.coupon.dto.response.IssueFailurePageResponse;
import com.ace.coupon.dto.response.IssueFailureSummaryResponse;
import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.failure.IssueFailureStageGroup;
import com.ace.coupon.persistence.failure.IssueFailureStatus;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.RequiredArgsConstructor;

// 발급 실패(DLQ) 조회
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueFailureQueryService {

	// 경보에 실을 회차 수
	private static final int BLOCKED_EVENT_SAMPLE_SIZE = 20;

	// 자동 재처리기와 같은 순서로 보여준다
	private static final Sort SORT =
			Sort.by(Sort.Order.asc("lastAttemptAt"), Sort.Order.asc("id"));

	private final IssueFailureLogRepository repository;
	private final IssueFailureActionPolicy actionPolicy;

	public IssueFailurePageResponse findFailures(
			Long eventId,
			IssueFailureStage stage,
			IssueFailureStatus status,
			String requestId,
			int page,
			int size) {

		if (requestId != null && !requestId.isBlank()) {
			return IssueFailurePageResponse.from(byRequestId(requestId, page, size));
		}

		Pageable pageable = PageRequest.of(page, size, SORT);
		IssueFailureStageGroup confirmGroup = IssueFailureStageGroup.CONFIRM;
		IssueFailureStageGroup persistGroup = IssueFailureStageGroup.PERSIST;

		Page<IssueFailureLog> found = status == null
				? repository.findFiltered(eventId, stage, pageable)
				: switch (status) {
					case SETTLED -> repository.findSettled(
							eventId, stage, IssueFailureStage.CONFIRM,
							confirmGroup.getSettledResults(), persistGroup.getSettledResults(), pageable);
					case RETRYABLE -> repository.findRetryable(
							eventId, stage, IssueFailureStage.CONFIRM,
							confirmGroup.getRetryableResults(), persistGroup.getRetryableResults(), pageable);
					case UNRECOVERABLE -> repository.findUnrecoverable(
							eventId, stage, IssueFailureStage.CONFIRM,
							knownResults(confirmGroup), knownResults(persistGroup), pageable);
				};

		return IssueFailurePageResponse.from(found);
	}

	public IssueFailureDetailResponse findDetail(long failureId) {
		IssueFailureLog failure = repository.findById(failureId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_FAILURE_NOT_FOUND));
		return IssueFailureDetailResponse.of(failure, availableActions(failure));
	}

	public List<IssueFailureActionResponse> findActions(long failureId) {
		IssueFailureLog failure = repository.findById(failureId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_FAILURE_NOT_FOUND));
		return availableActions(failure);
	}

	public IssueFailureSummaryResponse findSummary() {
		List<IssueFailureSummaryResponse.GroupSummary> groups = new ArrayList<>();
		Set<Long> blocked = new HashSet<>();

		for (IssueFailureStageGroup group : IssueFailureStageGroup.values()) {
			groups.add(new IssueFailureSummaryResponse.GroupSummary(
					group,
					group.getLabel(),
					repository.countSettledInGroup(group.getStages(), group.getSettledResults()),
					repository.countRetryableInGroup(group.getStages(), group.getRetryableResults()),
					repository.countUnrecoverableInGroup(group.getStages(), knownResults(group))));

			blocked.addAll(repository.findBlockedEventIds(
					group.getStages(),
					group.getSettledResults(),
					PageRequest.of(0, BLOCKED_EVENT_SAMPLE_SIZE)));
		}

		return new IssueFailureSummaryResponse(groups, blocked.stream().sorted().toList());
	}

	private List<IssueFailureActionResponse> availableActions(IssueFailureLog failure) {
		return actionPolicy.availableActions(failure).stream()
				.map(IssueFailureActionResponse::from)
				.toList();
	}

	// requestId 는 유니크에 가까워 페이징 없이 읽고 잘라 준다
	private Page<IssueFailureLog> byRequestId(String requestId, int page, int size) {
		List<IssueFailureLog> all = repository.findAllByRequestId(requestId.trim());
		int from = Math.min(page * size, all.size());
		int to = Math.min(from + size, all.size());
		return new PageImpl<>(all.subList(from, to), PageRequest.of(page, size), all.size());
	}

	private static Set<String> knownResults(IssueFailureStageGroup group) {
		Set<String> known = new HashSet<>(group.getSettledResults());
		known.addAll(group.getRetryableResults());
		return known;
	}
}
