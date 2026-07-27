package com.finsight.workflow;

public interface WorkflowTaskPublisher {
    void publish(WorkflowTask task);

    default void publishRetry(WorkflowTask task) {
        publish(task);
    }
}
