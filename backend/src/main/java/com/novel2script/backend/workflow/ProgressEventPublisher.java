package com.novel2script.backend.workflow;

import com.novel2script.backend.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 项目级 SSE 事件发布器。只推送阶段、数量和状态，不推送正文、Prompt、AI 原始响应或密钥。
 */
@Component
public class ProgressEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProgressEventPublisher.class);

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Project project) {
        String projectId = project.getProjectId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(projectId, emitter));
        emitter.onTimeout(() -> remove(projectId, emitter));
        emitter.onError(ignored -> remove(projectId, emitter));

        safeSend(projectId, emitter, "phase.changed", Map.of(
                "projectId", projectId,
                "phase", project.getStatus().name().toLowerCase(),
                "progress", progressOf(project.getStatus().name()),
                "message", "已连接项目实时进度流"
        ));
        return emitter;
    }

    public void jobStarted(String projectId, String jobType, String phase, int progress, String message) {
        publish(projectId, "job.started", Map.of(
                "projectId", projectId,
                "jobType", jobType,
                "phase", phase,
                "progress", progress,
                "message", message
        ));
    }

    public void phaseChanged(String projectId, String phase, int progress, String message) {
        publish(projectId, "phase.changed", Map.of(
                "projectId", projectId,
                "phase", phase,
                "progress", progress,
                "message", message
        ));
    }

    public void outlineReady(String projectId, int sceneCount) {
        publish(projectId, "outline.ready", Map.of(
                "projectId", projectId,
                "phase", "outlined",
                "progress", 60,
                "sceneCount", sceneCount,
                "message", "场景大纲已生成"
        ));
    }

    public void sceneDone(String projectId, String sceneId, String validationStatus) {
        publish(projectId, "scene.done", Map.of(
                "projectId", projectId,
                "phase", "scene_generating",
                "progress", 78,
                "sceneId", sceneId,
                "validationStatus", validationStatus,
                "message", "Scene 生成完成: " + sceneId
        ));
    }

    public void validationWarn(String projectId, String sceneId, String field, String message) {
        publish(projectId, "validation.warn", Map.of(
                "projectId", projectId,
                "phase", "validating",
                "progress", 88,
                "sceneId", sceneId,
                "field", field,
                "message", message
        ));
    }

    public void jobCompleted(String projectId, String phase, int progress, boolean exportReady, String message) {
        publish(projectId, "job.completed", Map.of(
                "projectId", projectId,
                "phase", phase,
                "progress", progress,
                "exportReady", exportReady,
                "message", message
        ));
    }

    public void jobFailed(String projectId, String phase, String errorCode, String message) {
        publish(projectId, "job.failed", Map.of(
                "projectId", projectId,
                "phase", phase,
                "progress", 100,
                "errorCode", errorCode,
                "message", message
        ));
    }

    private void publish(String projectId, String eventName, Map<String, Object> payload) {
        List<SseEmitter> projectEmitters = emitters.get(projectId);
        if (projectEmitters == null || projectEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : projectEmitters) {
            safeSend(projectId, emitter, eventName, payload);
        }
    }

    private void safeSend(String projectId, SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException | IllegalStateException ex) {
            remove(projectId, emitter);
            completeQuietly(emitter);
            log.debug("SSE 连接已移除: projectId={}, event={}", projectId, eventName, ex);
        }
    }

    private void remove(String projectId, SseEmitter emitter) {
        List<SseEmitter> projectEmitters = emitters.get(projectId);
        if (projectEmitters == null) {
            return;
        }
        projectEmitters.remove(emitter);
        if (projectEmitters.isEmpty()) {
            emitters.remove(projectId);
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 连接已经关闭时无需继续处理，避免断连异常影响业务接口。
        }
    }

    private int progressOf(String status) {
        return switch (status) {
            case "CREATED" -> 5;
            case "SOURCE_SUBMITTED" -> 18;
            case "CHAPTERED" -> 30;
            case "ENTITY_READY" -> 45;
            case "OUTLINED" -> 60;
            case "SCENE_GENERATING" -> 78;
            case "COMPLETED", "FAILED" -> 100;
            default -> 0;
        };
    }
}
