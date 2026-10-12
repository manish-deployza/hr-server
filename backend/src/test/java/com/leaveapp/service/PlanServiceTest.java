package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.repo.PlanRepo;

// Catalog reads run against the tiers PlanService seeds into H2 on context startup;
// seedDefaults' already-seeded early return is driven through a hand-written PlanRepo
// double, since the shared context only ever seeds once.
public class PlanServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private PlanService planSvc;

    /* Public Methods */

    @Test
    public void seedDefaults_emptyTable_seedsFourTiers() {
        RecordingPlanRepo repo = new RecordingPlanRepo(0);
        PlanService svc = serviceWith(repo.proxy);

        svc.seedDefaults();

        assertEquals(List.of(PlanCode.values()),
            repo.saved.stream().map(PlanPojo::getCode).toList());
        assertEquals(Integer.MAX_VALUE, repo.saved.get(3).getSeatLimit());
        assertTrue(repo.saved.get(3).isCustomPricing());
        assertFalse(repo.saved.get(0).isCustomPricing());
    }

    @Test
    public void seedDefaults_alreadySeeded_savesNothing() {
        RecordingPlanRepo repo = new RecordingPlanRepo(4);
        PlanService svc = serviceWith(repo.proxy);

        svc.seedDefaults();

        assertTrue(repo.saved.isEmpty());
    }

    @Test
    public void listActive_returnsSeededActiveTiers() {
        List<PlanCode> codes = planSvc.listActive().stream().map(PlanPojo::getCode).toList();
        assertTrue(codes.containsAll(List.of(PlanCode.values())));
    }

    @Test
    public void findById_existing_returnsPlan() {
        PlanPojo free = planSvc.findByCode(PlanCode.FREE);
        assertEquals(PlanCode.FREE, planSvc.findById(free.getId()).getCode());
    }

    @Test
    public void findById_missing_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> planSvc.findById(-1L));
    }

    @Test
    public void findByCode_existing_returnsPlan() {
        assertEquals("Starter", planSvc.findByCode(PlanCode.STARTER).getName());
    }



    /* Private Helper Methods */

    private static PlanService serviceWith(PlanRepo repo) {
        PlanService svc = new PlanService();
        ReflectionTestUtils.setField(svc, "planRepo", repo);
        return svc;
    }

    /* Test Doubles */

    // Only count() and save() are ever reached by seedDefaults.
    private static class RecordingPlanRepo implements java.lang.reflect.InvocationHandler {

        final List<PlanPojo> saved = new ArrayList<>();
        private final long existing;
        final PlanRepo proxy = (PlanRepo) Proxy.newProxyInstance(
            PlanRepo.class.getClassLoader(), new Class<?>[] { PlanRepo.class }, this);

        RecordingPlanRepo(long existing) {
            this.existing = existing;
        }

        @Override
        public Object invoke(Object p, java.lang.reflect.Method m, Object[] args) {
            return switch (m.getName()) {
                case "count" -> existing;
                case "save" -> {
                    saved.add((PlanPojo) args[0]);
                    yield args[0];
                }
                default -> throw new UnsupportedOperationException(m.getName());
            };
        }
    }
}
