package com.finsight.workflow;

import java.time.Duration;
import java.util.Optional;

public interface WorkflowLeaseService {
    Optional<WorkflowLease> tryAcquire(String key, Duration ttl);

    Optional<WorkflowLease> renew(WorkflowLease lease, Duration ttl);

    void release(WorkflowLease lease);
}
