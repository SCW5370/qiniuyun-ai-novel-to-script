package com.novel2script.backend.workflow;

/**
 * 投递到 RabbitMQ 的工作流任务消息。
 */
public class WorkflowJobMessage {

    private String jobId;

    private String projectId;

    private String jobType;

    public WorkflowJobMessage() {
    }

    public WorkflowJobMessage(String jobId, String projectId, String jobType) {
        this.jobId = jobId;
        this.projectId = projectId;
        this.jobType = jobType;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }
}
