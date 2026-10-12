package com.leaveapp.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.service.SubscriptionService;

// Plain unit test: both polls ask for their configured batch size, process every id, and
// one failing row is logged and skipped so it never blocks the rest of the batch. The two
// polls are independent (reconcile never touches grace expiry and vice versa).
public class SubscriptionSchedulerTest {

    /* Variables */

    private final RecordingSubSvc subSvc = new RecordingSubSvc();
    private final SubscriptionScheduler scheduler = new SubscriptionScheduler();

    {
        ReflectionTestUtils.setField(scheduler, "subSvc", subSvc);
        ReflectionTestUtils.setField(scheduler, "reconcileBatchSize", 11);
        ReflectionTestUtils.setField(scheduler, "graceBatchSize", 22);
    }

    /* Public Methods */

    @Test
    public void reconcile_nothingDue_onlyQueries() {
        scheduler.reconcile();
        assertEquals(List.of("findReconcilable:11"), subSvc.log);
    }

    @Test
    public void reconcile_reconcilesEachId() {
        subSvc.reconcilable = List.of(1L, 2L);

        scheduler.reconcile();

        assertEquals(List.of("findReconcilable:11", "reconcile:1", "reconcile:2"), subSvc.log);
    }

    @Test
    public void reconcile_oneFails_restStillProcessed() {
        subSvc.reconcilable = List.of(1L, 2L, 3L);
        subSvc.failFor = Set.of(2L);

        scheduler.reconcile();

        assertEquals(List.of("findReconcilable:11", "reconcile:1", "reconcile:2", "reconcile:3"), subSvc.log);
    }

    @Test
    public void reconcile_queryFails_propagates() {
        subSvc.queryFails = true;
        assertThrows(IllegalStateException.class, scheduler::reconcile);
    }

    @Test
    public void expireGrace_nothingDue_onlyQueries() {
        scheduler.expireGrace();
        assertEquals(List.of("findGraceExpired:22"), subSvc.log);
    }

    @Test
    public void expireGrace_expiresEachId() {
        subSvc.graceExpired = List.of(5L, 6L);

        scheduler.expireGrace();

        assertEquals(List.of("findGraceExpired:22", "expireGrace:5", "expireGrace:6"), subSvc.log);
    }

    @Test
    public void expireGrace_oneFails_restStillProcessed() {
        subSvc.graceExpired = List.of(5L, 6L, 7L);
        subSvc.failFor = Set.of(5L, 7L);

        scheduler.expireGrace();

        assertEquals(List.of("findGraceExpired:22", "expireGrace:5", "expireGrace:6", "expireGrace:7"), subSvc.log);
    }

    @Test
    public void expireGrace_queryFails_propagates() {
        subSvc.queryFails = true;
        assertThrows(IllegalStateException.class, scheduler::expireGrace);
    }

    /* Test Doubles */

    private static final class RecordingSubSvc extends SubscriptionService {
        private final List<String> log = new ArrayList<>();
        private List<Long> reconcilable = List.of();
        private List<Long> graceExpired = List.of();
        private Set<Long> failFor = Set.of();
        private boolean queryFails;

        @Override
        public List<Long> findReconcilableIds(int limit) {
            log.add("findReconcilable:" + limit);
            if (queryFails) {
                throw new IllegalStateException("db down");
            }
            return reconcilable;
        }

        @Override
        public void reconcileOne(Long subscriptionId) {
            log.add("reconcile:" + subscriptionId);
            if (failFor.contains(subscriptionId)) {
                throw new IllegalStateException("gateway down");
            }
        }

        @Override
        public List<Long> findGraceExpiredIds(int limit) {
            log.add("findGraceExpired:" + limit);
            if (queryFails) {
                throw new IllegalStateException("db down");
            }
            return graceExpired;
        }

        @Override
        public void expireGraceOne(Long subscriptionId) {
            log.add("expireGrace:" + subscriptionId);
            if (failFor.contains(subscriptionId)) {
                throw new IllegalStateException("db hiccup");
            }
        }
    }
}
