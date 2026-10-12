package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.pojo.PlanPojo;

// PlanService.seedDefaults (@PostConstruct) has already inserted FREE/STARTER/GROWTH/
// ENTERPRISE by the time any test here runs -- those rows are asserted on directly
// rather than re-seeded. code is a unique PlanCode, so a test that saves its own row
// first deletes the seeded GROWTH row (rolled back with the test) and reuses its code.
public class PlanRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private PlanRepo planRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        PlanPojo saved = planRepo.save(freshPlan());
        assertNotNull(saved.getId());
    }

    @Test
    public void findByCode_returnsMatch() {
        Optional<PlanPojo> found = planRepo.findByCode(PlanCode.STARTER);
        assertTrue(found.isPresent());
    }

    @Test
    public void findByCode_noMatch_empty() {
        freshPlan();
        assertTrue(planRepo.findByCode(PlanCode.GROWTH).isEmpty());
    }

    @Test
    public void findByCode_seededFree_isCustomPricingFalse() {
        PlanPojo free = planRepo.findByCode(PlanCode.FREE).orElseThrow();
        assertFalse(free.isCustomPricing());
        assertEquals(5, free.getSeatLimit());
    }

    @Test
    public void findByCode_seededEnterprise_isCustomPricingTrue() {
        PlanPojo enterprise = planRepo.findByCode(PlanCode.ENTERPRISE).orElseThrow();
        assertTrue(enterprise.isCustomPricing());
    }

    @Test
    public void findByActiveTrue_includesSeededDefaults() {
        List<PlanPojo> active = planRepo.findByActiveTrue();
        assertTrue(active.stream().anyMatch(p -> PlanCode.FREE == p.getCode()));
        assertTrue(active.stream().anyMatch(p -> PlanCode.ENTERPRISE == p.getCode()));
    }

    @Test
    public void findByActiveTrue_excludesInactive() {
        PlanPojo p = freshPlan();
        p.setActive(false);
        planRepo.save(p);
        assertTrue(planRepo.findByActiveTrue().stream().noneMatch(x -> PlanCode.GROWTH == x.getCode()));
    }

    @Test
    public void save_nullCode_throws() {
        PlanPojo p = freshPlan();
        p.setCode(null);
        assertThrows(DataIntegrityViolationException.class, () -> {
            planRepo.save(p);
            planRepo.flush();
        });
    }

    @Test
    public void save_nullName_throws() {
        PlanPojo p = freshPlan();
        p.setName(null);
        assertThrows(DataIntegrityViolationException.class, () -> planRepo.saveAndFlush(p));
    }

    @Test
    public void save_nullCurrency_throws() {
        PlanPojo p = freshPlan();
        p.setCurrency(null);
        assertThrows(DataIntegrityViolationException.class, () -> planRepo.saveAndFlush(p));
    }

    @Test
    public void save_nullBillingPeriod_throws() {
        PlanPojo p = freshPlan();
        p.setBillingPeriod(null);
        assertThrows(DataIntegrityViolationException.class, () -> planRepo.saveAndFlush(p));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        PlanPojo p = freshPlan();
        p.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> planRepo.saveAndFlush(p));
    }

    @Test
    public void save_duplicateCode_throws() {
        PlanPojo dup = PlanTestData.aPlan(PlanCode.FREE);
        assertThrows(DataIntegrityViolationException.class, () -> planRepo.saveAndFlush(dup));
    }

    /* Private Helper Methods */

    // frees the GROWTH code (rolled back with the test) so a new row can take it
    private PlanPojo freshPlan() {
        planRepo.delete(planRepo.findByCode(PlanCode.GROWTH).orElseThrow());
        planRepo.flush();
        return PlanTestData.aPlan(PlanCode.GROWTH);
    }
}
