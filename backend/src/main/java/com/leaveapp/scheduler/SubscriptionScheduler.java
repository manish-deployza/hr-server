package com.leaveapp.scheduler;

// import flow: SubscriptionScheduler -> SubscriptionService (what's due) -> per-row transaction
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.leaveapp.service.SubscriptionService;

// Two independent polls: a catch-all for a missed webhook (reconcile), and downgrading
// an org whose grace window has lapsed (expireGrace). Same single-instance reasoning as
// scheduler.EmailSender (one WAR, one Tomcat) -- no leader lease needed.
@Component
public class SubscriptionScheduler {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    @Autowired
    private SubscriptionService subSvc;

    @Value("${app.billing.reconcile.batch-size}")
    private int reconcileBatchSize;

    @Value("${app.billing.grace-expiry.batch-size}")
    private int graceBatchSize;

    /* Public Methods */

    @Scheduled(fixedDelayString = "${app.billing.reconcile.poll-delay-ms}")
    public void reconcile() {
        List<Long> ids = subSvc.findReconcilableIds(reconcileBatchSize);
        for (Long id : ids) {
            try {
                subSvc.reconcileOne(id);
            } catch (Exception e) {
                log.error("billing reconcile error subscriptionId={}", id, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.billing.grace-expiry.poll-delay-ms}")
    public void expireGrace() {
        List<Long> ids = subSvc.findGraceExpiredIds(graceBatchSize);
        for (Long id : ids) {
            try {
                subSvc.expireGraceOne(id);
            } catch (Exception e) {
                log.error("billing grace-expiry error subscriptionId={}", id, e);
            }
        }
    }
}
