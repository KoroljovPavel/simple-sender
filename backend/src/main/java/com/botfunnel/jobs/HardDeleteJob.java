package com.botfunnel.jobs;

import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

// JobRunr resolves @Recurring at startup and registers it with the recurring scheduler.
// "0 3 * * *" = 5-field Unix cron (minute=0, hour=3, daily). JobRunr 7.3.x supports both
// 5-field and 6-field forms (see RecurringJobBuilder.withCron source). The cron is UTC unless
// zoneId is set — operationally fine; daily window has no DST sensitivity.
@Component
public class HardDeleteJob {

    private static final Logger log = LoggerFactory.getLogger(HardDeleteJob.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final UserRepository userRepository;

    public HardDeleteJob(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Recurring(id = "hard-delete-users", cron = "0 3 * * *")
    @Job(name = "Hard delete soft-deleted users")
    public void hardDeleteSoftDeletedUsers() {
        // AC line 81: deletedAt <= now - 30d. The repository finder is `Before` (strict <).
        // Bumping the cutoff by 1 nanosecond converts the strict comparison to inclusive at the
        // exact-30-days boundary so a user soft-deleted 30 days ago to the second is removed
        // on the next run (instead of surviving one more day).
        Instant cutoff = Instant.now().minus(RETENTION).plusNanos(1);
        // .collectList() materialises the candidate set so the IDs can be logged BEFORE deletion
        // (GDPR audit trail per tech-spec line 95). The set is naturally bounded by the daily
        // job cadence × 30-day retention window — no streaming needed at this volume.
        List<User> users = userRepository.findByStatusAndDeletedAtBefore(UserStatus.deleted, cutoff)
                .collectList()
                .blockOptional()
                .orElseGet(List::of);
        if (users.isEmpty()) {
            log.info("Hard-delete job: 0 users removed (cutoff={})", cutoff);
            return;
        }
        List<String> ids = users.stream().map(User::getId).collect(Collectors.toList());
        log.info("Hard-delete job: removing {} users (ids={}, cutoff={})", ids.size(), ids, cutoff);
        userRepository.deleteAll(users).block();
    }
}
