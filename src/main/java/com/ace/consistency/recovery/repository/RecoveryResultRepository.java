package com.ace.consistency.recovery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ace.consistency.recovery.RecoveryResult;

@Repository
public interface RecoveryResultRepository extends JpaRepository<RecoveryResult, Long> {

	List<RecoveryResult> findByVerificationResultIdOrderByCreatedAtDesc(Long verificationResultId);
}
