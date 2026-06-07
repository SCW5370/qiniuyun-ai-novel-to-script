package com.novel2script.backend.workflow;

import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.scene.SceneGenerationService;
import com.novel2script.backend.story.StoryAnalysisService;
import com.novel2script.backend.workflow.dto.WorkflowJobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowJobService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJobService.class);

    private final StoryAnalysisService storyAnalysisService;

    private final SceneGenerationService sceneGenerationService;

    private final ProjectService projectService;

    private final ProgressEventPublisher progressEventPublisher;

    private final RabbitTemplate rabbitTemplate;

    private final String exchangeName;

    private final String routingKey;

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public WorkflowJobService(
            StoryAnalysisService storyAnalysisService,
            SceneGenerationService sceneGenerationService,
            ProjectService projectService,
            ProgressEventPublisher progressEventPublisher,
            RabbitTemplate rabbitTemplate,
            @Value("${WORKFLOW_JOB_EXCHANGE:novel2script.workflow}") String exchangeName,
            @Value("${WORKFLOW_JOB_ROUTING_KEY:workflow.job}") String routingKey
    ) {
        this.storyAnalysisService = storyAnalysisService;
        this.sceneGenerationService = sceneGenerationService;
        this.projectService = projectService;
        this.progressEventPublisher = progressEventPublisher;
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.routingKey = routingKey;
    }

    public WorkflowJobResponse submitStoryAnalysis(String projectId) {
        return submit(projectId, "story_analysis_async", "故事资产分析任务已提交到 MQ");
    }

    public WorkflowJobResponse submitIncrementalStoryAnalysis(String projectId) {
        return submit(projectId, "story_analysis_incremental_async", "增量故事资产分析任务已提交到 MQ");
    }

    public WorkflowJobResponse submitOutlineGeneration(String projectId) {
        return submit(projectId, "outline_generation_async", "场景大纲生成任务已提交到 MQ");
    }

    public WorkflowJobResponse submitIncrementalOutlineGeneration(String projectId) {
        return submit(projectId, "outline_generation_incremental_async", "增量场景大纲生成任务已提交到 MQ");
    }

    public WorkflowJobResponse getJob(String projectId, String jobId) {
        JobState job = jobs.get(jobId);
        if (job == null || !projectId.equals(job.projectId())) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        return job.toResponse();
    }

    private WorkflowJobResponse submit(String projectId, String jobType, String submitMessage) {
        projectService.getProjectEntity(projectId);
        String jobId = "job_" + UUID.randomUUID().toString().replace("-", "");
        JobState job = new JobState(jobId, projectId, jobType, "QUEUED", submitMessage, LocalDateTime.now(), LocalDateTime.now());
        jobs.put(jobId, job);
        progressEventPublisher.jobStarted(projectId, jobType, "queued", 1, submitMessage);
        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, new WorkflowJobMessage(jobId, projectId, jobType));
            log.info("异步任务已投递 MQ: projectId={}, jobId={}, jobType={}", projectId, jobId, jobType);
        } catch (Exception ex) {
            String message = rootCauseMessage(ex);
            update(jobId, "FAILED", "任务投递 MQ 失败: " + message);
            progressEventPublisher.jobFailed(projectId, "job_failed", jobType, "任务投递 MQ 失败: " + message);
            throw new IllegalStateException("任务投递 MQ 失败: " + message, ex);
        }
        return job.toResponse();
    }

    @RabbitListener(queues = "${WORKFLOW_JOB_QUEUE:novel2script.workflow.jobs}")
    public void consume(WorkflowJobMessage message) {
        runJob(message);
    }

    private void runJob(WorkflowJobMessage message) {
        String jobId = message.getJobId();
        JobState job = jobs.get(jobId);
        if (job == null) {
            job = new JobState(
                    message.getJobId(),
                    message.getProjectId(),
                    message.getJobType(),
                    "RUNNING",
                    "任务由 MQ 消费端恢复执行",
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            jobs.put(jobId, job);
        }
        if (!job.projectId().equals(message.getProjectId()) || !job.jobType().equals(message.getJobType())) {
            log.warn(
                    "忽略不一致的 MQ 任务消息: jobId={}, stateProjectId={}, messageProjectId={}, stateJobType={}, messageJobType={}",
                    jobId,
                    job.projectId(),
                    message.getProjectId(),
                    job.jobType(),
                    message.getJobType()
            );
            return;
        }
        try {
            update(jobId, "RUNNING", "任务已由 MQ 消费端开始执行");
            progressEventPublisher.phaseChanged(job.projectId(), "job_running", 3, job.jobType() + " 已由 MQ 消费端开始执行");
            executeJob(job);
            update(jobId, "SUCCEEDED", "任务执行完成");
            progressEventPublisher.jobCompleted(job.projectId(), "job_finished", 100, false, job.jobType() + " 执行完成");
            log.info("异步任务执行完成: projectId={}, jobId={}, jobType={}", job.projectId(), jobId, job.jobType());
        } catch (Exception ex) {
            String failureMessage = rootCauseMessage(ex);
            update(jobId, "FAILED", failureMessage);
            progressEventPublisher.jobFailed(job.projectId(), "job_failed", job.jobType(), failureMessage);
            log.warn("异步任务执行失败: projectId={}, jobId={}, jobType={}, reason={}", job.projectId(), jobId, job.jobType(), failureMessage);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(failureMessage, ex);
        }
    }

    private void executeJob(JobState job) {
        switch (job.jobType()) {
            case "story_analysis_async" -> storyAnalysisService.analyze(job.projectId());
            case "story_analysis_incremental_async" -> storyAnalysisService.analyzeIncremental(job.projectId());
            case "outline_generation_async" -> sceneGenerationService.listOutline(job.projectId());
            case "outline_generation_incremental_async" -> sceneGenerationService.generateIncrementalOutline(job.projectId());
            default -> throw new IllegalArgumentException("不支持的任务类型: " + job.jobType());
        }
    }

    private void update(String jobId, String status, String message) {
        jobs.computeIfPresent(jobId, (ignored, current) -> current.withStatus(status, message));
    }

    private String rootCauseMessage(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private record JobState(
            String jobId,
            String projectId,
            String jobType,
            String status,
            String message,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        private JobState withStatus(String nextStatus, String nextMessage) {
            return new JobState(jobId, projectId, jobType, nextStatus, nextMessage, createdAt, LocalDateTime.now());
        }

        private WorkflowJobResponse toResponse() {
            return new WorkflowJobResponse(jobId, projectId, jobType, status, message, createdAt, updatedAt);
        }
    }
}
