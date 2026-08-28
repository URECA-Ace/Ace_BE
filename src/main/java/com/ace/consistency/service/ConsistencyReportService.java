package com.ace.consistency.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.dto.response.ConsistencyResultPageResponse;
import com.ace.consistency.dto.response.ConsistencyResultResponse;
import com.ace.consistency.dto.response.ConsistencyViolationPageResponse;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.consistency.repository.VerificationViolationRepository;

import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsistencyReportService {

	private final VerificationResultRepository resultRepository;
	private final VerificationViolationRepository violationRepository;

	public ConsistencyResultPageResponse findResults(VerificationResult.Status status, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size,
				Sort.by(Sort.Direction.DESC, "executedAt").and(Sort.by(Sort.Direction.DESC, "id")));
		var result = status == null
				? resultRepository.findAll(pageable)
				: resultRepository.findByStatus(status, pageable);
		return ConsistencyResultPageResponse.from(result);
	}

	public ConsistencyResultResponse findResult(long id) {
		VerificationResultEntity entity = resultRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "정합성 검증 결과를 찾을 수 없습니다."));
		return ConsistencyResultResponse.from(entity);
	}

	public ConsistencyViolationPageResponse findViolations(long resultId, int page, int size) {
		if (!resultRepository.existsById(resultId)) {
			throw new ResponseStatusException(NOT_FOUND, "정합성 검증 결과를 찾을 수 없습니다.");
		}
		return ConsistencyViolationPageResponse.from(
				violationRepository.findByVerificationResultIdOrderByIdDesc(
						resultId, PageRequest.of(page, size)));
	}
}
