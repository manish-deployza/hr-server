package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB): PlanService -> PlanRepo -> DB (plan catalog)
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.BillingPeriod;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.repo.PlanRepo;

// Global subscription-plan catalog (not per-org). Bounded, fixed catalog -- same
// carve-out as leave_type -- so listActive/findByCode return unpaged lists safely.
@Service
public class PlanService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    @Autowired
    private PlanRepo planRepo;

    /* Constructors */

    /* Public Methods */

    // Seeds the four placeholder tiers once, on first boot against an empty table --
    // same "seed data, no migration tool" spirit as organization's own seeded default
    // row (see backend/CLAUDE.md). Prices/seat limits are placeholders; edit here (or
    // patch the plan table directly) before real launch -- see the feature doc.
    @PostConstruct
    public void seedDefaults() {
        if (planRepo.count() > 0) {
            return;
        }
        seed(PlanCode.FREE, "Free", 5, 0, false);
        seed(PlanCode.STARTER, "Starter", 25, 99_900, false);
        seed(PlanCode.GROWTH, "Growth", 100, 299_900, false);
        // Integer.MAX_VALUE -- never the seat-capping tier; self-serve checkout for it
        // is blocked in SubscriptionService.subscribe (isCustomPricing).
        seed(PlanCode.ENTERPRISE, "Enterprise", Integer.MAX_VALUE, 0, true);
        log.info("seeded default billing plans");
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<PlanPojo> listActive() {
        return planRepo.findByActiveTrue();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PlanPojo findById(Long id) {
        return planRepo.findById(id) //
            .orElseThrow(() -> new NotFoundException("plan not found id=" + id));
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PlanPojo findByCode(PlanCode code) {
        return planRepo.findByCode(code) //
            .orElseThrow(() -> new NotFoundException("plan not found code=" + code));
    }

    /* Private Helper Methods */

    private void seed(PlanCode code, String name, long seatLimit, long priceMinor, boolean customPricing) {
        PlanPojo p = new PlanPojo();
        p.setCode(code);
        p.setName(name);
        p.setSeatLimit((int) seatLimit);
        p.setPriceMinor(priceMinor);
        p.setCurrency("INR");
        p.setBillingPeriod(BillingPeriod.MONTHLY);
        p.setRazorpayPlanId(null);
        p.setActive(true);
        p.setCustomPricing(customPricing);
        p.setCreatedAt(Instant.now());
        planRepo.save(p);
    }
}
