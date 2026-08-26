package com.ace.consistency.recovery;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecoveryResultRepository extends JpaRepository<RecoveryResult, Long> {

	List<RecoveryResult> findByVerificationResultIdOrderByCreatedAtDesc(Long verificationResultId);
}
