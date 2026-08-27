package com.ace.consistency.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.dto.response.ConsistencyResultPageResponse;
import com.ace.consistency.dto.response.ConsistencyResultResponse;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.repository.VerificationResultRepository;

import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsistencyReportService {

	private final VerificationResultRepository repository;

	public ConsistencyResultPageResponse findResults(VerificationResult.Status status, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size,
				Sort.by(Sort.Direction.DESC, "executedAt").and(Sort.by(Sort.Direction.DESC, "id")));
		var result = status == null
				? repository.findAll(pageable)
				: repository.findByStatus(status, pageable);
		return ConsistencyResultPageResponse.from(result);
	}

	public ConsistencyResultResponse findResult(long id) {
		VerificationResultEntity entity = repository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "정합성 검증 결과를 찾을 수 없습니다."));
		return ConsistencyResultResponse.from(entity);
	}
}
