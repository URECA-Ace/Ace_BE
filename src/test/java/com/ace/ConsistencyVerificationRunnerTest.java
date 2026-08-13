package com.ace;

import com.ace.consistency.common.*;
import com.ace.consistency.repository.VerificationResultRepository;
import org.junit.jupiter.api.Test;
 
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
/**
 * ConsistencyVerificationRunner가 실제로 어떻게 동작하는지 콘솔에서 눈으로 확인하기 위한 테스트.
 * DB/Redis/Kafka 없이, 이 파일 안에서 가짜 Check와 결과 출력까지 전부 처리한다.
 *
 * IDE에서 이 파일을 그냥 실행(Run Test)하면 콘솔에 결과 표가 출력된다.
 */
class ConsistencyVerificationRunnerTest {
 
    @Test
    void 정상_케이스와_실패_케이스가_섞여서_나온다() {
        var repo = new PrintingRepository();
        var runner = new ConsistencyVerificationRunner(repo);
 
        List<ConsistencyCheck> checks = List.of(
                alwaysPass("StockConsistencyCheck"),
                alwaysFail("PerUserLimitCheck", Map.of("violatingUserIds", List.of(1001L, 2002L)))
        );
 
        List<VerificationResult> results = runner.run(checks, Scope.ofEvent(123L), TriggerType.EVENT_TRIGGER);
 
        assertEquals(1, results.stream().filter(VerificationResult::isPass).count());
        assertEquals(1, results.stream().filter(r -> r.getStatus() == VerificationResult.Status.FAIL).count());
    }
 
    @Test
    void 하나가_예외를_던져도_나머지는_계속_실행된다() {
        var repo = new PrintingRepository();
        var runner = new ConsistencyVerificationRunner(repo);
 
        List<ConsistencyCheck> checks = List.of(
                alwaysPass("StateTransitionCheck"),
                alwaysThrow("PipelineConsistencyCheck", new IllegalStateException("Redis connection timeout"))
        );
 
        LocalDateTime to = LocalDateTime.now().minusMinutes(1);
        LocalDateTime from = to.minusMinutes(5);
        List<VerificationResult> results = runner.run(checks, Scope.ofAsOfRange(from, to), TriggerType.SCHEDULED);
 
        assertTrue(results.stream().anyMatch(r -> r.getStatus() == VerificationResult.Status.ERROR));
        assertTrue(results.stream().anyMatch(VerificationResult::isPass));
    }
 
    @Test
    void 지원하지_않는_스코프로_호출하면_ERROR가_된다() {
        var repo = new PrintingRepository();
        var runner = new ConsistencyVerificationRunner(repo);
 
        // AS_OF_RANGE만 지원하는 Check를 EVENT 스코프로 잘못 호출
        ConsistencyCheck check = checkSupporting(Set.of(Scope.ScopeType.AS_OF_RANGE), "StateTransitionCheck");
 
        List<VerificationResult> results = runner.run(List.of(check), Scope.ofEvent(999L), TriggerType.ON_DEMAND);
 
        assertEquals(VerificationResult.Status.ERROR, results.get(0).getStatus());
    }
 
    @Test
    void 같은_Check가_EVENT와_ALL_스코프_둘_다_지원한다() {
        var repo = new PrintingRepository();
        var runner = new ConsistencyVerificationRunner(repo);
 
        ConsistencyCheck stockCheck =
                checkSupporting(Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL), "StockConsistencyCheck");
 
        runner.run(List.of(stockCheck), Scope.ofEvent(1L), TriggerType.EVENT_TRIGGER);
        List<VerificationResult> allResult = runner.run(List.of(stockCheck), Scope.all(), TriggerType.ON_DEMAND);
 
        assertTrue(allResult.get(0).isPass());
    }
 
    // ----------------- 아래는 테스트용 헬퍼 (가짜 Check 생성 + 콘솔 출력) -----------------
 
    private static ConsistencyCheck alwaysPass(String name) {
        return checkReturning(name, ConsistencyCheck.CheckOutcome.pass());
    }
 
    private static ConsistencyCheck alwaysFail(String name, Map<String, Object> diff) {
        return checkReturning(name, ConsistencyCheck.CheckOutcome.fail(diff));
    }
 
    private static ConsistencyCheck alwaysThrow(String name, RuntimeException ex) {
        return new ConsistencyCheck() {
            @Override
            public String getName() { return name; }
 
            @Override
            public Set<Scope.ScopeType> supportedScopeTypes() {
                return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.AS_OF_RANGE, Scope.ScopeType.ALL);
            }
 
            @Override
            public CheckOutcome check(Scope scope) { throw ex; }
        };
    }
 
    private static ConsistencyCheck checkReturning(String name, ConsistencyCheck.CheckOutcome outcome) {
        return checkSupporting(Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.AS_OF_RANGE, Scope.ScopeType.ALL),
                name, outcome);
    }
 
    private static ConsistencyCheck checkSupporting(Set<Scope.ScopeType> scopes, String name) {
        return checkSupporting(scopes, name, ConsistencyCheck.CheckOutcome.pass());
    }
 
    private static ConsistencyCheck checkSupporting(Set<Scope.ScopeType> scopes, String name,
                                                      ConsistencyCheck.CheckOutcome outcome) {
        return new ConsistencyCheck() {
            @Override
            public String getName() { return name; }
 
            @Override
            public Set<Scope.ScopeType> supportedScopeTypes() { return scopes; }
 
            @Override
            public CheckOutcome check(Scope scope) { return outcome; }
        };
    }
 
    /** 저장 대신 콘솔에 표로 찍어주는 테스트 전용 Repository. */
    private static class PrintingRepository implements VerificationResultRepository {
        @Override
        public void saveAll(List<VerificationResult> results) {
            String format = "| %-24s | %-13s | %-28s | %-7s | %s%n";
            System.out.println("-".repeat(100));
            System.out.printf(format, "CHECK", "TRIGGER", "SCOPE", "STATUS", "DETAIL");
            System.out.println("-".repeat(100));
            for (VerificationResult r : results) {
                String detail = switch (r.getStatus()) {
                    case PASS -> "-";
                    case FAIL -> String.valueOf(r.getDiffDetail());
                    case ERROR -> r.getErrorMessage();
                };
                System.out.printf(format, r.getCheckName(), r.getTriggerType(), r.getScope(),
                        r.getStatus(), detail);
            }
            System.out.println("-".repeat(100));
        }
    }
}