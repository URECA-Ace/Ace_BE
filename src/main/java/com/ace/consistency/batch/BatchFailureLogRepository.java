package com.ace.consistency.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * BatchFailureLogEntity 영속화 담당.
 */
@Repository
public interface BatchFailureLogRepository extends JpaRepository<BatchFailureLogEntity, Long> {
	java.util.Optional<BatchFailureLogEntity> findTopByStatusOrderByOccurredAtDesc(String status);
	java.util.Optional<BatchFailureLogEntity> findTopByStatusInOrderByOccurredAtDesc(java.util.Collection<String> statuses);
}
